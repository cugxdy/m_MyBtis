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
package org.apache.ibatis.scripting.xmltags;

import java.util.List;
import java.util.Map;

import org.apache.ibatis.builder.SqlSourceBuilder;
import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.mapping.SqlSource;
import org.apache.ibatis.session.Configuration;

/**
 * @author Clinton Begin
 */
public class DynamicSqlSource implements SqlSource {

  private final Configuration configuration;
  // MixedSqlNode对象中维护着List<SqlNode> contents,这个集合封装了Sql语句中
  // 动态节点的节点，包含嵌套的，在后面执行数据库查询的时候，会解析这个contents集合
  private final SqlNode rootSqlNode; // 为MixedSqlNode节点

  public DynamicSqlSource(Configuration configuration, SqlNode rootSqlNode) {
    this.configuration = configuration; // 全局配置信息
    this.rootSqlNode = rootSqlNode; // Sql节点
  }

  @Override//  parameterObject = 101
  public BoundSql getBoundSql(Object parameterObject) {
	// 创建DynamicContext，parameterObject是用户传入的实参
    //  parameterObject = 101
    DynamicContext context = new DynamicContext(configuration, parameterObject);
    
    // 通过调用rootSqlNode.apply()方法调用整个树形结构中全部SqlNode.apply()方法.
    // 每个sqlNode的apply()方法都将解析得到的SQL语句片段追加到context中，最终通过context.getSql得到完整的SQl语句
    rootSqlNode.apply(context);
    
    // 创建SqlSourceBuilder，解析参数类型，并将SQL语句中的"${}"占位符替换成"?"占位符
    SqlSourceBuilder sqlSourceParser = new SqlSourceBuilder(configuration);
    
    // 获取参数的Class对象
    Class<?> parameterType = parameterObject == null ? Object.class : parameterObject.getClass();

    SqlSource sqlSource = sqlSourceParser.parse(context.getSql(), parameterType, context.getBindings());
    // 创建BoundSql对象，并将DynamicContext.bindins中的参数信息复制到其additionalParameters集合中保存
    BoundSql boundSql = sqlSource.getBoundSql(parameterObject);
    
    for (Map.Entry<String, Object> entry : context.getBindings().entrySet()) {
      boundSql.setAdditionalParameter(entry.getKey(), entry.getValue());
    }
    return boundSql;
  }

}
