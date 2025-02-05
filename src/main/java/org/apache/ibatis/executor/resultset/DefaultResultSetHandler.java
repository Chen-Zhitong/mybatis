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

    /**
     * 对存储过程中输出参数的相关处理
     *
     * @param cs
     * @throws SQLException
     */
    @Override
    public void handleOutputParameters(CallableStatement cs) throws SQLException {
        //  获取用户传入的实际参数,并为其创建相应的MetaObject对象
        final Object parameterObject = parameterHandler.getParameterObject();
        final MetaObject metaParam = configuration.newMetaObject(parameterObject);
        // 获取BoundSql.parameterMappings集合,其中记录了参数相关信息
        final List<ParameterMapping> parameterMappings = boundSql.getParameterMappings();
        //循环处理每个参数
        for (int i = 0; i < parameterMappings.size(); i++) {
            final ParameterMapping parameterMapping = parameterMappings.get(i);
            //只处理OUT|INOUT
            if (parameterMapping.getMode() == ParameterMode.OUT || parameterMapping.getMode() == ParameterMode.INOUT) {
                // 如果存在输出类型的参数,则解析参数值,并设置到parameterObject中
                if (ResultSet.class.equals(parameterMapping.getJavaType())) {
                    //如果是ResultSet型(游标)
                    //#{result, jdbcType=CURSOR, mode=OUT, javaType=ResultSet, resultMap=userResultMap}
                    //先用CallableStatement.getObject取得这个游标，作为参数传进去
                    // 如果指定该输出参数为ResultSet类型,则需要解析
                    handleRefCursorOutputParameter((ResultSet) cs.getObject(i + 1), parameterMapping, metaParam);
                } else {
                    //否则是普通型，核心就是CallableStatement.getXXX取得值
                    // 使用TypeHandler获取参数值,并设置到parameterObject中
                    final TypeHandler<?> typeHandler = parameterMapping.getTypeHandler();
                    metaParam.setValue(parameterMapping.getProperty(), typeHandler.getResult(cs, i + 1));
                }
            }
        }
    }

    //
    // HANDLE OUTPUT PARAMETER
    //

    // 负责处理ResultSet类型的输出参数(存储过程?), 它会按照指定的的ResultMap对该ResultSet类型的输出参数进行映射,
    // 并将映射得到的结果对象设置到用户传入的parameterObject对象中
    private void handleRefCursorOutputParameter(ResultSet rs, ParameterMapping parameterMapping, MetaObject metaParam) throws SQLException {
        try {
            // 获取映射使用的ResultMap对象
            final String resultMapId = parameterMapping.getResultMapId();
            final ResultMap resultMap = configuration.getResultMap(resultMapId);
            // 创建用于保存映射结果对象的DefaultResultHandler对象
            final DefaultResultHandler resultHandler = new DefaultResultHandler(objectFactory);
            // 将结果集封装成ResultSetWrapper
            final ResultSetWrapper rsw = new ResultSetWrapper(rs, configuration);
            // 通过handlerRowValues()方法完成映射操作,并将结果对象保存到DefaultResultHandler中
            handleRowValues(rsw, resultMap, resultHandler, new RowBounds(), null);
            // 将映射得到的结果对象保存到parameterObject中
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
                    // 获取映射该结果集要使用的Resultmap对象
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
        // 创建CacheKey对象
        CacheKey parentKey = createKeyForMultipleResults(rs, parentMapping, parentMapping.getColumn(), parentMapping.getForeignColumn());
        // 获取pendingRelations集合中parentKey对应的pendingRelation对象
        List<PendingRelation> parents = pendingRelations.get(parentKey);
        // 遍历PendingRelations集合
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

    /**
     * 将外层对象中Collection类型的属性进行初始化,并返回该集合对象
     *
     * @param resultMapping
     * @param metaObject
     * @return
     */
    private Object instantiateCollectionPropertyIfAppropriate(ResultMapping resultMapping, MetaObject metaObject) {
        // 获取指定的属性名称和当前属性值
        final String propertyName = resultMapping.getProperty();
        Object propertyValue = metaObject.getValue(propertyName);
        // 检测该属性是否已经初始化
        if (propertyValue == null) {
            // 获取属性的java类型
            Class<?> type = resultMapping.getJavaType();
            if (type == null) {
                type = metaObject.getSetterType(propertyName);
            }
            try {
                // 指定属性为集合类型
                if (objectFactory.isCollection(type)) {
                    // 通过ObjectFactory创建该类型的集合对象,并进行相应设置
                    propertyValue = objectFactory.create(type);
                    metaObject.setValue(propertyName, propertyValue);
                    return propertyValue;
                }
            } catch (Exception e) {
                throw new ExecutorException("Error instantiating collection property for result '" + resultMapping.getProperty() + "'.  Cause: " + e, e);
            }
        } else if (objectFactory.isCollection(propertyValue.getClass())) {
            // 指定属性是集合类型且已经初始化,则返回该属性值
            return propertyValue;
        }
        return null;
    }

    private void addPendingChildRelation(ResultSet rs, MetaObject metaResultObject, ResultMapping parentMapping) throws SQLException {
        // 1. 为指定结果集创建 CacheKey 对象
        CacheKey cacheKey = createKeyForMultipleResults(rs, parentMapping, parentMapping.getColumn(), parentMapping.getColumn());
        // 2. 创建 PendingRelation 对象
        PendingRelation deferLoad = new PendingRelation();
        deferLoad.metaObject = metaResultObject;
        deferLoad.propertyMapping = parentMapping;
        // 3. 将pendingRelation对象添加到pendingRelations集合缓存
        List<PendingRelation> relations = pendingRelations.get(cacheKey);
        // issue #255
        if (relations == null) {
            relations = new ArrayList<DefaultResultSetHandler.PendingRelation>();
            pendingRelations.put(cacheKey, relations);
        }
        relations.add(deferLoad);
        // 4. 在nextResultmaps集合记录指定属性对应的结果集名称以及对应的ResultMapping对象
        ResultMapping previous = nextResultMaps.get(parentMapping.getResultSet());
        if (previous == null) {
            nextResultMaps.put(parentMapping.getResultSet(), parentMapping);
        } else {
            // 如果同名的结果集对应不同的Resultmapping, 则抛出异常
            if (!previous.equals(parentMapping)) {
                throw new ExecutorException("Two different properties are mapped to the same resultSet");
            }
        }
    }

    private CacheKey createKeyForMultipleResults(ResultSet rs, ResultMapping resultMapping, String names, String columns) throws SQLException {
        CacheKey cacheKey = new CacheKey();
        cacheKey.update(resultMapping); // 添加Resultmapping
        if (columns != null && names != null) {
            // 按照逗号切分别名
            String[] columnsArray = columns.split(",");
            String[] namesArray = names.split(",");
            for (int i = 0; i < columnsArray.length; i++) {
                // 查询该列记录对应列的值
                Object value = rs.getString(columnsArray[i]);
                if (value != null) {
                    // 添加列名和列值
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
                // 默认是用的是JavassistProxyFactory
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
        // 获取嵌套查询的id以及对应的MappedStatement对象
        final String nestedQueryId = constructorMapping.getNestedQueryId();
        final MappedStatement nestedQuery = configuration.getMappedStatement(nestedQueryId);
        final Class<?> nestedQueryParameterType = nestedQuery.getParameterMap().getType();
        final Object nestedQueryParameterObject = prepareParameterForNestedQuery(rs, constructorMapping, nestedQueryParameterType, columnPrefix);
        // 获取传递给嵌套查询的参数值
        Object value = null;
        if (nestedQueryParameterObject != null) {
            // 获取嵌套查询对应的BoundSql对象和相应的CacheKey对象
            final BoundSql nestedBoundSql = nestedQuery.getBoundSql(nestedQueryParameterObject);
            final CacheKey key = executor.createCacheKey(nestedQuery, nestedQueryParameterObject, RowBounds.DEFAULT, nestedBoundSql);
            // 获取嵌套查询结果集经过映射后的目标类型
            final Class<?> targetType = constructorMapping.getJavaType();
            // 创建ResultLoader对象,并调用loadResult()方法执行嵌套查询,得到相应构造方法的参数值
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
        // 获取嵌套查询的id和对应MappedSatement的对象
        final String nestedQueryId = propertyMapping.getNestedQueryId();
        final String property = propertyMapping.getProperty();
        // 获取传递给嵌套查询的参数类型和参数值
        final MappedStatement nestedQuery = configuration.getMappedStatement(nestedQueryId);
        final Class<?> nestedQueryParameterType = nestedQuery.getParameterMap().getType();
        final Object nestedQueryParameterObject = prepareParameterForNestedQuery(rs, propertyMapping, nestedQueryParameterType, columnPrefix);
        Object value = NO_VALUE;
        if (nestedQueryParameterObject != null) {
            // 获取嵌套查询对应的BoundSql对象和相应的CacheKey对象
            final BoundSql nestedBoundSql = nestedQuery.getBoundSql(nestedQueryParameterObject);
            final CacheKey key = executor.createCacheKey(nestedQuery, nestedQueryParameterObject, RowBounds.DEFAULT, nestedBoundSql);
            // 获取嵌套查询结果集经过映射后的目标类型
            final Class<?> targetType = propertyMapping.getJavaType();
            // 检测缓存中是否存在该嵌套查询的结果对象
            if (executor.isCached(nestedQuery, key)) {
                // 创建DeferredLoad对象,并通过该DeferredLoad对象从缓存中加载结果
                executor.deferLoad(nestedQuery, metaResultObject, property, key, targetType);
            } else {
                // 如果该属性配置了延迟加载,则将其添加到ResultLoaderMap中,等到真正使用时
                // 再执行嵌套查询并得到结果对象
                final ResultLoader resultLoader = new ResultLoader(configuration, executor, nestedQuery, nestedQueryParameterObject, targetType, key, nestedBoundSql);
                if (propertyMapping.isLazy()) {
                    // 如果该属性配置了延迟加载,则将其添加到ResultLoaderMap中, 等待真正使用时
                    // 再执行嵌套查询并得到结果对象
                    lazyLoader.addLoader(property, metaResultObject, resultLoader);
                } else {
                    //没有配置延迟加载,则直接调用resultLoader.loadResult()方法执行嵌套查询,
                    // 并映射得到结果对象
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
        // 创建DefaultResultContext
        final DefaultResultContext resultContext = new DefaultResultContext();
        // 定位到指定记录行
        skipRows(rsw.getResultSet(), rowBounds);
        Object rowValue = null;
        // shouldProcessMoreRows() 检测是否能继续映射结果集中剩余的记录行
        while (shouldProcessMoreRows(resultContext, rowBounds) && rsw.getResultSet().next()) {
            // 调用resolveDiscriminatedResultMap()方法,它根据ResultMap中记录的Discriminator对象以及参与映射的记录行中相应列值
            // 决定映射使用的ResultMap对象
            final ResultMap discriminatedResultMap = resolveDiscriminatedResultMap(rsw.getResultSet(), resultMap, null);
            // 通过createRowkey()方法为该行记录生成CacheKey, CacheKey除了作为缓存中的key值,
            // 在嵌套映射中也作为key唯一标识一个结果对象.
            final CacheKey rowKey = createRowKey(discriminatedResultMap, rsw, null);
            // 查询nestedResultObjects集合
            // nestedResultObjects字段是一个HashMap对象.
            // 在处理嵌套映射过程中生成的所有结果对象(包括嵌套映射生成的对象),都会生成相应的Cachekey并保存到该集合中
            Object partialObject = nestedResultObjects.get(rowKey);
            // issue #577 && #542
            // 检测<select>节点中的resultOrdered属性的配置, 该设置仅针对嵌套映射有效
            // 当resultOrdered属性为true时, 则认为返回一个主结果行时,会提前释放nestedResultObjects集合中的数据,
            // 避免在进行嵌套映射出现内存不足的情况.
            // nestedResultObjects集合中的数据在映射完一个结果集时会进行清理,这是为映射下一个结果集做准备
            //
            // 最后要注意的是，resultOrdered属性虽然可以减小内存使用，但相应的代价就是要求用户在编写Select语句时需要特别注意，
            // 避免出现引用已清除的主结果对象（也就是嵌套映射的外层对象，本例中就是id为1的Blog对象）的情况，
            // 例如，分组等方式就可以避免这种情况。这就需要在应用程序的内存、SQL语句的复杂度以及给数据库带来的压力等多方面进行权衡了。
            if (mappedStatement.isResultOrdered()) {
                if (partialObject == null && rowValue != null) {
                    nestedResultObjects.clear();
                    storeObject(resultHandler, resultContext, rowValue, parentMapping, rsw.getResultSet());
                }
                //  调用getRowValue完成当前记录行的映射操作并返回结果,还会将结果对象添加到nestedResultObjects集合中
                rowValue = getRowValue(rsw, discriminatedResultMap, rowKey, rowKey, null, partialObject);
            } else {
                //  调用getRowValue完成当前记录行的映射操作并返回结果,还会将结果对象添加到nestedResultObjects集合中
                rowValue = getRowValue(rsw, discriminatedResultMap, rowKey, rowKey, null, partialObject);
                if (partialObject == null) {
                    // 将生成的结果对象保存到ResultHandler中
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

    /**
     * 负责对数据集中的一行记录进行映射. 这是嵌套映射的重载
     *
     * @param rsw
     * @param resultMap
     * @param combinedKey
     * @param absoluteKey
     * @param columnPrefix
     * @param partialObject
     * @return
     * @throws SQLException
     */
    private Object getRowValue(ResultSetWrapper rsw, ResultMap resultMap, CacheKey combinedKey, CacheKey absoluteKey, String columnPrefix, Object partialObject) throws SQLException {
        final String resultMapId = resultMap.getId();
        Object resultObject = partialObject;
        // 1. 检测外层对象是否存在, 会出现两条处理分支
        if (resultObject != null) {
            final MetaObject metaObject = configuration.newMetaObject(resultObject);
            //  3.1 将外层对象添加到ancestorObjects集合中
            putAncestor(absoluteKey, resultObject, resultMapId, columnPrefix);
            // 3.2  处理嵌套映射
            applyNestedResultMappings(rsw, resultMap, metaObject, columnPrefix, combinedKey, false);
            // 3.3  将外层对象从ancestorObjects集合中移除
            ancestorObjects.remove(absoluteKey);
        } else {
            // 延迟加载
            final ResultLoaderMap lazyLoader = new ResultLoaderMap();
            // 2.1 创建外层对象
            resultObject = createResultObject(rsw, resultMap, lazyLoader, columnPrefix);
            if (resultObject != null && !typeHandlerRegistry.hasTypeHandler(resultMap.getType())) {
                final MetaObject metaObject = configuration.newMetaObject(resultObject);
                // 更新foundValues, 其含义与简单映射中同名变量相同: 成功映射任意属性,则foundValues为true;
                // 否则 foundValues 为 false
                boolean foundValues = !resultMap.getConstructorResultMappings().isEmpty();
                // 2.2 自动映射
                if (shouldApplyAutomaticMappings(resultMap, true)) {
                    foundValues = applyAutomaticMappings(rsw, resultMap, metaObject, columnPrefix) || foundValues;
                }
                // 2.3 映射ResultMap中明确指定的字段
                foundValues = applyPropertyMappings(rsw, resultMap, metaObject, lazyLoader, columnPrefix) || foundValues;
                // 2.4 将外层对象提那家到 ancestorObjects集合中
                putAncestor(absoluteKey, resultObject, resultMapId, columnPrefix);
                // 2.5 处理嵌套映射(同3.2)
                foundValues = applyNestedResultMappings(rsw, resultMap, metaObject, columnPrefix, combinedKey, true) || foundValues;
                // 2.6 将外层该对象从ancestorObjects集合中移除
                ancestorObjects.remove(absoluteKey);
                foundValues = lazyLoader.size() > 0 || foundValues;
                resultObject = foundValues ? resultObject : null;
            }
            if (combinedKey != CacheKey.NULL_CACHE_KEY) {
                // 2.7 将外层对象保存到nestedResultObjects集合中,待映射后续记录时使用
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

    /**
     * 处理嵌套映射的核心逻辑,
     * 会遍历Result.propertyResultMappings集合中记录的ResultMapping对象,
     * 并处理其中的嵌套映射
     *
     * @param rsw
     * @param resultMap
     * @param metaObject
     * @param parentPrefix
     * @param parentRowKey
     * @param newObject
     * @return
     */
    private boolean applyNestedResultMappings(ResultSetWrapper rsw, ResultMap resultMap, MetaObject metaObject, String parentPrefix, CacheKey parentRowKey, boolean newObject) {
        boolean foundValues = false;
        for (ResultMapping resultMapping : resultMap.getPropertyResultMappings()) {
            // 1.1 获取nestedResultMapId字段值,该值不为空则表示存在相应的嵌套映射要处理
            final String nestedResultMapId = resultMapping.getNestedResultMapId();
            // 1.2  同时还会检测ResultMapping.resultSet字段,它指定了要映射的结果集名称
            if (nestedResultMapId != null && resultMapping.getResultSet() == null) {
                try {
                    // 获取列前缀
                    final String columnPrefix = getColumnPrefix(parentPrefix, resultMapping);
                    // 2. 确定嵌套映射使用的ResultMap对象
                    final ResultMap nestedResultMap = getNestedResultMap(rsw.getResultSet(), nestedResultMapId, columnPrefix);
                    CacheKey rowKey = null;
                    Object ancestorObject = null;
                    // 3. 处理循环引用的情况
                    if (ancestorColumnPrefix.containsKey(nestedResultMapId)) {
                        rowKey = createRowKey(nestedResultMap, rsw, ancestorColumnPrefix.get(nestedResultMapId));
                        ancestorObject = ancestorObjects.get(rowKey);
                    }
                    if (ancestorObject != null) {
                        if (newObject) {
                            metaObject.setValue(resultMapping.getProperty(), ancestorObject);
                        }
                    } else {
                        // 4. 为嵌套对象创建CacheKey独享
                        rowKey = createRowKey(nestedResultMap, rsw, columnPrefix);
                        final CacheKey combinedKey = combineKeys(rowKey, parentRowKey);
                        // 查找nestedResultObjects集合中是否有相同的key的嵌套对象
                        Object rowValue = nestedResultObjects.get(combinedKey);
                        boolean knownValue = (rowValue != null);

                        // 5. 初始化外层对象中Collection类型的属性
                        final Object collectionProperty = instantiateCollectionPropertyIfAppropriate(resultMapping, metaObject);
                        // 6. 根据notNullColumn属性检测结果集中的控制
                        if (anyNotNullColumnHasValue(resultMapping, columnPrefix, rsw.getResultSet())) {
                            // 7. 完成嵌套映射,并生成嵌套对象
                            rowValue = getRowValue(rsw, nestedResultMap, combinedKey, rowKey, columnPrefix, rowValue);
                            // 注意,"!knowValue"这个条件,当嵌套对象已存在于nestedResultObject集合中时,
                            // 说明相关列已经映射成了嵌套对象. 闲假设对象A中有b1和b2两个属性都指向了对象B且
                            // 这两个属性都是由同一ResultMap进行映射的. 在对一行记录进行映射时,
                            // 首先映射的b1属性会生成B对象,且成功赋值, 而b2属性则为null
                            if (rowValue != null && !knownValue) {
                                // 8 将步骤7中得到的嵌套对象保存到外层对象的相应属性中
                                // 根据属性是否为集合类型,调用MetaObject相应方法,将嵌套对象记录到外层对象相应属性中
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
        // 创建CacheKey对象
        final CacheKey cacheKey = new CacheKey();
        // 将ResultMap的id作为CacheKey的一部分
        cacheKey.update(resultMap.getId());
        // 查找ResultMapping对象集合
        List<ResultMapping> resultMappings = getResultMappingsForRowKey(resultMap);
        //  没有找到任何ResultMapping
        if (resultMappings.size() == 0) {
            if (Map.class.isAssignableFrom(resultMap.getType())) {
                // 由结果集的所有列名以及当前记录行的所有列值一起构成CahceKey对象
                createRowKeyForMap(rsw, cacheKey);
            } else {
                // 由结果集中未映射的列名以及它们在当前记录行中对应值一起构成CacheKey对象
                createRowKeyForUnmappedProperties(resultMap, rsw, cacheKey, columnPrefix);
            }
        } else {
            // 由resultMappings集合中的列名以及它们在当前记录行中相应的列值一起构成CacheKey
            createRowKeyForMappedProperties(resultMap, rsw, cacheKey, resultMappings, columnPrefix);
        }
        return cacheKey;
    }

    //
    // UNIQUE RESULT KEY
    //

    /**
     * 将嵌套对象和外层对象的CacheKey合并,最终得到嵌套对象的真正CacheKey,
     * 此时可以认为该CacheKey全局唯一
     *
     * @param rowKey
     * @param parentRowKey
     * @return
     */
    private CacheKey combineKeys(CacheKey rowKey, CacheKey parentRowKey) {
        if (rowKey.getUpdateCount() > 1 && parentRowKey.getUpdateCount() > 1) {
            CacheKey combinedKey;
            try {
                combinedKey = rowKey.clone();// 克隆
            } catch (CloneNotSupportedException e) {
                throw new ExecutorException("Error cloning cache key.  Cause: " + e, e);
            }
            // 与外层对象的CacheKey合并,形成嵌套对象的最终CacheKey
            combinedKey.update(parentRowKey);
            return combinedKey;
        }
        return CacheKey.NULL_CACHE_KEY;
    }

    /**
     * 首先检查ResultMap中是否定义了<idArg>节点或<id>节点,如果是,则返回
     * ResultMap.idResultMappings集合,否则返回ResultMap.propertyResultMappings集合
     *
     * @param resultMap
     * @return
     */
    private List<ResultMapping> getResultMappingsForRowKey(ResultMap resultMap) {
        // ResultMap.idResultMappings集合中记录<idArg>和<id>节点对应的ResultMapping对象
        List<ResultMapping> resultMappings = resultMap.getIdResultMappings();
        if (resultMappings.size() == 0) {
            // ResultMap.propertyResultMappings集合记录了除<id*>节点之外的ResultMapping对象
            resultMappings = resultMap.getPropertyResultMappings();
        }
        return resultMappings;
    }

    /**
     * 通过CacheKey.update()方法将指定的列名以及它们在当前记录行中相应的列值添加到CacheKey中,
     * 使其成为构成CacheKey对象的一部分
     *
     * @param resultMap
     * @param rsw
     * @param cacheKey
     * @param resultMappings
     * @param columnPrefix
     * @throws SQLException
     */
    private void createRowKeyForMappedProperties(ResultMap resultMap, ResultSetWrapper rsw, CacheKey cacheKey, List<ResultMapping> resultMappings, String columnPrefix) throws SQLException {
        // 遍历所有resultMappings集合
        for (ResultMapping resultMapping : resultMappings) {
            // 如果存在嵌套映射, 递归调用createRowKeyForMappedProperties()方法进行处理
            if (resultMapping.getNestedResultMapId() != null && resultMapping.getResultSet() == null) {
                // Issue #392
                final ResultMap nestedResultMap = configuration.getResultMap(resultMapping.getNestedResultMapId());
                createRowKeyForMappedProperties(nestedResultMap, rsw, cacheKey, nestedResultMap.getConstructorResultMappings(),
                        prependPrefix(resultMapping.getColumnPrefix(), columnPrefix));
            } else if (resultMapping.getNestedQueryId() == null) { // 忽略嵌套查询
                // 获取该列的名称
                final String column = prependPrefix(resultMapping.getColumn(), columnPrefix);
                // 获取相应的TypeHandler对象
                final TypeHandler<?> th = resultMapping.getTypeHandler();
                // 获取映射的列名
                List<String> mappedColumnNames = rsw.getMappedColumnNames(resultMap, columnPrefix);
                // Issue #114
                if (column != null && mappedColumnNames.contains(column.toUpperCase(Locale.ENGLISH))) {
                    // 获取列值
                    final Object value = th.getResult(rsw.getResultSet(), column);
                    if (value != null) {
                        // 将列名和列值添加到CacheKey对象中
                        cacheKey.update(column);
                        cacheKey.update(value);
                    }
                }
            }
        }
    }

    /**
     * 通过CacheKey.update()方法将指定的列名以及它们在当前记录行中相应的列值添加到CacheKey中,
     * 使其成为构成CacheKey对象的一部分
     *
     * @param resultMap
     * @param rsw
     * @param cacheKey
     * @param columnPrefix
     * @throws SQLException
     */
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

    /**
     * 通过CacheKey.update()方法将指定的列名以及它们在当前记录行中相应的列值添加到CacheKey中,
     * 使其成为构成CacheKey对象的一部分
     *
     * @param rsw
     * @param cacheKey
     * @throws SQLException
     */
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
