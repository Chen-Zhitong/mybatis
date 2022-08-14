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
package org.apache.ibatis.datasource.pooled;

import org.apache.ibatis.datasource.unpooled.UnpooledDataSource;
import org.apache.ibatis.logging.Log;
import org.apache.ibatis.logging.LogFactory;

import javax.sql.DataSource;
import java.io.PrintWriter;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.sql.*;
import java.util.Properties;
import java.util.logging.Logger;

/**
 * This is a simple, synchronous, thread-safe database connection pool.
 *
 * @author Clinton Begin
 */

/**
 * 有连接池的数据源
 * 这个是mybatis自己实行了一个连接池
 * 其他还有诸如C3P0的com.mchange.v2.c3p0.ComboPooledDataSource
 * DBCP 等等
 * 还有一些人喜欢自己用apache commons pool写一个连接池
 */
public class PooledDataSource implements DataSource {

    private static final Log log = LogFactory.getLog(PooledDataSource.class);

    // 通过PoolState管理连接池的状态并记录统计信息
    private final PoolState state = new PoolState(this);

    // 记录UnpooledDataSource对象,用于生成真实的数据库连接对象,构造函数会初始化该字段
    private final UnpooledDataSource dataSource;

    // OPTIONAL CONFIGURATION FIELDS
    //最大活跃连接数
    protected int poolMaximumActiveConnections = 10;
    //最大空闲连接数
    protected int poolMaximumIdleConnections = 5;
    //最大Checkout时长
    protected int poolMaximumCheckoutTime = 20000;
    // 无法获取连接时, 线程需要等待的时间
    protected int poolTimeToWait = 20000;
    // 在检测一个数据库连接是否可用时,会给数据发送一个测试SQL语句
    protected String poolPingQuery = "NO PING QUERY SET";
    //  是否允许发送测试SQL语句
    protected boolean poolPingEnabled = false;
    // 当连接超过poolPingConnectionsNotUsedFor毫秒未使用时,会发送一次测试SQL语句,检测连接是否正常
    protected int poolPingConnectionsNotUsedFor = 0;

    // 根据数据库的URL, 用户名和密码生成一个hash值, 该哈希值用于标志着当前的连接池, 在构造函数中初始化
    private int expectedConnectionTypeCode;

    public PooledDataSource() {
        dataSource = new UnpooledDataSource();
    }

    public PooledDataSource(String driver, String url, String username, String password) {
        dataSource = new UnpooledDataSource(driver, url, username, password);
        expectedConnectionTypeCode = assembleConnectionTypeCode(dataSource.getUrl(), dataSource.getUsername(), dataSource.getPassword());
    }

    public PooledDataSource(String driver, String url, Properties driverProperties) {
        dataSource = new UnpooledDataSource(driver, url, driverProperties);
        expectedConnectionTypeCode = assembleConnectionTypeCode(dataSource.getUrl(), dataSource.getUsername(), dataSource.getPassword());
    }

    public PooledDataSource(ClassLoader driverClassLoader, String driver, String url, String username, String password) {
        dataSource = new UnpooledDataSource(driverClassLoader, driver, url, username, password);
        expectedConnectionTypeCode = assembleConnectionTypeCode(dataSource.getUrl(), dataSource.getUsername(), dataSource.getPassword());
    }

    public PooledDataSource(ClassLoader driverClassLoader, String driver, String url, Properties driverProperties) {
        dataSource = new UnpooledDataSource(driverClassLoader, driver, url, driverProperties);
        expectedConnectionTypeCode = assembleConnectionTypeCode(dataSource.getUrl(), dataSource.getUsername(), dataSource.getPassword());
    }

    /*
     * Unwraps a pooled connection to get to the 'real' connection
     *
     * @param conn - the pooled connection to unwrap
     * @return The 'real' connection
     */
    public static Connection unwrapConnection(Connection conn) {
        if (Proxy.isProxyClass(conn.getClass())) {
            InvocationHandler handler = Proxy.getInvocationHandler(conn);
            if (handler instanceof PooledConnection) {
                return ((PooledConnection) handler).getRealConnection();
            }
        }
        return conn;
    }

    @Override
    public Connection getConnection() throws SQLException {
        //覆盖了DataSource.getConnection方法，每次都是pop一个Connection，即从池中取出一个来
        return popConnection(dataSource.getUsername(), dataSource.getPassword()).getProxyConnection();
    }

    @Override
    public Connection getConnection(String username, String password) throws SQLException {
        return popConnection(username, password).getProxyConnection();
    }

    @Override
    public int getLoginTimeout() throws SQLException {
        return DriverManager.getLoginTimeout();
    }

    @Override
    public void setLoginTimeout(int loginTimeout) throws SQLException {
        DriverManager.setLoginTimeout(loginTimeout);
    }

    @Override
    public PrintWriter getLogWriter() throws SQLException {
        return DriverManager.getLogWriter();
    }

    @Override
    public void setLogWriter(PrintWriter logWriter) throws SQLException {
        DriverManager.setLogWriter(logWriter);
    }

    public void setDefaultAutoCommit(boolean defaultAutoCommit) {
        dataSource.setAutoCommit(defaultAutoCommit);
        forceCloseAll();
    }

    public String getDriver() {
        return dataSource.getDriver();
    }

    public void setDriver(String driver) {
        dataSource.setDriver(driver);
        forceCloseAll();
    }

    public String getUrl() {
        return dataSource.getUrl();
    }

    public void setUrl(String url) {
        dataSource.setUrl(url);
        forceCloseAll();
    }

    public String getUsername() {
        return dataSource.getUsername();
    }

    public void setUsername(String username) {
        dataSource.setUsername(username);
        forceCloseAll();
    }

    public String getPassword() {
        return dataSource.getPassword();
    }

    public void setPassword(String password) {
        dataSource.setPassword(password);
        forceCloseAll();
    }

    public boolean isAutoCommit() {
        return dataSource.isAutoCommit();
    }

    public Integer getDefaultTransactionIsolationLevel() {
        return dataSource.getDefaultTransactionIsolationLevel();
    }

    public void setDefaultTransactionIsolationLevel(Integer defaultTransactionIsolationLevel) {
        dataSource.setDefaultTransactionIsolationLevel(defaultTransactionIsolationLevel);
        forceCloseAll();
    }

    public Properties getDriverProperties() {
        return dataSource.getDriverProperties();
    }

    public void setDriverProperties(Properties driverProps) {
        dataSource.setDriverProperties(driverProps);
        forceCloseAll();
    }

    public int getPoolMaximumActiveConnections() {
        return poolMaximumActiveConnections;
    }

    /*
     * The maximum number of active connections
     *
     * @param poolMaximumActiveConnections The maximum number of active connections
     */
    public void setPoolMaximumActiveConnections(int poolMaximumActiveConnections) {
        this.poolMaximumActiveConnections = poolMaximumActiveConnections;
        forceCloseAll();
    }

    public int getPoolMaximumIdleConnections() {
        return poolMaximumIdleConnections;
    }

    /*
     * The maximum number of idle connections
     *
     * @param poolMaximumIdleConnections The maximum number of idle connections
     */
    public void setPoolMaximumIdleConnections(int poolMaximumIdleConnections) {
        this.poolMaximumIdleConnections = poolMaximumIdleConnections;
        forceCloseAll();
    }

    public int getPoolMaximumCheckoutTime() {
        return poolMaximumCheckoutTime;
    }

    /*
     * The maximum time a connection can be used before it *may* be
     * given away again.
     *
     * @param poolMaximumCheckoutTime The maximum time
     */
    public void setPoolMaximumCheckoutTime(int poolMaximumCheckoutTime) {
        this.poolMaximumCheckoutTime = poolMaximumCheckoutTime;
        forceCloseAll();
    }

    public int getPoolTimeToWait() {
        return poolTimeToWait;
    }

    /*
     * The time to wait before retrying to get a connection
     *
     * @param poolTimeToWait The time to wait
     */
    public void setPoolTimeToWait(int poolTimeToWait) {
        this.poolTimeToWait = poolTimeToWait;
        forceCloseAll();
    }

    public String getPoolPingQuery() {
        return poolPingQuery;
    }

    /*
     * The query to be used to check a connection
     *
     * @param poolPingQuery The query
     */
    public void setPoolPingQuery(String poolPingQuery) {
        this.poolPingQuery = poolPingQuery;
        forceCloseAll();
    }

    public boolean isPoolPingEnabled() {
        return poolPingEnabled;
    }

    /*
     * Determines if the ping query should be used.
     *
     * @param poolPingEnabled True if we need to check a connection before using it
     */
    public void setPoolPingEnabled(boolean poolPingEnabled) {
        this.poolPingEnabled = poolPingEnabled;
        forceCloseAll();
    }

    public int getPoolPingConnectionsNotUsedFor() {
        return poolPingConnectionsNotUsedFor;
    }

    /*
     * If a connection has not been used in this many milliseconds, ping the
     * database to make sure the connection is still good.
     *
     * @param milliseconds the number of milliseconds of inactivity that will trigger a ping
     */
    public void setPoolPingConnectionsNotUsedFor(int milliseconds) {
        this.poolPingConnectionsNotUsedFor = milliseconds;
        forceCloseAll();
    }

    /*
     * Closes all active and idle connections in the pool
     * 当修改PooledDataSource的字段时，例如数据库URL、用户名、密码、autoCommit配置等，都会调用forceCloseAll（）方法将所有数据库连接关闭，
     * 同时也会将所有相应的PooledConnection对象都设置为无效，清空activeConnections集合和idleConnections集合。
     */
    public void forceCloseAll() {
        synchronized (state) {
            //  更新当前连接池的标识
            expectedConnectionTypeCode = assembleConnectionTypeCode(dataSource.getUrl(), dataSource.getUsername(), dataSource.getPassword());
            //关闭所有的activeConnections和idleConnections
            for (int i = state.activeConnections.size(); i > 0; i--) {
                try {
                    // 从PoolState.activeConnections集合中获取PooledConnection对象
                    PooledConnection conn = state.activeConnections.remove(i - 1);
                    // 将PooledConnection对象设置为无效
                    conn.invalidate();

                    //获取真正的数据库连接对象
                    Connection realConn = conn.getRealConnection();
                    // 回滚未提交的事务
                    if (!realConn.getAutoCommit()) {
                        realConn.rollback();
                    }
                    // 关闭真正的数据库连接
                    realConn.close();
                } catch (Exception e) {
                    // ignore
                }
            }
            for (int i = state.idleConnections.size(); i > 0; i--) {
                try {
                    PooledConnection conn = state.idleConnections.remove(i - 1);
                    conn.invalidate();

                    Connection realConn = conn.getRealConnection();
                    if (!realConn.getAutoCommit()) {
                        realConn.rollback();
                    }
                    realConn.close();
                } catch (Exception e) {
                    // ignore
                }
            }
        }
        if (log.isDebugEnabled()) {
            log.debug("PooledDataSource forcefully closed/removed all connections.");
        }
    }

    public PoolState getPoolState() {
        return state;
    }

    private int assembleConnectionTypeCode(String url, String username, String password) {
        return ("" + url + username + password).hashCode();
    }

    /**
     * 当调用连接的代理对象的close（）方法时，并未关闭真正的数据连接，
     * 而是调用PooledDataSource.pushConnection（）方法将PooledConnection对象归还给连接池，供之后重用。
     *
     * 摘录来自: 徐郡明. “MyBatis技术内幕。” Apple Books.
     *
     * @param conn
     * @throws SQLException
     */
    protected void pushConnection(PooledConnection conn) throws SQLException {

        synchronized (state) {
            //先从activeConnections中删除此connection
            state.activeConnections.remove(conn);
            // 检测PooledConnection对象是否有效
            if (conn.isValid()) {
                // 检测空闲连接数是否已达上限, 以及 PooledConnection 是否为该连接池的连接
                if (state.idleConnections.size() < poolMaximumIdleConnections && conn.getConnectionTypeCode() == expectedConnectionTypeCode) {
                    // 累积 checkout时长
                    state.accumulatedCheckoutTime += conn.getCheckoutTime();
                    // 回滚未提交的事务
                    if (!conn.getRealConnection().getAutoCommit()) {
                        conn.getRealConnection().rollback();
                    }
                    // 为返还连接创建新的 PooledConnection 对象
                    PooledConnection newConn = new PooledConnection(conn.getRealConnection(), this);
                    // 添加到 idleConnection集合
                    state.idleConnections.add(newConn);
                    newConn.setCreatedTimestamp(conn.getCreatedTimestamp());
                    newConn.setLastUsedTimestamp(conn.getLastUsedTimestamp());
                    // 将原PooledConnection对象设置为无效
                    conn.invalidate();
                    if (log.isDebugEnabled()) {
                        log.debug("Returned connection " + newConn.getRealHashCode() + " to pool.");
                    }
                    // 唤醒阻塞等待的线程
                    state.notifyAll();
                } else {
                    //否则，即空闲的连接已经足够了
                    state.accumulatedCheckoutTime += conn.getCheckoutTime();
                    if (!conn.getRealConnection().getAutoCommit()) {
                        conn.getRealConnection().rollback();
                    }
                    //那就将connection关闭就可以了
                    conn.getRealConnection().close();
                    if (log.isDebugEnabled()) {
                        log.debug("Closed connection " + conn.getRealHashCode() + ".");
                    }
                    conn.invalidate();
                }
            } else {
                if (log.isDebugEnabled()) {
                    log.debug("A bad connection (" + conn.getRealHashCode() + ") attempted to return to the pool, discarding connection.");
                }
                // 统计无效PooledConnection对象个数
                state.badConnectionCount++;
            }
        }
    }

    /**
     *  PooledDataSource.getConnection（）方法首先会调用PooledDataSource.popConnection（）方法获取PooledConnection对象，
     *  然后通过PooledConnection.getProxyConnection（）方法获取数据库连接的代理对象。
     *
     *
     * @param username
     * @param password
     * @return
     * @throws SQLException
     */
    private PooledConnection popConnection(String username, String password) throws SQLException {
        boolean countedWait = false;
        PooledConnection conn = null;
        long t = System.currentTimeMillis();
        int localBadConnectionCount = 0;

        //最外面是while死循环，如果一直拿不到connection，则不断尝试
        while (conn == null) {
            synchronized (state) {
                // 检测空闲连接
                if (!state.idleConnections.isEmpty()) {
                    //如果有空闲的连接的话
                    // Pool has available connection
                    //删除空闲列表里第一个，返回
                    conn = state.idleConnections.remove(0);
                    if (log.isDebugEnabled()) {
                        log.debug("Checked out connection " + conn.getRealHashCode() + " from pool.");
                    }
                } else {
                    //如果没有空闲的连接
                    // Pool does not have available connection
                    // 活跃连接数没有到最大值,则可以创建新链接
                    if (state.activeConnections.size() < poolMaximumActiveConnections) {
                        // Can create new connection
                        // 创建新数据库连接,并封装成PooledConnection对象
                        conn = new PooledConnection(dataSource.getConnection(), this);
                        if (log.isDebugEnabled()) {
                            log.debug("Created connection " + conn.getRealHashCode() + ".");
                        }
                    } else {
                        // 活跃连接数已到最大值,则不能创建新链接
                        // 获取最先创建的活跃连接
                        PooledConnection oldestActiveConnection = state.activeConnections.get(0);
                        long longestCheckoutTime = oldestActiveConnection.getCheckoutTime();
                        // 检测该连接是否超时
                        if (longestCheckoutTime > poolMaximumCheckoutTime) {
                            // Can claim overdue connection
                            // 对超时连接进行统计
                            state.claimedOverdueConnectionCount++;
                            state.accumulatedCheckoutTimeOfOverdueConnections += longestCheckoutTime;
                            state.accumulatedCheckoutTime += longestCheckoutTime;
                            // 将超时连接移出activeConnections集合
                            state.activeConnections.remove(oldestActiveConnection);
                            // 如果超时连接未提交, 则自动回滚
                            if (!oldestActiveConnection.getRealConnection().getAutoCommit()) {
                                oldestActiveConnection.getRealConnection().rollback();
                            }
                            // 创建新pooledConnection对象,但是真正的数据库连接并未创建新的
                            conn = new PooledConnection(oldestActiveConnection.getRealConnection(), this);
                            // 将超时PooledConnection设为无效
                            oldestActiveConnection.invalidate();
                            if (log.isDebugEnabled()) {
                                log.debug("Claimed overdue connection " + conn.getRealHashCode() + ".");
                            }
                        } else {
                            // Must wait
                            // 无空闲连接, 无法创建新链接 且 无超时连接, 只能阻塞等待
                            try {
                                if (!countedWait) {
                                    //统计信息：等待+1
                                    state.hadToWaitCount++;
                                    countedWait = true;
                                }
                                if (log.isDebugEnabled()) {
                                    log.debug("Waiting as long as " + poolTimeToWait + " milliseconds for connection.");
                                }
                                long wt = System.currentTimeMillis();
                                //睡一会儿吧
                                state.wait(poolTimeToWait);
                                state.accumulatedWaitTime += System.currentTimeMillis() - wt;
                            } catch (InterruptedException e) {
                                break;
                            }
                        }
                    }
                }
                if (conn != null) {
                    // 检测pooledConnection 是否有效
                    if (conn.isValid()) {
                        if (!conn.getRealConnection().getAutoCommit()) {
                            conn.getRealConnection().rollback();
                        }
                        // 配置PooledConnection的相关属性, 设置connectionTypeCode,
                        // checkoutTimestamp, lastUsedTimestamp字段的值
                        conn.setConnectionTypeCode(assembleConnectionTypeCode(dataSource.getUrl(), username, password));
                        conn.setCheckoutTimestamp(System.currentTimeMillis());
                        conn.setLastUsedTimestamp(System.currentTimeMillis());
                        // 进行相关统计
                        state.activeConnections.add(conn);
                        state.requestCount++;
                        state.accumulatedRequestTime += System.currentTimeMillis() - t;
                    } else {
                        if (log.isDebugEnabled()) {
                            log.debug("A bad connection (" + conn.getRealHashCode() + ") was returned from the pool, getting another connection.");
                        }
                        //如果没拿到，统计信息：坏连接+1
                        state.badConnectionCount++;
                        localBadConnectionCount++;
                        conn = null;
                        if (localBadConnectionCount > (poolMaximumIdleConnections + 3)) {
                            //如果好几次都拿不到，就放弃了，抛出异常
                            if (log.isDebugEnabled()) {
                                log.debug("PooledDataSource: Could not get a good connection to the database.");
                            }
                            throw new SQLException("PooledDataSource: Could not get a good connection to the database.");
                        }
                    }
                }
            }

        }

        if (conn == null) {
            if (log.isDebugEnabled()) {
                log.debug("PooledDataSource: Unknown severe error condition.  The connection pool returned a null connection.");
            }
            throw new SQLException("PooledDataSource: Unknown severe error condition.  The connection pool returned a null connection.");
        }

        return conn;
    }

    /*
     * Method to check to see if a connection is still usable
     *
     * @param conn - the connection to check
     * @return True if the connection is still usable
     */
    protected boolean pingConnection(PooledConnection conn) {
        boolean result = true;

        try {
            // 检测真正的数据库连接是否已经关闭
            result = !conn.getRealConnection().isClosed();
        } catch (SQLException e) {
            if (log.isDebugEnabled()) {
                log.debug("Connection " + conn.getRealHashCode() + " is BAD: " + e.getMessage());
            }
            result = false;
        }

        if (result) {
            // 检测poolPingEnabled设置, 是否运行测试SQL语句
            if (poolPingEnabled) {
                // 长时间(超过poolPingConnectionsNotUsedFor指定的时长)未使用的连接,
                // 才需要ping操作来检测数据库连接是否正常
                if (poolPingConnectionsNotUsedFor >= 0 && conn.getTimeElapsedSinceLastUse() > poolPingConnectionsNotUsedFor) {
                    try {
                        if (log.isDebugEnabled()) {
                            log.debug("Testing connection " + conn.getRealHashCode() + " ...");
                        }
                        Connection realConn = conn.getRealConnection();
                        Statement statement = realConn.createStatement();
                        ResultSet rs = statement.executeQuery(poolPingQuery);
                        rs.close();
                        statement.close();
                        if (!realConn.getAutoCommit()) {
                            realConn.rollback();
                        }
                        result = true;
                        if (log.isDebugEnabled()) {
                            log.debug("Connection " + conn.getRealHashCode() + " is GOOD!");
                        }
                    } catch (Exception e) {
                        log.warn("Execution of ping query '" + poolPingQuery + "' failed: " + e.getMessage());
                        try {
                            conn.getRealConnection().close();
                        } catch (Exception e2) {
                            //ignore
                        }
                        result = false;
                        if (log.isDebugEnabled()) {
                            log.debug("Connection " + conn.getRealHashCode() + " is BAD: " + e.getMessage());
                        }
                    }
                }
            }
        }
        return result;
    }

    protected void finalize() throws Throwable {
        forceCloseAll();
        super.finalize();
    }

    public <T> T unwrap(Class<T> iface) throws SQLException {
        throw new SQLException(getClass().getName() + " is not a wrapper.");
    }

    public boolean isWrapperFor(Class<?> iface) throws SQLException {
        return false;
    }

    public Logger getParentLogger() {
        return Logger.getLogger(Logger.GLOBAL_LOGGER_NAME); // requires JDK version 1.6
    }

}
