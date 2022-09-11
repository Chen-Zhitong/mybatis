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

import org.apache.ibatis.executor.statement.StatementHandler;
import org.apache.ibatis.logging.Log;
import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.ResultHandler;
import org.apache.ibatis.session.RowBounds;
import org.apache.ibatis.transaction.Transaction;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Collections;
import java.util.List;

/**
 * @author Clinton Begin
 */

/**
 * 简单执行器
 */
public class SimpleExecutor extends BaseExecutor {

    public SimpleExecutor(Configuration configuration, Transaction transaction) {
        super(configuration, transaction);
    }

    //update
    @Override
    public int doUpdate(MappedStatement ms, Object parameter) throws SQLException {
        Statement stmt = null;
        try {
            // 获取配置对象
            Configuration configuration = ms.getConfiguration();
            StatementHandler handler = configuration.newStatementHandler(this, ms, parameter, RowBounds.DEFAULT, null, null);
            stmt = prepareStatement(handler, ms.getStatementLog());
            //StatementHandler.update
            return handler.update(stmt);
        } finally {
            closeStatement(stmt);
        }
    }

    //select
    @Override
    public <E> List<E> doQuery(MappedStatement ms, Object parameter, RowBounds rowBounds, ResultHandler resultHandler, BoundSql boundSql) throws SQLException {
        Statement stmt = null;
        try {
            Configuration configuration = ms.getConfiguration();
            //新建一个StatementHandler, 实际返回的是RoutingStatementHandler独享
            // 其中根据MappedStatement.statementType选择具体的StatementHandler实现
            StatementHandler handler = configuration.newStatementHandler(wrapper, ms, parameter, rowBounds, resultHandler, boundSql);
            //完成Statement的创建和初始化,该方法首先会调用StatementHandler.prepare()方法创建Statement对象,
            // 然后调用StatementHandler.parameterize()方法处理占位符
            stmt = prepareStatement(handler, ms.getStatementLog());
            //StatementHandler.query
            // 调用Statement.query()方法,执行SQL语句,并通过ResultSetHandler完成结果集的映射
            return handler.<E>query(stmt, resultHandler);
        } finally {
            closeStatement(stmt);
        }
    }

    @Override
    public List<BatchResult> doFlushStatements(boolean isRollback) throws SQLException {
        //doFlushStatements只是给batch用的，所以这里返回空
        return Collections.emptyList();
    }

    private Statement prepareStatement(StatementHandler handler, Log statementLog) throws SQLException {
        Statement stmt;
        Connection connection = getConnection(statementLog);
        // 创建Statement对象
        stmt = handler.prepare(connection);
        // 处理占位符
        handler.parameterize(stmt);
        return stmt;
    }

}
