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
package org.apache.ibatis.reflection.wrapper;

import org.apache.ibatis.reflection.MetaObject;
import org.apache.ibatis.reflection.factory.ObjectFactory;
import org.apache.ibatis.reflection.property.PropertyTokenizer;

import java.util.List;

/**
 * ObjectWrapper接口是对对象的包装，抽象了对象的属性信息，它定义了一系列查询对象属性信息的方法，以及更新属性的方法。
 *
 * @author Clinton Begin
 */
public interface ObjectWrapper {

    // 如果 ObjectWrapper 中封装的是普通Bean对象, 则调用相应属性的相应getter方法,
    // 如果封装的是集合类, 则获取指定key或下标对应的 value 值
    Object get(PropertyTokenizer prop);

    //set
    void set(PropertyTokenizer prop, Object value);

    // 查找属性表达式指定的属性, 第二个参数表示是否忽略属性表达式中的下划线
    String findProperty(String name, boolean useCamelCaseMapping);

    // 查找可写属性的名称集合
    String[] getGetterNames();

    // 查找可读属性的名称集合
    String[] getSetterNames();

    // 解析属性表达式指定属性的 setter 方法的参数类型
    Class<?> getSetterType(String name);

    // 解析属性表达式指定属性的 getter 方法的返回值类型
    Class<?> getGetterType(String name);

    //是否有指定的setter
    boolean hasSetter(String name);

    //是否有指定的getter
    boolean hasGetter(String name);

    // 为属性表达式指定的属性创建相应的 MetaObject 对象
    MetaObject instantiatePropertyValue(String name, PropertyTokenizer prop, ObjectFactory objectFactory);

    //是否是集合
    boolean isCollection();

    // 调用Collection对象的 add() 方法
    public void add(Object element);

    // 调用Collection对象的 addAll() 方法
    public <E> void addAll(List<E> element);

}
