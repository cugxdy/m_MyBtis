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
package org.apache.ibatis.cache.decorators;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.locks.ReadWriteLock;

import org.apache.ibatis.cache.Cache;
import org.apache.ibatis.logging.Log;
import org.apache.ibatis.logging.LogFactory;

/**
 * The 2nd level cache transactional buffer.
 * 
 * This class holds all cache entries that are to be added to the 2nd level cache during a Session.
 * Entries are sent to the cache when commit is called or discarded if the Session is rolled back. 
 * Blocking cache support has been added. Therefore any get() that returns a cache miss 
 * will be followed by a put() so any lock associated with the key can be released. 
 * 
 * @author Clinton Begin
 * @author Eduardo Macarron
 */
public class TransactionalCache implements Cache {

  private static final Log log = LogFactory.getLog(TransactionalCache.class);

  // 实际上具体的缓存的对象(PerpetualCache类的实例)
  private final Cache delegate; //底层封装了的二级缓存所对应的Cache对象
  
  // 当该字段为true时，则表示当前TransactionalCache不可查询，且提交事务时会将底层Cache清空
  private boolean clearOnCommit;
  
  // 暂时记录添加到TransactionalCache中的数据，在事务提交时，会将其中的数据添加到二级缓存中
  private final Map<Object, Object> entriesToAddOnCommit;
  
  // 记录缓存未命中的CacheKey对象
  private final Set<Object> entriesMissedInCache;

  public TransactionalCache(Cache delegate) {
    this.delegate = delegate;
    this.clearOnCommit = false;
    this.entriesToAddOnCommit = new HashMap<Object, Object>();
    this.entriesMissedInCache = new HashSet<Object>();
  }

  @Override
  public String getId() {
	// 调用相应的Cache对象的实现方法
    return delegate.getId();
  }

  @Override
  public int getSize() {
	// 调用相应的Cache对象的实现方法
    return delegate.getSize();
  }

  // 调用相应的Cache对象的getObject方法从缓存中拿取数据
  @Override
  public Object getObject(Object key) {
    // issue #116
	// 查询底层的Cache是否包含指定的key
    Object object = delegate.getObject(key);
    if (object == null) {
      // 如果底层缓存对象不包含该缓存项，则将该记录到entriesMissedInCache集合中
      entriesMissedInCache.add(key);
    }
    // issue #146
    // 如果clearOnCommit为true，则当前TransactionalCache不可查询，始终返回null
    if (clearOnCommit) {
      return null;
    } else {
      return object;// 返回从底层Cache中查询到的对象
    }
  }

  @Override
  public ReadWriteLock getReadWriteLock() {
    return null;
  }

  @Override
  public void putObject(Object key, Object object) {
	// entriesToAddOnCommit存放未提交之前的缓存数据
    entriesToAddOnCommit.put(key, object); // 将缓存项暂存在entriesToAddOnCommit集合中
  }

  @Override
  public Object removeObject(Object key) {
    return null;
  }

  @Override
  public void clear() {
    clearOnCommit = true; // 设置TransactionalCache不可查询
    entriesToAddOnCommit.clear(); // 清空entriesToAddOnCommit集合
  }

  // 提交时，会将entriesToAddOnCommit集合中的数据保存到真正具体的Cache对象中去
  public void commit() {
    if (clearOnCommit) {
      delegate.clear();
    }
    flushPendingEntries(); // 将entriesToAddOnCommit集合中的数据保存到二级缓存
    // 重置clearOnCommit为false，并清空entriesToAddOnCommit、entriesMissedInCache集合
    reset();
  }

  public void rollback() {
	// 将entriesMissedInCache集合中记录的缓存项在二级缓存中删除
    unlockMissedEntries();
    reset();// 重置clearOnCommit为false，并清空entriesToAddOnCommit、entriesMissedInCache集合
  }

  private void reset() {
    clearOnCommit = false;
    entriesToAddOnCommit.clear();
    entriesMissedInCache.clear();
  }
  // 将entriesToAddOnCommit集合中的数据保存到二级缓存(Cache对象中去)
  private void flushPendingEntries() {
	  
	// 遍历entriesToAddOnCommit集合，将其中的记录的缓存项添加到二级缓存中
    for (Map.Entry<Object, Object> entry : entriesToAddOnCommit.entrySet()) {
      delegate.putObject(entry.getKey(), entry.getValue());
    }
    // 遍历entriesMissedInCache集合,将entriesMissedInCache集合中不包含的缓存项添加到二级缓存中
    for (Object entry : entriesMissedInCache) {
      if (!entriesToAddOnCommit.containsKey(entry)) {
        delegate.putObject(entry, null); // 未命中时也存入二级缓存中(但是相应的value为null)
      }
    }
  }

  private void unlockMissedEntries() {
    for (Object entry : entriesMissedInCache) {
      try {
        delegate.removeObject(entry); // 从二级缓存中将entriesMissedInCache中相对的key删除
      } catch (Exception e) {
        log.warn("Unexpected exception while notifiying a rollback to the cache adapter."
            + "Consider upgrading your cache adapter to the latest version.  Cause: " + e);
      }
    }
  }

}
