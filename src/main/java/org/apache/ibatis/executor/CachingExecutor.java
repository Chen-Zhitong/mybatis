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

import org.apache.ibatis.cache.Cache;
import org.apache.ibatis.cache.CacheKey;
import org.apache.ibatis.cache.TransactionalCacheManager;
import org.apache.ibatis.mapping.*;
import org.apache.ibatis.reflection.MetaObject;
import org.apache.ibatis.session.ResultHandler;
import org.apache.ibatis.session.RowBounds;
import org.apache.ibatis.transaction.Transaction;

import java.sql.SQLException;
import java.util.List;

/**
 * @author Clinton Begin
 * @author Eduardo Macarron
 */

/**
 * 二级缓存执行器,是一个Executor接口的装饰器,它为Executor对象增加了二级缓存功能
 * Mybatis中提供的二级缓存是应用级别的缓存,它的什么周期与应用程序的生命周期相同.
 */
public class CachingExecutor implements Executor {

    private Executor delegate;
    private TransactionalCacheManager tcm = new TransactionalCacheManager();

    public CachingExecutor(Executor delegate) {
        this.delegate = delegate;
        delegate.setExecutorWrapper(this);
    }

    @Override
    public Transaction getTransaction() {
        return delegate.getTransaction();
    }

    @Override
    public void close(boolean forceRollback) {
        try {
            //issues #499, #524 and #573
            if (forceRollback) {
                tcm.rollback();
            } else {
                tcm.commit();
            }
        } finally {
            delegate.close(forceRollback);
        }
    }

    @Override
    public boolean isClosed() {
        return delegate.isClosed();
    }

    @Override
    public int update(MappedStatement ms, Object parameterObject) throws SQLException {
        //刷新缓存完再update
        flushCacheIfRequired(ms);
        return delegate.update(ms, parameterObject);
    }

    @Override
    public <E> List<E> query(MappedStatement ms, Object parameterObject, RowBounds rowBounds, ResultHandler resultHandler) throws SQLException {
        // 1. 获取BoundSql对象
        BoundSql boundSql = ms.getBoundSql(parameterObject);
        //query时传入一个cachekey参数
        // 创建CacheKey对象
        CacheKey key = createCacheKey(ms, parameterObject, rowBounds, boundSql);
        return query(ms, parameterObject, rowBounds, resultHandler, key, boundSql);
    }

    //被ResultLoader.selectList调用
    @Override
    public <E> List<E> query(MappedStatement ms, Object parameterObject, RowBounds rowBounds, ResultHandler resultHandler, CacheKey key, BoundSql boundSql)
            throws SQLException {
        // 获取查询语句是否开启了二级缓存功能
        Cache cache = ms.getCache();
        //默认情况下是没有开启缓存的(二级缓存).要开启二级缓存,你需要先在配置文件中添加 mybatis: configuration: cache-enabled:true
        // 再在 SQL 映射文件中添加一行: <cache/>或<cache-ref/>

        // 2. 是否开启了二级缓存功能
        if (cache != null) {
            // 根据<select>节点的配置,决定是否需要去清空二级缓存
            flushCacheIfRequired(ms);
            // 键SQL节点的useCache配置以及是否使用了resultHandler配置
            if (ms.isUseCache() && resultHandler == null) {
                // 3. 二级缓存不能保存输出类型的参数, 如果查询操作调用了包含输出参数的存储过程,则报错
                ensureNoOutParams(ms, parameterObject, boundSql);
                @SuppressWarnings("unchecked")
                // 4. 查询二级缓存
                List<E> list = (List<E>) tcm.getObject(cache, key);
                if (list == null) {
                    // 5. 二级缓存没有相应的结果对象, 调用封装的Executor对象的query()方法,
                    // 正如前面介绍的,其中会先查询一级缓存
                    list = delegate.<E>query(ms, parameterObject, rowBounds, resultHandler, key, boundSql);
                    // 将查询结果保存到TransactionalCache.entriesToAddOnCommit集合中
                    tcm.putObject(cache, key, list); // issue #578 and #116
                }
                return list;
            }
        }
        // 没有启动二级缓存,直接调用底层Executor执行数据库查询操作
        return delegate.<E>query(ms, parameterObject, rowBounds, resultHandler, key, boundSql);
    }

    @Override
    public List<BatchResult> flushStatements() throws SQLException {
        return delegate.flushStatements();
    }

    @Override
    public void commit(boolean required) throws SQLException {
        //  调用底层的Executor提交事务
        delegate.commit(required);
        //遍历所有相关的TranscationalCache对象执行commit方法
        tcm.commit();
    }

    @Override
    public void rollback(boolean required) throws SQLException {
        try {
            // 调用底层的Executor回滚事务
            delegate.rollback(required);
        } finally {
            if (required) {
                // 遍历所有相关的TranscationalCache对象执行rollback()方法
                tcm.rollback();
            }
        }
    }

    private void ensureNoOutParams(MappedStatement ms, Object parameter, BoundSql boundSql) {
        if (ms.getStatementType() == StatementType.CALLABLE) {
            for (ParameterMapping parameterMapping : boundSql.getParameterMappings()) {
                if (parameterMapping.getMode() != ParameterMode.IN) {
                    throw new ExecutorException("Caching stored procedures with OUT params is not supported.  Please configure useCache=false in " + ms.getId() + " statement.");
                }
            }
        }
    }

    @Override
    public CacheKey createCacheKey(MappedStatement ms, Object parameterObject, RowBounds rowBounds, BoundSql boundSql) {
        return delegate.createCacheKey(ms, parameterObject, rowBounds, boundSql);
    }

    @Override
    public boolean isCached(MappedStatement ms, CacheKey key) {
        return delegate.isCached(ms, key);
    }

    @Override
    public void deferLoad(MappedStatement ms, MetaObject resultObject, String property, CacheKey key, Class<?> targetType) {
        delegate.deferLoad(ms, resultObject, property, key, targetType);
    }

    @Override
    public void clearLocalCache() {
        delegate.clearLocalCache();
    }

    private void flushCacheIfRequired(MappedStatement ms) {
        Cache cache = ms.getCache();
        if (cache != null && ms.isFlushCacheRequired()) {
            tcm.clear(cache);
        }
    }

    @Override
    public void setExecutorWrapper(Executor executor) {
        throw new UnsupportedOperationException("This method should not be called");
    }

}
