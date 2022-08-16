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
package org.apache.ibatis.cache;

import java.io.Serializable;
import java.lang.reflect.Array;
import java.util.ArrayList;
import java.util.List;

/**
 * @author Clinton Begin
 */

/**
 * 缓存key
 * 一般缓存框架的数据结构基本上都是 Key-Value 方式存储，
 * MyBatis 对于其 Key 的生成采取规则为：[mappedStementId + offset + limit + SQL + queryParams + environment]生成一个哈希码
 */
public class CacheKey implements Cloneable, Serializable {

    public static final CacheKey NULL_CACHE_KEY = new NullCacheKey();
    private static final long serialVersionUID = 1146682552656046210L;
    private static final int DEFAULT_MULTIPLYER = 37;
    private static final int DEFAULT_HASHCODE = 17;

    //  参与计算 hashcode, 默认值 37
    private int multiplier;
    // CacheKey对象的 hashcode 默认17
    private int hashcode;
    // 校验和
    private long checksum;
    // 集合的个数
    private int count;
    // 由该集合中的所有对象共同决定两个CacheKey是否相同
    private List<Object> updateList;

    public CacheKey() {
        this.hashcode = DEFAULT_HASHCODE;
        this.multiplier = DEFAULT_MULTIPLYER;
        this.count = 0;
        this.updateList = new ArrayList<Object>();
    }

    //传入一个Object数组，更新hashcode和效验码
    public CacheKey(Object[] objects) {
        this();
        updateAll(objects);
    }

    public int getUpdateCount() {
        return updateList.size();
    }

    public void update(Object object) {
        // 添加数组或集合类型
        if (object != null && object.getClass().isArray()) {
            //如果是数组，则循环调用doUpdate
            int length = Array.getLength(object);
            for (int i = 0; i < length; i++) {
                Object element = Array.get(object, i);
                // 重新计算 count, checksum 和 hashcode ,并将数组或集合中的每一项都添加到 updateList 集合中
                doUpdate(element);
            }
        } else {
            // 重新计算 count, checksum 和 hashcode ,并将数组或集合中的每一项都添加到 updateList 集合中
            doUpdate(object);
        }
    }

    private void doUpdate(Object object) {
        //计算hash值，校验码
        int baseHashCode = object == null ? 1 : object.hashCode();

        count++;
        checksum += baseHashCode;
        baseHashCode *= count;

        hashcode = multiplier * hashcode + baseHashCode;

        //同时将对象加入列表，这样万一两个CacheKey的hash码碰巧一样，再根据对象严格equals来区分
        // 将object添加到updateList集合中
        updateList.add(object);
    }

    public void updateAll(Object[] objects) {
        for (Object o : objects) {
            update(o);
        }
    }

    @Override
    public boolean equals(Object object) {
        // 是否是同一对象
        if (this == object) {
            return true;
        }
        // 是否类型相同
        if (!(object instanceof CacheKey)) {
            return false;
        }

        final CacheKey cacheKey = (CacheKey) object;

        //先比hashcode，checksum，count，理论上可以快速比出来
        if (hashcode != cacheKey.hashcode) {
            return false;
        }
        if (checksum != cacheKey.checksum) {
            return false;
        }
        if (count != cacheKey.count) {
            return false;
        }

        //万一两个CacheKey的hash码碰巧一样，再根据对象严格equals来区分
        //这里两个list的size没比是否相等，其实前面count相等就已经保证了
        // 比较updateList中的每一项
        for (int i = 0; i < updateList.size(); i++) {
            Object thisObject = updateList.get(i);
            Object thatObject = cacheKey.updateList.get(i);
            if (thisObject == null) {
                if (thatObject != null) {
                    return false;
                }
            } else {
                if (!thisObject.equals(thatObject)) {
                    return false;
                }
            }
        }
        return true;
    }

    @Override
    public int hashCode() {
        return hashcode;
    }

    @Override
    public String toString() {
        StringBuilder returnValue = new StringBuilder().append(hashcode).append(':').append(checksum);
        for (int i = 0; i < updateList.size(); i++) {
            returnValue.append(':').append(updateList.get(i));
        }

        return returnValue.toString();
    }

    @Override
    public CacheKey clone() throws CloneNotSupportedException {
        CacheKey clonedCacheKey = (CacheKey) super.clone();
        clonedCacheKey.updateList = new ArrayList<Object>(updateList);
        return clonedCacheKey;
    }

}
