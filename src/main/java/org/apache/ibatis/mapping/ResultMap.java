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
package org.apache.ibatis.mapping;

import org.apache.ibatis.session.Configuration;

import java.util.*;

/**
 * 每个<resultMap>都会被解析成一个ResultMap对象, 其中每个节点所定义的映射关系,
 * 则使用ResultMapping对象表示
 *
 * @author Clinton Begin
 */

/**
 * 结果映射
 * MyBatis 中最重要最强大的元素
 */
public class ResultMap {
    // <resultMap> 节点的id属性
    private String id;
    // <resultMap>的type属性
    private Class<?> type;
    // 记录了除<discriminator>节点之外的其他映射关系(即ResultMapping对象集合)
    private List<ResultMapping> resultMappings;
    // 记录了映射关系中带有ID标志的映射关系, 例如<id>节点和<constructor>节点的<idArg>子节点
    private List<ResultMapping> idResultMappings;
    // 记录了映射关系中带有Constructor标志的映射关系, 例如<constructor>所有子元素
    private List<ResultMapping> constructorResultMappings;
    // 记录了映射关系中不带有Constructor标志的映射关系
    private List<ResultMapping> propertyResultMappings;
    // 记录所有映射关系中设计column属性的集合
    private Set<String> mappedColumns;
    // 鉴别器,对应<discriminator>节点
    private Discriminator discriminator;
    // 是否含有嵌套的结果映射,如果某个映射关系中存在resultMap属性,且不存在resultSet属性,则为true
    private boolean hasNestedResultMaps;
    // 是否含有嵌套查询, 如果某个属性映射存在select属性,则为true
    private boolean hasNestedQueries;
    // 是否开启自动映射
    private Boolean autoMapping;

    private ResultMap() {
    }

    public String getId() {
        return id;
    }

    public boolean hasNestedResultMaps() {
        return hasNestedResultMaps;
    }

    public boolean hasNestedQueries() {
        return hasNestedQueries;
    }

    public Class<?> getType() {
        return type;
    }

    public List<ResultMapping> getResultMappings() {
        return resultMappings;
    }

    public List<ResultMapping> getConstructorResultMappings() {
        return constructorResultMappings;
    }

    public List<ResultMapping> getPropertyResultMappings() {
        return propertyResultMappings;
    }

    public List<ResultMapping> getIdResultMappings() {
        return idResultMappings;
    }

    public Set<String> getMappedColumns() {
        return mappedColumns;
    }

    public Discriminator getDiscriminator() {
        return discriminator;
    }

    public void forceNestedResultMaps() {
        hasNestedResultMaps = true;
    }

    public Boolean getAutoMapping() {
        return autoMapping;
    }

    //静态内部类，建造者模式
    public static class Builder {
        private ResultMap resultMap = new ResultMap();

        public Builder(Configuration configuration, String id, Class<?> type, List<ResultMapping> resultMappings) {
            this(configuration, id, type, resultMappings, null);
        }

        public Builder(Configuration configuration, String id, Class<?> type, List<ResultMapping> resultMappings, Boolean autoMapping) {
            resultMap.id = id;
            resultMap.type = type;
            resultMap.resultMappings = resultMappings;
            resultMap.autoMapping = autoMapping;
        }

        public Builder discriminator(Discriminator discriminator) {
            resultMap.discriminator = discriminator;
            return this;
        }

        public Class<?> type() {
            return resultMap.type;
        }

        public ResultMap build() {
            if (resultMap.id == null) {
                throw new IllegalArgumentException("ResultMaps must have an id");
            }
            resultMap.mappedColumns = new HashSet<String>();
            resultMap.idResultMappings = new ArrayList<ResultMapping>();
            resultMap.constructorResultMappings = new ArrayList<ResultMapping>();
            resultMap.propertyResultMappings = new ArrayList<ResultMapping>();
            for (ResultMapping resultMapping : resultMap.resultMappings) {
                resultMap.hasNestedQueries = resultMap.hasNestedQueries || resultMapping.getNestedQueryId() != null;
                resultMap.hasNestedResultMaps = resultMap.hasNestedResultMaps || (resultMapping.getNestedResultMapId() != null && resultMapping.getResultSet() == null);
                final String column = resultMapping.getColumn();
                if (column != null) {
                    resultMap.mappedColumns.add(column.toUpperCase(Locale.ENGLISH));
                } else if (resultMapping.isCompositeResult()) {
                    for (ResultMapping compositeResultMapping : resultMapping.getComposites()) {
                        final String compositeColumn = compositeResultMapping.getColumn();
                        if (compositeColumn != null) {
                            resultMap.mappedColumns.add(compositeColumn.toUpperCase(Locale.ENGLISH));
                        }
                    }
                }
                if (resultMapping.getFlags().contains(ResultFlag.CONSTRUCTOR)) {
                    resultMap.constructorResultMappings.add(resultMapping);
                } else {
                    resultMap.propertyResultMappings.add(resultMapping);
                }
                if (resultMapping.getFlags().contains(ResultFlag.ID)) {
                    resultMap.idResultMappings.add(resultMapping);
                }
            }
            if (resultMap.idResultMappings.isEmpty()) {
                resultMap.idResultMappings.addAll(resultMap.resultMappings);
            }
            // lock down collections
            resultMap.resultMappings = Collections.unmodifiableList(resultMap.resultMappings);
            resultMap.idResultMappings = Collections.unmodifiableList(resultMap.idResultMappings);
            resultMap.constructorResultMappings = Collections.unmodifiableList(resultMap.constructorResultMappings);
            resultMap.propertyResultMappings = Collections.unmodifiableList(resultMap.propertyResultMappings);
            resultMap.mappedColumns = Collections.unmodifiableSet(resultMap.mappedColumns);
            return resultMap;
        }
    }

}
