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
package org.apache.ibatis.scripting.xmltags;

import org.apache.ibatis.parsing.GenericTokenParser;
import org.apache.ibatis.parsing.TokenHandler;
import org.apache.ibatis.scripting.ScriptingException;
import org.apache.ibatis.type.SimpleTypeRegistry;

import java.util.regex.Pattern;

/**
 * 表示的是包含"${}"占位符的动态SQL节点
 *
 * @author Clinton Begin
 */

/**
 * 文本SQL节点（CDATA|TEXT）
 */
public class TextSqlNode implements SqlNode {
    private String text;
    private Pattern injectionFilter;

    public TextSqlNode(String text) {
        this(text, null);
    }

    public TextSqlNode(String text, Pattern injectionFilter) {
        this.text = text;
        this.injectionFilter = injectionFilter;
    }

    //判断是否是动态sql
    public boolean isDynamic() {
        DynamicCheckerTokenParser checker = new DynamicCheckerTokenParser();
        // 创建GenericTokenParser对象
        GenericTokenParser parser = createParser(checker);
        parser.parse(text);
        return checker.isDynamic();
    }

    /**
     * 会使用GenericTokenParser解析"${}"占位符,并替换成用户给定的实际参数值
     *
     * @param context
     * @return
     */
    @Override
    public boolean apply(DynamicContext context) {
        //  chua创建GenericTOkenParser解析器
        GenericTokenParser parser = createParser(new BindingTokenParser(context, injectionFilter));
        // 将解析后的SQL片段 添加到 DynamicContext 中
        context.appendSql(parser.parse(text));
        return true;
    }

    private GenericTokenParser createParser(TokenHandler handler) {
        // 解析的是"${}"占位符
        return new GenericTokenParser("${", "}", handler);
    }

    //绑定记号解析器
    //  主要功能是依据DynamicContext.bindings集合中的信息解析SQL语句节点中的"${}"占位符
    private static class BindingTokenParser implements TokenHandler {

        private DynamicContext context;
        private Pattern injectionFilter;

        public BindingTokenParser(DynamicContext context, Pattern injectionFilter) {
            this.context = context;
            this.injectionFilter = injectionFilter;
        }

        @Override
        public String handleToken(String content) {
            // 用户提供的实参
            // 假设用户传入的实参中包含了“id-＞1”的对应关系，在TextSqlNode.apply（）方法解析时，会将“id=${id}”中的“${id}”占位符
            // 直接替换成“1”得到“id=1”，并将其追加到DynamicContext中。
            Object parameter = context.getBindings().get("_parameter");
            if (parameter == null) {
                context.getBindings().put("value", null);
            } else if (SimpleTypeRegistry.isSimpleType(parameter.getClass())) {
                context.getBindings().put("value", parameter);
            }
            // 通过OGNL解析content的值
            Object value = OgnlCache.getValue(content, context.getBindings());
            String srtValue = (value == null ? "" : String.valueOf(value)); // issue #274 return "" instead of "null"
            // 检测合法性
            checkInjection(srtValue);
            return srtValue;
        }

        //检查是否匹配正则表达式
        private void checkInjection(String value) {
            if (injectionFilter != null && !injectionFilter.matcher(value).matches()) {
                throw new ScriptingException("Invalid input. Please conform to regex" + injectionFilter.pattern());
            }
        }
    }

    //动态SQL检查器
    private static class DynamicCheckerTokenParser implements TokenHandler {

        private boolean isDynamic;

        public DynamicCheckerTokenParser() {
            // Prevent Synthetic Access
        }

        public boolean isDynamic() {
            return isDynamic;
        }

        @Override
        public String handleToken(String content) {
            //灰常简单，设置isDynamic为true，即调用了这个类就必定是动态sql？？？
            this.isDynamic = true;
            return null;
        }
    }

}
