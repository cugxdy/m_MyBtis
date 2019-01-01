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
package org.apache.ibatis.executor;

import static org.apache.ibatis.executor.ExecutionPlaceholder.EXECUTION_PLACEHOLDER;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;

import org.apache.ibatis.cache.CacheKey;
import org.apache.ibatis.cache.impl.PerpetualCache;
import org.apache.ibatis.cursor.Cursor;
import org.apache.ibatis.executor.statement.StatementUtil;
import org.apache.ibatis.logging.Log;
import org.apache.ibatis.logging.LogFactory;
import org.apache.ibatis.logging.jdbc.ConnectionLogger;
import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.mapping.ParameterMapping;
import org.apache.ibatis.mapping.ParameterMode;
import org.apache.ibatis.mapping.StatementType;
import org.apache.ibatis.reflection.MetaObject;
import org.apache.ibatis.reflection.factory.ObjectFactory;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.LocalCacheScope;
import org.apache.ibatis.session.ResultHandler;
import org.apache.ibatis.session.RowBounds;
import org.apache.ibatis.transaction.Transaction;
import org.apache.ibatis.type.TypeHandlerRegistry;

/**
 * @author Clinton Begin
 */
// 它主要提供了缓存管理和事务管理的基本功能
public abstract class BaseExecutor implements Executor {

  private static final Log log = LogFactory.getLog(BaseExecutor.class);

  protected Transaction transaction;// Transaction对象，实现事务的提交，回滚与关闭操作
  protected Executor wrapper;// 其中封装的Executor对象

  protected ConcurrentLinkedQueue<DeferredLoad> deferredLoads; // 延迟加载队列
  
  // 一级缓存，用于缓存该对象查询结果集映射得到的结果对象，PerpetualCache的具体实现
  protected PerpetualCache localCache; 
  // 一级缓存，用于缓存输出类型的参数
  protected PerpetualCache localOutputParameterCache;
  protected Configuration configuration;

  // 用来记录嵌套查询的层数，分析DefaultResultSetHandler时介绍过嵌套查询
  protected int queryStack;
  private boolean closed;

  protected BaseExecutor(Configuration configuration, Transaction transaction) {
    this.transaction = transaction;
    this.deferredLoads = new ConcurrentLinkedQueue<DeferredLoad>();
    this.localCache = new PerpetualCache("LocalCache");
    this.localOutputParameterCache = new PerpetualCache("LocalOutputParameterCache");
    this.closed = false;
    this.configuration = configuration;
    this.wrapper = this;
  }

  @Override
  public Transaction getTransaction() {
    if (closed) {
      throw new ExecutorException("Executor was closed.");
    }
    return transaction;
  }

  @Override
  public void close(boolean forceRollback) {
    try {
      try {
        rollback(forceRollback);
      } finally {
        if (transaction != null) {
          transaction.close();
        }
      }
    } catch (SQLException e) {
      // Ignore.  There's nothing that can be done at this point.
      log.warn("Unexpected exception on closing transaction.  Cause: " + e);
    } finally {
      transaction = null;
      deferredLoads = null;
      localCache = null;
      localOutputParameterCache = null;
      closed = true;
    }
  }

  @Override
  public boolean isClosed() {
    return closed;
  }

  @Override
  public int update(MappedStatement ms, Object parameter) throws SQLException {
    ErrorContext.instance().resource(ms.getResource()).activity("executing an update").object(ms.getId());
    if (closed) { // 检测当前Executor是否已经关闭
      throw new ExecutorException("Executor was closed.");
    }
    // clearLocalCache()方法会调用localCache,localOutputParameterCache两个缓存的
    // clear()方法完成清理工作，这是影响一级缓存中数据存活时长的第三个方面
    clearLocalCache();
    // 调用doUpdate()方法执行SQL语句
    return doUpdate(ms, parameter);
  }

  @Override
  public List<BatchResult> flushStatements() throws SQLException {
    return flushStatements(false);
  }

  public List<BatchResult> flushStatements(boolean isRollBack) throws SQLException {
    if (closed) {// 检测当前Executor是否已经关闭
      throw new ExecutorException("Executor was closed.");
    }
    // 调用doFlushStatements方法这个基本方法，其参数isRollBack表示是否执行Executor中缓存的SQL语句
    // false表示执行，true表示不执行
    return doFlushStatements(isRollBack);
  }

  @Override// parameter = 101
  public <E> List<E> query(MappedStatement ms, Object parameter, RowBounds rowBounds, ResultHandler resultHandler) throws SQLException {
    BoundSql boundSql = ms.getBoundSql(parameter); // 获取BoundSql对象
    // 创建CacheKey对象，该CacheKey对象的组成部分在后面介绍
    CacheKey key = createCacheKey(ms, parameter, rowBounds, boundSql);
    // 调用query的另一个重载，继续处理后续结果
    return query(ms, parameter, rowBounds, resultHandler, key, boundSql);
  }

  @SuppressWarnings("unchecked")
  @Override
  public <E> List<E> query(MappedStatement ms, Object parameter, RowBounds rowBounds, ResultHandler resultHandler, CacheKey key, BoundSql boundSql) throws SQLException {
    ErrorContext.instance().resource(ms.getResource()).activity("executing a query").object(ms.getId());
    if (closed) { // 检测当前Executor是否关闭
      throw new ExecutorException("Executor was closed.");
    }
   
    if (queryStack == 0 && ms.isFlushCacheRequired()) {
      // 非嵌套查询，并且<select>节点配置的flushCache属性为true时，才会清空一级缓存
      // flushCache配置项是影响一级缓存中结果对象存活时长的第一个方面
      clearLocalCache();
    }
    List<E> list;
    try {
      queryStack++; // 增加查询层数
      // 查询一级缓存
      list = resultHandler == null ? (List<E>) localCache.getObject(key) : null;
      if (list != null) {
    	// 针对存储过程调用的处理，其功能是；在一级缓存命中时，获取缓存中保存输出类型参数，
    	// 并设置到用户传入的参数(parameter)对象中，
        handleLocallyCachedOutputParameters(ms, key, parameter, boundSql);
      } else {
    	// 其中会调用doQuery方法完成数据库的查询，并得到映射后的结果对象，doQuery()方法是一个抽象方法
    	// 由BaseExecuter的子类实现
        list = queryFromDatabase(ms, parameter, rowBounds, resultHandler, key, boundSql);
      }
    } finally {
      queryStack--; // 当前查询完成，查询层数减少
    }
    if (queryStack == 0) {
      // 触发DeferredLoad加载一级缓存中记录的嵌套查询的结果对象
      for (DeferredLoad deferredLoad : deferredLoads) {
    	// 延迟加载的内容
        deferredLoad.load();
      }
      // issue #601
      deferredLoads.clear(); // 加载完成后，清空deferredLoads集合
      if (configuration.getLocalCacheScope() == LocalCacheScope.STATEMENT) {
        // issue #482
    	// 根据localCacheScope配置决定是否清空一级缓存，localCacheScope配置影响一级缓存中结果对象
    	// 存活时长的第二个方面
        clearLocalCache();
      }
    }
    return list;
  }

  // 它不会直接将结果集映射成结果对象，而是将结果集封装成Cursor对并返回，待用户遍历Cursor时才真正完成结果集的映射操作
  // 另外是直接调用doQueryCursor方法，不会去查询一级缓存
  @Override
  public <E> Cursor<E> queryCursor(MappedStatement ms, Object parameter, RowBounds rowBounds) throws SQLException {
    BoundSql boundSql = ms.getBoundSql(parameter);
    return doQueryCursor(ms, parameter, rowBounds, boundSql);
  }

  @Override
  public void deferLoad(MappedStatement ms, MetaObject resultObject, String property, CacheKey key, Class<?> targetType) {
    if (closed) { // 边界检测
      throw new ExecutorException("Executor was closed.");
    }
    //  创建DeferredLoad对象
    DeferredLoad deferredLoad = new DeferredLoad(resultObject, property, key, localCache, configuration, targetType);
    if (deferredLoad.canLoad()) {
      // 一级缓存中已经记录了指定查询的结果对象，直接从缓存中加载对象，并设置到外层对象中
      deferredLoad.load();
    } else {
      // 将DeferredLoad对象添加到deferredLoads队列中，待整个外层查询结束后，在加载该结果对象
      deferredLoads.add(new DeferredLoad(resultObject, property, key, localCache, configuration, targetType));
    }
  }

  @Override
  public CacheKey createCacheKey(MappedStatement ms, Object parameterObject, RowBounds rowBounds, BoundSql boundSql) {
    if (closed) { // 检测当前Executor是否已经关闭
      throw new ExecutorException("Executor was closed.");
    }
    // 创建CacheKey对象
    CacheKey cacheKey = new CacheKey();
    cacheKey.update(ms.getId()); // 将MappedStatement的id添加到CacheKey对象中
    cacheKey.update(rowBounds.getOffset()); // 将offset添加到CacheKey对象中
    cacheKey.update(rowBounds.getLimit()); // 将limit添加到CacheKey对象中
    cacheKey.update(boundSql.getSql());// 将Sql语句添加到CacheKey对象中
    
    List<ParameterMapping> parameterMappings = boundSql.getParameterMappings();
    TypeHandlerRegistry typeHandlerRegistry = ms.getConfiguration().getTypeHandlerRegistry();
    
    // mimic DefaultParameterHandler logic
    // 获取用户传入的实参，并添加到CacheKey对象中
    for (ParameterMapping parameterMapping : parameterMappings) {
      // 输入参数ParameterMode.IN
      if (parameterMapping.getMode() != ParameterMode.OUT) { // 过滤掉输出类型的参数
        Object value;
        // name值
        String propertyName = parameterMapping.getProperty();
        if (boundSql.hasAdditionalParameter(propertyName)) {
          value = boundSql.getAdditionalParameter(propertyName);
        } else if (parameterObject == null) {
          value = null;
          // 类型处理器
        } else if (typeHandlerRegistry.hasTypeHandler(parameterObject.getClass())) {
          value = parameterObject;
        } else {
          MetaObject metaObject = configuration.newMetaObject(parameterObject);
          value = metaObject.getValue(propertyName);
        }
        cacheKey.update(value);// 将实参添加到CacheKey对象中
      }
    }
    // 如果Environment的id不为空。则将其添加到CacheKey中
    if (configuration.getEnvironment() != null) {
      // issue #176
      cacheKey.update(configuration.getEnvironment().getId());
    }
    return cacheKey;
  }

  @Override
  public boolean isCached(MappedStatement ms, CacheKey key) {
    return localCache.getObject(key) != null; // 检测缓存中是否缓存了CacheKey对应的对象
  }

  @Override
  public void commit(boolean required) throws SQLException {
    if (closed) {// 检测当前Executor是否已经关闭
      throw new ExecutorException("Cannot commit, transaction is already closed");
    }
    // 清空一级缓存
    clearLocalCache();
    // 执行缓存的SQl语句，其中调用了flushStatements(false)方法
    flushStatements();
    if (required) { // 根据required参数决定是否提交事务
      transaction.commit();
    }
  }

  @Override
  public void rollback(boolean required) throws SQLException {
    if (!closed) {
      try {
        clearLocalCache();
        flushStatements(true);
      } finally {
        if (required) {
          transaction.rollback();
        }
      }
    }
  }

  @Override
  public void clearLocalCache() {
    if (!closed) {
      localCache.clear();
      localOutputParameterCache.clear();
    }
  }

  protected abstract int doUpdate(MappedStatement ms, Object parameter)
      throws SQLException;

  protected abstract List<BatchResult> doFlushStatements(boolean isRollback)
      throws SQLException;

  protected abstract <E> List<E> doQuery(MappedStatement ms, Object parameter, RowBounds rowBounds, ResultHandler resultHandler, BoundSql boundSql)
      throws SQLException;

  protected abstract <E> Cursor<E> doQueryCursor(MappedStatement ms, Object parameter, RowBounds rowBounds, BoundSql boundSql)
      throws SQLException;

  protected void closeStatement(Statement statement) {
    if (statement != null) {
      try {
        if (!statement.isClosed()) {
          statement.close();
        }
      } catch (SQLException e) {
        // ignore
      }
    }
  }

  /**
   * Apply a transaction timeout.
   * @param statement a current statement
   * @throws SQLException if a database access error occurs, this method is called on a closed <code>Statement</code>
   * @since 3.4.0
   * @see StatementUtil#applyTransactionTimeout(Statement, Integer, Integer)
   */
  protected void applyTransactionTimeout(Statement statement) throws SQLException {
    StatementUtil.applyTransactionTimeout(statement, statement.getQueryTimeout(), transaction.getTimeout());
  }

  private void handleLocallyCachedOutputParameters(MappedStatement ms, CacheKey key, Object parameter, BoundSql boundSql) {
    //　当StatementType = CALLABLE类型
	if (ms.getStatementType() == StatementType.CALLABLE) {
      final Object cachedParameter = localOutputParameterCache.getObject(key);
      if (cachedParameter != null && parameter != null) {
        final MetaObject metaCachedParameter = configuration.newMetaObject(cachedParameter);
        final MetaObject metaParameter = configuration.newMetaObject(parameter);
        // 设置参数
        for (ParameterMapping parameterMapping : boundSql.getParameterMappings()) {
          if (parameterMapping.getMode() != ParameterMode.IN) {
            final String parameterName = parameterMapping.getProperty();
            final Object cachedValue = metaCachedParameter.getValue(parameterName);
            metaParameter.setValue(parameterName, cachedValue);
          }
        }
      }
    }
  }

  private <E> List<E> queryFromDatabase(MappedStatement ms, Object parameter, RowBounds rowBounds, ResultHandler resultHandler, CacheKey key, BoundSql boundSql) throws SQLException {
    List<E> list;
    localCache.putObject(key, EXECUTION_PLACEHOLDER); // 在缓存中添加占位符
    try {
      // 调用doQuery方法(抽象方法)，完成数据库查询操作，并返回结果对象
      list = doQuery(ms, parameter, rowBounds, resultHandler, boundSql);
    } finally {
      localCache.removeObject(key); // 删除占位符
    }
    localCache.putObject(key, list); // 将真正的对象添加到一级缓存中
    if (ms.getStatementType() == StatementType.CALLABLE) { // 是否为存储过程调用
      localOutputParameterCache.putObject(key, parameter);// 缓存输出类型的参数
    }
    return list;
  }

  protected Connection getConnection(Log statementLog) throws SQLException {
	// 创建数据库的连接
    Connection connection = transaction.getConnection();
    if (statementLog.isDebugEnabled()) {  // statementLog日志记录能用的话
      // 创建代理对象,真正的对象执行方法都会被这个拦截
      return ConnectionLogger.newInstance(connection, statementLog, queryStack);
    } else {
      return connection;
    }
  }

  @Override
  public void setExecutorWrapper(Executor wrapper) {
    this.wrapper = wrapper;
  }
  
  private static class DeferredLoad {

    private final MetaObject resultObject;  // 外层对象对应的MetaObject对象
    
    private final String property; // 延迟加载的属性名称
    
    private final Class<?> targetType; // 延迟加载的属性的类型
     
    private final CacheKey key; // 延迟加载的结果对象在一级缓存中相应的CacheKey对象
    // 一级缓存，与BaseExecutor.localCache属性指向的是同一个PerpetualCache
    private final PerpetualCache localCache;

    private final ObjectFactory objectFactory;
    
    // ResultExtractor负责结果对象的类型装换，
    private final ResultExtractor resultExtractor;

    // issue #781
    public DeferredLoad(MetaObject resultObject,
                        String property,
                        CacheKey key,
                        PerpetualCache localCache,
                        Configuration configuration,
                        Class<?> targetType) {
      this.resultObject = resultObject;
      this.property = property;
      this.key = key;
      this.localCache = localCache;
      this.objectFactory = configuration.getObjectFactory();
      this.resultExtractor = new ResultExtractor(configuration, objectFactory);
      this.targetType = targetType;
    }

    // 它是负责检测缓存项是否已经完全加载到了缓存中
    // 完全加载:在调用doQuery方法查询数据库之前，会先在localCache中添加占位符，待查询结束后，才将真正的结果
    // 对象放入到localCache中缓存，此时该缓存项才算"完全加载"
    public boolean canLoad() {
      return localCache.getObject(key) != null &&  // 检测缓存是否存在指定的结果对象
    		  localCache.getObject(key) != EXECUTION_PLACEHOLDER;// 检测是否为占位符
    }

    public void load() {
      @SuppressWarnings( "unchecked" )
      // we suppose we get back a List
      // 从缓存中查询指定的结果对象
      List<Object> list = (List<Object>) localCache.getObject(key);
      // 将缓存的结果对象装换成指定类型
      Object value = resultExtractor.extractObjectFromList(list, targetType);
      resultObject.setValue(property, value); // 设置到外层对象的对应属性
    }

  }

}
