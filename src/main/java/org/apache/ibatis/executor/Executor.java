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

import org.apache.ibatis.cache.CacheKey;
import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.reflection.MetaObject;
import org.apache.ibatis.session.ResultHandler;
import org.apache.ibatis.session.RowBounds;
import org.apache.ibatis.transaction.Transaction;

import java.sql.SQLException;
import java.util.List;

/**
 * @author Clinton Begin
 */

/**
 * 执行器
 */
public interface Executor {

    //不需要ResultHandler
    ResultHandler NO_RESULT_HANDLER = null;

    //  执行 update, insert, delete三种类型的SQL语句
    int update(MappedStatement ms, Object parameter) throws SQLException;

    //查询，带分页，带缓存，BoundSql
    <E> List<E> query(MappedStatement ms, Object parameter, RowBounds rowBounds, ResultHandler resultHandler, CacheKey cacheKey, BoundSql boundSql) throws SQLException;

    //查询，带分页
    <E> List<E> query(MappedStatement ms, Object parameter, RowBounds rowBounds, ResultHandler resultHandler) throws SQLException;

    //  批量执行SQL语句
    List<BatchResult> flushStatements() throws SQLException;

    //提交和回滚，参数是是否要强制
    void commit(boolean required) throws SQLException;

    void rollback(boolean required) throws SQLException;

    //创建缓存中用到的CacheKey对象
    CacheKey createCacheKey(MappedStatement ms, Object parameterObject, RowBounds rowBounds, BoundSql boundSql);

    // 根据CacheKey对象查找缓存
    boolean isCached(MappedStatement ms, CacheKey key);

    // 清空一级缓存
    void clearLocalCache();

    // 延迟加载一级缓存中的数据
    void deferLoad(MappedStatement ms, MetaObject resultObject, String property, CacheKey key, Class<?> targetType);

    // 获取事务对象
    Transaction getTransaction();

    // 关闭Executor对象
    void close(boolean forceRollback);

    // 检测Executor对象是否关闭
    boolean isClosed();

    void setExecutorWrapper(Executor executor);

}
