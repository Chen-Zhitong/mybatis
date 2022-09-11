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
package org.apache.ibatis.cache.decorators;

import org.apache.ibatis.cache.Cache;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.locks.ReadWriteLock;

/**
 * The 2nd level cache transactional buffer.
 * <p>
 * This class holds all cache entries that are to be added to the 2nd level cache during a Session.
 * Entries are sent to the cache when commit is called or discarded if the Session is rolled back.
 * Blocking cache support has been added. Therefore any get() that returns a cache miss
 * will be followed by a put() so any lock associated with the key can be released.
 *
 * @author Clinton Begin
 * @author Eduardo Macarron
 */

/**
 * 事务缓存
 * 一次性存入多个缓存，移除多个缓存
 * 主要用于保存在某个SqlSession的某个事务中需要向某个二级缓存中添加的缓存数据
 *
 */
public class TransactionalCache implements Cache {

    // 底层封装的二级缓存对应的Cache对象
    private Cache delegate;
    //当该字段为true时,表示当前TransactionalCache不可查询,且提交事务时会将底层Cache清空
    private boolean clearOnCommit;
    // 暂时记录添加到TransactionalCache中的数据. 在事务提交时,会将其中的数据添加到二级缓存中
    private Map<Object, Object> entriesToAddOnCommit;

    // 记录缓存未命中的CacheKey对象
    private Set<Object> entriesMissedInCache;

    public TransactionalCache(Cache delegate) {
        this.delegate = delegate;
        //默认commit时不清缓存
        this.clearOnCommit = false;
        this.entriesToAddOnCommit = new HashMap<Object, Object>();
        this.entriesMissedInCache = new HashSet<Object>();
    }

    @Override
    public String getId() {
        return delegate.getId();
    }

    @Override
    public int getSize() {
        return delegate.getSize();
    }

    @Override
    public Object getObject(Object key) {
        // issue #116
        // 查询底层的Cache是否包含指定的key
        Object object = delegate.getObject(key);
        if (object == null) {
            // 如果底层缓存对象中不包含该缓存项,则将key记录到entriesMissedInCache集合中
            entriesMissedInCache.add(key);
        }
        // issue #146
        // 如果clearOnCommit为true,则当前TranscationalCache不可查询,始终返回null
        if (clearOnCommit) {
            return null;
        } else {
            // 返回从底层Cache中查询到的对象
            return object;
        }
    }

    @Override
    public ReadWriteLock getReadWriteLock() {
        return null;
    }

    @Override
    public void putObject(Object key, Object object) {
        // 将缓存项暂存在entriesToAddOnCommit集合中
        // 在事务提交时才会将这些结果从entriesToAddOnCommit集合添加到二级缓存中
        entriesToAddOnCommit.put(key, object);
    }

    @Override
    public Object removeObject(Object key) {
        return null;
    }

    @Override
    public void clear() {
        clearOnCommit = true;
        entriesToAddOnCommit.clear();
    }

    //多了commit方法，提供事务功能
    public void commit() {
        // 事务提交前, 清空二级缓存
        if (clearOnCommit) {
            delegate.clear();
        }
        // 将entriesToAddOnCommit集合中的数据保存到二级缓存
        flushPendingEntries();
        // 重置clearOnCommit为false,并清空entriesToAddOnCommit, entriesMissedInCache集合
        reset();
    }

    public void rollback() {
        // 将entriesMissedInCache集合中记录的缓存项从二级缓存中删除
        unlockMissedEntries();

        // 重置clearOnCommit为false,并清空entriesToAddOnCommit, entriesMissedInCache集合
        reset();
    }

    private void reset() {
        clearOnCommit = false;
        entriesToAddOnCommit.clear();
        entriesMissedInCache.clear();
    }

    private void flushPendingEntries() {
        // 遍历entriesToAddOnCommit集合,将其中记录的缓存项添加到二级缓存中
        for (Map.Entry<Object, Object> entry : entriesToAddOnCommit.entrySet()) {
            delegate.putObject(entry.getKey(), entry.getValue());
        }
        // 遍历entriesMissedInCache集合, 将 entriesToAddOnCommit集合中不包含的缓存项添加二级缓存中
        for (Object entry : entriesMissedInCache) {
            if (!entriesToAddOnCommit.containsKey(entry)) {
                delegate.putObject(entry, null);
            }
        }
    }

    private void unlockMissedEntries() {
        for (Object entry : entriesMissedInCache) {
            delegate.putObject(entry, null);
        }
    }

}
