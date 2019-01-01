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
package org.apache.ibatis.builder.xml;

import java.util.List;
import java.util.Locale;

import org.apache.ibatis.builder.BaseBuilder;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.apache.ibatis.executor.keygen.Jdbc3KeyGenerator;
import org.apache.ibatis.executor.keygen.KeyGenerator;
import org.apache.ibatis.executor.keygen.NoKeyGenerator;
import org.apache.ibatis.executor.keygen.SelectKeyGenerator;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.mapping.ResultSetType;
import org.apache.ibatis.mapping.SqlCommandType;
import org.apache.ibatis.mapping.SqlSource;
import org.apache.ibatis.mapping.StatementType;
import org.apache.ibatis.parsing.XNode;
import org.apache.ibatis.scripting.LanguageDriver;
import org.apache.ibatis.session.Configuration;

/**
 * @author Clinton Begin
 */
public class XMLStatementBuilder extends BaseBuilder {

  private final MapperBuilderAssistant builderAssistant; // 辅助操作类
  private final XNode context; // SQL节点
  private final String requiredDatabaseId; // 数据库Id

  public XMLStatementBuilder(Configuration configuration, MapperBuilderAssistant builderAssistant, XNode context) {
    this(configuration, builderAssistant, context, null);
  }

  public XMLStatementBuilder(Configuration configuration, MapperBuilderAssistant builderAssistant, XNode context, String databaseId) {
    super(configuration);
    this.builderAssistant = builderAssistant; // 辅助操作类
    this.context = context; // 具体的SQL节点，包括节点的属性与sql语句
    this.requiredDatabaseId = databaseId; // 数据库厂商ID
  }

  public void parseStatementNode() {
	  
	// 	在命名空间中唯一的标识符，可以被用来引用这条语句。  
	// 也即是对应的Mapper接口的中方法名
    String id = context.getStringAttribute("id");
    
    // 如果配置了 databaseIdProvider，MyBatis会加载所有的不带 databaseId 
    // 或匹配当前 databaseId 的语句；如果带或者不带的语句都有，则不带的会被忽略。
    String databaseId = context.getStringAttribute("databaseId");
    
    
	// 获取SQL节点的id以及databaseId属性，若其databaseId属性值与当前使用的数据库不匹配，则不加载
	// 该Sql节点，若存在相同id且  databaseId不为空的sql节点，则不再加载该Sql节点
    if (!databaseIdMatchesCurrent(id, databaseId, this.requiredDatabaseId)) {
      return;
    }
    
    // 获取SQL节点的属性值(fetchSize,timeout,parameterMap,parameterType)
    // (resultMap,resultType,lang,resultSetType)
    
    // 这是尝试影响驱动程序每次批量返回的结果行数和这个设置值相等。默认值为 unset（依赖驱动）。
    Integer fetchSize = context.getIntAttribute("fetchSize");
    
    // 这个设置是在抛出异常之前，驱动程序等待数据库返回请求结果的秒数。默认值为 unset（依赖驱动）。
    Integer timeout = context.getIntAttribute("timeout");
    
    String parameterMap = context.getStringAttribute("parameterMap");
    
    // 将会传入这条语句的参数类的完全限定名或别名。
    // 这个属性是可选的，因为 MyBatis 可以通过 TypeHandler 推断出具体传入语句的参数，默认值为 unset。
    String parameterType = context.getStringAttribute("parameterType");
    Class<?> parameterTypeClass = resolveClass(parameterType); // 获取参数类型的Class字面量
    
    // 	外部 resultMap 的命名引用。结果集的映射是 MyBatis 最强大的特性，
    // 对其有一个很好的理解的话，许多复杂映射的情形都能迎刃而解。使用 resultMap 或 resultType，但不能同时使用。
    String resultMap = context.getStringAttribute("resultMap");
    
    // 从这条语句中返回的期望类型的类的完全限定名或别名。
    // 注意如果是集合情形，那应该是集合可以包含的类型，而不能是集合本身。
    // 使用 resultType 或 resultMap，但不能同时使用。
    String resultType = context.getStringAttribute("resultType");
    
    String lang = context.getStringAttribute("lang");
    LanguageDriver langDriver = getLanguageDriver(lang);

    Class<?> resultTypeClass = resolveClass(resultType); // 获取参数类型的Class字面量
    
    // FORWARD_ONLY，SCROLL_SENSITIVE 或 SCROLL_INSENSITIVE 中的一个，默认值为 unset （依赖驱动）。
    String resultSetType = context.getStringAttribute("resultSetType");
    
    // STATEMENT，PREPARED 或 CALLABLE 的一个。
    // 这会让 MyBatis 分别使用 Statement，PreparedStatement 或 CallableStatement，默认值：PREPARED。
    StatementType statementType = StatementType.valueOf(context.getStringAttribute("statementType", StatementType.PREPARED.toString()));
    
    ResultSetType resultSetTypeEnum = resolveResultSetType(resultSetType);
    
    // 根据SQL节点的名称决定其SqlCommandType
    String nodeName = context.getNode().getNodeName(); // 获取节点名称
    
    // 转换为标准节点类型
    SqlCommandType sqlCommandType = SqlCommandType.valueOf(nodeName.toUpperCase(Locale.ENGLISH));
    
    boolean isSelect = sqlCommandType == SqlCommandType.SELECT;  // 判断是否为SELECT节点
    
    // 将其设置为 true，任何时候只要语句被调用，都会导致本地缓存和二级缓存都会被清空，默认值：false。
    
    // select节点时=默认为false,update|insert|delete节点=默认为true
    // flushCache表示会刷新一级和二级缓存
    boolean flushCache = context.getBooleanAttribute("flushCache", !isSelect);
    
    
    // 将其设置为 true，将会导致本条语句的结果被二级缓存，默认值：对 select 元素为 true。
    // select节点时=默认为true,update|insert|delete节点=默认为false
    boolean useCache = context.getBooleanAttribute("useCache", isSelect);
    
    // 这个设置仅针对嵌套结果 select 语句适用：如果为 true，就是假设包含了嵌套结果集或是分组了，
    // 这样的话当返回一个主结果行的时候，就不会发生有对前面结果集的引用的情况。
    // 这就使得在获取嵌套的结果集的时候不至于导致内存不够用。默认值：false。
    boolean resultOrdered = context.getBooleanAttribute("resultOrdered", false);

    // Include Fragments before parsing
    // 在解析SQL语句之前，先处理其中的<include>节点
    XMLIncludeTransformer includeParser = new XMLIncludeTransformer(configuration, builderAssistant);
    includeParser.applyIncludes(context.getNode());

    
    // Parse selectKey after includes and remove them.
    processSelectKeyNodes(id, parameterTypeClass, langDriver);// 处理<selectKey>节点
    
    // Parse the SQL (pre: <selectKey> and <include> were parsed and removed)
    // 下面是解析SQL节点的逻辑，也是parseStatementNode()方法的核心
    // 调用
    SqlSource sqlSource = langDriver.createSqlSource(configuration, context, parameterTypeClass);
    
    // 获取resultSets、keyProperty、keyColumn属性
    // 这个设置仅对多结果集的情况适用，它将列出语句执行后返回的结果集并每个结果集给一个名称，名称是逗号分隔的。
    String resultSets = context.getStringAttribute("resultSets");
    
    // 
    String keyProperty = context.getStringAttribute("keyProperty");
    
    // （仅对 insert 和 update 有用）通过生成的键值设置表中的列名，
    // 这个设置仅在某些数据库（像 PostgreSQL）是必须的，当主键列不是表中的第一列的时候需要设置。
    // 如果希望得到多个生成的列，也可以是逗号分隔的属性名称列表。
    String keyColumn = context.getStringAttribute("keyColumn");
    
    
    KeyGenerator keyGenerator;
    // 获取<selectKey>节点对应的SelectKeyGenerator的id id+!selectKey作为id标识
    String keyStatementId = id + SelectKeyGenerator.SELECT_KEY_SUFFIX;
    
    // 得到最终 namespace+id+!selectKey
    keyStatementId = builderAssistant.applyCurrentNamespace(keyStatementId, true);
    // 这里检测SQL节点中是否配置<selectKey>节点、SQL节点的useGeneratorKeys属性值、
    // mybatis-config.xml中全局的useGeneratorKeys配置，以及是否为insert语句，决定使用的
    // KeyGenerator接口实现
    
    
    // 看是否有keyStatementId对应的KeyGenerator对象
    if (configuration.hasKeyGenerator(keyStatementId)) { 
      keyGenerator = configuration.getKeyGenerator(keyStatementId);
    } else {
    	
      // （仅对 insert 和 update 有用）这会令 MyBatis 
      // 使用 JDBC 的 getGeneratedKeys 方法来取出由数据库内部生成的主键（比如：像 MySQL 和 SQL Server 这样的关系数据库管理系统的自动递增字段），
      // 默认值：false。
      // 默认值排序:useGeneratedKeys -> Jdbc3KeyGenerator -> NoKeyGenerator
      keyGenerator = context.getBooleanAttribute("useGeneratedKeys",
          // Configuration.useGeneratedKeys配置项允许使用(主键)
          configuration.isUseGeneratedKeys() && SqlCommandType.INSERT.equals(sqlCommandType))
          ? Jdbc3KeyGenerator.INSTANCE : NoKeyGenerator.INSTANCE;
    }
    // 通过MapperBuilderAssistant创建MappedStatement对象，并添加到Configuation.mappedStatement
    // 集合中保存
    builderAssistant.addMappedStatement(id, sqlSource, statementType, sqlCommandType,
        fetchSize, timeout, parameterMap, parameterTypeClass, resultMap, resultTypeClass,
        resultSetTypeEnum, flushCache, useCache, resultOrdered, 
        keyGenerator, keyProperty, keyColumn, databaseId, langDriver, resultSets);
  }

  private void processSelectKeyNodes(String id, Class<?> parameterTypeClass, LanguageDriver langDriver) {
/*	  <selectKey keyProperty="id" resultType="int" order="BEFORE">
	    select CAST(RANDOM()*1000000 as INTEGER) a from SYSIBM.SYSDUMMY1
	  </selectKey>*/
	// 获取全部的<selectKey>节点
	List<XNode> selectKeyNodes = context.evalNodes("selectKey");
	// 解析<selectKey>节点
    if (configuration.getDatabaseId() != null) {
      parseSelectKeyNodes(id, selectKeyNodes, parameterTypeClass, langDriver, configuration.getDatabaseId());
    }
    parseSelectKeyNodes(id, selectKeyNodes, parameterTypeClass, langDriver, null);
    removeSelectKeyNodes(selectKeyNodes);// 移除<selectKey>节点
  }

  private void parseSelectKeyNodes(String parentId, List<XNode> list, Class<?> parameterTypeClass, LanguageDriver langDriver, String skRequiredDatabaseId) {
    for (XNode nodeToHandle : list) {
      // 为<selectKey>节点生成id，检测databaseId是否匹配以及是否已经加载过相同的id且databaseId不为空的
      // <selectKey>节点，并调用parseSelectKeyNode()方法处理每个<selectKey>节点
      String id = parentId + SelectKeyGenerator.SELECT_KEY_SUFFIX;
      String databaseId = nodeToHandle.getStringAttribute("databaseId");
      
      
      if (databaseIdMatchesCurrent(id, databaseId, skRequiredDatabaseId)) {
        parseSelectKeyNode(id, nodeToHandle, parameterTypeClass, langDriver, databaseId);
      }
    }
  }

  /**
   * 
   * @param id    sql节点的id + !selectKey
   * @param nodeToHandle  <selectKey>节点
   * @param parameterTypeClass  SQL节点的参数类型
   * @param langDriver LanguageDriver对象，用于创建SqlSource对象
   * @param databaseId 数据库厂商id
   */
  private void parseSelectKeyNode(String id, XNode nodeToHandle, Class<?> parameterTypeClass, LanguageDriver langDriver, String databaseId) {
    // 获取<selectKey>节点属性(resultType/keyProperty/keyColumn)
	
	// 结果的类型。MyBatis 通常可以推算出来，但是为了更加确定写上也不会有什么问题。
	// MyBatis 允许任何简单类型用作主键的类型，包括字符串。
	// 如果希望作用于多个生成的列，则可以使用一个包含期望属性的 Object 或一个 Map。
	String resultType = nodeToHandle.getStringAttribute("resultType");
    Class<?> resultTypeClass = resolveClass(resultType); // 对应的Class字面量
    
    // 与前面相同，MyBatis 支持 STATEMENT，PREPARED 和 CALLABLE 语句的映射类型，分别代表 PreparedStatement 和 CallableStatement 类型。
    StatementType statementType = StatementType.valueOf(nodeToHandle.getStringAttribute("statementType", StatementType.PREPARED.toString()));
    
    // selectKey 语句结果应该被设置的目标属性。如果希望得到多个生成的列，也可以是逗号分隔的属性名称列表。
    String keyProperty = nodeToHandle.getStringAttribute("keyProperty");
    
    // 	匹配属性的返回结果集中的列名称。如果希望得到多个生成的列，也可以是逗号分隔的属性名称列表。
    String keyColumn = nodeToHandle.getStringAttribute("keyColumn");
    
    // 这可以被设置为 BEFORE 或 AFTER。
    // 如果设置为 BEFORE，那么它会首先选择主键，设置 keyProperty 然后执行插入语句。
    // 如果设置为 AFTER，那么先执行插入语句，然后是 selectKey 元素 - 这和像 Oracle 的数据库相似，在插入语句内部可能有嵌入索引调用。
    
    // 默认值为AFTER属性
    boolean executeBefore = "BEFORE".equals(nodeToHandle.getStringAttribute("order", "AFTER"));

    //defaults
    // 设置一系列MappedStatement对象需要的默认值，例如useCache、resultOrdered
    boolean useCache = false;
    boolean resultOrdered = false;
    KeyGenerator keyGenerator = NoKeyGenerator.INSTANCE;
    Integer fetchSize = null;
    Integer timeout = null;
    boolean flushCache = false;
    String parameterMap = null;
    String resultMap = null;
    ResultSetType resultSetTypeEnum = null;
    
    // 通过LanguageDriver.createSqlSource()方法生成SqlSource
    // sqlSource中封装了动态Sql节点的相关信息
    SqlSource sqlSource = langDriver.createSqlSource(configuration, nodeToHandle, parameterTypeClass);
    
    // <selectKey>节点中只能配置SELECT语句
    SqlCommandType sqlCommandType = SqlCommandType.SELECT;
   
    // 通过MapperBuilderAssistant创建MappedStatement对象，并添加到Configuation.mappedStatement
    // 集合中保存，该集合为StructMap<MappedStatement>类型
    // ###标志
    builderAssistant.addMappedStatement(id, sqlSource, statementType, sqlCommandType,
        fetchSize, timeout, parameterMap, parameterTypeClass, resultMap, resultTypeClass,
        resultSetTypeEnum, flushCache, useCache, resultOrdered,
        keyGenerator, keyProperty, keyColumn, databaseId, langDriver, null);

    // 修饰字符串[namespace.id]
    id = builderAssistant.applyCurrentNamespace(id, false);

    // 获取###处创建MappedStatement对象
    MappedStatement keyStatement = configuration.getMappedStatement(id, false);
    // 创建<selectKey>节点对应的KeyGenerator对象，添加到Configuation.KeyGenerator集合中
    // 保存，Configuation.KeyGenerator字段是StructMap<KeyGenerator>类型的对象
    configuration.addKeyGenerator(id, new SelectKeyGenerator(keyStatement, executeBefore));
  }

  private void removeSelectKeyNodes(List<XNode> selectKeyNodes) {
    for (XNode nodeToHandle : selectKeyNodes) {
      // 删除<selectKey>节点
      nodeToHandle.getParent().getNode().removeChild(nodeToHandle.getNode());
    }
  }

  private boolean databaseIdMatchesCurrent(String id, String databaseId, String requiredDatabaseId) {
    if (requiredDatabaseId != null) { // requiredDatabaseId不为空
      if (!requiredDatabaseId.equals(databaseId)) { // databaseId与requiredDatabaseId不相等时
        return false;
      }
    } else {
      if (databaseId != null) {
        return false;
      }
      // skip this statement if there is a previous one with a not null databaseId
      id = builderAssistant.applyCurrentNamespace(id, false);
      if (this.configuration.hasStatement(id, false)) {
        MappedStatement previous = this.configuration.getMappedStatement(id, false); // issue #2
        if (previous.getDatabaseId() != null) {
          return false;
        }
      }
    }
    return true;
  }

  private LanguageDriver getLanguageDriver(String lang) {
    Class<?> langClass = null;
    if (lang != null) {
      langClass = resolveClass(lang); // 获取对应Class类型
    }
    return builderAssistant.getLanguageDriver(langClass);
  }

}
