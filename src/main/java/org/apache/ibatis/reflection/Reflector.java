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

import org.apache.ibatis.reflection.invoker.GetFieldInvoker;
import org.apache.ibatis.reflection.invoker.Invoker;
import org.apache.ibatis.reflection.invoker.MethodInvoker;
import org.apache.ibatis.reflection.invoker.SetFieldInvoker;
import org.apache.ibatis.reflection.property.PropertyNamer;

import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/*
 * This class represents a cached set of class definition information that
 * allows for easy mapping between property names and getter/setter methods.
 */
/**
 * @author Clinton Begin
 */

/**
 * 反射器, 属性->getter/setter的映射器，而且加了缓存
 * 可参考ReflectorTest来理解这个类的用处
 */
public class Reflector {

    private static final String[] EMPTY_STRING_ARRAY = new String[0];
    //这里用ConcurrentHashMap，多线程支持，作为一个缓存
    private static final Map<Class<?>, Reflector> REFLECTOR_MAP = new ConcurrentHashMap<Class<?>, Reflector>();
    private static boolean classCacheEnabled = true;
    private Class<?> type;
    //getter的属性列表 可读属性集合
    private String[] readablePropertyNames = EMPTY_STRING_ARRAY;
    //setter的属性列表
    private String[] writeablePropertyNames = EMPTY_STRING_ARRAY;
    //setter的方法列表 key是属性的名称,value 是Invoker对象
    private Map<String, Invoker> setMethods = new HashMap<String, Invoker>();
    //getter的方法列表
    private Map<String, Invoker> getMethods = new HashMap<String, Invoker>();
    //setter的类型列表 记录了属性相应的setter方法的参数值类型，key是属性名称，value是setter方法的参数类型
    private Map<String, Class<?>> setTypes = new HashMap<String, Class<?>>();
    //getter的类型列表
    private Map<String, Class<?>> getTypes = new HashMap<String, Class<?>>();
    //构造函数 记录了默认构造方法
    private Constructor<?> defaultConstructor;

    // 记录了所有属性名称的集合
    private Map<String, String> caseInsensitivePropertyMap = new HashMap<String, String>();

    private Reflector(Class<?> clazz) {
        type = clazz;
        // 查找 clazz 的默认构造方法(无参构造方法),  具体实现是通过反射遍历所有的构造方法
        addDefaultConstructor(clazz);
        //处理clazz中的getter
        addGetMethods(clazz);
        //加入setter
        addSetMethods(clazz);
        //处理没有getter, setter方法的字段
        addFields(clazz);

        // 根据 getMethod/setMethod 集合, 初始化可读/写属性集合
        readablePropertyNames = getMethods.keySet().toArray(new String[getMethods.keySet().size()]);
        writeablePropertyNames = setMethods.keySet().toArray(new String[setMethods.keySet().size()]);

        // 初始化caseInsensitivePropertyMap集合, 其中记录了所有大写格式的属性名称
        for (String propName : readablePropertyNames) {
            //这里为了能找到某一个属性，就把他变成大写作为map的key。。。
            caseInsensitivePropertyMap.put(propName.toUpperCase(Locale.ENGLISH), propName);
        }
        for (String propName : writeablePropertyNames) {
            caseInsensitivePropertyMap.put(propName.toUpperCase(Locale.ENGLISH), propName);
        }
    }

    private static boolean canAccessPrivateMethods() {
        try {
            SecurityManager securityManager = System.getSecurityManager();
            if (null != securityManager) {
                securityManager.checkPermission(new ReflectPermission("suppressAccessChecks"));
            }
        } catch (SecurityException e) {
            return false;
        }
        return true;
    }

    /*
     * Gets an instance of ClassInfo for the specified class.
     * 得到某个类的反射器，是静态方法，而且要缓存，又要多线程，所以REFLECTOR_MAP是一个ConcurrentHashMap
     *
     * @param clazz The class for which to lookup the method cache.
     * @return The method cache for the class
     */
    public static Reflector forClass(Class<?> clazz) {
        if (classCacheEnabled) {
            // synchronized (clazz) removed see issue #461
            //对于每个类来说，我们假设它是不会变的，这样可以考虑将这个类的信息(构造函数，getter,setter,字段)加入缓存，以提高速度
            Reflector cached = REFLECTOR_MAP.get(clazz);
            if (cached == null) {
                cached = new Reflector(clazz);
                REFLECTOR_MAP.put(clazz, cached);
            }
            return cached;
        } else {
            return new Reflector(clazz);
        }
    }

    public static boolean isClassCacheEnabled() {
        return classCacheEnabled;
    }

    public static void setClassCacheEnabled(boolean classCacheEnabled) {
        Reflector.classCacheEnabled = classCacheEnabled;
    }

    private void addDefaultConstructor(Class<?> clazz) {
        Constructor<?>[] consts = clazz.getDeclaredConstructors();
        for (Constructor<?> constructor : consts) {
            if (constructor.getParameterTypes().length == 0) {
                if (canAccessPrivateMethods()) {
                    try {
                        constructor.setAccessible(true);
                    } catch (Exception e) {
                        // Ignored. This is only a final precaution, nothing we can do.
                    }
                }
                if (constructor.isAccessible()) {
                    this.defaultConstructor = constructor;
                }
            }
        }
    }

    private void addGetMethods(Class<?> cls) {
        Map<String, List<Method>> conflictingGetters = new HashMap<String, List<Method>>();
        //这里getter和setter都调用了getClassMethods，有点浪费效率了。不妨把addGetMethods,addSetMethods合并成一个方法叫addMethods
        // 1. 首先,调用Reflector.getClassMethods() 方法获取当前类以及其父类中定义的所有方法的唯一签名以及相应的Method对象.
        Method[] methods = getClassMethods(cls);

        // 2. 按照JavaBean的规范, 从Reflector.getClassMethods() 方法返回的Method
        // 数组中查找该类定义的getter方法(具体哪些方法算是getter方法,看后面)
        // 将其记录到conflicting集合中. conflicting集合(Map<String, List<Method>>)
        // key是属性名称, value是该属性对应的getter方法集合
        for (Method method : methods) {
            String name = method.getName();
            // JavaBean 中 getter方法的方法名长度大于3 且 必须以get开头
            if (name.startsWith("get") && name.length() > 3) {
                // 方法的参数列表为空
                if (method.getParameterTypes().length == 0) {
                    // 按照JavaBean的规范, 获取对应的属性名称
                    name = PropertyNamer.methodToProperty(name);
                    // 将属性名与 getter 方法的对应关系记录到 conflictingGetters 集合中
                    addMethodConflict(conflictingGetters, name, method);
                }
            } else if (name.startsWith("is") && name.length() > 2) {
                // 对is开头的属性进行处理, 逻辑同get开头的方法
                if (method.getParameterTypes().length == 0) {
                    name = PropertyNamer.methodToProperty(name);
                    addMethodConflict(conflictingGetters, name, method);
                }
            }
        }
        // 3. 当子类覆盖了父类的getter方法且返回值发生变化时,
        // 在步骤1中就会产生两个签名不同的方法.
        // 例如现有类A及其子类SubA, A类定义了getNames()方法,其返回值是List<String>
        // 而在其子类SubA中, 覆写了其getNames() 方法, 且将返回值修改成Array<String>类型,
        // 这种覆写在java语言中是合法的. 最终得到的两个方法签名分别是 java.util.List#getNames
        // 和 java.util.ArrayList#getNames, 在Reflector.addUniqueMethod() 方法中会被认为是
        // 两个不同的方法并添加到uniqueMethods集合中, 这显然不是我们想要的效果.
        // 所以, 在这里调用resolveGetterConflicts() 方法对这种覆写情况进行处理,
        // 同时会将处理得到的get方法记录到getMethods集合,并将其返回值类型填充到getTypes集合.
        resolveGetterConflicts(conflictingGetters);
    }

    private void resolveGetterConflicts(Map<String, List<Method>> conflictingGetters) {
        for (String propName : conflictingGetters.keySet()) {
            List<Method> getters = conflictingGetters.get(propName);
            Iterator<Method> iterator = getters.iterator();
            Method firstMethod = iterator.next();
            if (getters.size() == 1) {
                // 该字段只有一个getter方法, 直接添加到getMethods集合并填充getTypes集合
                addGetMethod(propName, firstMethod);
            } else {
                // 同一属性名称存在多个getter方法, 则需要比较这些getter方法的返回值,
                // 选择getter方法迭代过程中的临时变量, 用于记录迭代到目前为止,
                // 最适合作为getter方法的Method
                Method getter = firstMethod;
                // 记录返回类型
                Class<?> getterType = firstMethod.getReturnType();

                while (iterator.hasNext()) {
                    Method method = iterator.next();
                    // 获取方法的返回值
                    Class<?> methodType = method.getReturnType();
                    if (methodType.equals(getterType)) {
                        // 返回值相同, 这种情况应该在步骤1中被过滤掉,如果出现,则抛出异常
                        throw new ReflectionException("Illegal overloaded getter method with ambiguous type for property "
                                + propName + " in class " + firstMethod.getDeclaringClass()
                                + ".  This breaks the JavaBeans " + "specification and can cause unpredicatble results.");
                    } else if (methodType.isAssignableFrom(getterType)) {
                        // OK getter type is descendant
                        //  getterType是methodType的超类接口, 则当前是最适合的方法,不做处理
                    } else if (getterType.isAssignableFrom(methodType)) {
                        // methodType是getterType的超类接口, 则method是最适合的方法
                        getter = method;
                        getterType = methodType;
                    } else {
                        // 返回值相同,二义性,抛异常
                        throw new ReflectionException("Illegal overloaded getter method with ambiguous type for property "
                                + propName + " in class " + firstMethod.getDeclaringClass()
                                + ".  This breaks the JavaBeans " + "specification and can cause unpredicatble results.");
                    }
                }
                addGetMethod(propName, getter);
            }
        }
    }

    private void addGetMethod(String name, Method method) {
        // 检测属性名是否合法
        if (isValidPropertyName(name)) {
            // 将属性名以及对应的MethodInvoker对象添加到getMethods集合中
            // 注意这里会将Method对象(字段对应的Field对象同理)封装成Invoker对象.
            // 查看Invoker的定义
            getMethods.put(name, new MethodInvoker(method));
            // 将属性名称以及getter方法的返回值类型添加到getType集合中保存
            getTypes.put(name, method.getReturnType());
        }
    }

    private void addSetMethods(Class<?> cls) {
        Map<String, List<Method>> conflictingSetters = new HashMap<String, List<Method>>();
        Method[] methods = getClassMethods(cls);
        for (Method method : methods) {
            String name = method.getName();
            if (name.startsWith("set") && name.length() > 3) {
                if (method.getParameterTypes().length == 1) {
                    name = PropertyNamer.methodToProperty(name);
                    addMethodConflict(conflictingSetters, name, method);
                }
            }
        }
        resolveSetterConflicts(conflictingSetters);
    }

    private void addMethodConflict(Map<String, List<Method>> conflictingMethods, String name, Method method) {
        List<Method> list = conflictingMethods.get(name);
        if (list == null) {
            list = new ArrayList<Method>();
            conflictingMethods.put(name, list);
        }
        list.add(method);
    }

    private void resolveSetterConflicts(Map<String, List<Method>> conflictingSetters) {
        for (String propName : conflictingSetters.keySet()) {
            List<Method> setters = conflictingSetters.get(propName);
            Method firstMethod = setters.get(0);
            if (setters.size() == 1) {
                addSetMethod(propName, firstMethod);
            } else {
                Class<?> expectedType = getTypes.get(propName);
                if (expectedType == null) {
                    throw new ReflectionException("Illegal overloaded setter method with ambiguous type for property "
                            + propName + " in class " + firstMethod.getDeclaringClass() + ".  This breaks the JavaBeans " +
                            "specification and can cause unpredicatble results.");
                } else {
                    Iterator<Method> methods = setters.iterator();
                    Method setter = null;
                    while (methods.hasNext()) {
                        Method method = methods.next();
                        if (method.getParameterTypes().length == 1
                                && expectedType.equals(method.getParameterTypes()[0])) {
                            setter = method;
                            break;
                        }
                    }
                    if (setter == null) {
                        throw new ReflectionException("Illegal overloaded setter method with ambiguous type for property "
                                + propName + " in class " + firstMethod.getDeclaringClass() + ".  This breaks the JavaBeans " +
                                "specification and can cause unpredicatble results.");
                    }
                    addSetMethod(propName, setter);
                }
            }
        }
    }

    private void addSetMethod(String name, Method method) {
        if (isValidPropertyName(name)) {
            setMethods.put(name, new MethodInvoker(method));
            setTypes.put(name, method.getParameterTypes()[0]);
        }
    }

    /**
     * 会处理类中定义的所有字段,并且将处理后的字段信息添加到setMethods集合, setTypes集合
     * getMethods集合,以及getTypes集合中.
     *
     * @param clazz
     */
    private void addFields(Class<?> clazz) {
        // 获取clazz中定义的全部字段
        Field[] fields = clazz.getDeclaredFields();
        for (Field field : fields) {
            if (canAccessPrivateMethods()) {
                try {
                    field.setAccessible(true);
                } catch (Exception e) {
                    // Ignored. This is only a final precaution, nothing we can do.
                }
            }
            if (field.isAccessible()) {
                // 当setMethods集合不包含同名属性时,将其记录到setMethods集合和setTypes集合
                if (!setMethods.containsKey(field.getName())) {
                    // issue #379 - removed the check for final because JDK 1.5 allows
                    // modification of final fields through reflection (JSR-133). (JGB)
                    // pr #16 - final static can only be set by the classloader
                    int modifiers = field.getModifiers();
                    // 过滤掉 final 和 static修饰的字段
                    if (!(Modifier.isFinal(modifiers) && Modifier.isStatic(modifiers))) {
                        // addSetField()方法的主要功能是填充 setMethods集合 和 setTypes集合,
                        addSetField(field);
                    }
                }
                // 当getMethods集合不包含同名属性时,将其记录到getMethods集合和getTypes集合
                if (!getMethods.containsKey(field.getName())) {
                    addGetField(field);
                }
            }
        }
        if (clazz.getSuperclass() != null) {
            // 处理父类中定义的字段
            addFields(clazz.getSuperclass());
        }
    }

    private void addSetField(Field field) {
        if (isValidPropertyName(field.getName())) {
            setMethods.put(field.getName(), new SetFieldInvoker(field));
            setTypes.put(field.getName(), field.getType());
        }
    }

    private void addGetField(Field field) {
        if (isValidPropertyName(field.getName())) {
            getMethods.put(field.getName(), new GetFieldInvoker(field));
            getTypes.put(field.getName(), field.getType());
        }
    }

    private boolean isValidPropertyName(String name) {
        return !(name.startsWith("$") || "serialVersionUID".equals(name) || "class".equals(name));
    }

    /*
     * This method returns an array containing all methods
     * declared in this class and any superclass.
     * We use this method, instead of the simpler Class.getMethods(),
     * because we want to look for private methods as well.
     * 得到所有方法，包括private方法，包括父类方法.包括接口方法
     *
     * @param cls The class
     * @return An array containing all methods in this class
     */
    private Method[] getClassMethods(Class<?> cls) {
        //  用于记录指定类中定义的全部方法的唯一签名以及对应的Method对象
        Map<String, Method> uniqueMethods = new HashMap<String, Method>();
        Class<?> currentClass = cls;
        while (currentClass != null) {
            // 记录 currentClass 这个类中定义的全部方法
            addUniqueMethods(uniqueMethods, currentClass.getDeclaredMethods());

            // we also need to look for interface methods -
            // because the class may be abstract
            // 记录接口中的定义的方法
            Class<?>[] interfaces = currentClass.getInterfaces();
            for (Class<?> anInterface : interfaces) {
                addUniqueMethods(uniqueMethods, anInterface.getMethods());
            }

            // 获取父类, 继续while循环
            currentClass = currentClass.getSuperclass();
        }

        Collection<Method> methods = uniqueMethods.values();

        // 转换成Methods数组返回
        return methods.toArray(new Method[methods.size()]);
    }

    /**
     * 为每个方法生成唯一签名,并记录到uniqueMethods集合中
     *
     * @param uniqueMethods
     * @param methods
     */
    private void addUniqueMethods(Map<String, Method> uniqueMethods, Method[] methods) {
        for (Method currentMethod : methods) {
            if (!currentMethod.isBridge()) {
                // 取得签名 : 返回值类型#方法名:参数类型列表
                // 例如, Reflector.getSignature(Method) 方法的唯一签名是:
                // java.lang.String#getSignature:java.lang.reflect.Method
                // 通过 Reflector.getSignature() 方法得到的方法签名是全局唯一的,
                // 可以作为该方法的唯一标识
                String signature = getSignature(currentMethod);
                // check to see if the method is already known
                // if it is known, then an extended class must have
                // overridden a method
                // 检测是否在子类中已经添加过该方法,
                // 如果在子类中已经添加过, 则表示子类覆盖了该方法
                // 无需再向uniqueMethods集合中添加该方法了
                if (!uniqueMethods.containsKey(signature)) {
                    if (canAccessPrivateMethods()) {
                        try {
                            currentMethod.setAccessible(true);
                        } catch (Exception e) {
                            // Ignored. This is only a final precaution, nothing we can do.
                        }
                    }

                    // 记录该签名和方法的对应关系
                    uniqueMethods.put(signature, currentMethod);
                }
            }
        }
    }

    private String getSignature(Method method) {
        StringBuilder sb = new StringBuilder();
        Class<?> returnType = method.getReturnType();
        if (returnType != null) {
            sb.append(returnType.getName()).append('#');
        }
        sb.append(method.getName());
        Class<?>[] parameters = method.getParameterTypes();
        for (int i = 0; i < parameters.length; i++) {
            if (i == 0) {
                sb.append(':');
            } else {
                sb.append(',');
            }
            sb.append(parameters[i].getName());
        }
        return sb.toString();
    }

    /*
     * Gets the name of the class the instance provides information for
     *
     * @return The class name
     */
    public Class<?> getType() {
        return type;
    }

    public Constructor<?> getDefaultConstructor() {
        if (defaultConstructor != null) {
            return defaultConstructor;
        } else {
            throw new ReflectionException("There is no default constructor for " + type);
        }
    }

    public boolean hasDefaultConstructor() {
        return defaultConstructor != null;
    }

    public Invoker getSetInvoker(String propertyName) {
        Invoker method = setMethods.get(propertyName);
        if (method == null) {
            throw new ReflectionException("There is no setter for property named '" + propertyName + "' in '" + type + "'");
        }
        return method;
    }

    public Invoker getGetInvoker(String propertyName) {
        Invoker method = getMethods.get(propertyName);
        if (method == null) {
            throw new ReflectionException("There is no getter for property named '" + propertyName + "' in '" + type + "'");
        }
        return method;
    }

    /*
     * Gets the type for a property setter
     *
     * @param propertyName - the name of the property
     * @return The Class of the propery setter
     */
    public Class<?> getSetterType(String propertyName) {
        Class<?> clazz = setTypes.get(propertyName);
        if (clazz == null) {
            throw new ReflectionException("There is no setter for property named '" + propertyName + "' in '" + type + "'");
        }
        return clazz;
    }

    /*
     * Gets the type for a property getter
     *
     * @param propertyName - the name of the property
     * @return The Class of the propery getter
     */
    public Class<?> getGetterType(String propertyName) {
        Class<?> clazz = getTypes.get(propertyName);
        if (clazz == null) {
            throw new ReflectionException("There is no getter for property named '" + propertyName + "' in '" + type + "'");
        }
        return clazz;
    }

    /*
     * Gets an array of the readable properties for an object
     *
     * @return The array
     */
    public String[] getGetablePropertyNames() {
        return readablePropertyNames;
    }

    /*
     * Gets an array of the writeable properties for an object
     *
     * @return The array
     */
    public String[] getSetablePropertyNames() {
        return writeablePropertyNames;
    }

    /*
     * Check to see if a class has a writeable property by name
     *
     * @param propertyName - the name of the property to check
     * @return True if the object has a writeable property by the name
     */
    public boolean hasSetter(String propertyName) {
        return setMethods.keySet().contains(propertyName);
    }

    /*
     * Check to see if a class has a readable property by name
     *
     * @param propertyName - the name of the property to check
     * @return True if the object has a readable property by the name
     */
    public boolean hasGetter(String propertyName) {
        return getMethods.keySet().contains(propertyName);
    }

    public String findPropertyName(String name) {
        return caseInsensitivePropertyMap.get(name.toUpperCase(Locale.ENGLISH));
    }
}
