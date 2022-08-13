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
package org.apache.ibatis.reflection;

import org.apache.ibatis.reflection.factory.ObjectFactory;
import org.apache.ibatis.reflection.property.PropertyTokenizer;
import org.apache.ibatis.reflection.wrapper.*;

import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * @author Clinton Begin
 */

/**
 * 元对象,各种get，set方法有点ognl表达式的味道
 * 可以参考MetaObjectTest来跟踪调试，基本上用到了reflection包下所有的类
 */
public class MetaObject {

    // 原始JavaBean对象
    private Object originalObject;
    // 上文介绍的ObjectWrapper对象, 其中封装了originalObject对象
    private ObjectWrapper objectWrapper;
    // 负责实例化 originalObject 的工厂对象
    private ObjectFactory objectFactory;
    // 负责创建ObjectWrapper的工厂对象
    private ObjectWrapperFactory objectWrapperFactory;

    private MetaObject(Object object, ObjectFactory objectFactory, ObjectWrapperFactory objectWrapperFactory) {
        // 初始化上述字段
        this.originalObject = object;
        this.objectFactory = objectFactory;
        this.objectWrapperFactory = objectWrapperFactory;

        if (object instanceof ObjectWrapper) {
            //如果对象本身已经是ObjectWrapper型，则直接赋给objectWrapper
            this.objectWrapper = (ObjectWrapper) object;
        } else if (objectWrapperFactory.hasWrapperFor(object)) {
            // 若ObjectWrapperFactory能够为该原始对象创建对应的ObjectWrapper对象,则由优先使用ObjectWrapperFactory,
            // 而 DefaultObjectWrapperFactory.hasWrapperFor() 则始终返回false
            // 用户可以自定义 ObjectWrapperFactory 实现进行扩展
            this.objectWrapper = objectWrapperFactory.getWrapperFor(this, object);
        } else if (object instanceof Map) {
            //如果是Map型，返回MapWrapper
            this.objectWrapper = new MapWrapper(this, (Map) object);
        } else if (object instanceof Collection) {
            //如果是Collection型，返回CollectionWrapper
            this.objectWrapper = new CollectionWrapper(this, (Collection) object);
        } else {
            //除此以外，返回BeanWrapper
            this.objectWrapper = new BeanWrapper(this, object);
        }
    }

    // MetaObject的构造方法时 private 修饰的, 只能通过forObject()这个静态方法创建MetaObject对象
    public static MetaObject forObject(Object object, ObjectFactory objectFactory, ObjectWrapperFactory objectWrapperFactory) {
        if (object == null) {
            // 如果object为null, 则统一返回 SystemMetaObject.NULL_META_OBJECT 这个对象
            return SystemMetaObject.NULL_META_OBJECT;
        } else {
            return new MetaObject(object, objectFactory, objectWrapperFactory);
        }
    }

    public ObjectFactory getObjectFactory() {
        return objectFactory;
    }

    public ObjectWrapperFactory getObjectWrapperFactory() {
        return objectWrapperFactory;
    }

    public Object getOriginalObject() {
        return originalObject;
    }

    //--------以下方法都是委派给ObjectWrapper------
    //查找属性
    public String findProperty(String propName, boolean useCamelCaseMapping) {
        return objectWrapper.findProperty(propName, useCamelCaseMapping);
    }

    //取得getter的名字列表
    public String[] getGetterNames() {
        return objectWrapper.getGetterNames();
    }

    //取得setter的名字列表
    public String[] getSetterNames() {
        return objectWrapper.getSetterNames();
    }

    //取得setter的类型列表
    public Class<?> getSetterType(String name) {
        return objectWrapper.getSetterType(name);
    }

    //取得getter的类型列表
    public Class<?> getGetterType(String name) {
        return objectWrapper.getGetterType(name);
    }

    //是否有指定的setter
    public boolean hasSetter(String name) {
        return objectWrapper.hasSetter(name);
    }

    //是否有指定的getter
    public boolean hasGetter(String name) {
        return objectWrapper.hasGetter(name);
    }

    /**
     * 为了帮助读者理解，这里依然以“orders[0].id”这个属性表达式为例来分析MetaObject.getValue（）方法的执行流程:
     *
     * （1）创建User对象相应的MetaObject对象，并调用MetaObject.getValue（）方法，经过PropertyTokenizer解析“orders[0].id”表达式之后，
     * 其name为orders，indexedName为orders[0]，index为0，children为id。
     *
     * （2）调用MetaObject.metaObjectForProperty（）方法处理“orders[0]”表达式，
     * 底层会调用BeanWrapper.get（）方法获取orders集合中第一个Order对象，其中先通过BeanWrapper.resolve-Collection（）方法获取orders集合对象，
     * 然后调用BeanWrapper.getCollectionValue（）方法获取orders集合中的第一个元素。注意，这个过程中会递归调用MetaObject.getValue（）方法。
     *
     * （3）得到Order对象后，创建其相应的MetaObject对象，并调用MetaObject.getValue（）方法处理“id”表达式，逻辑同上，
     * 最后得到属性表达式指定属性的值，即User对象的orders集合属性中第一个Order元素的id属性值。
     *
     * 摘录来自: 徐郡明. “MyBatis技术内幕。” Apple Books.
     *
     * @param name
     * @return
     */
    //取得值
    //如person[0].birthdate.year
    //具体测试用例可以看MetaObjectTest
    public Object getValue(String name) {
        //  解析属性表达式
        PropertyTokenizer prop = new PropertyTokenizer(name);
        // 处理子表达式
        if (prop.hasNext()) {
            // 根据 PropertyTokenizer 解析后指定的属性, 创建相应的 MetaObject对象
            MetaObject metaValue = metaObjectForProperty(prop.getIndexedName());
            if (metaValue == SystemMetaObject.NULL_META_OBJECT) {
                //如果上层就是null了，那就结束，返回null
                return null;
            } else {
                //否则继续看下一层，递归调用getValue
                return metaValue.getValue(prop.getChildren());
            }
        } else {
            // 通过ObjectWrapper 获取指定的属性值
            return objectWrapper.get(prop);
        }
    }

    //设置值
    //如person[0].birthdate.year
    public void setValue(String name, Object value) {
        PropertyTokenizer prop = new PropertyTokenizer(name);
        if (prop.hasNext()) {
            MetaObject metaValue = metaObjectForProperty(prop.getIndexedName());
            if (metaValue == SystemMetaObject.NULL_META_OBJECT) {
                if (value == null && prop.getChildren() != null) {
                    // don't instantiate child path if value is null
                    //如果上层就是null了，还得看有没有儿子，没有那就结束
                    return;
                } else {
                    //否则还得new一个，委派给ObjectWrapper.instantiatePropertyValue
                    metaValue = objectWrapper.instantiatePropertyValue(name, prop, objectFactory);
                }
            }
            //递归调用setValue
            metaValue.setValue(prop.getChildren(), value);
        } else {
            //到了最后一层了，所以委派给ObjectWrapper.set
            objectWrapper.set(prop, value);
        }
    }

    //为某个属性生成元对象
    public MetaObject metaObjectForProperty(String name) {
        //实际是递归调用
        // 获取指定的属性
        Object value = getValue(name);
        // 创建该属性对象相应的MetaObject对象
        return MetaObject.forObject(value, objectFactory, objectWrapperFactory);
    }

    public ObjectWrapper getObjectWrapper() {
        return objectWrapper;
    }

    //是否是集合
    public boolean isCollection() {
        return objectWrapper.isCollection();
    }

    //添加属性
    public void add(Object element) {
        objectWrapper.add(element);
    }

    //添加属性
    public <E> void addAll(List<E> list) {
        objectWrapper.addAll(list);
    }

}
