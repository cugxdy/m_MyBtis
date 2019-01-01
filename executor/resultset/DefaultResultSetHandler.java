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

import org.apache.ibatis.annotations.AutomapConstructor;
import org.apache.ibatis.binding.MapperMethod.ParamMap;
import org.apache.ibatis.cache.CacheKey;
import org.apache.ibatis.cursor.Cursor;
import org.apache.ibatis.cursor.defaults.DefaultCursor;
import org.apache.ibatis.executor.ErrorContext;
import org.apache.ibatis.executor.Executor;
import org.apache.ibatis.executor.ExecutorException;
import org.apache.ibatis.executor.loader.ResultLoader;
import org.apache.ibatis.executor.loader.ResultLoaderMap;
import org.apache.ibatis.executor.parameter.ParameterHandler;
import org.apache.ibatis.executor.result.DefaultResultContext;
import org.apache.ibatis.executor.result.DefaultResultHandler;
import org.apache.ibatis.executor.result.ResultMapException;
import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.mapping.Discriminator;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.mapping.ParameterMapping;
import org.apache.ibatis.mapping.ParameterMode;
import org.apache.ibatis.mapping.ResultMap;
import org.apache.ibatis.mapping.ResultMapping;
import org.apache.ibatis.reflection.MetaClass;
import org.apache.ibatis.reflection.MetaObject;
import org.apache.ibatis.reflection.ReflectorFactory;
import org.apache.ibatis.reflection.factory.ObjectFactory;
import org.apache.ibatis.session.AutoMappingBehavior;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.ResultContext;
import org.apache.ibatis.session.ResultHandler;
import org.apache.ibatis.session.RowBounds;
import org.apache.ibatis.type.TypeHandler;
import org.apache.ibatis.type.TypeHandlerRegistry;

import java.lang.reflect.Constructor;
import java.sql.CallableStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * @author Clinton Begin
 * @author Eduardo Macarron
 * @author Iwao AVE!
 * @author Kazuki Shimizu
 */
public class DefaultResultSetHandler implements ResultSetHandler {

  private static final Object DEFERED = new Object();
  // 关联的executor、configuration、mappedStatement、rowBounds对象
  private final Executor executor;
  private final Configuration configuration;
  private final MappedStatement mappedStatement;
  private final RowBounds rowBounds;
  
  private final ParameterHandler parameterHandler;
  
  // 用户指定用于处理结果集的ResultHandler对象
  private final ResultHandler<?> resultHandler;
  
  private final BoundSql boundSql; // 执行的SQL语句
  
  private final TypeHandlerRegistry typeHandlerRegistry; // 类型处理器
  private final ObjectFactory objectFactory; // 对象工厂
  private final ReflectorFactory reflectorFactory; // 反射工厂

  // nested resultmaps
  // 当处理嵌套映射时，主结果对象的需要先保存起来，以供后来的结果集处理
  private final Map<CacheKey, Object> nestedResultObjects = new HashMap<CacheKey, Object>();
  
  
  // 将外层对象添加到DefaultResultSetHandler.ancestorObjects(HashMap<String, Object>();)中，其中key为ResultMap的id，value为外层对象
  private final Map<String, Object> ancestorObjects = new HashMap<String, Object>();
  private Object previousRowValue;

  // multiple resultsets // 多结果集
  private final Map<String, ResultMapping> nextResultMaps = new HashMap<String, ResultMapping>();
  // 存放多结果集对应的集合
  private final Map<CacheKey, List<PendingRelation>> pendingRelations = new HashMap<CacheKey, List<PendingRelation>>();

  // Cached Automappings 
  private final Map<String, List<UnMappedColumnAutoMapping>> autoMappingsCache = new HashMap<String, List<UnMappedColumnAutoMapping>>();

  // temporary marking flag that indicate using constructor mapping (use field to reduce memory usage)
  private boolean useConstructorMappings;

  private final PrimitiveTypes primitiveTypes;

  private static class PendingRelation {
    public MetaObject metaObject;  // 当前主结果
    public ResultMapping propertyMapping;// 多结果集映射对应的<association>节点 也就是ResultMapping节点
  }

  private static class UnMappedColumnAutoMapping {
    private final String column;
    private final String property;
    private final TypeHandler<?> typeHandler;
    private final boolean primitive;

    public UnMappedColumnAutoMapping(String column, String property, TypeHandler<?> typeHandler, boolean primitive) {
      this.column = column;
      this.property = property;
      this.typeHandler = typeHandler;
      this.primitive = primitive;
    }
  }

  public DefaultResultSetHandler(Executor executor, MappedStatement mappedStatement, ParameterHandler parameterHandler, ResultHandler<?> resultHandler, BoundSql boundSql,
                                 RowBounds rowBounds) {
    this.executor = executor;
    this.configuration = mappedStatement.getConfiguration();
    this.mappedStatement = mappedStatement;
    this.rowBounds = rowBounds;
    this.parameterHandler = parameterHandler;
    this.boundSql = boundSql;
    this.typeHandlerRegistry = configuration.getTypeHandlerRegistry();
    this.objectFactory = configuration.getObjectFactory();
    this.reflectorFactory = configuration.getReflectorFactory();
    this.resultHandler = resultHandler;
    this.primitiveTypes = new PrimitiveTypes();
  }

  //
  // HANDLE OUTPUT PARAMETER
  //

  @Override
  public void handleOutputParameters(CallableStatement cs) throws SQLException {
    // 获取用户传入的实际参数，并为其创建相应的MetaObject对象
	final Object parameterObject = parameterHandler.getParameterObject();
    final MetaObject metaParam = configuration.newMetaObject(parameterObject);
    // 获取BoundSql.parameterMappings集合，其中记录了参数相关信息，
    final List<ParameterMapping> parameterMappings = boundSql.getParameterMappings();
    
    // 遍历所有参数信息
    for (int i = 0; i < parameterMappings.size(); i++) {
      final ParameterMapping parameterMapping = parameterMappings.get(i);
      // 如果存在输出类型的参数，则解析参数值，并设置到parameterObject中
      if (parameterMapping.getMode() == ParameterMode.OUT || parameterMapping.getMode() == ParameterMode.INOUT) {
        if (ResultSet.class.equals(parameterMapping.getJavaType())) {
          // 如果指定该输出参数为ResultSet类型，则需要进行映射
          handleRefCursorOutputParameter((ResultSet) cs.getObject(i + 1), parameterMapping, metaParam);
        } else {
          // 使用TypeHandler获取参数值，并设置到parameterObject中
          final TypeHandler<?> typeHandler = parameterMapping.getTypeHandler();
          metaParam.setValue(parameterMapping.getProperty(), typeHandler.getResult(cs, i + 1));
        }
      }
    }
  }

  private void handleRefCursorOutputParameter(ResultSet rs, ParameterMapping parameterMapping, MetaObject metaParam) throws SQLException {
    if (rs == null) {
      return;
    }
    try {
      // 获取映射使用ResultMap对象
      final String resultMapId = parameterMapping.getResultMapId();
      final ResultMap resultMap = configuration.getResultMap(resultMapId);
      // 将结果集封装成ResultSetWrapper
      final ResultSetWrapper rsw = new ResultSetWrapper(rs, configuration);
      if (this.resultHandler == null) {
    	// 创建用于保存映射结果对象的DefaultResultHandler对象
        final DefaultResultHandler resultHandler = new DefaultResultHandler(objectFactory);
        // 通过handleRowValues()方法完成映射操作，并将结果对象保存到DefaultResultHandler中
        handleRowValues(rsw, resultMap, resultHandler, new RowBounds(), null);
        //　将映射得到的结果对象保存到parameterObject对象
        metaParam.setValue(parameterMapping.getProperty(), resultHandler.getResultList());
      } else {
        handleRowValues(rsw, resultMap, resultHandler, new RowBounds(), null);
      }
    } finally {
      // issue #228 (close resultsets)
      closeResultSet(rs);
    }
  }

  //
  // HANDLE RESULT SETS
  //
  @Override
  public List<Object> handleResultSets(Statement stmt) throws SQLException {
	    
/*	 
 * 	 BEGIN (具体的执行SQL语句)
 *       select * from BLOG where id = ID;
 *       select * from ATTHOR where id = ID;
 *   END
 *   <select id="selectBlog" resultsets="bolgs,authors" resultMap="blogResult" 
 *     statementType="CALLABLE">
 *       // 调用存储过程，执行SQL语句(返回两个结果集)
 *       {call get_blogs_and_authors(#{id,jdbcType=INTEGER,mode=IN})}
 *   <select>
 *   
 *   <result id="blogResult" type="Blog">
 *   	<constructor>
 *          <idArg column="id" javaType="int">
 *   	</constructor>
 *   	<result property="title" column="title">
 *      // 嵌套映射，其中resultSet属性指向了第二个结果集
 *      // resultMap对象
 *   	<association property="author" javaType="Author" resultSet="authors" 
 *   		column="author_id" foreignColumn="id">
 *   		<id proerty="id" column="id" />
 *   		<result property="username" column="username" />
 *   		<result property="password" column="password" />
 *   		<result property="email" column="email">
 *   	</association>
 *   </result>
 * 
 */
	// 在执行完SQL语句之后去开始执行结果集的映射
    ErrorContext.instance().activity("handling results").object(mappedStatement.getId());

    // 该集合用于保存映射结果集得到的结果对象
    final List<Object> multipleResults = new ArrayList<Object>();

    int resultSetCount = 0;
    
    // 获取第一个ResultSet对象，正如前面所说，可能存在多个ResultSet，这里只获得第一个ResultSet
    ResultSetWrapper rsw = getFirstResultSet(stmt);

    // 获取MappedStatement.resultMaps集合，前面分析过Mybatis初始化介绍过，映射文件中<resultMap>节点
    // 会被解析成ResultMap对象，保存到MappedStatement.resultMaps集合中，如果SQL节点能够产生多个ResultSet
    // 那么我们可以在SQL节点的resultMap属性中配置多个<resultMap>节点的id，它们之间","分隔，实现对多个结果集的映射
    List<ResultMap> resultMaps = mappedStatement.getResultMaps();
    
    // resultMaps集合的个数
    int resultMapCount = resultMaps.size();
    validateResultMapsCount(rsw, resultMapCount);// 如果结果集不为空，则resultMaps集合不能为空，否则抛出异常
    
    // 调用handleResultSet方法循环处理结果集
    // resultMapCount = 1; resultSetCount = 0; 
    while (rsw != null && resultMapCount > resultSetCount) { // (1) 遍历resultMaps集合
      // 获取结果集对应的ResultMap对象
      ResultMap resultMap = resultMaps.get(resultSetCount);
      
      // 根据ResultMap中定义的映射规则对ResultSet进行映射，并将映射的结果对象添加到multipleResults集合中保存
      // 依次调用ResultMap对象对ResultSet对象 (循环进行处理)
      handleResultSet(rsw, resultMap, multipleResults, null);
      
      rsw = getNextResultSet(stmt); // 获取下一个结果集
      
      
      // 清空存放主结果对象的集合
      cleanUpAfterHandlingResultSet();// 清空nestedResultObjects集合
      resultSetCount++;// 递增resultSetCount
    }
    
    // 获取MappedStatement.resultSets集合。该属性仅对多结果集的情况适用，该属性将列出语句执行后返回的结果集
    // 并给每个结果集一个名称，名称是逗号分隔的!
    // 这里会根据ResultSet的名称处理嵌套映射
    String[] resultSets = mappedStatement.getResultSets();
    if (resultSets != null) {
      // resultMapCount = 1; resultSets.length = 2; 
      while (rsw != null && resultSetCount < resultSets.length) { // (2)
    	  
    	// 根据ResultSet的名称，获取未处理的ResultMapping
        ResultMapping parentMapping = nextResultMaps.get(resultSets[resultSetCount]);
        if (parentMapping != null) {
        	
          // 嵌套映射的处理 
          String nestedResultMapId = parentMapping.getNestedResultMapId();
          ResultMap resultMap = configuration.getResultMap(nestedResultMapId);
          // 根据ResultMap对象映射结果集
          handleResultSet(rsw, resultMap, null, parentMapping);
          
        }
        rsw = getNextResultSet(stmt); // 获取下一个结果集
        cleanUpAfterHandlingResultSet();// 清空nestedResultObjects集合
        resultSetCount++;// 递增resultSetCount
      }
    }

    return collapseSingleResultList(multipleResults);
  }
 
  // 它是在数据库查询之后，将结果集对应的ResultSetWrapper对应以及映射使用的ResultMap对象封装成
  // DefaultCursor对象并返回
  @Override
  public <E> Cursor<E> handleCursorResultSets(Statement stmt) throws SQLException {
    ErrorContext.instance().activity("handling cursor results").object(mappedStatement.getId());
    //　获取结果集并封装成ResultSetWrapper对象
    ResultSetWrapper rsw = getFirstResultSet(stmt);
    // 获取映射使用的ResultMap对象集合
    List<ResultMap> resultMaps = mappedStatement.getResultMaps();
    
    // 边界检测，只能映射一个结果集，所以存在一个ResultMap对象
    int resultMapCount = resultMaps.size();
    validateResultMapsCount(rsw, resultMapCount);
    if (resultMapCount != 1) {
      throw new ExecutorException("Cursor results cannot be mapped to multiple resultMaps");
    }

    //　使用第一个ResultMap对象
    ResultMap resultMap = resultMaps.get(0);
    // 将ResultSetWrapper对象、映射使用的ResultMap对象以及控制映射起始位置的rowBounds对象
    // 封装成DefaultCursor对象
    return new DefaultCursor<E>(this, resultMap, rsw, rowBounds);
  }

  private ResultSetWrapper getFirstResultSet(Statement stmt) throws SQLException {
    ResultSet rs = stmt.getResultSet(); // 获取ResultSet对象
    while (rs == null) {
      // move forward to get the first resultset in case the driver
      // doesn't return the resultset as the first result (HSQLDB 2.1)
      if (stmt.getMoreResults()) { // 检测是否还有待处理的ResultSet
        rs = stmt.getResultSet();
      } else {
        if (stmt.getUpdateCount() == -1) { // 没有待处理的ResultSet对象
          // no more results. Must be no resultset
          break;
        }
      }
    }
    // 将结果集封装成ResultSetWrapper对象
    return rs != null ? new ResultSetWrapper(rs, configuration) : null;
  }

  private ResultSetWrapper getNextResultSet(Statement stmt) throws SQLException {
    // Making this method tolerant of bad JDBC drivers
    try { // 检测JDBC是否支持多结果集
      if (stmt.getConnection().getMetaData().supportsMultipleResultSets()) {
        // Crazy Standard JDBC way of determining if there are more results
    	// 检测是否还有待处理的结果集，若存在，则封装成ResultSetWrapper对象返回
        if (!(!stmt.getMoreResults() && stmt.getUpdateCount() == -1)) {
          ResultSet rs = stmt.getResultSet();
          if (rs == null) {
            return getNextResultSet(stmt);
          } else {
            return new ResultSetWrapper(rs, configuration);
          }
        }
      }
    } catch (Exception e) {
      // Intentionally ignored.
    }
    return null;
  }

  private void closeResultSet(ResultSet rs) {
    try {
      if (rs != null) {
        rs.close();
      }
    } catch (SQLException e) {
      // ignore
    }
  }

  private void cleanUpAfterHandlingResultSet() {
    nestedResultObjects.clear();
  }

  private void validateResultMapsCount(ResultSetWrapper rsw, int resultMapCount) {
	// 对结果集与resultMap进行检验
    if (rsw != null && resultMapCount < 1) {
      throw new ExecutorException("A query was run and no Result Maps were found for the Mapped Statement '" + mappedStatement.getId()
          + "'.  It's likely that neither a Result Type nor a Result Map was specified.");
    }
  }

  private void handleResultSet(ResultSetWrapper rsw, ResultMap resultMap, List<Object> multipleResults, ResultMapping parentMapping) throws SQLException {
    try {
      if (parentMapping != null) {
    	// 处理多结果的映射嵌套映射，就是在此处实现映射的
        handleRowValues(rsw, resultMap, null, RowBounds.DEFAULT, parentMapping);
      } else { 
        if (resultHandler == null) {
        	
          // 如果用户未指定处理映射结果对象的ResultHandler，则使用DefaultResultHandler作为默认的ResultHandler对象
          DefaultResultHandler defaultResultHandler = new DefaultResultHandler(objectFactory);
          
          // 对ResultSet进行映射，并将映射得到的结果对象添加到DefaultResultHandler对象暂存
          handleRowValues(rsw, resultMap, defaultResultHandler, rowBounds, null);
          
          // 将DefaultResultHandler中保存的结果对象添加到multipleResults集合中
          multipleResults.add(defaultResultHandler.getResultList());
          
        } else {
          // 使用用户指定的ResultHandler对象处理结果对象
          handleRowValues(rsw, resultMap, resultHandler, rowBounds, null);
        }
      }
    } finally {
      // issue #228 (close resultsets)
      closeResultSet(rsw.getResultSet()); // 调用ResultSet.close()方法关闭结果集
    }
  }

  @SuppressWarnings("unchecked")
  private List<Object> collapseSingleResultList(List<Object> multipleResults) {
    return multipleResults.size() == 1 ? (List<Object>) multipleResults.get(0) : multipleResults;
  }

  //
  // HANDLE ROWS FOR SIMPLE RESULTMAP
  //

  public void handleRowValues(ResultSetWrapper rsw, ResultMap resultMap, ResultHandler<?> resultHandler, RowBounds rowBounds, ResultMapping parentMapping) throws SQLException {
    // 嵌套映射
	if (resultMap.hasNestedResultMaps()) { // 针对存在嵌套ResultMap的情况
      ensureNoRowBounds(); // 检测是否允许在嵌套映射中使用RowBounds
      checkResultHandler();// 检测是否允许在嵌套映射中使用用户自定义的ResultHandler
      // 
      handleRowValuesForNestedResultMap(rsw, resultMap, resultHandler, rowBounds, parentMapping);
    } else {
      // 针对不含嵌套映射的简单映射的处理
      handleRowValuesForSimpleResultMap(rsw, resultMap, resultHandler, rowBounds, parentMapping);
    }
  }

  private void ensureNoRowBounds() {
    if (configuration.isSafeRowBoundsEnabled() && rowBounds != null && (rowBounds.getLimit() < RowBounds.NO_ROW_LIMIT || rowBounds.getOffset() > RowBounds.NO_ROW_OFFSET)) {
      throw new ExecutorException("Mapped Statements with nested result mappings cannot be safely constrained by RowBounds. "
          + "Use safeRowBoundsEnabled=false setting to bypass this check.");
    }
  }

  protected void checkResultHandler() {
    if (resultHandler != null && configuration.isSafeResultHandlerEnabled() && !mappedStatement.isResultOrdered()) {
      throw new ExecutorException("Mapped Statements with nested result mappings cannot be safely used with a custom ResultHandler. "
          + "Use safeResultHandlerEnabled=false setting to bypass this check "
          + "or ensure your statement returns ordered data and set resultOrdered=true on it.");
    }
  }
  // 处理简单映射的方法
  private void handleRowValuesForSimpleResultMap(ResultSetWrapper rsw, ResultMap resultMap, ResultHandler<?> resultHandler, RowBounds rowBounds, ResultMapping parentMapping)
      throws SQLException {
	  
	// 默认上下文对象
    DefaultResultContext<Object> resultContext = new DefaultResultContext<Object>();
    
    // 步骤一:根据rowBounds中的位置定位到指定的一行记录
    skipRows(rsw.getResultSet(), rowBounds); // 第一个结果对象所对应的行数
    
    // 步骤二:检测已经处理的行数是否已经达到上限(RowBounds.limit)以及ResultSet中是否还有可处理的记录
    
    // 对结果集进行多次映射，因为结果集可能存在多行数据
    // resultContext中存放着临时的结果对象，以及保存着对返回结果对象的计数
    while (shouldProcessMoreRows(resultContext, rowBounds) && rsw.getResultSet().next()) {
      
      // 步骤三:根据该行记录以及ResultMap.discriminator，决定映射使用的ResultMap
      ResultMap discriminatedResultMap = resolveDiscriminatedResultMap(rsw.getResultSet(), resultMap, null);
      
      // 步骤四:根据最终确定的ResultMap对ResultSet中的该行记录进行映射，得到映射后的结果对象
      Object rowValue = getRowValue(rsw, discriminatedResultMap);
      
      // 步骤五:将映射创建的结果对象添加到ResultHandler.resultList中保存
      storeObject(resultHandler, resultContext, rowValue, parentMapping, rsw.getResultSet());
    }
  }

  private void storeObject(ResultHandler<?> resultHandler, DefaultResultContext<Object> resultContext, Object rowValue, ResultMapping parentMapping, ResultSet rs) throws SQLException {
    if (parentMapping != null) {
    	
      // 嵌套查询或嵌套映射，将结果对象保存到父对象对应的属性中去。
      linkToParents(rs, parentMapping, rowValue);
    } else {
      // 普通映射，将结果对象保存到ResultHandler中
      callResultHandler(resultHandler, resultContext, rowValue);
    }
  }

  @SuppressWarnings("unchecked" /* because ResultHandler<?> is always ResultHandler<Object>*/)
  private void callResultHandler(ResultHandler<?> resultHandler, DefaultResultContext<Object> resultContext, Object rowValue) {
    
	// 递增DefaultResultContext.resultCount，该值用于检测处理的记录行数是否已经达到上限
    // (在RowBounds.limit字段中记录了该上限).之后将结果对象保存到
	resultContext.nextResultObject(rowValue);
    
	// 将结果对象添加到ResultHandler.resultList中保存DefaultResultContext.resultResult字段中
    ((ResultHandler<Object>) resultHandler).handleResult(resultContext);
  }
  
  // 它检测是否能够对后续的记录进行映射操作
  private boolean shouldProcessMoreRows(ResultContext<?> context, RowBounds rowBounds) throws SQLException {
    // 一个检测DefaultResultContext.stopped字段，另一个检测映射行数是否达到了RowBounds.limit的限制
	return !context.isStopped() && context.getResultCount() < rowBounds.getLimit();
  }
  
  // 它会依据RowBounds.offset字段的值定位到指定的记录
  private void skipRows(ResultSet rs, RowBounds rowBounds) throws SQLException {
    // 根据ResultSet的类型进行定位
	if (rs.getType() != ResultSet.TYPE_FORWARD_ONLY) {
      if (rowBounds.getOffset() != RowBounds.NO_ROW_OFFSET) {
        rs.absolute(rowBounds.getOffset()); // 直接定位到offset指定的记录
      }
    } else {
      // 通过多次调用ResultSet.next方法移动到指定的记录
      for (int i = 0; i < rowBounds.getOffset(); i++) {
        rs.next();
      }
    }
  }

  //
  // GET VALUE FROM ROW FOR SIMPLE RESULT MAP
  //
  private Object getRowValue(ResultSetWrapper rsw, ResultMap resultMap) throws SQLException {
    
	  // ResultLoaderMap与延迟加载有关
	final ResultLoaderMap lazyLoader = new ResultLoaderMap();
	
    // 步骤一:创建该行记录映射之后得到的结果对象，该结果对象的类型由<ResultMap>节点的type类型决定
    Object rowValue = createResultObject(rsw, resultMap, lazyLoader, null);
    
    // 结果对象不为空，
    if (rowValue != null && !hasTypeHandlerForResultObject(rsw, resultMap.getType())) {
      
      // 创建上述结果对象相应的MetaObject对象，MetaObject存储着对rowValue操作的相应方法
      final MetaObject metaObject = configuration.newMetaObject(rowValue);
      
      // 成功映射任意属性，则foundValues为true，否则foundValues为false
      boolean foundValues = this.useConstructorMappings;
      if (shouldApplyAutomaticMappings(resultMap, false)) { // 检测是否需要进行自动映射
    	  
    	// 步骤二:自动映射ResultMap中未明确指定的列
        foundValues = applyAutomaticMappings(rsw, resultMap, metaObject, null) || foundValues;
      }
      
      // 步骤三:映射ResultMap中明确指定需要映射的列
      foundValues = applyPropertyMappings(rsw, resultMap, metaObject, lazyLoader, null) || foundValues;
      foundValues = lazyLoader.size() > 0 || foundValues;
      
      // 步骤四:如果没有成功映射任何属性，则根据mybatis-config.xml中的<ReturnInstanceForEmptyRow>
      // 配置决定返回空的结果对象还是null
      rowValue = foundValues || configuration.isReturnInstanceForEmptyRow() ? rowValue : null;
    }
    return rowValue;
  }

  private boolean shouldApplyAutomaticMappings(ResultMap resultMap, boolean isNested) {
    if (resultMap.getAutoMapping() != null) { // 获取ResultMap中的autoMapping属性
      return resultMap.getAutoMapping();
    } else {
      if (isNested) { // 检测是否为嵌套查询还是嵌套映射
        return AutoMappingBehavior.FULL == configuration.getAutoMappingBehavior();
      } else {
        return AutoMappingBehavior.NONE != configuration.getAutoMappingBehavior();
      }
    }
  }

  //
  // PROPERTY MAPPINGS
  //

  private boolean applyPropertyMappings(ResultSetWrapper rsw, ResultMap resultMap, MetaObject metaObject, ResultLoaderMap lazyLoader, String columnPrefix)
      throws SQLException {
	  
	// 获取ResultMap中明确需要进行映射的列名集合
    final List<String> mappedColumnNames = rsw.getMappedColumnNames(resultMap, columnPrefix);
    
    boolean foundValues = false;
    
    // 获取ResultMap.PropertyResultMappings集合，其中记录了映射使用的所有的ResultMapping对象
    // 该集合填充过程，
    // 记录了映射关系不带有constructor标志的映射关系
    final List<ResultMapping> propertyMappings = resultMap.getPropertyResultMappings();
    
    for (ResultMapping propertyMapping : propertyMappings) {
      // 处理列前缀
      String column = prependPrefix(propertyMapping.getColumn(), columnPrefix);
      if (propertyMapping.getNestedResultMapId() != null) {
        // the user added a column attribute to a nested result map, ignore it
    	// 该属性需要使用一个嵌套ResultMap进行映射，忽略column属性
        column = null;
      }
      // 下面的逻辑主要处理三种情况
      // (1)column是"{prop1=col1,prop2=col2}"这种形式的，一般与嵌套查询配合使用，表示将col1和col2
      // 的列值传递给内层嵌套查询作为参数
      // (2)基本类型的属性映射
      // (3)多结果集的场景处理，该属性来自另一结果集
      if (propertyMapping.isCompositeResult() // (1)
    	  // (2)
          || (column != null && mappedColumnNames.contains(column.toUpperCase(Locale.ENGLISH)))
          || propertyMapping.getResultSet() != null) { // (3)
        // 通过getPropertyMappingValue()方法完成映射，并得到属性值
    	Object value = getPropertyMappingValue(rsw.getResultSet(), metaObject, propertyMapping, lazyLoader, columnPrefix);
        
    	// issue #541 make property optional
        final String property = propertyMapping.getProperty();
        if (property == null) {
          continue;
        } else if (value == DEFERED) {
          // DEFERED表示的占位符对象。
          foundValues = true;
          continue;
        }
        if (value != null) {
          foundValues = true;
        }
        if (value != null || (configuration.isCallSettersOnNulls() && !metaObject.getSetterType(property).isPrimitive())) {
          // gcode issue #377, call setter on nulls (value is not 'found')
          metaObject.setValue(property, value); // 设置属性值
        }
      }
    }
    return foundValues;
  }

  private Object getPropertyMappingValue(ResultSet rs, MetaObject metaResultObject, ResultMapping propertyMapping, ResultLoaderMap lazyLoader, String columnPrefix)
      throws SQLException {
	  
    if (propertyMapping.getNestedQueryId() != null) {
      // 嵌套查询
      return getNestedQueryMappingValue(rs, metaResultObject, propertyMapping, lazyLoader, columnPrefix);
    } else if (propertyMapping.getResultSet() != null) {
      // 多结果集的处理
      addPendingChildRelation(rs, metaResultObject, propertyMapping);   // TODO is that OK?
      return DEFERED; // 返回占位符对象
    } else {
      // 获取ResultMapping中记录的TypeHandler对象
      final TypeHandler<?> typeHandler = propertyMapping.getTypeHandler();
      final String column = prependPrefix(propertyMapping.getColumn(), columnPrefix);
      // 使用TypeHandler对象获取属性的值
      return typeHandler.getResult(rs, column);
    }
  }

  private List<UnMappedColumnAutoMapping> createAutomaticMappings(ResultSetWrapper rsw, ResultMap resultMap, MetaObject metaObject, String columnPrefix) throws SQLException {
    final String mapKey = resultMap.getId() + ":" + columnPrefix; // 自动映射的缓存key
    List<UnMappedColumnAutoMapping> autoMapping = autoMappingsCache.get(mapKey);
    if (autoMapping == null) { // autoMappingsCache未命中
      autoMapping = new ArrayList<UnMappedColumnAutoMapping>();
      // 从ResultSetWrapper中获取未映射列名集合
      final List<String> unmappedColumnNames = rsw.getUnmappedColumnNames(resultMap, columnPrefix);
      for (String columnName : unmappedColumnNames) {
        String propertyName = columnName;  // 生成属性名称
        if (columnPrefix != null && !columnPrefix.isEmpty()) {
          // When columnPrefix is specified,
          // ignore columns without the prefix.
          // 如果列名以列前缀开头，则属性名称为列名去除前缀删除的部分。如果指定了列前缀，但
          // 列名没有以前缀开头，则跳过该列处理后面的列
          if (columnName.toUpperCase(Locale.ENGLISH).startsWith(columnPrefix)) {
            propertyName = columnName.substring(columnPrefix.length());
          } else {
            continue;
          }
        }
        // 从结果对象查找指定的属性名
        final String property = metaObject.findProperty(propertyName, configuration.isMapUnderscoreToCamelCase());
        // 检测是否存在该属性的setter方法，注意:如果是MapWrapper接口，一直返回true
        if (property != null && metaObject.hasSetter(property)) {
          if (resultMap.getMappedProperties().contains(property)) {
            continue;
          }
          final Class<?> propertyType = metaObject.getSetterType(property);
          if (typeHandlerRegistry.hasTypeHandler(propertyType, rsw.getJdbcType(columnName))) {
            // 查找对应的TypeHandler对象
        	final TypeHandler<?> typeHandler = rsw.getTypeHandler(propertyType, columnName);
            // 创建UnMappedColumnAutoMapping对象，并添加到autoMapping集合中
        	autoMapping.add(new UnMappedColumnAutoMapping(columnName, property, typeHandler, propertyType.isPrimitive()));
          } else {
            configuration.getAutoMappingUnknownColumnBehavior()
                .doAction(mappedStatement, columnName, property, propertyType);
          }
        } else {
          configuration.getAutoMappingUnknownColumnBehavior()
              .doAction(mappedStatement, columnName, (property != null) ? property : propertyName, null);
        }
      }
      autoMappingsCache.put(mapKey, autoMapping);  // 将autoMapping添加到缓存中保存
    }
    return autoMapping;
  }
  // 该方法主要负责自动映射ResultMap中未明确映射的列
  private boolean applyAutomaticMappings(ResultSetWrapper rsw, ResultMap resultMap, MetaObject metaObject, String columnPrefix) throws SQLException {
    // 获取ResultSet存在，但ResultSet中没有明确映射的列所对应的UnMappedColumnAutoMapping集合，
	// 如果HashMap中设置的returnType为java.util.HashMap的话，则全部的列都会在这里获取到
	List<UnMappedColumnAutoMapping> autoMapping = createAutomaticMappings(rsw, resultMap, metaObject, columnPrefix);
    boolean foundValues = false;
    if (!autoMapping.isEmpty()) {
      for (UnMappedColumnAutoMapping mapping : autoMapping) { // 遍历autoMapping集合
    	// 使用TyepHandler获取自动映射的列值
        final Object value = mapping.typeHandler.getResult(rsw.getResultSet(), mapping.column);
        if (value != null) { // 边界检测，更新foundValues操作
          foundValues = true;
        }
        if (value != null || (configuration.isCallSettersOnNulls() && !mapping.primitive)) {
          // gcode issue #377, call setter on nulls (value is not 'found')
          metaObject.setValue(mapping.property, value); // 将自动映射的属性值设置到结果对象中去
        }
      }
    }
    return foundValues;
  }

  // MULTIPLE RESULT SETS

  private void linkToParents(ResultSet rs, ResultMapping parentMapping, Object rowValue) throws SQLException {
    // 缓存CacheKey对象，注意这里构成CacheKey的第三部分，它换成了外键的值，
	CacheKey parentKey = createKeyForMultipleResults(rs, parentMapping, parentMapping.getColumn(), parentMapping.getForeignColumn());
    //　获取PendingRelation集合中parentKey对应的PendingRelation对象
	List<PendingRelation> parents = pendingRelations.get(parentKey);
    if (parents != null) {
      for (PendingRelation parent : parents) {
        if (parent != null && rowValue != null) {
          // 将当前记录的结果添加到外层对象的相应属性中
          linkObjects(parent.metaObject, parent.propertyMapping, rowValue);
        }
      }
    }
  }

  private void addPendingChildRelation(ResultSet rs, MetaObject metaResultObject, ResultMapping parentMapping) throws SQLException {
    
	//　步骤1:为指定结果集创建CacheKey对象
	CacheKey cacheKey = createKeyForMultipleResults(rs, parentMapping, parentMapping.getColumn(), parentMapping.getColumn());
    
	// 步骤2:创建PendingRelation对象
	PendingRelation deferLoad = new PendingRelation();
    deferLoad.metaObject = metaResultObject;
    deferLoad.propertyMapping = parentMapping;
    
    // 步骤3:将PendingRelation对象添加到pendingRelations集合中缓存保存
    List<PendingRelation> relations = pendingRelations.get(cacheKey);
    // issue #255
    if (relations == null) {
      // 创建ArrayList对象
      relations = new ArrayList<DefaultResultSetHandler.PendingRelation>();
      pendingRelations.put(cacheKey, relations);
    }
    relations.add(deferLoad);
    // 步骤4:在nextResultMaps集合记录指定属性对应的结果集名称以及对应的ResultMapping对象
    // 获取指定的结果集，并将其添加到nextResultMaps集合中
    ResultMapping previous = nextResultMaps.get(parentMapping.getResultSet());
    if (previous == null) {
      nextResultMaps.put(parentMapping.getResultSet(), parentMapping);
    } else {
      // 如果同名的结果集对应不同的ResultMapping，则抛出异常
      if (!previous.equals(parentMapping)) {
        throw new ExecutorException("Two different properties are mapped to the same resultSet");
      }
    }
  }

  private CacheKey createKeyForMultipleResults(ResultSet rs, ResultMapping resultMapping, String names, String columns) throws SQLException {
    CacheKey cacheKey = new CacheKey();
    cacheKey.update(resultMapping); // 添加ResultMapping
    if (columns != null && names != null) {
      // 按照逗号切分列名
      String[] columnsArray = columns.split(",");
      String[] namesArray = names.split(",");
      for (int i = 0; i < columnsArray.length; i++) {
    	// 查询该行记录对应列的值
        Object value = rs.getString(columnsArray[i]);
        if (value != null) {
          cacheKey.update(namesArray[i]); // 添加列名和列值
          cacheKey.update(value);
        }
      }
    }
    return cacheKey;
  }

  //
  // INSTANTIATION & CONSTRUCTOR MAPPING
  //

  private Object createResultObject(ResultSetWrapper rsw, ResultMap resultMap, ResultLoaderMap lazyLoader, String columnPrefix) throws SQLException {
    
	this.useConstructorMappings = false; // 标识是否使用构造函数创建该结果对象
    // 记录构造函数的参数类型
    final List<Class<?>> constructorArgTypes = new ArrayList<Class<?>>();
    
    // 记录构造函数实参
    final List<Object> constructorArgs = new ArrayList<Object>();
    
    // 创建该行记录对应的结果对象，该方法是该步骤的核心
    Object resultObject = createResultObject(rsw, resultMap, constructorArgTypes, constructorArgs, columnPrefix);
    
    if (resultObject != null && !hasTypeHandlerForResultObject(rsw, resultMap.getType())) {
      // propertyResultMappings记录了映射关系不带有constructor标志的映射关系
      final List<ResultMapping> propertyMappings = resultMap.getPropertyResultMappings();
      for (ResultMapping propertyMapping : propertyMappings) {
        // issue gcode #109 && issue #149
    	// 嵌套查询  (当用时才进行查询 )        、、、、、、设置了延迟加载
        if (propertyMapping.getNestedQueryId() != null && propertyMapping.isLazy()) {
          // 如果包含嵌套查询，且配置了延迟加载，则创建代理对象// 利用动态代理机制创建代理对象
          resultObject = configuration.getProxyFactory().createProxy(resultObject, lazyLoader, configuration, objectFactory, constructorArgTypes, constructorArgs);
          break;
        }
      }
    }
    //记录是否使用构造函数
    this.useConstructorMappings = resultObject != null && !constructorArgTypes.isEmpty(); // set current mapping result
    return resultObject;
  }

  private Object createResultObject(ResultSetWrapper rsw, ResultMap resultMap, List<Class<?>> constructorArgTypes, List<Object> constructorArgs, String columnPrefix)
      throws SQLException {
	// 获取ResultMap中记录的type属性，也就是该行记录最终映射成的结果对象
    final Class<?> resultType = resultMap.getType();
    
    // 创建该类型的MetaClass对象
    final MetaClass metaType = MetaClass.forClass(resultType, reflectorFactory);
    
    // 获取ResultMap中记录的<constructor>节点信息，如果该集合不为空，则可以通过该集合确定相应Java类中的
    // 唯一构造函数 // 当映射对象中配置了构造函数时，可通过<constructor>节点配置相应的构造函数
    final List<ResultMapping> constructorMappings = resultMap.getConstructorResultMappings();
    // 创建结果对象分为四种情况
      // 结果集只有一列，且存在TypeHandler对象可以将该列转换成resultType类型的值
    if (hasTypeHandlerForResultObject(rsw, resultType)) {
      // 一:先查找相应的TypeHandler对象，在使用TypeHandler对象将该记录转换成Java类型的值
      return createPrimitiveResultObject(rsw, resultMap, columnPrefix);
    } else if (!constructorMappings.isEmpty()) {
      // 二:ResultMap中记录<constructor>节点信息，则通过反射方式调用构造方法，创建结果对象
      return createParameterizedResultObject(rsw, resultType, constructorMappings, constructorArgTypes, constructorArgs, columnPrefix);
    } else if (resultType.isInterface() || metaType.hasDefaultConstructor()) {
      // 三:使用的默认无参构造函数，则直接使用ObjectFactory创建对象
      return objectFactory.create(resultType);
    } else if (shouldApplyAutomaticMappings(resultMap, false)) {
    	
      // 四:通过自动映射的方式查找合适的构造方法并创建结果对象
      return createByConstructorSignature(rsw, resultType, constructorArgTypes, constructorArgs, columnPrefix);
    }
    // 初始化失败，抛出异常
    throw new ExecutorException("Do not know how to create an instance of " + resultType);
  }
  
  // ResultMap中记录<constructor>节点信息，则通过反射方式调用构造方法，创建结果对象
  Object createParameterizedResultObject(ResultSetWrapper rsw, Class<?> resultType, List<ResultMapping> constructorMappings,
                                         List<Class<?>> constructorArgTypes, List<Object> constructorArgs, String columnPrefix) {
    boolean foundValues = false;

/*  public User(Long id, String username, String password, String address) {
        this.id = id;
        this.username = username;
        this.password = password;
        this.address = address;
    }*/
/*  <constructor>
    	<idArg column="id" javaType="long"/>
    	<arg column="username" javaType="string"/>
    	<arg column="password" javaType="string"/>
    	<arg column="address" javaType="string"/>
    </constructor>*/
    // 遍历constructorMappings集合，该过程会使用constructorArgType集合记录构造函数参数类型
    // 使用constructorArgs集合构造函数实参
    for (ResultMapping constructorMapping : constructorMappings) {
      // 获取当前构造参数的类型
      final Class<?> parameterType = constructorMapping.getJavaType(); // 获取java类型
      final String column = constructorMapping.getColumn(); // 获取column属性值
      final Object value;
      try {
        if (constructorMapping.getNestedQueryId() != null) {
          // 存在嵌套查询，需要处理该查询，然后才能得到实参
          // 我的理解是java对象的字段可能是集合类型(需要根据数据库查询得到的数据才能初始化)
          value = getNestedQueryConstructorValue(rsw.getResultSet(), constructorMapping, columnPrefix);
        } else if (constructorMapping.getNestedResultMapId() != null) {
          // 存在嵌套映射，需要处理该映射，然后才能得到实参
          // 我的理解是java对象的字段可能存在对另一个对象的引用(需要另一个结果集的处理)
          final ResultMap resultMap = configuration.getResultMap(constructorMapping.getNestedResultMapId());
          value = getRowValue(rsw, resultMap);
        } else {
          // 直接获取该列的值，然后经过TypeHandler对象的转换，得到构造函数的实参
          final TypeHandler<?> typeHandler = constructorMapping.getTypeHandler();
          value = typeHandler.getResult(rsw.getResultSet(), prependPrefix(column, columnPrefix));
        }
      } catch (ResultMapException e) {
    	// 抛出异常
        throw new ExecutorException("Could not process result for mapping: " + constructorMapping, e);
      } catch (SQLException e) {
    	// 抛出异常
        throw new ExecutorException("Could not process result for mapping: " + constructorMapping, e);
      }
      constructorArgTypes.add(parameterType); // 记录当前构造函数参数的类型
      constructorArgs.add(value);// 记录当前构造函数的实际值
      foundValues = value != null || foundValues; // foundValues记录获取value成功
    }
    // 调用objectFactory调用匹配的构造函数，创建结果对象
    return foundValues ? objectFactory.create(resultType, constructorArgTypes, constructorArgs) : null;
  }

  private Object createByConstructorSignature(ResultSetWrapper rsw, Class<?> resultType, List<Class<?>> constructorArgTypes, List<Object> constructorArgs,
                                              String columnPrefix) throws SQLException {
    // 获取所有的Constructor函数
	final Constructor<?>[] constructors = resultType.getDeclaredConstructors();
    final Constructor<?> annotatedConstructor = findAnnotatedConstructor(constructors);
    if (annotatedConstructor != null) {
      return createUsingConstructor(rsw, resultType, constructorArgTypes, constructorArgs, columnPrefix, annotatedConstructor);
    } else {
      for (Constructor<?> constructor : constructors) {
        if (allowedConstructor(constructor, rsw.getClassNames())) {
          return createUsingConstructor(rsw, resultType, constructorArgTypes, constructorArgs, columnPrefix, constructor);
        }
      }
    }
    throw new ExecutorException("No constructor found in " + resultType.getName() + " matching " + rsw.getClassNames());
  }

  private Object createUsingConstructor(ResultSetWrapper rsw, Class<?> resultType, List<Class<?>> constructorArgTypes, List<Object> constructorArgs, String columnPrefix, Constructor<?> constructor) throws SQLException {
    boolean foundValues = false;
    for (int i = 0; i < constructor.getParameterTypes().length; i++) {
      // 获取构造函数的参数类型
      Class<?> parameterType = constructor.getParameterTypes()[i];
      String columnName = rsw.getColumnNames().get(i); // ResultSet的列名
      // 查找对应的TypeHandler,并获取该列的值
      TypeHandler<?> typeHandler = rsw.getTypeHandler(parameterType, columnName);
      Object value = typeHandler.getResult(rsw.getResultSet(), prependPrefix(columnName, columnPrefix));
      // 记录构造函数的参数类型和参数值
      constructorArgTypes.add(parameterType);
      constructorArgs.add(value);
      foundValues = value != null || foundValues; // 更新foundValues值
    }
    // 使用ObjectFactory调用对应的构造方法，创建结果对象
    return foundValues ? objectFactory.create(resultType, constructorArgTypes, constructorArgs) : null;
  }

  private Constructor<?> findAnnotatedConstructor(final Constructor<?>[] constructors) {
    for (final Constructor<?> constructor : constructors) {
      if (constructor.isAnnotationPresent(AutomapConstructor.class)) {
        return constructor;
      }
    }
    return null;
  }

  private boolean allowedConstructor(final Constructor<?> constructor, final List<String> classNames) {
    final Class<?>[] parameterTypes = constructor.getParameterTypes();
    if (typeNames(parameterTypes).equals(classNames)) return true;
    if (parameterTypes.length != classNames.size()) return false;
    for (int i = 0; i < parameterTypes.length; i++) {
      final Class<?> parameterType = parameterTypes[i];
      if (parameterType.isPrimitive() && !primitiveTypes.getWrapper(parameterType).getName().equals(classNames.get(i))) {
        return false;
      } else if (!parameterType.isPrimitive() && !parameterType.getName().equals(classNames.get(i))) {
        return false;
      }
    }
    return true;
  }

  private List<String> typeNames(Class<?>[] parameterTypes) {
    List<String> names = new ArrayList<String>();
    for (Class<?> type : parameterTypes) {
      names.add(type.getName());
    }
    return names;
  }
  
  // 当结果集只有一列时
  private Object createPrimitiveResultObject(ResultSetWrapper rsw, ResultMap resultMap, String columnPrefix) throws SQLException {
    // 获取resultMap的类型参数
	final Class<?> resultType = resultMap.getType();
	
    final String columnName;
    if (!resultMap.getResultMappings().isEmpty()) {
    	
      final List<ResultMapping> resultMappingList = resultMap.getResultMappings();
      final ResultMapping mapping = resultMappingList.get(0);
      columnName = prependPrefix(mapping.getColumn(), columnPrefix);
    } else {
      // 获取结果集的列名
      columnName = rsw.getColumnNames().get(0);
      
    }
    
    final TypeHandler<?> typeHandler = rsw.getTypeHandler(resultType, columnName);
    return typeHandler.getResult(rsw.getResultSet(), columnName);
  }

  //
  // NESTED QUERY
  //
  // 
  private Object getNestedQueryConstructorValue(ResultSet rs, ResultMapping constructorMapping, String columnPrefix) throws SQLException {
    // 获取嵌套查询的id以及对应的MappedStatement对象
	final String nestedQueryId = constructorMapping.getNestedQueryId();
    final MappedStatement nestedQuery = configuration.getMappedStatement(nestedQueryId);
    final Class<?> nestedQueryParameterType = nestedQuery.getParameterMap().getType();
    // 获取传递给嵌套查询的的参数值
    final Object nestedQueryParameterObject = prepareParameterForNestedQuery(rs, constructorMapping, nestedQueryParameterType, columnPrefix);
    Object value = null;
    if (nestedQueryParameterObject != null) {
      // 获取嵌套查询对应的BoundSql对象和相应的CacheKey对象
      final BoundSql nestedBoundSql = nestedQuery.getBoundSql(nestedQueryParameterObject);
      final CacheKey key = executor.createCacheKey(nestedQuery, nestedQueryParameterObject, RowBounds.DEFAULT, nestedBoundSql);
      // 获取嵌套查询结果集经过映射后的目标类型
      final Class<?> targetType = constructorMapping.getJavaType();
      // 创建ResultLoader对象，并调用loadResult方法执行嵌套查询，得到相应的构造方法参数值
      
      // 在创建构造函数的参数时，涉及的嵌套查询，无论配置如何，都不会延迟加载，
      final ResultLoader resultLoader = new ResultLoader(configuration, executor, nestedQuery, nestedQueryParameterObject, targetType, key, nestedBoundSql);
      value = resultLoader.loadResult();
    }
    return value;
  }

  private Object getNestedQueryMappingValue(ResultSet rs, MetaObject metaResultObject, ResultMapping propertyMapping, ResultLoaderMap lazyLoader, String columnPrefix)
      throws SQLException {
	  
	// 获取嵌套查询的id和对应的MappedStatement对象
    final String nestedQueryId = propertyMapping.getNestedQueryId();
    
    final String property = propertyMapping.getProperty();
    
    final MappedStatement nestedQuery = configuration.getMappedStatement(nestedQueryId);
    
    //　传递给嵌套查询的参数类型和参数值
    final Class<?> nestedQueryParameterType = nestedQuery.getParameterMap().getType();
    
    final Object nestedQueryParameterObject = prepareParameterForNestedQuery(rs, propertyMapping, nestedQueryParameterType, columnPrefix);
    Object value = null;
    
    if (nestedQueryParameterObject != null) { // 嵌套查询的参数不为空
    	
      // 获取嵌套查询对应BoundSql对象和相应CacheKey对象
      // 获取可执行的SQL语句
      final BoundSql nestedBoundSql = nestedQuery.getBoundSql(nestedQueryParameterObject);
      
      final CacheKey key = executor.createCacheKey(nestedQuery, nestedQueryParameterObject, RowBounds.DEFAULT, nestedBoundSql);
      // 获取嵌套查询结果集经过映射后的目标类型(结果集经过映射后的目标对象)
      final Class<?> targetType = propertyMapping.getJavaType();
      // 检测缓存中是否存在该嵌套查询的结果对象
      if (executor.isCached(nestedQuery, key)) {
    	//　创建DeferredLoad对象，并通过该DeferredLoad对象从缓存中加载结果对象
        executor.deferLoad(nestedQuery, metaResultObject, property, key, targetType);
        value = DEFERED; // 返回DEFERED标识(占位符)
        
      } else {
    	//　创建嵌套查询相对应的ResultLoad对象
        final ResultLoader resultLoader = new ResultLoader(configuration, executor, nestedQuery, nestedQueryParameterObject, targetType, key, nestedBoundSql);
        if (propertyMapping.isLazy()) {
          // 如果该属性配置了延迟加载，则将其添加到ResultLoadMap对象，等待真正使用时，在执行嵌套查询
          // 并得到结果对象
          lazyLoader.addLoader(property, metaResultObject, resultLoader);
          value = DEFERED;
        } else {
          // 没有配置延迟加载，则直接调用resultLoader.loadResult()方法执行嵌套查询，并映射得到结果对象
          value = resultLoader.loadResult();
        }
      }
    }
    return value;
  }

  private Object prepareParameterForNestedQuery(ResultSet rs, ResultMapping resultMapping, Class<?> parameterType, String columnPrefix) throws SQLException {
    if (resultMapping.isCompositeResult()) {
      return prepareCompositeKeyParameter(rs, resultMapping, parameterType, columnPrefix);
    } else {
      return prepareSimpleKeyParameter(rs, resultMapping, parameterType, columnPrefix);
    }
  }

  private Object prepareSimpleKeyParameter(ResultSet rs, ResultMapping resultMapping, Class<?> parameterType, String columnPrefix) throws SQLException {
    final TypeHandler<?> typeHandler;
    if (typeHandlerRegistry.hasTypeHandler(parameterType)) {
      typeHandler = typeHandlerRegistry.getTypeHandler(parameterType);
    } else {
      typeHandler = typeHandlerRegistry.getUnknownTypeHandler();
    }
    return typeHandler.getResult(rs, prependPrefix(resultMapping.getColumn(), columnPrefix));
  }

  private Object prepareCompositeKeyParameter(ResultSet rs, ResultMapping resultMapping, Class<?> parameterType, String columnPrefix) throws SQLException {
    final Object parameterObject = instantiateParameterObject(parameterType);
    final MetaObject metaObject = configuration.newMetaObject(parameterObject);
    boolean foundValues = false;
    for (ResultMapping innerResultMapping : resultMapping.getComposites()) {
      final Class<?> propType = metaObject.getSetterType(innerResultMapping.getProperty());
      final TypeHandler<?> typeHandler = typeHandlerRegistry.getTypeHandler(propType);
      final Object propValue = typeHandler.getResult(rs, prependPrefix(innerResultMapping.getColumn(), columnPrefix));
      // issue #353 & #560 do not execute nested query if key is null
      if (propValue != null) {
        metaObject.setValue(innerResultMapping.getProperty(), propValue);
        foundValues = true;
      }
    }
    return foundValues ? parameterObject : null;
  }

  private Object instantiateParameterObject(Class<?> parameterType) {
    if (parameterType == null) {
      return new HashMap<Object, Object>();
    } else if (ParamMap.class.equals(parameterType)) {
      return new HashMap<Object, Object>(); // issue #649
    } else {
      return objectFactory.create(parameterType);
    }
  }


  // 它会依据ResultMap对象中记录的Discriminator以及参与映射的列值，选择映射操作最终使用的ResultMap对象
  public ResultMap resolveDiscriminatedResultMap(ResultSet rs, ResultMap resultMap, String columnPrefix) throws SQLException {
    
	//　记录已经处理过的ResultMap的id
	Set<String> pastDiscriminators = new HashSet<String>();
	
	//　获取ResultMap中的Discriminator对象。<discriminator>节点对应生成的Discriminator对象并记录到
	// ResultMap.discriminator字段中，而不是生成ResultMapping对象 
    Discriminator discriminator = resultMap.getDiscriminator();
    
    while (discriminator != null) {
/*    	<resultMap id="vehicleResult" type="Vehicle">
    	  <id property="id" column="id" />
    	  <result property="vin" column="vin"/>
    	  <result property="year" column="year"/>
    	  <result property="make" column="make"/>
    	  <result property="model" column="model"/>
    	  <result property="color" column="color"/>
    	  <discriminator javaType="int" column="vehicle_type">
    	    <case value="1" resultMap="carResult"/>
    	    <case value="2" resultMap="truckResult"/>
    	    <case value="3" resultMap="vanResult"/>
    	    <case value="4" resultMap="suvResult"/>
    	  </discriminator>
    	</resultMap>*/
      // 获取记录中对应列的值，其中会使用相应的TypeHandler对象将该列值装换成Java类型
      final Object value = getDiscriminatorValue(rs, discriminator, columnPrefix);
      
      // 根据该列值获取对应的ResultMap的id，比如说就是对应的resultMap的名称suvResult
      final String discriminatedMapId = discriminator.getMapIdFor(String.valueOf(value));
      
      if (configuration.hasResultMap(discriminatedMapId)) {
    	// 根据上述获取的id，查找相应的ResultMap对象
        resultMap = configuration.getResultMap(discriminatedMapId);
        
        // 记录当前Discriminator对象
        Discriminator lastDiscriminator = discriminator;
        
        // 获取ResultMap对象中的Discriminator
        discriminator = resultMap.getDiscriminator();
        // 检测Discriminator是否出现了环形引用
        // 防止出现循环引用
        if (discriminator == lastDiscriminator || !pastDiscriminators.add(discriminatedMapId)) {
          break;
        }
      } else {
        break;
      }
    }
    return resultMap; // 该resultMap为映射最终使用的ResultMap
  }

  private Object getDiscriminatorValue(ResultSet rs, Discriminator discriminator, String columnPrefix) throws SQLException {
    // 从discriminator中返回ResultMapping
	final ResultMapping resultMapping = discriminator.getResultMapping();
    final TypeHandler<?> typeHandler = resultMapping.getTypeHandler(); // 对应的类型处理器
    return typeHandler.getResult(rs, prependPrefix(resultMapping.getColumn(), columnPrefix));
  }

  private String prependPrefix(String columnName, String prefix) {
    if (columnName == null || columnName.length() == 0 || prefix == null || prefix.length() == 0) {
      return columnName;
    }
    return prefix + columnName;
  }

  //
  // HANDLE NESTED RESULT MAPS
  //

  private void handleRowValuesForNestedResultMap(ResultSetWrapper rsw, ResultMap resultMap, ResultHandler<?> resultHandler, RowBounds rowBounds, ResultMapping parentMapping) throws SQLException {
    
	// 创建DefaultResultContext
	final DefaultResultContext<Object> resultContext = new DefaultResultContext<Object>();
    // 步骤一:定位到指定的记录行
	skipRows(rsw.getResultSet(), rowBounds);
	
    Object rowValue = previousRowValue;

    // 步骤二:检测是否能继续映射结果集中剩余项的记录行
    while (shouldProcessMoreRows(resultContext, rowBounds) && rsw.getResultSet().next()) {
      
      // 步骤三:通过resolveDiscriminatedResultMap()方法决定映射使用的ResultMap对象
      final ResultMap discriminatedResultMap = resolveDiscriminatedResultMap(rsw.getResultSet(), resultMap, null);
      
      // 步骤四:为该行记录生成CacheKey
      final CacheKey rowKey = createRowKey(discriminatedResultMap, rsw, null);
      
      // 步骤五:根据(4)生成的CacheKey查找nestedResultObjects集合
      Object partialObject = nestedResultObjects.get(rowKey);
      // issue #577 && #542
      if (mappedStatement.isResultOrdered()) {// 步骤六:检测ResultOrdered属性
    	  
    	// resultOrdered属性发挥作用的地方
        if (partialObject == null && rowValue != null) { // 主结果对象发生变化
          nestedResultObjects.clear();// 清空nestedResultObjects集合
          // 调用storeObject()方法保存主结果对象(也就是嵌套映射的外层结果对象)
          storeObject(resultHandler, resultContext, rowValue, parentMapping, rsw.getResultSet());
        }
        
        // 步骤七:完成该行记录的映射返回结果对象，其中还会将结果对象添加到nestedResultObjects集合中
        rowValue = getRowValue(rsw, discriminatedResultMap, rowKey, null, partialObject);
      } else {
    	// 步骤七:完成该行记录的映射返回结果对象，其中还会将结果对象添加到nestedResultObjects集合中
        rowValue = getRowValue(rsw, discriminatedResultMap, rowKey, null, partialObject);
        if (partialObject == null) {
          // 步骤八:调用storeObject()方法保存结果对象
          storeObject(resultHandler, resultContext, rowValue, parentMapping, rsw.getResultSet());
        }
      }
    }
    // 对ResultOrdered属性为true时的特殊处理，调用storeObject()方法保存结果对象
    if (rowValue != null && mappedStatement.isResultOrdered() && shouldProcessMoreRows(resultContext, rowBounds)) {
      storeObject(resultHandler, resultContext, rowValue, parentMapping, rsw.getResultSet());
      previousRowValue = null;
    } else if (rowValue != null) {
      previousRowValue = rowValue; // 保存向前的rowValue
    }
  }

  //
  // GET VALUE FROM ROW FOR NESTED RESULT MAP
  //

  private Object getRowValue(ResultSetWrapper rsw, ResultMap resultMap, CacheKey combinedKey, String columnPrefix, Object partialObject) throws SQLException {
    
	final String resultMapId = resultMap.getId();
    Object rowValue = partialObject;
    if (rowValue != null) { // 步骤一:检测外层对象是否存在
      final MetaObject metaObject = configuration.newMetaObject(rowValue);
      
      // 步骤3.1:将外层对象添加到ancestorOnjects集合中
      // 将外层对象添加到DefaultResultSetHandler.ancestorObjects(HashMap<String, Object>();)中，其中key为ResultMap的id，value为外层对象
      putAncestor(rowValue, resultMapId);
      
      // 步骤3.2:处理嵌套映射
      applyNestedResultMappings(rsw, resultMap, metaObject, columnPrefix, combinedKey, false);
      
      // 步骤3.3:将外层对象ancestorOnjects集合中移除
      ancestorObjects.remove(resultMapId);
    } else {
    	
      final ResultLoaderMap lazyLoader = new ResultLoaderMap(); // 延迟加载
      // 创建外层对象
      rowValue = createResultObject(rsw, resultMap, lazyLoader, columnPrefix);
      
      if (rowValue != null && !hasTypeHandlerForResultObject(rsw, resultMap.getType())) {
        final MetaObject metaObject = configuration.newMetaObject(rowValue);
        
        // 更新foundValues,其含义与简单映射中同名变量相同:成功映射任意属性，则foundValues为true
        // 否则foundValues为false
        boolean foundValues = this.useConstructorMappings;
        
        if (shouldApplyAutomaticMappings(resultMap, true)) { // 步骤2.2:自动映射
          foundValues = applyAutomaticMappings(rsw, resultMap, metaObject, columnPrefix) || foundValues;
        }
        // 步骤2.3:映射resultMap中明确指定的字段
        foundValues = applyPropertyMappings(rsw, resultMap, metaObject, lazyLoader, columnPrefix) || foundValues;
        
        // 步骤2.4:将外层对象添加到ancestorOnjects集合中
        putAncestor(rowValue, resultMapId);
        
        // 步骤2.5:处理嵌套映射
        foundValues = applyNestedResultMappings(rsw, resultMap, metaObject, columnPrefix, combinedKey, true) || foundValues;
        
        // 步骤2.6:将外层对象从ancestorOnjects集合中移除
        ancestorObjects.remove(resultMapId);
        foundValues = lazyLoader.size() > 0 || foundValues;
        rowValue = foundValues || configuration.isReturnInstanceForEmptyRow() ? rowValue : null;
      }
      if (combinedKey != CacheKey.NULL_CACHE_KEY) {
    	// 步骤2.7:将外层对象保存到nestedResultObjects集合中，待映射后续记录时使用
        nestedResultObjects.put(combinedKey, rowValue);
      }
    }
    return rowValue;
  }

  private void putAncestor(Object resultObject, String resultMapId) {
	// 将外层对象添加到DefaultResultSetHandler.ancestorObjects中，其中key为ResultMap的id，value为外层对象
    ancestorObjects.put(resultMapId, resultObject);
  }

  //
  // NESTED RESULT MAP (JOIN MAPPING)
  //

  private boolean applyNestedResultMappings(ResultSetWrapper rsw, ResultMap resultMap, MetaObject metaObject, String parentPrefix, CacheKey parentRowKey, boolean newObject) {
    boolean foundValues = false;
    // 遍历全部ResultMappings对象，处理其中的嵌套映射
    for (ResultMapping resultMapping : resultMap.getPropertyResultMappings()) {
    	
      // 获取ResultMappings.NestedResultMapId值
      final String nestedResultMapId = resultMapping.getNestedResultMapId();
      
      // 步骤1:检测nestedResultMapId和ResulSet这两个字段
      if (nestedResultMapId != null && resultMapping.getResultSet() == null) {
    	  
        try {
          
          // 获取列前缀
          final String columnPrefix = getColumnPrefix(parentPrefix, resultMapping);
          
          // 步骤2:确定嵌套映射使用的ResultMap对象
          final ResultMap nestedResultMap = getNestedResultMap(rsw.getResultSet(), nestedResultMapId, columnPrefix);
          
          // 步骤3:处理循环引用的情况
          if (resultMapping.getColumnPrefix() == null) {
            // try to fill circular reference only when columnPrefix
            // is not specified for the nested result map (issue #215)
            Object ancestorObject = ancestorObjects.get(nestedResultMapId);
            if (ancestorObject != null) {
              if (newObject) {
                linkObjects(metaObject, resultMapping, ancestorObject); // issue #385
              }
              // 若是循环引用,则不用执行下面的路径创建新对象，而是重用之前的对象
              continue;
            }
          }
          // 步骤四:创建嵌套对象的CacheKey对象
          final CacheKey rowKey = createRowKey(nestedResultMap, rsw, columnPrefix);
          
          // 合并两个CacheKey对象，组成全局唯一的CacheKey对象
          final CacheKey combinedKey = combineKeys(rowKey, parentRowKey);
          
          // 查找nestedResultObjects集合中是否有相同的key嵌套对象
          Object rowValue = nestedResultObjects.get(combinedKey);
          boolean knownValue = rowValue != null;
          
          // 步骤5:初始化外层对象中的Collection类型的属性
          instantiateCollectionPropertyIfAppropriate(resultMapping, metaObject); // mandatory
          
          // 步骤6:根据NotNullColumn属性检测结果集中的空值
          // notNullColumn属性发挥作用的地方
          if (anyNotNullColumnHasValue(resultMapping, columnPrefix, rsw)) {
        	
        	// 步骤7:完成嵌套映射,并生成嵌套对象
            rowValue = getRowValue(rsw, nestedResultMap, combinedKey, columnPrefix, rowValue);
            
            // 注意:“!knownValue”这个条件，当嵌套对象已存在于nestedResultObjects集合中时。
            // 说明相关列已经映射成了嵌套对象。现假设对象A中有b1和b2两个属性都指向了对象B且这两个属性都是同一
            // ResultMap进行映射的。在对一行记录进行映射时，首先映射的b1属性会生成B对象且成功赋值，而B2属性
            // 为null
            if (rowValue != null && !knownValue) {
              // 步骤8:将步骤7得到的嵌套对象保存到外层对象的相应属性中
              linkObjects(metaObject, resultMapping, rowValue);
              foundValues = true;
            }
          }
        } catch (SQLException e) {
          throw new ExecutorException("Error getting nested result map values for '" + resultMapping.getProperty() + "'.  Cause: " + e, e);
        }
      }
    }
    return foundValues;
  }

  private String getColumnPrefix(String parentPrefix, ResultMapping resultMapping) {
    final StringBuilder columnPrefixBuilder = new StringBuilder();
    if (parentPrefix != null) {
      columnPrefixBuilder.append(parentPrefix);
    }
    if (resultMapping.getColumnPrefix() != null) {
      columnPrefixBuilder.append(resultMapping.getColumnPrefix());
    }
    return columnPrefixBuilder.length() == 0 ? null : columnPrefixBuilder.toString().toUpperCase(Locale.ENGLISH);
  }

  private boolean anyNotNullColumnHasValue(ResultMapping resultMapping, String columnPrefix, ResultSetWrapper rsw) throws SQLException {
    Set<String> notNullColumns = resultMapping.getNotNullColumns();
    if (notNullColumns != null && !notNullColumns.isEmpty()) {
      ResultSet rs = rsw.getResultSet();
      for (String column : notNullColumns) {
        rs.getObject(prependPrefix(column, columnPrefix));
        if (!rs.wasNull()) {
          return true;
        }
      }
      return false;
    } else if (columnPrefix != null) {
      for (String columnName : rsw.getColumnNames()) {
        if (columnName.toUpperCase().startsWith(columnPrefix.toUpperCase())) {
          return true;
        }
      }
      return false;
    }
    return true;
  }

  private ResultMap getNestedResultMap(ResultSet rs, String nestedResultMapId, String columnPrefix) throws SQLException {
    ResultMap nestedResultMap = configuration.getResultMap(nestedResultMapId);
    return resolveDiscriminatedResultMap(rs, nestedResultMap, columnPrefix);
  }

  //
  // UNIQUE RESULT KEY
  //

  private CacheKey createRowKey(ResultMap resultMap, ResultSetWrapper rsw, String columnPrefix) throws SQLException {
    final CacheKey cacheKey = new CacheKey(); // 创建CacheKey对象
    
    // updateList中添加id
    cacheKey.update(resultMap.getId());// 将ResultMap的id作为CacheKey的一部分
    // 查找ResultMapping对象集合
    List<ResultMapping> resultMappings = getResultMappingsForRowKey(resultMap);
    
    if (resultMappings.isEmpty()) { // 没有找到resultMappings集合
      if (Map.class.isAssignableFrom(resultMap.getType())) { // 当type类型为Map时  
    	// 由结果集的所有列名以及当前记录行的所有列值一起构成CacheKey对象
        createRowKeyForMap(rsw, cacheKey);
      } else { 
    	// 由结果集中未映射的列名以及它们在当前记录行中的对应列值一起构成CacheKey对象
        createRowKeyForUnmappedProperties(resultMap, rsw, cacheKey, columnPrefix);
      }
    } else {
      // 由ResultMapping集合中的列名以及它们在当前记录行中相应的列值一起构成CacheKey对象
      createRowKeyForMappedProperties(resultMap, rsw, cacheKey, resultMappings, columnPrefix);
    }
    if (cacheKey.getUpdateCount() < 2) {
      // 通过上面的查找没有找到任何列参与构成CacheKey对象，则返回NULL_CACHE_KEY对象
      return CacheKey.NULL_CACHE_KEY;
    }
   
    return cacheKey;
  }

  private CacheKey combineKeys(CacheKey rowKey, CacheKey parentRowKey) {
	// 边界检测
    if (rowKey.getUpdateCount() > 1 && parentRowKey.getUpdateCount() > 1) {
      CacheKey combinedKey; 
      try {
        combinedKey = rowKey.clone(); // 注意使用的是rowKey的克隆对象
      } catch (CloneNotSupportedException e) {
        throw new ExecutorException("Error cloning cache key.  Cause: " + e, e);
      }
      // 与外层对象的CacheKey和并，形成嵌套对象最终的的CacheKey
      combinedKey.update(parentRowKey);
      return combinedKey;
    }
    return CacheKey.NULL_CACHE_KEY;
  }
  // 它会检查ResultMap是否定义了<idArg>和<id>节点，如果是的话则返回ResultMap.idResultMappings集合
  // 如果否的情况则返回ResultMap.propertyResultMappings集合
  private List<ResultMapping> getResultMappingsForRowKey(ResultMap resultMap) {
	  
	// ResultMap.idResultMappings集合中记录<idArg>和<id>节点对应的ResultMapping对象
    List<ResultMapping> resultMappings = resultMap.getIdResultMappings();
    if (resultMappings.isEmpty()) {
      // resultMap.getPropertyResultMappings()集合记录了除<id*>节点之外的ResultMapping对象
      resultMappings = resultMap.getPropertyResultMappings();
    }
    return resultMappings;
  }

  private void createRowKeyForMappedProperties(ResultMap resultMap, ResultSetWrapper rsw, CacheKey cacheKey, List<ResultMapping> resultMappings, String columnPrefix) throws SQLException {
    for (ResultMapping resultMapping : resultMappings) { // 遍历resultMappings集合
      if (resultMapping.getNestedResultMapId() != null && resultMapping.getResultSet() == null) {
        // Issue #392
    	// 如果存在嵌套映射的，递归调用createRowKeyForMappedProperties()方法进行处理
        final ResultMap nestedResultMap = configuration.getResultMap(resultMapping.getNestedResultMapId());
        createRowKeyForMappedProperties(nestedResultMap, rsw, cacheKey, nestedResultMap.getConstructorResultMappings(),
            prependPrefix(resultMapping.getColumnPrefix(), columnPrefix));
        
      } else if (resultMapping.getNestedQueryId() == null) { // 忽略嵌套映射
    	
    	// 获取该列的名称        (前缀+列名)
        final String column = prependPrefix(resultMapping.getColumn(), columnPrefix);
        
        // 获取该列相应的TypeHandler对象
        final TypeHandler<?> th = resultMapping.getTypeHandler();
        
        // 获取映射的列名
        List<String> mappedColumnNames = rsw.getMappedColumnNames(resultMap, columnPrefix);
        
        // Issue #114
        if (column != null && mappedColumnNames.contains(column.toUpperCase(Locale.ENGLISH))) {
          // 获取列值
          final Object value = th.getResult(rsw.getResultSet(), column);
          if (value != null || configuration.isReturnInstanceForEmptyRow()) {
            // 将列名与列值添加到Cache对象中
        	cacheKey.update(column);// 添加到cacheKey中
            cacheKey.update(value);// 添加到cacheKey中
          }
        }
      }
    }
  }

  private void createRowKeyForUnmappedProperties(ResultMap resultMap, ResultSetWrapper rsw, CacheKey cacheKey, String columnPrefix) throws SQLException {
    // 获取type类型对应的MetaClass对象
	final MetaClass metaType = MetaClass.forClass(resultMap.getType(), reflectorFactory);
    // 得到未明确映射的列名
	List<String> unmappedColumnNames = rsw.getUnmappedColumnNames(resultMap, columnPrefix);
    for (String column : unmappedColumnNames) {
      String property = column;
      if (columnPrefix != null && !columnPrefix.isEmpty()) {
        // When columnPrefix is specified, ignore columns without the prefix.
    	// 忽略前缀
        if (column.toUpperCase(Locale.ENGLISH).startsWith(columnPrefix)) {
          property = column.substring(columnPrefix.length());
        } else {
          continue;
        }
      }
      if (metaType.findProperty(property, configuration.isMapUnderscoreToCamelCase()) != null) {
        String value = rsw.getResultSet().getString(column); // 获取列值
        if (value != null) {
          cacheKey.update(column); // 添加到cacheKey中
          cacheKey.update(value);// 添加到cacheKey中
        }
      }
    }
  }

  private void createRowKeyForMap(ResultSetWrapper rsw, CacheKey cacheKey) throws SQLException {
    
	List<String> columnNames = rsw.getColumnNames(); // 得到列名
    for (String columnName : columnNames) { // 遍历列名集合
      final String value = rsw.getResultSet().getString(columnName); // 当前记录行的列值
      if (value != null) {
        cacheKey.update(columnName); // 添加到cacheKey中
        cacheKey.update(value); // 添加到cacheKey中
      }
    }
  }

  private void linkObjects(MetaObject metaObject, ResultMapping resultMapping, Object rowValue) {
    // 检测外层对象的指定属性是否为Collection类型，如果是且未初始化，则初始化该集合属性并返回
	final Object collectionProperty = instantiateCollectionPropertyIfAppropriate(resultMapping, metaObject);
    // 根据是否为集合类型，调用MetaObject方法。将嵌套对象记录到外层对象的相应属性中去
	if (collectionProperty != null) {
      final MetaObject targetMetaObject = configuration.newMetaObject(collectionProperty);
      targetMetaObject.add(rowValue);
    } else {
      metaObject.setValue(resultMapping.getProperty(), rowValue);
    }
  }

  private Object instantiateCollectionPropertyIfAppropriate(ResultMapping resultMapping, MetaObject metaObject) {
    // 获取指定的属性名称和当前属性值
	final String propertyName = resultMapping.getProperty();// 获取resultMapping的property属性值
	
	Object propertyValue = metaObject.getValue(propertyName); // 获取外层对象的相应属性字段

    if (propertyValue == null) { // 检测该属性是否初始化
      Class<?> type = resultMapping.getJavaType(); // 获取该属性的javaType类型
      if (type == null) {
        type = metaObject.getSetterType(propertyName); // 从setter方法中去获取
      }
      try {
    	// 指定属性为集合类型
        if (objectFactory.isCollection(type)) {
          // 通过ObjectFactory创建该类型的集合对象，并进行相应设置
          propertyValue = objectFactory.create(type);
          // 设置相应的属性值
          metaObject.setValue(propertyName, propertyValue);
          return propertyValue;
        }
      } catch (Exception e) {
        throw new ExecutorException("Error instantiating collection property for result '" + resultMapping.getProperty() + "'.  Cause: " + e, e);
      }
    } else if (objectFactory.isCollection(propertyValue.getClass())) {
      return propertyValue; // 指定属性是集合类型且已经初始化，并返回该属性值
    }
    return null;
  }

  private boolean hasTypeHandlerForResultObject(ResultSetWrapper rsw, Class<?> resultType) {
    if (rsw.getColumnNames().size() == 1) {
      // 查询的结果集只存在一列时
      return typeHandlerRegistry.hasTypeHandler(resultType, rsw.getJdbcType(rsw.getColumnNames().get(0)));
    }
    return typeHandlerRegistry.hasTypeHandler(resultType);
  }

}
