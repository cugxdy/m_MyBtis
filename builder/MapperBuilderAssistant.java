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
package org.apache.ibatis.builder;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.StringTokenizer;

import org.apache.ibatis.cache.Cache;
import org.apache.ibatis.cache.decorators.LruCache;
import org.apache.ibatis.cache.impl.PerpetualCache;
import org.apache.ibatis.executor.ErrorContext;
import org.apache.ibatis.executor.keygen.KeyGenerator;
import org.apache.ibatis.mapping.CacheBuilder;
import org.apache.ibatis.mapping.Discriminator;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.mapping.ParameterMap;
import org.apache.ibatis.mapping.ParameterMapping;
import org.apache.ibatis.mapping.ParameterMode;
import org.apache.ibatis.mapping.ResultFlag;
import org.apache.ibatis.mapping.ResultMap;
import org.apache.ibatis.mapping.ResultMapping;
import org.apache.ibatis.mapping.ResultSetType;
import org.apache.ibatis.mapping.SqlCommandType;
import org.apache.ibatis.mapping.SqlSource;
import org.apache.ibatis.mapping.StatementType;
import org.apache.ibatis.reflection.MetaClass;
import org.apache.ibatis.scripting.LanguageDriver;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.type.JdbcType;
import org.apache.ibatis.type.TypeHandler;

/**
 * @author Clinton Begin
 */
public class MapperBuilderAssistant extends BaseBuilder {

  private String currentNamespace;   // 命名空间
  private final String resource; // url
  private Cache currentCache;
  private boolean unresolvedCacheRef; // issue #676

  public MapperBuilderAssistant(Configuration configuration, String resource) {
    super(configuration);
    ErrorContext.instance().resource(resource);
    this.resource = resource;
  }

  public String getCurrentNamespace() {
    return currentNamespace;
  }

  public void setCurrentNamespace(String currentNamespace) {
    if (currentNamespace == null) {
      throw new BuilderException("The mapper element requires a namespace attribute to be specified.");
    }

    if (this.currentNamespace != null && !this.currentNamespace.equals(currentNamespace)) {
      throw new BuilderException("Wrong namespace. Expected '"
          + this.currentNamespace + "' but found '" + currentNamespace + "'.");
    }

    this.currentNamespace = currentNamespace; // 初始化currentNamespace
  }

  public String applyCurrentNamespace(String base, boolean isReference) {
    if (base == null) {  // base为空的时候
      return null;
    }
    if (isReference) {
      // is it qualified with any namespace yet?
      if (base.contains(".")) {
        return base;
      }
    } else {
      // is it qualified with this namespace yet?
      if (base.startsWith(currentNamespace + ".")) {
        return base;
      }
      if (base.contains(".")) {
        throw new BuilderException("Dots are not allowed in element names, please remove it from " + base);
      }
    }
    return currentNamespace + "." + base;  // resultMap的id
  }
  
  
  
  // namespace是当前被引用的namespace
  public Cache useCacheRef(String namespace) {
    if (namespace == null) {  // 如果namespace为空，则抛出异常
      throw new BuilderException("cache-ref element requires a namespace attribute.");
    }
    try {
      unresolvedCacheRef = true; // 标识未成功解析Cache引用
      
      // 得到Cache对象
      Cache cache = configuration.getCache(namespace);// 获取namespace对应的Cache对象
      
      if (cache == null) {// 如果cache为空，则抛出IncompleteElementException异常
        throw new IncompleteElementException("No cache for namespace '" + namespace + "' could be found.");
      }
      currentCache = cache;// 记录当前命名空间使用的Cache
      unresolvedCacheRef = false;// 标识已成功解析Cache引用
      return cache;
    } catch (IllegalArgumentException e) {
      throw new IncompleteElementException("No cache for namespace '" + namespace + "' could be found.", e);
    }
  }

  public Cache useNewCache(Class<? extends Cache> typeClass,
      Class<? extends Cache> evictionClass,
      Long flushInterval,
      Integer size,
      boolean readWrite,
      boolean blocking,
      Properties props) {
	  
	  
	// 创建Cache对象，这里使用建造者模式，CacheBuilder是建造者的角色，而Cache是生成的产品
    Cache cache = new CacheBuilder(currentNamespace)
           // 默认情况下返回(CacheBuilder:implementation = PerpetualCache )
        .implementation(valueOrDefault(typeClass, PerpetualCache.class)) 
           // 默认情况下返回(CacheBuilder:implementation = PerpetualCache 
        // decorators = LruCache 只有一个元素)
        .addDecorator(valueOrDefault(evictionClass, LruCache.class))
        // clearInterval = flushInterval
        .clearInterval(flushInterval)
        // size = size
        .size(size)
        // readWrite = readWrite
        .readWrite(readWrite)
        // blocking = blocking
        .blocking(blocking)
        // properties = props
        .properties(props)
        .build();
    // 将Cache对象添加到Configuration.caches集合中保存，其中会将Cache的id作为key，Cache对象本身作为value
    configuration.addCache(cache);
    currentCache = cache;// 记录当前命名空进使用的cache对象
    return cache;
  }

  public ParameterMap addParameterMap(String id, Class<?> parameterClass, List<ParameterMapping> parameterMappings) {
    id = applyCurrentNamespace(id, false);
    ParameterMap parameterMap = new ParameterMap.Builder(configuration, id, parameterClass, parameterMappings).build();
    configuration.addParameterMap(parameterMap);
    return parameterMap;
  }

  public ParameterMapping buildParameterMapping(
      Class<?> parameterType,
      String property,
      Class<?> javaType,
      JdbcType jdbcType,
      String resultMap,
      ParameterMode parameterMode,
      Class<? extends TypeHandler<?>> typeHandler,
      Integer numericScale) {
    resultMap = applyCurrentNamespace(resultMap, true);

    // Class parameterType = parameterMapBuilder.type();
    Class<?> javaTypeClass = resolveParameterJavaType(parameterType, property, javaType, jdbcType);
    TypeHandler<?> typeHandlerInstance = resolveTypeHandler(javaTypeClass, typeHandler);

    return new ParameterMapping.Builder(configuration, property, javaTypeClass)
        .jdbcType(jdbcType)
        .resultMapId(resultMap)
        .mode(parameterMode)
        .numericScale(numericScale)
        .typeHandler(typeHandlerInstance)
        .build();
  }

  /**
   * 
   * @param id  resultMap节点id
   * @param type <resultMap>的type属性，表示结果集将被映射成type指定类型的对象，注意其默认值
   * @param extend  <resultMap>的extends属性
   * @param discriminator  <discriminator>节点 // 返回Discriminator对象
   * @param resultMappings  记录解析的结果(resultMap子节点的属性)
   * @param autoMapping  是否自动映射
   * @return
   */
  public ResultMap addResultMap(
      String id,
      Class<?> type,
      String extend,
      Discriminator discriminator,
      List<ResultMapping> resultMappings,
      Boolean autoMapping) {
	  
	// ResultMap的完整id是"currentnamespace.id"格式
    id = applyCurrentNamespace(id, false);
    
    // 获取被继承的ResultMap的完整id，也就是父ResultMap对象的完整id[currentNamespace.id]
    extend = applyCurrentNamespace(extend, true);

    if (extend != null) { // 针对extend属性的处理
    	
      // 检测Configuration.resultMaps集合中是否存在被继承的ResultMap对象
      if (!configuration.hasResultMap(extend)) {
        throw new IncompleteElementException("Could not find a parent resultmap with id '" + extend + "'");
      }
      // 获取需要被继承的ResultMap对象，也就是父ResultMap对象
      ResultMap resultMap = configuration.getResultMap(extend);
      
      // 获取父ResultMap对象中记录的ResultMapping集合
      List<ResultMapping> extendedResultMappings = new ArrayList<ResultMapping>(resultMap.getResultMappings());
      
      // 删除需要覆盖的ResultMapping集合
      // 在extendedResultMappings删除当前resultMappings含有的resultMappings对象
      
      // 相等的条件resultMappings.property属性相同时，就会被删除
      extendedResultMappings.removeAll(resultMappings);
      
      // Remove parent constructor if this resultMap declares a constructor.
      // 如果当前<resultMap>节点中定义了<constructor>节点，则不需要使用父ResultMap中记录的相应的
      // <constructor>节点，则将其对应的ResultMapping对象删除
      boolean declaresConstructor = false;
      for (ResultMapping resultMapping : resultMappings) {
        if (resultMapping.getFlags().contains(ResultFlag.CONSTRUCTOR)) {
          declaresConstructor = true;  // 看子类中是否包含constructor节点
          break;
        }
      }
      // 当declaresConstructor为true的时候删除，父类的resultMap中constructor节点
      if (declaresConstructor) {
        Iterator<ResultMapping> extendedResultMappingsIter = extendedResultMappings.iterator();
        while (extendedResultMappingsIter.hasNext()) {
          if (extendedResultMappingsIter.next().getFlags().contains(ResultFlag.CONSTRUCTOR)) {
            extendedResultMappingsIter.remove();
          }
        }
      }
      // 添加需要被继承下来的ResultMapping集合(extendedResultMapping已经删除了一部分)
      resultMappings.addAll(extendedResultMappings);
    }
    // 创建ResultMap对象，并添加到Configuration.resultMaps集合中保存
    ResultMap resultMap = new ResultMap.Builder(configuration, id, type, resultMappings, autoMapping)
        .discriminator(discriminator)
        .build();
    configuration.addResultMap(resultMap);
    return resultMap;
  }

  public Discriminator buildDiscriminator(
      Class<?> resultType,
      String column,
      Class<?> javaType,
      JdbcType jdbcType,
      Class<? extends TypeHandler<?>> typeHandler,
      Map<String, String> discriminatorMap) {
	
	// 创建ResultMapping对象
    ResultMapping resultMapping = buildResultMapping(
        resultType,
        null,
        column,
        javaType,
        jdbcType,
        null,
        null,
        null,
        null,
        typeHandler,
        new ArrayList<ResultFlag>(),
        null,
        null,
        false);
    Map<String, String> namespaceDiscriminatorMap = new HashMap<String, String>();
    
    for (Map.Entry<String, String> e : discriminatorMap.entrySet()) {
      String resultMap = e.getValue(); // resultMap的id
      // 将resultMap转换成namespace.resultMap
      resultMap = applyCurrentNamespace(resultMap, true); // resultMap变成能识别的resultMap名字
      namespaceDiscriminatorMap.put(e.getKey(), resultMap);
    }
    return new Discriminator.Builder(configuration, resultMapping, namespaceDiscriminatorMap).build();
  }

  public MappedStatement addMappedStatement(
      String id,
      SqlSource sqlSource,
      StatementType statementType,
      SqlCommandType sqlCommandType,
      Integer fetchSize,
      Integer timeout,
      String parameterMap,
      Class<?> parameterType,
      String resultMap,
      Class<?> resultType,
      ResultSetType resultSetType,
      boolean flushCache,
      boolean useCache,
      boolean resultOrdered,
      KeyGenerator keyGenerator,
      String keyProperty,
      String keyColumn,
      String databaseId,
      LanguageDriver lang,
      String resultSets) {

    if (unresolvedCacheRef) {
      // 抛出异常
      throw new IncompleteElementException("Cache-ref not yet resolved");
    }
    
    // 修饰字符串[namespace.id]
    id = applyCurrentNamespace(id, false);
    boolean isSelect = sqlCommandType == SqlCommandType.SELECT;

    MappedStatement.Builder statementBuilder = new MappedStatement.Builder(configuration, id, sqlSource, sqlCommandType)
        // 设置MappedStatement的resource
    	.resource(resource)
    	// 设置MappedStatement的fetchSize
        .fetchSize(fetchSize)
        // 设置MappedStatement的timeout
        .timeout(timeout)
        // 设置MappedStatement的statementType
        .statementType(statementType)  // STATEMENT, PREPARED, CALLABLE
        // 设置MappedStatement的keyGenerator
        .keyGenerator(keyGenerator)
        // 设置MappedStatement的keyProperty
        .keyProperty(keyProperty)
        // 设置MappedStatement的keyColumn
        .keyColumn(keyColumn)
        // 设置MappedStatement的databaseId
        .databaseId(databaseId)
        // 设置MappedStatement的lang
        .lang(lang)
        // 设置MappedStatement的resultOrdered
        .resultOrdered(resultOrdered)
        // 设置MappedStatement的resultSets
        .resultSets(resultSets)
        // 设置MappedStatement的resultMaps集合
        .resultMaps(getStatementResultMaps(resultMap, resultType, id))
        // 设置MappedStatement的resultSetType
        .resultSetType(resultSetType)
        // 设置MappedStatement的flushCache
        .flushCacheRequired(valueOrDefault(flushCache, !isSelect))
        // 设置MappedStatement的useCache
        .useCache(valueOrDefault(useCache, isSelect))
        // 设置MappedStatement的currentCache
        .cache(currentCache);

    //　获取ParameterMap集合
    ParameterMap statementParameterMap = getStatementParameterMap(parameterMap, parameterType, id);
    if (statementParameterMap != null) { // 当statementParameterMap不为空时
      // 设置MappedStatement的parameterMap
      statementBuilder.parameterMap(statementParameterMap);
    }
    // 生成MappedStatement对象
    MappedStatement statement = statementBuilder.build();
    // 将生成的statement添加到Configuation配置对象中去
    configuration.addMappedStatement(statement);
    return statement;
  }

  private <T> T valueOrDefault(T value, T defaultValue) {
	// 默认情况下为PerpetualCache类
    return value == null ? defaultValue : value;
  }

  private ParameterMap getStatementParameterMap(
      String parameterMapName,
      Class<?> parameterTypeClass,
      String statementId) {
	// 修饰字符串
    parameterMapName = applyCurrentNamespace(parameterMapName, true);
    ParameterMap parameterMap = null;
    if (parameterMapName != null) {
      try {
        parameterMap = configuration.getParameterMap(parameterMapName);
      } catch (IllegalArgumentException e) {
        throw new IncompleteElementException("Could not find parameter map " + parameterMapName, e);
      }
    } else if (parameterTypeClass != null) {
      List<ParameterMapping> parameterMappings = new ArrayList<ParameterMapping>();
      parameterMap = new ParameterMap.Builder(
          configuration,
          statementId + "-Inline",
          parameterTypeClass,
          parameterMappings).build();
    }
    return parameterMap; // 返回parameterMap集合
  }

  private List<ResultMap> getStatementResultMaps(
      String resultMap, // resultMap的id标识
      Class<?> resultType,
      String statementId) {
	  
	 // 修饰字符串namespace.resultMap
    resultMap = applyCurrentNamespace(resultMap, true);
    
    // 用来存储返回ResultMap对象集合
    List<ResultMap> resultMaps = new ArrayList<ResultMap>();
    
    if (resultMap != null) {
      String[] resultMapNames = resultMap.split(","); // 可以配置多个,以,分隔
      for (String resultMapName : resultMapNames) {
        try {
          // 从Configuation中获取ResultMap对象，并添加到resultMaps集合中
          resultMaps.add(configuration.getResultMap(resultMapName.trim()));
        } catch (IllegalArgumentException e) {
          // 抛出异常
          throw new IncompleteElementException("Could not find result map " + resultMapName, e);
        }
      }
    } else if (resultType != null) {
      ResultMap inlineResultMap = new ResultMap.Builder(
          configuration,
          statementId + "-Inline",
          resultType,
          new ArrayList<ResultMapping>(),
          null).build();
      resultMaps.add(inlineResultMap);
    }
    return resultMaps; // 返回resultMap集合
  }
  // MapperBuilderAssistant.buildResultMapping方法的具体实现
  /**
   * 
   * @param resultType resultMap节点的type属性对应的Class字面量
   * @param property  name 或者 property属性值
   * @param column  column属性
   * @param javaType javaType属性
   * @param jdbcType jdbcType属性
   * @param nestedSelect 嵌套查询
   * @param nestedResultMap 嵌套映射id
   * @param notNullColumn
   * @param columnPrefix  列名前缀
   * @param typeHandler 类型处理器
   * @param flags 是否为constructor节点
   * @param resultSet 多结果集
   * @param foreignColumn 外键
   * @param lazy 延迟加载
   * @return
   */
  public ResultMapping buildResultMapping(
      Class<?> resultType,
      String property,
      String column,
      Class<?> javaType,
      JdbcType jdbcType,
      String nestedSelect,
      String nestedResultMap,
      String notNullColumn,
      String columnPrefix,
      Class<? extends TypeHandler<?>> typeHandler,
      List<ResultFlag> flags,
      String resultSet,
      String foreignColumn,
      boolean lazy) {
	  
	// 解析<resultType>节点指定的property属性的类型
    Class<?> javaTypeClass = resolveResultJavaType(resultType, property, javaType);
    
    // 获取typeHandler指定的TypeHandler对象，底层依赖typeHandlerRegisty
    // 获取具体的TypeHandler处理器实例
    TypeHandler<?> typeHandlerInstance = resolveTypeHandler(javaTypeClass, typeHandler);
    
    // 解析column属性值，当column是{prop1=col1,prop2=col2}""形式时，会解析成ResultMapping
    // 对象集合，column的这种形式主要用于嵌套查询的参数传递
    List<ResultMapping> composites = parseCompositeColumnName(column);
    
    // 创建ResultMapping.Bulider对象，创建ResultMapping对象，并设置其字段
    return new ResultMapping.Builder(configuration, property, column, javaTypeClass)
    		// 设置ResultMapping的jdbcType属性
        .jdbcType(jdbcType)
            // 设置ResultMapping的nestedQueryId属性
        .nestedQueryId(applyCurrentNamespace(nestedSelect, true)) // 对嵌套查询id进行处理(字符串修饰)
            // 设置ResultMapping的nestedResultMapId属性
        .nestedResultMapId(applyCurrentNamespace(nestedResultMap, true)) // 对嵌套映射resultMap的id进行处理(字符串修饰[namespace.nestedResultMap]) 
            // 设置ResultMapping的resultSet属性
        .resultSet(resultSet)
            // 设置ResultMapping的typeHandler属性
        .typeHandler(typeHandlerInstance)
            // 设置ResultMapping的flags属性
        .flags(flags == null ? new ArrayList<ResultFlag>() : flags)
            // 设置ResultMapping的composites属性
        .composites(composites)
            // 设置ResultMapping的notNullColumns属性
        .notNullColumns(parseMultipleColumnNames(notNullColumn))
            // 设置ResultMapping的columnPrefix属性
        .columnPrefix(columnPrefix)
            // 设置ResultMapping的foreignColumn属性
        .foreignColumn(foreignColumn)
            // 设置ResultMapping的lazy属性
        .lazy(lazy)
            // 生成最终的ResultMapping对象
        .build();
  }

  private Set<String> parseMultipleColumnNames(String columnName) {
    Set<String> columns = new HashSet<String>();  // 创建HashSet集合对象
    if (columnName != null) {
      if (columnName.indexOf(',') > -1) { 
        StringTokenizer parser = new StringTokenizer(columnName, "{}, ", false);
        while (parser.hasMoreTokens()) {
          String column = parser.nextToken();
          columns.add(column);
        }
      } else {
        columns.add(columnName);
      }
    }
    return columns;
  }

  private List<ResultMapping> parseCompositeColumnName(String columnName) {
    List<ResultMapping> composites = new ArrayList<ResultMapping>();
    if (columnName != null && (columnName.indexOf('=') > -1 || columnName.indexOf(',') > -1)) {
      //  StringTokenizer(String str, String delim, boolean returnDelims) ：
      // 构造一个用来解析str的StringTokenizer对象，并提供一个指定的分隔符，同时，指定是否返回分隔符。
      StringTokenizer parser = new StringTokenizer(columnName, "{}=, ", false);
      // hasMoreElements()：返回是否还有分隔符。
      while (parser.hasMoreTokens()) {
        String property = parser.nextToken();
        String column = parser.nextToken();
        ResultMapping complexResultMapping = new ResultMapping.Builder(
            configuration, property, column, configuration.getTypeHandlerRegistry().getUnknownTypeHandler()).build();
        composites.add(complexResultMapping);
      }
    }
    return composites;
  }

  private Class<?> resolveResultJavaType(Class<?> resultType, String property, Class<?> javaType) {
    if (javaType == null && property != null) {
      try {
        MetaClass metaResultType = MetaClass.forClass(resultType, configuration.getReflectorFactory());
        javaType = metaResultType.getSetterType(property);
      } catch (Exception e) {
        //ignore, following null check statement will deal with the situation
      }
    }
    if (javaType == null) {
      javaType = Object.class;
    }
    return javaType;
  }

  private Class<?> resolveParameterJavaType(Class<?> resultType, String property, Class<?> javaType, JdbcType jdbcType) {
    if (javaType == null) {
      if (JdbcType.CURSOR.equals(jdbcType)) {
        javaType = java.sql.ResultSet.class;
      } else if (Map.class.isAssignableFrom(resultType)) {
        javaType = Object.class;
      } else {
        MetaClass metaResultType = MetaClass.forClass(resultType, configuration.getReflectorFactory());
        javaType = metaResultType.getGetterType(property);
      }
    }
    if (javaType == null) {
      javaType = Object.class;
    }
    return javaType;
  }

  /** Backward compatibility signature */
  public ResultMapping buildResultMapping(
      Class<?> resultType,
      String property,
      String column,
      Class<?> javaType,
      JdbcType jdbcType,
      String nestedSelect,
      String nestedResultMap,
      String notNullColumn,
      String columnPrefix,
      Class<? extends TypeHandler<?>> typeHandler,
      List<ResultFlag> flags) {
      return buildResultMapping(
        resultType, property, column, javaType, jdbcType, nestedSelect,
        nestedResultMap, notNullColumn, columnPrefix, typeHandler, flags, null, null, configuration.isLazyLoadingEnabled());
  }

  public LanguageDriver getLanguageDriver(Class<?> langClass) {
    if (langClass != null) {
      configuration.getLanguageRegistry().register(langClass); // 存入缓存
    } else {
      // 获取默认的defaultDriverClass
      langClass = configuration.getLanguageRegistry().getDefaultDriverClass();
    }
    // 获取LanguageDriver接口的实现类
    return configuration.getLanguageRegistry().getDriver(langClass);
  }

  /** Backward compatibility signature */
  public MappedStatement addMappedStatement(
    String id,
    SqlSource sqlSource,
    StatementType statementType,
    SqlCommandType sqlCommandType,
    Integer fetchSize,
    Integer timeout,
    String parameterMap,
    Class<?> parameterType,
    String resultMap,
    Class<?> resultType,
    ResultSetType resultSetType,
    boolean flushCache,
    boolean useCache,
    boolean resultOrdered,
    KeyGenerator keyGenerator,
    String keyProperty,
    String keyColumn,
    String databaseId,
    LanguageDriver lang) {
    return addMappedStatement(
      id, sqlSource, statementType, sqlCommandType, fetchSize, timeout,
      parameterMap, parameterType, resultMap, resultType, resultSetType,
      flushCache, useCache, resultOrdered, keyGenerator, keyProperty,
      keyColumn, databaseId, lang, null);
  }

}
