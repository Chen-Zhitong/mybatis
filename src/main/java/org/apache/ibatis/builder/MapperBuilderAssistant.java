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
package org.apache.ibatis.builder;

import org.apache.ibatis.cache.Cache;
import org.apache.ibatis.cache.decorators.LruCache;
import org.apache.ibatis.cache.impl.PerpetualCache;
import org.apache.ibatis.executor.ErrorContext;
import org.apache.ibatis.executor.keygen.KeyGenerator;
import org.apache.ibatis.mapping.*;
import org.apache.ibatis.reflection.MetaClass;
import org.apache.ibatis.scripting.LanguageDriver;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.type.JdbcType;
import org.apache.ibatis.type.TypeHandler;

import java.util.*;

/**
 * @author Clinton Begin
 */

/**
 * 映射构建器助手，建造者模式,继承BaseBuilder
 */
public class MapperBuilderAssistant extends BaseBuilder {

    //每个助手都有1个namespace,resource,cache
    private String currentNamespace;
    private String resource;
    private Cache currentCache;
    private boolean unresolvedCacheRef; // issue #676

    public MapperBuilderAssistant(Configuration configuration, String resource) {
        super(configuration);
        ErrorContext.instance().resource(resource);
        this.resource = resource;
    }

    public String getCurrentNamespace() {
        return currentNamespace;
    }

    public void setCurrentNamespace(String currentNamespace) {
        if (currentNamespace == null) {
            throw new BuilderException("The mapper element requires a namespace attribute to be specified.");
        }

        if (this.currentNamespace != null && !this.currentNamespace.equals(currentNamespace)) {
            throw new BuilderException("Wrong namespace. Expected '"
                    + this.currentNamespace + "' but found '" + currentNamespace + "'.");
        }

        this.currentNamespace = currentNamespace;
    }

    //为id加上namespace前缀，如selectPerson-->org.a.b.selectPerson
    public String applyCurrentNamespace(String base, boolean isReference) {
        if (base == null) {
            return null;
        }
        if (isReference) {
            // is it qualified with any namespace yet?
            if (base.contains(".")) {
                return base;
            }
        } else {
            // is it qualified with this namespace yet?
            if (base.startsWith(currentNamespace + ".")) {
                return base;
            }
            if (base.contains(".")) {
                throw new BuilderException("Dots are not allowed in element names, please remove it from " + base);
            }
        }
        return currentNamespace + "." + base;
    }

    public Cache useCacheRef(String namespace) {
        if (namespace == null) {
            throw new BuilderException("cache-ref element requires a namespace attribute.");
        }
        try {
            // 标识未成功解析Cache引用
            unresolvedCacheRef = true;
            // 获取namespace对应的Cache对象
            Cache cache = configuration.getCache(namespace);
            if (cache == null) {
                throw new IncompleteElementException("No cache for namespace '" + namespace + "' could be found.");
            }
            // 记录当前命名空间使用的Cache信息
            currentCache = cache;
            // 标识已成功解析cahce引用
            unresolvedCacheRef = false;
            return cache;
        } catch (IllegalArgumentException e) {
            throw new IncompleteElementException("No cache for namespace '" + namespace + "' could be found.", e);
        }
    }

    /**
     * 负责创建Cache对象, 并将其添加到Configuration.caches集合中保存h
     *
     * @param typeClass
     * @param evictionClass
     * @param flushInterval
     * @param size
     * @param readWrite
     * @param blocking
     * @param props
     * @return
     */
    public Cache useNewCache(Class<? extends Cache> typeClass,
                             Class<? extends Cache> evictionClass,
                             Long flushInterval,
                             Integer size,
                             boolean readWrite,
                             boolean blocking,
                             Properties props) {
        //这里面又判断了一下是否为null就用默认值，有点和XMLMapperBuilder.cacheElement逻辑重复了
        typeClass = valueOrDefault(typeClass, PerpetualCache.class);
        evictionClass = valueOrDefault(evictionClass, LruCache.class);
        //调用CacheBuilder构建cache,id=currentNamespace
        Cache cache = new CacheBuilder(currentNamespace)
                .implementation(typeClass)
                .addDecorator(evictionClass)
                .clearInterval(flushInterval)
                .size(size)
                .readWrite(readWrite)
                .blocking(blocking)
                .properties(props)
                .build();
        //加入缓存, 其中会将Cache的id作为key, Cache对象本身作为value
        configuration.addCache(cache);
        //记录当前命名空间使用的cache对象
        currentCache = cache;
        return cache;
    }

    public ParameterMap addParameterMap(String id, Class<?> parameterClass, List<ParameterMapping> parameterMappings) {
        id = applyCurrentNamespace(id, false);
        ParameterMap.Builder parameterMapBuilder = new ParameterMap.Builder(configuration, id, parameterClass, parameterMappings);
        ParameterMap parameterMap = parameterMapBuilder.build();
        configuration.addParameterMap(parameterMap);
        return parameterMap;
    }

    public ParameterMapping buildParameterMapping(
            Class<?> parameterType,
            String property,
            Class<?> javaType,
            JdbcType jdbcType,
            String resultMap,
            ParameterMode parameterMode,
            Class<? extends TypeHandler<?>> typeHandler,
            Integer numericScale) {
        resultMap = applyCurrentNamespace(resultMap, true);

        // Class parameterType = parameterMapBuilder.type();
        Class<?> javaTypeClass = resolveParameterJavaType(parameterType, property, javaType, jdbcType);
        TypeHandler<?> typeHandlerInstance = resolveTypeHandler(javaTypeClass, typeHandler);

        ParameterMapping.Builder builder = new ParameterMapping.Builder(configuration, property, javaTypeClass);
        builder.jdbcType(jdbcType);
        builder.resultMapId(resultMap);
        builder.mode(parameterMode);
        builder.numericScale(numericScale);
        builder.typeHandler(typeHandlerInstance);
        return builder.build();
    }

    //增加ResultMap
    public ResultMap addResultMap(
            String id,
            Class<?> type,
            String extend,
            Discriminator discriminator,
            List<ResultMapping> resultMappings,
            Boolean autoMapping) {
        // ResultMap 完整的id是"namespace.id"的格式
        id = applyCurrentNamespace(id, false);
        // 获取 被继承的ResultMap的完整id,也就是父ResultMap对象的完整id
        extend = applyCurrentNamespace(extend, true);

        //建造者模式
        ResultMap.Builder resultMapBuilder = new ResultMap.Builder(configuration, id, type, resultMappings, autoMapping);
        // 针对extend属性做处理
        if (extend != null) {
            // 检测Configuration.resultMaps集合中是否存在被继承的ResultMap对象
            if (!configuration.hasResultMap(extend)) {
                throw new IncompleteElementException("Could not find a parent resultmap with id '" + extend + "'");
            }
            // 获取需要被继承的Resultmap对象 也就是父ResultMap对象
            ResultMap resultMap = configuration.getResultMap(extend);
            // 获取父ResultMap对象中记录的ResultMapping集合
            List<ResultMapping> extendedResultMappings = new ArrayList<ResultMapping>(resultMap.getResultMappings());
            // 删除需要覆盖的ResultMapping集合
            extendedResultMappings.removeAll(resultMappings);
            // Remove parent constructor if this resultMap declares a constructor.
            // 如果当前<resultMap>节点中定义了<constructor>节点则不需要父ResultMap中相应的constructor节点
            // 则将父ResultMap中对应的ResultMapping对象删除
            boolean declaresConstructor = false;
            for (ResultMapping resultMapping : resultMappings) {
                if (resultMapping.getFlags().contains(ResultFlag.CONSTRUCTOR)) {
                    declaresConstructor = true;
                    break;
                }
            }
            if (declaresConstructor) {
                Iterator<ResultMapping> extendedResultMappingsIter = extendedResultMappings.iterator();
                while (extendedResultMappingsIter.hasNext()) {
                    if (extendedResultMappingsIter.next().getFlags().contains(ResultFlag.CONSTRUCTOR)) {
                        extendedResultMappingsIter.remove();
                    }
                }
            }
            // 添加需要被继承下来的ResultMapping集合
            resultMappings.addAll(extendedResultMappings);
        }
        resultMapBuilder.discriminator(discriminator);
        // 创建ResultMap对象,并添加到configration.resultmap集合中保存
        ResultMap resultMap = resultMapBuilder.build();
        configuration.addResultMap(resultMap);
        return resultMap;
    }

    public Discriminator buildDiscriminator(
            Class<?> resultType,
            String column,
            Class<?> javaType,
            JdbcType jdbcType,
            Class<? extends TypeHandler<?>> typeHandler,
            Map<String, String> discriminatorMap) {
        ResultMapping resultMapping = buildResultMapping(
                resultType,
                null,
                column,
                javaType,
                jdbcType,
                null,
                null,
                null,
                null,
                typeHandler,
                new ArrayList<ResultFlag>(),
                null,
                null,
                false);
        Map<String, String> namespaceDiscriminatorMap = new HashMap<String, String>();
        for (Map.Entry<String, String> e : discriminatorMap.entrySet()) {
            String resultMap = e.getValue();
            resultMap = applyCurrentNamespace(resultMap, true);
            namespaceDiscriminatorMap.put(e.getKey(), resultMap);
        }
        Discriminator.Builder discriminatorBuilder = new Discriminator.Builder(configuration, resultMapping, namespaceDiscriminatorMap);
        return discriminatorBuilder.build();
    }

    //增加映射语句
    public MappedStatement addMappedStatement(
            String id,
            SqlSource sqlSource,
            StatementType statementType,
            SqlCommandType sqlCommandType,
            Integer fetchSize,
            Integer timeout,
            String parameterMap,
            Class<?> parameterType,
            String resultMap,
            Class<?> resultType,
            ResultSetType resultSetType,
            boolean flushCache,
            boolean useCache,
            boolean resultOrdered,
            KeyGenerator keyGenerator,
            String keyProperty,
            String keyColumn,
            String databaseId,
            LanguageDriver lang,
            String resultSets) {

        if (unresolvedCacheRef) {
            throw new IncompleteElementException("Cache-ref not yet resolved");
        }

        //为id加上namespace前缀
        id = applyCurrentNamespace(id, false);
        //是否是select语句
        boolean isSelect = sqlCommandType == SqlCommandType.SELECT;

        //又是建造者模式
        MappedStatement.Builder statementBuilder = new MappedStatement.Builder(configuration, id, sqlSource, sqlCommandType);
        statementBuilder.resource(resource);
        statementBuilder.fetchSize(fetchSize);
        statementBuilder.statementType(statementType);
        statementBuilder.keyGenerator(keyGenerator);
        statementBuilder.keyProperty(keyProperty);
        statementBuilder.keyColumn(keyColumn);
        statementBuilder.databaseId(databaseId);
        statementBuilder.lang(lang);
        statementBuilder.resultOrdered(resultOrdered);
        statementBuilder.resulSets(resultSets);
        setStatementTimeout(timeout, statementBuilder);

        //1.参数映射
        setStatementParameterMap(parameterMap, parameterType, statementBuilder);
        //2.结果映射
        setStatementResultMap(resultMap, resultType, resultSetType, statementBuilder);
        setStatementCache(isSelect, flushCache, useCache, currentCache, statementBuilder);

        MappedStatement statement = statementBuilder.build();
        //建造好调用configuration.addMappedStatement
        configuration.addMappedStatement(statement);
        return statement;
    }

    private <T> T valueOrDefault(T value, T defaultValue) {
        return value == null ? defaultValue : value;
    }

    private void setStatementCache(
            boolean isSelect,
            boolean flushCache,
            boolean useCache,
            Cache cache,
            MappedStatement.Builder statementBuilder) {
        flushCache = valueOrDefault(flushCache, !isSelect);
        useCache = valueOrDefault(useCache, isSelect);
        statementBuilder.flushCacheRequired(flushCache);
        statementBuilder.useCache(useCache);
        statementBuilder.cache(cache);
    }

    private void setStatementParameterMap(
            String parameterMap,
            Class<?> parameterTypeClass,
            MappedStatement.Builder statementBuilder) {
        parameterMap = applyCurrentNamespace(parameterMap, true);

        if (parameterMap != null) {
            try {
                statementBuilder.parameterMap(configuration.getParameterMap(parameterMap));
            } catch (IllegalArgumentException e) {
                throw new IncompleteElementException("Could not find parameter map " + parameterMap, e);
            }
        } else if (parameterTypeClass != null) {
            List<ParameterMapping> parameterMappings = new ArrayList<ParameterMapping>();
            ParameterMap.Builder inlineParameterMapBuilder = new ParameterMap.Builder(
                    configuration,
                    statementBuilder.id() + "-Inline",
                    parameterTypeClass,
                    parameterMappings);
            statementBuilder.parameterMap(inlineParameterMapBuilder.build());
        }
    }

    //2.result map
    private void setStatementResultMap(
            String resultMap,
            Class<?> resultType,
            ResultSetType resultSetType,
            MappedStatement.Builder statementBuilder) {
        resultMap = applyCurrentNamespace(resultMap, true);

        List<ResultMap> resultMaps = new ArrayList<ResultMap>();
        if (resultMap != null) {
            //2.1 resultMap是高级功能
            String[] resultMapNames = resultMap.split(",");
            for (String resultMapName : resultMapNames) {
                try {
                    resultMaps.add(configuration.getResultMap(resultMapName.trim()));
                } catch (IllegalArgumentException e) {
                    throw new IncompleteElementException("Could not find result map " + resultMapName, e);
                }
            }
        } else if (resultType != null) {
            //2.2 resultType,一般用这个足矣了
            //<select id="selectUsers" resultType="User">
            //这种情况下,MyBatis 会在幕后自动创建一个 ResultMap,基于属性名来映射列到 JavaBean 的属性上。
            //如果列名没有精确匹配,你可以在列名上使用 select 字句的别名来匹配标签。
            //创建一个inline result map, 把resultType设上就OK了，
            //然后后面被DefaultResultSetHandler.createResultObject()使用
            //DefaultResultSetHandler.getRowValue()使用
            ResultMap.Builder inlineResultMapBuilder = new ResultMap.Builder(
                    configuration,
                    statementBuilder.id() + "-Inline",
                    resultType,
                    new ArrayList<ResultMapping>(),
                    null);
            resultMaps.add(inlineResultMapBuilder.build());
        }
        statementBuilder.resultMaps(resultMaps);

        statementBuilder.resultSetType(resultSetType);
    }

    private void setStatementTimeout(Integer timeout, MappedStatement.Builder statementBuilder) {
        if (timeout == null) {
            timeout = configuration.getDefaultStatementTimeout();
        }
        statementBuilder.timeout(timeout);
    }

    //构建result map
    public ResultMapping buildResultMapping(
            Class<?> resultType,
            String property,
            String column,
            Class<?> javaType,
            JdbcType jdbcType,
            String nestedSelect,
            String nestedResultMap,
            String notNullColumn,
            String columnPrefix,
            Class<? extends TypeHandler<?>> typeHandler,
            List<ResultFlag> flags,
            String resultSet,
            String foreignColumn,
            boolean lazy) {
        // 解析<resultType>节点指定的property属性的类型
        Class<?> javaTypeClass = resolveResultJavaType(resultType, property, javaType);
        // 获取typeHandler指定的TypeHandler对象,底层依赖于typeHandlerRegistry
        TypeHandler<?> typeHandlerInstance = resolveTypeHandler(javaTypeClass, typeHandler);
        // 解析column属性值,当column是"{prop1=col1,prop2=col2}"形式时,会解析成ResultMapping
        // 对象集合,column这种形式主要用于嵌套查询的参数传递
        List<ResultMapping> composites = parseCompositeColumnName(column);
        if (composites.size() > 0) {
            column = null;
        }
        //创建result map,并设置其字段
        ResultMapping.Builder builder = new ResultMapping.Builder(configuration, property, column, javaTypeClass);
        builder.jdbcType(jdbcType);
        builder.nestedQueryId(applyCurrentNamespace(nestedSelect, true));
        builder.nestedResultMapId(applyCurrentNamespace(nestedResultMap, true));
        builder.resultSet(resultSet);
        builder.typeHandler(typeHandlerInstance);
        builder.flags(flags == null ? new ArrayList<ResultFlag>() : flags);
        builder.composites(composites);
        builder.notNullColumns(parseMultipleColumnNames(notNullColumn));
        builder.columnPrefix(columnPrefix);
        builder.foreignColumn(foreignColumn);
        builder.lazy(lazy);
        return builder.build();
    }

    private Set<String> parseMultipleColumnNames(String columnName) {
        Set<String> columns = new HashSet<String>();
        if (columnName != null) {
            if (columnName.indexOf(',') > -1) {
                StringTokenizer parser = new StringTokenizer(columnName, "{}, ", false);
                while (parser.hasMoreTokens()) {
                    String column = parser.nextToken();
                    columns.add(column);
                }
            } else {
                columns.add(columnName);
            }
        }
        return columns;
    }

    //解析复合列名，即列名由多个组成，可以先忽略
    private List<ResultMapping> parseCompositeColumnName(String columnName) {
        List<ResultMapping> composites = new ArrayList<ResultMapping>();
        if (columnName != null && (columnName.indexOf('=') > -1 || columnName.indexOf(',') > -1)) {
            StringTokenizer parser = new StringTokenizer(columnName, "{}=, ", false);
            while (parser.hasMoreTokens()) {
                String property = parser.nextToken();
                String column = parser.nextToken();
                ResultMapping.Builder complexBuilder = new ResultMapping.Builder(configuration, property, column, configuration.getTypeHandlerRegistry().getUnknownTypeHandler());
                composites.add(complexBuilder.build());
            }
        }
        return composites;
    }

    private Class<?> resolveResultJavaType(Class<?> resultType, String property, Class<?> javaType) {
        if (javaType == null && property != null) {
            try {
                MetaClass metaResultType = MetaClass.forClass(resultType);
                javaType = metaResultType.getSetterType(property);
            } catch (Exception e) {
                //ignore, following null check statement will deal with the situation
            }
        }
        if (javaType == null) {
            javaType = Object.class;
        }
        return javaType;
    }

    private Class<?> resolveParameterJavaType(Class<?> resultType, String property, Class<?> javaType, JdbcType jdbcType) {
        if (javaType == null) {
            if (JdbcType.CURSOR.equals(jdbcType)) {
                javaType = java.sql.ResultSet.class;
            } else if (Map.class.isAssignableFrom(resultType)) {
                javaType = Object.class;
            } else {
                MetaClass metaResultType = MetaClass.forClass(resultType);
                javaType = metaResultType.getGetterType(property);
            }
        }
        if (javaType == null) {
            javaType = Object.class;
        }
        return javaType;
    }

    /**
     * Backward compatibility signature
     */
    //向后兼容方法
    public ResultMapping buildResultMapping(
            Class<?> resultType,
            String property,
            String column,
            Class<?> javaType,
            JdbcType jdbcType,
            String nestedSelect,
            String nestedResultMap,
            String notNullColumn,
            String columnPrefix,
            Class<? extends TypeHandler<?>> typeHandler,
            List<ResultFlag> flags) {
        return buildResultMapping(
                resultType, property, column, javaType, jdbcType, nestedSelect,
                nestedResultMap, notNullColumn, columnPrefix, typeHandler, flags, null, null, configuration.isLazyLoadingEnabled());
    }

    //取得语言驱动
    public LanguageDriver getLanguageDriver(Class<?> langClass) {
        if (langClass != null) {
            //注册语言驱动
            configuration.getLanguageRegistry().register(langClass);
        } else {
            //如果为null，则取得默认驱动（mybatis3.2以前大家一直用的方法）
            langClass = configuration.getLanguageRegistry().getDefaultDriverClass();
        }
        //再去调configuration
        return configuration.getLanguageRegistry().getDriver(langClass);
    }

    /**
     * Backward compatibility signature
     */
    //向后兼容方法
    public MappedStatement addMappedStatement(
            String id,
            SqlSource sqlSource,
            StatementType statementType,
            SqlCommandType sqlCommandType,
            Integer fetchSize,
            Integer timeout,
            String parameterMap,
            Class<?> parameterType,
            String resultMap,
            Class<?> resultType,
            ResultSetType resultSetType,
            boolean flushCache,
            boolean useCache,
            boolean resultOrdered,
            KeyGenerator keyGenerator,
            String keyProperty,
            String keyColumn,
            String databaseId,
            LanguageDriver lang) {
        return addMappedStatement(
                id, sqlSource, statementType, sqlCommandType, fetchSize, timeout,
                parameterMap, parameterType, resultMap, resultType, resultSetType,
                flushCache, useCache, resultOrdered, keyGenerator, keyProperty,
                keyColumn, databaseId, lang, null);
    }

}
