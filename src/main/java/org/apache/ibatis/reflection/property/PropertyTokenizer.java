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
package org.apache.ibatis.reflection.property;

import java.util.Iterator;

/**
 * 属性分解为标记，迭代子模式
 * 如person[0].birthdate.year，将依次取得person[0], birthdate, year
 * PropertyTokenizer继承了Iterator接口, 它可以迭代处理嵌套多层表达式.
 *
 * @author Clinton Begin
 */
public class PropertyTokenizer implements Iterable<PropertyTokenizer>, Iterator<PropertyTokenizer> {
    //例子： person[0].birthdate.year
    private String name; //person  当前表达式的名称
    private String indexedName; //person[0]  当前表达式的索引名
    private String index; //0  索引下标
    private String children; //birthdate.year  子表达式


    /**
     * 构造方法会对传入的表达式进行分析,并初始化上述字段
     *
     * @param fullname
     */
    public PropertyTokenizer(String fullname) {
        //person[0].birthdate.year
        // 查找"."的位置
        int delim = fullname.indexOf('.');
        if (delim > -1) {
            // 初始化name
            name = fullname.substring(0, delim);
            // 初始化children
            children = fullname.substring(delim + 1);
        } else {
            //找不到.的话，取全部部分
            name = fullname;
            children = null;
        }
        // 初始化 indexedName
        indexedName = name;
        //把中括号里的数字给解析出来
        delim = name.indexOf('[');
        if (delim > -1) {
            // 初始化index
            index = name.substring(delim + 1, name.length() - 1);
            name = name.substring(0, delim);
        }
    }

    public String getName() {
        return name;
    }

    public String getIndex() {
        return index;
    }

    public String getIndexedName() {
        return indexedName;
    }

    public String getChildren() {
        return children;
    }

    @Override
    public boolean hasNext() {
        return children != null;
    }

    //取得下一个,非常简单，直接再通过儿子来new另外一个实例
    @Override
    public PropertyTokenizer next() {
        return new PropertyTokenizer(children);
    }

    @Override
    public void remove() {
        throw new UnsupportedOperationException("Remove is not supported, as it has no meaning in the context of properties.");
    }

    @Override
    public Iterator<PropertyTokenizer> iterator() {
        return this;
    }
}
