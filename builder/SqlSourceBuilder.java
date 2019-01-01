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
package org.apache.ibatis.builder;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.apache.ibatis.mapping.ParameterMapping;
import org.apache.ibatis.mapping.SqlSource;
import org.apache.ibatis.parsing.GenericTokenParser;
import org.apache.ibatis.parsing.TokenHandler;
import org.apache.ibatis.reflection.MetaClass;
import org.apache.ibatis.reflection.MetaObject;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.type.JdbcType;

/**
 * @author Clinton Begin
 */
public class SqlSourceBuilder extends BaseBuilder {

  private static final String parameterProperties = "javaType,jdbcType,mode,numericScale,resultMap,typeHandler,jdbcTypeName";

  public SqlSourceBuilder(Configuration configuration) {
    super(configuration);
  }

  /**
   * 
   * @param originalSql 它是经过SqlNode.apply()方法处理过的SQL语句
   * @param parameterType 它是用户传入的实参类型
   * @param additionalParameters 它记录了形参与实参的对应关系，其实就是经过SqlNode.apply()方法处理过的
   * DynamicContext.bindings集合
   * @return
   */
  public SqlSource parse(String originalSql, Class<?> parameterType, Map<String, Object> additionalParameters) {
    // 创建ParameterMappingTokenHandler对象，它是解析"#{}"占位符中的参数属性以及替换占位符的核心
	ParameterMappingTokenHandler handler = new ParameterMappingTokenHandler(configuration, parameterType, additionalParameters);
    
	// 使用GenericTokenParser与ParameterMappingTokenHandler配合解析"#{}"占位符
	GenericTokenParser parser = new GenericTokenParser("#{", "}", handler);
    String sql = parser.parse(originalSql);
    
    // 创建StaticSqlSource,其中封装了占位符被替换成"?"的SQL语句以及参数对应的ParameterMapping集合
    return new StaticSqlSource(configuration, sql, handler.getParameterMappings());
  }

  private static class ParameterMappingTokenHandler extends BaseBuilder implements TokenHandler {

	// 用来记录解析得到的ParameterMapping集合
    private List<ParameterMapping> parameterMappings = new ArrayList<ParameterMapping>();
    
    private Class<?> parameterType; // 参数类型
    
    // DynamicContext,bindings集合对应MetaObject对象
    private MetaObject metaParameters; 

    public ParameterMappingTokenHandler(Configuration configuration, Class<?> parameterType, Map<String, Object> additionalParameters) {
      super(configuration);
      this.parameterType = parameterType;
      // configuration.newMetaObject(additionalParameters)创建了一个MetaObject对象
      // MetaObject.wrapperObject = additionalParameters;
      this.metaParameters = configuration.newMetaObject(additionalParameters);
    }

    public List<ParameterMapping> getParameterMappings() {
      return parameterMappings;
    }

    @Override
    public String handleToken(String content) {
      // 创建ParameterMapping对象，并添加到ParameterMappings集合中保存
      parameterMappings.add(buildParameterMapping(content));
      return "?";// 返回问号占位符
    }
    
    // 解析参数属性
    private ParameterMapping buildParameterMapping(String content) {
      // 解析参数的类型，并形成map。
      // 例如:#{__frc_item_0,javaType=int,jdbcType=NUMERIC,typeHandler=MyTypeHandler}
      // {"property"->"__frc_item_0","javaType"->"int","jdbcType"->"NUMERIC","typeHandler"->"MyTypeHandler"}
      Map<String, String> propertiesMap = parseParameterMapping(content);
      String property = propertiesMap.get("property"); // 参数名称
      Class<?> propertyType;
      // 确定参数的javaType属性
      if (metaParameters.hasGetter(property)) { // issue #448 get type from additional params
        propertyType = metaParameters.getGetterType(property);
      } else if (typeHandlerRegistry.hasTypeHandler(parameterType)) {
        propertyType = parameterType;
      } else if (JdbcType.CURSOR.name().equals(propertiesMap.get("jdbcType"))) {
        propertyType = java.sql.ResultSet.class;
      } else if (property == null || Map.class.isAssignableFrom(parameterType)) {
        propertyType = Object.class;
      } else {
        MetaClass metaClass = MetaClass.forClass(parameterType, configuration.getReflectorFactory());
        if (metaClass.hasGetter(property)) {
          propertyType = metaClass.getGetterType(property);
        } else {
          propertyType = Object.class;
        }
      }
      // 创建ParameterMapping的建造者，并设置ParameterMapping相关配置
      ParameterMapping.Builder builder = new ParameterMapping.Builder(configuration, property, propertyType);
      Class<?> javaType = propertyType;
      String typeHandlerAlias = null;
      for (Map.Entry<String, String> entry : propertiesMap.entrySet()) {
        String name = entry.getKey();
        String value = entry.getValue();
        if ("javaType".equals(name)) {
          javaType = resolveClass(value);
          builder.javaType(javaType);
        } else if ("jdbcType".equals(name)) {
          builder.jdbcType(resolveJdbcType(value));
          // 处理mode、numericScale、resultMap、typeHandler、jdbcTypeName、property等属性
        } else if ("mode".equals(name)) {
          builder.mode(resolveParameterMode(value));
        } else if ("numericScale".equals(name)) {
          builder.numericScale(Integer.valueOf(value));
        } else if ("resultMap".equals(name)) {
          builder.resultMapId(value);
        } else if ("typeHandler".equals(name)) {
          typeHandlerAlias = value;
        } else if ("jdbcTypeName".equals(name)) {
          builder.jdbcTypeName(value);
        } else if ("property".equals(name)) {
          // Do Nothing
        } else if ("expression".equals(name)) {
          throw new BuilderException("Expression based parameters are not supported yet");
        } else {
          throw new BuilderException("An invalid property '" + name + "' was found in mapping #{" + content + "}.  Valid properties are " + parameterProperties);
        }
      }
      if (typeHandlerAlias != null) { // 获取TypeHandler对象
        builder.typeHandler(resolveTypeHandler(javaType, typeHandlerAlias));
      }
      // 创建ParameterMapping对象，注意，如果没有指定TypeHandler，则会在这里build()方法中，根据
      // javaType和jdbcType从TypeHandlerRegistry中获取对应的TypeHandler对象
      return builder.build();
    }

    private Map<String, String> parseParameterMapping(String content) {
      try {
        return new ParameterExpression(content);
      } catch (BuilderException ex) {
        throw ex;
      } catch (Exception ex) {
        throw new BuilderException("Parsing error was found in mapping #{" + content + "}.  Check syntax #{property|(expression), var1=value1, var2=value2, ...} ", ex);
      }
    }
  }

}
