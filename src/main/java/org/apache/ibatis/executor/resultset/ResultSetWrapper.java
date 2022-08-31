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
package org.apache.ibatis.executor.resultset;

import org.apache.ibatis.io.Resources;
import org.apache.ibatis.mapping.ResultMap;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.type.*;

import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.util.*;

/**
 * 记录了ResultSet中的一些元数据,并且提供了一系列ResultSet的辅助方法
 *
 * @author Iwao AVE!
 */
class ResultSetWrapper {

    // 底层封装的ResultSet对象
    private final ResultSet resultSet;
    private final TypeHandlerRegistry typeHandlerRegistry;
    // 记录了ResultSet中每列的列名
    private final List<String> columnNames = new ArrayList<String>();
    // 记录了ResultSet中每列对应的Java类型
    private final List<String> classNames = new ArrayList<String>();
    // 记录ResultSet中每列对应的JdbcType类型
    private final List<JdbcType> jdbcTypes = new ArrayList<JdbcType>();
    // 记录了每列对应的TypeHandler对象, key是列名, value是TypeHandler集合
    private final Map<String, Map<Class<?>, TypeHandler<?>>> typeHandlerMap = new HashMap<String, Map<Class<?>, TypeHandler<?>>>();
    // 记录了被映射的列名, 其中key是ResultMap对象的id, value是该ResultMap对象映射的列名集合
    private Map<String, List<String>> mappedColumnNamesMap = new HashMap<String, List<String>>();
    // 记录了未映射的列名,其中key是ResultMap对象的id, value是该ResultMap对象未映射的列名集合
    private Map<String, List<String>> unMappedColumnNamesMap = new HashMap<String, List<String>>();

    public ResultSetWrapper(ResultSet rs, Configuration configuration) throws SQLException {
        super();
        this.typeHandlerRegistry = configuration.getTypeHandlerRegistry();
        this.resultSet = rs;
        // 获取ResultSet的元信息
        final ResultSetMetaData metaData = rs.getMetaData();
        // ResultSet中的列数
        final int columnCount = metaData.getColumnCount();
        for (int i = 1; i <= columnCount; i++) {
            // 获取列名或是通过"AS"关键字指定的别名
            columnNames.add(configuration.isUseColumnLabel() ? metaData.getColumnLabel(i) : metaData.getColumnName(i));
            jdbcTypes.add(JdbcType.forCode(metaData.getColumnType(i))); // 该列的JdbcType类型
            classNames.add(metaData.getColumnClassName(i)); // 该列对应的Java类型
        }
    }

    public ResultSet getResultSet() {
        return resultSet;
    }

    public List<String> getColumnNames() {
        return this.columnNames;
    }

    public List<String> getClassNames() {
        return Collections.unmodifiableList(classNames);
    }

    /**
     * Gets the type handler to use when reading the result set.
     * Tries to get from the TypeHandlerRegistry by searching for the property type.
     * If not found it gets the column JDBC type and tries to get a handler for it.
     *
     * @param propertyType
     * @param columnName
     * @return
     */
    public TypeHandler<?> getTypeHandler(Class<?> propertyType, String columnName) {
        TypeHandler<?> handler = null;
        Map<Class<?>, TypeHandler<?>> columnHandlers = typeHandlerMap.get(columnName);
        if (columnHandlers == null) {
            columnHandlers = new HashMap<Class<?>, TypeHandler<?>>();
            typeHandlerMap.put(columnName, columnHandlers);
        } else {
            handler = columnHandlers.get(propertyType);
        }
        if (handler == null) {
            handler = typeHandlerRegistry.getTypeHandler(propertyType);
            // Replicate logic of UnknownTypeHandler#resolveTypeHandler
            // See issue #59 comment 10
            if (handler == null || handler instanceof UnknownTypeHandler) {
                final int index = columnNames.indexOf(columnName);
                final JdbcType jdbcType = jdbcTypes.get(index);
                final Class<?> javaType = resolveClass(classNames.get(index));
                if (javaType != null && jdbcType != null) {
                    handler = typeHandlerRegistry.getTypeHandler(javaType, jdbcType);
                } else if (javaType != null) {
                    handler = typeHandlerRegistry.getTypeHandler(javaType);
                } else if (jdbcType != null) {
                    handler = typeHandlerRegistry.getTypeHandler(jdbcType);
                }
            }
            if (handler == null || handler instanceof UnknownTypeHandler) {
                handler = new ObjectTypeHandler();
            }
            columnHandlers.put(propertyType, handler);
        }
        return handler;
    }

    private Class<?> resolveClass(String className) {
        try {
            return Resources.classForName(className);
        } catch (ClassNotFoundException e) {
            return null;
        }
    }

    private void loadMappedAndUnmappedColumnNames(ResultMap resultMap, String columnPrefix) throws SQLException {
        // mappedColumnNames 和 unmappedColumnNames 分别记录 ResultMap中映射的列名和未映射的列名
        List<String> mappedColumnNames = new ArrayList<String>();
        List<String> unmappedColumnNames = new ArrayList<String>();
        // 修改列名前缀为大写
        final String upperColumnPrefix = columnPrefix == null ? null : columnPrefix.toUpperCase(Locale.ENGLISH);
        // ResultMap中定义的列加上前缀,得到实际映射的列名
        final Set<String> mappedColumns = prependPrefixes(resultMap.getMappedColumns(), upperColumnPrefix);
        for (String columnName : columnNames) {
            final String upperColumnName = columnName.toUpperCase(Locale.ENGLISH);
            if (mappedColumns.contains(upperColumnName)) {
                // 记录映射的列名
                mappedColumnNames.add(upperColumnName);
            } else {
                // 记录未映射的列名
                unmappedColumnNames.add(columnName);
            }
        }
        // 将ResultMap的Id和列前缀组成key, 将ResultMap映射的列名及未映射的列名保存到
        // mappedColumnNamesMap 和 unMappedColumnnamesMap 中
        mappedColumnNamesMap.put(getMapKey(resultMap, columnPrefix), mappedColumnNames);
        unMappedColumnNamesMap.put(getMapKey(resultMap, columnPrefix), unmappedColumnNames);
    }

    public List<String> getMappedColumnNames(ResultMap resultMap, String columnPrefix) throws SQLException {
        // 在mappedColumnNamesMap集合中查找被映射的列名, 其中key是由ResultMap的id与列前缀组成
        List<String> mappedColumnNames = mappedColumnNamesMap.get(getMapKey(resultMap, columnPrefix));
        if (mappedColumnNames == null) {
            // 未查找到指定ResultMap映射的列名,则加载后存入到mappedColumnNamesMap集合中
            loadMappedAndUnmappedColumnNames(resultMap, columnPrefix);
            mappedColumnNames = mappedColumnNamesMap.get(getMapKey(resultMap, columnPrefix));
        }
        return mappedColumnNames;
    }

    public List<String> getUnmappedColumnNames(ResultMap resultMap, String columnPrefix) throws SQLException {
        List<String> unMappedColumnNames = unMappedColumnNamesMap.get(getMapKey(resultMap, columnPrefix));
        if (unMappedColumnNames == null) {
            loadMappedAndUnmappedColumnNames(resultMap, columnPrefix);
            unMappedColumnNames = unMappedColumnNamesMap.get(getMapKey(resultMap, columnPrefix));
        }
        return unMappedColumnNames;
    }

    private String getMapKey(ResultMap resultMap, String columnPrefix) {
        return resultMap.getId() + ":" + columnPrefix;
    }

    private Set<String> prependPrefixes(Set<String> columnNames, String prefix) {
        if (columnNames == null || columnNames.isEmpty() || prefix == null || prefix.length() == 0) {
            return columnNames;
        }
        final Set<String> prefixed = new HashSet<String>();
        for (String columnName : columnNames) {
            prefixed.add(prefix + columnName);
        }
        return prefixed;
    }

}
