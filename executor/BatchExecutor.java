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

import java.sql.BatchUpdateException;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.apache.ibatis.cursor.Cursor;
import org.apache.ibatis.executor.keygen.Jdbc3KeyGenerator;
import org.apache.ibatis.executor.keygen.KeyGenerator;
import org.apache.ibatis.executor.keygen.NoKeyGenerator;
import org.apache.ibatis.executor.statement.StatementHandler;
import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.ResultHandler;
import org.apache.ibatis.session.RowBounds;
import org.apache.ibatis.transaction.Transaction;

/**
 * @author Jeff Butler 
 */
// 使用批量处理的优化方法可以在客户端缓存多条SQL语句，并在合适的时机将多条SQL语句打包发送给数据库执行
// 从而减少网络方面的开销，提升系统的性能

// 在批量执行多条SQL语句时，每次向数据库发送的SQL语句条数是有上限的，如果超过这个上限，数据库就会拒绝
// 执行这些SQL语句并抛出异常
public class BatchExecutor extends BaseExecutor {

  public static final int BATCH_UPDATE_RETURN_VALUE = Integer.MIN_VALUE + 1002;

  // 缓存多个Statement对象，其中每个Statement对象都缓存了多条SQL语句
  private final List<Statement> statementList = new ArrayList<Statement>();
  
  // 记录批处理的结果，BatchResult中通过updateCounts字段(int[]数组类型)记录每个Statement执行批处理
  // 结果
  private final List<BatchResult> batchResultList = new ArrayList<BatchResult>();
  
  private String currentSql; // 记录当前执行的SQL语句
  private MappedStatement currentStatement;// 记录当前执行的MappedStatement对象

  public BatchExecutor(Configuration configuration, Transaction transaction) {
    super(configuration, transaction);
  }

  /**
   * doUpdate()方法再添加一条SQL语句时，首先会将currentSql字段记录的Sql语句以及currentStatement字段记录
   * MappedStatement对象与当前添加的SQL以及MappedStatement对象进行比较，如果相同则添加到同一Statement
   * 对象等待执行，如果不同则创建新的Statement并将其缓存到statementList集合中等待执行
   */
  @Override
  public int doUpdate(MappedStatement ms, Object parameterObject) throws SQLException {
    final Configuration configuration = ms.getConfiguration(); // 获取Configuration配置对象
    // 创建StatementHandler对象
    final StatementHandler handler = configuration.newStatementHandler(this, ms, parameterObject, RowBounds.DEFAULT, null, null);
    final BoundSql boundSql = handler.getBoundSql();
    final String sql = boundSql.getSql(); // 获取SQL语句
    final Statement stmt;
    // 如果当前执行的SQL模式与上次执行的SQL模式相同且对应的MappedStatement对象相同
    if (sql.equals(currentSql) && ms.equals(currentStatement)) {
      // 获取statementList最后一个Statement对象
      int last = statementList.size() - 1;
      stmt = statementList.get(last);
      applyTransactionTimeout(stmt);
      handler.parameterize(stmt);//fix Issues 322 // 绑定实参,处理"?"占位符
      // 查找对应的BatchResult对象，并记录用户传入的实参
      BatchResult batchResult = batchResultList.get(last);
      batchResult.addParameterObject(parameterObject);
    } else {
      Connection connection = getConnection(ms.getStatementLog());
      // 创建Statement对象
      stmt = handler.prepare(connection, transaction.getTimeout());
      handler.parameterize(stmt);   // 绑定实参，处理"?"占位符
      currentSql = sql; // 更新currentSql和currentStatement
      currentStatement = ms;
      statementList.add(stmt);// 将新创建的Statement对象添加到statementList集合中
      // 添加新的BatchResult对象
      batchResultList.add(new BatchResult(ms, sql, parameterObject));
    }
    // handler.parameterize(stmt);
    // 底层通过调用Staement.addBatch()方法添加SQL语句
    handler.batch(stmt);
    return BATCH_UPDATE_RETURN_VALUE;
  }

  @Override
  public <E> List<E> doQuery(MappedStatement ms, Object parameterObject, RowBounds rowBounds, ResultHandler resultHandler, BoundSql boundSql)
      throws SQLException {
    Statement stmt = null;
    try {
      flushStatements();
      Configuration configuration = ms.getConfiguration();
      StatementHandler handler = configuration.newStatementHandler(wrapper, ms, parameterObject, rowBounds, resultHandler, boundSql);
      Connection connection = getConnection(ms.getStatementLog());
      stmt = handler.prepare(connection, transaction.getTimeout());
      handler.parameterize(stmt);
      return handler.<E>query(stmt, resultHandler);
    } finally {
      closeStatement(stmt);
    }
  }

  @Override
  protected <E> Cursor<E> doQueryCursor(MappedStatement ms, Object parameter, RowBounds rowBounds, BoundSql boundSql) throws SQLException {
    flushStatements();
    Configuration configuration = ms.getConfiguration();
    StatementHandler handler = configuration.newStatementHandler(wrapper, ms, parameter, rowBounds, null, boundSql);
    Connection connection = getConnection(ms.getStatementLog());
    Statement stmt = handler.prepare(connection, transaction.getTimeout());
    handler.parameterize(stmt);
    return handler.<E>queryCursor(stmt);
  }

  @Override
  public List<BatchResult> doFlushStatements(boolean isRollback) throws SQLException {
    try {
      // results集合用于存储批处理的结果
      List<BatchResult> results = new ArrayList<BatchResult>();
      // 如果明确指明了要回滚事务，则直接返回空集合，忽略statementList集合中记录的SQL语句
      if (isRollback) {
        return Collections.emptyList();
      }
      for (int i = 0, n = statementList.size(); i < n; i++) { // 遍历statementList集合
    	// 获取Statement对象
        Statement stmt = statementList.get(i);
        applyTransactionTimeout(stmt);
        BatchResult batchResult = batchResultList.get(i); // 获取相应的BatchResult对象
        try {
          // 调用Statement.executeBatch()方法批量执行其中记录的SQL语句，并使用返回的int数组
          // 更新BatchResult.UpdateCounts字段，其中每一个元素都表示一个Sql语句影响的记录条数
          batchResult.setUpdateCounts(stmt.executeBatch());
          MappedStatement ms = batchResult.getMappedStatement();
          List<Object> parameterObjects = batchResult.getParameterObjects();
          // 获取配置的KeyGenerator的对象
          KeyGenerator keyGenerator = ms.getKeyGenerator();
          if (Jdbc3KeyGenerator.class.equals(keyGenerator.getClass())) {
            Jdbc3KeyGenerator jdbc3KeyGenerator = (Jdbc3KeyGenerator) keyGenerator;
            // 获取数据库生成的主键，并设置到parameterObjects中，
            jdbc3KeyGenerator.processBatch(ms, stmt, parameterObjects);
          } else if (!NoKeyGenerator.class.equals(keyGenerator.getClass())) { //issue #141
            for (Object parameter : parameterObjects) {
              // 其它的KeyGenerator，会调用其processAfter()方法
              keyGenerator.processAfter(this, ms, stmt, parameter);
            }
          }
          // Close statement to close cursor #1109
          closeStatement(stmt);
        } catch (BatchUpdateException e) {
          // 异常处理
          StringBuilder message = new StringBuilder();
          message.append(batchResult.getMappedStatement().getId())
              .append(" (batch index #")
              .append(i + 1)
              .append(")")
              .append(" failed.");
          if (i > 0) {
            message.append(" ")
                .append(i)
                .append(" prior sub executor(s) completed successfully, but will be rolled back.");
          }
          throw new BatchExecutorException(message.toString(), e, results, batchResult);
        }
        results.add(batchResult); // 添加batchResult到results集合中
      } 
      return results;
    } finally {
      // 关闭所有Statement对象，并清空currentSql字段，清空statementList集合、清空batchResultList集合
      for (Statement stmt : statementList) {
        closeStatement(stmt);
      }
      currentSql = null;
      statementList.clear();
      batchResultList.clear();
    }
  }

}
