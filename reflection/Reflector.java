/**
 *    Copyright 2009-2018 the original author or authors.
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

import java.lang.reflect.Array;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.GenericArrayType;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.ReflectPermission;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Map.Entry;

import org.apache.ibatis.reflection.invoker.GetFieldInvoker;
import org.apache.ibatis.reflection.invoker.Invoker;
import org.apache.ibatis.reflection.invoker.MethodInvoker;
import org.apache.ibatis.reflection.invoker.SetFieldInvoker;
import org.apache.ibatis.reflection.property.PropertyNamer;

/**
 * This class represents a cached set of class definition information that
 * allows for easy mapping between property names and getter/setter methods.
 *
 * @author Clinton Begin
 */
public class Reflector {

  private final Class<?> type; // 对应的Class类型
  
  // 可读属性的名称集合，可读属性就是存在相应getter方法的属性，初始值为空数组
  private final String[] readablePropertyNames;
  
  // 可写属性的名称集合，可写属性就是存在相应setter方法的属性，初始值为空数组
  private final String[] writeablePropertyNames;
  
  // 记录了属性相应的setter方法，key是属性名称，value是Invoker对象，它是对setter方法对应Method对象的封装
  private final Map<String, Invoker> setMethods = new HashMap<String, Invoker>();
  
  // 属性相应的getter方法集合，key是属性名称，value也是Invoker对象
  private final Map<String, Invoker> getMethods = new HashMap<String, Invoker>();
  
  // 记录了属性相应的setter的数组类型，key是属性名称，value是setter方法的参数类型
  private final Map<String, Class<?>> setTypes = new HashMap<String, Class<?>>();
  
  // 记录了属性相应的getter的数组类型，key是属性名称，value是getter方法的参数类型
  private final Map<String, Class<?>> getTypes = new HashMap<String, Class<?>>();
  
  private Constructor<?> defaultConstructor; // 记录了默认的构造函数
  
  // 记录了所有属性的集合
  private Map<String, String> caseInsensitivePropertyMap = new HashMap<String, String>();

  public Reflector(Class<?> clazz) {
	  
    type = clazz; // 初始化type字段
    addDefaultConstructor(clazz); // 默认构造函数(无参构造方法),具体实现是通过反射遍历所有的构造方法
    
    addGetMethods(clazz); // 处理clazz中的getter方法，填充getMethods集合和getTypes集合
    
    addSetMethods(clazz); // 处理clazz中的setter方法，填充setMethods集合和setTypes集合
    
    addFields(clazz); // 处理没有getter/setter方法的字段
    
    // 根据setMethods/setMethods集合，初始化可读/可写属性的名称集合
    readablePropertyNames = getMethods.keySet().toArray(new String[getMethods.keySet().size()]);
    writeablePropertyNames = setMethods.keySet().toArray(new String[setMethods.keySet().size()]);
    
    // 初始化caseInsensitivePropertyMap集合，其中记录了所有大写格式的属性名称
    for (String propName : readablePropertyNames) {
      caseInsensitivePropertyMap.put(propName.toUpperCase(Locale.ENGLISH), propName);
    }
    for (String propName : writeablePropertyNames) {
      caseInsensitivePropertyMap.put(propName.toUpperCase(Locale.ENGLISH), propName);
    }
  }

  private void addDefaultConstructor(Class<?> clazz) {
	// 获取clazz对应的构造器
    Constructor<?>[] consts = clazz.getDeclaredConstructors();
    for (Constructor<?> constructor : consts) {
      // 无参构造函数defaultConstructor字段
      if (constructor.getParameterTypes().length == 0) {
    	// 将无参构造函数设置
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
  // 处理clazz中的getter方法，填充getMethods集合和getTypes集合
  private void addGetMethods(Class<?> cls) {
	/*
	 * conflictingGetters集合的key为属性名称，value是相应getter方法集合，因为子类可能覆盖父类的
	 * getter方法，所以同一属性名称会存在多个getter方法
	 */
    Map<String, List<Method>> conflictingGetters = new HashMap<String, List<Method>>();
    // 步骤一:获取指定类及其超类和接口中定义的方法
    Method[] methods = getClassMethods(cls);
    
    // 步骤二:按照javaBean规范查找getter方法，并记录到conflictingGetters集合中
    for (Method method : methods) {
      if (method.getParameterTypes().length > 0) {
        continue;
      }
      String name = method.getName();
      // JavaBean中getter方法的长度大于3且必须以“get”开头
      if ((name.startsWith("get") && name.length() > 3)
          || (name.startsWith("is") && name.length() > 2)) { // 对is开头的属性进行处理
    	  
    	// 按照JavaBean的规范，获取对应的属性名称(getName -> name)
        name = PropertyNamer.methodToProperty(name);
        // 将属性名称与getter方法的对应关系记录到conflictingGetters中去
        addMethodConflict(conflictingGetters, name, method);
      }
    }
    // 步骤三:对conflictingGetters集合进行处理
    resolveGetterConflicts(conflictingGetters);
  }

  private void resolveGetterConflicts(Map<String, List<Method>> conflictingGetters) {
      // 遍历conflictingGetters集合
	  for (Entry<String, List<Method>> entry : conflictingGetters.entrySet()) {
      Method winner = null;
      String propName = entry.getKey();
      // 处理子类与超类之间的overloaded情况
      for (Method candidate : entry.getValue()) {
        if (winner == null) { // 第一次循环时会continue,winner = candidate
          winner = candidate;
          continue;
        }
        Class<?> winnerType = winner.getReturnType();
        Class<?> candidateType = candidate.getReturnType();
        
        if (candidateType.equals(winnerType)) {
          // 返回值相同，这种情况应该在步骤一被过滤调，如果出现，则抛出异常
          if (!boolean.class.equals(candidateType)) {
            throw new ReflectionException(
                "Illegal overloaded getter method with ambiguous type for property "
                    + propName + " in class " + winner.getDeclaringClass()
                    + ". This breaks the JavaBeans specification and can cause unpredictable results.");
          } else if (candidate.getName().startsWith("is")) {
        	/*
        	 * 当前最适合的方法的返回值是当前返回值的子类，什么都不做，当前最适合的方法依然不变
        	 */
            winner = candidate;
          }
        } else if (candidateType.isAssignableFrom(winnerType)) {
          // OK getter type is descendant
        } else if (winnerType.isAssignableFrom(candidateType)) {
          // 当前方法的返回值是当前最适合的方法的返回值的子类，更新临时变量getter,当前的getter方法
          // 成为最适合的getter方法
          winner = candidate;
        } else {
          // 返回值相同,二义性，抛出异常
          throw new ReflectionException(
              "Illegal overloaded getter method with ambiguous type for property "
                  + propName + " in class " + winner.getDeclaringClass()
                  + ". This breaks the JavaBeans specification and can cause unpredictable results.");
        }
      }
      addGetMethod(propName, winner);
    }
  }

  private void addGetMethod(String name, Method method) {
    if (isValidPropertyName(name)) { // 检测属性名是否合法
      /*
       * 将属性名以及对应的MethodInvoker对象添加到getMethods集合中，Invoker的内容后面介绍
       */
      getMethods.put(name, new MethodInvoker(method));
      
      // 获取返回值的type，TypeParameterResolver后面来解析
      Type returnType = TypeParameterResolver.resolveReturnType(method, type);
      /*
       * 将属性名称及其getter方法的返回值类型添加到getTypes集合中保存，typeToClass()方法和面解析
       */
      getTypes.put(name, typeToClass(returnType));
    }
  }
  // 处理clazz中的setter方法，填充setMethods集合和setTypes集合
  private void addSetMethods(Class<?> cls) {
	/*
	 * conflictingSetters集合的key为属性名称，value是相应setter方法集合，因为子类可能覆盖父类的
	 * setter方法，所以同一属性名称会存在多个getter方法
	 */  
    Map<String, List<Method>> conflictingSetters = new HashMap<String, List<Method>>();
    Method[] methods = getClassMethods(cls);
    for (Method method : methods) {
      String name = method.getName();
      if (name.startsWith("set") && name.length() > 3) {
        if (method.getParameterTypes().length == 1) { // 参数个数为1	
          // 按照JavaBean的规范，获取对应的属性名称(getName -> name)
          name = PropertyNamer.methodToProperty(name);
          // 将属性名称与setter方法的对应关系记录到conflictingSetters中去
          addMethodConflict(conflictingSetters, name, method);
        }
      }
    }
    // 对conflictingGetters集合进行处理
    resolveSetterConflicts(conflictingSetters);
  }

  private void addMethodConflict(Map<String, List<Method>> conflictingMethods, String name, Method method) {
    List<Method> list = conflictingMethods.get(name); // 获取name相应的value
    if (list == null) { // 如果为空的话
      list = new ArrayList<Method>(); // 生成ArrayList<Method>();
      conflictingMethods.put(name, list);
    }
    list.add(method); // 将方法对象添加到list中
  }

  private void resolveSetterConflicts(Map<String, List<Method>> conflictingSetters) {
    for (String propName : conflictingSetters.keySet()) { // 遍历conflictingSetters集合
    	
      List<Method> setters = conflictingSetters.get(propName);
      Class<?> getterType = getTypes.get(propName);
      
      Method match = null;
      ReflectionException exception = null;
      for (Method setter : setters) {
    	// 获取参数类型
        Class<?> paramType = setter.getParameterTypes()[0];
        if (paramType.equals(getterType)) {
          // should be the best match
          match = setter; // 当参数类型与get操作的返回值类型相同直接返回
          break;
        }
        if (exception == null) {
          try {
            match = pickBetterSetter(match, setter, propName);
          } catch (ReflectionException e) {
            // there could still be the 'best match'
            match = null;
            exception = e;
          }
        }
      }
      if (match == null) {
        throw exception;
      } else {
        addSetMethod(propName, match);
      }
    }
  }

  private Method pickBetterSetter(Method setter1, Method setter2, String property) {
    if (setter1 == null) {
      return setter2;
    }
    Class<?> paramType1 = setter1.getParameterTypes()[0];
    Class<?> paramType2 = setter2.getParameterTypes()[0];
    if (paramType1.isAssignableFrom(paramType2)) {
      return setter2;
    } else if (paramType2.isAssignableFrom(paramType1)) {
      return setter1;
    }
    throw new ReflectionException("Ambiguous setters defined for property '" + property + "' in class '"
        + setter2.getDeclaringClass() + "' with types '" + paramType1.getName() + "' and '"
        + paramType2.getName() + "'.");
  }

  private void addSetMethod(String name, Method method) {
    if (isValidPropertyName(name)) {
      // 将属性名以及对应的MethodInvoker对象添加到setMethods集合中.
      setMethods.put(name, new MethodInvoker(method));
      // 获取返回值的type，TypeParameterResolver后面来解析
      Type[] paramTypes = TypeParameterResolver.resolveParamTypes(method, type);
      // 将属性名称及其getter方法的返回值类型添加到getTypes集合中保存，typeToClass()方法和面解析
      setTypes.put(name, typeToClass(paramTypes[0]));
    }
  }

  private Class<?> typeToClass(Type src) {
    Class<?> result = null;
    if (src instanceof Class) { // Class类型
      result = (Class<?>) src;
    } else if (src instanceof ParameterizedType) { // ParameterizedType类型
      result = (Class<?>) ((ParameterizedType) src).getRawType();
    } else if (src instanceof GenericArrayType) { // GenericArrayType类型
      Type componentType = ((GenericArrayType) src).getGenericComponentType();
      if (componentType instanceof Class) {
        result = Array.newInstance((Class<?>) componentType, 0).getClass();
      } else {
        Class<?> componentClass = typeToClass(componentType);
        result = Array.newInstance((Class<?>) componentClass, 0).getClass();
      }
    }
    if (result == null) {
      result = Object.class;
    }
    return result;
  }
  /*
   * addFields处理类中定义的所有字段，并将处理后的字段信息添加到setMethods集合/setTypes集合/
   * getMethods集合以及getTypes集合中
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
    	// 当setMethods集合不包含同名属性时，将其记录到setMethods集合和setTypes集合
        if (!setMethods.containsKey(field.getName())) {
          // issue #379 - removed the check for final because JDK 1.5 allows
          // modification of final fields through reflection (JSR-133). (JGB)
          // pr #16 - final static can only be set by the classloader
          int modifiers = field.getModifiers();
          // 过滤掉final与static字段
          if (!(Modifier.isFinal(modifiers) && Modifier.isStatic(modifiers))) {
        	// 该方法是填充setMethods集合和setTypes集合，与addGetMethods方法类似
            addSetField(field);
          }
        }
        // 当getMethods集合中不包含同名属性时，将其记录到getMethods集合与getTypes集合
        if (!getMethods.containsKey(field.getName())) {
          // 该方法是填充setMethods集合和setTypes集合，与addGetMethods方法类似
          addGetField(field);
        }
      }
    }
    if (clazz.getSuperclass() != null) {
      addFields(clazz.getSuperclass()); // 处理父类中的字段
    }
  }

  private void addSetField(Field field) {
    if (isValidPropertyName(field.getName())) { // 验证字段名
      // 将属性名以及对应的SetFieldInvoker对象添加到setMethods集合中.
      setMethods.put(field.getName(), new SetFieldInvoker(field));
      Type fieldType = TypeParameterResolver.resolveFieldType(field, type);
      // 将属性名称及其setter方法的返回值类型添加到setTypes集合中保存，typeToClass()方法和面解析
      setTypes.put(field.getName(), typeToClass(fieldType));
    }
  }

  private void addGetField(Field field) {
    if (isValidPropertyName(field.getName())) { // 验证字段名
      // 将属性名以及对应的GetFieldInvoker对象添加到getMethods集合中.
      getMethods.put(field.getName(), new GetFieldInvoker(field));
      Type fieldType = TypeParameterResolver.resolveFieldType(field, type);
      // 将属性名称及其getter方法的返回值类型添加到getTypes集合中保存，typeToClass()方法和面解析
      getTypes.put(field.getName(), typeToClass(fieldType));
    }
  }
  
  // 检测属性是否合法
  private boolean isValidPropertyName(String name) {
    return !(name.startsWith("$") || "serialVersionUID".equals(name) || "class".equals(name));
  }

  /*
   * This method returns an array containing all methods
   * declared in this class and any superclass.
   * We use this method, instead of the simpler Class.getMethods(),
   * because we want to look for private methods as well.
   *
   * @param cls The class
   * @return An array containing all methods in this class
   */
  private Method[] getClassMethods(Class<?> cls) {
	// 用来记录指定类中定义的全部方法的唯一签名以及对应的Method对象 
    Map<String, Method> uniqueMethods = new HashMap<String, Method>();
    Class<?> currentClass = cls;
    while (currentClass != null && currentClass != Object.class) {
      // 记录currentClass这个类中定义的全部方法
      addUniqueMethods(uniqueMethods, currentClass.getDeclaredMethods());

      // we also need to look for interface methods -
      // because the class may be abstract
      // 记录接口中定义的方法
      Class<?>[] interfaces = currentClass.getInterfaces();
      for (Class<?> anInterface : interfaces) {
        addUniqueMethods(uniqueMethods, anInterface.getMethods());
      }
      // 获取超类，继续while循环
      currentClass = currentClass.getSuperclass();
    }

    Collection<Method> methods = uniqueMethods.values();

    return methods.toArray(new Method[methods.size()]); // 转换成Methods数组返回
  }

  private void addUniqueMethods(Map<String, Method> uniqueMethods, Method[] methods) {
    for (Method currentMethod : methods) {
      if (!currentMethod.isBridge()) {
    	/*
    	 * 通过Reflector.getSignature方法得到的签名是:返回值类型#方法名称:参数类型列表
    	 * 例如Reflector.getSignature(Method)方法的唯一签名是:
    	 * java.lang.String#getSignature:java.lang.reflect.Method
    	 * 通过Reflector.getSignature()方法得到的方法签名是全局唯一的，可以作为该方法的唯一标识。
    	 */
        String signature = getSignature(currentMethod);
        // check to see if the method is already known
        // if it is known, then an extended class must have
        // overridden a method
        /*
         * 检测是否在子类中已经添加过该方法，如果在子类中已经添加过，则表示子类覆盖了该方法
         * 无须再向uniqueMethods集合中添加该方法了
         */
        if (!uniqueMethods.containsKey(signature)) {
          if (canAccessPrivateMethods()) {
            try {
              currentMethod.setAccessible(true);
            } catch (Exception e) {
              // Ignored. This is only a final precaution, nothing we can do.
            }
          }
          // 记录该签名和方法的对应
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
