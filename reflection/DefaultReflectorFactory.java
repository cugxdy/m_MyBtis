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
package org.apache.ibatis.reflection;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

public class DefaultReflectorFactory implements ReflectorFactory {
	
  private boolean classCacheEnabled = true; // 默认允许缓存(该字段决定是否开启对Reflector对象的缓存)
  
  // 使用ConcurrentMap集合实现对Reflector对象的缓存
  private final ConcurrentMap<Class<?>, Reflector> reflectorMap = new ConcurrentHashMap<Class<?>, Reflector>();

  public DefaultReflectorFactory() {
	  
  }

  @Override
  public boolean isClassCacheEnabled() {
    return classCacheEnabled;
  }

  @Override
  public void setClassCacheEnabled(boolean classCacheEnabled) {
    this.classCacheEnabled = classCacheEnabled;
  }

  @Override
  public Reflector findForClass(Class<?> type) {
    if (classCacheEnabled) { // 检测缓存是否开启
            // synchronized (type) removed see issue #461
      // 从缓存中获取Reflector对象
      Reflector cached = reflectorMap.get(type);
      if (cached == null) {
        cached = new Reflector(type); // 创建Reflector对象(在构造器完成相应字段的初始化)
        reflectorMap.put(type, cached);// 放入到reflectorMap中缓存
      }
      return cached;
    } else {
      return new Reflector(type); // 未开启缓存，则直接创建并返回Reflector对象
    }
  }

}
