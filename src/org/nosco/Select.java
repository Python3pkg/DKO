package org.nosco;

import static org.nosco.Constants.DIRECTION.DESCENDING;

import java.lang.ref.WeakReference;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Queue;

import javax.sql.DataSource;

import org.nosco.Constants.DB_TYPE;
import org.nosco.Constants.DIRECTION;
import org.nosco.DBQuery.Join;
import org.nosco.Field.FK;


class Select<T extends Table> implements Iterable<T>, Iterator<T> {

	private static final int BATCH_SIZE = 2048;

	@Override
	protected void finalize() throws Throwable {
		super.finalize();
		if (rs != null && !rs.isClosed()) rs.close();
		if (ps != null && !ps.isClosed()) ps.close();
	}

	private String sql;
	private DBQuery<T> query;
	private PreparedStatement ps;
	private ResultSet rs;
	private T next;
	private Field<?>[] selectedFields;
	private Field<?>[] selectedBoundFields;
	private Constructor<T> constructor;
	private Map<Class<? extends Table>,Constructor<? extends Table>> constructors =
			new HashMap<Class<? extends Table>, Constructor<? extends Table>>();
	private Map<Class<? extends Table>,Method> fkSetMethods =
			new HashMap<Class<? extends Table>,Method>();
	private Map<FK<?>,Method> fkSetSetMethods =
			new HashMap<FK<?>,Method>();
	private Connection conn;
	private Queue<Object[]> nextRows = new LinkedList<Object[]>();
	private boolean done = false;
	Object[] lastFieldValues;
	//private boolean hideDups = true;
	private boolean shouldCloseConnection = true;
	private SqlContext context = null;

	@SuppressWarnings("unchecked")
	Select(DBQuery<T> query) {
		this.query = query;
		try {
			constructor = (Constructor<T>) query.getType().getDeclaredConstructor(
					new Field[0].getClass(), new Object[0].getClass(), Integer.TYPE, Integer.TYPE);
			constructor.setAccessible(true);

			List<TableInfo> tableInfos = query.getAllTableInfos();
			for (TableInfo tableInfo : tableInfos) {
				if (tableInfo.tableClass.getName().startsWith("org.nosco.TmpTableBuilder")) continue;
				Constructor<? extends Table> constructor = tableInfo.tableClass.getDeclaredConstructor(
						new Field[0].getClass(), new Object[0].getClass(), Integer.TYPE, Integer.TYPE);
				constructor.setAccessible(true);
				constructors.put(tableInfo.tableClass, constructor);
				try {
					Method setFKMethod  = (Method) tableInfo.tableClass.getDeclaredMethod(
							"SET_FK", Field.FK.class, Object.class);
					setFKMethod.setAccessible(true);
					fkSetMethods.put(tableInfo.tableClass, setFKMethod);
				} catch (NoSuchMethodException e) {
					/* ignore */
				}
			}
			try {
				for (Join join : query.otherJoins) {
					FK<?> fk = join.fk;
					Method setFKSetMethod  = (Method) fk.referenced.getDeclaredMethod(
							"SET_FK_SET", Field.FK.class, Query.class);
					setFKSetMethod.setAccessible(true);
					fkSetSetMethods.put(fk, setFKSetMethod);
					//System.out.println("found "+ setFKSetMethod);
				}
			} catch (NoSuchMethodException e) {
				/* ignore */
			}

//			// don't apply this technique if only one table.
//			if (tableInfos.size() == 1) hideDups = false;

		} catch (SecurityException e) {
			e.printStackTrace();
		} catch (NoSuchMethodException e) {
			e.printStackTrace();
		}
	}

	protected String getSQL() {
		return getSQL(new SqlContext(query)).a;
	}

	DBQuery<T> getUnderlyingQuery() {
		return query;
	}

	protected Tuple2<String,List<Object>> getSQL(SqlContext context) {
		selectedFields = query.getSelectFields(false);
		selectedBoundFields = query.getSelectFields(true);
		StringBuffer sb = new StringBuffer();
		sb.append("select ");
		if (query.distinct) sb.append("distinct ");
		if (context.dbType==DB_TYPE.SQLSERVER && query.top>0) {
			sb.append(" top ").append(query.top).append(" ");
		}
		if (query.globallyAppliedSelectFunction == null) {
			sb.append(Util.join(", ", selectedBoundFields));
		} else {
			String[] x = new String[selectedBoundFields.length];
			for (int i=0; i < x.length; ++i) {
				x[i] = query.globallyAppliedSelectFunction + "("+ selectedBoundFields[i] +")";
			}
			sb.append(Util.join(", ", x));
		}
		sb.append(query.getFromClause(context));
		Tuple2<String, List<Object>> ret = query.getWhereClauseAndBindings(context);
		sb.append(ret.a);

		List<DIRECTION> directions = query.getOrderByDirections();
		List<Field<?>> fields = query.getOrderByFields();
		if (!context.inInnerQuery() && directions!=null & fields!=null) {
			sb.append(" order by ");
			int x = Math.min(directions.size(), fields.size());
			String[] tmp = new String[x];
			for (int i=0; i<x; ++i) {
				DIRECTION direction = directions.get(i);
				tmp[i] = Util.derefField(fields.get(i), context) + (direction==DESCENDING ? " DESC" : "");
			}
			sb.append(Util.join(", ", tmp));
		}

		if (context.dbType!=DB_TYPE.SQLSERVER && query.top>0) {
			sb.append(" limit ").append(query.top);
		}

		if (selectedFields.length > context.maxFields) {
			Util.log(sb.toString(), null);
			throw new RuntimeException("too many fields selected: "+ selectedFields.length
					+" > "+ context.maxFields +" (note: inner queries inside a 'where x in " +
							"()' can have at most one returned column.  use onlyFields()" +
							"in the inner query)");
		}

		sql = sb.toString();
		return new Tuple2<String,List<Object>>(sb.toString(), ret.b);
	}

//	protected List<Object> getSQLBindings() {
//		return query.getSQLBindings();
//	}

	@Override
	public Iterator<T> iterator() {
		try {
			DataSource ds = query.getDataSource();
			Tuple2<Connection,Boolean> connInfo = query.getConnR(ds);
			conn = connInfo.a;
			shouldCloseConnection  = connInfo.b;
			context  = new SqlContext(query);
			Tuple2<String, List<Object>> ret = getSQL(context);
			Util.log(sql, ret.b);
			query._preExecute(context, conn);
			ps = conn.prepareStatement(ret.a);
			query.setBindings(ps, ret.b);
			ps.execute();
			rs = ps.getResultSet();
			done = false;
			//m = query.getType().getMethod("INSTANTIATE", Map.class);
		} catch (SQLException e) {
			e.printStackTrace();
		} catch (SecurityException e) {
			e.printStackTrace();
		}
		return this;
	}

	private Object[] getNextRow() throws SQLException {
		Object[] tmp = peekNextRow();
		return nextRows.poll();
	}

	private Object[] peekNextRow() throws SQLException {
		if (done) return null;
		if (nextRows.isEmpty()) readNextRows(BATCH_SIZE);
		if (nextRows.isEmpty()) return null;
		else return nextRows.peek();
	}

	private int readNextRows(final int max) throws SQLException {
		if (rs == null) return 0;
		int c = 0;
		while (c < max) {
			if (!rs.next()) {
				cleanUp();
				preFetchOtherJoins();
				return c;
			}
			++c;
			Object[] nextRow = new Object[selectedFields.length];
			for (int i=0; i<selectedFields.length; ++i) {
				nextRow[i] = Util.fixObjectType(rs, selectedFields[i].TYPE, i+1);
			}
			nextRows.add(nextRow);
		}
		preFetchOtherJoins();
		return c;
	}

	private Map<Join,Query> ttbMap = new HashMap<Join,Query>();

	private void preFetchOtherJoins() {
		for (Join join : query.otherJoins) {
			System.err.println("preFetchOtherJoins() start "+ join);
			Field[] referencedFields = join.fk.REFERENCED_FIELDS();
			Field[] referencingFields = join.fk.REFERENCING_FIELDS();
			TmpTableBuilder ttb = new TmpTableBuilder(referencedFields);
			for (Object[] row : nextRows) {
				Object[] tmpRow = new Object[referencedFields.length];
				for (int i=0; i<selectedFields.length; ++i) {
					for (int j=0; j<referencedFields.length; ++j) {
						if (referencedFields[j].sameField(selectedFields[i])) {
							tmpRow[j] = row[i];
						}
					}
				}
				ttb.addRow(tmpRow);
			}
			Table tmpTable = ttb.buildTable();
			Query tmpQ = QueryFactory.IT.getQuery(join.fk.referencing, query.getDataSource());
			tmpQ = tmpQ.cross(tmpTable);
			List<TableInfo> tmpTableInfos = ((DBQuery)tmpQ).tableInfos;
			TableInfo x = tmpTableInfos.get(tmpTableInfos.size()-1);
			x.tableName = tmpTable.TABLE_NAME();
			x.nameAutogenned = false;
			for (int i=0; i<referencingFields.length; ++i) {
				tmpQ = tmpQ.where(referencingFields[i].eq(referencedFields[i].from(tmpTable.TABLE_NAME())));
			}
			InMemoryQuery tmpQuery = new InMemoryQuery(tmpQ, true);
			ttbMap.put(join, tmpQuery);
			System.err.println("preFetchOtherJoins() end");
		}
	}

	@Override
	public boolean hasNext() {
		if (next!=null) return true;
		try {
			Object[] fieldValues = getNextRow();
			this.lastFieldValues = fieldValues;
			if (fieldValues == null) return false;
			List<TableInfo> tableInfos = query.getAllTableInfos();
			int objectSize = tableInfos.size();
			Table[] objects = new Table[objectSize];
			@SuppressWarnings("unchecked")
			LinkedHashSet<Table>[] inMemoryCacheSets = new LinkedHashSet[objectSize];
			InMemoryQuery[] inMemoryCaches = new InMemoryQuery[objectSize];
			TableInfo baseTableInfo = null;
			for (int i=0; i<objectSize; ++i) {
				TableInfo ti = tableInfos.get(i);
				if (i == 0) baseTableInfo = ti;
				if (ti.path == null) {
					if (next == null) {
						next = (T) constructor.newInstance(selectedFields, fieldValues, ti.start, ti.end);
						next.__NOSCO_SELECT = this;
					}
					objects[i] = next;
				} else {
					Table fkv = constructors.get(ti.table.getClass())
							.newInstance(selectedFields, fieldValues, ti.start, ti.end);
					fkv.__NOSCO_SELECT = this;
					objects[i] = fkv;
				}
				//System.err.println(i+ "/"+ objectSize+":"+ objects[i]);
			}
			for (int i=0; i<objectSize; ++i) {
				TableInfo ti = tableInfos.get(i);
				if (ti.join != null) {
					Object reffedObject = objects[ti.position];
					Object reffingObject = objects[ti.join.reffingTableInfo.position];
					Method method = fkSetMethods.get(reffingObject.getClass());
					//System.err.println(reffingObject +" - "+ ti.join.fk +" - "+ reffedObject);
					method.invoke(reffingObject, ti.join.fk, reffedObject);
				}
			}
			for(Join join : query.otherJoins) {
				Object reffedObject = objects[join.reffedTableInfo.position];
				Method fkSetSetMethod = fkSetSetMethods.get(join.fk);
				Query tmpQuery = ttbMap.get(join);
				fkSetSetMethod.invoke(reffedObject, join.fk, tmpQuery);
			}

//			boolean sameObject = hideDups ;
//			while (sameObject) {
//				Object[] peekRow = peekNextRow();
//				if (peekRow == null) break;
//				for (int i=baseTableInfo.start; i < baseTableInfo.end; ++i) {
//					sameObject &= fieldValues[i]==null ?
//							peekRow[i]==null : fieldValues[i].equals(peekRow[i]);
//				}
//				if (sameObject) {
//
//					Table[] peekObjects = new Table[objectSize];
//
//					for (int i=0; i<objectSize; ++i) {
//						TableInfo ti = tableInfos.get(i);
//						if (i == 0) baseTableInfo = ti;
//						if (ti.path == null) {
//							peekObjects[i] = next;
//						} else {
//							Table fkv = constructors.get(ti.table.getClass())
//									.newInstance(selectedFields, peekRow, ti.start, ti.end);
//							peekObjects[i] = fkv;
//						}
//						if (objects[i]==null ? peekObjects[i]!=null : !objects[i].equals(peekObjects[i])) {
//							Set<Table> cache = inMemoryCacheSets[i];
//							if (cache!=null) cache.add(peekObjects[i]);
//						}
//					}
//
//					for (int i=0; i<objectSize; ++i) {
//						TableInfo ti = tableInfos.get(i);
//						for (int j=i+1; j<objectSize; ++j) {
//							TableInfo tj = tableInfos.get(j);
//							if (tj.path == null) continue;
//							if(Select.startsWith(tj.path, ti.path)) {
//								FK fk = tj.path[tj.path.length-1];
//								if (fk.referencing.equals(peekObjects[i].getClass()) &&
//										fk.referenced.equals(peekObjects[j].getClass())) {
//									Method method = fkSetMethods.get(peekObjects[i].getClass());
//									method.invoke(peekObjects[i], fk, peekObjects[j]);
//								}
//							}
//						}
//					}
//
//					peekRow = getNextRow();
//				}
//			}
//
//			for (int i=0; i<objectSize; ++i) {
//				if (inMemoryCaches[i]==null || inMemoryCacheSets[i]==null) continue;
//				inMemoryCaches[i].cache = new ArrayList<Table>(inMemoryCacheSets[i]);
//			}

		} catch (SQLException e) {
			e.printStackTrace();
			throw new RuntimeException(e);
		} catch (IllegalArgumentException e) {
			e.printStackTrace();
			throw new RuntimeException(e);
		} catch (IllegalAccessException e) {
			e.printStackTrace();
			throw new RuntimeException(e);
		} catch (InvocationTargetException e) {
			e.printStackTrace();
			throw new RuntimeException(e);
		} catch (InstantiationException e) {
			e.printStackTrace();
			throw new RuntimeException(e);
		}
		boolean hasNext = next != null;
		//if (!hasNext) cleanUp();
		return hasNext;
	}

	private String key4IMQ(FK<?>[] path) {
		StringBuffer sb = new StringBuffer();
		for (FK<?> fk : path) {
			sb.append(fk);
		}
		return sb.toString();
	}

	private void cleanUp() {
		if (done) return;
		try {
			query._postExecute(context, conn);
		} catch (SQLException e1) {
			e1.printStackTrace();
		}
		if (shouldCloseConnection) {
			try {
				conn.close();
			} catch (SQLException e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
			}
		}
		this.done = true;
	}

	@Override
	public T next() {
		T t = next;
		next = null;
		return t;
	}

	@Override
	public void remove() {
		// TODO Auto-generated method stub

	}

	static boolean startsWith(FK<?>[] path, FK<?>[] path2) {
		if (path2 == null) return true;
		for (int i=0; i<path2.length; ++i) {
			if (path[i] != path2[i]) return false;
		}
		return true;
	}

	@SuppressWarnings("rawtypes")
	private Map<Class<? extends Table>,Map<Condition,WeakReference<Query<? extends Table>>>> subQueryCache = new HashMap<Class<? extends Table>,Map<Condition,WeakReference<Query<? extends Table>>>>();

	@SuppressWarnings("unchecked")
	<S extends Table> Query<S> getSelectCachedQuery(Class<S> cls, Condition c) {
		Map<Condition, WeakReference<Query<? extends Table>>> x = subQueryCache.get(cls);
		if (x == null) {
			x = new HashMap<Condition,WeakReference<Query<? extends Table>>>();
			subQueryCache.put(cls, x);
		}
		WeakReference<Query<? extends Table>> wr = x.get(c);
		Query<? extends Table> q = wr == null ? null : wr.get();
		if (q == null) {
			q = new InMemoryQuery<S>(QueryFactory.IT.getQuery(cls).use(this.query.getDataSource()).where(c));
			x.put(c, new WeakReference<Query<? extends Table>>(q));
		}
		return (Query<S>) q;
	}

}
