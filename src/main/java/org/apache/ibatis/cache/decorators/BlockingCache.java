package org.apache.ibatis.cache.decorators;
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

import org.apache.ibatis.cache.Cache;
import org.apache.ibatis.cache.CacheException;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Simple blocking decorator
 * <p>
 * Sipmle and inefficient version of EhCache's BlockingCache decorator.
 * It sets a lock over a cache key when the element is not found in cache.
 * This way, other threads will wait until this element is filled instead of hitting the database.
 *
 * @author Eduardo Macarron
 */
public class BlockingCache implements Cache {

    // 被装饰的底层cache对象
    private final Cache delegate;
    // 每个key都有对应的 ReentrantLock 对象
    private final ConcurrentHashMap<Object, ReentrantLock> locks;
    // 阻塞超时时长
    private long timeout;

    public BlockingCache(Cache delegate) {
        this.delegate = delegate;
        this.locks = new ConcurrentHashMap<Object, ReentrantLock>();
    }

    @Override
    public String getId() {
        return delegate.getId();
    }

    @Override
    public int getSize() {
        return delegate.getSize();
    }


    /**
     * “假设线程A从数据库中查找到keyA对应的结果对象后，将结果对象放入到BlockingCache中，此时线程A会释放keyA对应的锁，唤醒阻塞在该锁上的线程。其他线程即可从BlockingCache中获取keyA对应的数据，而不是再次访问数据库”
     *
     * 摘录来自: 徐郡明. “MyBatis技术内幕。” Apple Books.
     * @param key Can be any object but usually it is a {@link CacheKey}
     * @param value The result of a select.
     */
    @Override
    public void putObject(Object key, Object value) {
        try {
            // 向缓存中添加缓存项
            delegate.putObject(key, value);
        } finally {
            // 释放锁
            releaseLock(key);
        }
    }

    /**
     * 假设线程A在BlockingCache中未查找到keyA对应的缓存项时，线程A会获取keyA对应的锁，这样后续线程在查找keyA时会发生阻塞
     *
     * 摘录来自: 徐郡明. “MyBatis技术内幕。” Apple Books.
     * @param key The key
     * @return
     */
    @Override
    public Object getObject(Object key) {
        // 获取该 key 对应的锁
        acquireLock(key);
        // 查询key
        Object value = delegate.getObject(key);
        // 缓存有key对应的缓存项, 释放锁, 否则继续持有锁
        if (value != null) {
            releaseLock(key);
        }
        return value;
    }

    @Override
    public Object removeObject(Object key) {
        return delegate.removeObject(key);
    }

    @Override
    public void clear() {
        delegate.clear();
    }

    @Override
    public ReadWriteLock getReadWriteLock() {
        return null;
    }

    private ReentrantLock getLockForKey(Object key) {
        // 创建ReentrantLock对象
        ReentrantLock lock = new ReentrantLock();
        // 尝试添加到locks集合,如果locks集合中已经有了相应的 ReentrantLock对象,
        // 则使用locks集合中的ReentrantLock对象
        ReentrantLock previous = locks.putIfAbsent(key, lock);
        return previous == null ? lock : previous;
    }

    private void acquireLock(Object key) {
        // 获取 ReentrantLock对象
        Lock lock = getLockForKey(key);
        // 获取锁, 带超时时长
        if (timeout > 0) {
            try {
                boolean acquired = lock.tryLock(timeout, TimeUnit.MILLISECONDS);
                // 超时,抛出异常
                if (!acquired) {
                    throw new CacheException("Couldn't get a lock in " + timeout + " for the key " + key + " at the cache " + delegate.getId());
                }
            } catch (InterruptedException e) {
                throw new CacheException("Got interrupted while trying to acquire lock for key " + key, e);
            }
        } else {
            // 获取锁, 不带超时异常
            lock.lock();
        }
    }

    private void releaseLock(Object key) {
        ReentrantLock lock = locks.get(key);
        // 锁是否被当前线程持有
        if (lock.isHeldByCurrentThread()) {
            lock.unlock();
        }
    }

    public long getTimeout() {
        return timeout;
    }

    public void setTimeout(long timeout) {
        this.timeout = timeout;
    }
}
