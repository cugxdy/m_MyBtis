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
package org.apache.ibatis.executor.loader;

import java.io.Serializable;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.security.AccessController;
import java.security.PrivilegedActionException;
import java.security.PrivilegedExceptionAction;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.apache.ibatis.cursor.Cursor;
import org.apache.ibatis.executor.BaseExecutor;
import org.apache.ibatis.executor.BatchResult;
import org.apache.ibatis.executor.ExecutorException;
import org.apache.ibatis.logging.Log;
import org.apache.ibatis.logging.LogFactory;
import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.reflection.MetaObject;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.ResultHandler;
import org.apache.ibatis.session.RowBounds;

/**
 * @author Clinton Begin
 * @author Franta Mejta
 */
public class ResultLoaderMap {
	
	
  // 这里使用loadMap字段(HashMap<String, LoadPair>())保存对象中延迟加载属性及其对应的ResultLoader对象
  // 之间的关系
  private final Map<String, LoadPair> loaderMap = new HashMap<String, LoadPair>();

  /**
   * 
   * @param property
   * @param metaResultObject
   * @param resultLoader
   */
  public void addLoader(String property, MetaObject metaResultObject, ResultLoader resultLoader) {
    String upperFirst = getUppercaseFirstProperty(property);
    if (!upperFirst.equalsIgnoreCase(property) && loaderMap.containsKey(upperFirst)) {
       // 重复加载时，报错(抛出异常)
       throw new ExecutorException("Nested lazy loaded result property '" + property +
              "' for query id '" + resultLoader.mappedStatement.getId() +
              " already exists in the result map. The leftmost property of all lazy loaded properties must be unique within a result map.");
    }
    loaderMap.put(upperFirst, new LoadPair(property, metaResultObject, resultLoader));
  }

  public final Map<String, LoadPair> getProperties() {
    return new HashMap<String, LoadPair>(this.loaderMap);
  }

  public Set<String> getPropertyNames() {
	// 返回key对应的set集合
    return loaderMap.keySet();
  }

  public int size() {
	// 大小
    return loaderMap.size();
  }

  public boolean hasLoader(String property) {
	// 是否包含property
    return loaderMap.containsKey(property.toUpperCase(Locale.ENGLISH));
  }

  public boolean load(String property) throws SQLException {
	// 从loadMap集合中移除指定的属性
    LoadPair pair = loaderMap.remove(property.toUpperCase(Locale.ENGLISH));
    if (pair != null) {
      pair.load(); //　调用LoadPair.load()方法执行延迟加载
      return true;
    }
    return false;
  }

  public void remove(String property) {
	// 删除指定项
    loaderMap.remove(property.toUpperCase(Locale.ENGLISH));
  }

  public void loadAll() throws SQLException {
	
    final Set<String> methodNameSet = loaderMap.keySet();
    // 转化成数组类型
    String[] methodNames = methodNameSet.toArray(new String[methodNameSet.size()]);
    for (String methodName : methodNames) {
      load(methodName); // 加载loadMap集合中记录的全部属性(对其中每个延迟加载项执行解析加载)
    }
  }

  private static String getUppercaseFirstProperty(String property) {
    String[] parts = property.split("\\.");
    return parts[0].toUpperCase(Locale.ENGLISH); // 获取相应的字符串
  }

  /**
   * Property which was not loaded yet.
   */
  public static class LoadPair implements Serializable {

    private static final long serialVersionUID = 20130412;
    
    
    /**
     * Name of factory method which returns database connection.
     */
    private static final String FACTORY_METHOD = "getConfiguration";
    
    
    /**
     * Object to check whether we went through serialization..
     */
    private final transient Object serializationCheck = new Object();
    
    
    /**
     * Meta object which sets loaded properties.
     */
    // 外层对象(一般是外层对象的代理对象)对应的MetaObject对象
    private transient MetaObject metaResultObject;
    
    
    /**
     * Result loader which loads unread properties.
     */
    // 负责延迟加载属性的ResultLoader对象
    private transient ResultLoader resultLoader;
    
    
    /**
     * Wow, logger.
     */
    // 日志记录器
    private transient Log log;
    
    
    /**
     * Factory class through which we get database connection.
     */
    private Class<?> configurationFactory;
    
    
    /**
     * Name of the unread property.
     */
    private String property; //　延迟加载的属性名称
    
    
    /**
     * ID of SQL statement which loads the property.
     */
    private String mappedStatement; // 用于加载属性的SQL语句的id
    
    
    /**
     * Parameter of the sql statement.
     */
    private Serializable mappedParameter;

    /**
     * 
     * @param property
     * @param metaResultObject 主结果对象
     * @param resultLoader // 延迟加载项
     */
    private LoadPair(final String property, MetaObject metaResultObject, ResultLoader resultLoader) {
      this.property = property;
      this.metaResultObject = metaResultObject;
      this.resultLoader = resultLoader;

      /* Save required information only if original object can be serialized. */
      // 原始的javaBean对象(继承了Serializable接口的一些处理)
      if (metaResultObject != null && metaResultObject.getOriginalObject() instanceof Serializable) {
        // 实参
    	final Object mappedStatementParameter = resultLoader.parameterObject;

        /* @todo May the parameter be null? */
        if (mappedStatementParameter instanceof Serializable) {
          this.mappedStatement = resultLoader.mappedStatement.getId();
          this.mappedParameter = (Serializable) mappedStatementParameter;

          this.configurationFactory = resultLoader.configuration.getConfigurationFactory();
        } else {
          Log log = this.getLogger();
          if (log.isDebugEnabled()) {
            log.debug("Property [" + this.property + "] of ["
                    + metaResultObject.getOriginalObject().getClass() + "] cannot be loaded "
                    + "after deserialization. Make sure it's loaded before serializing "
                    + "forenamed object.");
          }
        }
      }
    }

    public void load() throws SQLException {
      /* These field should not be null unless the loadpair was serialized.
       * Yet in that case this method should not be called. */
      if (this.metaResultObject == null) {
    	// 外层对象(一般是外层对象的代理对象)对应的MetaObject对象为空时，抛出异常
        throw new IllegalArgumentException("metaResultObject is null");
      }
      if (this.resultLoader == null) {
    	// 负责延迟加载属性的ResultLoader对象为空时，抛出异常
        throw new IllegalArgumentException("resultLoader is null");
      }

      this.load(null);
    }

    public void load(final Object userObject) throws SQLException {
      // 经过一系列的检测,会创建相应的ResultLoader对象
      if (this.metaResultObject == null || this.resultLoader == null) {
        if (this.mappedParameter == null) { // mappedParameter == null 抛出异常
          throw new ExecutorException("Property [" + this.property + "] cannot be loaded because "
                  + "required parameter of mapped statement ["
                  + this.mappedStatement + "] is not serializable.");
        }
        
        final Configuration config = this.getConfiguration();
        
        // 获取MappedStatement对象
        final MappedStatement ms = config.getMappedStatement(this.mappedStatement);
        if (ms == null) {
          throw new ExecutorException("Cannot lazy load property [" + this.property
                  + "] of deserialized object [" + userObject.getClass()
                  + "] because configuration does not contain statement ["
                  + this.mappedStatement + "]");
        }
        // 生成MetaObject对象
        this.metaResultObject = config.newMetaObject(userObject);
        
        // 生成ResultLoader对象
        this.resultLoader = new ResultLoader(config, new ClosedExecutor(), ms, this.mappedParameter,
                metaResultObject.getSetterType(this.property), null, null);
      }

      /* We are using a new executor because we may be (and likely are) on a new thread
       * and executors aren't thread safe. (Is this sufficient?)
       *
       * A better approach would be making executors thread safe. */
      if (this.serializationCheck == null) {
    	// 让线程安全
        final ResultLoader old = this.resultLoader;
        this.resultLoader = new ResultLoader(old.configuration, new ClosedExecutor(), old.mappedStatement,
                old.parameterObject, old.targetType, old.cacheKey, old.boundSql);
      }
      // 调用ResultLoader对象.loadResult方法执行延迟加载，将加载得到的嵌套对象设置到外层对象中
      // this.resultLoader.loadResult()获取延迟加载对象
      this.metaResultObject.setValue(property, this.resultLoader.loadResult());
    }
  
    private Configuration getConfiguration() {
      if (this.configurationFactory == null) {
        throw new ExecutorException("Cannot get Configuration as configuration factory was not set.");
      }

      Object configurationObject = null;
      try {
    	// 得到getConfiguration方法对应的对象
        final Method factoryMethod = this.configurationFactory.getDeclaredMethod(FACTORY_METHOD);
        if (!Modifier.isStatic(factoryMethod.getModifiers())) {
          // getConfiguration在非static时，抛出异常
          throw new ExecutorException("Cannot get Configuration as factory method ["
                  + this.configurationFactory + "]#["
                  + FACTORY_METHOD + "] is not static.");
        }

        if (!factoryMethod.isAccessible()) {  // 判断override是否为false
          configurationObject = AccessController.doPrivileged(new PrivilegedExceptionAction<Object>() {
            @Override
            public Object run() throws Exception {
              try {
                factoryMethod.setAccessible(true);
                return factoryMethod.invoke(null);
              } finally {
                factoryMethod.setAccessible(false);
              }
            }
          });
        } else {
          // 指定调用该方法
          configurationObject = factoryMethod.invoke(null);
        }
      } catch (final ExecutorException ex) {
        throw ex;
      } catch (final NoSuchMethodException ex) {
        throw new ExecutorException("Cannot get Configuration as factory class ["
                + this.configurationFactory + "] is missing factory method of name ["
                + FACTORY_METHOD + "].", ex);
      } catch (final PrivilegedActionException ex) {
        throw new ExecutorException("Cannot get Configuration as factory method ["
                + this.configurationFactory + "]#["
                + FACTORY_METHOD + "] threw an exception.", ex.getCause());
      } catch (final Exception ex) {
        throw new ExecutorException("Cannot get Configuration as factory method ["
                + this.configurationFactory + "]#["
                + FACTORY_METHOD + "] threw an exception.", ex);
      }

      if (!(configurationObject instanceof Configuration)) {
    	// configurationObject不为Configuration类型时
        throw new ExecutorException("Cannot get Configuration as factory method ["
                + this.configurationFactory + "]#["
                + FACTORY_METHOD + "] didn't return [" + Configuration.class + "] but ["
                + (configurationObject == null ? "null" : configurationObject.getClass()) + "].");
      }
      // 转换为Configuration类型
      return Configuration.class.cast(configurationObject);
    }

    private Log getLogger() {
      if (this.log == null) {
        this.log = LogFactory.getLog(this.getClass());
      }
      return this.log;
    }
  }

  private static final class ClosedExecutor extends BaseExecutor {

    public ClosedExecutor() {
      super(null, null);
    }

    @Override
    public boolean isClosed() {
      return true;
    }

    @Override
    protected int doUpdate(MappedStatement ms, Object parameter) throws SQLException {
      throw new UnsupportedOperationException("Not supported.");
    }

    @Override
    protected List<BatchResult> doFlushStatements(boolean isRollback) throws SQLException {
      throw new UnsupportedOperationException("Not supported.");
    }

    @Override
    protected <E> List<E> doQuery(MappedStatement ms, Object parameter, RowBounds rowBounds, ResultHandler resultHandler, BoundSql boundSql) throws SQLException {
      throw new UnsupportedOperationException("Not supported.");
    }

    @Override
    protected <E> Cursor<E> doQueryCursor(MappedStatement ms, Object parameter, RowBounds rowBounds, BoundSql boundSql) throws SQLException {
      throw new UnsupportedOperationException("Not supported.");
    }
  }
}
