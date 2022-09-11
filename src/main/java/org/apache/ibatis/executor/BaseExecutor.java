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
import org.apache.ibatis.cache.impl.PerpetualCache;
import org.apache.ibatis.logging.Log;
import org.apache.ibatis.logging.LogFactory;
import org.apache.ibatis.logging.jdbc.ConnectionLogger;
import org.apache.ibatis.mapping.*;
import org.apache.ibatis.reflection.MetaObject;
import org.apache.ibatis.reflection.factory.ObjectFactory;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.LocalCacheScope;
import org.apache.ibatis.session.ResultHandler;
import org.apache.ibatis.session.RowBounds;
import org.apache.ibatis.transaction.Transaction;
import org.apache.ibatis.type.TypeHandlerRegistry;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;

import static org.apache.ibatis.executor.ExecutionPlaceholder.EXECUTION_PLACEHOLDER;

/**
 * BaseExecutor是一个实现了Executor接口的抽象类，它实现了Executor接口的大部分方法，其中就使用了模板方法模式。BaseExecutor中主要提供了
 * 缓存管理和事务管理的基本功能，继承BaseExecutor的子类只要实现四个基本方法来完成数据库的相关操作即可，这四个方法分别是：doUpdate（）方法、
 * doQuery（）方法、doQueryCursor（）方法、doFlushStatement（）方法，其余的功能在BaseExecutor中实现。
 *
 * @author Clinton Begin
 */

/**
 * 执行器基类
 */
public abstract class BaseExecutor implements Executor {

    private static final Log log = LogFactory.getLog(BaseExecutor.class);

    // Transaction对象, 实现事务的提交, 回滚和关闭操作
    protected Transaction transaction;
    // 其中封装的Executor对象
    protected Executor wrapper;

    //延迟加载队列（线程安全）
    protected ConcurrentLinkedQueue<DeferredLoad> deferredLoads;
    //本地缓存机制（Local Cache）防止循环引用（circular references）和加速重复嵌套查询(一级缓存)
    //一级缓存,用于缓存该Executor对象查询结果集映射得到的结果对象,
    protected PerpetualCache localCache;
    //一级缓存,用于缓存输出类型参数
    protected PerpetualCache localOutputParameterCache;
    protected Configuration configuration;

    // 用来记录嵌套查询的层数
    protected int queryStack = 0;
    private boolean closed;

    protected BaseExecutor(Configuration configuration, Transaction transaction) {
        this.transaction = transaction;
        this.deferredLoads = new ConcurrentLinkedQueue<DeferredLoad>();
        this.localCache = new PerpetualCache("LocalCache");
        this.localOutputParameterCache = new PerpetualCache("LocalOutputParameterCache");
        this.closed = false;
        this.configuration = configuration;
        this.wrapper = this;
    }

    @Override
    public Transaction getTransaction() {
        if (closed) {
            throw new ExecutorException("Executor was closed.");
        }
        return transaction;
    }

    @Override
    public void close(boolean forceRollback) {
        try {
            try {
                rollback(forceRollback);
            } finally {
                if (transaction != null) {
                    transaction.close();
                }
            }
        } catch (SQLException e) {
            // Ignore.  There's nothing that can be done at this point.
            log.warn("Unexpected exception on closing transaction.  Cause: " + e);
        } finally {
            transaction = null;
            deferredLoads = null;
            localCache = null;
            localOutputParameterCache = null;
            closed = true;
        }
    }

    @Override
    public boolean isClosed() {
        return closed;
    }

    //SqlSession.update/insert/delete会调用此方法
    @Override
    public int update(MappedStatement ms, Object parameter) throws SQLException {
        ErrorContext.instance().resource(ms.getResource()).activity("executing an update").object(ms.getId());
        if (closed) {
            throw new ExecutorException("Executor was closed.");
        }
        //先清局部缓存，再更新，如何更新交由子类，模板方法模式
        clearLocalCache();
        return doUpdate(ms, parameter);
    }

    @Override
    public List<BatchResult> flushStatements() throws SQLException {
        return flushStatements(false);
    }

    //刷新语句，Batch用
    public List<BatchResult> flushStatements(boolean isRollBack) throws SQLException {
        if (closed) {
            throw new ExecutorException("Executor was closed.");
        }
        // 调用doFlushStatements()这个基本方法,其参数isRollBack表示是否执行Executor中缓存的
        // SQL语句,false表示执行,true表示不执行
        return doFlushStatements(isRollBack);
    }

    //SqlSession.selectList会调用此方法
    @Override
    public <E> List<E> query(MappedStatement ms, Object parameter, RowBounds rowBounds, ResultHandler resultHandler) throws SQLException {
        //得到绑定sql
        BoundSql boundSql = ms.getBoundSql(parameter);
        //创建缓存Key
        CacheKey key = createCacheKey(ms, parameter, rowBounds, boundSql);
        //调用query()的另一个重载,继续后续处理
        return query(ms, parameter, rowBounds, resultHandler, key, boundSql);
    }

    @SuppressWarnings("unchecked")
    @Override
    public <E> List<E> query(MappedStatement ms, Object parameter, RowBounds rowBounds, ResultHandler resultHandler, CacheKey key, BoundSql boundSql) throws SQLException {
        ErrorContext.instance().resource(ms.getResource()).activity("executing a query").object(ms.getId());
        //如果已经关闭，报错
        if (closed) {
            throw new ExecutorException("Executor was closed.");
        }
        if (queryStack == 0 && ms.isFlushCacheRequired()) {
            // 非嵌套查询,并且<select>节点配置的flushCache属性为true时,才会清空一级缓存
            // flushCache配置项是影响一级缓存中结果对象存活时长的第一个方面
            clearLocalCache();
        }
        List<E> list;
        try {
            //加一,这样递归调用到上面的时候就不会再清局部缓存了
            queryStack++; // 增加查询层数
            // 查询一级缓存
            list = resultHandler == null ? (List<E>) localCache.getObject(key) : null;
            if (list != null) {
                // 针对存储过程调用的处理,其功能是:在一级缓存命中时,获取缓存中保存的输出类型参数,
                // 并设置到用户传入的实参(parameter)对象中
                handleLocallyCachedOutputParameters(ms, key, parameter, boundSql);
            } else {
                // 其中会调用doQuery()方法完成数据库查询,并得到映射后的结果对象, doQuery()方法是一个抽象方法,
                // 由BaseExecutor的子类具体实现
                list = queryFromDatabase(ms, parameter, rowBounds, resultHandler, key, boundSql);
            }
        } finally {
            //清空堆栈
            // 当前查询完成,查询层数减少
            queryStack--;
        }
        if (queryStack == 0) {
            // 在外层的查询结束时,所有嵌套查询也已经完成,相关缓存项也已经完全加载,所以在这里可以
            // 触发DeferredLoad加载一级缓存中记录的嵌套查询结果对象
            for (DeferredLoad deferredLoad : deferredLoads) {
                deferredLoad.load();
            }
            // issue #601
            //加载完成后,清空延迟加载队列
            deferredLoads.clear();
            if (configuration.getLocalCacheScope() == LocalCacheScope.STATEMENT) {
                // issue #482
                // 根据localCacheScope配置决定是否清空一级缓存,localCacheScope配置是影响一级缓存
                // 中结果对象存活时长的第二个方面
                clearLocalCache();
            }
        }
        return list;
    }

    //延迟加载，DefaultResultSetHandler.getNestedQueryMappingValue调用.属于嵌套查询，比较高级.
    //
    @Override
    public void deferLoad(MappedStatement ms, MetaObject resultObject, String property, CacheKey key, Class<?> targetType) {
        // 边界检测
        if (closed) {
            throw new ExecutorException("Executor was closed.");
        }
        // 创建DefferedLoad对象
        DeferredLoad deferredLoad = new DeferredLoad(resultObject, property, key, localCache, configuration, targetType);
        if (deferredLoad.canLoad()) {
            // 一级缓存中已经记录了指定查询结果的对象,直接从缓存中加载对象,并设置到外层对象中
            deferredLoad.load();
        } else {
            // 将DeferredLoad对象添加到deferredLoads队列中,待整个外层查询结束后,则加载该结果对象
            deferredLoads.add(new DeferredLoad(resultObject, property, key, localCache, configuration, targetType));
        }
    }

    //创建缓存Key
    @Override
    public CacheKey createCacheKey(MappedStatement ms, Object parameterObject, RowBounds rowBounds, BoundSql boundSql) {
        if (closed) {
            throw new ExecutorException("Executor was closed.");
        }
        CacheKey cacheKey = new CacheKey();
        //MyBatis 对于其 Key 的生成采取规则为：[mappedStementId + offset + limit + SQL + queryParams + environment]生成一个哈希码
        cacheKey.update(ms.getId()); // 将MappedStatement的id添加到Cachekey对象中
        cacheKey.update(Integer.valueOf(rowBounds.getOffset())); // 将offset添加到cacheKey对象中
        cacheKey.update(Integer.valueOf(rowBounds.getLimit()));// 将limit添加到CacheKey对象中
        cacheKey.update(boundSql.getSql()); // 将SQL语句添加到CacheKey对象中
        List<ParameterMapping> parameterMappings = boundSql.getParameterMappings();
        TypeHandlerRegistry typeHandlerRegistry = ms.getConfiguration().getTypeHandlerRegistry();
        // mimic DefaultParameterHandler logic
        //模仿DefaultParameterHandler的逻辑,不再重复，请参考DefaultParameterHandler
        // 获取用户传入的实参,并添加到CacheKey对象中
        for (int i = 0; i < parameterMappings.size(); i++) {
            ParameterMapping parameterMapping = parameterMappings.get(i);
            // 过滤掉输出类型的参数
            if (parameterMapping.getMode() != ParameterMode.OUT) {
                Object value;
                String propertyName = parameterMapping.getProperty();
                if (boundSql.hasAdditionalParameter(propertyName)) {
                    value = boundSql.getAdditionalParameter(propertyName);
                } else if (parameterObject == null) {
                    value = null;
                } else if (typeHandlerRegistry.hasTypeHandler(parameterObject.getClass())) {
                    value = parameterObject;
                } else {
                    MetaObject metaObject = configuration.newMetaObject(parameterObject);
                    value = metaObject.getValue(propertyName);
                }
                // 将实参添加到CacheKey对象中
                cacheKey.update(value);
            }
        }
        // 如果Environment的id不为空则将其添加到CacheKey中
        if (configuration.getEnvironment() != null) {
            // issue #176
            cacheKey.update(configuration.getEnvironment().getId());
        }
        return cacheKey;
    }

    @Override
    public boolean isCached(MappedStatement ms, CacheKey key) {
        // 检测缓存中是否缓存了CacheKey对应的对象
        return localCache.getObject(key) != null;
    }

    @Override
    public void commit(boolean required) throws SQLException {
        if (closed) {
            throw new ExecutorException("Cannot commit, transaction is already closed");
        }
        // 清空一级缓存
        clearLocalCache();
        // 执行缓存的SQL语句,其中调用了flushStatements(false)方法
        flushStatements();
        if (required) { // 根据required参数决定是否提交事务
            transaction.commit();
        }
    }

    @Override
    public void rollback(boolean required) throws SQLException {
        if (!closed) {
            try {
                clearLocalCache();
                flushStatements(true);
            } finally {
                if (required) {
                    transaction.rollback();
                }
            }
        }
    }

    @Override
    public void clearLocalCache() {
        if (!closed) {
            localCache.clear();
            localOutputParameterCache.clear();
        }
    }

    protected abstract int doUpdate(MappedStatement ms, Object parameter)
            throws SQLException;

    protected abstract List<BatchResult> doFlushStatements(boolean isRollback)
            throws SQLException;

    //query-->queryFromDatabase-->doQuery
    protected abstract <E> List<E> doQuery(MappedStatement ms, Object parameter, RowBounds rowBounds, ResultHandler resultHandler, BoundSql boundSql)
            throws SQLException;

    protected void closeStatement(Statement statement) {
        if (statement != null) {
            try {
                statement.close();
            } catch (SQLException e) {
                // ignore
            }
        }
    }

    private void handleLocallyCachedOutputParameters(MappedStatement ms, CacheKey key, Object parameter, BoundSql boundSql) {
        //处理存储过程的OUT参数
        if (ms.getStatementType() == StatementType.CALLABLE) {
            final Object cachedParameter = localOutputParameterCache.getObject(key);
            if (cachedParameter != null && parameter != null) {
                final MetaObject metaCachedParameter = configuration.newMetaObject(cachedParameter);
                final MetaObject metaParameter = configuration.newMetaObject(parameter);
                for (ParameterMapping parameterMapping : boundSql.getParameterMappings()) {
                    if (parameterMapping.getMode() != ParameterMode.IN) {
                        final String parameterName = parameterMapping.getProperty();
                        final Object cachedValue = metaCachedParameter.getValue(parameterName);
                        metaParameter.setValue(parameterName, cachedValue);
                    }
                }
            }
        }
    }

    /**
     * 开始查询调用doQuery()方法查询数据库之前,会现在loadlCache中添加占位符
     * 待查询完成之后,才将真正的结果对象放到localCache中缓存,此时该缓存项才算"完全加载"
     *
     * @param ms
     * @param parameter
     * @param rowBounds
     * @param resultHandler
     * @param key
     * @param boundSql
     * @param <E>
     * @return
     * @throws SQLException
     */
    //从数据库查
    private <E> List<E> queryFromDatabase(MappedStatement ms, Object parameter, RowBounds rowBounds, ResultHandler resultHandler, CacheKey key, BoundSql boundSql) throws SQLException {
        List<E> list;
        //先向缓存中放入占位符
        localCache.putObject(key, EXECUTION_PLACEHOLDER);
        try {
            // 调用doQuery()方法(抽象方法),完成数据库查询操作,并返回结果对象
            list = doQuery(ms, parameter, rowBounds, resultHandler, boundSql);
        } finally {
            //删除占位符
            localCache.removeObject(key);
        }
        // 将真正的查询结果添加到一级缓存中
        localCache.putObject(key, list);
        //如果是存储过程，OUT参数也加入缓存
        if (ms.getStatementType() == StatementType.CALLABLE) {
            // 缓存输出类型的参数
            localOutputParameterCache.putObject(key, parameter);
        }
        return list;
    }

    protected Connection getConnection(Log statementLog) throws SQLException {
        Connection connection = transaction.getConnection();
        if (statementLog.isDebugEnabled()) {
            //如果需要打印Connection的日志，返回一个ConnectionLogger(代理模式, AOP思想)
            return ConnectionLogger.newInstance(connection, statementLog, queryStack);
        } else {
            return connection;
        }
    }

    @Override
    public void setExecutorWrapper(Executor wrapper) {
        this.wrapper = wrapper;
    }

    //  负责从localCache缓存中延迟加载结果对象
    private static class DeferredLoad {

        // 外层对象对应的MetaObject对象
        private final MetaObject resultObject;
        //延迟加载的属性名称
        private final String property;
        //延迟加载的属性类型
        private final Class<?> targetType;
        // 延迟加载的结果对象在一级缓存中相应的CacheKey对象
        private final CacheKey key;
        // 以及缓存, 与BaseExecutor.localCache指向同一PerpetualCache对象
        private final PerpetualCache localCache;
        private final ObjectFactory objectFactory;
        // 负责结果对象的类型转换
        private final ResultExtractor resultExtractor;

        // issue #781
        public DeferredLoad(MetaObject resultObject,
                            String property,
                            CacheKey key,
                            PerpetualCache localCache,
                            Configuration configuration,
                            Class<?> targetType) {
            this.resultObject = resultObject;
            this.property = property;
            this.key = key;
            this.localCache = localCache;
            this.objectFactory = configuration.getObjectFactory();
            this.resultExtractor = new ResultExtractor(configuration, objectFactory);
            this.targetType = targetType;
        }

        /**
         * 负责检测缓存项是否已经完全加载到了缓存中
         *
         * @return
         */
        public boolean canLoad() {
            //缓存中找到，且不为占位符，代表缓存项已完全加载到了缓存中
            return localCache.getObject(key) != null && localCache.getObject(key) != EXECUTION_PLACEHOLDER;
        }

        // 负责从缓存中加载结果对象,并设置到外层对象的相应属性中
        public void load() {
            // 从缓存中查询指定的结果对象
            @SuppressWarnings("unchecked")
            // we suppose we get back a List
            List<Object> list = (List<Object>) localCache.getObject(key);
            //调用ResultExtractor.extractObjectFromList
            Object value = resultExtractor.extractObjectFromList(list, targetType);
            // 设置到外层对象的对应属性
            resultObject.setValue(property, value);
        }

    }

}
