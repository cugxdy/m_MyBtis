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
package org.apache.ibatis.cursor.defaults;

import org.apache.ibatis.cursor.Cursor;
import org.apache.ibatis.executor.resultset.DefaultResultSetHandler;
import org.apache.ibatis.executor.resultset.ResultSetWrapper;
import org.apache.ibatis.mapping.ResultMap;
import org.apache.ibatis.session.ResultContext;
import org.apache.ibatis.session.ResultHandler;
import org.apache.ibatis.session.RowBounds;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Iterator;
import java.util.NoSuchElementException;

/**
 * This is the default implementation of a MyBatis Cursor.
 * This implementation is not thread safe.
 *
 * @author Guillaume Darmont / guillaume@dropinocean.com
 */
public class DefaultCursor<T> implements Cursor<T> {

    // ResultSetHandler stuff
	// 用于完成映射的DefaultResultSetHandler对象
    private final DefaultResultSetHandler resultSetHandler;
    
    private final ResultMap resultMap; // 映射使用的ResultMap对象
    
    private final ResultSetWrapper rsw;// 其中封装了结果集的相关元信息
    
    private final RowBounds rowBounds; // 指定了对结果集进行映射的起始位置
    
    // ObjectWrapperResultHandler继承了ResultHandler接口，用于暂存映射的结果对象
    private final ObjectWrapperResultHandler<T> objectWrapperResultHandler = new ObjectWrapperResultHandler<T>();

    // 通过该跌倒器获取映射得到的结果对象
    private final CursorIterator cursorIterator = new CursorIterator();
    
    private boolean iteratorRetrieved;  // 标识是否正在迭代结果集

    private CursorStatus status = CursorStatus.CREATED;
    private int indexWithRowBound = -1;// 记录已经完成映射的行数

    private enum CursorStatus {

        /**
         * A freshly created cursor, database ResultSet consuming has not started
         */
        CREATED,
        /**
         * A cursor currently in use, database ResultSet consuming has started
         */
        OPEN,
        /**
         * A closed cursor, not fully consumed
         */
        CLOSED,
        /**
         * A fully consumed cursor, a consumed cursor is always closed
         */
        CONSUMED
    }

    public DefaultCursor(DefaultResultSetHandler resultSetHandler, ResultMap resultMap, ResultSetWrapper rsw, RowBounds rowBounds) {
        this.resultSetHandler = resultSetHandler;
        this.resultMap = resultMap;
        this.rsw = rsw;
        this.rowBounds = rowBounds;
    }

    @Override
    public boolean isOpen() {
        return status == CursorStatus.OPEN;
    }

    @Override
    public boolean isConsumed() {
        return status == CursorStatus.CONSUMED;
    }

    @Override
    public int getCurrentIndex() {
    	// 获取当前索引
        return rowBounds.getOffset() + cursorIterator.iteratorIndex;
    }

    @Override
    public Iterator<T> iterator() {
        if (iteratorRetrieved) { 
            throw new IllegalStateException("Cannot open more than one iterator on a Cursor");
        }
        iteratorRetrieved = true;
        return cursorIterator;  // 返回CursorIterator(继承了Iterator接口)对象
    }

    @Override
    public void close() {
        if (isClosed()) { // 检测当前游标对象是否关闭
            return;
        }

        ResultSet rs = rsw.getResultSet(); // 获取结果集
        try {
            if (rs != null) {
                Statement statement = rs.getStatement();

                rs.close(); // 关闭ResultSet对象
                if (statement != null) {
                    statement.close(); // 关闭Statement对象
                }
            }
            status = CursorStatus.CLOSED; // 改变该对象的状态
        } catch (SQLException e) {
            // ignore
        }
    }

    protected T fetchNextUsingRowBound() {
    	// 映射一行数据库记录，得到记录对象
        T result = fetchNextObjectFromDatabase();
        
        // 从结果集开始一条条映射，但是将rowBounds.getOffset()之前的映射结果全部忽略
        while (result != null && indexWithRowBound < rowBounds.getOffset()) {
            result = fetchNextObjectFromDatabase();
        }
        return result;
    }

    protected T fetchNextObjectFromDatabase() {
        if (isClosed()) { // 检测当前游标对象是否关闭
            return null;
        }

        try {
            status = CursorStatus.OPEN; // 更新游标状态
            
            // 通过DefaultResultSetHandler.handleRowValues()方法完成映射
            // 这里会将映射得到的结果保存到objectWrapperResultHandler.result字段中
            resultSetHandler.handleRowValues(rsw, resultMap, objectWrapperResultHandler, RowBounds.DEFAULT, null);
            
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }

        T next = objectWrapperResultHandler.result; // 获取结果
        if (next != null) {
            indexWithRowBound++;// 统计返回的结果对象数量
        }
        // No more object or limit reached
        // 检测是否还存在需要映射的记录，如果没有，则关闭游标并修改状态
        if (next == null || getReadItemsCount() == rowBounds.getOffset() + rowBounds.getLimit()) {
            close();// 关闭结果集以及对应的StateMent对象
            status = CursorStatus.CONSUMED;
        }
        objectWrapperResultHandler.result = null;

        return next; // 返回结果对象
    }

    // 判断该游标对象是否关闭
    private boolean isClosed() {
        return status == CursorStatus.CLOSED || status == CursorStatus.CONSUMED;
    }

    // 将记录已经完成映射的行数 + 1(进行检测)
    private int getReadItemsCount() {
        return indexWithRowBound + 1;
    }

    private static class ObjectWrapperResultHandler<T> implements ResultHandler<T> {

        private T result;

        @Override
        public void handleResult(ResultContext<? extends T> context) {
            this.result = context.getResultObject();  // 获取结果对象
            context.stop(); // 记录stop = true;
        }
    }

    
    private class CursorIterator implements Iterator<T> {

        /**
         * Holder for the next object to be returned
         */
        T object;

        /**
         * Index of objects returned using next(), and as such, visible to users.
         */
        int iteratorIndex = -1;

        @Override
        public boolean hasNext() {
            if (object == null) {
            	// 检测结果集是否含有行数据
                object = fetchNextUsingRowBound();
            }
            return object != null;
        }

        @Override
        public T next() {
        	// hasNext()方法也会调用fetchNextUsingRowBound()，并将结果对象记录到object字段中
            // Fill next with object fetched from hasNext()
            T next = object;

            if (next == null) {
                next = fetchNextUsingRowBound(); // 对结果映射的核心
            }

            if (next != null) {
                object = null;
                iteratorIndex++; // 记录返回结果对象的个数
                return next;
            }
            throw new NoSuchElementException();
        }

        @Override
        public void remove() {
        	// 抛出异常
            throw new UnsupportedOperationException("Cannot remove element from Cursor");
        }
    }
}
