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

import java.util.Deque;
import java.util.LinkedList;
import java.util.concurrent.locks.ReadWriteLock;

/**
 * FIFO (first in, first out) cache decorator
 *
 * @author Clinton Begin
 */
/*
 * FIFO缓存
 * 这个类就是维护一个FIFO链表，其他都委托给所包装的cache去做。典型的装饰模式
 * 当向缓存中添加数据时, 如果缓存项已达到上限,则会将缓存中最老(即最早进入的缓存)的缓存项删除
 */
public class FifoCache implements Cache {

    // 底层被装饰的Cache独享
    private final Cache delegate;
    // 用于记录Key进入缓存的先后顺序,使用的是 LinkedList<Object>类型的集合对象
    private Deque<Object> keyList;
    // 记录了缓存项上线, 超过该值, 则需要清理最老的缓存项
    private int size;

    public FifoCache(Cache delegate) {
        this.delegate = delegate;
        this.keyList = new LinkedList<Object>();
        this.size = 1024;
    }

    @Override
    public String getId() {
        return delegate.getId();
    }

    @Override
    public int getSize() {
        return delegate.getSize();
    }

    public void setSize(int size) {
        this.size = size;
    }

    @Override
    public void putObject(Object key, Object value) {
        // 检测并清理缓存
        cycleKeyList(key);
        // 添加缓存项
        delegate.putObject(key, value);
    }

    @Override
    public Object getObject(Object key) {
        return delegate.getObject(key);
    }

    @Override
    public Object removeObject(Object key) {
        return delegate.removeObject(key);
    }

    @Override
    public void clear() {
        delegate.clear();
        keyList.clear();
    }

    @Override
    public ReadWriteLock getReadWriteLock() {
        return null;
    }

    private void cycleKeyList(Object key) {
        //增加记录时判断如果记录已达到上限，会移除链表的第一个元素，从而达到FIFO缓存效果
        keyList.addLast(key);
        if (keyList.size() > size) {
            Object oldestKey = keyList.removeFirst();
            delegate.removeObject(oldestKey);
        }
    }

}
