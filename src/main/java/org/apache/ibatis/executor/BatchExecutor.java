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
package org.apache.ibatis.executor;

import org.apache.ibatis.executor.keygen.Jdbc3KeyGenerator;
import org.apache.ibatis.executor.keygen.KeyGenerator;
import org.apache.ibatis.executor.keygen.NoKeyGenerator;
import org.apache.ibatis.executor.statement.StatementHandler;
import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.ResultHandler;
import org.apache.ibatis.session.RowBounds;
import org.apache.ibatis.transaction.Transaction;

import java.sql.BatchUpdateException;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * @author Jeff Butler
 */
public class BatchExecutor extends BaseExecutor {

    public static final int BATCH_UPDATE_RETURN_VALUE = Integer.MIN_VALUE + 1002;

    // 缓存多个Statement对象,其中每个Statement对象都缓存了多条SQL语句
    private final List<Statement> statementList = new ArrayList<Statement>();
    // 记录批处理的结果,BatchResult中通过updateCounts字段(int[]数组类型)记录每个
    // Statement执行批的结果
    private final List<BatchResult> batchResultList = new ArrayList<BatchResult>();
    // 记录当前执行的SQL语句
    private String currentSql;
    // 记录当前执行的MappedStatement对象
    private MappedStatement currentStatement;

    public BatchExecutor(Configuration configuration, Transaction transaction) {
        super(configuration, transaction);
    }

    @Override
    public int doUpdate(MappedStatement ms, Object parameterObject) throws SQLException {
        // 获取配置对象
        final Configuration configuration = ms.getConfiguration();
        // 创建StatementHandler对象
        final StatementHandler handler = configuration.newStatementHandler(this, ms, parameterObject, RowBounds.DEFAULT, null, null);
        final BoundSql boundSql = handler.getBoundSql();
        // 获取SQL语句
        final String sql = boundSql.getSql();
        final Statement stmt;
        // 如果当前执行的SQL模式与上次执行的SQL模式相同,且对应的MappedStatement对象相同
        if (sql.equals(currentSql) && ms.equals(currentStatement)) {
            // 获取statementList集合中最后一个Statement对象
            int last = statementList.size() - 1;
            stmt = statementList.get(last);
            // 查找对应的BatchResult对象
            BatchResult batchResult = batchResultList.get(last);
            batchResult.addParameterObject(parameterObject);
        } else {
            Connection connection = getConnection(ms.getStatementLog());
            // 创建新的Statemeng对象
            stmt = handler.prepare(connection);
            // 板顶实参,处理"?"占位符
            currentSql = sql;
            // 更新currentSql和currentStatement
            currentStatement = ms;
            // 将创建的Statement对象添加到statementList集合中
            statementList.add(stmt);
            // 添加新的BatchResult对象
            batchResultList.add(new BatchResult(ms, sql, parameterObject));
        }
        //记录用户传入的实参
        handler.parameterize(stmt);
        // 底层通过调用Statement.addBatch()方法添加SQL语句
        handler.batch(stmt);
        return BATCH_UPDATE_RETURN_VALUE;
    }

    @Override
    public <E> List<E> doQuery(MappedStatement ms, Object parameterObject, RowBounds rowBounds, ResultHandler resultHandler, BoundSql boundSql)
            throws SQLException {
        Statement stmt = null;
        try {
            flushStatements();
            Configuration configuration = ms.getConfiguration();
            StatementHandler handler = configuration.newStatementHandler(wrapper, ms, parameterObject, rowBounds, resultHandler, boundSql);
            Connection connection = getConnection(ms.getStatementLog());
            stmt = handler.prepare(connection);
            handler.parameterize(stmt);
            return handler.<E>query(stmt, resultHandler);
        } finally {
            closeStatement(stmt);
        }
    }

    @Override
    public List<BatchResult> doFlushStatements(boolean isRollback) throws SQLException {
        try {
            // results集合用于存储批处理的结果
            List<BatchResult> results = new ArrayList<BatchResult>();
            // 如果明确指定了要回滚事务,则直接返回空集合,忽略statementList集合中记录的SQL语句
            if (isRollback) {
                return Collections.emptyList();
            }
            // 遍历statementList集合
            for (int i = 0, n = statementList.size(); i < n; i++) {
                // 获取Statement对象
                Statement stmt = statementList.get(i);
                // 获取对应的BatchResult对象
                BatchResult batchResult = batchResultList.get(i);
                try {
                    // 调用Statement.executeBatch()方法批量执行其中记录的SQL语句,并使用返回的int数组
                    // 更新BatchResult.updateCounts字段, 其中每一个元素都表示一条SQL语句影响的二级路条数
                    batchResult.setUpdateCounts(stmt.executeBatch());
                    MappedStatement ms = batchResult.getMappedStatement();
                    List<Object> parameterObjects = batchResult.getParameterObjects();
                    // 获取配置的KeyGenerator对象
                    KeyGenerator keyGenerator = ms.getKeyGenerator();
                    if (Jdbc3KeyGenerator.class.equals(keyGenerator.getClass())) {
                        Jdbc3KeyGenerator jdbc3KeyGenerator = (Jdbc3KeyGenerator) keyGenerator;
                        // 获取数据库生成的主键,并设置到parameterObjects
                        jdbc3KeyGenerator.processBatch(ms, stmt, parameterObjects);
                    } else if (!NoKeyGenerator.class.equals(keyGenerator.getClass())) { //issue #141
                        // 对于其他类型的KeyGenerator,会调用processAfter()方法
                        for (Object parameter : parameterObjects) {
                            keyGenerator.processAfter(this, ms, stmt, parameter);
                        }
                    }
                } catch (BatchUpdateException e) {
                    StringBuilder message = new StringBuilder();
                    message.append(batchResult.getMappedStatement().getId())
                            .append(" (batch index #")
                            .append(i + 1)
                            .append(")")
                            .append(" failed.");
                    if (i > 0) {
                        message.append(" ")
                                .append(i)
                                .append(" prior sub executor(s) completed successfully, but will be rolled back.");
                    }
                    throw new BatchExecutorException(message.toString(), e, results, batchResult);
                }

                // 添加BatchResult到result集合
                results.add(batchResult);
            }
            return results;
        } finally {
            // 关闭所有Statement对象,并清空currentSql字段,清空statementList集合
            for (Statement stmt : statementList) {
                closeStatement(stmt);
            }
            currentSql = null;
            statementList.clear();
            // 清空batchResultList集合
            batchResultList.clear();
        }
    }

}
