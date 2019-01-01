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
package org.apache.ibatis.executor.loader;

import java.sql.SQLException;
import java.util.List;

import javax.sql.DataSource;

import org.apache.ibatis.cache.CacheKey;
import org.apache.ibatis.executor.Executor;
import org.apache.ibatis.executor.ExecutorException;
import org.apache.ibatis.executor.ResultExtractor;
import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.reflection.factory.ObjectFactory;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.ExecutorType;
import org.apache.ibatis.session.RowBounds;
import org.apache.ibatis.transaction.Transaction;
import org.apache.ibatis.transaction.TransactionFactory;

/**
 * @author Clinton Begin
 */
public class ResultLoader {

  protected final Configuration configuration; // Configuration配置对象
  
  // 用来执行延迟加载操作的Executor对象，后面专门介绍
  protected final Executor executor;
  
  // 记录延迟执行的SQL语句以及相关配置
  protected final MappedStatement mappedStatement;
  
  // 记录了延迟执行的SQL语句的实参
  protected final Object parameterObject;
  
  protected final Class<?> targetType;// 记录了延迟加载得到的对象类型
  
  // ObjectFactory工厂对象，通过反射创建延迟加载的Java对象
  protected final ObjectFactory objectFactory;
  
  protected final CacheKey cacheKey; // CacheKey对象
  
  protected final BoundSql boundSql;
  
  // ResultExtractor负责将延迟加载得到的结果对象转换成targetType类型的对象
  protected final ResultExtractor resultExtractor;
  
  protected final long creatorThreadId; // 创建ResultLoader的线程id
  
  protected boolean loaded;
  
  // 延迟加载得到的结果对象
  protected Object resultObject;
  
  public ResultLoader(Configuration config, Executor executor, MappedStatement mappedStatement, Object parameterObject, Class<?> targetType, CacheKey cacheKey, BoundSql boundSql) {
    this.configuration = config;
    this.executor = executor;
    this.mappedStatement = mappedStatement;
    this.parameterObject = parameterObject;
    this.targetType = targetType;
    this.objectFactory = configuration.getObjectFactory();
    this.cacheKey = cacheKey;
    this.boundSql = boundSql;
    this.resultExtractor = new ResultExtractor(configuration, objectFactory);
    this.creatorThreadId = Thread.currentThread().getId();
  }

  public Object loadResult() throws SQLException {
	// 执行延迟加载，得到结果对象，并以List的形式返回
    List<Object> list = selectList();
    // 将List集合转换成targetType指定类型的对象
    resultObject = resultExtractor.extractObjectFromList(list, targetType);
    return resultObject;
  }

  private <E> List<E> selectList() throws SQLException {
    Executor localExecutor = executor;
    // 检测调用该方法的线程是否为创建ResultLoader对象的线程，检测localExecutor是否关闭，检测到异常情况
    // 时，会创建新的Executor对象来执行延迟加载动作
    if (Thread.currentThread().getId() != this.creatorThreadId || localExecutor.isClosed()) {
      localExecutor = newExecutor();
    }
    try {
      // 执行查询操作，得到延迟加载的对象。Executor的相关实现原理
      return localExecutor.<E> query(mappedStatement, parameterObject, RowBounds.DEFAULT, Executor.NO_RESULT_HANDLER, cacheKey, boundSql);
    } finally {
      if (localExecutor != executor) {
    	// 如果在selectList()创建新的Executor对象，需要关闭
        localExecutor.close(false);
      }
    }
  }

  private Executor newExecutor() {
	// 根据Environment配置创建Environment对象
    final Environment environment = configuration.getEnvironment();
    if (environment == null) {
      throw new ExecutorException("ResultLoader could not load lazily.  Environment was not configured.");
    }
    //　创建DataSource对象
    final DataSource ds = environment.getDataSource();
    if (ds == null) {
      throw new ExecutorException("ResultLoader could not load lazily.  DataSource was not configured.");
    }
    final TransactionFactory transactionFactory = environment.getTransactionFactory();
    final Transaction tx = transactionFactory.newTransaction(ds, null, false);
    // 创建具体的Executor对象
    return configuration.newExecutor(tx, ExecutorType.SIMPLE);
  }

  public boolean wasNull() {
    return resultObject == null;
  }

}
