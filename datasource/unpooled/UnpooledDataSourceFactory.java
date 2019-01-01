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
package org.apache.ibatis.datasource.unpooled;

import java.util.Hashtable;
import java.util.Properties;

import javax.sql.DataSource;

import org.apache.ibatis.datasource.DataSourceException;
import org.apache.ibatis.datasource.DataSourceFactory;
import org.apache.ibatis.reflection.MetaObject;
import org.apache.ibatis.reflection.SystemMetaObject;

/**
 * @author Clinton Begin
 */
public class UnpooledDataSourceFactory implements DataSourceFactory {

  private static final String DRIVER_PROPERTY_PREFIX = "driver.";
  private static final int DRIVER_PROPERTY_PREFIX_LENGTH = DRIVER_PROPERTY_PREFIX.length();

  protected DataSource dataSource;

  public UnpooledDataSourceFactory() {
    this.dataSource = new UnpooledDataSource();  // 初始化UnpooledDataSource字段
  }

  
/* HashMap和Hashtable都实现了Map接口，但决定用哪一个之前先要弄清楚它们之间的分别。
 * 主要的区别有：线程安全性，同步(synchronization)，以及速度。
 * HashMap几乎可以等价于Hashtable，除了HashMap是非synchronized的，并可以接受null(HashMap可以接受为null的键值(key)和值(value)，
 * 而Hashtable则不行)。
 * HashMap是非synchronized，而Hashtable是synchronized，这意味着Hashtable是线程安全的，多个线程可以共享一个Hashtable；而如果没有正确的同步的话，多个线程是不能共享HashMap的。
 * Java 5提供了ConcurrentHashMap，它是HashTable的替代，比HashTable的扩展性更好。
 * 另一个区别是HashMap的迭代器(Iterator)是fail-fast迭代器，而Hashtable的enumerator迭代器不是fail-fast的。
 * 所以当有其它线程改变了HashMap的结构（增加或者移除元素），将会抛出ConcurrentModificationException，但迭代器本身的remove()方法移除元素则不会抛出ConcurrentModificationException异常。
 * 但这并不是一个一定发生的行为，要看JVM。这条同样也是Enumeration和Iterator的区别。由于Hashtable是线程安全的也是synchronized，所以在单线程环境下它比HashMap要慢。如果你不需要同步，只需要单一线程，那么使用HashMap性能要好过Hashtable。
 * HashMap不能保证随着时间的推移Map中的元素次序是不变的。*/
  // Properties继承了HashTable(Hashtable<Object,Object>)类
  @Override
  public void setProperties(Properties properties) {
	  
    Properties driverProperties = new Properties(); 
    // 创建DataSource相对应的MetaObject
    MetaObject metaDataSource = SystemMetaObject.forObject(dataSource);
    
    // 遍历properties集合,该集合中配置了数据源需要的信息
    for (Object key : properties.keySet()) {
      String propertyName = (String) key;
      if (propertyName.startsWith(DRIVER_PROPERTY_PREFIX)) {
    	// 以"Dirver."开头的配置项是对DataSource的配置，记录到driverProperties中保存
        String value = properties.getProperty(propertyName);
        driverProperties.setProperty(propertyName.substring(DRIVER_PROPERTY_PREFIX_LENGTH), value);
      } else if (metaDataSource.hasSetter(propertyName)) { // 是否有该属性的setter方法
    	  
    	// 完成  DataSource相关的赋值操作
    	
        String value = (String) properties.get(propertyName);
        // 根据属性类型进行类型转换，主要是Integer、Long、Boolean三种类型的转换
        Object convertedValue = convertValue(metaDataSource, propertyName, value);
        // 设置DataSource的相关属性值
        metaDataSource.setValue(propertyName, convertedValue);  
        
      } else {
        throw new DataSourceException("Unknown DataSource property: " + propertyName);
      }
    }
    if (driverProperties.size() > 0) {// 设置DataSource.driverProperties属性值
      metaDataSource.setValue("driverProperties", driverProperties);
    }
  }

  @Override
  public DataSource getDataSource() {
    return dataSource;
  }

  private Object convertValue(MetaObject metaDataSource, String propertyName, String value) {
    Object convertedValue = value;
    Class<?> targetType = metaDataSource.getSetterType(propertyName);
    if (targetType == Integer.class || targetType == int.class) {
      convertedValue = Integer.valueOf(value);  // int类型的转换
    } else if (targetType == Long.class || targetType == long.class) {
      convertedValue = Long.valueOf(value);// long类型的转换
    } else if (targetType == Boolean.class || targetType == boolean.class) {
      convertedValue = Boolean.valueOf(value);// boolean类型的转换
    }
    return convertedValue;
  }

}
