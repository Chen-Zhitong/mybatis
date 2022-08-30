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
package org.apache.ibatis.mapping;

import org.apache.ibatis.cache.Cache;
import org.apache.ibatis.cache.CacheException;
import org.apache.ibatis.cache.decorators.*;
import org.apache.ibatis.cache.impl.PerpetualCache;
import org.apache.ibatis.reflection.MetaObject;
import org.apache.ibatis.reflection.SystemMetaObject;

import java.lang.reflect.Constructor;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;

/**
 * @author Clinton Begin
 */

/**
 * 缓存构建器,建造者模式
 */
public class CacheBuilder {
    // Cache 对象的唯一标识, 一般情况下对应映射文件中的配置namespace
    private String id;
    // Cache接口的真正实现类, 默认值是之前介绍的 PerpetualCache
    private Class<? extends Cache> implementation;
    // 装饰器集合, 默认只包含LruCache.class
    private List<Class<? extends Cache>> decorators;
    // Cache大小
    private Integer size;
    // 清理时间周期
    private Long clearInterval;
    // 是否可读写
    private boolean readWrite;
    // 其他配置信息
    private Properties properties;
    // 是否阻塞
    private boolean blocking;

    public CacheBuilder(String id) {
        this.id = id;
        this.decorators = new ArrayList<Class<? extends Cache>>();
    }

    public CacheBuilder implementation(Class<? extends Cache> implementation) {
        this.implementation = implementation;
        return this;
    }

    public CacheBuilder addDecorator(Class<? extends Cache> decorator) {
        if (decorator != null) {
            this.decorators.add(decorator);
        }
        return this;
    }

    public CacheBuilder size(Integer size) {
        this.size = size;
        return this;
    }

    public CacheBuilder clearInterval(Long clearInterval) {
        this.clearInterval = clearInterval;
        return this;
    }

    public CacheBuilder readWrite(boolean readWrite) {
        this.readWrite = readWrite;
        return this;
    }

    public CacheBuilder blocking(boolean blocking) {
        this.blocking = blocking;
        return this;
    }

    public CacheBuilder properties(Properties properties) {
        this.properties = properties;
        return this;
    }

    public Cache build() {
        // 如果 implementation 字段和 decorators集合为空,则为其设置默认值,implementation默认值
        // 是PerpetualCache.class, decorators集合默认只包含 LruCache.class
        setDefaultImplementations();
        // 根据 implementation 指定的类型,通过反射获取参数为String类型的构造方法,
        // 并通过该构造方法创建Cache对象
        Cache cache = newBaseCacheInstance(implementation, id);
        // 根据<cache>节点下配置的<property>信息,初始化 Cache 对象
        setCacheProperties(cache);

        // issue #352, do not apply decorators to custom caches
        // 检测 cache 对象的类型, 如果PerpetualCache类型,则为其添加 decorators集合中的装饰器
        // 如果是自定义类型的Cache接口实现, 则不添加decorators集合中的装饰器
        if (PerpetualCache.class.equals(cache.getClass())) {
            for (Class<? extends Cache> decorator : decorators) {
                //装饰者模式一个个包装cache
                // 通过反射获取参数为Cache类型的构造方法, 并通过该构造方法创建装饰器
                cache = newCacheDecoratorInstance(decorator, cache);
                //又要来一遍设额外属性
                // 配置cache对象的属性
                setCacheProperties(cache);
            }
            //最后附加上标准的装饰者
            // 添加MyBatis中提供的标准装饰器
            cache = setStandardDecorators(cache);
        } else if (!LoggingCache.class.isAssignableFrom(cache.getClass())) {
            // 如果不是LoggingCache的子类, 则添加Logging装饰器
            cache = new LoggingCache(cache);
        }
        return cache;
    }

    private void setDefaultImplementations() {
        //又是一重保险，如果为null则设默认值,和XMLMapperBuilder.cacheElement以及MapperBuilderAssistant.useNewCache逻辑重复了
        if (implementation == null) {
            implementation = PerpetualCache.class;
            if (decorators.isEmpty()) {
                decorators.add(LruCache.class);
            }
        }
    }

    /**
     * 会根据CacheBuilder中各个字段的值,
     * 为cache对象添加对应的装饰器
     *
     * @param cache
     * @return
     */
    private Cache setStandardDecorators(Cache cache) {
        try {
            // 创建 cache 对象对应的 MetaObject 对象
            MetaObject metaCache = SystemMetaObject.forObject(cache);
            if (size != null && metaCache.hasSetter("size")) {
                metaCache.setValue("size", size);
            }
            // 检测是否制定了clearInterval字段
            if (clearInterval != null) {
                //刷新缓存间隔,怎么刷新呢，用ScheduledCache来刷，还是装饰者模式，漂亮！
                // 添加 ScheduledCahce 的 clearInterval字段
                cache = new ScheduledCache(cache);
                ((ScheduledCache) cache).setClearInterval(clearInterval);
            }
            //  是否只读, 对应添加SerializedCache装饰器
            if (readWrite) {
                //如果readOnly=false,可读写的缓存 会返回缓存对象的拷贝(通过序列化) 。这会慢一些,但是安全,因此默认是 false。
                cache = new SerializedCache(cache);
            }
            // 默认添加 LoggingCache 和 SynchronizedCache两个装饰器
            cache = new LoggingCache(cache);
            //同步缓存, 3.2.6以后这个类已经没用了，考虑到Hazelcast, EhCache已经有锁机制了，所以这个锁就画蛇添足了。
            cache = new SynchronizedCache(cache);
            // 是否阻塞, 对应添加 BlockingCache装饰器
            if (blocking) {
                cache = new BlockingCache(cache);
            }
            return cache;
        } catch (Exception e) {
            throw new CacheException("Error building standard cache decorators.  Cause: " + e, e);
        }
    }

    /**
     * 会根据<cache>节点下配置的<prperty>信息,初始化Cache对象
     *
     * @param cache
     */
    private void setCacheProperties(Cache cache) {
        if (properties != null) {
            // cache 对应的创建 Metaobject 对象
            MetaObject metaCache = SystemMetaObject.forObject(cache);
            //用反射设置额外的property属性
            for (Map.Entry<Object, Object> entry : properties.entrySet()) {
                // 配置项的名称, 即 Cache 对应的属性名称
                String name = (String) entry.getKey();
                // 配置项的值, 即Cache对应的属性值
                String value = (String) entry.getValue();
                // 检测cache是否有该属性对应的setter方法
                if (metaCache.hasSetter(name)) {
                    // 获取该属性的类型
                    Class<?> type = metaCache.getSetterType(name);
                    //下面就是各种基本类型的判断了，味同嚼蜡但是又不得不写
                    // 进行类型转换,并设置该属性值
                    if (String.class == type) {
                        metaCache.setValue(name, value);
                    } else if (int.class == type
                            || Integer.class == type) {
                        metaCache.setValue(name, Integer.valueOf(value));
                    } else if (long.class == type
                            || Long.class == type) {
                        metaCache.setValue(name, Long.valueOf(value));
                    } else if (short.class == type
                            || Short.class == type) {
                        metaCache.setValue(name, Short.valueOf(value));
                    } else if (byte.class == type
                            || Byte.class == type) {
                        metaCache.setValue(name, Byte.valueOf(value));
                    } else if (float.class == type
                            || Float.class == type) {
                        metaCache.setValue(name, Float.valueOf(value));
                    } else if (boolean.class == type
                            || Boolean.class == type) {
                        metaCache.setValue(name, Boolean.valueOf(value));
                    } else if (double.class == type
                            || Double.class == type) {
                        metaCache.setValue(name, Double.valueOf(value));
                    } else {
                        throw new CacheException("Unsupported property type for cache: '" + name + "' of type " + type);
                    }
                }
            }
        }
    }

    private Cache newBaseCacheInstance(Class<? extends Cache> cacheClass, String id) {
        Constructor<? extends Cache> cacheConstructor = getBaseCacheConstructor(cacheClass);
        try {
            return cacheConstructor.newInstance(id);
        } catch (Exception e) {
            throw new CacheException("Could not instantiate cache implementation (" + cacheClass + "). Cause: " + e, e);
        }
    }

    private Constructor<? extends Cache> getBaseCacheConstructor(Class<? extends Cache> cacheClass) {
        try {
            return cacheClass.getConstructor(String.class);
        } catch (Exception e) {
            throw new CacheException("Invalid base cache implementation (" + cacheClass + ").  " +
                    "Base cache implementations must have a constructor that takes a String id as a parameter.  Cause: " + e, e);
        }
    }

    private Cache newCacheDecoratorInstance(Class<? extends Cache> cacheClass, Cache base) {
        Constructor<? extends Cache> cacheConstructor = getCacheDecoratorConstructor(cacheClass);
        try {
            return cacheConstructor.newInstance(base);
        } catch (Exception e) {
            throw new CacheException("Could not instantiate cache decorator (" + cacheClass + "). Cause: " + e, e);
        }
    }

    private Constructor<? extends Cache> getCacheDecoratorConstructor(Class<? extends Cache> cacheClass) {
        try {
            return cacheClass.getConstructor(Cache.class);
        } catch (Exception e) {
            throw new CacheException("Invalid cache decorator (" + cacheClass + ").  " +
                    "Cache decorators must have a constructor that takes a Cache instance as a parameter.  Cause: " + e, e);
        }
    }
}
