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

import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.Map;
import java.util.SortedMap;
import java.util.TreeMap;

import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.binding.MapperMethod.ParamMap;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.ResultHandler;
import org.apache.ibatis.session.RowBounds;

public class ParamNameResolver {

  private static final String GENERIC_NAME_PREFIX = "param";

  /**
   * <p>
   * The key is the index and the value is the name of the parameter.<br />
   * The name is obtained from {@link Param} if specified. When {@link Param} is not specified,
   * the parameter index is used. Note that this index could be different from the actual index
   * when the method has special parameters (i.e. {@link RowBounds} or {@link ResultHandler}).
   * </p>
   * <ul>
   * <li>aMethod(@Param("M") int a, @Param("N") int b) -&gt; {{0, "M"}, {1, "N"}}</li>
   * <li>aMethod(int a, int b) -&gt; {{0, "0"}, {1, "1"}}</li>
   * <li>aMethod(int a, RowBounds rb, int b) -&gt; {{0, "0"}, {2, "1"}}</li>
   * </ul>
   */
  // 记录了参数在参数列表中的位置索引与参数名称之间的对应的关系，其中key表示参数在参数列表中的索引位置
  // value表示参数名称
  private final SortedMap<Integer, String> names;

  private boolean hasParamAnnotation; // 记录了对应方法的参数列表中是否使用了@param注解

  public ParamNameResolver(Configuration config, Method method) {
	// 获取参数列表中每个参数的类型
    final Class<?>[] paramTypes = method.getParameterTypes();
    
    // 参数列表中的注解
    final Annotation[][] paramAnnotations = method.getParameterAnnotations();
    
    // 该集合用于记录参数索引与参数名称的对应关系
    final SortedMap<Integer, String> map = new TreeMap<Integer, String>();
    int paramCount = paramAnnotations.length;
    // get names from @Param annotations
    for (int paramIndex = 0; paramIndex < paramCount; paramIndex++) { // 遍历方法所有参数
      if (isSpecialParameter(paramTypes[paramIndex])) {
        // skip special parameters
    	// 如果参数是RowBounds类型或ResultHandler类型，则跳过对该参数的分析
        continue;
      }
      String name = null;
      // 遍历该参数对应的注解集合
      for (Annotation annotation : paramAnnotations[paramIndex]) { // 
        if (annotation instanceof Param) {
          // @Param注解出现过一次，就将hasParamAnnotation初始化为true
          hasParamAnnotation = true;
          name = ((Param) annotation).value();
          break;
        }
      }
      // 这个if代码解释了上面的实例中names集合项的value为什么是“0”和“1”
      if (name == null) {
        // @Param was not specified.
    	// 该参数没有对应的@Param注解，则根据配置决定是否使用参数实际名称作为其名称
        if (config.isUseActualParamName()) {
          name = getActualParamName(method, paramIndex);
        }
        if (name == null) {
          // use the parameter index as the name ("0", "1", ...)
          // gcode issue #71
          // 使用参数索引作为名称
          name = String.valueOf(map.size());
        }
      }
      map.put(paramIndex, name); // 记录到map中保存
    }
    // 最终的names字段:aMethod(int a, int b) -&gt; {{0, "0"}, {1, "1"}}
    names = Collections.unmodifiableSortedMap(map); // 初始化names集合
  }

  private String getActualParamName(Method method, int paramIndex) {
    if (Jdk.parameterExists) {
      return ParamNameUtil.getParamNames(method).get(paramIndex);
    }
    return null;
  }
  // isSpecialParameter()方法是过滤掉RowBounds和ResultHandler两种类型的参数
  private static boolean isSpecialParameter(Class<?> clazz) {
    return RowBounds.class.isAssignableFrom(clazz) || ResultHandler.class.isAssignableFrom(clazz);
  }

  /**
   * Returns parameter names referenced by SQL providers.
   */
  public String[] getNames() {
    return names.values().toArray(new String[0]);
  }

  /**
   * <p>
   * A single non-special parameter is returned without a name.<br />
   * Multiple parameters are named using the naming rule.<br />
   * In addition to the default names, this method also adds the generic names (param1, param2,
   * ...).
   * </p>
   */
  public Object getNamedParams(Object[] args) {
    final int paramCount = names.size();// names字段大小
    if (args == null || paramCount == 0) { // 无参数，直接返回
      return null;
    } else if (!hasParamAnnotation && paramCount == 1) { // 为使用@Param且只有一个参数
      return args[names.firstKey()];
    } else { // 处理使用@Param注解指定了参数名称或多个参数的情况
    	
      // param这个Map中记录了参数名称与实参之间的对应的关系.ParamMap继承了HashMap，向
      // ParamMap中添加已经存在的key，会报错，其行为与HashMap相同
      final Map<String, Object> param = new ParamMap<Object>();
      int i = 0;
      for (Map.Entry<Integer, String> entry : names.entrySet()) {
    	// 将参数名与实参对应关系记录到param中
        param.put(entry.getValue(), args[entry.getKey()]);
        // add generic param names (param1, param2, ...)
        // 下面是参数创建“param+索引”格式的默认参数名称，例如param1，param2等，并添加param集合中
        final String genericParamName = GENERIC_NAME_PREFIX + String.valueOf(i + 1);
        // ensure not to overwrite parameter named with @Param
        // 如果@Param注解指定的参数名称就是“param+索引”格式的，则不需要添加
        if (!names.containsValue(genericParamName)) {
          param.put(genericParamName, args[entry.getKey()]);
        }
        i++;
      }
      return param;
    }
  }
}
