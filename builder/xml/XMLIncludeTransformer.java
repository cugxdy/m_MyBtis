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
package org.apache.ibatis.builder.xml;

import java.util.HashMap;
import java.util.Map;
import java.util.Properties;

import org.apache.ibatis.builder.BuilderException;
import org.apache.ibatis.builder.IncompleteElementException;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.apache.ibatis.parsing.PropertyParser;
import org.apache.ibatis.parsing.XNode;
import org.apache.ibatis.session.Configuration;
import org.w3c.dom.NamedNodeMap;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

/**
 * @author Frank D. Martinez [mnesarco]
 */
public class XMLIncludeTransformer {

  private final Configuration configuration;
  private final MapperBuilderAssistant builderAssistant;

  public XMLIncludeTransformer(Configuration configuration, MapperBuilderAssistant builderAssistant) {
	  
    this.configuration = configuration; // 配置项
    this.builderAssistant = builderAssistant; // 辅助操作类
    
  }

  public void applyIncludes(Node source) {
	
	// 获取mybatis-config.xml中<properties>节点下的变量集合
    Properties variablesContext = new Properties();
    Properties configurationVariables = configuration.getVariables();
    if (configurationVariables != null) {
      variablesContext.putAll(configurationVariables); // 全部放进去
    }
    
    applyIncludes(source, variablesContext, false);// 处理<include>子节点
  }

  /**
   * Recursively apply includes through all SQL fragments.
   * @param source Include node in DOM tree
   * @param variablesContext Current context for static variables with values
   */
  // 下面是处理<include>节点的applyIncludes()方法重载
  /**
   * 
   * @param source  <include>节点
   * @param variablesContext configuration中配置key-value键值对
   * @param included
   */
  private void applyIncludes(Node source, final Properties variablesContext, boolean included) {
	  
    if (source.getNodeName().equals("include")) { // (2)处理<include>节点
    	
      // 查找refid属性指向的<SQL>节点，返回的是深克隆的Node对象
      Node toInclude = findSqlFragment(getStringAttribute(source, "refid"), variablesContext);
      
      // 解析<include>节点下的<property>节点，将得到键值对添加到variablesContext
      // 并形成新的Properties对象返回，用于替换占位符
      Properties toIncludeContext = getVariablesContext(source, variablesContext);
      
      // 递归处理<include>节点，在<sql>节点中可能会使用<include>引用了其他Sql片段
      applyIncludes(toInclude, toIncludeContext, true);
      
      // getOwnerDocument()方法:该属性将 Node 对象与创建这些对象时的上下文所属的 Document 关联起来。
      if (toInclude.getOwnerDocument() != source.getOwnerDocument()) {
        toInclude = source.getOwnerDocument().importNode(toInclude, true);
      }
      
      // Document元素的操作
      // 将<include>节点替换成<sql>节点
      // replaceChild() 方法可将某个子节点替换为另一个。即用toInclude替换source节点
      source.getParentNode().replaceChild(toInclude, source);
      while (toInclude.hasChildNodes()) { // 将<sql>节点的子节点添加到<sql>节点前面
        // parentNode.insertBefore(newNode,oldNode);
    	// 把新的节点插入到一个已知节点oldNode之前，就要先找到他们的父节点，这样才能把节点放到正确位置。
    	// getFirstChild()方法获取第一个子节点, 一般为文本节点
    	toInclude.getParentNode().insertBefore(toInclude.getFirstChild(), toInclude);
      }
      // 在删除toInclude节点，相当于完成了sql文本节点的替换
      toInclude.getParentNode().removeChild(toInclude); // 删除<sql>节点
      
    } else if (source.getNodeType() == Node.ELEMENT_NODE) { //  (1) 元素节点
      if (included && !variablesContext.isEmpty()) { // 第一次为false
        // replace variables in attribute values
        NamedNodeMap attributes = source.getAttributes(); // 遍历当前SQL语句的子节点
        for (int i = 0; i < attributes.getLength(); i++) {
          Node attr = attributes.item(i);
          attr.setNodeValue(PropertyParser.parse(attr.getNodeValue(), variablesContext));
        }
      }
      NodeList children = source.getChildNodes();
      for (int i = 0; i < children.getLength(); i++) {
        applyIncludes(children.item(i), variablesContext, included);
      }
    } else if (included && source.getNodeType() == Node.TEXT_NODE // (3) 文本节点
        && !variablesContext.isEmpty()) {
      // replace variables in text node
      // 使用之前的解析得到Properties对象替换对应的占位符
      source.setNodeValue(PropertyParser.parse(source.getNodeValue(), variablesContext));
    }
  }

  private Node findSqlFragment(String refid, Properties variables) {
	// 字符串解析操作  
    refid = PropertyParser.parse(refid, variables);  // 处理refid其中占位符
    
    // 修饰字符串[namespace.refid]
    refid = builderAssistant.applyCurrentNamespace(refid, true);
    try {
      // 获取先前解析的SQL节点，sql节点保存在configuration中
      XNode nodeToInclude = configuration.getSqlFragments().get(refid);
      return nodeToInclude.getNode().cloneNode(true);  // 深层copy (复制一个SQL节点)
    } catch (IllegalArgumentException e) {
      // 抛出异常
      throw new IncompleteElementException("Could not find SQL statement to include with refid '" + refid + "'", e);
    }
  }

  private String getStringAttribute(Node node, String name) {
	// 在node节点中返回name属性指定的值
    return node.getAttributes().getNamedItem(name).getNodeValue();
  }

  /**
   * Read placeholders and their values from include node definition. 
   * @param node Include node instance
   * @param inheritedVariablesContext Current context used for replace variables in new variables values
   * @return variables context from include instance (no inherited values)
   */
  // 获取<include>子节点的Properties值
  private Properties getVariablesContext(Node node, Properties inheritedVariablesContext) {
    
	// 创建declaredProperties变量
	Map<String, String> declaredProperties = null;
    NodeList children = node.getChildNodes(); // 获得子节点
    
    for (int i = 0; i < children.getLength(); i++) { // 遍历子节点
      Node n = children.item(i); 
      if (n.getNodeType() == Node.ELEMENT_NODE) { // 当为元素节点时
        String name = getStringAttribute(n, "name"); // 获取name属性值
        // Replace variables inside (获取节点上的value值)
        String value = PropertyParser.parse(getStringAttribute(n, "value"), inheritedVariablesContext);
        if (declaredProperties == null) {
          declaredProperties = new HashMap<String, String>();
        }
        if (declaredProperties.put(name, value) != null) {
          // 抛出异常
          throw new BuilderException("Variable " + name + " defined twice in the same include definition");
        }
      }
    }
    if (declaredProperties == null) {
      return inheritedVariablesContext;
    } else {
      Properties newProperties = new Properties();
      newProperties.putAll(inheritedVariablesContext);
      newProperties.putAll(declaredProperties);
      return newProperties;
    }
  }
}
