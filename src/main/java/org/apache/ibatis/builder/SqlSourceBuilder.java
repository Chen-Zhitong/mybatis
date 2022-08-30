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
package org.apache.ibatis.builder;

import org.apache.ibatis.mapping.ParameterMapping;
import org.apache.ibatis.mapping.SqlSource;
import org.apache.ibatis.parsing.GenericTokenParser;
import org.apache.ibatis.parsing.TokenHandler;
import org.apache.ibatis.reflection.MetaClass;
import org.apache.ibatis.reflection.MetaObject;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.type.JdbcType;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * SQL源码构建器
 * 在经过SqlNode.apply()方法的解析之后,SQL语句会被传递到SqlSourceBuilder中进行进一步解析.
 * SqlSourceBuilder主要完成两方面操作:
 *  1. 解析SQL语句中定义的属性, 格式类似于 #{__frc_item_0,javaType=int,jdbcType=NUMERIC,typeHandler=MyTypeHandler}
 *  2. 将SQL语句中的"#{}"占位符替换成"?"占位符
 *
 * @author Clinton Begin
 */
public class SqlSourceBuilder extends BaseBuilder {

    private static final String parameterProperties = "javaType,jdbcType,mode,numericScale,resultMap,typeHandler,jdbcTypeName";

    public SqlSourceBuilder(Configuration configuration) {
        super(configuration);
    }

    /**
     * @param originalSql 经过SqlNode.apply()方法处理之后的SQL语句
     * @param parameterType 用户传入的实参类型
     * @param additionalParameters 形参与实参的对应关系, 其实就是经过SqlNode.apply()方法处理后的DynamicContext.binding集合
     * @return
     */
    public SqlSource parse(String originalSql, Class<?> parameterType, Map<String, Object> additionalParameters) {
        // 创建ParamterMappingTokenHandler对象, 它是解析"#{}"占位符中参数属性以及替换占位符的核心
        ParameterMappingTokenHandler handler = new ParameterMappingTokenHandler(configuration, parameterType, additionalParameters);
        // 使用GenericTokenParser与ParamterMappingTokenHandler配合解析
        GenericTokenParser parser = new GenericTokenParser("#{", "}", handler);
        String sql = parser.parse(originalSql);
        //返回静态SQL源码
        return new StaticSqlSource(configuration, sql, handler.getParameterMappings());
    }

    //参数映射记号处理器，静态内部类
    private static class ParameterMappingTokenHandler extends BaseBuilder implements TokenHandler {

        // 用于记录解析得到的 ParameterMapping 集合
        private List<ParameterMapping> parameterMappings = new ArrayList<ParameterMapping>();
        // 参数类型
        private Class<?> parameterType;
        // 集合对应的MetaObject对象
        private MetaObject metaParameters;

        public ParameterMappingTokenHandler(Configuration configuration, Class<?> parameterType, Map<String, Object> additionalParameters) {
            super(configuration);
            this.parameterType = parameterType;
            this.metaParameters = configuration.newMetaObject(additionalParameters);
        }

        public List<ParameterMapping> getParameterMappings() {
            return parameterMappings;
        }

        @Override
        public String handleToken(String content) {
            // 将解析得到的ParameterMapper对象添加到 parameterMappings 集合中
            parameterMappings.add(buildParameterMapping(content));
            //如何替换很简单，永远是一个问号，但是参数的信息要记录在parameterMappings里面供后续使用
            // 返回问号占位符
            return "?";
        }

        // 解析参数属性
        private ParameterMapping buildParameterMapping(String content) {
            // 解析参数的属性, 并形成Map. 例如#{__frc_item_0, javaType=int, jdbcType=NUMERIC,
            // typeHandler=MyTypeHandler} 这个占位符,它就会被解析成如下Map:
            // {"property"->"__frch_item_0","javaType"->"int","jdbcType"->"NUMBERIC",
            // "typeHandler"->"MyTypeHandler"}
            Map<String, String> propertiesMap = parseParameterMapping(content);
            // 获取参数的名称
            String property = propertiesMap.get("property");
            Class<?> propertyType;
            //这里分支比较多，需要逐个理解
            // 确定参数的javaType属性
            if (metaParameters.hasGetter(property)) { // issue #448 get type from additional params
                propertyType = metaParameters.getGetterType(property);
            } else if (typeHandlerRegistry.hasTypeHandler(parameterType)) {
                propertyType = parameterType;
            } else if (JdbcType.CURSOR.name().equals(propertiesMap.get("jdbcType"))) {
                propertyType = java.sql.ResultSet.class;
            } else if (property != null) {
                MetaClass metaClass = MetaClass.forClass(parameterType);
                if (metaClass.hasGetter(property)) {
                    propertyType = metaClass.getGetterType(property);
                } else {
                    propertyType = Object.class;
                }
            } else {
                propertyType = Object.class;
            }
            // 创建 ParameterMapping的建造者, 并设置ParameterMapping相关配置
            ParameterMapping.Builder builder = new ParameterMapping.Builder(configuration, property, propertyType);
            Class<?> javaType = propertyType;
            String typeHandlerAlias = null;
            // {"property"->"__frch_item_0","javaType"->"int","jdbcType"->"NUMBERIC",
            // "typeHandler"->"MyTypeHandler"}
            for (Map.Entry<String, String> entry : propertiesMap.entrySet()) {
                String name = entry.getKey();
                String value = entry.getValue();
                if ("javaType".equals(name)) {
                    javaType = resolveClass(value);
                    builder.javaType(javaType);
                } else if ("jdbcType".equals(name)) {
                    builder.jdbcType(resolveJdbcType(value));
                } else if ("mode".equals(name)) {
                    builder.mode(resolveParameterMode(value));
                } else if ("numericScale".equals(name)) {
                    builder.numericScale(Integer.valueOf(value));
                } else if ("resultMap".equals(name)) {
                    builder.resultMapId(value);
                } else if ("typeHandler".equals(name)) {
                    typeHandlerAlias = value;
                } else if ("jdbcTypeName".equals(name)) {
                    builder.jdbcTypeName(value);
                } else if ("property".equals(name)) {
                    // Do Nothing
                } else if ("expression".equals(name)) {
                    throw new BuilderException("Expression based parameters are not supported yet");
                } else {
                    throw new BuilderException("An invalid property '" + name + "' was found in mapping #{" + content + "}.  Valid properties are " + parameterProperties);
                }
            }
            //#{age,javaType=int,jdbcType=NUMERIC,typeHandler=MyTypeHandler}
            // 获取TypeHandler对象
            if (typeHandlerAlias != null) {
                builder.typeHandler(resolveTypeHandler(javaType, typeHandlerAlias));
            }
            // 创建ParameterMapping对象, 注意, 如果没有指定TypeHandler,则会在这里的build()方法中,
            // 根据javaType和jdbcType从TypeHandlerRegistry中获取对应的TypeHandler对象
            return builder.build();
        }

        private Map<String, String> parseParameterMapping(String content) {
            try {
                return new ParameterExpression(content);
            } catch (BuilderException ex) {
                throw ex;
            } catch (Exception ex) {
                throw new BuilderException("Parsing error was found in mapping #{" + content + "}.  Check syntax #{property|(expression), var1=value1, var2=value2, ...} ", ex);
            }
        }
    }

}
