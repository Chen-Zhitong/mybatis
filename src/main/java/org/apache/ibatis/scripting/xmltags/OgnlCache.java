/*
 *    Copyright 2009-2014 the original author or authors.
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

import ognl.Ognl;
import ognl.OgnlException;
import org.apache.ibatis.builder.BuilderException;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Caches OGNL parsed expressions.
 *
 * @author Eduardo Macarron
 * @see http://code.google.com/p/mybatis/issues/detail?id=342
 * OGNL缓存,根据以上链接，大致是说ognl有性能问题，所以加了一个缓存
 */
public final class OgnlCache {

    private static final Map<String, Object> expressionCache = new ConcurrentHashMap<String, Object>();

    private OgnlCache() {
        // Prevent Instantiation of Static Class
    }

    public static Object getValue(String expression, Object root) {
        try {
            // 创建 OgnlContext 对象, OgnlClassResolver 替代了 OGNL 中原有的DefaultClassResolver,
            // 其主要功能就是前面介绍的Resource工具类定位资源
            Map<Object, OgnlClassResolver> context = Ognl.createDefaultContext(root, new OgnlClassResolver());
            // 使用OGNL执行expression表达式
            return Ognl.getValue(parseExpression(expression), context, root);
        } catch (OgnlException e) {
            throw new BuilderException("Error evaluating expression '" + expression + "'. Cause: " + e, e);
        }
    }

    private static Object parseExpression(String expression) throws OgnlException {
        // 查找缓存
        Object node = expressionCache.get(expression);
        if (node == null) {
            //大致意思就是OgnlParser.topLevelExpression很慢，所以加个缓存，放到ConcurrentHashMap里面
            // 解析表达式
            node = Ognl.parseExpression(expression);
            // 将表达式的解析结果添加到缓存中
            expressionCache.put(expression, node);
        }
        return node;
    }

}
