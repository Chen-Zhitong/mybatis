/*
 *    Copyright 2009-2013 the original author or authors.
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

import org.apache.ibatis.builder.*;
import org.apache.ibatis.cache.Cache;
import org.apache.ibatis.executor.ErrorContext;
import org.apache.ibatis.io.Resources;
import org.apache.ibatis.mapping.*;
import org.apache.ibatis.parsing.XNode;
import org.apache.ibatis.parsing.XPathParser;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.type.JdbcType;
import org.apache.ibatis.type.TypeHandler;

import java.io.InputStream;
import java.io.Reader;
import java.util.*;

/**
 * @author Clinton Begin
 */

/**
 * XML映射构建器，建造者模式,继承BaseBuilder
 */
public class XMLMapperBuilder extends BaseBuilder {

    private XPathParser parser;
    //映射器构建助手
    private MapperBuilderAssistant builderAssistant;
    //用来存放sql片段的哈希表
    private Map<String, XNode> sqlFragments;
    private String resource;

    @Deprecated
    public XMLMapperBuilder(Reader reader, Configuration configuration, String resource, Map<String, XNode> sqlFragments, String namespace) {
        this(reader, configuration, resource, sqlFragments);
        this.builderAssistant.setCurrentNamespace(namespace);
    }

    @Deprecated
    public XMLMapperBuilder(Reader reader, Configuration configuration, String resource, Map<String, XNode> sqlFragments) {
        this(new XPathParser(reader, true, configuration.getVariables(), new XMLMapperEntityResolver()),
                configuration, resource, sqlFragments);
    }

    public XMLMapperBuilder(InputStream inputStream, Configuration configuration, String resource, Map<String, XNode> sqlFragments, String namespace) {
        this(inputStream, configuration, resource, sqlFragments);
        this.builderAssistant.setCurrentNamespace(namespace);
    }

    public XMLMapperBuilder(InputStream inputStream, Configuration configuration, String resource, Map<String, XNode> sqlFragments) {
        this(new XPathParser(inputStream, true, configuration.getVariables(), new XMLMapperEntityResolver()),
                configuration, resource, sqlFragments);
    }

    private XMLMapperBuilder(XPathParser parser, Configuration configuration, String resource, Map<String, XNode> sqlFragments) {
        super(configuration);
        this.builderAssistant = new MapperBuilderAssistant(configuration, resource);
        this.parser = parser;
        this.sqlFragments = sqlFragments;
        this.resource = resource;
    }

    //解析
    public void parse() {
        //如果没有加载过再加载，防止重复加载
        if (!configuration.isResourceLoaded(resource)) {
            // 处理mapper节点
            configurationElement(parser.evalNode("/mapper"));
            // 将resource添加到Configuration.loadedResources集合中保存, 它是
            // HashSet<String>类型的耳机和, 其中记录了已经加载过的映射文件
            configuration.addLoadedResource(resource);
            // 注册Mapper接口
            bindMapperForNamespace();
        }

        // 处理 configurationElement()方法中解析失败的<resultMap>节点
        parsePendingResultMaps();
        // 处理configurationElement() 方法中解析失败的<cache-ref>节点
        parsePendingChacheRefs();
        // 处理configurationElement() 方法中解析失败的SQL语句节点
        parsePendingStatements();
    }

    public XNode getSqlFragment(String refid) {
        return sqlFragments.get(refid);
    }

    //配置mapper元素
//	<mapper namespace="org.mybatis.example.BlogMapper">
//	  <select id="selectBlog" parameterType="int" resultType="Blog">
//	    select * from Blog where id = #{id}
//	  </select>
//	</mapper>
    private void configurationElement(XNode context) {
        try {
            // 获取<mapper>节点的namespace属性
            String namespace = context.getStringAttribute("namespace");
            // 如果 namespace属性为空, 则抛出异常
            if (namespace.equals("")) {
                throw new BuilderException("Mapper's namespace cannot be empty");
            }
            // 设置 MapperBuilderAssistant的currentNamespace字段, 记录当前命名空间
            builderAssistant.setCurrentNamespace(namespace);
            //2.解析cache-ref节点
            cacheRefElement(context.evalNode("cache-ref"));
            //3.解析cache 节点
            cacheElement(context.evalNode("cache"));
            //4.配置parameterMap(已经废弃,老式风格的参数映射)
            parameterMapElement(context.evalNodes("/mapper/parameterMap"));
            //5.配置resultMap(高级功能)
            resultMapElements(context.evalNodes("/mapper/resultMap"));
            //6.配置sql(定义可重用的 SQL 代码段)
            sqlElement(context.evalNodes("/mapper/sql"));
            //7.配置select|insert|update|delete节点, (构建SQL语句)
            buildStatementFromContext(context.evalNodes("select|insert|update|delete"));
        } catch (Exception e) {
            throw new BuilderException("Error parsing Mapper XML. Cause: " + e, e);
        }
    }

    //7.配置select|insert|update|delete
    private void buildStatementFromContext(List<XNode> list) {
        //调用7.1构建语句
        if (configuration.getDatabaseId() != null) {
            buildStatementFromContext(list, configuration.getDatabaseId());
        }
        buildStatementFromContext(list, null);
    }

    //7.1构建语句
    private void buildStatementFromContext(List<XNode> list, String requiredDatabaseId) {
        for (XNode context : list) {
            //构建所有语句,一个mapper下可以有很多select
            //语句比较复杂，核心都在这里面，所以调用XMLStatementBuilder
            final XMLStatementBuilder statementParser = new XMLStatementBuilder(configuration, builderAssistant, context, requiredDatabaseId);
            try {
                //核心XMLStatementBuilder.parseStatementNode
                statementParser.parseStatementNode();
            } catch (IncompleteElementException e) {
                //如果出现SQL语句不完整，把它记下来，塞到configuration去
                configuration.addIncompleteStatement(statementParser);
            }
        }
    }

    private void parsePendingResultMaps() {
        Collection<ResultMapResolver> incompleteResultMaps = configuration.getIncompleteResultMaps();
        synchronized (incompleteResultMaps) {
            Iterator<ResultMapResolver> iter = incompleteResultMaps.iterator();
            while (iter.hasNext()) {
                try {
                    iter.next().resolve();
                    iter.remove();
                } catch (IncompleteElementException e) {
                    // ResultMap is still missing a resource...
                }
            }
        }
    }

    private void parsePendingChacheRefs() {
        Collection<CacheRefResolver> incompleteCacheRefs = configuration.getIncompleteCacheRefs();
        synchronized (incompleteCacheRefs) {
            Iterator<CacheRefResolver> iter = incompleteCacheRefs.iterator();
            while (iter.hasNext()) {
                try {
                    iter.next().resolveCacheRef();
                    iter.remove();
                } catch (IncompleteElementException e) {
                    // Cache ref is still missing a resource...
                }
            }
        }
    }

    private void parsePendingStatements() {
        // 获取Configuration.incompleteStatements集合
        Collection<XMLStatementBuilder> incompleteStatements = configuration.getIncompleteStatements();
        //加锁同步
        synchronized (incompleteStatements) {
            // 遍历incompleteStaements集合
            Iterator<XMLStatementBuilder> iter = incompleteStatements.iterator();
            while (iter.hasNext()) {
                try {
                    // 重新解析SQL语句节点
                    iter.next().parseStatementNode();
                    // 移除XMLStatementBuilder对象
                    iter.remove();
                } catch (IncompleteElementException e) {
                    // Statement is still missing a resource...
                }
            }
        }
    }

    /**
     * 如果我们希望多个namespace共用同一个二级缓存,即同一个Cache对象,则可以使用<cache-ref>节点进行配置
     *
     * @param context
     */
    //2.配置cache-ref,在这样的 情况下你可以使用 cache-ref 元素来引用另外一个缓存。
//<cache-ref namespace="com.someone.application.data.SomeMapper"/>
    private void cacheRefElement(XNode context) {
        if (context != null) {
            // 将当前Mapper配置文件的 namespace 与被引用的 Cache 所在的 namespace 之间的对应关系,
            // 记录到Configuration.cacheRefMap 集合中
            configuration.addCacheRef(builderAssistant.getCurrentNamespace(), context.getStringAttribute("namespace"));
            // 创建CacheRefResolver对象
            CacheRefResolver cacheRefResolver = new CacheRefResolver(builderAssistant, context.getStringAttribute("namespace"));
            try {
                // 解析Cache引用,该过程主要是设置mapperBuilderAssistant中的
                // currentCache和unresolvedCacheRef字段
                cacheRefResolver.resolveCacheRef();
            } catch (IncompleteElementException e) {
                // 如果解析过程中出现异常,则添加到 IncompleteCacheRefs 集合中
                configuration.addIncompleteCacheRef(cacheRefResolver);
            }
        }
    }

    //3.配置cache
//  <cache
//  eviction="FIFO"
//  flushInterval="60000"
//  size="512"
//  readOnly="true"/>
    private void cacheElement(XNode context) throws Exception {
        if (context != null) {
            // 获取<cache>节点的type属性, 默认是 PERPETUAL( 永恒的)
            String type = context.getStringAttribute("type", "PERPETUAL");
            // 查找 type 属性对应的Cache接口实现
            Class<? extends Cache> typeClass = typeAliasRegistry.resolveAlias(type);
            // 获取<cache>节点的eviction属性,默认值是null
            String eviction = context.getStringAttribute("eviction", "LRU");
            // 即系 eviction 属性指定的Cache装饰器类型
            Class<? extends Cache> evictionClass = typeAliasRegistry.resolveAlias(eviction);
            // 获取<cache>节点的 flushInterval 属性 默认是null
            Long flushInterval = context.getLongAttribute("flushInterval");
            // 获取<cahce>节点的size属性, 默认是 null
            Integer size = context.getIntAttribute("size");
            // 获取<cahce>节点的readOnly属性, 默认是false
            boolean readWrite = !context.getBooleanAttribute("readOnly", false);
            // 获取<cahce>节点的blocking属性, 默认是false
            boolean blocking = context.getBooleanAttribute("blocking", false);
            //读入额外的配置信息，易于第三方的缓存扩展,例:
//    <cache type="com.domain.something.MyCustomCache">
//      <property name="cacheFile" value="/tmp/my-custom-cache.tmp"/>
//    </cache>
            // 获取<cahce>节点下的子节点,将用于初始化二级缓存
            Properties props = context.getChildrenAsProperties();
            // 通过 MapperBuilderAssistant创建Cache对象,并添加到 Configuration.caches 集合中保存
            builderAssistant.useNewCache(typeClass, evictionClass, flushInterval, size, readWrite, blocking, props);
        }
    }

    //4.配置parameterMap
    //已经被废弃了!老式风格的参数映射。可以忽略
    private void parameterMapElement(List<XNode> list) throws Exception {
        for (XNode parameterMapNode : list) {
            String id = parameterMapNode.getStringAttribute("id");
            String type = parameterMapNode.getStringAttribute("type");
            Class<?> parameterClass = resolveClass(type);
            List<XNode> parameterNodes = parameterMapNode.evalNodes("parameter");
            List<ParameterMapping> parameterMappings = new ArrayList<ParameterMapping>();
            for (XNode parameterNode : parameterNodes) {
                String property = parameterNode.getStringAttribute("property");
                String javaType = parameterNode.getStringAttribute("javaType");
                String jdbcType = parameterNode.getStringAttribute("jdbcType");
                String resultMap = parameterNode.getStringAttribute("resultMap");
                String mode = parameterNode.getStringAttribute("mode");
                String typeHandler = parameterNode.getStringAttribute("typeHandler");
                Integer numericScale = parameterNode.getIntAttribute("numericScale");
                ParameterMode modeEnum = resolveParameterMode(mode);
                Class<?> javaTypeClass = resolveClass(javaType);
                JdbcType jdbcTypeEnum = resolveJdbcType(jdbcType);
                @SuppressWarnings("unchecked")
                Class<? extends TypeHandler<?>> typeHandlerClass = (Class<? extends TypeHandler<?>>) resolveClass(typeHandler);
                ParameterMapping parameterMapping = builderAssistant.buildParameterMapping(parameterClass, property, javaTypeClass, jdbcTypeEnum, resultMap, modeEnum, typeHandlerClass, numericScale);
                parameterMappings.add(parameterMapping);
            }
            builderAssistant.addParameterMap(id, parameterClass, parameterMappings);
        }
    }

    //5.配置resultMap,高级功能
    private void resultMapElements(List<XNode> list) throws Exception {
        //基本上就是循环把resultMap加入到Configuration里去,保持2份，一份缩略，一分全名
        for (XNode resultMapNode : list) {
            try {
                //循环调resultMapElement
                resultMapElement(resultMapNode);
            } catch (IncompleteElementException e) {
                // ignore, it will be retried
            }
        }
    }

    //5.1 配置resultMap
    private ResultMap resultMapElement(XNode resultMapNode) throws Exception {
        return resultMapElement(resultMapNode, Collections.<ResultMapping>emptyList());
    }

    //5.1 配置resultMap
    // 解析配置文件中的全部<resultMap>节点, 该方法会循环调用resultMapElement()方法处理每个resultMap节点
    private ResultMap resultMapElement(XNode resultMapNode, List<ResultMapping> additionalResultMappings) throws Exception {
        //错误上下文
//取得标示符   ("resultMap[userResultMap]")
//    <resultMap id="userResultMap" type="User">
//      <id property="id" column="user_id" />
//      <result property="username" column="username"/>
//      <result property="password" column="password"/>
//    </resultMap>
        ErrorContext.instance().activity("processing " + resultMapNode.getValueBasedIdentifier());
        // 获取<resultMap>的id属性, 默认会拼装所有父节点的id或value或Property属性
        String id = resultMapNode.getStringAttribute("id",
                resultMapNode.getValueBasedIdentifier());
        //一般拿type就可以了，后面3个难道是兼容老的代码？
        // 获取<resultMap>节点的type属性,表示结果集将映射成type指定类型的对象,注意其默认值
        String type = resultMapNode.getStringAttribute("type",
                resultMapNode.getStringAttribute("ofType",
                        resultMapNode.getStringAttribute("resultType",
                                resultMapNode.getStringAttribute("javaType"))));
        //高级功能，还支持继承?
//  <resultMap id="carResult" type="Car" extends="vehicleResult">
//    <result property="doorCount" column="door_count" />
//  </resultMap>
        // 获取<resultMap>节点的的extends属性,该属性制定了该<resultMap>节点的继承关系
        String extend = resultMapNode.getStringAttribute("extends");
        // 读取<resultMap>节点的autoMapping属性, 将该属性设置为true, 则启动自动映射功能
        // 即自动查找与列同名的属性名,并调用setter方法. 而设置为false后, 则需要在<resultMap>
        // 节点内注明映射关系才会调用对应的setter方法
        Boolean autoMapping = resultMapNode.getBooleanAttribute("autoMapping");
        // 解析type类型
        Class<?> typeClass = resolveClass(type);
        Discriminator discriminator = null;
        // 该集合用于记录解析的结果
        List<ResultMapping> resultMappings = new ArrayList<ResultMapping>();
        resultMappings.addAll(additionalResultMappings);
        // 处理ResultMap的子节点
        List<XNode> resultChildren = resultMapNode.getChildren();
        // 处理resultMap的子节点
        for (XNode resultChild : resultChildren) {
            if ("constructor".equals(resultChild.getName())) {
                //解析result map的constructor
                // 处理<constructor>节点
                processConstructorElement(resultChild, typeClass, resultMappings);
            } else if ("discriminator".equals(resultChild.getName())) {
                //解析result map的discriminator
                discriminator = processDiscriminatorElement(resultChild, typeClass, resultMappings);
            } else {
                // 处理<id>, <result>, <association>, <collection>等节点
                List<ResultFlag> flags = new ArrayList<ResultFlag>();
                if ("id".equals(resultChild.getName())) {
                    // 如果是<id>节点, 则向flags集合中添加 ResultFlag.ID
                    flags.add(ResultFlag.ID);
                }
                //调5.1.1 buildResultMappingFromContext,得到ResultMapping
                // 创建ResultMapping对象, 并添加到resultMapping集合中保存
                resultMappings.add(buildResultMappingFromContext(resultChild, typeClass, flags));
            }
        }
        //最后再调ResultMapResolver得到ResultMap
        ResultMapResolver resultMapResolver = new ResultMapResolver(builderAssistant, id, typeClass, extend, discriminator, resultMappings, autoMapping);
        try {
            // 创建ResultMap对象,并添加到Configuration.resultMap集合中,该集合是StrictMap类型
            return resultMapResolver.resolve();
        } catch (IncompleteElementException e) {
            configuration.addIncompleteResultMap(resultMapResolver);
            throw e;
        }
    }

    //解析result map的constructor
//<constructor>
//  <idArg column="blog_id" javaType="int"/>
//</constructor>
    private void processConstructorElement(XNode resultChild, Class<?> resultType, List<ResultMapping> resultMappings) throws Exception {
        // 获取<constructor>节点的子节点
        List<XNode> argChildren = resultChild.getChildren();
        for (XNode argChild : argChildren) {
            List<ResultFlag> flags = new ArrayList<ResultFlag>();
            //加上CONSTRUCTOR标志
            flags.add(ResultFlag.CONSTRUCTOR);
            if ("idArg".equals(argChild.getName())) {
                // 对于<idArg>节点 添加ID标志
                flags.add(ResultFlag.ID);
            }
            // 创建ResultMapping对象, 并添加到resultMappings集合中
            resultMappings.add(buildResultMappingFromContext(argChild, resultType, flags));
        }
    }

    //解析result map的discriminator
//<discriminator javaType="int" column="draft">
//  <case value="1" resultType="DraftPost"/>
//</discriminator>
    private Discriminator processDiscriminatorElement(XNode context, Class<?> resultType, List<ResultMapping> resultMappings) throws Exception {
        // 获取 column,javaType,jdbcType,typeHandler  属性
        String column = context.getStringAttribute("column");
        String javaType = context.getStringAttribute("javaType");
        String jdbcType = context.getStringAttribute("jdbcType");
        String typeHandler = context.getStringAttribute("typeHandler");
        Class<?> javaTypeClass = resolveClass(javaType);
        @SuppressWarnings("unchecked")
        Class<? extends TypeHandler<?>> typeHandlerClass = (Class<? extends TypeHandler<?>>) resolveClass(typeHandler);
        JdbcType jdbcTypeEnum = resolveJdbcType(jdbcType);
        // 处理<discriminator>节点的子节点
        Map<String, String> discriminatorMap = new HashMap<String, String>();
        for (XNode caseChild : context.getChildren()) {
            String value = caseChild.getStringAttribute("value");
            // 调用processNestedResultMappings() 方法创建嵌套的ResultMap对象
            String resultMap = caseChild.getStringAttribute("resultMap", processNestedResultMappings(caseChild, resultMappings));
            // 记录该列值与对应选择的 ResultMap 的 Id
            discriminatorMap.put(value, resultMap);
        }
        // 创建 Discriminator 对象
        return builderAssistant.buildDiscriminator(resultType, column, javaTypeClass, jdbcTypeEnum, typeHandlerClass, discriminatorMap);
    }

    //6 配置sql(定义可重用的 SQL 代码段)
    private void sqlElement(List<XNode> list) throws Exception {
        if (configuration.getDatabaseId() != null) {
            sqlElement(list, configuration.getDatabaseId());
        }
        sqlElement(list, null);
    }

    //6.1 配置sql
//<sql id="userColumns"> id,username,password </sql>
    private void sqlElement(List<XNode> list, String requiredDatabaseId) throws Exception {
        // 遍历<sql>节点
        for (XNode context : list) {
            String databaseId = context.getStringAttribute("databaseId");
            // 获取id属性
            String id = context.getStringAttribute("id");
            // 为id添加命名空间
            id = builderAssistant.applyCurrentNamespace(id, false);
            //比较简单，就是将sql片段放入hashmap,不过此时还没有解析sql片段
            // 检测<sql>的databaseId与当前Configration中记录的databaseId是否一致
            if (databaseIdMatchesCurrent(id, databaseId, requiredDatabaseId)) {
                // 记录到XMLMapperBuilder.sqlFragments(Map<String,Xnode>类型)中保存,
                // 在XMLMapperBuilder的构造函数中,可以看到该字段指向了Configuration.sqlFragments集合
                sqlFragments.put(id, context);
            }
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
            // skip this fragment if there is a previous one with a not null databaseId
            //如果有重名的id了
            //<sql id="userColumns"> id,username,password </sql>
            if (this.sqlFragments.containsKey(id)) {
                XNode context = this.sqlFragments.get(id);
                //如果之前那个重名的sql id有databaseId，则false，否则难道true？这样新的sql覆盖老的sql？？？
                if (context.getStringAttribute("databaseId") != null) {
                    return false;
                }
            }
        }
        return true;
    }

    //5.1.1 构建resultMap
    private ResultMapping buildResultMappingFromContext(XNode context, Class<?> resultType, List<ResultFlag> flags) throws Exception {
        //<id property="id" column="author_id"/>
        //<result property="username" column="author_username"/>
        // 获取该节点的property属性值
        String property = context.getStringAttribute("property");
        String column = context.getStringAttribute("column");
        String javaType = context.getStringAttribute("javaType");
        String jdbcType = context.getStringAttribute("jdbcType");
        String nestedSelect = context.getStringAttribute("select");
        //处理嵌套的result map
        // 此处会处理<association>节点
        //      如果未指定<association>节点的resultMap属性,则是匿名的嵌套映射,需要通过
        //      processNestedResultMappers() 方法解析该匿名类的嵌套映射,
        //      在后面分析<collection>节点时还会涉及匿名嵌套映射的解析过程
        String nestedResultMap = context.getStringAttribute("resultMap",
                processNestedResultMappings(context, Collections.<ResultMapping>emptyList()));
        String notNullColumn = context.getStringAttribute("notNullColumn");
        String columnPrefix = context.getStringAttribute("columnPrefix");
        String typeHandler = context.getStringAttribute("typeHandler");
        String resulSet = context.getStringAttribute("resultSet");
        String foreignColumn = context.getStringAttribute("foreignColumn");
        boolean lazy = "lazy".equals(context.getStringAttribute("fetchType", configuration.isLazyLoadingEnabled() ? "lazy" : "eager"));
        // 解析javaType, typeHandler和jdbcType
        Class<?> javaTypeClass = resolveClass(javaType);
        @SuppressWarnings("unchecked")
        Class<? extends TypeHandler<?>> typeHandlerClass = (Class<? extends TypeHandler<?>>) resolveClass(typeHandler);
        JdbcType jdbcTypeEnum = resolveJdbcType(jdbcType);
        //又去调builderAssistant.buildResultMapping
        // 创建resultMapping对象
        return builderAssistant.buildResultMapping(resultType, property, column, javaTypeClass, jdbcTypeEnum, nestedSelect, nestedResultMap, notNullColumn, columnPrefix, typeHandlerClass, flags, resulSet, foreignColumn, lazy);
    }

    //5.1.1.1 处理嵌套的result map
    private String processNestedResultMappings(XNode context, List<ResultMapping> resultMappings) throws Exception {
        //只会处理association|collection|case 三种节点
        if ("association".equals(context.getName())
                || "collection".equals(context.getName())
                || "case".equals(context.getName())) {

//    	<resultMap id="blogResult" type="Blog">
//    	  <association property="author" column="author_id" javaType="Author" select="selectAuthor"/>
//    	</resultMap>
//如果不是嵌套查询
            // 指定了 select 属性后, 不会生成嵌套的ResultMap对象
            if (context.getStringAttribute("select") == null) {
                // 创建Result对象, 并添加到 Configuration.resultMaps集合中. 注意, 本例中
                // <association>节点没有id, 其id由XNode.getValueBasedIdentifier()方法生成,
                //        <association property="author" resultMap="authorResult"/>
                // 例子:src/test/java/com/learning/mapper/BlogMapper.xml
                // 本例中 id 为"mapper_resultMap[detailedBlogResultMap]_association[author]"
                //  另外,本例中<association>节点指定了resultMap属性, 而非匿名嵌套映射,
                // 所以该resultMap对象中ResultMappings集合为空
                ResultMap resultMap = resultMapElement(context, resultMappings);
                return resultMap.getId();
            }
        }
        return null;
    }

    private void bindMapperForNamespace() {
        // 获取映射配置文件的的命名空间
        String namespace = builderAssistant.getCurrentNamespace();
        if (namespace != null) {
            Class<?> boundType = null;
            try {
                // 解析命名空间对应的类型
                boundType = Resources.classForName(namespace);
            } catch (ClassNotFoundException e) {
                //ignore, bound type is not required
            }
            if (boundType != null) {
                // 是否已经加载了boundType接口
                if (!configuration.hasMapper(boundType)) {
                    // Spring may not know the real resource name so we set a flag
                    // to prevent loading again this resource from the mapper interface
                    // look at MapperAnnotationBuilder#loadXmlResource
                    // 追加 namespace前缀, 并添加到Configuration.loadeResources集合中保存
                    configuration.addLoadedResource("namespace:" + namespace);
                    // 调用MapperRegistry.addMapper()方法, 注册 boundType 接口
                    configuration.addMapper(boundType);
                }
            }
        }
    }

}
