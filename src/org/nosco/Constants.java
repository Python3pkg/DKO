package org.nosco;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.SQLException;
import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.logging.Logger;

import javax.sql.DataSource;

import org.nosco.datasource.ConnectionCountingDataSource;
import org.nosco.datasource.MirroredDataSource;
import org.nosco.datasource.ReflectedDataSource;

/**
 * Defines constants for use in this API.
 *
 * @author Derek Anderson
 */
public class Constants {

	public static enum DIRECTION {
		ASCENDING,
		DESCENDING
	}

	public static enum CALENDAR {
		NANOSECOND,
		MICROSECOND,
		MILLISECOND,
		SECOND,
		MINUTE,
		HOUR,
		DAY,
		WEEKDAY,
		WEEK,
		MONTH,
		QUARTER,
		YEAR,
	}

	/**
	 * Used for tweaking generated SQL by database type.
	 * You can override the detected type by passing one of these constants
	 * into Query.use(DB_TYPE type).
	 *
	 * @author Derek Anderson
	 */
	public enum DB_TYPE {
		MYSQL,
		SQLSERVER,
		POSTGRES,
		ORACLE,
		SQLITE3,
		HSQL,
		SQL92;

		private static Map<DataSource, DB_TYPE> cache = Collections
				.synchronizedMap(new WeakHashMap<DataSource, DB_TYPE>());

		static DB_TYPE detect(final DataSource ds) {

			// see if we've already typed this one
			final DB_TYPE cached = cache.get(ds);
			if (cached != null) return cached;

			// unwrap known datasource layers
			DataSource underlying = ds;
			while (true) {
				if (underlying instanceof MirroredDataSource) {
					underlying = ((MirroredDataSource)underlying).getPrimaryDataSource();
					continue;
				}
				if (underlying instanceof ReflectedDataSource) {
					underlying = ((ReflectedDataSource)underlying).getUnderlyingDataSource();
					continue;
				}
				if (underlying instanceof ConnectionCountingDataSource) {
					underlying = ((ConnectionCountingDataSource)underlying).getUnderlyingDataSource();
					continue;
				}
				if (underlying == null) return null;
				break;
			}

			// is the class recognizable?
			final String className = underlying.getClass().getName();
			if (className.contains("SQLServer")) {
				cache.put(ds, SQLSERVER);
				return SQLSERVER;
			}
			if (className.contains("org.hsqldb")) {
				cache.put(ds, HSQL);
				return HSQL;
			}
			if (className.contains("com.mysql")) {
				cache.put(ds, MYSQL);
				return MYSQL;
			}

			// attempt to detect from a connection
			Connection conn = null;
			try {
				conn = ds.getConnection();
				final DB_TYPE type = detect(conn);
				if (type != null) {
					cache.put(ds, type);
					return type;
				}
			} catch (final SQLException e) {
				e.printStackTrace();
			} finally {
				try {
					if (conn != null && !conn.isClosed()) {
						conn.close();
					}
				} catch (final SQLException e) {
					e.printStackTrace();
				}
			}

			// unknown
			System.err.println("unknown db type for DataSource: "+ ds);
			return null;
		}

		static DB_TYPE detect(final Connection conn) throws SQLException {

			// try from the class name
			final String className = conn.getClass().getName();
			if (className.contains("SQLServer")) return SQLSERVER;
			//System.err.println(className);

			// try from the jdbc metadata
			final DatabaseMetaData metaData = conn.getMetaData();
			String driver = null;
			String url = null;
			if (metaData != null) {
				driver = metaData.getDriverName();
				if (driver.contains("sqlserver")) return SQLSERVER;
				if (driver.contains("hsqldb")) return HSQL;
				url = metaData.getURL();
				if (url.startsWith("jdbc:sqlserver")) return SQLSERVER;
				if (url.startsWith("jdbc:hsql")) return HSQL;
			}

			System.err.println("unknown db type for Connection: "+ conn
					+" (driver:"+ driver +", url:"+ url +")");
			return null;
		}

		String getDatabaseTableSeparator() {
			return this == SQLSERVER ? ".dbo." : ".";
		}

	}

	/**
	 * Writes the generated SQL to stderr.  Now all SQL is logged by default
	 * to: {@code java.util.logging.Logger.getLogger("org.nosco.sql");}
	 */
	@Deprecated
	public static final String PROP_LOG_SQL = "org.nosco.log_sql";

	/**
	 * Writes the generated SQL to stderr.  Now all SQL is logged by default
	 * to: {@code java.util.logging.Logger.getLogger("org.nosco.sql");}
	 */
	@Deprecated
	public static final String PROP_LOG = "org.nosco.log";

	/**
	 * A Java property that controls the select statement optimizations.
	 * These optimizations filter out fields from generated queries based on which
	 * columns have been accessed over the life of all the objects created from the query.
	 * Enabled by default.
	 */
	public static final String PROPERTY_OPTIMIZE_SELECT_FIELDS = "org.nosco.optimize_select_fields";

	/**
	 * A Java property that controls the automatic warning of excessive lazy loading
	 * (which can seriously degrade both application and database performance).
	 * Enabled by default.
	 */
	public static final String PROPERTY_WARN_EXCESSIVE_LAZY_LOADING = "org.nosco.warn_excessive_lazy_loading";

	/**
	 * A Java property that controls where Nosco keeps cached performance metrics.
	 * By default: ~/.nosco_optimizations
	 */
	public static final String PROPERTY_CACHE_DIR = "org.nosco.cache_dir";

}

