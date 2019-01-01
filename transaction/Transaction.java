/**
 *    Copyright 2009-2016 the original author or authors.
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
package org.apache.ibatis.transaction;

import java.sql.Connection;
import java.sql.SQLException;

/**
 * Wraps a database connection.
 * Handles the connection lifecycle that comprises: its creation, preparation, commit/rollback and close. 
 *
 * @author Clinton Begin
 */
public interface Transaction {

  /**
   * Retrieve inner database connection
   * @return DataBase connection
   * @throws SQLException
   */
  Connection getConnection() throws SQLException; // 获取对应的数据库连接对象

  /**
   * Commit inner database connection.
   * @throws SQLException
   */
  void commit() throws SQLException; // 提交事务

  /**
   * Rollback inner database connection.
   * @throws SQLException
   */
  void rollback() throws SQLException; // 事务回滚

  /**
   * Close inner database connection.
   * @throws SQLException
   */
  void close() throws SQLException; // 关闭数据库连接

  /**
   * Get transaction timeout if set
   * @throws SQLException
   */
  Integer getTimeout() throws SQLException;// 获取事务超时时间
  
}
