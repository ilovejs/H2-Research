/*
 * Copyright 2004-2013 H2 Group. Multiple-Licensed under the H2 License,
 * Version 1.0, and under the Eclipse Public License, Version 1.0
 * (http://h2database.com/html/license.html).
 * Initial Developer: H2 Group
 */
package org.h2.index;

import org.h2.constant.ErrorCode;
import org.h2.constant.SysProperties;
import org.h2.engine.Constants;
import org.h2.engine.Session;
import org.h2.message.DbException;
import org.h2.result.Row;
import org.h2.result.SearchRow;
import org.h2.result.SortOrder;
import org.h2.store.Data;
import org.h2.store.Page;
import org.h2.store.PageStore;
import org.h2.table.Column;
import org.h2.table.IndexColumn;
import org.h2.table.RegularTable;
import org.h2.util.MathUtils;
import org.h2.value.Value;
import org.h2.value.ValueNull;

/**
 * This is the most common type of index, a b tree index.
 * Only the data of the indexed columns are stored in the index.
 */
public class PageBtreeIndex extends PageIndex {

    private static int memoryChangeRequired;

    private final PageStore store;
    private final RegularTable tableData;
    private final boolean needRebuild;
    private long rowCount;
    private int memoryPerPage;
    private int memoryCount;

    //PageDataIndex的id就是表的id，其他索引如PageBtreeIndex的id是自动分配的并不是表的id
    public PageBtreeIndex(RegularTable table, int id, String indexName, IndexColumn[] columns,
            IndexType indexType, boolean create, Session session) {
        initBaseIndex(table, id, indexName, columns, indexType);
        if (!database.isStarting() && create) {
            checkIndexColumnTypes(columns); //索引字段不能是 BLOB or CLOB类型
        }
        // int test;
        // trace.setLevel(TraceSystem.DEBUG);
        tableData = table;
        //内存数据库显然不能建立Btree索引，因为Btree索引要存硬盘
        if (!database.isPersistent() || id < 0) {
            throw DbException.throwInternalError("" + indexName);
        }
        this.store = database.getPageStore();
        store.addIndex(this); //加到PageStore的metaObjects
        if (create) {
            // new index
            rootPageId = store.allocatePage();
            // TODO currently the head position is stored in the log
            // it should not for new tables, otherwise redo of other operations
            // must ensure this page is not used for other things
            store.addMeta(this, session); //把此索引的元数据放到metaIndex中，metaIndex是一个PageDataIndex
            PageBtreeLeaf root = PageBtreeLeaf.create(this, rootPageId, PageBtree.ROOT);
            store.logUndo(root, null);
            store.update(root); //放到缓存中
        } else {
            rootPageId = store.getRootPageId(id);
            PageBtree root = getPage(rootPageId);
            rowCount = root.getRowCount();
        }
        this.needRebuild = create || (rowCount == 0 && store.isRecoveryRunning());
        if (trace.isDebugEnabled()) {
            trace.debug("opened {0} rows: {1}", getName() , rowCount);
        }
        memoryPerPage = (Constants.MEMORY_PAGE_BTREE + store.getPageSize()) >> 2;
        //System.out.println(getPlanSQL());
        //System.out.println(getCreateSQL());
    }

    private static void checkIndexColumnTypes(IndexColumn[] columns) {
        for (IndexColumn c : columns) {
            int type = c.column.getType();
            if (type == Value.CLOB || type == Value.BLOB) {
                throw DbException.get(ErrorCode.FEATURE_NOT_SUPPORTED_1, "Index on BLOB or CLOB column: " + c.column.getCreateSQL());
            }
        }
    }

    public void add(Session session, Row row) { //row是完整的记录
        if (trace.isDebugEnabled()) {
            trace.debug("{0} add {1}", getName(), row);
        }
        // safe memory
        SearchRow newRow = getSearchRow(row); //按索引字段构建SearchRow，只取索引字段的值
        try {
            addRow(newRow);
        } finally {
            store.incrementChangeCount();
        }
    }

    private void addRow(SearchRow newRow) {
        while (true) {
            PageBtree root = getPage(rootPageId);
            int splitPoint = root.addRowTry(newRow);
            if (splitPoint == -1) {
                break;
            }
           
//            System.out.println("-----------切割前----------");
//            System.out.println(root);
//            
            //下面的代码是处理切割的情况
            //一开始因为root是一个PageBtreeLeaf，所以是对PageBtreeLeaf的切割
            //从第二次开始，都是对PageBtreeNode的切割
            //对于PageBtreeLeaf，如果索引是升序的，那么切割后的两个节点，小于等于切割点的在左边结点，大于切割点的在右边结点，
            //如果索引是降序的，那么切割后的两个节点，大于等于切割点的在左边结点，小于切割点的在右边结点，
            if (trace.isDebugEnabled()) {
                trace.debug("split {0}", splitPoint);
            }
            SearchRow pivot = root.getRow(splitPoint - 1);
            store.logUndo(root, root.data);
            PageBtree page1 = root;
            PageBtree page2 = root.split(splitPoint);
            store.logUndo(page2, null);
            int id = store.allocatePage();
            page1.setPageId(id); //左结点要改变pageId，右结点在root.split(splitPoint)内部已经有新pageId了。
            
            //在这里进行切割操作的因为是顶层结点，所以左右子结点的parentPageId必然是rootPageId, 这两个左右子结点在B-tree的第二层，
            //当顶层结点继续切割时，那么这两些的两个左右子结点会继续往下沉，所以他们的parentPageId就不是rootPageId了，
            //而是新的在B-tree的第二层的结点，所以在root.split(splitPoint)内部会重新remapChildren右节点中对应的原始子节点的
            //而 page1.setPageId(id)会重新remapChildren左节点中对应的原始子节点
            page1.setParentPageId(rootPageId);
            page2.setParentPageId(rootPageId);
            PageBtreeNode newRoot = PageBtreeNode.create(this, rootPageId, PageBtree.ROOT);
            store.logUndo(newRoot, null);
            newRoot.init(page1, pivot, page2);
            store.update(page1);
            store.update(page2);
			store.update(newRoot);
			root = newRoot; //这行代码没用

			//			System.out.println("-----------按" + pivot + "切割----------");
			//			System.out.println("-----------Root切割成两个子页面----------");
			//			System.out.println(page1);
			//			System.out.println(page2);
			//
			//			System.out.println("-----------切割后----------");
			//			System.out.println(root);
		}
		invalidateRowCount();
		rowCount++;

		//		System.out.println();
		//		System.out.println(getPage(rootPageId));
		//		System.out.println("---------------------");
    }

    /**
     * Create a search row for this row.
     *
     * @param row the row
     * @return the search row
     */
    private SearchRow getSearchRow(Row row) {
        SearchRow r = table.getTemplateSimpleRow(columns.length == 1);
        r.setKeyAndVersion(row);
        for (Column c : columns) {
            int idx = c.getColumnId();
            r.setValue(idx, row.getValue(idx));
        }
        return r;
    }

    /**
     * Read the given page.
     *
     * @param id the page id
     * @return the page
     */
    PageBtree getPage(int id) {
        Page p = store.getPage(id);
        if (p == null) {
            PageBtreeLeaf empty = PageBtreeLeaf.create(this, id, PageBtree.ROOT);
            // could have been created before, but never committed
            store.logUndo(empty, null);
            store.update(empty);
            return empty;
        } else if (!(p instanceof PageBtree)) {
            throw DbException.get(ErrorCode.FILE_CORRUPTED_1, "" + p);
        }
        return (PageBtree) p;
    }

    public boolean canGetFirstOrLast() {
        return true;
    }

    public Cursor findNext(Session session, SearchRow first, SearchRow last) {
        return find(session, first, true, last);
    }

    public Cursor find(Session session, SearchRow first, SearchRow last) {
        return find(session, first, false, last);
    }

    private Cursor find(Session session, SearchRow first, boolean bigger, SearchRow last) {
        if (SysProperties.CHECK && store == null) {
            throw DbException.get(ErrorCode.OBJECT_CLOSED);
        }
        PageBtree root = getPage(rootPageId);
        //System.out.println(root);
        //System.out.println("---------------------");
        PageBtreeCursor cursor = new PageBtreeCursor(session, this, last);
        root.find(cursor, first, bigger);
        return cursor;
    }

    public Cursor findFirstOrLast(Session session, boolean first) {
        if (first) {
            // TODO optimization: this loops through NULL elements
            Cursor cursor = find(session, null, false, null);
            while (cursor.next()) {
                SearchRow row = cursor.getSearchRow();
                Value v = row.getValue(columnIds[0]);
                if (v != ValueNull.INSTANCE) {
                    return cursor;
                }
            }
            return cursor;
        }
        PageBtree root = getPage(rootPageId);
        PageBtreeCursor cursor = new PageBtreeCursor(session, this, null);
        root.last(cursor);
        cursor.previous();
        // TODO optimization: this loops through NULL elements
        do {
            SearchRow row = cursor.getSearchRow();
            if (row == null) {
                break;
            }
            Value v = row.getValue(columnIds[0]);
            if (v != ValueNull.INSTANCE) {
                return cursor;
            }
        } while (cursor.previous());
        return cursor;
    }

    public double getCost(Session session, int[] masks, SortOrder sortOrder) {
        return 10 * getCostRangeIndex(masks, tableData.getRowCount(session), sortOrder);
    }

    public boolean needRebuild() {
        return needRebuild;
    }

    public void remove(Session session, Row row) {
        if (trace.isDebugEnabled()) {
            trace.debug("{0} remove {1}", getName(), row);
        }
        // TODO invalidate row count
        // setChanged(session);
        if (rowCount == 1) {
            removeAllRows();
        } else {
            try {
                PageBtree root = getPage(rootPageId);
                root.remove(row);
                invalidateRowCount();
                rowCount--;
            } finally {
                store.incrementChangeCount();
            }
        }
    }

    public void remove(Session session) { //删掉索引时会调用(删表时也会触发删索引)
        if (trace.isDebugEnabled()) {
            trace.debug("remove");
        }
        removeAllRows();
        store.free(rootPageId);
        store.removeMeta(this, session);
    }

    public void truncate(Session session) { //TRUNCATE表时触发
        if (trace.isDebugEnabled()) {
            trace.debug("truncate");
        }
        removeAllRows();
        if (tableData.getContainsLargeObject()) {
            database.getLobStorage().removeAllForTable(table.getId());
        }
        tableData.setRowCount(0);
    }

    private void removeAllRows() {
        try {
            PageBtree root = getPage(rootPageId);
            root.freeRecursive(); //在存储中删除所有page
            root = PageBtreeLeaf.create(this, rootPageId, PageBtree.ROOT);
            store.removeRecord(rootPageId); //在缓存中清除rootPageId对应的page
            store.update(root);
            rowCount = 0;
        } finally {
            store.incrementChangeCount();
        }
    }

    public void checkRename() {
        // ok
    }

    /**
     * Get a row from the main index.
     *
     * @param session the session
     * @param key the row key
     * @return the row
     */
    public Row getRow(Session session, long key) {
        return tableData.getRow(session, key);
    }

    PageStore getPageStore() {
        return store;
    }

    public long getRowCountApproximation() {
        return tableData.getRowCountApproximation();
    }

    public long getDiskSpaceUsed() {
        return tableData.getDiskSpaceUsed();
    }

    public long getRowCount(Session session) {
        return rowCount;
    }

    public void close(Session session) {
        if (trace.isDebugEnabled()) {
            trace.debug("close");
        }
        // can not close the index because it might get used afterwards,
        // for example after running recovery
        try {
            writeRowCount();
        } finally {
            store.incrementChangeCount();
        }
    }

    /**
     * Read a row from the data page at the given offset.
     *
     * @param data the data
     * @param offset the offset
     * @param onlyPosition whether only the position of the row is stored
     * @param needData whether the row data is required
     * @return the row
     */
    SearchRow readRow(Data data, int offset, boolean onlyPosition, boolean needData) {
        synchronized (data) {
            data.setPos(offset);
            long key = data.readVarLong();
            if (onlyPosition) {
                if (needData) {
                    return tableData.getRow(null, key);
                }
                SearchRow row = table.getTemplateSimpleRow(true);
                row.setKey(key);
                return row;
            }
            SearchRow row = table.getTemplateSimpleRow(columns.length == 1);
            row.setKey(key);
            for (Column col : columns) {
                int idx = col.getColumnId();
                row.setValue(idx, data.readValue());
            }
            return row;
        }
    }

    /**
     * Get the complete row from the data index.
     *
     * @param key the key
     * @return the row
     */
    SearchRow readRow(long key) {
        return tableData.getRow(null, key);
    }

    /**
     * Write a row to the data page at the given offset.
     *
     * @param data the data
     * @param offset the offset
     * @param onlyPosition whether only the position of the row is stored
     * @param row the row to write
     */
    void writeRow(Data data, int offset, SearchRow row, boolean onlyPosition) {
        data.setPos(offset);
        data.writeVarLong(row.getKey());
        if (!onlyPosition) {
            for (Column col : columns) {
                int idx = col.getColumnId();
                data.writeValue(row.getValue(idx));
            }
        }
    }

    /**
     * Get the size of a row (only the part that is stored in the index).
     *
     * @param dummy a dummy data page to calculate the size
     * @param row the row
     * @param onlyPosition whether only the position of the row is stored
     * @return the number of bytes
     */
    int getRowSize(Data dummy, SearchRow row, boolean onlyPosition) {
        int rowsize = Data.getVarLongLen(row.getKey());
        if (!onlyPosition) {
            for (Column col : columns) {
                Value v = row.getValue(col.getColumnId());
                rowsize += dummy.getValueLen(v);
            }
        }
        return rowsize;
    }

    public boolean canFindNext() {
        return true;
    }

    /**
     * The root page has changed.
     *
     * @param session the session
     * @param newPos the new position
     */
    void setRootPageId(Session session, int newPos) {
        store.removeMeta(this, session);
        this.rootPageId = newPos;
        store.addMeta(this, session);
        store.addIndex(this);
    }

    private void invalidateRowCount() {
        PageBtree root = getPage(rootPageId);
        root.setRowCountStored(PageData.UNKNOWN_ROWCOUNT);
    }

    public void writeRowCount() {
        if (SysProperties.MODIFY_ON_WRITE && rootPageId == 0) {
            // currently creating the index
            return;
        }
        PageBtree root = getPage(rootPageId);
        //PageBtreeLeaf什么都不做，PageBtreeNode在头中更新rowCountStored字段值
        root.setRowCountStored(MathUtils.convertLongToInt(rowCount));
    }

    /**
     * Check whether the given row contains data.
     *
     * @param row the row
     * @return true if it contains data
     */
    boolean hasData(SearchRow row) {
        return row.getValue(columns[0].getColumnId()) != null;
    }

    int getMemoryPerPage() {
        return memoryPerPage;
    }

    /**
     * The memory usage of a page was changed. The new value is used to adopt
     * the average estimated memory size of a page.
     *
     * @param x the new memory size
     */
    void memoryChange(int x) {
        if (memoryCount < Constants.MEMORY_FACTOR) {
            memoryPerPage += (x - memoryPerPage) / ++memoryCount;
        } else {
            memoryPerPage += (x > memoryPerPage ? 1 : -1) + ((x - memoryPerPage) / Constants.MEMORY_FACTOR);
        }
    }

    /**
     * Check if calculating the memory is required.
     *
     * @return true if it is
     */
    static boolean isMemoryChangeRequired() {
        if (memoryChangeRequired-- <= 0) {
            memoryChangeRequired = 10;
            return true;
        }
        return false;
    }

}