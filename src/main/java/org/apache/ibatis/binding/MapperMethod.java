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
package org.apache.ibatis.binding;

import org.apache.ibatis.annotations.MapKey;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.mapping.SqlCommandType;
import org.apache.ibatis.reflection.MetaObject;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.ResultHandler;
import org.apache.ibatis.session.RowBounds;
import org.apache.ibatis.session.SqlSession;

import java.lang.reflect.Array;
import java.lang.reflect.Method;
import java.util.*;

/**
 * @author Clinton Begin
 * @author Eduardo Macarron
 * @author Lasse Voss
 */

/**
 * MapperMethod中封装了Mapper接口中对应方法的信息, 以及SQL语句的信息.
 * 可以将MapperMethod看做连接Mapper接口以及映射配置文件中定义的SQL语句的桥梁.
 */
public class MapperMethod {

    // 记录了SQL 语句的名称和类型
    private final SqlCommand command;
    // Mapper接口中对应方法的相关信息
    private final MethodSignature method;

    public MapperMethod(Class<?> mapperInterface, Method method, Configuration config) {
        this.command = new SqlCommand(config, mapperInterface, method);
        this.method = new MethodSignature(config, method);
    }

    /**
     * 会根据SQL语句的类型调用SqlSession对应的方法完成数据库操作.
     *
     * @param sqlSession 是MyBatis核心组件之一 , 负责完成数据库操作
     * @param args
     * @return
     */
    public Object execute(SqlSession sqlSession, Object[] args) {
        Object result;
        //可以看到执行时就是4种情况，insert|update|delete|select，分别调用SqlSession的4大类方法
        if (SqlCommandType.INSERT == command.getType()) {
            // 使用 ParamNameResolver 处理 args[] 数组( 用户传入的实参列表)
            // 将用户传入的实参与指定参数名称关联起来
            Object param = method.convertArgsToSqlCommandParam(args);
            // 调用SqlSession.insert()方法, rowCountResult方法会根据method字段中记录的方法
            // 的返回值类型对结果进行转换
            result = rowCountResult(sqlSession.insert(command.getName(), param));
        } else if (SqlCommandType.UPDATE == command.getType()) {
            Object param = method.convertArgsToSqlCommandParam(args);
            result = rowCountResult(sqlSession.update(command.getName(), param));
        } else if (SqlCommandType.DELETE == command.getType()) {
            Object param = method.convertArgsToSqlCommandParam(args);
            result = rowCountResult(sqlSession.delete(command.getName(), param));
        } else if (SqlCommandType.SELECT == command.getType()) {
            if (method.returnsVoid() && method.hasResultHandler()) {
                // 处理返回值为void且ResultHandler处理的方法
                executeWithResultHandler(sqlSession, args);
                result = null;
            } else if (method.returnsMany()) {
                //处理返回值为集合或数组的方法
                result = executeForMany(sqlSession, args);
            } else if (method.returnsMap()) {
                //如果结果是map
                result = executeForMap(sqlSession, args);
            } else {
                //处理返回值为单一对象的方法
                Object param = method.convertArgsToSqlCommandParam(args);
                result = sqlSession.selectOne(command.getName(), param);
            }
        } else {
            throw new BindingException("Unknown execution method for: " + command.getName());
        }
        if (result == null && method.getReturnType().isPrimitive() && !method.returnsVoid()) {
            throw new BindingException("Mapper method '" + command.getName()
                    + " attempted to return null from a method with a primitive return type (" + method.getReturnType() + ").");
        }
        return result;
    }

    /**
     * 当执行INSERT, UPDATE, DELETE类型的SQL语句时其执行结果都需要经过此方法处理
     * inser返回值为int值 rowCountResult方法会将该int值转换成Mapper接口中对应方法的返回值
     * @param rowCount
     * @return
     */
    private Object rowCountResult(int rowCount) {
        final Object result;
        if (method.returnsVoid()) {
            // Mapper接口中相应方法的返回值为void
            result = null;
        } else if (Integer.class.equals(method.getReturnType()) || Integer.TYPE.equals(method.getReturnType())) {
            //如果返回值是int或 Integer
            result = Integer.valueOf(rowCount);
        } else if (Long.class.equals(method.getReturnType()) || Long.TYPE.equals(method.getReturnType())) {
            //如果返回值是long 或 Long
            result = Long.valueOf(rowCount);
        } else if (Boolean.class.equals(method.getReturnType()) || Boolean.TYPE.equals(method.getReturnType())) {
            //如果返回值是boolean或Boolean
            result = Boolean.valueOf(rowCount > 0);
        } else {
            // 如果以上都不成立,则抛出异常
            throw new BindingException("Mapper method '" + command.getName() + "' has an unsupported return type: " + method.getReturnType());
        }
        return result;
    }

    /**
     * 如果Mapper接口中定义的方法准备使用ResultHandler处理查询结果集,
     * 则通过 MapperMethod.executeWithResultHandler() 方法处理
     *
     * @param sqlSession
     * @param args
     */
    private void executeWithResultHandler(SqlSession sqlSession, Object[] args) {
        // 获取SQL语句对应的MappedStatement对象, MappedStatement中记录了SQL语句相关信息
        MappedStatement ms = sqlSession.getConfiguration().getMappedStatement(command.getName());
        // 当使用ResultHandler处理结果集时, 必须指定ResultMap或ResultType
        if (void.class.equals(ms.getResultMaps().get(0).getType())) {
            throw new BindingException("method " + command.getName()
                    + " needs either a @ResultMap annotation, a @ResultType annotation,"
                    + " or a resultType attribute in XML so a ResultHandler can be used as a parameter.");
        }
        // 转换实参列表
        Object param = method.convertArgsToSqlCommandParam(args);
        // 检测参数列表中是否有RowBounds类型的参数
        if (method.hasRowBounds()) {
            // 获取RowBounds对象,根据MethodSignature.rowBoundsIndex字段指定位置, 从args数组中查找
            RowBounds rowBounds = method.extractRowBounds(args);
            // 调用SqlSession.select()方法, 执行查询,并由指定的ResultHandler处理结果对象
            sqlSession.select(command.getName(), param, rowBounds, method.extractResultHandler(args));
        } else {
            sqlSession.select(command.getName(), param, method.extractResultHandler(args));
        }
    }


    /**
     *  如果结果是Collection或是数组,则使用此方法解析
     *
     * @param sqlSession
     * @param args
     * @param <E>
     * @return
     */
    private <E> Object executeForMany(SqlSession sqlSession, Object[] args) {
        List<E> result;
        //参数列表转换
        Object param = method.convertArgsToSqlCommandParam(args);
        // 检测是否制定了 RowBounds 参数
        if (method.hasRowBounds()) {
            RowBounds rowBounds = method.extractRowBounds(args);
            // 调用SqlSession.selectList()方法完成查询
            result = sqlSession.<E>selectList(command.getName(), param, rowBounds);
        } else {
            result = sqlSession.<E>selectList(command.getName(), param);
        }
        // issue #510 Collections & arrays support
        // 将结果转化为数组或Collection集合
        if (!method.getReturnType().isAssignableFrom(result.getClass())) {
            if (method.getReturnType().isArray()) {
                return convertToArray(result);
            } else {
                return convertToDeclaredCollection(sqlSession.getConfiguration(), result);
            }
        }
        return result;
    }

    private <E> Object convertToDeclaredCollection(Configuration config, List<E> list) {
        //  使用前面的ObjectFactory,通过反射创建集合对象
        Object collection = config.getObjectFactory().create(method.getReturnType());
        MetaObject metaObject = config.newMetaObject(collection);
        // 实际上就是调用Collection.addAll()方法
        metaObject.addAll(list);
        return collection;
    }

    @SuppressWarnings("unchecked")
    private <E> E[] convertToArray(List<E> list) {
        E[] array = (E[]) Array.newInstance(method.getReturnType().getComponentType(), list.size());
        array = list.toArray(array);
        return array;
    }

    /**
     * 如果返回值是Map,则通过此方法处理
     *
     * @param sqlSession
     * @param args
     * @param <K>
     * @param <V>
     * @return
     */
    private <K, V> Map<K, V> executeForMap(SqlSession sqlSession, Object[] args) {
        Map<K, V> result;
        // 转换实参列表
        Object param = method.convertArgsToSqlCommandParam(args);
        if (method.hasRowBounds()) {
            RowBounds rowBounds = method.extractRowBounds(args);
            // 调用SqlSession.selectMap方法完成查询查询
            result = sqlSession.<K, V>selectMap(command.getName(), param, method.getMapKey(), rowBounds);
        } else {
            result = sqlSession.<K, V>selectMap(command.getName(), param, method.getMapKey());
        }
        return result;
    }

    //参数map，静态内部类,更严格的get方法，如果没有相应的key，报错
    public static class ParamMap<V> extends HashMap<String, V> {

        private static final long serialVersionUID = -2212268410512043556L;

        @Override
        public V get(Object key) {
            if (!super.containsKey(key)) {
                throw new BindingException("Parameter '" + key + "' not found. Available parameters are " + keySet());
            }
            return super.get(key);
        }

    }

    //SQL命令，静态内部类
    // 它使用name字段记录了SQL语句的名称, 使用type字段(sqlCommandType类型)记录了SQL语句的类型
    // sqlCommandType是枚举类型 有效值为 UNKNOWN, INSERT, UPDATE, DELETE, SELECT
    public static class SqlCommand {

        private final String name;
        private final SqlCommandType type;

        /**
         * 会初始化 name字段 和 type字段
         *
         * @param configuration
         * @param mapperInterface
         * @param method
         */
        public SqlCommand(Configuration configuration, Class<?> mapperInterface, Method method) {
            // SQL语句的名称是由Mapper接口的名称与对应的方法名称组成的
            String statementName = mapperInterface.getName() + "." + method.getName();
            MappedStatement ms = null;
            // 检测是否有该名称的SQL语句
            if (configuration.hasStatement(statementName)) {
                // 从Configuration.mappedStatement集合中查找对应的 MappedStatement对象
                // MappedStatement 对象中封装了SQL语句相关信息,在MyBatis初始化时创建
                ms = configuration.getMappedStatement(statementName);
            } else if (!mapperInterface.equals(method.getDeclaringClass().getName())) { // issue #35
                // 如果指定方法是在父接口中定义的, 则在此进行继承结构的处理
                String parentStatementName = method.getDeclaringClass().getName() + "." + method.getName();
                // 从Configuration.mappedStatement集合中查找对应的 MappedStatement对象
                if (configuration.hasStatement(parentStatementName)) {
                    ms = configuration.getMappedStatement(parentStatementName);
                }
            }
            if (ms == null) {
                throw new BindingException("Invalid bound statement (not found): " + statementName);
            }
            // 初始化name和type
            name = ms.getId();
            type = ms.getSqlCommandType();
            if (type == SqlCommandType.UNKNOWN) {
                throw new BindingException("Unknown execution method for: " + name);
            }
        }

        public String getName() {
            return name;
        }

        public SqlCommandType getType() {
            return type;
        }
    }

    //方法签名，静态内部类
    public static class MethodSignature {

        //  返回值类型是否为Collection类型或数组类型
        private final boolean returnsMany;
        // 返回值类型是否为Map类型
        private final boolean returnsMap;
        // 返回值类型是否为void
        private final boolean returnsVoid;
        //  返回值类型
        private final Class<?> returnType;
        // 如果返回值类型为Map,则该字段记录了作为key的列名
        private final String mapKey;
        // 用来标记该方法参数列表中ResultHandler类型参数的位置
        private final Integer resultHandlerIndex;
        // 用来标记该方法参数列表中 RowBounds 类型参数的位置
        private final Integer rowBoundsIndex;
        //  记录参数在参数列表中的位置索引与参数名称之间的对应关系
        // value表示参数名称, 参数名称可以通过@Param注解指定,如果没有指定则使用参数索引作为其名称
        private final SortedMap<Integer, String> params;
        // 记录对应方法的参数列表是否使用了 @Param 注解
        private final boolean hasNamedParameters;

        public MethodSignature(Configuration configuration, Method method) {
            this.returnType = method.getReturnType();
            this.returnsVoid = void.class.equals(this.returnType);
            this.returnsMany = (configuration.getObjectFactory().isCollection(this.returnType) || this.returnType.isArray());
            //  若methodSignature对应方法的返回值是Map且指定了@MapKey注解
            // 则使用getMapkey方法处理
            this.mapKey = getMapKey(method);
            this.returnsMap = (this.mapKey != null);
            this.hasNamedParameters = hasNamedParams(method);
            //以下重复循环2遍调用getUniqueParamIndex，是不是降低效率了
            //记下RowBounds是第几个参数
            this.rowBoundsIndex = getUniqueParamIndex(method, RowBounds.class);
            //记下ResultHandler是第几个参数
            this.resultHandlerIndex = getUniqueParamIndex(method, ResultHandler.class);
            this.params = Collections.unmodifiableSortedMap(getParams(method, this.hasNamedParameters));
        }

        /**
         * 负责将 args[] 数组(用户传入的实参列表)转换成SQL语句对应的参数列表
         *
         * @param args
         * @return
         */
        public Object convertArgsToSqlCommandParam(Object[] args) {
            final int paramCount = params.size();
            if (args == null || paramCount == 0) {
                //如果没参数返回null
                return null;
            } else if (!hasNamedParameters && paramCount == 1) {
                // 未使用@Param 且 只有一个参数
                return args[params.keySet().iterator().next().intValue()];
            } else {
                // 处理使用@Param注解指定了参数名称或有多个参数的情况
                // param这个这个Map中记录了参数名称与实参之间的对应关系.
                // ParamMap 继承了 HashMap, 如果从ParamMap中获取不存在的key,会报错, 其他行为与HashMap相同
                final Map<String, Object> param = new ParamMap<Object>();
                int i = 0;
                for (Map.Entry<Integer, String> entry : params.entrySet()) {
                    // 将参数名与实参对应关系记录到 param 中
                    param.put(entry.getValue(), args[entry.getKey().intValue()]);
                    // issue #71, add param names as param1, param2...but ensure backward compatibility
                    // 下面是为参数创建"param+索引"格式的默认参数名称, 例如: param1, param2 等,并添加到param集合中
                    final String genericParamName = "param" + String.valueOf(i + 1);
                    if (!param.containsKey(genericParamName)) {
                        //2.再加一个#{param1},#{param2}...参数
                        //你可以传递多个参数给一个映射器方法。如果你这样做了,
                        //默认情况下它们将会以它们在参数列表中的位置来命名,比如:#{param1},#{param2}等。
                        //如果你想改变参数的名称(只在多参数情况下) ,那么你可以在参数上使用@Param(“paramName”)注解。
                        param.put(genericParamName, args[entry.getKey()]);
                    }
                    i++;
                }
                return param;
            }
        }

        public boolean hasRowBounds() {
            return rowBoundsIndex != null;
        }

        public RowBounds extractRowBounds(Object[] args) {
            return hasRowBounds() ? (RowBounds) args[rowBoundsIndex] : null;
        }

        public boolean hasResultHandler() {
            return resultHandlerIndex != null;
        }

        public ResultHandler extractResultHandler(Object[] args) {
            return hasResultHandler() ? (ResultHandler) args[resultHandlerIndex] : null;
        }

        public String getMapKey() {
            return mapKey;
        }

        public Class<?> getReturnType() {
            return returnType;
        }

        public boolean returnsMany() {
            return returnsMany;
        }

        public boolean returnsMap() {
            return returnsMap;
        }

        public boolean returnsVoid() {
            return returnsVoid;
        }

        /**
         *  查找指定类型的参数在参数列表中的位置
         *
         * @param method
         * @param paramType
         * @return
         */
        private Integer getUniqueParamIndex(Method method, Class<?> paramType) {
            Integer index = null;
            final Class<?>[] argTypes = method.getParameterTypes();
            //  遍历 MethodSignature 对应方法的参数列表
            for (int i = 0; i < argTypes.length; i++) {
                if (paramType.isAssignableFrom(argTypes[i])) {
                    // 记录 paramType类型参数在参数列表中的位置索引
                    if (index == null) {
                        index = i;
                    } else {
                        // RowBounds 和 ResultHandler 类型的参数只能有一个,不能重复出现
                        throw new BindingException(method.getName() + " cannot have multiple " + paramType.getSimpleName() + " parameters");
                    }
                }
            }
            return index;
        }

        private String getMapKey(Method method) {
            String mapKey = null;
            if (Map.class.isAssignableFrom(method.getReturnType())) {
                //如果返回类型是map类型的，查看该method是否有MapKey注解。如果有这个注解，将这个注解的值作为map的key
                final MapKey mapKeyAnnotation = method.getAnnotation(MapKey.class);
                if (mapKeyAnnotation != null) {
                    mapKey = mapKeyAnnotation.value();
                }
            }
            return mapKey;
        }

        //得到所有参数
        private SortedMap<Integer, String> getParams(Method method, boolean hasNamedParameters) {
            //用一个TreeMap,这样就保证还是按参数的先后顺序
            final SortedMap<Integer, String> params = new TreeMap<Integer, String>();
            //  获取参数列表中每个参数的类型
            final Class<?>[] argTypes = method.getParameterTypes();
            for (int i = 0; i < argTypes.length; i++) {
                //如果是RowBounds/ResultHandler类型的参数,则掉过对该参数的分析
                if (!RowBounds.class.isAssignableFrom(argTypes[i]) && !ResultHandler.class.isAssignableFrom(argTypes[i])) {
                    //参数名字默认为0,1,2，这就是为什么xml里面可以用#{1}这样的写法来表示参数了
                    String paramName = String.valueOf(params.size());
                    if (hasNamedParameters) {
                        //还可以用注解@Param来重命名参数
                        paramName = getParamNameFromAnnotation(method, i, paramName);
                    }
                    params.put(i, paramName);
                }
            }
            return params;
        }

        private String getParamNameFromAnnotation(Method method, int i, String paramName) {
            // 获取参数的注解
            final Object[] paramAnnos = method.getParameterAnnotations()[i];
            for (Object paramAnno : paramAnnos) {
                if (paramAnno instanceof Param) {
                    // 获取@Param注解指定的参数名称
                    paramName = ((Param) paramAnno).value();
                }
            }
            return paramName;
        }

        /**
         * 查找方法中是否使用了@Param注解
         *
         * @param method
         * @return
         */
        private boolean hasNamedParams(Method method) {
            boolean hasNamedParams = false;
            final Object[][] paramAnnos = method.getParameterAnnotations();
            for (Object[] paramAnno : paramAnnos) {
                for (Object aParamAnno : paramAnno) {
                    if (aParamAnno instanceof Param) {
                        hasNamedParams = true;
                        break;
                    }
                }
            }
            return hasNamedParams;
        }

    }

}
