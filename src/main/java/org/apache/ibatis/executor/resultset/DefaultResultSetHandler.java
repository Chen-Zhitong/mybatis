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
package org.apache.ibatis.executor.resultset;

import org.apache.ibatis.cache.CacheKey;
import org.apache.ibatis.executor.ErrorContext;
import org.apache.ibatis.executor.Executor;
import org.apache.ibatis.executor.ExecutorException;
import org.apache.ibatis.executor.loader.ResultLoader;
import org.apache.ibatis.executor.loader.ResultLoaderMap;
import org.apache.ibatis.executor.parameter.ParameterHandler;
import org.apache.ibatis.executor.result.DefaultResultContext;
import org.apache.ibatis.executor.result.DefaultResultHandler;
import org.apache.ibatis.mapping.*;
import org.apache.ibatis.reflection.MetaClass;
import org.apache.ibatis.reflection.MetaObject;
import org.apache.ibatis.reflection.factory.ObjectFactory;
import org.apache.ibatis.session.*;
import org.apache.ibatis.type.TypeHandler;
import org.apache.ibatis.type.TypeHandlerRegistry;

import java.lang.reflect.Constructor;
import java.sql.CallableStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.*;

/**
 * @author Clinton Begin
 * @author Eduardo Macarron
 */

/**
 * 默认Map结果处理器
 */
public class DefaultResultSetHandler implements ResultSetHandler {

    private static final Object NO_VALUE = new Object();

    private final Executor executor;
    private final Configuration configuration;
    private final MappedStatement mappedStatement;
    private final RowBounds rowBounds;
    private final ParameterHandler parameterHandler;
    // 用户指定用于处理结果集的ResultHandler对象
    private final ResultHandler resultHandler;
    private final BoundSql boundSql;
    private final TypeHandlerRegistry typeHandlerRegistry;
    private final ObjectFactory objectFactory;

    // nested resultmaps
    private final Map<CacheKey, Object> nestedResultObjects = new HashMap<CacheKey, Object>();
    private final Map<CacheKey, Object> ancestorObjects = new HashMap<CacheKey, Object>();
    private final Map<String, String> ancestorColumnPrefix = new HashMap<String, String>();

    // multiple resultsets
    private final Map<String, ResultMapping> nextResultMaps = new HashMap<String, ResultMapping>();
    private final Map<CacheKey, List<PendingRelation>> pendingRelations = new HashMap<CacheKey, List<PendingRelation>>();

    public DefaultResultSetHandler(Executor executor, MappedStatement mappedStatement, ParameterHandler parameterHandler, ResultHandler resultHandler, BoundSql boundSql,
                                   RowBounds rowBounds) {
        this.executor = executor;
        this.configuration = mappedStatement.getConfiguration();
        this.mappedStatement = mappedStatement;
        this.rowBounds = rowBounds;
        this.parameterHandler = parameterHandler;
        this.boundSql = boundSql;
        this.typeHandlerRegistry = configuration.getTypeHandlerRegistry();
        this.objectFactory = configuration.getObjectFactory();
        this.resultHandler = resultHandler;
    }

    @Override
    public void handleOutputParameters(CallableStatement cs) throws SQLException {
        final Object parameterObject = parameterHandler.getParameterObject();
        final MetaObject metaParam = configuration.newMetaObject(parameterObject);
        final List<ParameterMapping> parameterMappings = boundSql.getParameterMappings();
        //循环处理每个参数
        for (int i = 0; i < parameterMappings.size(); i++) {
            final ParameterMapping parameterMapping = parameterMappings.get(i);
            //只处理OUT|INOUT
            if (parameterMapping.getMode() == ParameterMode.OUT || parameterMapping.getMode() == ParameterMode.INOUT) {
                if (ResultSet.class.equals(parameterMapping.getJavaType())) {
                    //如果是ResultSet型(游标)
                    //#{result, jdbcType=CURSOR, mode=OUT, javaType=ResultSet, resultMap=userResultMap}
                    //先用CallableStatement.getObject取得这个游标，作为参数传进去
                    handleRefCursorOutputParameter((ResultSet) cs.getObject(i + 1), parameterMapping, metaParam);
                } else {
                    //否则是普通型，核心就是CallableStatement.getXXX取得值
                    final TypeHandler<?> typeHandler = parameterMapping.getTypeHandler();
                    metaParam.setValue(parameterMapping.getProperty(), typeHandler.getResult(cs, i + 1));
                }
            }
        }
    }

    //
    // HANDLE OUTPUT PARAMETER
    //

    //处理游标(OUT参数)
    private void handleRefCursorOutputParameter(ResultSet rs, ParameterMapping parameterMapping, MetaObject metaParam) throws SQLException {
        try {
            final String resultMapId = parameterMapping.getResultMapId();
            final ResultMap resultMap = configuration.getResultMap(resultMapId);
            final DefaultResultHandler resultHandler = new DefaultResultHandler(objectFactory);
            final ResultSetWrapper rsw = new ResultSetWrapper(rs, configuration);
            //里面就和一般ResultSet处理没两样了
            handleRowValues(rsw, resultMap, resultHandler, new RowBounds(), null);
            metaParam.setValue(parameterMapping.getProperty(), resultHandler.getResultList());
        } finally {
            // issue #228 (close resultsets)
            closeResultSet(rs);
        }
    }

    //
    // HANDLE RESULT SETS
    //
    @Override
    public List<Object> handleResultSets(Statement stmt) throws SQLException {
        ErrorContext.instance().activity("handling results").object(mappedStatement.getId());

        // 该集合用于保存映射结果集得到的结果对象
        final List<Object> multipleResults = new ArrayList<Object>();

        int resultSetCount = 0;
        // 获取第一个ResultSet对象, 可能存在多个ResultSet,这里只获取第一个ResultSet
        ResultSetWrapper rsw = getFirstResultSet(stmt);

        // 获取MappedStatement.resultMaps集合,映射文件中的<resultMap>节点会被解析成ResultMap对象,
        // 保存到MappedStatement.resultMaps集合中, 如果SQL节点能够产生多个ResultSet,那么我们可以在SQL节点的resultMap属性中
        // 配置多个<resultMap>节点的id,它们之间通过","分隔, 实现对多个结果集的映射
        List<ResultMap> resultMaps = mappedStatement.getResultMaps();
        //一般resultMaps里只有一个元素
        int resultMapCount = resultMaps.size();
        // 如果结果集不为空,则resultMaps集合不能为空, 否则抛出异常
        validateResultMapsCount(rsw, resultMapCount);
        while (rsw != null && resultMapCount > resultSetCount) { //---(1)  遍历resultMaps集合
            // 获取该结果集对应的ResultMap的对象
            ResultMap resultMap = resultMaps.get(resultSetCount);
            // 根据ResultMap中定义的映射规则对ResultSet进行映射,并将映射的结果对象添加到
            // multipleResults 集合中保存
            handleResultSet(rsw, resultMap, multipleResults, null);
            rsw = getNextResultSet(stmt);// 获取下一个结果集
            cleanUpAfterHandlingResultSet(); // 清空 nestedResultObject集合
            resultSetCount++;// 递增 resultSetCount
        }

        // 获取MappedStatement.resultSets属性. 该属性仅对多结果集的情况适用,该属性将列出语句执行后
        // 返回的结果集, 并给每个结果集一个名称, 名称是逗号分割的
        // 这里会根据ResultSet名称处理嵌套映射
        String[] resultSets = mappedStatement.getResulSets();
        if (resultSets != null) {
            while (rsw != null && resultSetCount < resultSets.length) {
                // 根据resultSet的名称, 获取未处理的ResultMapping
                ResultMapping parentMapping = nextResultMaps.get(resultSets[resultSetCount]);
                if (parentMapping != null) {
                    String nestedResultMapId = parentMapping.getNestedResultMapId();
                    ResultMap resultMap = configuration.getResultMap(nestedResultMapId);
                    // 根据ResultMap对象映射结果集
                    handleResultSet(rsw, resultMap, null, parentMapping);
                }
                // 获取下一个结果集
                rsw = getNextResultSet(stmt);
                // 清空 nestedResultObject集合
                cleanUpAfterHandlingResultSet();
                // 递增resultSetCount
                resultSetCount++;
            }
        }

        return collapseSingleResultList(multipleResults);
    }

    private ResultSetWrapper getFirstResultSet(Statement stmt) throws SQLException {
        // 获取ResultSet对象
        ResultSet rs = stmt.getResultSet();
        //HSQLDB2.1特殊情况处理
        while (rs == null) {
            // move forward to get the first resultset in case the driver
            // doesn't return the resultset as the first result (HSQLDB 2.1)
            // 检测是否还有待处理的结果集
            if (stmt.getMoreResults()) {
                rs = stmt.getResultSet();
            } else {
                // 没有待处理的的结果集
                if (stmt.getUpdateCount() == -1) {
                    // no more results. Must be no resultset
                    break;
                }
            }
        }
        // 将结果集封装成ResultSetWrapper对象
        return rs != null ? new ResultSetWrapper(rs, configuration) : null;
    }

    private ResultSetWrapper getNextResultSet(Statement stmt) throws SQLException {
        // Making this method tolerant of bad JDBC drivers
        try {
            // 检测JDBC是否支持多结果集
            if (stmt.getConnection().getMetaData().supportsMultipleResultSets()) {
                // Crazy Standard JDBC way of determining if there are more results
                // 检测是否还有待处理的结果集, 若存在 则封装成ResultSetWrapper对象并返回
                if (!((!stmt.getMoreResults()) && (stmt.getUpdateCount() == -1))) {
                    ResultSet rs = stmt.getResultSet();
                    return rs != null ? new ResultSetWrapper(rs, configuration) : null;
                }
            }
        } catch (Exception e) {
            // Intentionally ignored.
        }
        return null;
    }

    private void closeResultSet(ResultSet rs) {
        try {
            if (rs != null) {
                rs.close();
            }
        } catch (SQLException e) {
            // ignore
        }
    }

    private void cleanUpAfterHandlingResultSet() {
        nestedResultObjects.clear();
        ancestorColumnPrefix.clear();
    }

    private void validateResultMapsCount(ResultSetWrapper rsw, int resultMapCount) {
        if (rsw != null && resultMapCount < 1) {
            throw new ExecutorException("A query was run and no Result Maps were found for the Mapped Statement '" + mappedStatement.getId()
                    + "'.  It's likely that neither a Result Type nor a Result Map was specified.");
        }
    }

    //处理结果集
    private void handleResultSet(ResultSetWrapper rsw, ResultMap resultMap, List<Object> multipleResults, ResultMapping parentMapping) throws SQLException {
        try {
            if (parentMapping != null) {
                // 处理多结果集中的嵌套映射
                handleRowValues(rsw, resultMap, null, RowBounds.DEFAULT, parentMapping);
            } else {
                if (resultHandler == null) {
                    //如果没有resultHandler
                    //新建DefaultResultHandler
                    DefaultResultHandler defaultResultHandler = new DefaultResultHandler(objectFactory);
                    // 多ResultSet进行映射,并将映射得到的结果添加到DefaultResultHandler对象中暂存
                    handleRowValues(rsw, resultMap, defaultResultHandler, rowBounds, null);
                    // 将DefaultResulthandler中保存的结果对象添加到multipleResults集合中
                    multipleResults.add(defaultResultHandler.getResultList());
                } else {
                    // 使用用户指定的ResultHandler对象处理结果对象
                    handleRowValues(rsw, resultMap, resultHandler, rowBounds, null);
                }
            }
        } finally {
            //最后别忘了关闭结果集，这个居然出bug了
            // issue #228 (close resultsets)
            closeResultSet(rsw.getResultSet());
        }
    }

    @SuppressWarnings("unchecked")
    private List<Object> collapseSingleResultList(List<Object> multipleResults) {
        return multipleResults.size() == 1 ? (List<Object>) multipleResults.get(0) : multipleResults;
    }

    /**
     * 映射结果集的核心代码, 其中有两个分支:
     * 一个是针对包含嵌套映射的处理,
     * 另一个是针对不含嵌套映射的简单映射的处理.
     *
     * @param rsw
     * @param resultMap
     * @param resultHandler
     * @param rowBounds
     * @param parentMapping
     * @throws SQLException
     */
    private void handleRowValues(ResultSetWrapper rsw, ResultMap resultMap, ResultHandler resultHandler, RowBounds rowBounds, ResultMapping parentMapping) throws SQLException {
        // 针对存在嵌套ResultMap的情况
        if (resultMap.hasNestedResultMaps()) {
            // 检测是否允许再嵌套映射中使用 RowBound
            ensureNoRowBounds();
            // 检测是否允许再嵌套映射中使用用户自定义的ResultHandler
            checkResultHandler();
            handleRowValuesForNestedResultMap(rsw, resultMap, resultHandler, rowBounds, parentMapping);
        } else {
            // 针对不含嵌套映射的简单映射的处理
            handleRowValuesForSimpleResultMap(rsw, resultMap, resultHandler, rowBounds, parentMapping);
        }
    }

    //
    // HANDLE ROWS FOR SIMPLE RESULTMAP
    //

    private void ensureNoRowBounds() {
        if (configuration.isSafeRowBoundsEnabled() && rowBounds != null && (rowBounds.getLimit() < RowBounds.NO_ROW_LIMIT || rowBounds.getOffset() > RowBounds.NO_ROW_OFFSET)) {
            throw new ExecutorException("Mapped Statements with nested result mappings cannot be safely constrained by RowBounds. "
                    + "Use safeRowBoundsEnabled=false setting to bypass this check.");
        }
    }

    protected void checkResultHandler() {
        if (resultHandler != null && configuration.isSafeResultHandlerEnabled() && !mappedStatement.isResultOrdered()) {
            throw new ExecutorException("Mapped Statements with nested result mappings cannot be safely used with a custom ResultHandler. "
                    + "Use safeResultHandlerEnabled=false setting to bypass this check "
                    + "or ensure your statement returns ordered data and set resultOrdered=true on it.");
        }
    }

    private void handleRowValuesForSimpleResultMap(ResultSetWrapper rsw, ResultMap resultMap, ResultHandler resultHandler, RowBounds rowBounds, ResultMapping parentMapping)
            throws SQLException {
        DefaultResultContext resultContext = new DefaultResultContext();
        // 1. 根据Rowbounds中的offset值定位到指定的记录行
        skipRows(rsw.getResultSet(), rowBounds);
        // 2. shouldProcessMoreRows() 检测是否还有需要映射的记录
        while (shouldProcessMoreRows(resultContext, rowBounds) && rsw.getResultSet().next()) {
            // 3. resolveDiscriminatedResultMap 确定映射使用的ResultMap对象
            ResultMap discriminatedResultMap = resolveDiscriminatedResultMap(rsw.getResultSet(), resultMap, null);
            // 4. 调用getRowValue(),方法对ResultSet中的一行记录进行映射
            Object rowValue = getRowValue(rsw, discriminatedResultMap);
            // 5. 调用storeObject()保存映射得到结果对象
            storeObject(resultHandler, resultContext, rowValue, parentMapping, rsw.getResultSet());
        }
    }

    private void storeObject(ResultHandler resultHandler, DefaultResultContext resultContext, Object rowValue, ResultMapping parentMapping, ResultSet rs) throws SQLException {
        if (parentMapping != null) {
            // 嵌套查询或嵌套映射,将结果对象保存到父对象对应的属性中
            linkToParents(rs, parentMapping, rowValue);
        } else {
            // 普通映射, 将对象保存到Resulthandler中
            callResultHandler(resultHandler, resultContext, rowValue);
        }
    }

    private void callResultHandler(ResultHandler resultHandler, DefaultResultContext resultContext, Object rowValue) {
        //  递增DefaultResultContext.resultCount, 该值用于记录行数是否已经达到上限(在RowBounds.limit字段中记录了其上限)
        // 之后将结果对象保存到DefaultResultContext.resultObject字段中
        resultContext.nextResultObject(rowValue);
        // 将结果添加到ResultHandler.resultList中保存
        resultHandler.handleResult(resultContext);
    }

    /**
     * 检测能否对后续的记录行进行映射操作
     *
     * @param context
     * @param rowBounds
     * @return
     * @throws SQLException
     */
    private boolean shouldProcessMoreRows(ResultContext context, RowBounds rowBounds) throws SQLException {
        // 检测DefaultResultContext.stopped字段,和检测映射行数是否达到了RowBounds.limit的限制
        return !context.isStopped() && context.getResultCount() < rowBounds.getLimit();
    }

    /**
     * 根据RowBounds.offset字段的值定位到指定的记录
     *
     * @param rs
     * @param rowBounds
     * @throws SQLException
     */
    private void skipRows(ResultSet rs, RowBounds rowBounds) throws SQLException {
        // 根据ResultSet类型进行定位
        if (rs.getType() != ResultSet.TYPE_FORWARD_ONLY) {
            if (rowBounds.getOffset() != RowBounds.NO_ROW_OFFSET) {
                rs.absolute(rowBounds.getOffset());
            }
        } else {
            // 通过多次调用ResultSet.next()方法移动到指定的记录
            for (int i = 0; i < rowBounds.getOffset(); i++) {
                rs.next();
            }
        }
    }

    //核心，取得一行的值
    private Object getRowValue(ResultSetWrapper rsw, ResultMap resultMap) throws SQLException {
        //实例化ResultLoaderMap(延迟加载器)
        final ResultLoaderMap lazyLoader = new ResultLoaderMap();
        //调用自己的createResultObject,内部就是new一个对象(如果是简单类型，new完也把值赋进去)
        // a. 创建映射后的结果对象, 该结果对象的类型由<resultMap>节点的type属性指定
        Object resultObject = createResultObject(rsw, resultMap, lazyLoader, null);
        if (resultObject != null && !typeHandlerRegistry.hasTypeHandler(resultMap.getType())) {
            //一般不是简单类型不会有typehandler,这个if会进来
            // 创建上述结果对象相应的MetaObject对象
            final MetaObject metaObject = configuration.newMetaObject(resultObject);
            // 成功映射任意属性,则foundValues为true
            boolean foundValues = !resultMap.getConstructorResultMappings().isEmpty();
            // b判断是否开启了自动映射功能
            if (shouldApplyAutomaticMappings(resultMap, false)) {
                //自动映射咯
                //这里把每个列的值都赋到相应的字段里去了
                // 通过applyAutomaticMappings 自动映射ResultMap中未明确映射的列
                foundValues = applyAutomaticMappings(rsw, resultMap, metaObject, null) || foundValues;
            }
            // 通过applyPropertyMappings()方法映射ResultMap中明确映射的属性,
            // 到这里该行的记录数据已经全完映射到了结果对象的相应属性中
            foundValues = applyPropertyMappings(rsw, resultMap, metaObject, lazyLoader, null) || foundValues;
            foundValues = lazyLoader.size() > 0 || foundValues;
            // 如果没有成功映射任何属性,则根据mybatis-config.xml中的<resultInstanceForEmptyRow>
            // 配置决定返回空的结果对象还是返回null
            resultObject = foundValues ? resultObject : null;
            return resultObject;
        }
        return resultObject;
    }

    //
    // GET VALUE FROM ROW FOR SIMPLE RESULT MAP
    //

    private boolean shouldApplyAutomaticMappings(ResultMap resultMap, boolean isNested) {
        // 开关1: 在ResultMap中明确地配置了autoMapping属性
        if (resultMap.getAutoMapping() != null) {
            return resultMap.getAutoMapping();
        } else {
            // 开关2: 根据mybatis-config.xml中<setting>节点中配置的autoMappingBehavior值(默认为PARTIAL)决定是否开启自动映射功能
            // NONE表示取消自动映射
            // PARTIAL只会自动映射没有定义嵌套映射的ResultSet
            // FULL会映射任意复杂的ResultSet
            if (isNested) {
                return AutoMappingBehavior.FULL == configuration.getAutoMappingBehavior();
            } else {
                return AutoMappingBehavior.NONE != configuration.getAutoMappingBehavior();
            }
        }
    }

    /**
     * 处理ResultMap中明确需要进行映射的列,
     * 涉及延迟加载,嵌套映射等
     *
     * @param rsw
     * @param resultMap
     * @param metaObject
     * @param lazyLoader
     * @param columnPrefix
     * @return
     * @throws SQLException
     */
    private boolean applyPropertyMappings(ResultSetWrapper rsw, ResultMap resultMap, MetaObject metaObject, ResultLoaderMap lazyLoader, String columnPrefix)
            throws SQLException {
        // 获取该ResultMap中明确需要进行映射的列名集合
        final List<String> mappedColumnNames = rsw.getMappedColumnNames(resultMap, columnPrefix);
        boolean foundValues = false;
        // 获取ResultMap.propertyResultmappings集合,其中记录了映射使用的所有ResultMapping对象
        final List<ResultMapping> propertyMappings = resultMap.getPropertyResultMappings();
        for (ResultMapping propertyMapping : propertyMappings) {
            // 处理列前缀
            final String column = prependPrefix(propertyMapping.getColumn(), columnPrefix);
            // 下面逻辑主要处理三种
            // 1. column是"{prop1=col1, prop2=col2}"这种形式的, 一般与嵌套查询配合使用,
            // 表示将col1和col2的列值传递给内层嵌套查询作为参数
            // 2. 基本类型的属性映射
            // 3. 多结果集的场景处理,该属性来自另一个结果集
            if (propertyMapping.isCompositeResult() // --- 1
                    || (column != null && mappedColumnNames.contains(column.toUpperCase(Locale.ENGLISH))) // --- 2
                    || propertyMapping.getResultSet() != null) { // 3
                // 通过getPropertymappingValue()方法完成映射,并得到属性值
                Object value = getPropertyMappingValue(rsw.getResultSet(), metaObject, propertyMapping, lazyLoader, columnPrefix);
                // issue #541 make property optional
                // 获取属性名
                final String property = propertyMapping.getProperty();
                // issue #377, call setter on nulls
                if (value != NO_VALUE && property != null && (value != null || configuration.isCallSettersOnNulls())) {
                    if (value != null || !metaObject.getSetterType(property).isPrimitive()) {
                        // 设置属性值
                        metaObject.setValue(property, value);
                    }
                    foundValues = true;
                }
            }
        }
        return foundValues;
    }

    //
    // PROPERTY MAPPINGS
    //

    private Object getPropertyMappingValue(ResultSet rs, MetaObject metaResultObject, ResultMapping propertyMapping, ResultLoaderMap lazyLoader, String columnPrefix)
            throws SQLException {
        if (propertyMapping.getNestedQueryId() != null) { // 嵌套查询
            return getNestedQueryMappingValue(rs, metaResultObject, propertyMapping, lazyLoader, columnPrefix);
        } else if (propertyMapping.getResultSet() != null) { // 多结果集的处理
            addPendingChildRelation(rs, metaResultObject, propertyMapping);
            return NO_VALUE;
        } else if (propertyMapping.getNestedResultMapId() != null) {
            // the user added a column attribute to a nested result map, ignore it
            return NO_VALUE;
        } else {
            // 获取ResultMapping中记录的TypeHandler对象
            final TypeHandler<?> typeHandler = propertyMapping.getTypeHandler();
            final String column = prependPrefix(propertyMapping.getColumn(), columnPrefix);
            // 使用TypeHandler对象获取属性值
            return typeHandler.getResult(rs, column);
        }
    }

    // 负责自动映射ResultMap中未明确映射的列
    private boolean applyAutomaticMappings(ResultSetWrapper rsw, ResultMap resultMap, MetaObject metaObject, String columnPrefix) throws SQLException {
        // 获取ResultSet中存在,但ResultMap中没有明确映射的列所对应的 unmappedColumnNames 名称
        final List<String> unmappedColumnNames = rsw.getUnmappedColumnNames(resultMap, columnPrefix);
        boolean foundValues = false;
        // 遍历列名
        for (String columnName : unmappedColumnNames) {
            String propertyName = columnName;
            // 解析转换列名
            if (columnPrefix != null && !columnPrefix.isEmpty()) {
                // When columnPrefix is specified,
                // ignore columns without the prefix.
                if (columnName.toUpperCase(Locale.ENGLISH).startsWith(columnPrefix)) {
                    propertyName = columnName.substring(columnPrefix.length());
                } else {
                    continue;
                }
            }
            // 在结果对象中查找指定的属性名
            final String property = metaObject.findProperty(propertyName, configuration.isMapUnderscoreToCamelCase());
            // 检测该属性的setter方法, 注意:如果是MapWrapper,一直返回true
            if (property != null && metaObject.hasSetter(property)) {
                final Class<?> propertyType = metaObject.getSetterType(property);
                if (typeHandlerRegistry.hasTypeHandler(propertyType)) {
                    //查找对应的TypeHandler对象
                    final TypeHandler<?> typeHandler = rsw.getTypeHandler(propertyType, columnName);
                    //巧妙的用TypeHandler取得结果
                    final Object value = typeHandler.getResult(rsw.getResultSet(), columnName);
                    // issue #377, call setter on nulls
                    if (value != null || configuration.isCallSettersOnNulls()) {
                        if (value != null || !propertyType.isPrimitive()) {
                            //然后巧妙的用反射来设置到对象
                            metaObject.setValue(property, value);
                        }
                        foundValues = true;
                    }
                }
            }
        }
        return foundValues;
    }

    private void linkToParents(ResultSet rs, ResultMapping parentMapping, Object rowValue) throws SQLException {
        CacheKey parentKey = createKeyForMultipleResults(rs, parentMapping, parentMapping.getColumn(), parentMapping.getForeignColumn());
        List<PendingRelation> parents = pendingRelations.get(parentKey);
        for (PendingRelation parent : parents) {
            if (parent != null) {
                final Object collectionProperty = instantiateCollectionPropertyIfAppropriate(parent.propertyMapping, parent.metaObject);
                if (rowValue != null) {
                    if (collectionProperty != null) {
                        final MetaObject targetMetaObject = configuration.newMetaObject(collectionProperty);
                        targetMetaObject.add(rowValue);
                    } else {
                        parent.metaObject.setValue(parent.propertyMapping.getProperty(), rowValue);
                    }
                }
            }
        }
    }

    // MULTIPLE RESULT SETS

    private Object instantiateCollectionPropertyIfAppropriate(ResultMapping resultMapping, MetaObject metaObject) {
        final String propertyName = resultMapping.getProperty();
        Object propertyValue = metaObject.getValue(propertyName);
        if (propertyValue == null) {
            Class<?> type = resultMapping.getJavaType();
            if (type == null) {
                type = metaObject.getSetterType(propertyName);
            }
            try {
                if (objectFactory.isCollection(type)) {
                    propertyValue = objectFactory.create(type);
                    metaObject.setValue(propertyName, propertyValue);
                    return propertyValue;
                }
            } catch (Exception e) {
                throw new ExecutorException("Error instantiating collection property for result '" + resultMapping.getProperty() + "'.  Cause: " + e, e);
            }
        } else if (objectFactory.isCollection(propertyValue.getClass())) {
            return propertyValue;
        }
        return null;
    }

    private void addPendingChildRelation(ResultSet rs, MetaObject metaResultObject, ResultMapping parentMapping) throws SQLException {
        CacheKey cacheKey = createKeyForMultipleResults(rs, parentMapping, parentMapping.getColumn(), parentMapping.getColumn());
        PendingRelation deferLoad = new PendingRelation();
        deferLoad.metaObject = metaResultObject;
        deferLoad.propertyMapping = parentMapping;
        List<PendingRelation> relations = pendingRelations.get(cacheKey);
        // issue #255
        if (relations == null) {
            relations = new ArrayList<DefaultResultSetHandler.PendingRelation>();
            pendingRelations.put(cacheKey, relations);
        }
        relations.add(deferLoad);
        ResultMapping previous = nextResultMaps.get(parentMapping.getResultSet());
        if (previous == null) {
            nextResultMaps.put(parentMapping.getResultSet(), parentMapping);
        } else {
            if (!previous.equals(parentMapping)) {
                throw new ExecutorException("Two different properties are mapped to the same resultSet");
            }
        }
    }

    private CacheKey createKeyForMultipleResults(ResultSet rs, ResultMapping resultMapping, String names, String columns) throws SQLException {
        CacheKey cacheKey = new CacheKey();
        cacheKey.update(resultMapping);
        if (columns != null && names != null) {
            String[] columnsArray = columns.split(",");
            String[] namesArray = names.split(",");
            for (int i = 0; i < columnsArray.length; i++) {
                Object value = rs.getString(columnsArray[i]);
                if (value != null) {
                    cacheKey.update(namesArray[i]);
                    cacheKey.update(value);
                }
            }
        }
        return cacheKey;
    }

    /**
     * 负责创建数据库记录映射得到的结果对象,该方法会根据结果集的列数,
     * ResultMapconstructorResultmappings集合等信息,选择不同的方式创建结果对象
     *
     * @param rsw
     * @param resultMap
     * @param lazyLoader
     * @param columnPrefix
     * @return
     * @throws SQLException
     */
    private Object createResultObject(ResultSetWrapper rsw, ResultMap resultMap, ResultLoaderMap lazyLoader, String columnPrefix) throws SQLException {
        // 记录构造函数的参数类型
        final List<Class<?>> constructorArgTypes = new ArrayList<Class<?>>();
        // 记录构造函数的实参
        final List<Object> constructorArgs = new ArrayList<Object>();
        // 创建该行记录对应的结果对象,该方法是该步骤的核心
        final Object resultObject = createResultObject(rsw, resultMap, constructorArgTypes, constructorArgs, columnPrefix);

        if (resultObject != null && !typeHandlerRegistry.hasTypeHandler(resultMap.getType())) {
            final List<ResultMapping> propertyMappings = resultMap.getPropertyResultMappings();
            for (ResultMapping propertyMapping : propertyMappings) {
                // issue gcode #109 && issue #149
                // 如果包含嵌套查询,且配置了延迟加载,则创建代理对象
                if (propertyMapping.getNestedQueryId() != null && propertyMapping.isLazy()) {
                    //TODO 使用代理(cglib/javaassist)
                    return configuration.getProxyFactory().createProxy(resultObject, lazyLoader, configuration, objectFactory, constructorArgTypes, constructorArgs);
                }
            }
        }
        return resultObject;
    }

    //
    // INSTANTIATION & CONSTRUCTOR MAPPING
    //

    //创建结果对象
    private Object createResultObject(ResultSetWrapper rsw, ResultMap resultMap, List<Class<?>> constructorArgTypes, List<Object> constructorArgs, String columnPrefix)
            throws SQLException {
        // 获取ResultMap中记录的type属性, 也就是该行记录最终映射成的结果对象类型
        final Class<?> resultType = resultMap.getType();
        // 创建该类型对应的MetaClass对象
        final MetaClass metaType = MetaClass.forClass(resultType);
        // 获取ResultMap中记录的<constructor>节点信息, 如果该集合不为空,则可以通过该集合确定相应Java类中的唯一构造函数
        final List<ResultMapping> constructorMappings = resultMap.getConstructorResultMappings();
        //创建对象结果分为以下四种场景
        // 1. 结果集只有一列,且存在TypeHandler对象可以将该列转换成resultType类型的值
        if (typeHandlerRegistry.hasTypeHandler(resultType)) {
            // 先查找相应的TypeHandler对象,再使用TypeHandler对象将该记录转换成Java类型的值
            return createPrimitiveResultObject(rsw, resultMap, columnPrefix);
        } else if (!constructorMappings.isEmpty()) {
            // 2. ResultMap中记录了<constructor>节点的信息, 则通过反射方式调用构造方法,创建结果对象
            return createParameterizedResultObject(rsw, resultType, constructorMappings, constructorArgTypes, constructorArgs, columnPrefix);
        } else if (resultType.isInterface() || metaType.hasDefaultConstructor()) {
            // 3. 使用默认的无参构造函数, 则使用ObjectFactory创建对象
            //普通bean类型
            return objectFactory.create(resultType);
        } else if (shouldApplyAutomaticMappings(resultMap, false)) {
            // 4. 通过自动映射的方式查找合适的构造方法,并创建结果对象
            return createByConstructorSignature(rsw, resultType, constructorArgTypes, constructorArgs, columnPrefix);
        }
        // 初始化失败,抛出异常
        throw new ExecutorException("Do not know how to create an instance of " + resultType);
    }

    /**
     * 会根据<constructor>节点的配置,选择合适的构造方法创建结果对象,其中也会涉及嵌套查询和嵌套映射的处理
     *
     * @param rsw
     * @param resultType
     * @param constructorMappings
     * @param constructorArgTypes
     * @param constructorArgs
     * @param columnPrefix
     * @return
     * @throws SQLException
     */
    private Object createParameterizedResultObject(ResultSetWrapper rsw, Class<?> resultType, List<ResultMapping> constructorMappings,
                                                   List<Class<?>> constructorArgTypes, List<Object> constructorArgs, String columnPrefix) throws SQLException {
        boolean foundValues = false;
        // 遍历constructorMappings集合,该过程中会使用constructorArgTypes集合记录构造参数类型,
        // 使用constructorArgs集合记录构造参数实参
        for (ResultMapping constructorMapping : constructorMappings) {
            // 获取当前构造参数的类型
            final Class<?> parameterType = constructorMapping.getJavaType();
            final String column = constructorMapping.getColumn();
            final Object value;
            if (constructorMapping.getNestedQueryId() != null) {
                // 存在嵌套查询, 需要处理该查询,然后才能得到实参
                value = getNestedQueryConstructorValue(rsw.getResultSet(), constructorMapping, columnPrefix);
            } else if (constructorMapping.getNestedResultMapId() != null) {
                // 存在嵌套映射, 需要处理映射,然后才能得到实参
                final ResultMap resultMap = configuration.getResultMap(constructorMapping.getNestedResultMapId());
                value = getRowValue(rsw, resultMap);
            } else {
                // 直接获取该列的值, 然后经过TypeHandler对象的转换,得到构造函数的实参
                final TypeHandler<?> typeHandler = constructorMapping.getTypeHandler();
                value = typeHandler.getResult(rsw.getResultSet(), prependPrefix(column, columnPrefix));
            }
            // 记录当前构造参数的类型
            constructorArgTypes.add(parameterType);
            // 记录当前构造参数的实际值
            constructorArgs.add(value);
            foundValues = value != null || foundValues;
        }
        // 通过ObjectFactory调用调用匹配的构造函数,创建结果对象
        return foundValues ? objectFactory.create(resultType, constructorArgTypes, constructorArgs) : null;
    }

    /**
     * ResultSetWrapper.classNames集合记录了ResultSet中所有列对应的Java类型,
     * createByConstructorSignature()方法会根据该集合查找合适的构造函数,并创建结果对象
     *
     * @param rsw
     * @param resultType
     * @param constructorArgTypes
     * @param constructorArgs
     * @param columnPrefix
     * @return
     * @throws SQLException
     */
    private Object  createByConstructorSignature(ResultSetWrapper rsw, Class<?> resultType, List<Class<?>> constructorArgTypes, List<Object> constructorArgs,
                                                String columnPrefix) throws SQLException {
        // 遍历全部构造方法
        for (Constructor<?> constructor : resultType.getDeclaredConstructors()) {
            // 查找合适的构造方法,该构造方法的参数类型与ResultSet中列所对应的Java类型匹配
            if (typeNames(constructor.getParameterTypes()).equals(rsw.getClassNames())) {
                boolean foundValues = false;
                for (int i = 0; i < constructor.getParameterTypes().length; i++) {
                    // 获取构造函数的参数类型
                    Class<?> parameterType = constructor.getParameterTypes()[i];
                    // ResultSet中的列名
                    String columnName = rsw.getColumnNames().get(i);
                    // 查找对应的TypeHandler, 并获取该列的值
                    TypeHandler<?> typeHandler = rsw.getTypeHandler(parameterType, columnName);
                    Object value = typeHandler.getResult(rsw.getResultSet(), prependPrefix(columnName, columnPrefix));
                    // 记录构造函数的参数类型和参数值
                    constructorArgTypes.add(parameterType);
                    constructorArgs.add(value);
                    // 更新 foundValues 值
                    foundValues = value != null || foundValues;
                }
                //上面是构造函数创建对象，下面是对象工厂来创建
                // 使用ObjectFactory 调用对应的构造方法, 创建结果对象
                return foundValues ? objectFactory.create(resultType, constructorArgTypes, constructorArgs) : null;
            }
        }
        throw new ExecutorException("No constructor found in " + resultType.getName() + " matching " + rsw.getClassNames());
    }

    private List<String> typeNames(Class<?>[] parameterTypes) {
        List<String> names = new ArrayList<String>();
        for (Class<?> type : parameterTypes) {
            names.add(type.getName());
        }
        return names;
    }

    //简单类型走这里
    private Object createPrimitiveResultObject(ResultSetWrapper rsw, ResultMap resultMap, String columnPrefix) throws SQLException {
        final Class<?> resultType = resultMap.getType();
        final String columnName;
        if (!resultMap.getResultMappings().isEmpty()) {
            final List<ResultMapping> resultMappingList = resultMap.getResultMappings();
            final ResultMapping mapping = resultMappingList.get(0);
            columnName = prependPrefix(mapping.getColumn(), columnPrefix);
        } else {
            //因为只有1列，所以取得这一列的名字
            columnName = rsw.getColumnNames().get(0);
        }
        final TypeHandler<?> typeHandler = rsw.getTypeHandler(resultType, columnName);
        return typeHandler.getResult(rsw.getResultSet(), columnName);
    }

    private Object getNestedQueryConstructorValue(ResultSet rs, ResultMapping constructorMapping, String columnPrefix) throws SQLException {
        final String nestedQueryId = constructorMapping.getNestedQueryId();
        final MappedStatement nestedQuery = configuration.getMappedStatement(nestedQueryId);
        final Class<?> nestedQueryParameterType = nestedQuery.getParameterMap().getType();
        final Object nestedQueryParameterObject = prepareParameterForNestedQuery(rs, constructorMapping, nestedQueryParameterType, columnPrefix);
        Object value = null;
        if (nestedQueryParameterObject != null) {
            final BoundSql nestedBoundSql = nestedQuery.getBoundSql(nestedQueryParameterObject);
            final CacheKey key = executor.createCacheKey(nestedQuery, nestedQueryParameterObject, RowBounds.DEFAULT, nestedBoundSql);
            final Class<?> targetType = constructorMapping.getJavaType();
            final ResultLoader resultLoader = new ResultLoader(configuration, executor, nestedQuery, nestedQueryParameterObject, targetType, key, nestedBoundSql);
            value = resultLoader.loadResult();
        }
        return value;
    }

    //
    // NESTED QUERY
    //

    //得到嵌套查询值
    private Object getNestedQueryMappingValue(ResultSet rs, MetaObject metaResultObject, ResultMapping propertyMapping, ResultLoaderMap lazyLoader, String columnPrefix)
            throws SQLException {
        final String nestedQueryId = propertyMapping.getNestedQueryId();
        final String property = propertyMapping.getProperty();
        final MappedStatement nestedQuery = configuration.getMappedStatement(nestedQueryId);
        final Class<?> nestedQueryParameterType = nestedQuery.getParameterMap().getType();
        final Object nestedQueryParameterObject = prepareParameterForNestedQuery(rs, propertyMapping, nestedQueryParameterType, columnPrefix);
        Object value = NO_VALUE;
        if (nestedQueryParameterObject != null) {
            final BoundSql nestedBoundSql = nestedQuery.getBoundSql(nestedQueryParameterObject);
            final CacheKey key = executor.createCacheKey(nestedQuery, nestedQueryParameterObject, RowBounds.DEFAULT, nestedBoundSql);
            final Class<?> targetType = propertyMapping.getJavaType();
            if (executor.isCached(nestedQuery, key)) {
                //如果已经有一级缓存了，则延迟加载(实际上deferLoad方法中可以看到则是立即加载)
                executor.deferLoad(nestedQuery, metaResultObject, property, key, targetType);
            } else {
                //否则lazyLoader.addLoader 需要延迟加载则addLoader
                //或者ResultLoader.loadResult 不需要延迟加载则立即加载
                final ResultLoader resultLoader = new ResultLoader(configuration, executor, nestedQuery, nestedQueryParameterObject, targetType, key, nestedBoundSql);
                if (propertyMapping.isLazy()) {
                    lazyLoader.addLoader(property, metaResultObject, resultLoader);
                } else {
                    value = resultLoader.loadResult();
                }
            }
        }
        return value;
    }

    private Object prepareParameterForNestedQuery(ResultSet rs, ResultMapping resultMapping, Class<?> parameterType, String columnPrefix) throws SQLException {
        if (resultMapping.isCompositeResult()) {
            return prepareCompositeKeyParameter(rs, resultMapping, parameterType, columnPrefix);
        } else {
            return prepareSimpleKeyParameter(rs, resultMapping, parameterType, columnPrefix);
        }
    }

    private Object prepareSimpleKeyParameter(ResultSet rs, ResultMapping resultMapping, Class<?> parameterType, String columnPrefix) throws SQLException {
        final TypeHandler<?> typeHandler;
        if (typeHandlerRegistry.hasTypeHandler(parameterType)) {
            typeHandler = typeHandlerRegistry.getTypeHandler(parameterType);
        } else {
            typeHandler = typeHandlerRegistry.getUnknownTypeHandler();
        }
        return typeHandler.getResult(rs, prependPrefix(resultMapping.getColumn(), columnPrefix));
    }

    private Object prepareCompositeKeyParameter(ResultSet rs, ResultMapping resultMapping, Class<?> parameterType, String columnPrefix) throws SQLException {
        final Object parameterObject = instantiateParameterObject(parameterType);
        final MetaObject metaObject = configuration.newMetaObject(parameterObject);
        boolean foundValues = false;
        for (ResultMapping innerResultMapping : resultMapping.getComposites()) {
            final Class<?> propType = metaObject.getSetterType(innerResultMapping.getProperty());
            final TypeHandler<?> typeHandler = typeHandlerRegistry.getTypeHandler(propType);
            final Object propValue = typeHandler.getResult(rs, prependPrefix(innerResultMapping.getColumn(), columnPrefix));
            // issue #353 & #560 do not execute nested query if key is null
            if (propValue != null) {
                metaObject.setValue(innerResultMapping.getProperty(), propValue);
                foundValues = true;
            }
        }
        return foundValues ? parameterObject : null;
    }

    private Object instantiateParameterObject(Class<?> parameterType) {
        if (parameterType == null) {
            return new HashMap<Object, Object>();
        } else {
            return objectFactory.create(parameterType);
        }
    }

    /**
     * 会根据ResultMap中记录的Discriminator以及参与映射的列值,
     * 选择映射操作最终使用的ResultMap对象,这个选择过程可能嵌套多层
     *
     * @param rs
     * @param resultMap
     * @param columnPrefix
     * @return
     * @throws SQLException
     */
    public ResultMap resolveDiscriminatedResultMap(ResultSet rs, ResultMap resultMap, String columnPrefix) throws SQLException {
        // 记录已经处理过的ResultMap的id
        Set<String> pastDiscriminators = new HashSet<String>();
        // 获取ResultMap中的Discriminator对象
        // <discriminator>节点对应生成的是Discriminator对象并记录到ResultMap.discriminator字段中
        Discriminator discriminator = resultMap.getDiscriminator();
        while (discriminator != null) {
            // 获取记录中对应列的值,其中会使用相应的TypeHandler对象,将该值转换成Java类型
            final Object value = getDiscriminatorValue(rs, discriminator, columnPrefix);
            // 根据该列值获取对应的ResultMap的id
            final String discriminatedMapId = discriminator.getMapIdFor(String.valueOf(value));
            if (configuration.hasResultMap(discriminatedMapId)) {
                // 根据上述步骤,获取的id,查找相应的Resultmap对象
                resultMap = configuration.getResultMap(discriminatedMapId);
                // 记录当前Discriminator对象
                Discriminator lastDiscriminator = discriminator;
                // 获取ResultMap对象中的Discriminator
                discriminator = resultMap.getDiscriminator();
                // 检测是否出现了环引用
                if (discriminator == lastDiscriminator || !pastDiscriminators.add(discriminatedMapId)) {
                    break;
                }
            } else {
                break;
            }
        }
        // 该 ResultMap对象为映射最终使用的的ResultMap
        return resultMap;
    }

    //
    // DISCRIMINATOR
    //

    private Object getDiscriminatorValue(ResultSet rs, Discriminator discriminator, String columnPrefix) throws SQLException {
        final ResultMapping resultMapping = discriminator.getResultMapping();
        final TypeHandler<?> typeHandler = resultMapping.getTypeHandler();
        return typeHandler.getResult(rs, prependPrefix(resultMapping.getColumn(), columnPrefix));
    }

    private String prependPrefix(String columnName, String prefix) {
        if (columnName == null || columnName.length() == 0 || prefix == null || prefix.length() == 0) {
            return columnName;
        }
        return prefix + columnName;
    }

    private void handleRowValuesForNestedResultMap(ResultSetWrapper rsw, ResultMap resultMap, ResultHandler resultHandler, RowBounds rowBounds, ResultMapping parentMapping) throws SQLException {
        final DefaultResultContext resultContext = new DefaultResultContext();
        skipRows(rsw.getResultSet(), rowBounds);
        Object rowValue = null;
        while (shouldProcessMoreRows(resultContext, rowBounds) && rsw.getResultSet().next()) {
            final ResultMap discriminatedResultMap = resolveDiscriminatedResultMap(rsw.getResultSet(), resultMap, null);
            final CacheKey rowKey = createRowKey(discriminatedResultMap, rsw, null);
            Object partialObject = nestedResultObjects.get(rowKey);
            // issue #577 && #542
            if (mappedStatement.isResultOrdered()) {
                if (partialObject == null && rowValue != null) {
                    nestedResultObjects.clear();
                    storeObject(resultHandler, resultContext, rowValue, parentMapping, rsw.getResultSet());
                }
                rowValue = getRowValue(rsw, discriminatedResultMap, rowKey, rowKey, null, partialObject);
            } else {
                rowValue = getRowValue(rsw, discriminatedResultMap, rowKey, rowKey, null, partialObject);
                if (partialObject == null) {
                    storeObject(resultHandler, resultContext, rowValue, parentMapping, rsw.getResultSet());
                }
            }
        }
        if (rowValue != null && mappedStatement.isResultOrdered() && shouldProcessMoreRows(resultContext, rowBounds)) {
            storeObject(resultHandler, resultContext, rowValue, parentMapping, rsw.getResultSet());
        }
    }

    //
    // HANDLE NESTED RESULT MAPS
    //

    private Object getRowValue(ResultSetWrapper rsw, ResultMap resultMap, CacheKey combinedKey, CacheKey absoluteKey, String columnPrefix, Object partialObject) throws SQLException {
        final String resultMapId = resultMap.getId();
        Object resultObject = partialObject;
        if (resultObject != null) {
            final MetaObject metaObject = configuration.newMetaObject(resultObject);
            putAncestor(absoluteKey, resultObject, resultMapId, columnPrefix);
            applyNestedResultMappings(rsw, resultMap, metaObject, columnPrefix, combinedKey, false);
            ancestorObjects.remove(absoluteKey);
        } else {
            final ResultLoaderMap lazyLoader = new ResultLoaderMap();
            resultObject = createResultObject(rsw, resultMap, lazyLoader, columnPrefix);
            if (resultObject != null && !typeHandlerRegistry.hasTypeHandler(resultMap.getType())) {
                final MetaObject metaObject = configuration.newMetaObject(resultObject);
                boolean foundValues = !resultMap.getConstructorResultMappings().isEmpty();
                if (shouldApplyAutomaticMappings(resultMap, true)) {
                    foundValues = applyAutomaticMappings(rsw, resultMap, metaObject, columnPrefix) || foundValues;
                }
                foundValues = applyPropertyMappings(rsw, resultMap, metaObject, lazyLoader, columnPrefix) || foundValues;
                putAncestor(absoluteKey, resultObject, resultMapId, columnPrefix);
                foundValues = applyNestedResultMappings(rsw, resultMap, metaObject, columnPrefix, combinedKey, true) || foundValues;
                ancestorObjects.remove(absoluteKey);
                foundValues = lazyLoader.size() > 0 || foundValues;
                resultObject = foundValues ? resultObject : null;
            }
            if (combinedKey != CacheKey.NULL_CACHE_KEY) {
                nestedResultObjects.put(combinedKey, resultObject);
            }
        }
        return resultObject;
    }

    //
    // GET VALUE FROM ROW FOR NESTED RESULT MAP
    //

    private void putAncestor(CacheKey rowKey, Object resultObject, String resultMapId, String columnPrefix) {
        if (!ancestorColumnPrefix.containsKey(resultMapId)) {
            ancestorColumnPrefix.put(resultMapId, columnPrefix);
        }
        ancestorObjects.put(rowKey, resultObject);
    }

    private boolean applyNestedResultMappings(ResultSetWrapper rsw, ResultMap resultMap, MetaObject metaObject, String parentPrefix, CacheKey parentRowKey, boolean newObject) {
        boolean foundValues = false;
        for (ResultMapping resultMapping : resultMap.getPropertyResultMappings()) {
            final String nestedResultMapId = resultMapping.getNestedResultMapId();
            if (nestedResultMapId != null && resultMapping.getResultSet() == null) {
                try {
                    final String columnPrefix = getColumnPrefix(parentPrefix, resultMapping);
                    final ResultMap nestedResultMap = getNestedResultMap(rsw.getResultSet(), nestedResultMapId, columnPrefix);
                    CacheKey rowKey = null;
                    Object ancestorObject = null;
                    if (ancestorColumnPrefix.containsKey(nestedResultMapId)) {
                        rowKey = createRowKey(nestedResultMap, rsw, ancestorColumnPrefix.get(nestedResultMapId));
                        ancestorObject = ancestorObjects.get(rowKey);
                    }
                    if (ancestorObject != null) {
                        if (newObject) {
                            metaObject.setValue(resultMapping.getProperty(), ancestorObject);
                        }
                    } else {
                        rowKey = createRowKey(nestedResultMap, rsw, columnPrefix);
                        final CacheKey combinedKey = combineKeys(rowKey, parentRowKey);
                        Object rowValue = nestedResultObjects.get(combinedKey);
                        boolean knownValue = (rowValue != null);
                        final Object collectionProperty = instantiateCollectionPropertyIfAppropriate(resultMapping, metaObject);
                        if (anyNotNullColumnHasValue(resultMapping, columnPrefix, rsw.getResultSet())) {
                            rowValue = getRowValue(rsw, nestedResultMap, combinedKey, rowKey, columnPrefix, rowValue);
                            if (rowValue != null && !knownValue) {
                                if (collectionProperty != null) {
                                    final MetaObject targetMetaObject = configuration.newMetaObject(collectionProperty);
                                    targetMetaObject.add(rowValue);
                                } else {
                                    metaObject.setValue(resultMapping.getProperty(), rowValue);
                                }
                                foundValues = true;
                            }
                        }
                    }
                } catch (SQLException e) {
                    throw new ExecutorException("Error getting nested result map values for '" + resultMapping.getProperty() + "'.  Cause: " + e, e);
                }
            }
        }
        return foundValues;
    }

    //
    // NESTED RESULT MAP (JOIN MAPPING)
    //

    private String getColumnPrefix(String parentPrefix, ResultMapping resultMapping) {
        final StringBuilder columnPrefixBuilder = new StringBuilder();
        if (parentPrefix != null) {
            columnPrefixBuilder.append(parentPrefix);
        }
        if (resultMapping.getColumnPrefix() != null) {
            columnPrefixBuilder.append(resultMapping.getColumnPrefix());
        }
        return columnPrefixBuilder.length() == 0 ? null : columnPrefixBuilder.toString().toUpperCase(Locale.ENGLISH);
    }

    private boolean anyNotNullColumnHasValue(ResultMapping resultMapping, String columnPrefix, ResultSet rs) throws SQLException {
        Set<String> notNullColumns = resultMapping.getNotNullColumns();
        boolean anyNotNullColumnHasValue = true;
        if (notNullColumns != null && !notNullColumns.isEmpty()) {
            anyNotNullColumnHasValue = false;
            for (String column : notNullColumns) {
                rs.getObject(prependPrefix(column, columnPrefix));
                if (!rs.wasNull()) {
                    anyNotNullColumnHasValue = true;
                    break;
                }
            }
        }
        return anyNotNullColumnHasValue;
    }

    private ResultMap getNestedResultMap(ResultSet rs, String nestedResultMapId, String columnPrefix) throws SQLException {
        ResultMap nestedResultMap = configuration.getResultMap(nestedResultMapId);
        return resolveDiscriminatedResultMap(rs, nestedResultMap, columnPrefix);
    }

    private CacheKey createRowKey(ResultMap resultMap, ResultSetWrapper rsw, String columnPrefix) throws SQLException {
        final CacheKey cacheKey = new CacheKey();
        cacheKey.update(resultMap.getId());
        List<ResultMapping> resultMappings = getResultMappingsForRowKey(resultMap);
        if (resultMappings.size() == 0) {
            if (Map.class.isAssignableFrom(resultMap.getType())) {
                createRowKeyForMap(rsw, cacheKey);
            } else {
                createRowKeyForUnmappedProperties(resultMap, rsw, cacheKey, columnPrefix);
            }
        } else {
            createRowKeyForMappedProperties(resultMap, rsw, cacheKey, resultMappings, columnPrefix);
        }
        return cacheKey;
    }

    //
    // UNIQUE RESULT KEY
    //

    private CacheKey combineKeys(CacheKey rowKey, CacheKey parentRowKey) {
        if (rowKey.getUpdateCount() > 1 && parentRowKey.getUpdateCount() > 1) {
            CacheKey combinedKey;
            try {
                combinedKey = rowKey.clone();
            } catch (CloneNotSupportedException e) {
                throw new ExecutorException("Error cloning cache key.  Cause: " + e, e);
            }
            combinedKey.update(parentRowKey);
            return combinedKey;
        }
        return CacheKey.NULL_CACHE_KEY;
    }

    private List<ResultMapping> getResultMappingsForRowKey(ResultMap resultMap) {
        List<ResultMapping> resultMappings = resultMap.getIdResultMappings();
        if (resultMappings.size() == 0) {
            resultMappings = resultMap.getPropertyResultMappings();
        }
        return resultMappings;
    }

    private void createRowKeyForMappedProperties(ResultMap resultMap, ResultSetWrapper rsw, CacheKey cacheKey, List<ResultMapping> resultMappings, String columnPrefix) throws SQLException {
        for (ResultMapping resultMapping : resultMappings) {
            if (resultMapping.getNestedResultMapId() != null && resultMapping.getResultSet() == null) {
                // Issue #392
                final ResultMap nestedResultMap = configuration.getResultMap(resultMapping.getNestedResultMapId());
                createRowKeyForMappedProperties(nestedResultMap, rsw, cacheKey, nestedResultMap.getConstructorResultMappings(),
                        prependPrefix(resultMapping.getColumnPrefix(), columnPrefix));
            } else if (resultMapping.getNestedQueryId() == null) {
                final String column = prependPrefix(resultMapping.getColumn(), columnPrefix);
                final TypeHandler<?> th = resultMapping.getTypeHandler();
                List<String> mappedColumnNames = rsw.getMappedColumnNames(resultMap, columnPrefix);
                // Issue #114
                if (column != null && mappedColumnNames.contains(column.toUpperCase(Locale.ENGLISH))) {
                    final Object value = th.getResult(rsw.getResultSet(), column);
                    if (value != null) {
                        cacheKey.update(column);
                        cacheKey.update(value);
                    }
                }
            }
        }
    }

    private void createRowKeyForUnmappedProperties(ResultMap resultMap, ResultSetWrapper rsw, CacheKey cacheKey, String columnPrefix) throws SQLException {
        final MetaClass metaType = MetaClass.forClass(resultMap.getType());
        List<String> unmappedColumnNames = rsw.getUnmappedColumnNames(resultMap, columnPrefix);
        for (String column : unmappedColumnNames) {
            String property = column;
            if (columnPrefix != null && !columnPrefix.isEmpty()) {
                // When columnPrefix is specified, ignore columns without the prefix.
                if (column.toUpperCase(Locale.ENGLISH).startsWith(columnPrefix)) {
                    property = column.substring(columnPrefix.length());
                } else {
                    continue;
                }
            }
            if (metaType.findProperty(property, configuration.isMapUnderscoreToCamelCase()) != null) {
                String value = rsw.getResultSet().getString(column);
                if (value != null) {
                    cacheKey.update(column);
                    cacheKey.update(value);
                }
            }
        }
    }

    private void createRowKeyForMap(ResultSetWrapper rsw, CacheKey cacheKey) throws SQLException {
        List<String> columnNames = rsw.getColumnNames();
        for (String columnName : columnNames) {
            final String value = rsw.getResultSet().getString(columnName);
            if (value != null) {
                cacheKey.update(columnName);
                cacheKey.update(value);
            }
        }
    }

    private static class PendingRelation {
        public MetaObject metaObject;
        public ResultMapping propertyMapping;
    }

}
