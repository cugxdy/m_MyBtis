/**
 *    Copyright 2009-2017 the original author or authors.
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

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.Collection;

import org.apache.ibatis.reflection.invoker.GetFieldInvoker;
import org.apache.ibatis.reflection.invoker.Invoker;
import org.apache.ibatis.reflection.invoker.MethodInvoker;
import org.apache.ibatis.reflection.property.PropertyTokenizer;

/**
 * 对复杂的属性表达式的解析
 * @author Clinton Begin
 */
public class MetaClass {

  // reflectorFactory对象，用来缓存reflector对象
  private final ReflectorFactory reflectorFactory;
  
  // 在创建MetaClass时会指定一个类，该reflectorFactory对象会记录该类相关的元信息
  private final Reflector reflector;

  // MetaClass的构造方法是使用private修饰的
  private MetaClass(Class<?> type, ReflectorFactory reflectorFactory) {
    this.reflectorFactory = reflectorFactory;
    // 创建reflector对象，DefaultReflectorFactory.findForClass实现缓存机制
    this.reflector = reflectorFactory.findForClass(type);
  }
  
  // 使用静态方法创建MetaClass对象
  public static MetaClass forClass(Class<?> type, ReflectorFactory reflectorFactory) {
    return new MetaClass(type, reflectorFactory);
  }

  public MetaClass metaClassForProperty(String name) {
    Class<?> propType = reflector.getGetterType(name); // 查找指定属性对应的Class(getter返回类型)
    return MetaClass.forClass(propType, reflectorFactory);// 为该属性创建对应的MetaClass对象
  }

  public String findProperty(String name) {
	// 委托给bulidProperty()方法实现
    StringBuilder prop = buildProperty(name, new StringBuilder());
    return prop.length() > 0 ? prop.toString() : null;
  }

  public String findProperty(String name, boolean useCamelCaseMapping) {
    if (useCamelCaseMapping) {
      name = name.replace("_", "");
    }
    return findProperty(name);
  }

  public String[] getGetterNames() {
    return reflector.getGetablePropertyNames();
  }

  public String[] getSetterNames() {
    return reflector.getSetablePropertyNames();
  }

  public Class<?> getSetterType(String name) {
    PropertyTokenizer prop = new PropertyTokenizer(name);// 解析属性表达式
    if (prop.hasNext()) {
      MetaClass metaProp = metaClassForProperty(prop.getName());// 调用metaClassForProperty()方法
      return metaProp.getSetterType(prop.getChildren());// 递归调用
    } else {
      return reflector.getSetterType(prop.getName());
    }
  }

  public Class<?> getGetterType(String name) {
    PropertyTokenizer prop = new PropertyTokenizer(name); // 解析属性表达式
    if (prop.hasNext()) {
      MetaClass metaProp = metaClassForProperty(prop); // 调用metaClassForProperty()方法
      return metaProp.getGetterType(prop.getChildren());// 递归调用
    }
    // issue #506. Resolve the type inside a Collection Object
    return getGetterType(prop);// 调用getGetterType(PropertyTokenizer)重载
  }

  private MetaClass metaClassForProperty(PropertyTokenizer prop) {
    Class<?> propType = getGetterType(prop); // 获取表达式所表示的属性的类型
    return MetaClass.forClass(propType, reflectorFactory);
  }
  
  // 下面是 getGetterType(PropertyTokenizer prop)方法的具体实现
  private Class<?> getGetterType(PropertyTokenizer prop) {
    Class<?> type = reflector.getGetterType(prop.getName()); // 获取属性名称
    // 该表达式中是否使用“[]”指定了下标，且是Collection子类
    if (prop.getIndex() != null && Collection.class.isAssignableFrom(type)) {
      // 通过TypeParameterResolver工具类解析属性的类型
      Type returnType = getGenericGetterType(prop.getName());
      // 针对ParameterizedType，即针对泛型集合类型进行处理
      if (returnType instanceof ParameterizedType) {
    	// 获取实际的类型参数
        Type[] actualTypeArguments = ((ParameterizedType) returnType).getActualTypeArguments();
        // ???????????????????????????????????
        if (actualTypeArguments != null && actualTypeArguments.length == 1) {
          returnType = actualTypeArguments[0]; // 泛型的类型
          if (returnType instanceof Class) {
            type = (Class<?>) returnType;
          } else if (returnType instanceof ParameterizedType) {
            type = (Class<?>) ((ParameterizedType) returnType).getRawType();
          }
        }
      }
    }
    return type;
  }
  // 下面是getGenericGetterType(String propertyName)方法的实现
  private Type getGenericGetterType(String propertyName) {
    try {
      /*
       * 根据Reflector.getMethods集合中记录的Invoker实现类的类型，决定解析getter方法
       * 的返回值还是解析字段类型
       */
      Invoker invoker = reflector.getGetInvoker(propertyName);
      if (invoker instanceof MethodInvoker) {
        Field _method = MethodInvoker.class.getDeclaredField("method");
        _method.setAccessible(true);
        Method method = (Method) _method.get(invoker);
        return TypeParameterResolver.resolveReturnType(method, reflector.getType());
      } else if (invoker instanceof GetFieldInvoker) {
        Field _field = GetFieldInvoker.class.getDeclaredField("field");
        _field.setAccessible(true);
        Field field = (Field) _field.get(invoker);
        return TypeParameterResolver.resolveFieldType(field, reflector.getType());
      }
    } catch (NoSuchFieldException e) {
    } catch (IllegalAccessException e) {
    }
    return null;
  }

  public boolean hasSetter(String name) {
    PropertyTokenizer prop = new PropertyTokenizer(name);
    if (prop.hasNext()) {
      if (reflector.hasSetter(prop.getName())) {
        MetaClass metaProp = metaClassForProperty(prop.getName());
        // 调用Reflector.hasSetter()方法检测setMethods集合中是否包含这个元素
        return metaProp.hasSetter(prop.getChildren());
      } else {
        return false;
      }
    } else {
      // 调用Reflector.hasSetter()方法检测setMethods集合中是否包含这个元素
      return reflector.hasSetter(prop.getName());
    }
  }
  
  public boolean hasGetter(String name) {
    PropertyTokenizer prop = new PropertyTokenizer(name); //解析属性表达式
    if (prop.hasNext()) {// 存在待处理的子表达式
      // PropertyTokenizer.name指定的属性有getter方法，才能处理子表达式
      if (reflector.hasGetter(prop.getName())) { 
    	// 注意，这里的metaClassForProperty()是重载形式的，但两者逻辑差别大
        MetaClass metaProp = metaClassForProperty(prop);
        return metaProp.hasGetter(prop.getChildren()); // 递归入口
      } else {
        return false;// 递归出口
      }
    } else {
      return reflector.hasGetter(prop.getName()); // 递归出口
    }
  }

  public Invoker getGetInvoker(String name) {
    return reflector.getGetInvoker(name);
  }

  public Invoker getSetInvoker(String name) {
    return reflector.getSetInvoker(name);
  }
  // 下面是findProperty方法的具体实现
  private StringBuilder buildProperty(String name, StringBuilder builder) {
	
    PropertyTokenizer prop = new PropertyTokenizer(name); // 解析属性表达式
    if (prop.hasNext()) {// 是否有子表达式
      // 查找PropertyTokenizer.name对应的属性
      String propertyName = reflector.findPropertyName(prop.getName());
      if (propertyName != null) {
        builder.append(propertyName); // 追加属性
        builder.append(".");
        // 为该属性创建对应的MetaClass对象
        MetaClass metaProp = metaClassForProperty(propertyName);
        // 递归解析PropertyTokenizer.chlidren字段，并将解析结果添加到bulider中保存
        metaProp.buildProperty(prop.getChildren(), builder);
      }
    } else { // 递归出口
      String propertyName = reflector.findPropertyName(name);
      if (propertyName != null) {
        builder.append(propertyName);
      }
    }
    return builder;
  }

  public boolean hasDefaultConstructor() {
    return reflector.hasDefaultConstructor();
  }

}
