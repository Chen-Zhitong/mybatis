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

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.locks.ReadWriteLock;

/**
 * Lru (first in, first out) cache decorator
 *
 * @author Clinton Begin
 */
/*
 * 最近最少使用缓存
 * 基于 LinkedHashMap 覆盖其 removeEldestEntry 方法实现。
 */
public class LruCache implements Cache {

    private final Cache delegate;
    //额外用了一个map才做lru，但是委托的Cache里面其实也是一个map，这样等于用2倍的内存实现lru功能
    // LinkedHashMap<Object,Object> 它是一个有序的HashMap, 用于记录key最近的使用情况
    private Map<Object, Object> keyMap;
    // 记录最少被使用的缓存项的 key
    private Object eldestKey;

    public LruCache(Cache delegate) {
        this.delegate = delegate;
        setSize(1024);
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
     * 从新设置缓存大小时,会重置 keyMap字段
     * @param size
     */
    public void setSize(final int size) {
        // 注意linkedHashMap构造函数的第三个参数, true表示该LinkedHashMap记录的顺序是
        // access-order, 也就是说 LinkedhashMap.get() 方法会改变其记录的顺序
        keyMap = new LinkedHashMap<Object, Object>(size, .75F, true) {
            private static final long serialVersionUID = 4267176411845948333L;

            //核心就是覆盖 LinkedHashMap.removeEldestEntry方法,
            //返回true或false告诉 LinkedHashMap要不要删除此最老键值
            //LinkedHashMap内部其实就是每次访问或者插入一个元素都会把元素放到链表末尾，
            //这样不经常访问的键值肯定就在链表开头啦
            @Override
            protected boolean removeEldestEntry(Map.Entry<Object, Object> eldest) {
                boolean tooBig = size() > size;
                if (tooBig) {
                    // 如果已达上限, 则更新 eldestkey 字段, 后面会删除该项
                    eldestKey = eldest.getKey();
                }
                return tooBig;
            }
        };
    }

    @Override
    public void putObject(Object key, Object value) {
        delegate.putObject(key, value);
        //增加新纪录后，判断是否要将最老元素移除
        cycleKeyList(key);
    }

    @Override
    public Object getObject(Object key) {
        //get的时候调用一下LinkedHashMap.get，让经常访问的值移动到链表末尾
        keyMap.get(key); //touch
        return delegate.getObject(key);
    }

    @Override
    public Object removeObject(Object key) {
        return delegate.removeObject(key);
    }

    @Override
    public void clear() {
        delegate.clear();
        keyMap.clear();
    }

    @Override
    public ReadWriteLock getReadWriteLock() {
        return null;
    }

    private void cycleKeyList(Object key) {
        keyMap.put(key, key);
        // eldestKey 不为空, 表示已达到缓存上限
        if (eldestKey != null) {
            // 删除最久未使用的缓存项
            delegate.removeObject(eldestKey);
            eldestKey = null;
        }
    }

}
