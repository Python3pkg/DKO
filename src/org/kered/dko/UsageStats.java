package org.kered.dko;

import java.lang.reflect.InvocationTargetException;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

import javax.sql.DataSource;

import org.kered.dko.persistence.QuerySize;

class UsageStats {

	private static final int SIX_MONTHS_AGO = 1000 * 60 * 60 * 24 * 265 / 2;
	private static Map<Integer, QuerySize> cache = null;
	private static long median = 0;
	private static final Logger log = Logger.getLogger("org.kered.dko.UsageStats");

	public static long estimateRowCount(final Query<? extends Table> q1) {
		if (cache == null) init();
		final int hashCode = q1.hashCode();
		QuerySize qs = cache.get(hashCode);
		if (qs==null) {
			try {
				qs = new QuerySize();
				qs.setId((long) hashCode);
				qs.setHashCode(hashCode);
				qs.setRowCount(q1.count());
				qs.setLastSeen(System.currentTimeMillis());
				final Class<? extends Table> type = q1.getType();
				try {
					qs.setSchemaName(Util.getSchemaName(type));
					qs.setTableName(Util.getTableName(type));
				} catch (final Exception e) {
					log.warning("could not get schema or table info from type: "+ type +" --- "+ e);
				}
				qs.save();
			} catch (final SQLException e) {
				log.warning("could not get count (reverting to median): "+ e);
				return (long) (median + Math.random()*10);
			}
		}
		return qs.getRowCount();
	}

	private synchronized static void init() {
		if (cache != null) return;
		try {
			//DataSource ds = (DataSource) Class.forName("org.kered.dko.persistence.Util").getMethod("getDS", null).invoke(null);
			cache = QuerySize.ALL.where(
					QuerySize.LAST_SEEN.gt(System.currentTimeMillis()
							- SIX_MONTHS_AGO)).mapBy(QuerySize.HASH_CODE);
			final List<QuerySize> values = new ArrayList<QuerySize>(cache.values());
			final int count = values.size();
			final long[] rowCounts = new long[count];
			for (int i = 0; i < count; ++i) {
				rowCounts[i] = values.get(i).getRowCount();
			}
			Arrays.sort(rowCounts);
			median = median(rowCounts);
		} catch (final SQLException e) {
			e.printStackTrace();
		}
	}

	private static long median(final long[] a) {
		if (a.length==0) return 0;
		final int m = a.length / 2;
		if (a.length%2 == 1) return a[m];
		else return (a[m-1] + a[m]) / 2;
	}

}
