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
package org.apache.ibatis.scripting.xmltags;

import org.apache.ibatis.builder.SqlSourceBuilder;
import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.mapping.SqlSource;
import org.apache.ibatis.session.Configuration;

import java.util.Map;

/**
 * @author Clinton Begin
 */

/**
 * 动态SQL源码
 */
public class DynamicSqlSource implements SqlSource {

    private Configuration configuration;
    // 记录了待解析的SqlNode树的根节点
    private SqlNode rootSqlNode;

    public DynamicSqlSource(Configuration configuration, SqlNode rootSqlNode) {
        this.configuration = configuration;
        this.rootSqlNode = rootSqlNode;
    }

    //得到绑定的SQL
    @Override
    public BoundSql getBoundSql(Object parameterObject) {
        // 创建 DynamicContext 对象, parameterObject 是用户传入的实参
        DynamicContext context = new DynamicContext(configuration, parameterObject);
        // 通过调用rootSqlNode.apply()方法调用整个树形结构中全部 SqlNode.apply()方法.
        // 每个SqlNode的apply()方法都将解析得到的SQL语句片段追加到context中, 最终通过context.getSql()得到完整的SQL语句.
        rootSqlNode.apply(context);
        // 创建SqlSourceBuilder, 解析参数属性, 并将SQL语句中的"#{}"占位符替换成"?"占位符
        SqlSourceBuilder sqlSourceParser = new SqlSourceBuilder(configuration);
        Class<?> parameterType = parameterObject == null ? Object.class : parameterObject.getClass();
        //SqlSourceBuilder.parse,注意这里返回的是StaticSqlSource,解析完了就把那些参数都替换成?了，也就是最基本的JDBC的SQL写法
        SqlSource sqlSource = sqlSourceParser.parse(context.getSql(), parameterType, context.getBindings());
        // 创建BoundSql对象, 并将DynamicContext.bindings中的参数信息复制到其additionalParameters集合中保存
        BoundSql boundSql = sqlSource.getBoundSql(parameterObject);
        for (Map.Entry<String, Object> entry : context.getBindings().entrySet()) {
            boundSql.setAdditionalParameter(entry.getKey(), entry.getValue());
        }
        return boundSql;
    }

}
