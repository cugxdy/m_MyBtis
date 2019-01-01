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
package org.apache.ibatis.mapping;

import java.lang.reflect.Constructor;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import org.apache.ibatis.cache.Cache;
import org.apache.ibatis.cache.CacheException;
import org.apache.ibatis.builder.InitializingObject;
import org.apache.ibatis.cache.decorators.BlockingCache;
import org.apache.ibatis.cache.decorators.LoggingCache;
import org.apache.ibatis.cache.decorators.LruCache;
import org.apache.ibatis.cache.decorators.ScheduledCache;
import org.apache.ibatis.cache.decorators.SerializedCache;
import org.apache.ibatis.cache.decorators.SynchronizedCache;
import org.apache.ibatis.cache.impl.PerpetualCache;
import org.apache.ibatis.reflection.MetaObject;
import org.apache.ibatis.reflection.SystemMetaObject;

/**
 * @author Clinton Begin
 */
public class CacheBuilder {
  private final String id; // Cache对象的唯一ID，一般情况下对应映射文件的配置namespace
  
  // Cache接口的实现类，默认是前面介绍的PerpetualCache
  private Class<? extends Cache> implementation;
  
  private final List<Class<? extends Cache>> decorators; // 装饰器集合，默认只包含LruCache.class
  private Integer size; // cache大小
  private Long clearInterval; // 清理时间周期
  private boolean readWrite;// 是否可读写
  private Properties properties;// 其他配置信息
  private boolean blocking;// 是否阻塞

  public CacheBuilder(String id) {
    this.id = id;
    this.decorators = new ArrayList<Class<? extends Cache>>();
  }

  public CacheBuilder implementation(Class<? extends Cache> implementation) {
    this.implementation = implementation;
    return this;
  }

  public CacheBuilder addDecorator(Class<? extends Cache> decorator) {
    if (decorator != null) {
      this.decorators.add(decorator);
    }
    return this;
  }

  public CacheBuilder size(Integer size) {
    this.size = size;
    return this;
  }

  public CacheBuilder clearInterval(Long clearInterval) {
    this.clearInterval = clearInterval;
    return this;
  }

  public CacheBuilder readWrite(boolean readWrite) {
    this.readWrite = readWrite;
    return this;
  }

  public CacheBuilder blocking(boolean blocking) {
    this.blocking = blocking;
    return this;
  }
  
  public CacheBuilder properties(Properties properties) {
    this.properties = properties;
    return this;
  }

  public Cache build() {
	  
	// 如果implementation字段和decorators集合为空，则为其设置默认值，
	// implementation默认为PerpetyalCache.class,decorators集合默认只包含LruCache.class
    setDefaultImplementations();
    
    // 根据implementation指定的类型，通过反射获取参数为String类型的构造函数方法，并通过该构造方法创建
    // Cache对象
    Cache cache = newBaseCacheInstance(implementation, id);
    
    // 根据<cache>节点下配置的<property>信息，初始化Cache对象
    setCacheProperties(cache);
    // issue #352, do not apply decorators to custom caches
    
    // 检测cache对象的类型，如果是PerpetyalCache类型，则为其添加decorators集合中的装饰器
    // 如果是自定义的类型的Cache接口实现，则不添加decorators集合中的装饰器
    if (PerpetualCache.class.equals(cache.getClass())) {
    	
      for (Class<? extends Cache> decorator : decorators) {
    	// 通过反射获取参数为Cache类型的构造方法，并通过该构造方法创建装饰器
        cache = newCacheDecoratorInstance(decorator, cache);
        // 配置Cache对象的属性
        setCacheProperties(cache);
      }
      
      // 添加Mybatis中提供的装饰器 (mybatis自带的装饰器去完成扩展功能)
      cache = setStandardDecorators(cache);
      // isAssignableFrom()判断是继承了某个类
    } else if (!LoggingCache.class.isAssignableFrom(cache.getClass())) {
      // 如果不是LoggingCache的子类，则添加LoggingCache装饰器
      cache = new LoggingCache(cache);
    }
    return cache;
  }

  private void setDefaultImplementations() {
    if (implementation == null) {
      implementation = PerpetualCache.class;
      if (decorators.isEmpty()) {
        decorators.add(LruCache.class);
      }
    }
  }
  // 它会依据CacheBulider中各个字段的值，为cache对象添加对应的装饰器
  private Cache setStandardDecorators(Cache cache) {
    try {
      // 创建cache对象对应的MetaObject对象
      MetaObject metaCache = SystemMetaObject.forObject(cache);
      if (size != null && metaCache.hasSetter("size")) {
        metaCache.setValue("size", size);
      }
      if (clearInterval != null) {// 检测是否指定了clearInterval字段
        cache = new ScheduledCache(cache); // 添加ScheduledCache装饰器
        // 设置ScheduledCache的clearInterval字段
        ((ScheduledCache) cache).setClearInterval(clearInterval);
      }
      if (readWrite) {// 是否只读，对应添加SerializedCache装饰器
        cache = new SerializedCache(cache);
      }
      // 默认添加LoggingCache和SynchronizedCache装饰器
      cache = new LoggingCache(cache);
      cache = new SynchronizedCache(cache);
      if (blocking) { // 是否阻塞，对应添加BlockingCache装饰器
        cache = new BlockingCache(cache);
      }
      return cache;
    } catch (Exception e) {
      throw new CacheException("Error building standard cache decorators.  Cause: " + e, e);
    }
  }

  private void setCacheProperties(Cache cache) {
    if (properties != null) {
      // cache对应创建的MetaObject对象
      MetaObject metaCache = SystemMetaObject.forObject(cache);
      for (Map.Entry<Object, Object> entry : properties.entrySet()) {
        String name = (String) entry.getKey(); // 配置项的名称，即Cache对应的属性名称
        String value = (String) entry.getValue();// 配置项的值，即Cache对应的属性值
        
        // 根据缓存对象设置相应的属性值
        if (metaCache.hasSetter(name)) { // 检测Cache是否有该属性的对应的setter方法
          Class<?> type = metaCache.getSetterType(name); // 获取该属性的类型
          if (String.class == type) { // 进行类型转换，并设置该属性值
        	// 设置String类型的属性
            metaCache.setValue(name, value);
          } else if (int.class == type || Integer.class == type) {
        	// 设置int类型的属性  
            metaCache.setValue(name, Integer.valueOf(value));
          } else if (long.class == type || Long.class == type) {
        	// 设置long类型的属性  
            metaCache.setValue(name, Long.valueOf(value));
          } else if (short.class == type || Short.class == type) {  
        	// 设置short类型的属性  
            metaCache.setValue(name, Short.valueOf(value));
          } else if (byte.class == type || Byte.class == type) {
        	// 设置byte类型的属性  
            metaCache.setValue(name, Byte.valueOf(value));
          } else if (float.class == type || Float.class == type) {
        	// 设置float类型的属性  
            metaCache.setValue(name, Float.valueOf(value));
          } else if (boolean.class == type || Boolean.class == type) {
        	// 设置boolean类型的属性  
            metaCache.setValue(name, Boolean.valueOf(value));
          } else if (double.class == type || Double.class == type) {
        	// 设置double类型的属性  
            metaCache.setValue(name, Double.valueOf(value));
          } else {
            throw new CacheException("Unsupported property type for cache: '" + name + "' of type " + type);
          }
        }
      }
    }
    // 如果Cache类继承了InitializingObject接口，则调用其initialize方法继续自定义的初始化操作
    // isAssignableFrom()方法检验指定类是否继承特定的类
    if (InitializingObject.class.isAssignableFrom(cache.getClass())){
      try {
    	// 自定义的操作
        ((InitializingObject) cache).initialize();
      } catch (Exception e) {
    	// 抛出异常
        throw new CacheException("Failed cache initialization for '" +
            cache.getId() + "' on '" + cache.getClass().getName() + "'", e);
      }
    }
  }

  private Cache newBaseCacheInstance(Class<? extends Cache> cacheClass, String id) {
	// 获取cacheClass的构造器函数
    Constructor<? extends Cache> cacheConstructor = getBaseCacheConstructor(cacheClass);
    try {
      return cacheConstructor.newInstance(id); // 生成具体的缓存对象
    } catch (Exception e) {
      throw new CacheException("Could not instantiate cache implementation (" + cacheClass + "). Cause: " + e, e);
    }
  }

  private Constructor<? extends Cache> getBaseCacheConstructor(Class<? extends Cache> cacheClass) {
    try {
      // 获取cacheClass的构造器函数
      return cacheClass.getConstructor(String.class);
    } catch (Exception e) {
      throw new CacheException("Invalid base cache implementation (" + cacheClass + ").  " +
          "Base cache implementations must have a constructor that takes a String id as a parameter.  Cause: " + e, e);
    }
  }

  private Cache newCacheDecoratorInstance(Class<? extends Cache> cacheClass, Cache base) {
    Constructor<? extends Cache> cacheConstructor = getCacheDecoratorConstructor(cacheClass);
    try {
    	// 实例化该对象
      return cacheConstructor.newInstance(base);
    } catch (Exception e) {
      throw new CacheException("Could not instantiate cache decorator (" + cacheClass + "). Cause: " + e, e);
    }
  }

  private Constructor<? extends Cache> getCacheDecoratorConstructor(Class<? extends Cache> cacheClass) {
    try {
    	// 获取参数类型为(Cache)的构造函数
      return cacheClass.getConstructor(Cache.class);
    } catch (Exception e) {
      throw new CacheException("Invalid cache decorator (" + cacheClass + ").  " +
          "Cache decorators must have a constructor that takes a Cache instance as a parameter.  Cause: " + e, e);
    }
  }
}
