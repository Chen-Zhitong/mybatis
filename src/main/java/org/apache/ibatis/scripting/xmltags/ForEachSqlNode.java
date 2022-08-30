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

import org.apache.ibatis.parsing.GenericTokenParser;
import org.apache.ibatis.parsing.TokenHandler;
import org.apache.ibatis.session.Configuration;

import java.util.Map;

/**
 * @author Clinton Begin
 */

/**
 * foreach SQL节点
 * TODO
 */
public class ForEachSqlNode implements SqlNode {
    public static final String ITEM_PREFIX = "__frch_";

    // 用于判断循环的终止条件, ForeachSqlNode 构造方法中会创建该对象
    private ExpressionEvaluator evaluator;

    // 迭代的集合表达式
    private String collectionExpression;

    // 记录了该ForeachSqlNode节点的子节点
    private SqlNode contents;

    // 在循环开始前要添加的字符串
    private String open;

    // 在循环结束后要添加的字符串
    private String close;

    // 循环过程中,每项之间的分隔符
    private String separator;

    // index是当前迭代的次数, item的值是本次迭代的元素.
    // 若迭代集合是map, 则index是键, item是值
    private String item;
    private String index;

    // 配置对象
    private Configuration configuration;

    public ForEachSqlNode(Configuration configuration, SqlNode contents, String collectionExpression, String index, String item, String open, String close, String separator) {
        this.evaluator = new ExpressionEvaluator();
        this.collectionExpression = collectionExpression;
        this.contents = contents;
        this.open = open;
        this.close = close;
        this.separator = separator;
        this.index = index;
        this.item = item;
        this.configuration = configuration;
    }

    private static String itemizeItem(String item, int i) {
        // 添加"__frch_"前缀和 i 后缀
        return new StringBuilder(ITEM_PREFIX).append(item).append("_").append(i).toString();
    }

    /**
     * 步骤如下:
     * 1. 解析集合表达式, 获取对应的实际参数
     * 2. 在循环开始之前,添加open字段指定的字符串: applyOpen() 方法
     * 3. 开始遍历集合, 根据遍历的位置和是否指定分隔符. 用PrefixedContext 封装 DynamicContext
     * 4. 调用 applyIndex() 方法将index添加到 DynamicContext.bindings集合内,供后续解析: applyIndex
     * 5. 调用 applyItem()方法 将item添加到 DynamicContext.bindings集合中, 供后续解析使用
     * 6. 转换子节点中的"#{}"占位符, 此步骤会将PrefixedContext 封装成 FilteredDynamicContext,
     *    在追加子节点转换结果时, 就会使用前面介绍的FilteredDynamicContext.apply()方法"#{}"占位符
     *    转换成#{__frch_...}的格式,返回步骤3继续循环
     * 7. 循环结束, 调用DymaicContext.appendSql()方法添加close指定的字符串
     *
     * <select id="selectDyn2" resultType="Blog">
     *      select * from Blog B where id In
     *      <foreach collection="ids" index="idx" item="itm" open="(" separator="," close=")">
     *          #{itm}
     *      </foreach>
     * </select>
     *
     * @param context
     * @return
     */
    @Override
    public boolean apply(DynamicContext context) {
        // 获取参数信息
        Map<String, Object> bindings = context.getBindings();
        //解析collectionExpression->iterable,核心用的ognl
        final Iterable<?> iterable = evaluator.evaluateIterable(collectionExpression, bindings);
        if (!iterable.iterator().hasNext()) {
            return true;
        }
        boolean first = true;
        // 步骤2. 在循环开始之前,调用DynamicContext.appendSql()方法添加open指定的字符串
        applyOpen(context);
        int i = 0;
        for (Object o : iterable) {
            // 记录当前DynamicContext对象
            DynamicContext oldContext = context;
            //  步骤3: 创建PrefixedContext,并让context指向该PrefixedContext对象
            if (first) {
                // 如果是集合的第一项, 则将PrefixedContext.prefix 初始化为空字符串
                context = new PrefixedContext(context, "");
            } else if (separator != null) {
                // 如果指定了分隔符, 则PrefixedContext.prefix初始化为指定分割符
                context = new PrefixedContext(context, separator);
            } else {
                // 未指定分隔符, 则 PrefixedContext.prefix 初始化为空字符串
                context = new PrefixedContext(context, "");
            }
            // uniqueNumber 从 0 开始, 每次递增 1, 用于转换生成新的 "#{}" 占位符名称
            int uniqueNumber = context.getUniqueNumber();
            // Issue #709
            if (o instanceof Map.Entry) {
                @SuppressWarnings("unchecked")
                // 如果集合是Map类型,将集合中的key和value添加到 DynamicContext.bindings 集合中保存
                Map.Entry<Object, Object> mapEntry = (Map.Entry<Object, Object>) o;
                // 步骤4
                applyIndex(context, mapEntry.getKey(), uniqueNumber);
                // 步骤5
                applyItem(context, mapEntry.getValue(), uniqueNumber);
            } else {
                // 将集合中的索引和元素 添加到 DynamicContext.bindings 集合中保存
                // 步骤4
                applyIndex(context, i, uniqueNumber);
                // 步骤5
                applyItem(context, o, uniqueNumber);
            }
            // 步骤6: 调用子节点的apply()方法进行处理, 注意这里使用的FilteredDynamicContext 对象
            contents.apply(new FilteredDynamicContext(configuration, context, index, item, uniqueNumber));
            if (first) {
                first = !((PrefixedContext) context).isPrefixApplied();
            }
            // 还原成原来的context
            context = oldContext;
            i++;
        }
        // 步骤7
        applyClose(context);
        return true;
    }

    /**
     * @param context
     * @param o
     * @param i 由DynamicContext产生, 且在每个 DynamicContext 对象的生命周期中是单调递增的
     */
    private void applyIndex(DynamicContext context, Object o, int i) {
        if (index != null) {
            // key为index, value 是集合元素
            context.bind(index, o);
            // 为 index 添加前缀和后缀形成新的 key
            context.bind(itemizeItem(index, i), o);
        }
    }

    private void applyItem(DynamicContext context, Object o, int i) {
        if (item != null) {
            // key为item, value 是集合元素
            context.bind(item, o);
            // 为 item 添加前缀和后缀形成新的 key
            context.bind(itemizeItem(item, i), o);
        }
    }

    private void applyOpen(DynamicContext context) {
        if (open != null) {
            context.appendSql(open);
        }
    }

    private void applyClose(DynamicContext context) {
        if (close != null) {
            context.appendSql(close);
        }
    }

    /**
     * 被过滤的动态上下文
     * 负责处理"#{}"占位符, 但它并未完全解析#{}占位符
     */
    private static class FilteredDynamicContext extends DynamicContext {
        // DynamicContext对象
        private DynamicContext delegate;
        // 对应集合项在集合中的索引位置
        private int index;
        // 对应集合项的index
        private String itemIndex;
        // 对应集合项的 item
        private String item;

        public FilteredDynamicContext(Configuration configuration, DynamicContext delegate, String itemIndex, String item, int i) {
            super(configuration, null);
            this.delegate = delegate;
            this.index = i;
            this.itemIndex = itemIndex;
            this.item = item;
        }

        @Override
        public Map<String, Object> getBindings() {
            return delegate.getBindings();
        }

        @Override
        public void bind(String name, Object value) {
            delegate.bind(name, value);
        }

        @Override
        public String getSql() {
            return delegate.getSql();
        }

        /**
         * 此方法会将"#{item}"占位符转换成"#{__frch_item_1}"的格式,其中"__frch_"是固定的前缀,
         * "item"与处理前的占位符一样,未发生改变,1则是FilteredDynamicContext产生的单调递增值
         * 还会将#{itemIndex}占位符转换成"#{__frch_itemIndex_1}"的格式
         *
         * @param sql
         */
        @Override
        public void appendSql(String sql) {
            // 创建GenericTokenParser解析器,注意这里匿名实现的TokenHandler对象
            GenericTokenParser parser = new GenericTokenParser("#{", "}", new TokenHandler() {
                @Override
                public String handleToken(String content) {
                    // 对item进行处理
                    String newContent = content.replaceFirst("^\\s*" + item + "(?![^.,:\\s])", itemizeItem(item, index));
                    if (itemIndex != null && newContent.equals(content)) {
                        // 对 itemIndex 进行处理
                        newContent = content.replaceFirst("^\\s*" + itemIndex + "(?![^.,:\\s])", itemizeItem(itemIndex, index));
                    }
                    return new StringBuilder("#{").append(newContent).append("}").toString();
                }
            });
            // 将解析后的SQL语句片段追加到 delegate 中保存
            delegate.appendSql(parser.parse(sql));
        }

        @Override
        public int getUniqueNumber() {
            return delegate.getUniqueNumber();
        }

    }


    //前缀上下文
    private class PrefixedContext extends DynamicContext {
        //  底层封装的DynamicContext对象
        private DynamicContext delegate;
        // 指定的前缀
        private String prefix;
        // 是否已经处理过前缀
        private boolean prefixApplied;

        public PrefixedContext(DynamicContext delegate, String prefix) {
            super(configuration, null);
            this.delegate = delegate;
            this.prefix = prefix;
            this.prefixApplied = false;
        }

        public boolean isPrefixApplied() {
            return prefixApplied;
        }

        @Override
        public Map<String, Object> getBindings() {
            return delegate.getBindings();
        }

        @Override
        public void bind(String name, Object value) {
            delegate.bind(name, value);
        }

        @Override
        public void appendSql(String sql) {
            // 判断是否需要增加前缀
            if (!prefixApplied && sql != null && sql.trim().length() > 0) {
                // 追加前缀
                delegate.appendSql(prefix);
                // 表示已经处理过前缀
                prefixApplied = true;
            }
            // 追加sql片段
            delegate.appendSql(sql);
        }

        @Override
        public String getSql() {
            return delegate.getSql();
        }

        @Override
        public int getUniqueNumber() {
            return delegate.getUniqueNumber();
        }
    }

}
