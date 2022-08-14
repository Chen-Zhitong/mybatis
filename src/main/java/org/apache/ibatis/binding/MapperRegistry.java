/*
 *    Copyright 2009-2013 the original author or authors.
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
package org.apache.ibatis.binding;

import org.apache.ibatis.builder.annotation.MapperAnnotationBuilder;
import org.apache.ibatis.io.ResolverUtil;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSession;

import java.util.*;

/**
 * @author Clinton Begin
 * @author Eduardo Macarron
 * @author Lasse Voss
 */

/**
 * 是Mapper接口及其对应代理对象工厂的注册中心.
 */
public class MapperRegistry {

    // 记录了Mapper接口与对应MapperProxyFactory之间的关系
    private final Map<Class<?>, MapperProxyFactory<?>> knownMappers = new HashMap<Class<?>, MapperProxyFactory<?>>();
    // Configuration 对象, MyBatis全局唯一的配置对象, 其中包含了所有配置信息
    private Configuration config;

    public MapperRegistry(Configuration config) {
        this.config = config;
    }

    // 在需要执行某SQL语句时，会先调用MapperRegistry.getMapper（）方法获取实现了Mapper接口的代理对象，例如本节开始的示例中，
    // session.getMapper（BlogMapper.class）方法得到的实际上是MyBatis通过JDK动态代理为BlogMapper接口生成的代理对象。
    //
    // 摘录来自: 徐郡明. “MyBatis技术内幕。” Apple Books.
    @SuppressWarnings("unchecked")
    public <T> T getMapper(Class<T> type, SqlSession sqlSession) {
        // 查找指定type对应的MapperProxyFactory对象
        final MapperProxyFactory<T> mapperProxyFactory = (MapperProxyFactory<T>) knownMappers.get(type);
        // 为空则抛出异常
        if (mapperProxyFactory == null) {
            throw new BindingException("Type " + type + " is not known to the MapperRegistry.");
        }
        try {
            // 创建实现了type接口的代理对象
            return mapperProxyFactory.newInstance(sqlSession);
        } catch (Exception e) {
            throw new BindingException("Error getting mapper instance. Cause: " + e, e);
        }
    }

    public <T> boolean hasMapper(Class<T> type) {
        return knownMappers.containsKey(type);
    }

    // 在MyBatis初始化过程中会读取映射配置文件以及Mapper接口中的注解信息，并调用MapperRegistry.addMapper（）方法填充MapperRegistry.knownMappers集合，
    // 该集合的key是Mapper接口对应的Class对象，value为MapperProxyFactory工厂对象，可以为Mapper接口创建代理对象，MapperProxyFactory的实现马上就会分析到。
    //
    // 摘录来自: 徐郡明. “MyBatis技术内幕。” Apple Books.
    public <T> void addMapper(Class<T> type) {
        // 检测type是否为接口
        if (type.isInterface()) {
            // 检测是否已经加载过该接口
            if (hasMapper(type)) {
                //如果重复添加了，报错
                throw new BindingException("Type " + type + " is already known to the MapperRegistry.");
            }
            boolean loadCompleted = false;
            try {
                // 将Mapper接口对应的Class对象和MapperProxyFactory对象添加到 knownMappers 集合
                knownMappers.put(type, new MapperProxyFactory<T>(type));
                // It's important that the type is added before the parser is run
                // otherwise the binding may automatically be attempted by the
                // mapper parser. If the type is already known, it won't try.
                // 这里涉及XML解析和注解的处理
                MapperAnnotationBuilder parser = new MapperAnnotationBuilder(config, type);
                parser.parse();
                loadCompleted = true;
            } finally {
                //如果加载过程中出现异常需要再将这个mapper从mybatis中删除,这种方式比较丑陋吧，难道是不得已而为之？
                if (!loadCompleted) {
                    knownMappers.remove(type);
                }
            }
        }
    }

    /**
     * @since 3.2.2
     */
    public Collection<Class<?>> getMappers() {
        return Collections.unmodifiableCollection(knownMappers.keySet());
    }

    /**
     * @since 3.2.2
     */
    public void addMappers(String packageName, Class<?> superType) {
        //查找包下所有是superType的类
        ResolverUtil<Class<?>> resolverUtil = new ResolverUtil<Class<?>>();
        resolverUtil.find(new ResolverUtil.IsA(superType), packageName);
        Set<Class<? extends Class<?>>> mapperSet = resolverUtil.getClasses();
        for (Class<?> mapperClass : mapperSet) {
            addMapper(mapperClass);
        }
    }

    /**
     * @since 3.2.2
     */
    //查找包下所有类
    public void addMappers(String packageName) {
        addMappers(packageName, Object.class);
    }

}
