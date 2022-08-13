/*
 *    Copyright 2009-2011 the original author or authors.
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
package org.apache.ibatis.reflection;

import org.apache.ibatis.reflection.invoker.GetFieldInvoker;
import org.apache.ibatis.reflection.invoker.Invoker;
import org.apache.ibatis.reflection.invoker.MethodInvoker;
import org.apache.ibatis.reflection.property.PropertyTokenizer;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.Collection;

/**
 * MetaClass通过Reflector和propertyTokenizer组合使用实现了对复杂的属性表达式的解析
 * 并实现了获取指定属性描述信息的功能
 *
 * @author Clinton Begin
 */
public class MetaClass {

    // 在创建MetaClass时会指定一个类,该Reflector对象会用于记录该类相关的元信息
    private Reflector reflector;

    //  构造函数会为指定的Class创建相应的reflector对象,并用其初始化reflector对象
    private MetaClass(Class<?> type) {
        this.reflector = Reflector.forClass(type);
    }

    public static MetaClass forClass(Class<?> type) {
        return new MetaClass(type);
    }

    public static boolean isClassCacheEnabled() {
        return Reflector.isClassCacheEnabled();
    }

    public static void setClassCacheEnabled(boolean classCacheEnabled) {
        Reflector.setClassCacheEnabled(classCacheEnabled);
    }

    public MetaClass metaClassForProperty(String name) {
        // 查找指定属性对应的 Class
        Class<?> propType = reflector.getGetterType(name);
        // 为该属性创建对应的MetaClass 对象
        return MetaClass.forClass(propType);
    }

    /**
     * 通过调用 buildProperty 方法实现
     * 注意: 只查找"."导航的属性, 并没有检测下标.
     *
     * 这里以解析User类中的tele.num这个属性表达式为例解释上述过程：
     * 首先使用PropertyTokenizer解析tele.num表达式得到其children字段为num，name字段为tele；
     * 然后将tele追加到builder中保存，并调用metaClassForProperty（）方法为Tele类创建对应的MetaClass对象，
     * 调用其buildProperty（）方法处理子表达式num，逻辑同上，
     * 此时已经没有待处理的子表达式，最终得到builder中记录的字符串为tele.num。
     *
     * 摘录来自: 徐郡明. “MyBatis技术内幕。” Apple Books.
     * @param name
     * @return
     */
    public String findProperty(String name) {
        // 委托给 buildproperty() 方法实现
        StringBuilder prop = buildProperty(name, new StringBuilder());
        return prop.length() > 0 ? prop.toString() : null;
    }

    public String findProperty(String name, boolean useCamelCaseMapping) {
        if (useCamelCaseMapping) {
            name = name.replace("_", "");
        }
        return findProperty(name);
    }

    public String[] getGetterNames() {
        return reflector.getGetablePropertyNames();
    }

    public String[] getSetterNames() {
        return reflector.getSetablePropertyNames();
    }

    public Class<?> getSetterType(String name) {
        PropertyTokenizer prop = new PropertyTokenizer(name);
        if (prop.hasNext()) {
            MetaClass metaProp = metaClassForProperty(prop.getName());
            return metaProp.getSetterType(prop.getChildren());
        } else {
            return reflector.getSetterType(prop.getName());
        }
    }

    public Class<?> getGetterType(String name) {
        PropertyTokenizer prop = new PropertyTokenizer(name);
        if (prop.hasNext()) {
            MetaClass metaProp = metaClassForProperty(prop);
            return metaProp.getGetterType(prop.getChildren());
        }
        // issue #506. Resolve the type inside a Collection Object
        return getGetterType(prop);
    }

    private MetaClass metaClassForProperty(PropertyTokenizer prop) {
        // 获取表达式所表示的属性的类型
        Class<?> propType = getGetterType(prop);
        return MetaClass.forClass(propType);
    }

    private Class<?> getGetterType(PropertyTokenizer prop) {
        // 获取属性类型
        Class<?> type = reflector.getGetterType(prop.getName());
        // 该表达式中是否使用"[]"指定了下标,且是Collection子类
        if (prop.getIndex() != null && Collection.class.isAssignableFrom(type)) {
            // 通过解析属性的类型
            Type returnType = getGenericGetterType(prop.getName());
            // 针对 ParameterizedType 进行处理, 即针对泛型集合类型进行处理
            if (returnType instanceof ParameterizedType) {
                // 获取实际的类型参数
                Type[] actualTypeArguments = ((ParameterizedType) returnType).getActualTypeArguments();
                // 如果有一个实际类型则返回泛型的类型
                if (actualTypeArguments != null && actualTypeArguments.length == 1) {
                    // 泛型的类型
                    returnType = actualTypeArguments[0];
                    if (returnType instanceof Class) {
                        type = (Class<?>) returnType;
                    } else if (returnType instanceof ParameterizedType) {
                        type = (Class<?>) ((ParameterizedType) returnType).getRawType();
                    }
                }
            }
        }
        return type;
    }

    private Type getGenericGetterType(String propertyName) {
        try {
            // 根据Reflector.getMethod集合中记录的Invoker实现类的类型,
            // 决定解析getter方法的返回值类型还是解析字段类型
            Invoker invoker = reflector.getGetInvoker(propertyName);
            if (invoker instanceof MethodInvoker) {
                Field _method = MethodInvoker.class.getDeclaredField("method");
                _method.setAccessible(true);
                Method method = (Method) _method.get(invoker);
                return method.getGenericReturnType();
            } else if (invoker instanceof GetFieldInvoker) {
                Field _field = GetFieldInvoker.class.getDeclaredField("field");
                _field.setAccessible(true);
                Field field = (Field) _field.get(invoker);
                return field.getGenericType();
            }
        } catch (NoSuchFieldException e) {
        } catch (IllegalAccessException e) {
        }
        return null;
    }

    public boolean hasSetter(String name) {
        PropertyTokenizer prop = new PropertyTokenizer(name);
        if (prop.hasNext()) {
            if (reflector.hasSetter(prop.getName())) {
                MetaClass metaProp = metaClassForProperty(prop.getName());
                return metaProp.hasSetter(prop.getChildren());
            } else {
                return false;
            }
        } else {
            return reflector.hasSetter(prop.getName());
        }
    }

    /**
     * 判断属性表达式所表示的属性是否有其对应的属性式，
     * 假设现在通过orders[0].id这个属性表达式，检测User类中orders字段中的第一个元素（Order对象）的id字段是否有getter方法，大致步骤如下：
     *
     * （1）我们调用MetaClass.forClass（）方法创建User对应的MetaClass对象并调用其hasGetter（）方法开始解析，
     * 经过PropertyTokenizer对属性表达式的解析后，PropertyTokenizer对象的name值为orders，indexName为orders[0]，index为0，children为name。
     *
     * （2）进入到MetaClass.getGetterType（）方法，此时（1）处条件成立，调用getGenericGetterType（）方法解析orders字段的类型，
     * 得到returnType为List＜Order＞对应的ParameterizedType对象，此时条件（2）成立，更新returnType为Order对应的Class对象。
     *
     * （3）继续检测Order中的id字段是否有getter方法，具体逻辑同上。
     *
     * @param name
     * @return
     */
    public boolean hasGetter(String name) {
        // 解析属性表达式
        PropertyTokenizer prop = new PropertyTokenizer(name);
        // 存在待处理的子表达式
        if (prop.hasNext()) {
            // PropertyTokenizer.name 指定的属性有getter方法, 才能处理子表达式
            if (reflector.hasGetter(prop.getName())) {
                // 注意, 这里的 metaClassForProperty(PropertyTokenizer)方 法是 metaClassForProperty的重载
                MetaClass metaProp = metaClassForProperty(prop);
                // 递归入口
                return metaProp.hasGetter(prop.getChildren());
            } else {
                // 递归出口
                return false;
            }
        } else {
            // 递归出口
            return reflector.hasGetter(prop.getName());
        }
    }

    public Invoker getGetInvoker(String name) {
        return reflector.getGetInvoker(name);
    }

    public Invoker getSetInvoker(String name) {
        return reflector.getSetInvoker(name);
    }

    private StringBuilder buildProperty(String name, StringBuilder builder) {
        // 解析属性表达式
        PropertyTokenizer prop = new PropertyTokenizer(name);
        // 是否还有子表达式
        if (prop.hasNext()) {
            // 查找PropertyTokenizer.name对应的属性
            String propertyName = reflector.findPropertyName(prop.getName());
            if (propertyName != null) {
                // 追加属性名
                builder.append(propertyName);
                builder.append(".");
                // 为该属性创建对应的 MetaClass 对象
                MetaClass metaProp = metaClassForProperty(propertyName);
                // 递归解析 PropertyTokenizer.children 字段,并将解析结果添加到builder中保存
                metaProp.buildProperty(prop.getChildren(), builder);
            }
        } else {
            // 递归出口
            String propertyName = reflector.findPropertyName(name);
            if (propertyName != null) {
                builder.append(propertyName);
            }
        }
        return builder;
    }

    public boolean hasDefaultConstructor() {
        return reflector.hasDefaultConstructor();
    }

}
