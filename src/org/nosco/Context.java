package org.nosco;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import javax.sql.DataSource;


/**
 * This class is used for tweaking query executions. &nbsp;
 * Multiple context levels are supported (currently: vm, thread-group and thread)
 * in increasing order of precedence. &nbsp;
 *
 * A context can start and stop transactions, set default {@code DataSource}s (optionally
 * per package or class), and change what schema is referenced.
 *
 * Each context-altering method returns an {@code Undoer} object, which lets you undo the
 * context change at a later date. (by calling {@code Undoer.undo()}) &nbsp;
 * By default all {@code Undoer}s undo themselves when they're GCed. &nbsp; But you
 * can suppress this behavior by calling {@code Undoer.setAutoUndo(false)}.
 *
 * @author Derek Anderson
 */
public class Context {


	/**
	 * @return the context for the current thread
	 */
	public static Context getThreadContext() {
		return threadContextContainer.get();
	}

	/**
	 * Threads spawned by default share their parent's thread group. &nbsp;
	 * Use this if you want child threads spawned by some process to share this context.
	 * @return the context for the current thread group
	 */
	public static Context getThreadGroupContext() {
		ThreadGroup tg = Thread.currentThread().getThreadGroup();
		Context context = threadGroupContexts.get(tg);
		if (context == null) {
			context = new Context();
			threadGroupContexts.put(tg, context);
		}
		return context;
	}

	/**
	 * @return the singleton context for this VM
	 */
	public static Context getVMContext() {
		return vmContext;
	}

	static DataSource getDataSource(final Class<? extends Table> cls) {
		Context[] contexts = {getThreadContext(), getThreadGroupContext(), getVMContext()};
		//System.err.println("woot");
		for (Context context : contexts) {
			//System.err.println("woot "+ context);
			DataSource ds = null;

			Map<UUID, DataSource> x = context.classDataSources.get(cls);
			if (x != null) {
				for (DataSource tmp : x.values()) { ds = tmp; }
				if (ds != null) return ds;
			}

			//System.err.println("woot2 "+ context);
			x = context.packageDataSources.get(cls.getPackage());
			if (x != null) {
				for (DataSource tmp : x.values()) { ds = tmp; }
				//System.err.println("woot2.1 "+ ds);
				if (ds != null) return ds;
			}

			//System.err.println("woot3 "+ context);
			for (DataSource tmp : context.defaultDataSource.values()) { ds = tmp; }
			if (ds != null) return ds;
		}
		return null;
	}

	static String getSchemaToUse(final DataSource ds, final String originalSchema) {
		Tuple2<DataSource, String> key = new Tuple2<DataSource,String>(ds, originalSchema);
		String schema = null;
		Context[] contexts = {getThreadContext(), getThreadGroupContext(), getVMContext()};
		for (Context context : contexts) {
			Map<UUID, String> x = context.schemaOverrides.get(key);
			if (x == null) continue;
			for (String tmp : x.values()) { schema = tmp; }
			if (schema != null) return schema;
		}
		return originalSchema;
	}

	/**
	 * Returns true if currently inside a transaction.
	 * @param ds
	 * @return
	 */
	public static boolean inTransaction(DataSource ds) {
		boolean isInTransaction = getThreadContext().transactionConnections.containsKey(ds);
		if (isInTransaction) return true;
		isInTransaction = getThreadGroupContext().transactionConnections.containsKey(ds);
		if (isInTransaction) return true;
		return getVMContext().transactionConnections.containsKey(ds);
	}

	/**
	 * Gets the connection for this transaction.
	 * @param ds
	 * @return null is not currently in a transaction
	 */
	public static Connection getConnection(DataSource ds) {
		Connection c = getThreadContext().transactionConnections.get(ds);
		if (c == null) c = getThreadGroupContext().transactionConnections.get(ds);
		if (c == null) c = getVMContext().transactionConnections.get(ds);
		return c;
	}

	/**
	 * Starts a new transaction.
	 * @param ds
	 * @return
	 * @throws SQLException
	 */
	public boolean startTransaction(DataSource ds) throws SQLException {
		Connection c = transactionConnections.get(ds);
		if (c != null) return false;
		c = ds.getConnection();
		c.setAutoCommit(false);
		transactionConnections.put(ds, c);
		return true;
	}

	/**
	 * Commits the current transaction.
	 * @param ds
	 * @return
	 * @throws SQLException
	 */
	public boolean commitTransaction(DataSource ds) throws SQLException {
		Connection c = transactionConnections.get(ds);
		if (c == null) return false;
		c.commit();
		c.close();
		return true;
	}

	/**
	 * Rolls back the current transaction. &nbsp;
	 * This method hides the {@code SQLException} thrown by the rollback.
	 * @param ds
	 * @return
	 */
	public boolean rollbackTransaction(DataSource ds) {
		Connection c = transactionConnections.get(ds);
		transactionConnections.remove(ds);
		if (c == null) return false;
		try {
			c.rollback();
		} catch (SQLException e) {
			e.printStackTrace();
			try {
				c.close();
			} catch (SQLException e2) {
				e2.printStackTrace();
			}
			return false;
		}
		try {
			c.close();
		} catch (SQLException e) {
			e.printStackTrace();
		}
		return true;
	}

	/**
	 * Rolls back the current transaction.
	 * @param ds
	 * @return
	 * @throws SQLException
	 */
	public boolean rollbackTransactionThrowSQLException(DataSource ds) throws SQLException {
		Connection c = transactionConnections.get(ds);
		transactionConnections.remove(ds);
		if (c == null) return false;
		try {
			c.rollback();
		} catch (SQLException e) {
			throw e;
		} finally {
			c.close();
		}
		return true;
	}


	/**
	 * All generated classes have their schema embedded in them, and always specify their
	 * schema when performing a query. &nbsp; You can change what schema they reference by
	 * overriding it here.  (per {@code DataSource})
	 * @param ds
	 * @param originalSchema
	 * @param newSchema
	 * @return
	 */
	public Undoer overrideSchema(DataSource ds, String originalSchema, String newSchema) {
		Tuple2<DataSource, String> key = new Tuple2<DataSource,String>(ds, originalSchema);
		Map<UUID, String> map = schemaOverrides.get(key);
		if (map == null) {
			map = Collections.synchronizedMap(new LinkedHashMap<UUID, String>());
			schemaOverrides.put(key, map);
		}
		final UUID uuid = UUID.randomUUID();
		map.put(uuid, newSchema);
		final Map<UUID, String> map2 = map;
		return new Undoer() {
			@Override
			public void undo() {
				map2.remove(uuid);
			}
		};
	}

	/**
	 * Sets a default {@code DataSource} for this context. &nbsp; This will be overridden
	 * by future calls to this method, or any matching calls
	 * to the package or class versions of this method.
	 * @param ds
	 * @return
	 */
	public Undoer setDataSource(DataSource ds) {
		final UUID uuid = UUID.randomUUID();
		defaultDataSource.put(uuid, ds);
		return new Undoer() {
			@Override
			public void undo() {
				defaultDataSource.remove(uuid);
			}
		};
	}

	/**
	 * Sets a default {@code DataSource} for all classes in this package in this context. &nbsp;
	 * This will be overridden
	 * by future calls to this method, or any matching calls
	 * to the class version of this method.
	 * @param pkg
	 * @param ds
	 * @return
	 */
	public Undoer setDataSource(Package pkg, DataSource ds) {
		Map<UUID, DataSource> map = packageDataSources.get(pkg);
		if (map == null) {
			map = Collections.synchronizedMap(new LinkedHashMap<UUID, DataSource>());
			packageDataSources.put(pkg, map);
		}
		final UUID uuid = UUID.randomUUID();
		map.put(uuid, ds);
		final Map<UUID, DataSource> map2 = map;
		return new Undoer() {
			@Override
			public void undo() {
				map2.remove(uuid);
			}
		};
	}

	/**
	 * Sets a default {@code DataSource} for the specified class in this context.
	 * @param cls
	 * @param ds
	 * @return
	 */
	public Undoer setDataSource(Class<? extends Table> cls, DataSource ds) {
		Map<UUID, DataSource> map = classDataSources.get(cls);
		if (map == null) {
			map = Collections.synchronizedMap(new LinkedHashMap<UUID, DataSource>());
			classDataSources.put(cls, map);
		}
		final UUID uuid = UUID.randomUUID();
		map.put(uuid, ds);
		final Map<UUID, DataSource> map2 = map;
		return new Undoer() {
			@Override
			public void undo() {
				map2.remove(uuid);
			}
		};
	}

	/**
	 * Allows you to undo any context change. &nbsp; By default will automatically undo
	 * once this object is GCed, but this can be turned off by calling {@code setAutoUndo(false)}.
	 * @author Derek Anderson
	 */
	public static abstract class Undoer {
		private boolean autoRevoke = true;
		public abstract void undo();
		public boolean willAutoUndo() {
			return autoRevoke ;
		}
		public Undoer setAutoUndo(boolean v) {
			autoRevoke = v;
			return this;
		}

		private Undoer() {}
		protected void finalize() {
			if (autoRevoke) undo();
		}
	}

	private static Context vmContext = new Context();

	private static Map<ThreadGroup,Context> threadGroupContexts =
			Collections.synchronizedMap(new HashMap<ThreadGroup,Context>());

	private static ThreadLocal<Context> threadContextContainer = new ThreadLocal<Context>() {
		@Override
		protected Context initialValue() {
			return new Context();
		}
	};

	private Map<Tuple2<DataSource,String>,Map<UUID,String>> schemaOverrides =
			Collections.synchronizedMap(new HashMap<Tuple2<DataSource,String>,Map<UUID,String>>());

	private Map<UUID,DataSource> defaultDataSource =
			Collections.synchronizedMap(new LinkedHashMap<UUID,DataSource>());

	private Map<Package,Map<UUID,DataSource>> packageDataSources =
			Collections.synchronizedMap(new LinkedHashMap<Package,Map<UUID,DataSource>>());

	private Map<Class<?>,Map<UUID,DataSource>> classDataSources =
			Collections.synchronizedMap(new LinkedHashMap<Class<?>,Map<UUID,DataSource>>());

	private Map<DataSource,Connection> transactionConnections =
			Collections.synchronizedMap(new HashMap<DataSource,Connection>());

}