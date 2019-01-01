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

import java.util.HashMap;
import java.util.Map;

import ognl.OgnlContext;
import ognl.OgnlException;
import ognl.OgnlRuntime;
import ognl.PropertyAccessor;

import org.apache.ibatis.reflection.MetaObject;
import org.apache.ibatis.session.Configuration;

/**
 * @author Clinton Begin
 */
// DynamicContext主要用来解析动态Sql语句之后的SQL语句片段，可以认为它是一个用于记录动态SQL语句解析结果的容器
public class DynamicContext {

  public static final String PARAMETER_OBJECT_KEY = "_parameter";
  public static final String DATABASE_ID_KEY = "_databaseId";

  static {
    OgnlRuntime.setPropertyAccessor(ContextMap.class, new ContextAccessor());
  }

  private final ContextMap bindings;  // 参数上下文
  
  // 在sqlNode解析动态SQL时，会将解析后的Sql语句片段添加到该属性中保存，最终拼凑出完整的SQL语句
  private final StringBuilder sqlBuilder = new StringBuilder();
  
  private int uniqueNumber = 0;

  /**
   * 初始化bindings集合
   * @param configuration
   * @param parameterObject // 它是运行时传入的参数，其中包含了后续用来替换"#{}"占位符的实参
   */
  public DynamicContext(Configuration configuration, Object parameterObject) {
	
	// parameterObject不为空且parameterObject不是Map类型
    if (parameterObject != null && !(parameterObject instanceof Map)) {
      // 对于非Map类型的参数，会创建对应的MetaObject对象，并封装成ContextMap对象
      //  parameterObject = 101  将 101转换成MetaObject
      MetaObject metaObject = configuration.newMetaObject(parameterObject);
      bindings = new ContextMap(metaObject); // 初始化bindings集合
    } else {
      bindings = new ContextMap(null);
    }
    // 将PARAMETER_OBJECT_KEY -> parameterObject这一对应关系添加到bindings集合中，其中
    // PARAMETER_OBJECT_KEY的值是"_parameter",在有的SqlNode实现中直接使用了该字面值
    bindings.put(PARAMETER_OBJECT_KEY, parameterObject);
    // 数据库关键字
    bindings.put(DATABASE_ID_KEY, configuration.getDatabaseId());
  }

  public Map<String, Object> getBindings() {
    return bindings;
  }

  public void bind(String name, Object value) {
    bindings.put(name, value);
  }

  public void appendSql(String sql) {  // 追加SQL片段
    sqlBuilder.append(sql);
    sqlBuilder.append(" ");
  }

  public String getSql() { // 获取解析后的、完整的SQL语句
    return sqlBuilder.toString().trim();
  }

  public int getUniqueNumber() {
    return uniqueNumber++;
  }

  static class ContextMap extends HashMap<String, Object> {
    private static final long serialVersionUID = 2977601501966151582L;

    // 将用户传入的参数封装成了MetaObject对象
    private MetaObject parameterMetaObject;
    
    public ContextMap(MetaObject parameterMetaObject) {
      this.parameterMetaObject = parameterMetaObject;
    }

    @Override
    public Object get(Object key) {
      String strKey = (String) key;
      if (super.containsKey(strKey)) { // 如果ContextMap中包含了该key，则直接返回
        return super.get(strKey);
      }

      if (parameterMetaObject != null) {
        // issue #61 do not modify the context when reading
        return parameterMetaObject.getValue(strKey);// 从运行时参数中查找对应属性
      }

      return null;
    }
  }

  static class ContextAccessor implements PropertyAccessor {

    @Override
    public Object getProperty(Map context, Object target, Object name)
        throws OgnlException {
      Map map = (Map) target;

      Object result = map.get(name);
      if (map.containsKey(name) || result != null) {
        return result;
      }

      Object parameterObject = map.get(PARAMETER_OBJECT_KEY);
      if (parameterObject instanceof Map) {
        return ((Map)parameterObject).get(name);
      }

      return null;
    }

    @Override
    public void setProperty(Map context, Object target, Object name, Object value)
        throws OgnlException {
      Map<Object, Object> map = (Map<Object, Object>) target;
      map.put(name, value);
    }

    @Override
    public String getSourceAccessor(OgnlContext arg0, Object arg1, Object arg2) {
      return null;
    }

    @Override
    public String getSourceSetter(OgnlContext arg0, Object arg1, Object arg2) {
      return null;
    }
  }
}