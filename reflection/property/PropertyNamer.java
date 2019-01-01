/**
 *    Copyright 2009-2015 the original author or authors.
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
package org.apache.ibatis.reflection.property;

import java.util.Locale;

import org.apache.ibatis.reflection.ReflectionException;

/**
 * 它完成方法名到属性名的转换
 * @author Clinton Begin
 */
public final class PropertyNamer {

  private PropertyNamer() {
    // Prevent Instantiation of Static Class
  }
  
  // methodToProperty会将方法名装换成属性名
  public static String methodToProperty(String name) {
	// 具体逻辑为时将方法名前面的“is”、“get”和“set”截掉，并将首字母小写
    if (name.startsWith("is")) {
      name = name.substring(2);
    } else if (name.startsWith("get") || name.startsWith("set")) {
      name = name.substring(3);
    } else {
      throw new ReflectionException("Error parsing property name '" + name + "'.  Didn't start with 'is', 'get' or 'set'.");
    }

    if (name.length() == 1 || (name.length() > 1 && !Character.isUpperCase(name.charAt(1)))) {
      name = name.substring(0, 1).toLowerCase(Locale.ENGLISH) + name.substring(1);
    }

    return name;
  }
  // isProperty()方法负责检测方法名是否对应属性名
  public static boolean isProperty(String name) {
	// 具体逻辑方法名是否以“get”、“set”或“is”开头
    return name.startsWith("get") || name.startsWith("set") || name.startsWith("is");
  }
  // isGetter()方法负责检测方法是否为getter方法
  public static boolean isGetter(String name) {
    return name.startsWith("get") || name.startsWith("is");
  }
  // isSetter()方法负责检测方法是否为setter方法
  public static boolean isSetter(String name) {
    return name.startsWith("set");
  }

}
