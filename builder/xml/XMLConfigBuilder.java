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

import java.io.InputStream;
import java.io.Reader;
import java.util.Properties;
import javax.sql.DataSource;

import org.apache.ibatis.builder.BaseBuilder;
import org.apache.ibatis.builder.BuilderException;
import org.apache.ibatis.datasource.DataSourceFactory;
import org.apache.ibatis.executor.ErrorContext;
import org.apache.ibatis.executor.loader.ProxyFactory;
import org.apache.ibatis.io.Resources;
import org.apache.ibatis.io.VFS;
import org.apache.ibatis.logging.Log;
import org.apache.ibatis.mapping.DatabaseIdProvider;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.parsing.XNode;
import org.apache.ibatis.parsing.XPathParser;
import org.apache.ibatis.plugin.Interceptor;
import org.apache.ibatis.reflection.DefaultReflectorFactory;
import org.apache.ibatis.reflection.MetaClass;
import org.apache.ibatis.reflection.ReflectorFactory;
import org.apache.ibatis.reflection.factory.ObjectFactory;
import org.apache.ibatis.reflection.wrapper.ObjectWrapperFactory;
import org.apache.ibatis.session.AutoMappingBehavior;
import org.apache.ibatis.session.AutoMappingUnknownColumnBehavior;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.ExecutorType;
import org.apache.ibatis.session.LocalCacheScope;
import org.apache.ibatis.transaction.TransactionFactory;
import org.apache.ibatis.type.JdbcType;
import org.apache.ibatis.type.TypeHandler;

/**
 * @author Clinton Begin
 * @author Kazuki Shimizu
 */
public class XMLConfigBuilder extends BaseBuilder {

  // 标识是否已经解析过mybatis-config.xml配置文件
  private boolean parsed;
  
  // 用于解析mybatis-config.xml配置文件的XPathParser对象
  private final XPathParser parser;
  
  // 标识<environment>配置的名称，默认读取<environment>标签的默认(default)属性
  private String environment;
  
  // ReflectorFactory负责创建和缓存Reflector对象
  private final ReflectorFactory localReflectorFactory = new DefaultReflectorFactory();

  public XMLConfigBuilder(Reader reader) {
    this(reader, null, null);
  }

  public XMLConfigBuilder(Reader reader, String environment) {
    this(reader, environment, null);
  }
  // 解析XML文件
  public XMLConfigBuilder(Reader reader, String environment, Properties props) {
	// new XPathParser(reader, true, props, new XMLMapperEntityResolver()
	// 返回一个XPathParser对象，其中包含xml文件的文档树结构
    this(new XPathParser(reader, true, props, new XMLMapperEntityResolver()), environment, props);
  }

  public XMLConfigBuilder(InputStream inputStream) {
    this(inputStream, null, null);
  }

  public XMLConfigBuilder(InputStream inputStream, String environment) {
    this(inputStream, environment, null);
  }

  public XMLConfigBuilder(InputStream inputStream, String environment, Properties props) {
    this(new XPathParser(inputStream, true, props, new XMLMapperEntityResolver()), environment, props);
  }

  private XMLConfigBuilder(XPathParser parser, String environment, Properties props) {
    //配置属性 (完成基础的别名、类型处理器的工作) 
	super(new Configuration()); // 完成别名的注册
	
    ErrorContext.instance().resource("SQL Mapper Configuration");
    this.configuration.setVariables(props);
    this.parsed = false;
    this.environment = environment;
    this.parser = parser; // 用于解析.xml文件
  }

  public Configuration parse() {
	// 根据parsed变量的值，判断是否已经完成了对mybatis-config.xml配置文件的解析
    if (parsed) {
      throw new BuilderException("Each XMLConfigBuilder can only be used once.");
    }
    parsed = true;
    // parser.evalNode("/configuration") 从文档树结构中获取rootElement元素
    // configuration节点为根节点。在configuration节点之下，我们可以配置10个子节点， 
    // 分别为：properties、typeAliases、plugins、objectFactory、objectWrapperFactory、
    // settings、environments、databaseIdProvider、typeHandlers、mappers。
    parseConfiguration(parser.evalNode("/configuration"));
    return configuration;
  }
  // 具体的解析mybatis-config.xml配置文件过程
  private void parseConfiguration(XNode root) {
    try {
      
      // issue #117 read properties first
      // 解析<properties>节点
      propertiesElement(root.evalNode("properties"));
      
      // 解析<settings>节点
      Properties settings = settingsAsProperties(root.evalNode("settings"));
      loadCustomVfs(settings); // 设置vfsImpl字段
      
      // 解析<typeAliases>节点
      typeAliasesElement(root.evalNode("typeAliases"));
      
      // 解析<plugins>节点
      pluginElement(root.evalNode("plugins"));
      
      // 解析<objectFactory>节点
      objectFactoryElement(root.evalNode("objectFactory"));
      
      // 解析<objectWrapperFactory>节点
      objectWrapperFactoryElement(root.evalNode("objectWrapperFactory"));
      
      // 解析<reflectorFactory>节点
      reflectorFactoryElement(root.evalNode("reflectorFactory"));
      settingsElement(settings); // 将settings设置到Configuation
      
      // read it after objectFactory and objectWrapperFactory issue #631
      // 解析<environments>节点
      environmentsElement(root.evalNode("environments"));
      
      // 解析<databaseIdProvider>节点
      databaseIdProviderElement(root.evalNode("databaseIdProvider"));
      
      // 解析<typeHandlers>节点
      typeHandlerElement(root.evalNode("typeHandlers"));
      
      // 解析<mappers>节点
      mapperElement(root.evalNode("mappers"));
      
    } catch (Exception e) {
      throw new BuilderException("Error parsing SQL Mapper Configuration. Cause: " + e, e);
    }
  }
  // 在setting节点的配置信息是Mybatis全局性的信息，它们会改变Mybatis运行时的行为
  // 在解析时全局配置信息都会配置到Configuation对象的对应属性上
  private Properties settingsAsProperties(XNode context) {
    if (context == null) {
      return new Properties();
    }
    // 解析<settings>的子节点(<setting>标签)的name属性和value属性,并返回Properties对象
/*  <settings>
        <setting name="cacheEnabled" value="true" />
    </settings>*/  // setting配置示例
    Properties props = context.getChildrenAsProperties(); 
    // Check that all settings are known to the configuration class
    // 创建Configuration对应的MetaClass对象
    MetaClass metaConfig = MetaClass.forClass(Configuration.class, localReflectorFactory);
    // 检测Configuration中是否已经定义了key指定属性相应的setter方法
    for (Object key : props.keySet()) {
      // 检测Configuration类是否有相应属性的setter方法
      if (!metaConfig.hasSetter(String.valueOf(key))) {
        throw new BuilderException("The setting " + key + " is not known.  Make sure you spelled it correctly (case sensitive).");
      }
    }
    return props;
  }

  private void loadCustomVfs(Properties props) throws ClassNotFoundException {
    String value = props.getProperty("vfsImpl"); // 获取vfsImpl字段
    if (value != null) {
      String[] clazzes = value.split(","); // 有可能有多个，以,分隔
      for (String clazz : clazzes) {
        if (!clazz.isEmpty()) {
          @SuppressWarnings("unchecked") // ? extends VFS是继承VFS的类型(限定了条件)
          Class<? extends VFS> vfsImpl = (Class<? extends VFS>)Resources.classForName(clazz);
          configuration.setVfsImpl(vfsImpl); // 设置Configuration.vfsImpl与VFS.USER_IMPLEMENTATIONS字段
        }
      }
    }
  }

  private void typeAliasesElement(XNode parent) {
    if (parent != null) {
      for (XNode child : parent.getChildren()) { // 处理全部子节点
        if ("package".equals(child.getName())) { // 处理<package>节点
          // 获取指定的包名
          String typeAliasPackage = child.getStringAttribute("name");
          // 通过TypeAliasRegistry扫描指定包中所有的类，并解析@Aliases注解，完成别名注册
          configuration.getTypeAliasRegistry().registerAliases(typeAliasPackage);
        } else {// 处理<typeAlias>节点
/*        <typeAliases>
               <typeAlias alias="customer" type="com.wang.po.Customer"/>
          </typeAliases>*/  // 别名注册示例
          String alias = child.getStringAttribute("alias"); // 获取指定的别名
          String type = child.getStringAttribute("type");// 获取别名对应的类型
          try {
            Class<?> clazz = Resources.classForName(type); 
            if (alias == null) {
              typeAliasRegistry.registerAlias(clazz); // 扫描@Alias注解，完成注册
            } else {
              typeAliasRegistry.registerAlias(alias, clazz); // 注册别名
            }
          } catch (ClassNotFoundException e) {
        	// 抛出异常
            throw new BuilderException("Error registering typeAlias for '" + alias + "'. Cause: " + e, e);
          }
        }
      }
    }
  }

  private void pluginElement(XNode parent) throws Exception {
    if (parent != null) {
      for (XNode child : parent.getChildren()) {// 遍历全部子节点(即<plugin节点>)
    	// 获取<plugin>节点的interceptor属性的值
/*    	<plugins>
    	    <plugin interceptor="com.plugins.interceptors.LogPlugin" />    
    	</plugins>  插件配置的示例         */
        String interceptor = child.getStringAttribute("interceptor");
        // 获取<plugin>节点下<properties>配置的信息，并形成Properties对象
        Properties properties = child.getChildrenAsProperties();
        
        // 通过前面的介绍的TypeAliasRegistry解析别名之后，实例化Interceptor对象
        Interceptor interceptorInstance = (Interceptor) resolveClass(interceptor).newInstance();
        interceptorInstance.setProperties(properties); // 设置Interceptor的属性
        configuration.addInterceptor(interceptorInstance);// 记录Interceptor对象
      }
    }
  }

  private void objectFactoryElement(XNode context) throws Exception {
    if (context != null) {
      
      /*<objectFactory type="com.majing.learning.mybatis.reflect.objectfactory.ExampleObjectFactory"></objectFactory>*/
      // objectFactory节点配置示例
      // 获取<objectFactory>节点的type属性的值
      String type = context.getStringAttribute("type");
      
      // 获取<objectFactory>节点下配置的信息，并形成Properties对象
      Properties properties = context.getChildrenAsProperties();
      
      // 通过前面的介绍的TypeAliasRegistry解析别名之后，实例化objectFactory对象
      ObjectFactory factory = (ObjectFactory) resolveClass(type).newInstance();  // 生成实例  
      
      // 设置自定义的objectFactory的属性，完成初始化的相关操作(properties初始化)
      factory.setProperties(properties);
      
      // 将自定义的objectFactory对象记录到Configuation对象的objectFactory字段中
      configuration.setObjectFactory(factory); // 默认的是DefaultObjectFactory实现类
    }
  }

  private void objectWrapperFactoryElement(XNode context) throws Exception {
    if (context != null) {
      // 获取<objectWrapperFactory>节点的type属性的值
      String type = context.getStringAttribute("type");
      
      // 通过前面的介绍的TypeAliasRegistry解析别名之后，实例化objectWrapperFactory对象
      ObjectWrapperFactory factory = (ObjectWrapperFactory) resolveClass(type).newInstance();
      
      // 设置自定义的objectWrapperFactory的属性，完成初始化的相关操作
      configuration.setObjectWrapperFactory(factory); // 默认的实现类为DefaultObjectWrapperFactory
    }
  }

  private void reflectorFactoryElement(XNode context) throws Exception {
    if (context != null) {
    	
       // 获取<reflectorFactory>节点的type属性的值
       String type = context.getStringAttribute("type");
       // 通过前面的介绍的TypeAliasRegistry解析别名之后，实例化reflectorFactory对象
       ReflectorFactory factory = (ReflectorFactory) resolveClass(type).newInstance();
       // 设置自定义的reflectorFactory的属性，完成初始化的相关操作
       configuration.setReflectorFactory(factory); // 默认是DefaultReflectorFactory
    }
  }
  // 解析<properties>节点   <properties resource="db.properties"/>
  private void propertiesElement(XNode context) throws Exception {
    if (context != null) {
    	
      // 解析<properties>的子节点(<property>标签)的name属性和value属性
      Properties defaults = context.getChildrenAsProperties();
      
      // 解析<properties>的resource属性与url属性，这两个属性用于确定properties配置文件的位置
      String resource = context.getStringAttribute("resource");
      String url = context.getStringAttribute("url");
      
      // resource属性与url属性不能同时存在
      if (resource != null && url != null) {
        throw new BuilderException("The properties element cannot specify both a URL and a resource based property file reference.  Please specify one or the other.");
      }
      // 加载resource属性或url属性指定的properties文件
      if (resource != null) {
    	// Resources.getResourceAsProperties(resource)返回的是Properties对象
        defaults.putAll(Resources.getResourceAsProperties(resource));
      } else if (url != null) {
        defaults.putAll(Resources.getUrlAsProperties(url));
      }
      // 与Configuration对象中variables集合合并
      Properties vars = configuration.getVariables();
      if (vars != null) {
        defaults.putAll(vars); // 形成属性键值对
      }
      // 更新XPathParser和Configuation的variables字段
      parser.setVariables(defaults);
      configuration.setVariables(defaults);
    }
  }
  // 设置configuration对象的相关的字段
  private void settingsElement(Properties props) throws Exception {
    configuration.setAutoMappingBehavior(AutoMappingBehavior.valueOf(props.getProperty("autoMappingBehavior", "PARTIAL")));
    configuration.setAutoMappingUnknownColumnBehavior(AutoMappingUnknownColumnBehavior.valueOf(props.getProperty("autoMappingUnknownColumnBehavior", "NONE")));
    configuration.setCacheEnabled(booleanValueOf(props.getProperty("cacheEnabled"), true));
    configuration.setProxyFactory((ProxyFactory) createInstance(props.getProperty("proxyFactory")));
    configuration.setLazyLoadingEnabled(booleanValueOf(props.getProperty("lazyLoadingEnabled"), false));
    configuration.setAggressiveLazyLoading(booleanValueOf(props.getProperty("aggressiveLazyLoading"), false));
    configuration.setMultipleResultSetsEnabled(booleanValueOf(props.getProperty("multipleResultSetsEnabled"), true));
    configuration.setUseColumnLabel(booleanValueOf(props.getProperty("useColumnLabel"), true));
    configuration.setUseGeneratedKeys(booleanValueOf(props.getProperty("useGeneratedKeys"), false));
    configuration.setDefaultExecutorType(ExecutorType.valueOf(props.getProperty("defaultExecutorType", "SIMPLE")));
    configuration.setDefaultStatementTimeout(integerValueOf(props.getProperty("defaultStatementTimeout"), null));
    configuration.setDefaultFetchSize(integerValueOf(props.getProperty("defaultFetchSize"), null));
    configuration.setMapUnderscoreToCamelCase(booleanValueOf(props.getProperty("mapUnderscoreToCamelCase"), false));
    configuration.setSafeRowBoundsEnabled(booleanValueOf(props.getProperty("safeRowBoundsEnabled"), false));
    configuration.setLocalCacheScope(LocalCacheScope.valueOf(props.getProperty("localCacheScope", "SESSION")));
    configuration.setJdbcTypeForNull(JdbcType.valueOf(props.getProperty("jdbcTypeForNull", "OTHER")));
    configuration.setLazyLoadTriggerMethods(stringSetValueOf(props.getProperty("lazyLoadTriggerMethods"), "equals,clone,hashCode,toString"));
    configuration.setSafeResultHandlerEnabled(booleanValueOf(props.getProperty("safeResultHandlerEnabled"), true));
    configuration.setDefaultScriptingLanguage(resolveClass(props.getProperty("defaultScriptingLanguage")));
    @SuppressWarnings("unchecked")
    Class<? extends TypeHandler> typeHandler = (Class<? extends TypeHandler>)resolveClass(props.getProperty("defaultEnumTypeHandler"));
    configuration.setDefaultEnumTypeHandler(typeHandler);
    configuration.setCallSettersOnNulls(booleanValueOf(props.getProperty("callSettersOnNulls"), false));
    configuration.setUseActualParamName(booleanValueOf(props.getProperty("useActualParamName"), true));
    configuration.setReturnInstanceForEmptyRow(booleanValueOf(props.getProperty("returnInstanceForEmptyRow"), false));
    configuration.setLogPrefix(props.getProperty("logPrefix"));
    @SuppressWarnings("unchecked")
    Class<? extends Log> logImpl = (Class<? extends Log>)resolveClass(props.getProperty("logImpl"));
    configuration.setLogImpl(logImpl);
    configuration.setConfigurationFactory(resolveClass(props.getProperty("configurationFactory")));
  }

  private void environmentsElement(XNode context) throws Exception {
    if (context != null) {
      if (environment == null) {
    	// 未指定XMLConfigBuilder.environment字段，则使用默认的default属性指定<environment>
        environment = context.getStringAttribute("default");
      }
/*    <environments default="development">
      <!-- 本地开发环境 -->
      <environment id="default">
        <transactionManager type="JDBC">
            <property name="1" value="1"/>
        </transactionManager>
        <dataSource type="UNPOOLED">
          <property name="driver" value="com.mysql.jdbc.Driver"/>
          <property name="url" value="jdbc:mysql://127.0.0.1:3306/rfunbook?useUnicode=true&amp;characterEncoding=UTF-8"/>
          <property name="username" value="root"/>
          <property name="password" value="root"/>
       </dataSource>
      </environment>
    </environments>  <environment>节点配置示例*/
      for (XNode child : context.getChildren()) { // 遍历子节点(即<environment>节点)
        String id = child.getStringAttribute("id");
        if (isSpecifiedEnvironment(id)) {// 与XMLConfigBuilder.environment字段匹配
        	
          // 创建TransactionFactory，具体实现是先通过TypeAliasRegistry解析别名之后，实例化TransactionFactory
          TransactionFactory txFactory = transactionManagerElement(child.evalNode("transactionManager"));
          
          // 创建DataSourceFactory与DataSource
          DataSourceFactory dsFactory = dataSourceElement(child.evalNode("dataSource"));
          DataSource dataSource = dsFactory.getDataSource();
          // 创建Environment，Environment中封装了上面创建的TransactionFactory对象以及DataSource对象
          
          Environment.Builder environmentBuilder = new Environment.Builder(id) // 创建Builder对象
              .transactionFactory(txFactory) // 设置transactionFactory字段
              .dataSource(dataSource); // 设置dataSource字段
          
          // 将Environment对象记录到Configuration.environment字段中
          // 下面environmentBuilder.build()将生成Environment对象并记录到configuration对象中
          configuration.setEnvironment(environmentBuilder.build());
        }
      }
    }
  }

  private void databaseIdProviderElement(XNode context) throws Exception {
	
/*  <databaseIdProvider type="DB_VENDOR">
        <property name="Oracle" value="oracle"/>
        <property name="MySQL" value="mysql"/>
        <property name="DB2" value="d2"/>
    </databaseIdProvider>   DatabaseIdProvider配置示例*/
    DatabaseIdProvider databaseIdProvider = null;
    if (context != null) {
      String type = context.getStringAttribute("type");
      // awful patch to keep backward compatibility
      if ("VENDOR".equals(type)) { // 为了保证兼容性，修改type取值
          type = "DB_VENDOR";
      }
      Properties properties = context.getChildrenAsProperties(); // 解析相关配置信息
      
      // 创建DatabaseIdProvider对象
      databaseIdProvider = (DatabaseIdProvider) resolveClass(type).newInstance();
      // 配置DatabaseIdProvider，完成初始化(添加到properties中去)
      databaseIdProvider.setProperties(properties);
    }
    // 设置相关的配置
    Environment environment = configuration.getEnvironment();
    if (environment != null && databaseIdProvider != null) {
      // 通过前面的DataSource获取databaseId，并记录到Configuation.databaseId字段中
      String databaseId = databaseIdProvider.getDatabaseId(environment.getDataSource());
      
      configuration.setDatabaseId(databaseId);  // 假设数据库为MySQL,则databaseId = mysql;
    }
  }

  private TransactionFactory transactionManagerElement(XNode context) throws Exception {
/*  <transactionManager type="JDBC">
      <property name="1" value="1"/>
    </transactionManager>事务管理配置示例*/
	if (context != null) {
      String type = context.getStringAttribute("type");
      // 获取设置的name与value属性值
      Properties props = context.getChildrenAsProperties();
      // 创建TransactionFactory对象
      TransactionFactory factory = (TransactionFactory) resolveClass(type).newInstance();
      factory.setProperties(props); // 设置属性
      return factory;
    }
    throw new BuilderException("Environment declaration requires a TransactionFactory.");
  }

  private DataSourceFactory dataSourceElement(XNode context) throws Exception {
/*  <dataSource type="UNPOOLED">
       <property name="driver" value="com.mysql.jdbc.Driver"/>
       <property name="url" value="jdbc:mysql://127.0.0.1:3306/rfunbook?useUnicode=true&amp;characterEncoding=UTF-8"/>
       <property name="username" value="root"/>
       <property name="password" value="root"/>
    </dataSource> datasource配置示例*/
	if (context != null) {
      String type = context.getStringAttribute("type"); // 获取type类型
      Properties props = context.getChildrenAsProperties();// 获取name与value属性值
      
      DataSourceFactory factory = (DataSourceFactory) resolveClass(type).newInstance();
      factory.setProperties(props);
      return factory;
    }
    throw new BuilderException("Environment declaration requires a DataSourceFactory.");
  }

  private void typeHandlerElement(XNode parent) throws Exception {
/*	  <typeHandlers>
	         <typeHandler handler="cn.typeHandler.MyDemoTypeHandler" javaType="String" jdbcType="INTEGER"/>
	  </typeHandlers>*/
    if (parent != null) {
      for (XNode child : parent.getChildren()) {// 处理全部子节点
        if ("package".equals(child.getName())) {// 处理<package>节点
          // 获取指定的包名
          String typeHandlerPackage = child.getStringAttribute("name");
          // 通过TypeAliasRegistry扫描指定包中所有的类，并解析@Aliases注解，完成别名注册
          typeHandlerRegistry.register(typeHandlerPackage);
        } else {
          // 获取相应的属性
          String javaTypeName = child.getStringAttribute("javaType");
          String jdbcTypeName = child.getStringAttribute("jdbcType");
          String handlerTypeName = child.getStringAttribute("handler");
          
          // 对获得属性进行处理(获得对应javaTypeClass、jdbcType、typeHandlerClass对应的CLass字面量)
          Class<?> javaTypeClass = resolveClass(javaTypeName);
          JdbcType jdbcType = resolveJdbcType(jdbcTypeName);
          Class<?> typeHandlerClass = resolveClass(handlerTypeName);
          
          if (javaTypeClass != null) {
            if (jdbcType == null) {
              typeHandlerRegistry.register(javaTypeClass, typeHandlerClass);
            } else {
              typeHandlerRegistry.register(javaTypeClass, jdbcType, typeHandlerClass);
            }
          } else {
            typeHandlerRegistry.register(typeHandlerClass);
          }
        }
      }
    }
  }

  private void mapperElement(XNode parent) throws Exception {
	  
/*	<mappers>
		<mapper url="file:///var/mappers/AuthorMapper.xml"/>
	</mappers>  <mapper>节点配置示例*/
    if (parent != null) {
      for (XNode child : parent.getChildren()) { // 处理<mappers>子节点
        if ("package".equals(child.getName())) {// <package>子节点
          String mapperPackage = child.getStringAttribute("name"); 
          // 扫描指定的包，并向MapperRegistry注册Mapper接口
          configuration.addMappers(mapperPackage);
        } else {
          // 获取<resource>/<url>/<class>属性，这三种只能同时存在一种
          String resource = child.getStringAttribute("resource");
          String url = child.getStringAttribute("url");
          String mapperClass = child.getStringAttribute("class");
          
          // 如果<Mapper>节点指定了resource，则创建XMLMapperBuilder对象并解析该对象resource属性指定的
          // Mapper配置文件
          if (resource != null && url == null && mapperClass == null) {
            ErrorContext.instance().resource(resource);
            // 获取resource输入流
            InputStream inputStream = Resources.getResourceAsStream(resource);
            // 创建XMLMapperBuilder对象，解析映射配置文件
            XMLMapperBuilder mapperParser = new XMLMapperBuilder(inputStream, configuration, resource, configuration.getSqlFragments());
            mapperParser.parse();
          // 如果<Mapper>节点指定了url，则创建XMLMapperBuilder对象并解析该对象url属性指定的
          // Mapper配置文件
          } else if (resource == null && url != null && mapperClass == null) {
            ErrorContext.instance().resource(url);
            // 获取url输入流
            InputStream inputStream = Resources.getUrlAsStream(url);
            // 创建XMLMapperBuilder对象，解析映射配置文件
            XMLMapperBuilder mapperParser = new XMLMapperBuilder(inputStream, configuration, url, configuration.getSqlFragments());
            mapperParser.parse();
            
          } else if (resource == null && url == null && mapperClass != null) {
        	// 如果<Mapper>节点指定了class属性，则向MapperRegistry注册该Mapper接口
            Class<?> mapperInterface = Resources.classForName(mapperClass);
            configuration.addMapper(mapperInterface);
          } else {
            throw new BuilderException("A mapper element may only specify a url, resource or class, but not more than one.");
          }
        }
      }
    }
  }

  private boolean isSpecifiedEnvironment(String id) {
    if (environment == null) {
      throw new BuilderException("No environment specified.");
    } else if (id == null) {
      throw new BuilderException("Environment requires an id attribute.");
    } else if (environment.equals(id)) { // 默认情况下为default
      return true;
    }
    return false;
  }

}
