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
package org.apache.ibatis.logging.jdbc;

import org.apache.ibatis.logging.Log;
import org.apache.ibatis.reflection.ExceptionUtil;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Types;
import java.util.HashSet;
import java.util.Set;

/**
 * ResultSet proxy to add logging
 *
 * @author Clinton Begin
 * @author Eduardo Macarron
 */
public final class ResultSetLogger extends BaseJdbcLogger implements InvocationHandler {

    // 记录了超大长度的类型
    private static Set<Integer> BLOB_TYPES = new HashSet<Integer>();

    static {
        // 添加BLOB等类型的代码
        BLOB_TYPES.add(Types.BINARY);
        BLOB_TYPES.add(Types.BLOB);
        BLOB_TYPES.add(Types.CLOB);
        BLOB_TYPES.add(Types.LONGNVARCHAR);
        BLOB_TYPES.add(Types.LONGVARBINARY);
        BLOB_TYPES.add(Types.LONGVARCHAR);
        BLOB_TYPES.add(Types.NCLOB);
        BLOB_TYPES.add(Types.VARBINARY);
    }

    //  是否是ResultSet结果集的第一行
    private boolean first = true;
    // 统计行数
    private int rows = 0;
    // 真正的ResultSet对象
    private ResultSet rs;
    // 记录了超大字段的列编号
    private Set<Integer> blobColumns = new HashSet<Integer>();

    private ResultSetLogger(ResultSet rs, Log statementLog, int queryStack) {
        super(statementLog, queryStack);
        this.rs = rs;
    }

    /*
     * Creates a logging version of a ResultSet
     *
     * @param rs - the ResultSet to proxy
     * @return - the ResultSet with logging
     */
    public static ResultSet newInstance(ResultSet rs, Log statementLog, int queryStack) {
        InvocationHandler handler = new ResultSetLogger(rs, statementLog, queryStack);
        ClassLoader cl = ResultSet.class.getClassLoader();
        return (ResultSet) Proxy.newProxyInstance(cl, new Class[]{ResultSet.class}, handler);
    }

    /**
     * 会针对ResultSet.next() 方法调用进行一系列后置操作,
     * 通过这些后置操作会将ResultSet数据集中的记录全部输出到日志中
     *
     * @param proxy
     * @param method
     * @param params
     * @return
     * @throws Throwable
     */
    @Override
    public Object invoke(Object proxy, Method method, Object[] params) throws Throwable {
        try {
            // 如果调用的是从Object继承的方法, 则直接调用,不做任何其他处理
            if (Object.class.equals(method.getDeclaringClass())) {
                return method.invoke(this, params);
            }
            Object o = method.invoke(rs, params);
            // 针对 ResultSet.next() 方法的处理
            if ("next".equals(method.getName())) {
                // 是否还存在下一行数据
                if (((Boolean) o)) {
                    rows++;
                    if (isTraceEnabled()) {
                        ResultSetMetaData rsmd = rs.getMetaData();
                        // 获取数据集的列数
                        final int columnCount = rsmd.getColumnCount();
                        // 如果是第一行数据,则输出表头
                        if (first) {
                            first = false;
                            // 除了输出表头还会填充blobColumns集合,记录超大类型的列
                            printColumnHeaders(rsmd, columnCount);
                        }
                        // 输出该行记录, 注意会过滤掉blobColumns中记录的列, 这些列会数据较大,不会输出到日志
                        printColumnValues(columnCount);
                    }
                } else {
                    // 遍历完ResultSet之后,会输出总行数
                    debug("     Total: " + rows, false);
                }
            }
            // 清空BasejdbcLogger中的 column*集合
            clearColumnInfo();
            return o;
        } catch (Throwable t) {
            throw ExceptionUtil.unwrapThrowable(t);
        }
    }

    private void printColumnHeaders(ResultSetMetaData rsmd, int columnCount) throws SQLException {
        StringBuilder row = new StringBuilder();
        row.append("   Columns: ");
        for (int i = 1; i <= columnCount; i++) {
            if (BLOB_TYPES.contains(rsmd.getColumnType(i))) {
                blobColumns.add(i);
            }
            String colname = rsmd.getColumnLabel(i);
            row.append(colname);
            if (i != columnCount) {
                row.append(", ");
            }
        }
        trace(row.toString(), false);
    }

    private void printColumnValues(int columnCount) {
        StringBuilder row = new StringBuilder();
        row.append("       Row: ");
        for (int i = 1; i <= columnCount; i++) {
            String colname;
            try {
                if (blobColumns.contains(i)) {
                    colname = "<<BLOB>>";
                } else {
                    colname = rs.getString(i);
                }
            } catch (SQLException e) {
                // generally can't call getString() on a BLOB column
                colname = "<<Cannot Display>>";
            }
            row.append(colname);
            if (i != columnCount) {
                row.append(", ");
            }
        }
        trace(row.toString(), false);
    }

    /*
     * Get the wrapped result set
     *
     * @return the resultSet
     */
    public ResultSet getRs() {
        return rs;
    }

}
