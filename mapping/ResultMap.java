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
package org.apache.ibatis.mapping;

import java.lang.annotation.Annotation;
import java.lang.reflect.Constructor;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.builder.BuilderException;
import org.apache.ibatis.logging.Log;
import org.apache.ibatis.logging.LogFactory;
import org.apache.ibatis.reflection.Jdk;
import org.apache.ibatis.reflection.ParamNameUtil;
import org.apache.ibatis.session.Configuration;

/**
 * @author Clinton Begin
 */
public class ResultMap {
  private Configuration configuration;

  private String id;  // <resultMap>节点的id属性
  
  private Class<?> type; // <resultMap>节点的type属性
  
  // 记录了除<discriminator>节点外的其他映射关系(即ResultMapping对象集合)
  private List<ResultMapping> resultMappings; 
  
  // 记录了映射关系中带ID标志的映射关系，例如<id>节点和<constructor>节点的<idArg>子节点
  private List<ResultMapping> idResultMappings;
  
  // 记录了映射关系带有constructor标志的映射关系，例如<constructor>所有子元素
  private List<ResultMapping> constructorResultMappings;
  
  // 记录了映射关系不带有constructor标志的映射关系
  private List<ResultMapping> propertyResultMappings;
  
  private Set<String> mappedColumns; // 记录所有映射关系中涉及的column属性的集合
  
  private Set<String> mappedProperties; 
  
  private Discriminator discriminator; // 鉴别器，对应<discriminator>节点
  
  // 是否含有嵌套的结果映射，如果某个映射关系中存在resultMap属性，且不存在resultSet属性，则为true
  private boolean hasNestedResultMaps;
  
  // 是否有嵌套查询，如果某个属性映射存在select属性，则为true
  private boolean hasNestedQueries;
  
  private Boolean autoMapping;  // 是否开启自动映射

  private ResultMap() {
  }

  public static class Builder {
    private static final Log log = LogFactory.getLog(Builder.class);

    private ResultMap resultMap = new ResultMap();

    public Builder(Configuration configuration, String id, Class<?> type, List<ResultMapping> resultMappings) {
      this(configuration, id, type, resultMappings, null);
    }

    public Builder(Configuration configuration, String id, Class<?> type, List<ResultMapping> resultMappings, Boolean autoMapping) {
      resultMap.configuration = configuration;  // 配置项
      resultMap.id = id; // resultMap的id
      resultMap.type = type; // resultMap的返回类型
      resultMap.resultMappings = resultMappings; // resultMappings对象集合
      resultMap.autoMapping = autoMapping; // 是否自动映射
    }

    public Builder discriminator(Discriminator discriminator) {
      resultMap.discriminator = discriminator;
      return this;
    }

    public Class<?> type() {
      return resultMap.type;
    }

    public ResultMap build() {
      // 当resultMap.id为空时，抛出异常
      if (resultMap.id == null) {
        throw new IllegalArgumentException("ResultMaps must have an id");
      }
      resultMap.mappedColumns = new HashSet<String>();  
      resultMap.mappedProperties = new HashSet<String>();
      resultMap.idResultMappings = new ArrayList<ResultMapping>();
      resultMap.constructorResultMappings = new ArrayList<ResultMapping>();
      resultMap.propertyResultMappings = new ArrayList<ResultMapping>();
      
      final List<String> constructorArgNames = new ArrayList<String>();
      
      for (ResultMapping resultMapping : resultMap.resultMappings) {
    	// 初始化resultMap对象的hasNestedQueries与hasNestedResultMaps字段
        resultMap.hasNestedQueries = resultMap.hasNestedQueries || resultMapping.getNestedQueryId() != null; // 是否有嵌套查询
        // 是否有嵌套映射
        resultMap.hasNestedResultMaps = resultMap.hasNestedResultMaps || (resultMapping.getNestedResultMapId() != null && resultMapping.getResultSet() == null);
        final String column = resultMapping.getColumn();  // 获取列属性
        
        if (column != null) {
          // 添加column到mappedColumns集合中
          resultMap.mappedColumns.add(column.toUpperCase(Locale.ENGLISH));
        } else if (resultMapping.isCompositeResult()) { // 是否有composites属性
          for (ResultMapping compositeResultMapping : resultMapping.getComposites()) {
            final String compositeColumn = compositeResultMapping.getColumn();
            if (compositeColumn != null) {// composites 中也可以配置column列属性
              // 添加column到mappedColumns集合中
              resultMap.mappedColumns.add(compositeColumn.toUpperCase(Locale.ENGLISH));
            }
          }
        }
        final String property = resultMapping.getProperty();
        // 当property不为空
        if(property != null) {
          // 添加property到mappedProperties集合中
          resultMap.mappedProperties.add(property);
        }
        // 是否含有CONSTRUCTOR标签
        if (resultMapping.getFlags().contains(ResultFlag.CONSTRUCTOR)) {
          // 判断是否含有CONSTRUCTOR属性
          resultMap.constructorResultMappings.add(resultMapping);
          if (resultMapping.getProperty() != null) {
        	// 构造函数的参数类型(参数类型)
            constructorArgNames.add(resultMapping.getProperty());
          }
        } else {
          // 当不含有CONSTRUCTOR属性时，直接添加到propertyResultMappings
          resultMap.propertyResultMappings.add(resultMapping);
        }
        if (resultMapping.getFlags().contains(ResultFlag.ID)) {
          // 当含有id属性时，添加到idResultMappings中
          resultMap.idResultMappings.add(resultMapping);
        }
      }
      if (resultMap.idResultMappings.isEmpty()) { // 当idResultMappings为空时
        resultMap.idResultMappings.addAll(resultMap.resultMappings);
      }
      if (!constructorArgNames.isEmpty()) { // 当<constructor>的子节点含有property属性时
        final List<String> actualArgNames = argNamesOfMatchingConstructor(constructorArgNames);
        if (actualArgNames == null) {
          // 获取实际参数为空，抛出异常
          throw new BuilderException("Error in result map '" + resultMap.id
              + "'. Failed to find a constructor in '"
              + resultMap.getType().getName() + "' by arg names " + constructorArgNames
              + ". There might be more info in debug log.");
        }
        // 对constructorResultMappings集合中的ResultMapping对象进行排序，排序结果为实际参数类型对应的顺序结果
        Collections.sort(resultMap.constructorResultMappings, new Comparator<ResultMapping>() {
          @Override
          public int compare(ResultMapping o1, ResultMapping o2) { // 比较的依据
            int paramIdx1 = actualArgNames.indexOf(o1.getProperty());
            int paramIdx2 = actualArgNames.indexOf(o2.getProperty());
            return paramIdx1 - paramIdx2;
          }
        });
      }
      // lock down collections
      // unmodifiableList()方法使得集合变得不可更改
      resultMap.resultMappings = Collections.unmodifiableList(resultMap.resultMappings);
      resultMap.idResultMappings = Collections.unmodifiableList(resultMap.idResultMappings);
      resultMap.constructorResultMappings = Collections.unmodifiableList(resultMap.constructorResultMappings);
      resultMap.propertyResultMappings = Collections.unmodifiableList(resultMap.propertyResultMappings);
      resultMap.mappedColumns = Collections.unmodifiableSet(resultMap.mappedColumns);
      return resultMap;
    }

    private List<String> argNamesOfMatchingConstructor(List<String> constructorArgNames) {
      // 得到返回类型的构造函数列表
      Constructor<?>[] constructors = resultMap.type.getDeclaredConstructors();
      for (Constructor<?> constructor : constructors) {
    	// 获取构造函数的参数类型
        Class<?>[] paramTypes = constructor.getParameterTypes();
        if (constructorArgNames.size() == paramTypes.length) { // 当两者大小相等时
          List<String> paramNames = getArgNames(constructor);
          if (constructorArgNames.containsAll(paramNames)
              && argTypesMatch(constructorArgNames, paramTypes, paramNames)) {
            return paramNames; // 返回参数名称
          }
        }
      }
      return null;
    }

    private boolean argTypesMatch(final List<String> constructorArgNames,
        Class<?>[] paramTypes, List<String> paramNames) {
      for (int i = 0; i < constructorArgNames.size(); i++) {
        Class<?> actualType = paramTypes[paramNames.indexOf(constructorArgNames.get(i))];
        Class<?> specifiedType = resultMap.constructorResultMappings.get(i).getJavaType();
        if (!actualType.equals(specifiedType)) {
          if (log.isDebugEnabled()) {
            log.debug("While building result map '" + resultMap.id
                + "', found a constructor with arg names " + constructorArgNames
                + ", but the type of '" + constructorArgNames.get(i)
                + "' did not match. Specified: [" + specifiedType.getName() + "] Declared: ["
                + actualType.getName() + "]");
          }
          return false;
        }
      }
      return true;
    }
    // 得到参数名列表
    private List<String> getArgNames(Constructor<?> constructor) {
      List<String> paramNames = new ArrayList<String>();
      List<String> actualParamNames = null;
      // 得到参数注解 获得了一个注解的二维数组。第一个维度对应参数列表里参数的数目，第二个维度对应参数列表里对应的注解。
      final Annotation[][] paramAnnotations = constructor.getParameterAnnotations();
      int paramCount = paramAnnotations.length; // 返回参数的个数
      for (int paramIndex = 0; paramIndex < paramCount; paramIndex++) {
        String name = null;
        for (Annotation annotation : paramAnnotations[paramIndex]) {
          if (annotation instanceof Param) {
            name = ((Param) annotation).value(); // 当注解为@param("A")时, name = A
            break;
          }
        }
        if (name == null && resultMap.configuration.isUseActualParamName() && Jdk.parameterExists) {
          if (actualParamNames == null) {
            actualParamNames = ParamNameUtil.getParamNames(constructor);
          }
          if (actualParamNames.size() > paramIndex) {
            name = actualParamNames.get(paramIndex);
          }
        }
        paramNames.add(name != null ? name : "arg" + paramIndex); // 默认情况下为arg1
      }
      return paramNames;
    }
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

  public Set<String> getMappedProperties() {
    return mappedProperties;
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

}
