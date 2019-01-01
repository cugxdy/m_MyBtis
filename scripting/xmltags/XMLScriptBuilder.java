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

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.ibatis.builder.BaseBuilder;
import org.apache.ibatis.builder.BuilderException;
import org.apache.ibatis.mapping.SqlSource;
import org.apache.ibatis.parsing.XNode;
import org.apache.ibatis.scripting.defaults.RawSqlSource;
import org.apache.ibatis.session.Configuration;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

/**
 * @author Clinton Begin
 */
public class XMLScriptBuilder extends BaseBuilder {

  private final XNode context;  // 当前SQL节点

  private boolean isDynamic; // 是否动态SQL  包含 #{}、if、where、trim、foreach、set、otherwise等节点
  
  private final Class<?> parameterType;  // 解析查询节点时的parameterType对应的Class对象
  private final Map<String, NodeHandler> nodeHandlerMap = new HashMap<String, NodeHandler>();

  public XMLScriptBuilder(Configuration configuration, XNode context) {
    this(configuration, context, null);
  }

  public XMLScriptBuilder(Configuration configuration, XNode context, Class<?> parameterType) {
    super(configuration);
    this.context = context;
    this.parameterType = parameterType;
    initNodeHandlerMap();  // 初始化nodeHandlerMap集合
  }


  private void initNodeHandlerMap() {
	// 初始化nodeHandlerMap集合     该集合用来处理sql语句中包含的Mybatis标准的动态节点
    nodeHandlerMap.put("trim", new TrimHandler());
    nodeHandlerMap.put("where", new WhereHandler());
    nodeHandlerMap.put("set", new SetHandler());
    nodeHandlerMap.put("foreach", new ForEachHandler());
    nodeHandlerMap.put("if", new IfHandler());
    nodeHandlerMap.put("choose", new ChooseHandler());
    nodeHandlerMap.put("when", new IfHandler());
    nodeHandlerMap.put("otherwise", new OtherwiseHandler());
    nodeHandlerMap.put("bind", new BindHandler());
  }

  public SqlSource parseScriptNode() {
	// 首先判断当前的节点的是不是动态的SQL，动态SQL会包括占位符或是动态sql的相关节点
    MixedSqlNode rootSqlNode = parseDynamicTags(context);
    SqlSource sqlSource = null;
    if (isDynamic) { // 根据是否为动态的sql语句，创建相应的SqlSource对象
      // 动态SqlSource类型
      sqlSource = new DynamicSqlSource(configuration, rootSqlNode);
    } else {
      // 静态SqlSource类型
      sqlSource = new RawSqlSource(configuration, rootSqlNode, parameterType);
    }
    return sqlSource;
  }

  protected MixedSqlNode parseDynamicTags(XNode node) {
    List<SqlNode> contents = new ArrayList<SqlNode>(); // 用来记录生成的SqlNode集合
    NodeList children = node.getNode().getChildNodes();// 获取SelectKey的所有子节点 后面的SQL节点也会进入这里
    
    for (int i = 0; i < children.getLength(); i++) { // 获取节点的所有子节点(文本节点,元素节点,注释节点)
      // 创建XNode，该过程会将能解析掉的"${}"都解析掉
      XNode child = node.newXNode(children.item(i));
      
      // 对文本节点的处理(CDATA_SECTION_NODE    或者       TEXT_NODE)
      if (child.getNode().getNodeType() == Node.CDATA_SECTION_NODE || child.getNode().getNodeType() == Node.TEXT_NODE) {
        String data = child.getStringBody("");  // data为节点内容  文本内容
        
        TextSqlNode textSqlNode = new TextSqlNode(data);
        
        // 解析SQL语句，如果含有未解析的"${}"占位符，则为动态SQL
        if (textSqlNode.isDynamic()) {
          contents.add(textSqlNode);  // 添加到contents集合List中
          isDynamic = true; // 标志为动态SQL语句
        } else {
          contents.add(new StaticTextSqlNode(data));  // 添加到contents，但是为StaticTextSqlNode类型
        }
      } else if (child.getNode().getNodeType() == Node.ELEMENT_NODE) { // issue #628
    	// 如果子节点是一个标签，那么一定是动态SQL，并且根据不同的动态标签生成不同的
    	// NodeHandler
        String nodeName = child.getNode().getNodeName(); // 获取节点名字
        NodeHandler handler = nodeHandlerMap.get(nodeName); // 获取相应的token处理器
        if (handler == null) { // 如果handler为null，则抛出异常
          throw new BuilderException("Unknown element <" + nodeName + "> in SQL statement.");
        }
        // 处理动态SQL，并将解析得到的SqlNode对象放入contents集合中保存
        handler.handleNode(child, contents);
        isDynamic = true;
      }
    }
    return new MixedSqlNode(contents);
  }
  // nodeHandlerMap接口中handleNode处理if、foreach、where等表达式
  private interface NodeHandler {
    void handleNode(XNode nodeToHandle, List<SqlNode> targetContents);
  }

  private class BindHandler implements NodeHandler {
    public BindHandler() {
      // Prevent Synthetic Access
    }

    @Override
    public void handleNode(XNode nodeToHandle, List<SqlNode> targetContents) {
      final String name = nodeToHandle.getStringAttribute("name"); // 获取bing节点的name属性值
      final String expression = nodeToHandle.getStringAttribute("value"); // 获取bing节点的value属性值
      // 生成VarDeclSqlNode对象，其中bing节点的name与value属性值
      final VarDeclSqlNode node = new VarDeclSqlNode(name, expression);
      targetContents.add(node);
    }
  }

  private class TrimHandler implements NodeHandler {
    public TrimHandler() {
      // Prevent Synthetic Access
    }

    @Override
    public void handleNode(XNode nodeToHandle, List<SqlNode> targetContents) {
      MixedSqlNode mixedSqlNode = parseDynamicTags(nodeToHandle);
      String prefix = nodeToHandle.getStringAttribute("prefix");
      String prefixOverrides = nodeToHandle.getStringAttribute("prefixOverrides");
      String suffix = nodeToHandle.getStringAttribute("suffix");
      String suffixOverrides = nodeToHandle.getStringAttribute("suffixOverrides");
      TrimSqlNode trim = new TrimSqlNode(configuration, mixedSqlNode, prefix, prefixOverrides, suffix, suffixOverrides);
      targetContents.add(trim);
    }
  }

  private class WhereHandler implements NodeHandler {
    public WhereHandler() {
      // Prevent Synthetic Access
    }

    @Override
    public void handleNode(XNode nodeToHandle, List<SqlNode> targetContents) {
      // 调用parseDynamicTags()方法，解析<where>节点的子节点
      MixedSqlNode mixedSqlNode = parseDynamicTags(nodeToHandle);
      // 创建WhereSqlNode，并添加到targetContents集合中保存
      WhereSqlNode where = new WhereSqlNode(configuration, mixedSqlNode);
      targetContents.add(where);
    }
  }

  private class SetHandler implements NodeHandler {
    public SetHandler() {
      // Prevent Synthetic Access
    }

    @Override
    public void handleNode(XNode nodeToHandle, List<SqlNode> targetContents) {
      MixedSqlNode mixedSqlNode = parseDynamicTags(nodeToHandle);
      SetSqlNode set = new SetSqlNode(configuration, mixedSqlNode);
      targetContents.add(set);
    }
  }

  private class ForEachHandler implements NodeHandler {
    public ForEachHandler() {
      // Prevent Synthetic Access
    }

    @Override
    public void handleNode(XNode nodeToHandle, List<SqlNode> targetContents) {
      MixedSqlNode mixedSqlNode = parseDynamicTags(nodeToHandle);
      String collection = nodeToHandle.getStringAttribute("collection"); // 获取collection属性
      String item = nodeToHandle.getStringAttribute("item");// 获取item属性
      String index = nodeToHandle.getStringAttribute("index");// 获取index属性
      String open = nodeToHandle.getStringAttribute("open");// 获取open属性
      String close = nodeToHandle.getStringAttribute("close");// 获取close属性
      String separator = nodeToHandle.getStringAttribute("separator");// 获取separator属性
      // 创建ForEachSqlNode对象，其中封装了forEach节点的属性信息
      ForEachSqlNode forEachSqlNode = new ForEachSqlNode(configuration, mixedSqlNode, collection, index, item, open, close, separator);
      // 添加到List<SqlNode>集合中
      targetContents.add(forEachSqlNode);
    }
  }

  private class IfHandler implements NodeHandler {
    public IfHandler() {
      // Prevent Synthetic Access
    }

    @Override
    public void handleNode(XNode nodeToHandle, List<SqlNode> targetContents) {
      // if节点中可能嵌套了其他类型的动态Sql节点
      MixedSqlNode mixedSqlNode = parseDynamicTags(nodeToHandle);
      String test = nodeToHandle.getStringAttribute("test"); // 获取test属性值
      // 生成IfSqlNode节点
      IfSqlNode ifSqlNode = new IfSqlNode(mixedSqlNode, test);
      // 添加到targetContents中
      targetContents.add(ifSqlNode);
    }
  }

  private class OtherwiseHandler implements NodeHandler {
    public OtherwiseHandler() {
      // Prevent Synthetic Access
    }

    @Override
    public void handleNode(XNode nodeToHandle, List<SqlNode> targetContents) {
      // 解析Otherwise节点生成MixedSqlNode节点
      MixedSqlNode mixedSqlNode = parseDynamicTags(nodeToHandle);
      // 添加到targetContents中
      targetContents.add(mixedSqlNode);
    }
  }

  private class ChooseHandler implements NodeHandler {
    public ChooseHandler() {
      // Prevent Synthetic Access
    }

    @Override
    public void handleNode(XNode nodeToHandle, List<SqlNode> targetContents) {
      List<SqlNode> whenSqlNodes = new ArrayList<SqlNode>(); // 生成List<SqlNode>节点对象
      List<SqlNode> otherwiseSqlNodes = new ArrayList<SqlNode>();// 生成List<SqlNode>节点对象
      
      handleWhenOtherwiseNodes(nodeToHandle, whenSqlNodes, otherwiseSqlNodes);
      
      SqlNode defaultSqlNode = getDefaultSqlNode(otherwiseSqlNodes);
      ChooseSqlNode chooseSqlNode = new ChooseSqlNode(whenSqlNodes, defaultSqlNode);
      targetContents.add(chooseSqlNode);
    }

    private void handleWhenOtherwiseNodes(XNode chooseSqlNode, List<SqlNode> ifSqlNodes, List<SqlNode> defaultSqlNodes) {
      List<XNode> children = chooseSqlNode.getChildren(); // 获取choose的子节点
      for (XNode child : children) {
        String nodeName = child.getNode().getNodeName();
        NodeHandler handler = nodeHandlerMap.get(nodeName);
        if (handler instanceof IfHandler) { // IfHandler节点类型
          handler.handleNode(child, ifSqlNodes); // 
        } else if (handler instanceof OtherwiseHandler) { // OtherwiseHandler节点类型
          handler.handleNode(child, defaultSqlNodes);
        }
      }
    }

    private SqlNode getDefaultSqlNode(List<SqlNode> defaultSqlNodes) {
      SqlNode defaultSqlNode = null;
      if (defaultSqlNodes.size() == 1) {
        defaultSqlNode = defaultSqlNodes.get(0);
      } else if (defaultSqlNodes.size() > 1) {
        throw new BuilderException("Too many default (otherwise) elements in choose statement.");
      }
      return defaultSqlNode;
    }
  }

}
