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
package org.apache.ibatis.executor.loader.cglib;

import net.sf.cglib.proxy.Callback;
import net.sf.cglib.proxy.Enhancer;
import net.sf.cglib.proxy.MethodInterceptor;
import net.sf.cglib.proxy.MethodProxy;
import org.apache.ibatis.executor.loader.*;
import org.apache.ibatis.io.Resources;
import org.apache.ibatis.logging.Log;
import org.apache.ibatis.logging.LogFactory;
import org.apache.ibatis.reflection.ExceptionUtil;
import org.apache.ibatis.reflection.factory.ObjectFactory;
import org.apache.ibatis.reflection.property.PropertyCopier;
import org.apache.ibatis.reflection.property.PropertyNamer;
import org.apache.ibatis.session.Configuration;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

/**
 * @author Clinton Begin
 */

/**
 * Cglib延迟加载代理工厂
 */
public class CglibProxyFactory implements ProxyFactory {

    private static final Log log = LogFactory.getLog(CglibProxyFactory.class);
    private static final String FINALIZE_METHOD = "finalize";
    private static final String WRITE_REPLACE_METHOD = "writeReplace";

    public CglibProxyFactory() {
        try {
            //先检查是否有Cglib
            Resources.classForName("net.sf.cglib.proxy.Enhancer");
        } catch (Throwable e) {
            throw new IllegalStateException("Cannot enable lazy loading because CGLIB is not available. Add CGLIB to your classpath.", e);
        }
    }

    static Object crateProxy(Class<?> type, Callback callback, List<Class<?>> constructorArgTypes, List<Object> constructorArgs) {
        //核心就是用cglib的Enhancer
        Enhancer enhancer = new Enhancer();
        enhancer.setCallback(callback);
        enhancer.setSuperclass(type);
        // 查找名为"writeReplace"的方法,查找不到writeReplace()方法则添加
        // WriteReplaceInterface接口,该接口汇总定义了writeReplace()方法
        try {
            type.getDeclaredMethod(WRITE_REPLACE_METHOD);
            // ObjectOutputStream will call writeReplace of objects returned by writeReplace
            log.debug(WRITE_REPLACE_METHOD + " method was found on bean " + type + ", make sure it returns this");
        } catch (NoSuchMethodException e) {
            enhancer.setInterfaces(new Class[]{WriteReplaceInterface.class});
        } catch (SecurityException e) {
            // nothing to do here
        }
        // 根据构造方法的参数列表,调用相应的Enhancer.create()方法,创建代理对象
        Object enhanced = null;
        if (constructorArgTypes.isEmpty()) {
            enhanced = enhancer.create();
        } else {
            Class<?>[] typesArray = constructorArgTypes.toArray(new Class[constructorArgTypes.size()]);
            Object[] valuesArray = constructorArgs.toArray(new Object[constructorArgs.size()]);
            enhanced = enhancer.create(typesArray, valuesArray);
        }
        return enhanced;
    }

    @Override
    public Object createProxy(Object target, ResultLoaderMap lazyLoader, Configuration configuration, ObjectFactory objectFactory, List<Class<?>> constructorArgTypes, List<Object> constructorArgs) {
        return EnhancedResultObjectProxyImpl.createProxy(target, lazyLoader, configuration, objectFactory, constructorArgTypes, constructorArgs);
    }

    public Object createDeserializationProxy(Object target, Map<String, ResultLoaderMap.LoadPair> unloadedProperties, ObjectFactory objectFactory, List<Class<?>> constructorArgTypes, List<Object> constructorArgs) {
        return EnhancedDeserializationProxyImpl.createProxy(target, unloadedProperties, objectFactory, constructorArgTypes, constructorArgs);
    }

    @Override
    public void setProperties(Properties properties) {
        // Not Implemented
    }

    private static class EnhancedResultObjectProxyImpl implements MethodInterceptor {
        // 需要创建代理对象的目标类
        private Class<?> type;
        // ResultLoaderMap对象,其中记录了延迟加载的属性名称与对应ResultLoader对象之间的关系
        private ResultLoaderMap lazyLoader;
        // 在mybatis-config.xml文件中agressiveLazyLoading配置项的值
        private boolean aggressive;
        // 触发延迟加载的方法名列表,如果调用了该列表中的方法,则对全部的延迟加载属性进行加载操作
        private Set<String> lazyLoadTriggerMethods;
        private ObjectFactory objectFactory;
        // 创建代理对象使用的构造方法的参数类型和参数值
        private List<Class<?>> constructorArgTypes;
        private List<Object> constructorArgs;

        private EnhancedResultObjectProxyImpl(Class<?> type, ResultLoaderMap lazyLoader, Configuration configuration, ObjectFactory objectFactory, List<Class<?>> constructorArgTypes, List<Object> constructorArgs) {
            this.type = type;
            this.lazyLoader = lazyLoader;
            this.aggressive = configuration.isAggressiveLazyLoading();
            this.lazyLoadTriggerMethods = configuration.getLazyLoadTriggerMethods();
            this.objectFactory = objectFactory;
            this.constructorArgTypes = constructorArgTypes;
            this.constructorArgs = constructorArgs;
        }

        public static Object createProxy(Object target, ResultLoaderMap lazyLoader, Configuration configuration, ObjectFactory objectFactory, List<Class<?>> constructorArgTypes, List<Object> constructorArgs) {
            final Class<?> type = target.getClass();
            // EnhancedResultObjectProxyImpl 本身就是Callback接口的实现
            EnhancedResultObjectProxyImpl callback = new EnhancedResultObjectProxyImpl(type, lazyLoader, configuration, objectFactory, constructorArgTypes, constructorArgs);
            // 调用cglibProxyFactory.cglibProxy() 方法创建代理对象
            Object enhanced = crateProxy(type, callback, constructorArgTypes, constructorArgs);
            // 将 target 对象中的属性值复制到代理对象的对应属性中
            PropertyCopier.copyBeanProperties(type, target, enhanced);
            return enhanced;
        }

        /**
         * 该方法会根据当前调用的方法名称,决定是否触发对延迟加载属性的加载
         *
         * @param enhanced
         * @param method
         * @param args
         * @param methodProxy
         * @return
         * @throws Throwable
         */
        @Override
        public Object intercept(Object enhanced, Method method, Object[] args, MethodProxy methodProxy) throws Throwable {
            final String methodName = method.getName();
            try {
                synchronized (lazyLoader) {
                    if (WRITE_REPLACE_METHOD.equals(methodName)) {
                        Object original = null;
                        if (constructorArgTypes.isEmpty()) {
                            original = objectFactory.create(type);
                        } else {
                            original = objectFactory.create(type, constructorArgTypes, constructorArgs);
                        }
                        PropertyCopier.copyBeanProperties(type, enhanced, original);
                        if (lazyLoader.size() > 0) {
                            return new CglibSerialStateHolder(original, lazyLoader.getProperties(), objectFactory, constructorArgTypes, constructorArgs);
                        } else {
                            return original;
                        }
                    } else {
                        //这里是关键，延迟加载就是调用ResultLoaderMap.loadAll()
                        // 检测是否存在延迟加载的属性,以及调用方法名是否为finalize
                        if (lazyLoader.size() > 0 && !FINALIZE_METHOD.equals(methodName)) {
                            //如果aggressiveLazyLoading配置项为true,或是调用方法的名称存在于
                            // lazyLoadTriggerMethods类表中,则将全部的属性都加载完成
                            if (aggressive || lazyLoadTriggerMethods.contains(methodName)) {
                                lazyLoader.loadAll();
                            } else if (PropertyNamer.isProperty(methodName)) {
                                // 如果调用了某属性的getter方法,先获取该属性的名称
                                final String property = PropertyNamer.methodToProperty(methodName);
                                // 检测是否为延迟加载的属性
                                if (lazyLoader.hasLoader(property)) {
                                    // 触发该属性延迟加载操作
                                    lazyLoader.load(property);
                                }
                            }
                        }
                    }
                }
                // 调用目标对象的方法
                return methodProxy.invokeSuper(enhanced, args);
            } catch (Throwable t) {
                throw ExceptionUtil.unwrapThrowable(t);
            }
        }
    }

    private static class EnhancedDeserializationProxyImpl extends AbstractEnhancedDeserializationProxy implements MethodInterceptor {

        private EnhancedDeserializationProxyImpl(Class<?> type, Map<String, ResultLoaderMap.LoadPair> unloadedProperties, ObjectFactory objectFactory,
                                                 List<Class<?>> constructorArgTypes, List<Object> constructorArgs) {
            super(type, unloadedProperties, objectFactory, constructorArgTypes, constructorArgs);
        }

        public static Object createProxy(Object target, Map<String, ResultLoaderMap.LoadPair> unloadedProperties, ObjectFactory objectFactory,
                                         List<Class<?>> constructorArgTypes, List<Object> constructorArgs) {
            final Class<?> type = target.getClass();
            EnhancedDeserializationProxyImpl callback = new EnhancedDeserializationProxyImpl(type, unloadedProperties, objectFactory, constructorArgTypes, constructorArgs);
            Object enhanced = crateProxy(type, callback, constructorArgTypes, constructorArgs);
            PropertyCopier.copyBeanProperties(type, target, enhanced);
            return enhanced;
        }

        @Override
        public Object intercept(Object enhanced, Method method, Object[] args, MethodProxy methodProxy) throws Throwable {
            final Object o = super.invoke(enhanced, method, args);
            return (o instanceof AbstractSerialStateHolder) ? o : methodProxy.invokeSuper(o, args);
        }

        @Override
        protected AbstractSerialStateHolder newSerialStateHolder(Object userBean, Map<String, ResultLoaderMap.LoadPair> unloadedProperties, ObjectFactory objectFactory,
                                                                 List<Class<?>> constructorArgTypes, List<Object> constructorArgs) {
            return new CglibSerialStateHolder(userBean, unloadedProperties, objectFactory, constructorArgTypes, constructorArgs);
        }
    }
}
