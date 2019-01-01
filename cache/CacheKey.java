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
package org.apache.ibatis.cache;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

import org.apache.ibatis.reflection.ArrayUtil;

/**
 * @author Clinton Begin
 */
public class CacheKey implements Cloneable, Serializable {

  private static final long serialVersionUID = 1146682552656046210L;

  public static final CacheKey NULL_CACHE_KEY = new NullCacheKey();

  private static final int DEFAULT_MULTIPLYER = 37;
  private static final int DEFAULT_HASHCODE = 17;

  private final int multiplier;  // 参与计算hashCode，默认值为37
  
  private int hashcode; // CacheKey对象的hashCode，初始值为17
  
  private long checksum; // 检验和
  
  private int count;// updateList集合的个数
  
  // 8/21/2017 - Sonarlint flags this as needing to be marked transient.  While true if content is not serializable, this is not always true and thus should not be marked transient.
  private List<Object> updateList; // 由该集合中的所有对象共同决定两个CacheKey是否相同

  public CacheKey() {
    this.hashcode = DEFAULT_HASHCODE;
    this.multiplier = DEFAULT_MULTIPLYER;
    this.count = 0;
    this.updateList = new ArrayList<Object>();
  }

  public CacheKey(Object[] objects) {
    this();
    updateAll(objects);
  }

  public int getUpdateCount() {
    return updateList.size();
  }

  public void update(Object object) {
	// ArrayUtil.hashCode()方法是调用ArrayUtil里面的静态方法
    int baseHashCode = object == null ? 1 : ArrayUtil.hashCode(object); 
    // 重新计算count、checkSum和hashCode的值
    count++;
    checksum += baseHashCode;
    baseHashCode *= count;

    hashcode = multiplier * hashcode + baseHashCode;
    // 将Object添加到updateList中
    updateList.add(object);
  }

  public void updateAll(Object[] objects) {
    for (Object o : objects) {
      update(o);
    }
  }

  // 判断两个CacheKey是否相等(updateList集合要全部相等才相等)
  @Override
  public boolean equals(Object object) {
    if (this == object) { // 是否为同一对象
      return true;
    }
    if (!(object instanceof CacheKey)) { // 是否类型相同
      return false;
    }

    final CacheKey cacheKey = (CacheKey) object;

    if (hashcode != cacheKey.hashcode) { // 比较hashCode
      return false;
    }
    if (checksum != cacheKey.checksum) { // 比较checksum
      return false;
    }
    if (count != cacheKey.count) { // 比较count
      return false;
    }

    for (int i = 0; i < updateList.size(); i++) { // 比较updateList每一项
      Object thisObject = updateList.get(i);
      Object thatObject = cacheKey.updateList.get(i);
      // ArrayUtil.equals()方法是调用ArrayUtil里面的静态方法
      if (!ArrayUtil.equals(thisObject, thatObject)) {
        return false;
      }
    }
    return true;
  }

  @Override
  public int hashCode() {
    return hashcode;
  }

  // 输出CacheKey对象的字符串函数
  @Override
  public String toString() {
    StringBuilder returnValue = new StringBuilder().append(hashcode).append(':').append(checksum);
    for (Object object : updateList) {
      // ArrayUtil.toString()方法是调用ArrayUtil里面的静态方法
      returnValue.append(':').append(ArrayUtil.toString(object));
    }
    return returnValue.toString();
  }

  // 原型模式(实现了Cloneable接口) (我的理解就是完全复制一个对象)
  @Override
  public CacheKey clone() throws CloneNotSupportedException {
    CacheKey clonedCacheKey = (CacheKey) super.clone();
    clonedCacheKey.updateList = new ArrayList<Object>(updateList);
    return clonedCacheKey;
  }

}
