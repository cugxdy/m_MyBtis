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
package org.apache.ibatis.scripting.xmltags;

/**
 * @author Frank D. Martinez [mnesarco]
 */
// 它表示的是动态SQL语句中的<bind>节点，该节点可以从OGNL表达式创建一个变量并将其记录到上下文中
public class VarDeclSqlNode implements SqlNode {

  private final String name; // 记录了<bind>节点的name属性值
  private final String expression;// 记录了<bind>节点的expression属性值

  public VarDeclSqlNode(String var, String exp) {
    name = var;
    expression = exp;
  }

  @Override
  public boolean apply(DynamicContext context) {
	// 解析OGNL表达式的值
    final Object value = OgnlCache.getValue(expression, context.getBindings());
    context.bind(name, value); // 将name和表达式的值存入DynamicContext.bindings集合中
    return true;
  }

}
