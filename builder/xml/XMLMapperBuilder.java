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
package org.apache.ibatis.builder.xml;

import java.io.InputStream;
import java.io.Reader;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import org.apache.ibatis.builder.BaseBuilder;
import org.apache.ibatis.builder.BuilderException;
import org.apache.ibatis.builder.CacheRefResolver;
import org.apache.ibatis.builder.IncompleteElementException;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.apache.ibatis.builder.ResultMapResolver;
import org.apache.ibatis.cache.Cache;
import org.apache.ibatis.executor.ErrorContext;
import org.apache.ibatis.io.Resources;
import org.apache.ibatis.mapping.Discriminator;
import org.apache.ibatis.mapping.ParameterMapping;
import org.apache.ibatis.mapping.ParameterMode;
import org.apache.ibatis.mapping.ResultFlag;
import org.apache.ibatis.mapping.ResultMap;
import org.apache.ibatis.mapping.ResultMapping;
import org.apache.ibatis.parsing.XNode;
import org.apache.ibatis.parsing.XPathParser;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.type.JdbcType;
import org.apache.ibatis.type.TypeHandler;

/**
 * @author Clinton Begin
 */
public class XMLMapperBuilder extends BaseBuilder {

  private final XPathParser parser;
  private final MapperBuilderAssistant builderAssistant;
  private final Map<String, XNode> sqlFragments;
  private final String resource;

  @Deprecated
  public XMLMapperBuilder(Reader reader, Configuration configuration, String resource, Map<String, XNode> sqlFragments, String namespace) {
    this(reader, configuration, resource, sqlFragments);
    this.builderAssistant.setCurrentNamespace(namespace);
  }

  @Deprecated
  public XMLMapperBuilder(Reader reader, Configuration configuration, String resource, Map<String, XNode> sqlFragments) {
    this(new XPathParser(reader, true, configuration.getVariables(), new XMLMapperEntityResolver()),
        configuration, resource, sqlFragments);
  }

  public XMLMapperBuilder(InputStream inputStream, Configuration configuration, String resource, Map<String, XNode> sqlFragments, String namespace) {
    this(inputStream, configuration, resource, sqlFragments);
    this.builderAssistant.setCurrentNamespace(namespace);
  }

  public XMLMapperBuilder(InputStream inputStream, Configuration configuration, String resource, Map<String, XNode> sqlFragments) {
    this(new XPathParser(inputStream, true, configuration.getVariables(), new XMLMapperEntityResolver()),
        configuration, resource, sqlFragments);
  }
  // XPathParser  .xml文件解析器
  private XMLMapperBuilder(XPathParser parser, Configuration configuration, String resource, Map<String, XNode> sqlFragments) {
    super(configuration);
    this.builderAssistant = new MapperBuilderAssistant(configuration, resource);
    this.parser = parser;
    this.sqlFragments = sqlFragments;
    this.resource = resource;
  }

  public void parse() {
	// 判断是否已经加载过该映射文件(  resource == url)
    if (!configuration.isResourceLoaded(resource)) { 
    	
      // 解析Mapper节点
      configurationElement(parser.evalNode("/mapper"));// 处理<mapper>节点
      
      // 将resource添加到Configuation.loadedResource集合中保存，它是HashSet<String>类型的集合
      // 其中记录了已经加载过的映射文件
      configuration.addLoadedResource(resource);
      bindMapperForNamespace(); // 注册Mapper接口
    }
    
    // 处理configurationElement()方法中解析失败的<resuleMap>节点
    parsePendingResultMaps();
    // 处理configurationElement()方法中解析失败的<cache-ref>节点
    parsePendingCacheRefs();
    // 处理configurationElement()方法中解析失败的SQL节点
    parsePendingStatements();
  }

  public XNode getSqlFragment(String refid) {
    return sqlFragments.get(refid);
  }

  private void configurationElement(XNode context) {
    try {
      // 获取<Mapper>节点的namespace属性
      String namespace = context.getStringAttribute("namespace");
      if (namespace == null || namespace.equals("")) {
        throw new BuilderException("Mapper's namespace cannot be empty");
      }
      // 设置MapperBuilderAssistant的currentNamespace字段，记录当前命名空间
      builderAssistant.setCurrentNamespace(namespace);
      
      // 解析<cache-ref>节点
      cacheRefElement(context.evalNode("cache-ref"));
      
      // 解析<cache>节点
      cacheElement(context.evalNode("cache"));
      
      // 解析<parameterMap>节点(基本上未使用了)
      parameterMapElement(context.evalNodes("/mapper/parameterMap"));
      
      // 解析<resultMap>节点
      resultMapElements(context.evalNodes("/mapper/resultMap"));
      
      // 解析<sql>节点
      sqlElement(context.evalNodes("/mapper/sql"));
      
      // 解析<select><insert><update><delete>等Sql节点
      buildStatementFromContext(context.evalNodes("select|insert|update|delete"));
    } catch (Exception e) {
      throw new BuilderException("Error parsing Mapper XML. The XML location is '" + resource + "'. Cause: " + e, e);
    }
  }

  private void buildStatementFromContext(List<XNode> list) {
    if (configuration.getDatabaseId() != null) {
      buildStatementFromContext(list, configuration.getDatabaseId());
    }
    buildStatementFromContext(list, null);
  }
  // @param requiredDatabaseId为数据库厂商的id
  private void buildStatementFromContext(List<XNode> list, String requiredDatabaseId) {
    for (XNode context : list) {
      // 创建XMLStatementBuilder对象来进行SQL节点的解析
      final XMLStatementBuilder statementParser = new XMLStatementBuilder(configuration, builderAssistant, context, requiredDatabaseId);
      try {
        statementParser.parseStatementNode();
      } catch (IncompleteElementException e) {
        configuration.addIncompleteStatement(statementParser);
      }
    }
  }

  private void parsePendingResultMaps() {
    Collection<ResultMapResolver> incompleteResultMaps = configuration.getIncompleteResultMaps();
    synchronized (incompleteResultMaps) {
      Iterator<ResultMapResolver> iter = incompleteResultMaps.iterator();
      while (iter.hasNext()) {
        try {
          iter.next().resolve();
          iter.remove();
        } catch (IncompleteElementException e) {
          // ResultMap is still missing a resource...
        }
      }
    }
  }

  private void parsePendingCacheRefs() {
    Collection<CacheRefResolver> incompleteCacheRefs = configuration.getIncompleteCacheRefs();
    synchronized (incompleteCacheRefs) {
      Iterator<CacheRefResolver> iter = incompleteCacheRefs.iterator();
      while (iter.hasNext()) {
        try {
          iter.next().resolveCacheRef();
          iter.remove();
        } catch (IncompleteElementException e) {
          // Cache ref is still missing a resource...
        }
      }
    }
  }

  private void parsePendingStatements() {
	// 获取Configuration.incompleteStatements集合
    Collection<XMLStatementBuilder> incompleteStatements = configuration.getIncompleteStatements();
    synchronized (incompleteStatements) { // 遍历incompleteStatements集合
      Iterator<XMLStatementBuilder> iter = incompleteStatements.iterator();
      while (iter.hasNext()) {
        try {
          iter.next().parseStatementNode(); // 重新解析SQL语句节点
          iter.remove();// 移除XMLStatementBulider对象
        } catch (IncompleteElementException e) {
          // Statement is still missing a resource...
        }
      }
    }
  }

  private void cacheRefElement(XNode context) {
    if (context != null) {	
/*    <cache-ref namespace="com.someone.application.data.SomeMapper"/>*/
      
      // 将当前的Mapper配置文件的namespace与被引用的Cache所在的namespace之间的对应关系
      // 记录到Configuation.cacheRefMap集合中
      configuration.addCacheRef(builderAssistant.getCurrentNamespace(), context.getStringAttribute("namespace"));
      // 创建CacheRefResolver对象
      CacheRefResolver cacheRefResolver = new CacheRefResolver(builderAssistant, context.getStringAttribute("namespace"));
      try {
    	// 解析cache引用，该过程主要是设置MapperBuilderAssistant中的
    	// currentCache和unresolvedCacheRef字段
        cacheRefResolver.resolveCacheRef();
      } catch (IncompleteElementException e) {
    	// 如果解析异常的话，则添加到Configuration.incompleteCacheRef集合
        configuration.addIncompleteCacheRef(cacheRefResolver);
      }
    }
  }

  private void cacheElement(XNode context) throws Exception { 
/*	  <cache
	  eviction="FIFO"
	  flushInterval="60000"
	  size="512"
	  readOnly="true"/>  <cache>节点配置*/
    if (context != null) {
      // 获取<cache>节点的type属性，默认值为PERPETUAL
      String type = context.getStringAttribute("type", "PERPETUAL");
      
      // 查找type属性对应的Cache接口实现(即对应Cache对象的Class字面量)
      Class<? extends Cache> typeClass = typeAliasRegistry.resolveAlias(type);
      
      // 获取<cache>节点的eviction属性，默认值为LRU
      String eviction = context.getStringAttribute("eviction", "LRU");
      
      // 解析eviction属性的Cache装饰器(    可以配置多个     )
      Class<? extends Cache> evictionClass = typeAliasRegistry.resolveAlias(eviction);
      
      // 获取<cache>节点的flushInterval属性，默认值为null
      Long flushInterval = context.getLongAttribute("flushInterval");
      
      // 获取<cache>节点的size属性，默认值为null
      Integer size = context.getIntAttribute("size");
      
      // 获取<cache>节点的readOnly属性，默认值为false
      boolean readWrite = !context.getBooleanAttribute("readOnly", false);
      
      // 获取<cache>节点的blocking属性，默认值为false
      boolean blocking = context.getBooleanAttribute("blocking", false);
      
      // 获取<cache>节点的子节点，用于初始化二级缓存
      Properties props = context.getChildrenAsProperties();
      
      // 通过MapperBuilderAssistant创建Cache对象，并添加Configuation.caches集合中保存
      builderAssistant.useNewCache(typeClass, evictionClass, flushInterval, size, readWrite, blocking, props);
    }
  }

  private void parameterMapElement(List<XNode> list) throws Exception {
    for (XNode parameterMapNode : list) {
      String id = parameterMapNode.getStringAttribute("id");
      String type = parameterMapNode.getStringAttribute("type");
      Class<?> parameterClass = resolveClass(type);
      List<XNode> parameterNodes = parameterMapNode.evalNodes("parameter");
      List<ParameterMapping> parameterMappings = new ArrayList<ParameterMapping>();
      for (XNode parameterNode : parameterNodes) {
        String property = parameterNode.getStringAttribute("property");
        String javaType = parameterNode.getStringAttribute("javaType");
        String jdbcType = parameterNode.getStringAttribute("jdbcType");
        String resultMap = parameterNode.getStringAttribute("resultMap");
        String mode = parameterNode.getStringAttribute("mode");
        String typeHandler = parameterNode.getStringAttribute("typeHandler");
        Integer numericScale = parameterNode.getIntAttribute("numericScale");
        ParameterMode modeEnum = resolveParameterMode(mode);
        Class<?> javaTypeClass = resolveClass(javaType);
        JdbcType jdbcTypeEnum = resolveJdbcType(jdbcType);
        @SuppressWarnings("unchecked")
        Class<? extends TypeHandler<?>> typeHandlerClass = (Class<? extends TypeHandler<?>>) resolveClass(typeHandler);
        ParameterMapping parameterMapping = builderAssistant.buildParameterMapping(parameterClass, property, javaTypeClass, jdbcTypeEnum, resultMap, modeEnum, typeHandlerClass, numericScale);
        parameterMappings.add(parameterMapping);
      }
      builderAssistant.addParameterMap(id, parameterClass, parameterMappings);
    }
  }

  private void resultMapElements(List<XNode> list) throws Exception {
    for (XNode resultMapNode : list) { // 遍历resultMap节点
      try {
    	// resultMap可能有多个元素节点
        resultMapElement(resultMapNode);
      } catch (IncompleteElementException e) {
        // ignore, it will be retried
      }
    }
  }

  private ResultMap resultMapElement(XNode resultMapNode) throws Exception {
    return resultMapElement(resultMapNode, Collections.<ResultMapping> emptyList());
  }

  private ResultMap resultMapElement(XNode resultMapNode, List<ResultMapping> additionalResultMappings) throws Exception {
    ErrorContext.instance().activity("processing " + resultMapNode.getValueBasedIdentifier());
    
    // 获取<resultMap>的id属性，默认值会拼装所有父节点的id或value或property属性值
    // 在XNode.getValueBasedIdentifier()方法的实现
    // 获取id属性,当id属性不存在时,id -> value -> property = [parent_name]_[children_name]
    String id = resultMapNode.getStringAttribute("id",
        resultMapNode.getValueBasedIdentifier());
    
    
    // 获取<resultMap>的type属性，表示结果集将被映射成type指定类型的对象，注意其默认值
    // type = type -> ofType -> resultType -> javaType
    String type = resultMapNode.getStringAttribute("type",
        resultMapNode.getStringAttribute("ofType",
            resultMapNode.getStringAttribute("resultType",
                resultMapNode.getStringAttribute("javaType"))));
    
    // 获取<resultMap>的extends属性，该属性指定了该<resultMap>节点的继承关系
    String extend = resultMapNode.getStringAttribute("extends");
    
    // 获取<resultMap>的autoMapping属性，将属性设置为true，则启动自动映射功能
    // 即自动查找与列名同名的属性名，并调用setter方法。设置为false后，则需要在resultMap节点内
    // 明确注明映射关系才会调用对应的setter方法
    Boolean autoMapping = resultMapNode.getBooleanAttribute("autoMapping");
    
    Class<?> typeClass = resolveClass(type); // 解析type类型(生成type类型的Class对象)
    
    Discriminator discriminator = null;
    
    // 该集合用于记录解析的结果(resultMap子节点的属性)
    List<ResultMapping> resultMappings = new ArrayList<ResultMapping>();
    resultMappings.addAll(additionalResultMappings);  
    
    // 处理<resultMap>的子节点
    List<XNode> resultChildren = resultMapNode.getChildren();
    for (XNode resultChild : resultChildren) { // 遍历子节点类型
      if ("constructor".equals(resultChild.getName())) {
    	// 处理<constructor>节点
    	// 生成resultMapping对象并添加到resultMappings集合中
        processConstructorElement(resultChild, typeClass, resultMappings);
      } else if ("discriminator".equals(resultChild.getName())) {
    	// 处理<discriminator>节点 // 返回Discriminator对象
        discriminator = processDiscriminatorElement(resultChild, typeClass, resultMappings);
      } else {
    	// 处理<id>,<result>,<association>,<collection>等节点
        List<ResultFlag> flags = new ArrayList<ResultFlag>();
        if ("id".equals(resultChild.getName())) {
          flags.add(ResultFlag.ID);// 如果是<id>节点，则向flags集合中添加ResultFlag.ID
        }
        // 创建ResultMapping对象，并添加到resultMapping集合中保存
        resultMappings.add(buildResultMappingFromContext(resultChild, typeClass, flags));
      }
    }
    ResultMapResolver resultMapResolver = new ResultMapResolver(builderAssistant, id, typeClass, extend, discriminator, resultMappings, autoMapping);
    try {
      // 创建ResultMap对象，并添加到Configuration.resultMaps集合中，该集合类型StrictMap类型
      return resultMapResolver.resolve();
    } catch (IncompleteElementException  e) {
      configuration.addIncompleteResultMap(resultMapResolver);
      throw e;
    }
  }

  /**
   * 
   * @param resultChild
   * @param resultType   resultMap节点的type属性对应的CLass类示例
   * @param resultMappings
   * @throws Exception
   */
  private void processConstructorElement(XNode resultChild, Class<?> resultType, List<ResultMapping> resultMappings) throws Exception {
/*	<constructor>
	    <idArg column="user_id" javaType="long"/>
	    <arg column="name" javaType="String"/>
	</constructor>      <constructor>节点的示例配置    */
	List<XNode> argChildren = resultChild.getChildren(); // 获取<constructor>节点的子节点
    for (XNode argChild : argChildren) {  // 标记子节点
      List<ResultFlag> flags = new ArrayList<ResultFlag>();
      flags.add(ResultFlag.CONSTRUCTOR);  // 添加CONSTRUCTOR标志
      if ("idArg".equals(argChild.getName())) {
        flags.add(ResultFlag.ID);// 对于<idArg>节点，添加ID标志
      }
      // 创建ResultMapping对象，并添加到resultMapping集合中
      // resultType为resultMap的type属性对照的Class属性
      resultMappings.add(buildResultMappingFromContext(argChild, resultType, flags));
    }
  }

  private Discriminator processDiscriminatorElement(XNode context, Class<?> resultType, List<ResultMapping> resultMappings) throws Exception {
/*	<discriminator javaType="int" column="gender">
        <case value="1" resultType="emp">
            <association property="departMent" javaType="dep">
                <id column="did" property="id" />
                <result column="dept_name" property="departmentName" />
            </association>
        </case>
        <case value="0" resultType="emp">
            <id column="id" property="id" />
            <result column="email" property="lastName" />
            <result column="email" property="email" />
            <result column="gender" property="gender"/>
        </case>
    </discriminator>*/
	  
	// 获取column、javaType、jdbcType、typeHandler属性
	String column = context.getStringAttribute("column");
	
	// 获取javaType属性
    String javaType = context.getStringAttribute("javaType");
    
    // 获取jdbcType属性
    String jdbcType = context.getStringAttribute("jdbcType");
    
    // 获取typeHandler属性
    String typeHandler = context.getStringAttribute("typeHandler");
    
    Class<?> javaTypeClass = resolveClass(javaType); // 生成对应javaType的Class对象
    @SuppressWarnings("unchecked")
    Class<? extends TypeHandler<?>> typeHandlerClass = (Class<? extends TypeHandler<?>>) resolveClass(typeHandler);
    
    JdbcType jdbcTypeEnum = resolveJdbcType(jdbcType); // 枚举变量的使用
    
    // 处理<discriminator>节点的子节点
    Map<String, String> discriminatorMap = new HashMap<String, String>();
    for (XNode caseChild : context.getChildren()) {
      
      String value = caseChild.getStringAttribute("value");  // 获取value属性值(判断依据)
      
      // 调用processNestedResultMappings()方法创建嵌套的ResultMap对象
      // processNestedResultMappings()方法生成一个resultMap对象,并返回一个resultMap对象的id属性
      // 当resultMap -> resultMap.id 的准则来进行判断(resultMap)
      String resultMap = caseChild.getStringAttribute("resultMap", processNestedResultMappings(caseChild, resultMappings));
      
      // 记录该列值与对应选择的ResultMap的id   (ResultMap.id )
      discriminatorMap.put(value, resultMap); // 生成一个Map对象
    }
    
    // 创建Discriminator对象
    return builderAssistant.buildDiscriminator(resultType, column, javaTypeClass, jdbcTypeEnum, typeHandlerClass, discriminatorMap);
  }

  private void sqlElement(List<XNode> list) throws Exception {
    if (configuration.getDatabaseId() != null) { // 当数据库标识不为空时
      sqlElement(list, configuration.getDatabaseId());
    }
    sqlElement(list, null);
  }

  private void sqlElement(List<XNode> list, String requiredDatabaseId) throws Exception {
    for (XNode context : list) { // 遍历sql节点
      // 获取databaseId属性
      String databaseId = context.getStringAttribute("databaseId");
      String id = context.getStringAttribute("id"); // 获取id属性
      id = builderAssistant.applyCurrentNamespace(id, false); // 为id添加命名空间
      // 当前sql节点与databaseId进行匹配
      if (databaseIdMatchesCurrent(id, databaseId, requiredDatabaseId)) {
    	// 记录到XMLMapperBuilder.sqlFragments(Map<String, XNode>)中保存，
    	// 在XMLMapperBuilder的构造函数，可以看到该字段指向了Configuation.sqlFragments集合
        sqlFragments.put(id, context);
      }
    }
  }
  
  private boolean databaseIdMatchesCurrent(String id, String databaseId, String requiredDatabaseId) {
    if (requiredDatabaseId != null) {
      if (!requiredDatabaseId.equals(databaseId)) {
        return false;
      }
    } else {
      if (databaseId != null) {
        return false;
      }
      // skip this fragment if there is a previous one with a not null databaseId
      if (this.sqlFragments.containsKey(id)) { // sql语句片段
        XNode context = this.sqlFragments.get(id);
        if (context.getStringAttribute("databaseId") != null) { // sql节点对应的databaseId不为空
          return false;
        }
      }
    }
    return true;
  }
  /**
   * 
   * @param context     // 子节点
   * @param resultType  resultMap节点的type属性对应的Class字面量
   * @param flags  当子节点为<constructor>时,flag存在元素
   * @return
   * @throws Exception
   */
  private ResultMapping buildResultMappingFromContext(XNode context, Class<?> resultType, List<ResultFlag> flags) throws Exception {
    String property;
    if (flags.contains(ResultFlag.CONSTRUCTOR)) {
      property = context.getStringAttribute("name");
    } else {
      // 获取该节点的property的属性值
      property = context.getStringAttribute("property"); // 需要映射到JavaBean 的属性名称。
    }
    // 获取该节点的column的属性值   
    String column = context.getStringAttribute("column"); // 数据表的列名或者标签别名。 
    
    // 获取该节点的javaType的属性值
    // 一个完整的类名，或者是一个类型别名。如果你匹配的是一个JavaBean，那MyBatis 通常会自行检测到。
    // 然后，如果你是要映射到一个HashMap，那你需要指定javaType 要达到的目的。
    String javaType = context.getStringAttribute("javaType");
 
    
    // 获取该节点的jdbcType的属性值 数据表支持的类型列表。
    // 这个属性只在insert,update 或delete 的时候针对允许空的列有用。
    // JDBC 需要这项，但MyBatis 不需要。如果你是直接针对JDBC 编码，且有允许空的列，而你要指定这项。
    String jdbcType = context.getStringAttribute("jdbcType");
    
    // 获取该节点的select的属性值    // 嵌套查询,比如创建结果对象，构造函数实参需要另一个结果映射的对象
    String nestedSelect = context.getStringAttribute("select");
    
    // 如果未指定<association>节点的resultMap属性，则是匿名的嵌套映射，需要通过
    // processNestedResultMappings()方法解析匿名的嵌套映射
    // resultMap属性值 -> (association,collection,case)节点的
    
    // 对应的嵌套映射nestedResultMap的ID属性
    String nestedResultMap = context.getStringAttribute("resultMap",
    	 // 解析嵌套映射
        processNestedResultMappings(context, Collections.<ResultMapping> emptyList()));
    
    // 获取notNullColumn属性,不允许空行(在结果集映射使用)
    String notNullColumn = context.getStringAttribute("notNullColumn");
    
    // 获取columnPrefix属性,列名前缀(在结果集映射使用)
    String columnPrefix = context.getStringAttribute("columnPrefix");
    
    // 获取typeHandler属性,类型处理(在结果集映射使用)
    String typeHandler = context.getStringAttribute("typeHandler");
    
    // 获取resultSet属性,多结果集的时候(在结果集映射使用)
    String resultSet = context.getStringAttribute("resultSet");
    
    // 获取foreignColumn属性,外键(在结果集映射使用)
    String foreignColumn = context.getStringAttribute("foreignColumn");
    
    // 是否延迟加载
    boolean lazy = "lazy".equals(context.getStringAttribute("fetchType", configuration.isLazyLoadingEnabled() ? "lazy" : "eager"));
   
    // 解析javaType、typeHandler、jdbcType// 生成相应的Class类型
    Class<?> javaTypeClass = resolveClass(javaType);
    @SuppressWarnings("unchecked")
    Class<? extends TypeHandler<?>> typeHandlerClass = (Class<? extends TypeHandler<?>>) resolveClass(typeHandler);
    JdbcType jdbcTypeEnum = resolveJdbcType(jdbcType);
    
    // 创建ResultMapping对象
    return builderAssistant.buildResultMapping(resultType, property, column, javaTypeClass, jdbcTypeEnum, nestedSelect, nestedResultMap, notNullColumn, columnPrefix, typeHandlerClass, flags, resultSet, foreignColumn, lazy);
  }
  
  private String processNestedResultMappings(XNode context, List<ResultMapping> resultMappings) throws Exception {
      // 只会处理<association><collection><case>三种节点
	  if ("association".equals(context.getName())
        || "collection".equals(context.getName())
        || "case".equals(context.getName())) {
	  // 指定select属性之后，不会生成嵌套的ResultMap对象
      if (context.getStringAttribute("select") == null) {
    	// 递归调用resultMapElement()方法,解析association、collection、case三种节点
        ResultMap resultMap = resultMapElement(context, resultMappings);
        // 嵌套映射resultMap对应的id，用于在结果集映射查找
        return resultMap.getId(); // 返回resultMap的id值
      }
    }
    return null;
  }
  
  private void bindMapperForNamespace() {
	// 获取映射配置文件的命名空间
    String namespace = builderAssistant.getCurrentNamespace();
    if (namespace != null) {
      Class<?> boundType = null;
      try {
    	// 获取命名空间对应的Mapper接口类型Class变量
        boundType = Resources.classForName(namespace); // 解析命名空间对应的类型
      } catch (ClassNotFoundException e) {
    	// 抛出异常
        //ignore, bound type is not required
      }
      if (boundType != null) {
        if (!configuration.hasMapper(boundType)) { // 是否已经加载了boundType接口
          // Spring may not know the real resource name so we set a flag
          // to prevent loading again this resource from the mapper interface
          // look at MapperAnnotationBuilder#loadXmlResource
          // 追加namespace前缀，并添加到Configuation.loadedResources集合中保存
          configuration.addLoadedResource("namespace:" + namespace);
          // 调用Configuation.addMapper()方法，注册Mapper接口
          configuration.addMapper(boundType);
        }
      }
    }
  }

}
