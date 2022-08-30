/*
 *    Copyright 2009-2014 the original author or authors.
 *
 *    Licensed under the Apache License, Version 2.0 (the "License");
 *    you may not use this file except in compliance with the License.
 *    You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 *    Unless required by applicable law or agreed to in writing, software
 *    distributed under the License is distributed on an "AS IS" BASIS,
 *    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *    See the License for the specific language governing permissions and
 *    limitations under the License.
 */
package org.apache.ibatis.builder.xml;

import org.apache.ibatis.builder.BaseBuilder;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.apache.ibatis.executor.keygen.Jdbc3KeyGenerator;
import org.apache.ibatis.executor.keygen.KeyGenerator;
import org.apache.ibatis.executor.keygen.NoKeyGenerator;
import org.apache.ibatis.executor.keygen.SelectKeyGenerator;
import org.apache.ibatis.mapping.*;
import org.apache.ibatis.parsing.XNode;
import org.apache.ibatis.scripting.LanguageDriver;
import org.apache.ibatis.session.Configuration;

import java.util.List;
import java.util.Locale;

/**
 * @author Clinton Begin
 */

/**
 * XML语句构建器，建造者模式,继承BaseBuilder
 */
public class XMLStatementBuilder extends BaseBuilder {

    private MapperBuilderAssistant builderAssistant;
    private XNode context;
    private String requiredDatabaseId;

    public XMLStatementBuilder(Configuration configuration, MapperBuilderAssistant builderAssistant, XNode context) {
        this(configuration, builderAssistant, context, null);
    }

    public XMLStatementBuilder(Configuration configuration, MapperBuilderAssistant builderAssistant, XNode context, String databaseId) {
        super(configuration);
        this.builderAssistant = builderAssistant;
        this.context = context;
        this.requiredDatabaseId = databaseId;
    }

    //解析语句(select|insert|update|delete)
//<select
//  id="selectPerson"
//  parameterType="int"
//  parameterMap="deprecated"
//  resultType="hashmap"
//  resultMap="personResultMap"
//  flushCache="false"
//  useCache="true"
//  timeout="10000"
//  fetchSize="256"
//  statementType="PREPARED"
//  resultSetType="FORWARD_ONLY">
//  SELECT * FROM PERSON WHERE ID = #{id}
//</select>
    public void parseStatementNode() {
        // 获取SQL节点的id以及databaseId属性
        String id = context.getStringAttribute("id");
        String databaseId = context.getStringAttribute("databaseId");

        //如果id和databaseId不匹配或存在相同id且databaseId不为空的SQL节点,则不再加载
        if (!databaseIdMatchesCurrent(id, databaseId, this.requiredDatabaseId)) {
            return;
        }

        //暗示驱动程序每次批量返回的结果行数
        Integer fetchSize = context.getIntAttribute("fetchSize");
        //超时时间
        Integer timeout = context.getIntAttribute("timeout");
        //引用外部 parameterMap,已废弃
        String parameterMap = context.getStringAttribute("parameterMap");
        //参数类型
        String parameterType = context.getStringAttribute("parameterType");
        Class<?> parameterTypeClass = resolveClass(parameterType);
        //引用外部的 resultMap(高级功能)
        String resultMap = context.getStringAttribute("resultMap");
        //结果类型
        String resultType = context.getStringAttribute("resultType");
        //脚本语言,mybatis3.2的新功能
        String lang = context.getStringAttribute("lang");
        //得到语言驱动
        LanguageDriver langDriver = getLanguageDriver(lang);

        Class<?> resultTypeClass = resolveClass(resultType);
        //结果集类型，FORWARD_ONLY|SCROLL_SENSITIVE|SCROLL_INSENSITIVE 中的一种
        String resultSetType = context.getStringAttribute("resultSetType");
        //语句类型, STATEMENT|PREPARED|CALLABLE 的一种
        StatementType statementType = StatementType.valueOf(context.getStringAttribute("statementType", StatementType.PREPARED.toString()));
        ResultSetType resultSetTypeEnum = resolveResultSetType(resultSetType);

        // 根据SQL节点的名称 决定其SqlCommandType
        String nodeName = context.getNode().getNodeName();
        SqlCommandType sqlCommandType = SqlCommandType.valueOf(nodeName.toUpperCase(Locale.ENGLISH));
        boolean isSelect = sqlCommandType == SqlCommandType.SELECT;
        boolean flushCache = context.getBooleanAttribute("flushCache", !isSelect);
        //是否要缓存select结果
        boolean useCache = context.getBooleanAttribute("useCache", isSelect);
        //仅针对嵌套结果 select 语句适用：如果为 true，就是假设包含了嵌套结果集或是分组了，这样的话当返回一个主结果行的时候，就不会发生有对前面结果集的引用的情况。
        //这就使得在获取嵌套的结果集的时候不至于导致内存不够用。默认值：false。
        boolean resultOrdered = context.getBooleanAttribute("resultOrdered", false);

        // Include Fragments before parsing
        //解析之前先解析<include>SQL片段
        XMLIncludeTransformer includeParser = new XMLIncludeTransformer(configuration, builderAssistant);
        includeParser.applyIncludes(context.getNode());

        // Parse selectKey after includes and remove them.
        // 处理<selectKey>节点
        processSelectKeyNodes(id, parameterTypeClass, langDriver);

        // ----------接下来真正的SQL节点的解析,也是parseStatementNode()方法的核心--------------
        // Parse the SQL (pre: <selectKey> and <include> were parsed and removed)
        //解析成SqlSource，一般是DynamicSqlSource
        //调用LanguageDriver.createSqlSource()方法创建SqlSource对象
        SqlSource sqlSource = langDriver.createSqlSource(configuration, context, parameterTypeClass);
        // 获取resultSets,keyProperty,keyColumn三个属性
        String resultSets = context.getStringAttribute("resultSets");
        //(仅对 insert 有用) 标记一个属性, MyBatis 会通过 getGeneratedKeys 或者通过 insert 语句的 selectKey 子元素设置它的值
        String keyProperty = context.getStringAttribute("keyProperty");
        //(仅对 insert 有用) 标记一个属性, MyBatis 会通过 getGeneratedKeys 或者通过 insert 语句的 selectKey 子元素设置它的值
        String keyColumn = context.getStringAttribute("keyColumn");
        KeyGenerator keyGenerator;
        // 获取<selectKey>节点对应的SelectKeyGenerator的id
        String keyStatementId = id + SelectKeyGenerator.SELECT_KEY_SUFFIX;
        keyStatementId = builderAssistant.applyCurrentNamespace(keyStatementId, true);
        // 这里会检测SQL节点是否配置了<selectKey>节点, SQL节点的useGeneratedkeys属性值, mybatis-config.xml 中全局的
        // useGeneratedKeys 配置, 以及是否为 insert 语句, 决定使用的 KeyGenerator 接口实现.
        if (configuration.hasKeyGenerator(keyStatementId)) {
            keyGenerator = configuration.getKeyGenerator(keyStatementId);
        } else {
            keyGenerator = context.getBooleanAttribute("useGeneratedKeys",
                    configuration.isUseGeneratedKeys() && SqlCommandType.INSERT.equals(sqlCommandType))
                    ? new Jdbc3KeyGenerator() : new NoKeyGenerator();
        }

        // 通过 MapperBuilderAssistant创建 MappedStatement对象, 并添加到
        // Configuration.mappedStatements 集合中保存
        builderAssistant.addMappedStatement(id, sqlSource, statementType, sqlCommandType,
                fetchSize, timeout, parameterMap, parameterTypeClass, resultMap, resultTypeClass,
                resultSetTypeEnum, flushCache, useCache, resultOrdered,
                keyGenerator, keyProperty, keyColumn, databaseId, langDriver, resultSets);
    }


    private void processSelectKeyNodes(String id, Class<?> parameterTypeClass, LanguageDriver langDriver) {
        // 获取全部的<selectKey>节点
        List<XNode> selectKeyNodes = context.evalNodes("selectKey");
        // 解析<selectKey>节点
        if (configuration.getDatabaseId() != null) {
            parseSelectKeyNodes(id, selectKeyNodes, parameterTypeClass, langDriver, configuration.getDatabaseId());
        }
        parseSelectKeyNodes(id, selectKeyNodes, parameterTypeClass, langDriver, null);
        // 移除<selectKey>节点
        removeSelectKeyNodes(selectKeyNodes);
    }
    /**
     * 为<selectKey>节点生成id,检测databaseId是否匹配以及是否已经加载过相同id且
     * databaseId不为空的<selectKey>节点,并调用parseSelectKeyNode()方法处理每个<selectKey>节点
     */
    private void parseSelectKeyNodes(String parentId, List<XNode> list, Class<?> parameterTypeClass, LanguageDriver langDriver, String skRequiredDatabaseId) {
        for (XNode nodeToHandle : list) {
            String id = parentId + SelectKeyGenerator.SELECT_KEY_SUFFIX;
            String databaseId = nodeToHandle.getStringAttribute("databaseId");
            if (databaseIdMatchesCurrent(id, databaseId, skRequiredDatabaseId)) {
                parseSelectKeyNode(id, nodeToHandle, parameterTypeClass, langDriver, databaseId);
            }
        }
    }

    private void parseSelectKeyNode(String id, XNode nodeToHandle, Class<?> parameterTypeClass, LanguageDriver langDriver, String databaseId) {
        // 属性获取和设置
        String resultType = nodeToHandle.getStringAttribute("resultType");
        Class<?> resultTypeClass = resolveClass(resultType);
        StatementType statementType = StatementType.valueOf(nodeToHandle.getStringAttribute("statementType", StatementType.PREPARED.toString()));
        String keyProperty = nodeToHandle.getStringAttribute("keyProperty");
        String keyColumn = nodeToHandle.getStringAttribute("keyColumn");
        boolean executeBefore = "BEFORE".equals(nodeToHandle.getStringAttribute("order", "AFTER"));

        //defaults
        boolean useCache = false;
        boolean resultOrdered = false;
        KeyGenerator keyGenerator = new NoKeyGenerator();
        Integer fetchSize = null;
        Integer timeout = null;
        boolean flushCache = false;
        String parameterMap = null;
        String resultMap = null;
        ResultSetType resultSetTypeEnum = null;

        // 通过LanguageDriver.createSqlSource()方法生成SqlSource
        SqlSource sqlSource = langDriver.createSqlSource(configuration, nodeToHandle, parameterTypeClass);
        // <selectKey>只能配置select语句
        SqlCommandType sqlCommandType = SqlCommandType.SELECT;

        // 通过MapperBuilderAssistant创建 MappedStatement对象 并添加到
        // Configuration.mappedStatements集合中保存, 该集合为StrictMap<MappedStatement>类型
        builderAssistant.addMappedStatement(id, sqlSource, statementType, sqlCommandType,
                fetchSize, timeout, parameterMap, parameterTypeClass, resultMap, resultTypeClass,
                resultSetTypeEnum, flushCache, useCache, resultOrdered,
                keyGenerator, keyProperty, keyColumn, databaseId, langDriver, null);

        id = builderAssistant.applyCurrentNamespace(id, false);

        MappedStatement keyStatement = configuration.getMappedStatement(id, false);
        // 创建<selectKey>节点对应的KeyGenerator, 添加到Configuration.keyGenerators集合中保存,
        // Configuration.keyGenerators字段是StrictMap<KeyGenerator>类型的对象
        configuration.addKeyGenerator(id, new SelectKeyGenerator(keyStatement, executeBefore));
    }

    private void removeSelectKeyNodes(List<XNode> selectKeyNodes) {
        for (XNode nodeToHandle : selectKeyNodes) {
            nodeToHandle.getParent().getNode().removeChild(nodeToHandle.getNode());
        }
    }

    private boolean databaseIdMatchesCurrent(String id, String databaseId, String requiredDatabaseId) {
        if (requiredDatabaseId != null) {
            if (!requiredDatabaseId.equals(databaseId)) {
                return false;
            }
        } else {
            if (databaseId != null) {
                return false;
            }
            // skip this statement if there is a previous one with a not null databaseId
            id = builderAssistant.applyCurrentNamespace(id, false);
            if (this.configuration.hasStatement(id, false)) {
                MappedStatement previous = this.configuration.getMappedStatement(id, false); // issue #2
                if (previous.getDatabaseId() != null) {
                    return false;
                }
            }
        }
        return true;
    }

    //取得语言驱动
    private LanguageDriver getLanguageDriver(String lang) {
        Class<?> langClass = null;
        if (lang != null) {
            langClass = resolveClass(lang);
        }
        //调用builderAssistant
        return builderAssistant.getLanguageDriver(langClass);
    }

}
