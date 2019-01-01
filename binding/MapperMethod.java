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
package org.apache.ibatis.binding;

import org.apache.ibatis.annotations.Flush;
import org.apache.ibatis.annotations.MapKey;
import org.apache.ibatis.cursor.Cursor;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.mapping.SqlCommandType;
import org.apache.ibatis.mapping.StatementType;
import org.apache.ibatis.reflection.MetaObject;
import org.apache.ibatis.reflection.ParamNameResolver;
import org.apache.ibatis.reflection.TypeParameterResolver;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.ResultHandler;
import org.apache.ibatis.session.RowBounds;
import org.apache.ibatis.session.SqlSession;

import java.lang.reflect.Array;
import java.lang.reflect.Method;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.*;

/**
 * @author Clinton Begin
 * @author Eduardo Macarron
 * @author Lasse Voss
 */
public class MapperMethod {

  private final SqlCommand command;  // 记录了SQL语句的名称与类型
  private final MethodSignature method;// Mapper接口中对应方法的相关信息

  public MapperMethod(Class<?> mapperInterface, Method method, Configuration config) {
    this.command = new SqlCommand(config, mapperInterface, method);
    this.method = new MethodSignature(config, mapperInterface, method);
  }

  public Object execute(SqlSession sqlSession, Object[] args) {
    Object result;
    switch (command.getType()) { // 根据SQL语句的类型调用SqlSession对应的方法
      case INSERT: {
    	// 使用ParamNameResolver处理args[]数组(用户传入的实参列表)，将用户传入的实参
    	// 与指定参数名称关联起来
        Object param = method.convertArgsToSqlCommandParam(args);
        // 调用SqlSession.insert()方法，rowCountResult()方法会根据method字段中记录的
        // 方法返回值类型对结果进行转换
        result = rowCountResult(sqlSession.insert(command.getName(), param));
        break;
      }
      case UPDATE: {
        Object param = method.convertArgsToSqlCommandParam(args);
        result = rowCountResult(sqlSession.update(command.getName(), param));
        break;
      }
      case DELETE: {
        Object param = method.convertArgsToSqlCommandParam(args);
        result = rowCountResult(sqlSession.delete(command.getName(), param));
        break;
      }
      case SELECT:
    	// 处理返回值为void且ResultSet通过ResultHandler处理的方法
        if (method.returnsVoid() && method.hasResultHandler()) {
          executeWithResultHandler(sqlSession, args);
          result = null;
        } else if (method.returnsMany()) { // 处理返回值为集合或数组的方法
          result = executeForMany(sqlSession, args);
        } else if (method.returnsMap()) {// 处理返回值为Map的方法
          result = executeForMap(sqlSession, args);
        } else if (method.returnsCursor()) { // 处理返回值为Cursor的方法
          result = executeForCursor(sqlSession, args);
        } else { // 处理返回值为单一对象的方法
          Object param = method.convertArgsToSqlCommandParam(args);
          result = sqlSession.selectOne(command.getName(), param);
        }
        break;
      case FLUSH:
        result = sqlSession.flushStatements();
        break;
      default:
        throw new BindingException("Unknown execution method for: " + command.getName());
    }
    // 边界检查
    if (result == null && method.getReturnType().isPrimitive() && !method.returnsVoid()) {
      throw new BindingException("Mapper method '" + command.getName() 
          + " attempted to return null from a method with a primitive return type (" + method.getReturnType() + ").");
    }
    return result;
  }

  private Object rowCountResult(int rowCount) {
    final Object result;
    if (method.returnsVoid()) { 
      result = null;// Mapper接口中相应方法的返回值为void
    } else if (Integer.class.equals(method.getReturnType()) || Integer.TYPE.equals(method.getReturnType())) {
      result = rowCount;// Mapper接口中相应方法的返回值为int或Integer
    } else if (Long.class.equals(method.getReturnType()) || Long.TYPE.equals(method.getReturnType())) {
      result = (long)rowCount;// Mapper接口中相应方法的返回值为long或Long
    } else if (Boolean.class.equals(method.getReturnType()) || Boolean.TYPE.equals(method.getReturnType())) {
      result = rowCount > 0;// Mapper接口中相应方法的返回值为boolean或Boolean
    } else {
      // 以下条件都不成立时，则抛出异常
      throw new BindingException("Mapper method '" + command.getName() + "' has an unsupported return type: " + method.getReturnType());
    }
    return result;
  }
  // 使用ResultHandler处理查询结果集
  private void executeWithResultHandler(SqlSession sqlSession, Object[] args) {
	// 获取SQL语句对应的MappedStatement对象，MappedStatement中记录了SQL语句相关信息
    MappedStatement ms = sqlSession.getConfiguration().getMappedStatement(command.getName());
    if (!StatementType.CALLABLE.equals(ms.getStatementType())
    		// 当使用ResultHandler处理结果集时，必须指定ResultMap或ResultType类型
        && void.class.equals(ms.getResultMaps().get(0).getType())) {
      throw new BindingException("method " + command.getName()
          + " needs either a @ResultMap annotation, a @ResultType annotation,"
          + " or a resultType attribute in XML so a ResultHandler can be used as a parameter.");
    }
    Object param = method.convertArgsToSqlCommandParam(args); // 转换实参列表
    if (method.hasRowBounds()) { // 检测参数列表中是否有RowBounds类型的参数
      // 获取RowBounds对象，根据MethodSignature.rowBoundsIndex字段指定位置，从args数组查找。
      RowBounds rowBounds = method.extractRowBounds(args);
      // 调用sqlSession.select()方法,执行查询，并由指定的ResultHandler处理结果对象
      sqlSession.select(command.getName(), param, rowBounds, method.extractResultHandler(args));
    } else {
      sqlSession.select(command.getName(), param, method.extractResultHandler(args));
    }
  }

  private <E> Object executeForMany(SqlSession sqlSession, Object[] args) {
    List<E> result;
    Object param = method.convertArgsToSqlCommandParam(args);// 转换实参列表
    if (method.hasRowBounds()) {// 检测参数列表中是否有RowBounds类型的参数
      // 获取RowBounds对象，根据MethodSignature.rowBoundsIndex字段指定位置，从args数组查找。
      RowBounds rowBounds = method.extractRowBounds(args);
      // 调用sqlSession.<E>selectList()方法完成查询
      result = sqlSession.<E>selectList(command.getName(), param, rowBounds);
    } else {
      result = sqlSession.<E>selectList(command.getName(), param);
    }
    // issue #510 Collections & arrays support
    // 将结果集转换为数组或Collection集合
    if (!method.getReturnType().isAssignableFrom(result.getClass())) {
      if (method.getReturnType().isArray()) {
        return convertToArray(result);
      } else {
        return convertToDeclaredCollection(sqlSession.getConfiguration(), result);
      }
    }
    return result;
  }

  private <T> Cursor<T> executeForCursor(SqlSession sqlSession, Object[] args) {
    Cursor<T> result;
    Object param = method.convertArgsToSqlCommandParam(args);
    if (method.hasRowBounds()) {
      RowBounds rowBounds = method.extractRowBounds(args);
      result = sqlSession.<T>selectCursor(command.getName(), param, rowBounds);
    } else {
      result = sqlSession.<T>selectCursor(command.getName(), param);
    }
    return result;
  }

  private <E> Object convertToDeclaredCollection(Configuration config, List<E> list) {
    // 使用 ObjectFactory，通过反射方式创建集合对象
	Object collection = config.getObjectFactory().create(method.getReturnType());
	// 创建MetaObject对象
    MetaObject metaObject = config.newMetaObject(collection);
    metaObject.addAll(list); // 实际上就是调用Collection.addAll()方法
    return collection;
  }

  @SuppressWarnings("unchecked")
  private <E> Object convertToArray(List<E> list) {
	// 获取数组元素的类型
    Class<?> arrayComponentType = method.getReturnType().getComponentType();
    // 创建数组对象
    Object array = Array.newInstance(arrayComponentType, list.size());
    if (arrayComponentType.isPrimitive()) { // 将list中每一项都添加到数组中
      for (int i = 0; i < list.size(); i++) {
        Array.set(array, i, list.get(i));
      }
      return array;
    } else {
      return list.toArray((E[])array);
    }
  }

  private <K, V> Map<K, V> executeForMap(SqlSession sqlSession, Object[] args) {
    Map<K, V> result;
    Object param = method.convertArgsToSqlCommandParam(args);// 转换实参列表
    if (method.hasRowBounds()) {
      RowBounds rowBounds = method.extractRowBounds(args);
      // 调用sqlSession.<K, V>selectMap()方法完成查询操作
      result = sqlSession.<K, V>selectMap(command.getName(), param, method.getMapKey(), rowBounds);
    } else {
      result = sqlSession.<K, V>selectMap(command.getName(), param, method.getMapKey());
    }
    return result;
  }

  public static class ParamMap<V> extends HashMap<String, V> {

    private static final long serialVersionUID = -2212268410512043556L;

    @Override
    public V get(Object key) {
      if (!super.containsKey(key)) {
        throw new BindingException("Parameter '" + key + "' not found. Available parameters are " + keySet());
      }
      return super.get(key);
    }

  }

  public static class SqlCommand {

    private final String name;  // 记录了SQL语句的名称
    // sql的类型，INSERT,DELETE,UPDATE,SELECT,FLUSH
    private final SqlCommandType type; // 记录了SQL语句的类型

    public SqlCommand(Configuration configuration, Class<?> mapperInterface, Method method) {
      final String methodName = method.getName(); // 获取方法名
      final Class<?> declaringClass = method.getDeclaringClass(); // 获取方法对应的Class
      MappedStatement ms = resolveMappedStatement(mapperInterface, methodName, declaringClass,
          configuration);
      if (ms == null) {
        if (method.getAnnotation(Flush.class) != null) { // 处理@Flush注解
          name = null;
          type = SqlCommandType.FLUSH;
        } else {
          // 抛出异常
          throw new BindingException("Invalid bound statement (not found): "
              + mapperInterface.getName() + "." + methodName);
        }
      } else {
        name = ms.getId(); // 初始化name与type
        type = ms.getSqlCommandType();
        if (type == SqlCommandType.UNKNOWN) {
          // 抛出异常
          throw new BindingException("Unknown execution method for: " + name);
        }
      }
    }

    public String getName() {
      return name;
    }

    public SqlCommandType getType() {
      return type;
    }

    private MappedStatement resolveMappedStatement(Class<?> mapperInterface, String methodName,
        Class<?> declaringClass, Configuration configuration) {
    	
      // SQL语句的名称是由Mapper接口的名称与对应的方法名称组成的
      String statementId = mapperInterface.getName() + "." + methodName;
      if (configuration.hasStatement(statementId)) {// 检测是否有该名称的SQL语句
    	// 从Configuration.mappedStatements集合中查找对应的MappedStatements对象
    	// MappedStatements对象中封装了SQL语句相关的信息，在Mybatis初始化时创建！
        return configuration.getMappedStatement(statementId);
      } else if (mapperInterface.equals(declaringClass)) { // mapperInterface 与  declaringClass相等
        return null;
      }
      for (Class<?> superInterface : mapperInterface.getInterfaces()) {
        if (declaringClass.isAssignableFrom(superInterface)) {
          // 递归调用
          MappedStatement ms = resolveMappedStatement(superInterface, methodName,
              declaringClass, configuration);
          if (ms != null) {
            return ms;
          }
        }
      }
      return null;
    }
  }

  public static class MethodSignature {

    private final boolean returnsMany;  // 返回值类型是否为Collection类型或是数组类型
    
    private final boolean returnsMap; // 返回值类型是否为Map类型
    
    private final boolean returnsVoid; // 返回值类型是否为void
    
    private final boolean returnsCursor; // 返回值是否为Cursor类型
    
    private final Class<?> returnType; // 返回值类型
    
    // 如果返回值类型时Map，则该字段记录了作为key的列名
    private final String mapKey;
    
    // 用来标记该方法参数列表中ResultHandler类型参数的位置
    private final Integer resultHandlerIndex;
    
    // 用来标记该方法参数列表中RowBounds类型参数的位置
    private final Integer rowBoundsIndex;
    
    // 该方法对应的ParamNameResolver对象
    private final ParamNameResolver paramNameResolver;

    public MethodSignature(Configuration configuration, Class<?> mapperInterface, Method method) {
     
      // 解析方法的返回类型，前面已经介绍过TypeParameterResolver
      Type resolvedReturnType = TypeParameterResolver.resolveReturnType(method, mapperInterface);
      if (resolvedReturnType instanceof Class<?>) {// 返回类型为ParameterizedType类型
        this.returnType = (Class<?>) resolvedReturnType;
      } else if (resolvedReturnType instanceof ParameterizedType) { // 返回类型为ParameterizedType类型
        this.returnType = (Class<?>) ((ParameterizedType) resolvedReturnType).getRawType();
      } else {
    	// 其它情况下
        this.returnType = method.getReturnType();
      }
      // 初始化returnsVoid、returnsMany、returnsCursor、mapKey、returnsMap等字段
      this.returnsVoid = void.class.equals(this.returnType);
      this.returnsMany = configuration.getObjectFactory().isCollection(this.returnType) || this.returnType.isArray();
      this.returnsCursor = Cursor.class.equals(this.returnType);
      
      // MethodSignature对应方法的返回值为Map且指定了@MapKey注解，则使用getMapKey()方法处理
      this.mapKey = getMapKey(method);
      
      this.returnsMap = this.mapKey != null;
      
      // 初始化rowBoundsIndex和resultHandlerIndex字段
      this.rowBoundsIndex = getUniqueParamIndex(method, RowBounds.class);// 记录指定参数类型(RowBounds)在方法参数列表中的索引

      this.resultHandlerIndex = getUniqueParamIndex(method, ResultHandler.class);// 记录指定参数类型(ResultHandler)在方法参数列表中的索引
      
      // 创建paramNameResolver对象
      this.paramNameResolver = new ParamNameResolver(configuration, method);
    }
    
    // 负责将args[]数组(用户传入的实参列表)转换成SQL语句对应的参数列表，它是通过
    // ParamNameResolver的构造函数完成的
    public Object convertArgsToSqlCommandParam(Object[] args) {
      return paramNameResolver.getNamedParams(args);
    }

    public boolean hasRowBounds() {
      return rowBoundsIndex != null;
    }
    
    // 实参args
    public RowBounds extractRowBounds(Object[] args) {
      return hasRowBounds() ? (RowBounds) args[rowBoundsIndex] : null;
    }

    public boolean hasResultHandler() {
      return resultHandlerIndex != null;
    }

    @SuppressWarnings("rawtypes") // 返回resultHandlerIndex位置的resultHandler
	public ResultHandler extractResultHandler(Object[] args) {
      return hasResultHandler() ? (ResultHandler) args[resultHandlerIndex] : null;
    }

    public String getMapKey() {
      return mapKey;
    }

    public Class<?> getReturnType() {
      return returnType;
    }

    public boolean returnsMany() {
      return returnsMany;
    }

    public boolean returnsMap() {
      return returnsMap;
    }

    public boolean returnsVoid() {
      return returnsVoid;
    }

    public boolean returnsCursor() {
      return returnsCursor;
    }
    // 查找指定类型的参数在参数列表中的位置
    private Integer getUniqueParamIndex(Method method, Class<?> paramType) {
      Integer index = null;
      final Class<?>[] argTypes = method.getParameterTypes();
      for (int i = 0; i < argTypes.length; i++) { // 遍历MethodSignature对象方法的参数列表
        if (paramType.isAssignableFrom(argTypes[i])) {
          if (index == null) { // 记录paramType类型参数在参数列表中的位置索引
            index = i;
          } else { // rowBoundsIndex和resultHandlerIndex类型的参数只能出现一个
            throw new BindingException(method.getName() + " cannot have multiple " + paramType.getSimpleName() + " parameters");
          }
        }
      }
      return index;
    }

    private String getMapKey(Method method) {
      String mapKey = null;
      if (Map.class.isAssignableFrom(method.getReturnType())) {
        final MapKey mapKeyAnnotation = method.getAnnotation(MapKey.class);
        if (mapKeyAnnotation != null) {
          mapKey = mapKeyAnnotation.value();
        }
      }
      return mapKey;
    }
  }

}
