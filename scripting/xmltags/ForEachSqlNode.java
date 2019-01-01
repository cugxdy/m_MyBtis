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
package org.apache.ibatis.scripting.xmltags;

import java.util.Map;

import org.apache.ibatis.parsing.GenericTokenParser;
import org.apache.ibatis.parsing.TokenHandler;
import org.apache.ibatis.session.Configuration;

/**
 * @author Clinton Begin
 */
public class ForEachSqlNode implements SqlNode {
  public static final String ITEM_PREFIX = "__frch_";

  // 用来判断循环的终止条件，ForEachSqlNode构造函数会创建该对象
  private final ExpressionEvaluator evaluator;
  
  private final String collectionExpression; // 迭代的集合表达式
  
  private final SqlNode contents; // 记录了<ForEachSqlNode>节点的子节点
  
  private final String open; // 在循环开始前添加的字符串
  
  private final String close;// 在循环结束后添加的字符串
  
  private final String separator; // 循环过程中，每项之间的分隔符
  
  // index是当前迭代的次数，item的值是本次迭代的元素，若迭代集合为Map，则index是键，item为值
  private final String item;
  
  private final String index;
  
  private final Configuration configuration; // 配置对象

  public ForEachSqlNode(Configuration configuration, SqlNode contents, String collectionExpression, String index, String item, String open, String close, String separator) {
    this.evaluator = new ExpressionEvaluator();
    this.collectionExpression = collectionExpression;
    this.contents = contents;
    this.open = open;
    this.close = close;
    this.separator = separator;
    this.index = index;
    this.item = item;
    this.configuration = configuration;
  }

  @Override
  public boolean apply(DynamicContext context) {
/*   <foreach collection="array" index="index" item="item" open="(" separator="," close=")">
	     #{item}
	 </foreach>*/
	// ({__frch_item_0},{__frch_item_1})  
    Map<String, Object> bindings = context.getBindings();
    
    // 步骤一:解析集合表达式对应的实际参数
    final Iterable<?> iterable = evaluator.evaluateIterable(collectionExpression, bindings);
    if (!iterable.iterator().hasNext()) { // 没有下一个元素
      return true;
    }
    // 检测集合长度
    boolean first = true;
    
    // 步骤二:在循环开始之前，调用DynamicContext.appendSql()方法添加open指定的字符串
    applyOpen(context);
    
    int i = 0;
    for (Object o : iterable) {
      DynamicContext oldContext = context; // 记录当前DynamicContext对象
      
      // 步骤三:创建PrefixedContext，并让context指向该PrefixedContext对象
      if (first || separator == null) {
    	// 如果是集合的第一项或是未指定分隔符,则PrefixedContext.prefix初始化为空字符串
        context = new PrefixedContext(context, "");
      } else {
    	// 如果指定了分隔符，则PrefixedContext.prefix初始化为指定分隔符
        context = new PrefixedContext(context, separator);
      }
      // uniqueNumber从0开始，每次递增1，用于转换生成新的"#{}"占位符名称
      int uniqueNumber = context.getUniqueNumber();   // 递增计数
      // Issue #709 
      if (o instanceof Map.Entry) {
        @SuppressWarnings("unchecked") 
        // 如果集合是Map类型，将集合中key和value添加到DynamicContext.bindings集合中保存
        Map.Entry<Object, Object> mapEntry = (Map.Entry<Object, Object>) o;
        applyIndex(context, mapEntry.getKey(), uniqueNumber);// 步骤四
        applyItem(context, mapEntry.getValue(), uniqueNumber);// 步骤五
      } else {
    	// 将集合中索引和元素添加到DynamicContext.bindings集合中保存
        applyIndex(context, i, uniqueNumber);// 步骤四   // i 为当前索引
        applyItem(context, o, uniqueNumber);// 步骤五    // o 为当前迭代项
      }
      // 步骤六:调用子节点的apply()方法进行处理，注意，这里使用的FilteredDynamicContext对象
      // 转换子节点中的"#{}"占位符
      contents.apply(new FilteredDynamicContext(configuration, context, index, item, uniqueNumber));
      if (first) {
        first = !((PrefixedContext) context).isPrefixApplied();
      }
      context = oldContext; // 还原成原来的context
      i++;
    }
    // 步骤七:循环结束后，调用DynamicContext.appendSql()方法添加close指定的字符串
    applyClose(context);
    context.getBindings().remove(item);
    context.getBindings().remove(index);
    return true;
  }
  // 注意applyIndex方法和applyItem方法的第三个参数(i)，该值由DynamicContext产生
  // 且在每个DynamicContext对象的生命周期是单调递增的
  private void applyIndex(DynamicContext context, Object o, int i) {
    if (index != null) {
      context.bind(index, o); // key为index,value为集合元素
      context.bind(itemizeItem(index, i), o); // 为index添加前缀和后缀形式新的key
    }
  }

  private void applyItem(DynamicContext context, Object o, int i) {
    if (item != null) {
      context.bind(item, o); // key为index，value为集合项
      context.bind(itemizeItem(item, i), o);// 为item添加前缀和后缀形成新的key
    }
  }

  // 解析集合表达式，获取对应的实际参数
  // 在循环开始之前，添加open字段指定的字符串，具体方法的applyOpen()代码如下
  private void applyOpen(DynamicContext context) {
    if (open != null) {
      context.appendSql(open);
    }
  }

  private void applyClose(DynamicContext context) {
    if (close != null) {
      context.appendSql(close); // 添加SQL指定的字符串
    }
  }

  private static String itemizeItem(String item, int i) {
	// 添加"__frch_"前缀和"i"后缀
    return new StringBuilder(ITEM_PREFIX).append(item).append("_").append(i).toString();
  }

  private static class FilteredDynamicContext extends DynamicContext {
    private final DynamicContext delegate;  // DynamicContext对象
    
    // 对应集合项在集合中的位置
    private final int index;
    
    // 对应集合项的index
    private final String itemIndex;
    
    // 对应集合项的item
    private final String item;

    public FilteredDynamicContext(Configuration configuration,DynamicContext delegate, String itemIndex, String item, int i) {
      super(configuration, null);
      this.delegate = delegate;
      this.index = i;
      this.itemIndex = itemIndex;
      this.item = item;
    }

    @Override
    public Map<String, Object> getBindings() {
      return delegate.getBindings();
    }

    @Override
    public void bind(String name, Object value) {
      delegate.bind(name, value);
    }

    @Override
    public String getSql() {
      return delegate.getSql();
    }

    @Override
    public void appendSql(String sql) {
      // 创建GenericTokenParser解析器，注意这里匿名实现的TokenHandler对象
      GenericTokenParser parser = new GenericTokenParser("#{", "}", new TokenHandler() {
        @Override
        public String handleToken(String content) {
          // 对item处理
          String newContent = content.replaceFirst("^\\s*" + item + "(?![^.,:\\s])", itemizeItem(item, index));
          if (itemIndex != null && newContent.equals(content)) {
        	// 对itemIndex进行处理
            newContent = content.replaceFirst("^\\s*" + itemIndex + "(?![^.,:\\s])", itemizeItem(itemIndex, index));
          }
          return new StringBuilder("#{").append(newContent).append("}").toString();
        }
      });
      // 将解析后的SQL语句片段追加到delegate保存
      delegate.appendSql(parser.parse(sql));
    }

    @Override
    public int getUniqueNumber() {
      return delegate.getUniqueNumber();
    }

  }


  private class PrefixedContext extends DynamicContext {
	  
    private final DynamicContext delegate; // 底层封装的DynamicContext对象
    
    private final String prefix;// 指定的前缀(  分隔符    )
    
    private boolean prefixApplied;// 是否已经处理过前缀

    public PrefixedContext(DynamicContext delegate, String prefix) {
      super(configuration, null);
      this.delegate = delegate;
      this.prefix = prefix;
      this.prefixApplied = false;
    }

    public boolean isPrefixApplied() {
      return prefixApplied;
    }

    @Override
    public Map<String, Object> getBindings() {
      return delegate.getBindings();
    }

    @Override
    public void bind(String name, Object value) {
      delegate.bind(name, value);
    }

    @Override
    public void appendSql(String sql) {
      // 判断是否需要追加前缀
      if (!prefixApplied && sql != null && sql.trim().length() > 0) { 
        delegate.appendSql(prefix); // 追加前缀
        prefixApplied = true;// 表示已经处理过前缀
      }
      delegate.appendSql(sql); // 追加sql片段
    }

    @Override
    public String getSql() {
      return delegate.getSql();
    }

    @Override
    public int getUniqueNumber() {
      return delegate.getUniqueNumber();
    }
  }

}
