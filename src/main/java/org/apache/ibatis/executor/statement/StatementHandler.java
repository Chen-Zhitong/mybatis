/*
 *    Copyright 2009-2012 the original author or authors.
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
package org.apache.ibatis.executor.statement;

import org.apache.ibatis.executor.parameter.ParameterHandler;
import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.session.ResultHandler;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;

/**
 * @author Clinton Begin
 */

/**
 * 语句处理器
 */
public interface StatementHandler {

    // 从连接中获取一个Statement
    Statement prepare(Connection connection)
            throws SQLException;

    // 绑定statement执行时所需的实参
    void parameterize(Statement statement)
            throws SQLException;

    // 批量执行SQL语句
    void batch(Statement statement)
            throws SQLException;

    // 执行update/inster/delete语句
    int update(Statement statement)
            throws SQLException;

    //执行select语句-->结果给ResultHandler
    <E> List<E> query(Statement statement, ResultHandler resultHandler)
            throws SQLException;

    //得到绑定sql
    BoundSql getBoundSql();

    //得到参数处理器
    ParameterHandler getParameterHandler();

}
