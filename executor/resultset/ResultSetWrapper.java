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
package org.apache.ibatis.executor.resultset;

import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.apache.ibatis.io.Resources;
import org.apache.ibatis.mapping.ResultMap;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.type.JdbcType;
import org.apache.ibatis.type.ObjectTypeHandler;
import org.apache.ibatis.type.TypeHandler;
import org.apache.ibatis.type.TypeHandlerRegistry;
import org.apache.ibatis.type.UnknownTypeHandler;

/**
 * @author Iwao AVE!
 */
public class ResultSetWrapper {

  private final ResultSet resultSet; // 底层封装的ResultSet对象
  
  private final TypeHandlerRegistry typeHandlerRegistry;
  
  // 记录ResultSet中每列对应的列名
  private final List<String> columnNames = new ArrayList<String>();
  
  // 记录ResultSet中每列对应的javaType类型
  private final List<String> classNames = new ArrayList<String>();
  
  // 记录ResultSet中每列对应的JdbcType类型
  private final List<JdbcType> jdbcTypes = new ArrayList<JdbcType>();
  
  // 记录每列对应的TypeHandler对象，key为列名，value为TypeHandler集合
  private final Map<String, Map<Class<?>, TypeHandler<?>>> typeHandlerMap = new HashMap<String, Map<Class<?>, TypeHandler<?>>>();
  
  // 记录了被映射的列名,其中key是ResultMap对象的id，value是该ResultMap对象映射的列名集合
  private final Map<String, List<String>> mappedColumnNamesMap = new HashMap<String, List<String>>();
  
  // 记录了未映射的列名，其中key是ResultMap对象的id，value是该ResultMap对象的未映射的列名集合
  private final Map<String, List<String>> unMappedColumnNamesMap = new HashMap<String, List<String>>();

  public ResultSetWrapper(ResultSet rs, Configuration configuration) throws SQLException {
    super();
    // 获取类型处理器
    this.typeHandlerRegistry = configuration.getTypeHandlerRegistry();
    
    this.resultSet = rs;
    
    final ResultSetMetaData metaData = rs.getMetaData(); // 获取ResultSet的元信息
    
    final int columnCount = metaData.getColumnCount();// ResultSet中的列数
    
    for (int i = 1; i <= columnCount; i++) {
      // 获取列名或是通过"AS"关键字指定的列名
    	
      //　每列对应的名称
      columnNames.add(configuration.isUseColumnLabel() ? metaData.getColumnLabel(i) : metaData.getColumnName(i));
      
      // 每列对应的jdbcTypes类型
      jdbcTypes.add(JdbcType.forCode(metaData.getColumnType(i))); // 该列的JdbcType类型
      
      // 每列对应的javaType类型
      classNames.add(metaData.getColumnClassName(i));// 该列对应的Java类型
    }
  }

  public ResultSet getResultSet() {
    return resultSet;
  }

  public List<String> getColumnNames() {
    return this.columnNames;
  }

  public List<String> getClassNames() {
	// unmodifiableList()方法
    return Collections.unmodifiableList(classNames);
  }

  public JdbcType getJdbcType(String columnName) {
    for (int i = 0 ; i < columnNames.size(); i++) {
      if (columnNames.get(i).equalsIgnoreCase(columnName)) {
        return jdbcTypes.get(i);   // 从对应的list中获取jdbcType类型
      }
    }
    return null;
  }

  /**
   * Gets the type handler to use when reading the result set.
   * Tries to get from the TypeHandlerRegistry by searching for the property type.
   * If not found it gets the column JDBC type and tries to get a handler for it.
   * 
   * @param propertyType
   * @param columnName
   * @return
   */
  public TypeHandler<?> getTypeHandler(Class<?> propertyType, String columnName) {
    TypeHandler<?> handler = null;// 初始化TypeHandler对象
    
    // 获取列名对应的Map<Class<?>, TypeHandler<?>>对象
    Map<Class<?>, TypeHandler<?>> columnHandlers = typeHandlerMap.get(columnName);
    
    if (columnHandlers == null) {
      // columnHandlers当为空时,创建对象
      columnHandlers = new HashMap<Class<?>, TypeHandler<?>>();
      // 放入到typeHandlerMap中
      typeHandlerMap.put(columnName, columnHandlers);
    } else {
      // 获取对应的TypeHandler处理器
      handler = columnHandlers.get(propertyType);
    }
    if (handler == null) {
      // 获取JdbcType类型
      JdbcType jdbcType = getJdbcType(columnName);
      // 从typeHandlerRegistry获取相应的处理器
      handler = typeHandlerRegistry.getTypeHandler(propertyType, jdbcType);
      // Replicate logic of UnknownTypeHandler#resolveTypeHandler
      // See issue #59 comment 10
      // 当handler为空时
      if (handler == null || handler instanceof UnknownTypeHandler) {
    	// 获取索引号
        final int index = columnNames.indexOf(columnName);
        // 获取对应的javaType类型的Class对象
        final Class<?> javaType = resolveClass(classNames.get(index));
        // javaType与jdbcType不为空
        if (javaType != null && jdbcType != null) {
          handler = typeHandlerRegistry.getTypeHandler(javaType, jdbcType);
        } else if (javaType != null) {
          handler = typeHandlerRegistry.getTypeHandler(javaType);
        } else if (jdbcType != null) {
          handler = typeHandlerRegistry.getTypeHandler(jdbcType);
        }
      }
      if (handler == null || handler instanceof UnknownTypeHandler) {
        handler = new ObjectTypeHandler();
      }
      columnHandlers.put(propertyType, handler);
    }
    return handler;
  }

  private Class<?> resolveClass(String className) {
    try {
      // #699 className could be null
      if (className != null) {
    	// 获取Class类型
        return Resources.classForName(className);
      }
    } catch (ClassNotFoundException e) {
      // ignore
    }
    return null;
  }
  //  将明确需要映射的列和未明确映射的列添加到各自的集合中
  private void loadMappedAndUnmappedColumnNames(ResultMap resultMap, String columnPrefix) throws SQLException {
    
	// mappedColumnNames和unmappedColumnNames分别记录ResultMap中映射的列名和未映射的列名
	List<String> mappedColumnNames = new ArrayList<String>();
    List<String> unmappedColumnNames = new ArrayList<String>();
    
    // 列名前缀修改成大写
    final String upperColumnPrefix = columnPrefix == null ? null : columnPrefix.toUpperCase(Locale.ENGLISH);
    // ResultMap中定义的列名加上前缀，得到实际映射的列名
    // mappedColumns记录所有映射关系中涉及的column属性的集合
    final Set<String> mappedColumns = prependPrefixes(resultMap.getMappedColumns(), upperColumnPrefix);
    for (String columnName : columnNames) {
      final String upperColumnName = columnName.toUpperCase(Locale.ENGLISH);
      // 将明确需要映射的列和未明确映射的列添加到各自的集合中
      if (mappedColumns.contains(upperColumnName)) {
        mappedColumnNames.add(upperColumnName); // 记录映射的列名
      } else {
        unmappedColumnNames.add(columnName); // 记录未映射的列名
      }
    }
    // 将ResultMap的id与列前缀组成key，将ResultMap映射的列名以及未映射的列名保存到
    // mappedColumnNamesMap和unMappedColumnNamesMap中
    mappedColumnNamesMap.put(getMapKey(resultMap, columnPrefix), mappedColumnNames);
    unMappedColumnNamesMap.put(getMapKey(resultMap, columnPrefix), unmappedColumnNames);
  }

  public List<String> getMappedColumnNames(ResultMap resultMap, String columnPrefix) throws SQLException {
    
	// mappedColumnNamesMap集合中查找被映射的列名，其中key是由ResultMap的id与列前缀组成
	List<String> mappedColumnNames = mappedColumnNamesMap.get(getMapKey(resultMap, columnPrefix));
    if (mappedColumnNames == null) {
      // 未查找到指定ResultMap映射的别名，则加载后存入到mappedColumnNamesMap集合中
      loadMappedAndUnmappedColumnNames(resultMap, columnPrefix);
      mappedColumnNames = mappedColumnNamesMap.get(getMapKey(resultMap, columnPrefix));
    }
    return mappedColumnNames;
  }

  public List<String> getUnmappedColumnNames(ResultMap resultMap, String columnPrefix) throws SQLException {
    List<String> unMappedColumnNames = unMappedColumnNamesMap.get(getMapKey(resultMap, columnPrefix));
    if (unMappedColumnNames == null) {
      loadMappedAndUnmappedColumnNames(resultMap, columnPrefix);
      unMappedColumnNames = unMappedColumnNamesMap.get(getMapKey(resultMap, columnPrefix));
    }
    return unMappedColumnNames;
  }
  // 组装字符串
  private String getMapKey(ResultMap resultMap, String columnPrefix) {
    return resultMap.getId() + ":" + columnPrefix;
  }

  private Set<String> prependPrefixes(Set<String> columnNames, String prefix) {
	// columnNames记录所有映射关系中涉及的column属性的集合
    if (columnNames == null || columnNames.isEmpty() || prefix == null || prefix.length() == 0) {
      return columnNames;
    }
    final Set<String> prefixed = new HashSet<String>();
    for (String columnName : columnNames) {
      prefixed.add(prefix + columnName);  // 对每个columnName名称加上前缀
    }
    return prefixed;
  }
  
}
