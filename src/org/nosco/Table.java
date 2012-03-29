package org.nosco;

import java.sql.SQLException;
import java.util.BitSet;
import java.util.HashMap;
import java.util.Map;

import javax.sql.DataSource;

import org.nosco.Field.PK;
import org.nosco.util.Misc;



/**
 * This is the base class of all classes generated by this API.
 * Some of these methods are public only by necessity.  Please use only
 * {@code insert()}, {@code update()}, {@code save()} and {@code dirty()}.
 * I consider all others fair game for changing in later versions of the API.
 *
 * @author Derek Anderson
 */
public abstract class Table {

	/**
	 * Please do not use.
	 * @return
	 */
	public abstract String SCHEMA_NAME();

	/**
	 * Please do not use.
	 * @return
	 */
	public abstract String TABLE_NAME();

	/**
	 * Please do not use.
	 * @return
	 */
	@SuppressWarnings("rawtypes")
	public abstract Field[] FIELDS();

	/**
	 * Please do not use.
	 * @return
	 */
	public abstract Field.FK[] FKS();

	/**
	 * Please do not use.
	 */
	protected BitSet __NOSCO_FETCHED_VALUES = new BitSet();

	/**
	 * Please do not use.
	 */
	protected BitSet __NOSCO_UPDATED_VALUES = null;

	/**
	 * Returns true if the object has been modified
	 * @return true if the object has been modified
	 */
	public boolean dirty() {
		return __NOSCO_UPDATED_VALUES != null && !__NOSCO_UPDATED_VALUES.isEmpty();
	}

	/**
	 * Return the value of this instance that corresponds to the given field.
	 * Throws an IllegalArgumentException if the field isn't part of this instance.
	 * @param field
	 * @return
	 */
	public abstract <S> S get(Field<S> field);

	/**
	 * Sets the value of this instance that corresponds to the given field.
	 * Throws an IllegalArgumentException if the field isn't part of this instance.
	 * @param field
	 * @param value
	 * @return
	 */
	public abstract <S> void set(Field<S> field, S value);

	/**
	 * Creates and executes an insert statement for this object
	 * (irregardless of if it's already in the database)
	 * @return success
	 * @throws SQLException
	 */
	public abstract boolean insert() throws SQLException;

	/**
	 * Creates and executes an update statement for this object
	 * (irregardless of if it's already in the database)
	 * @return success
	 * @throws SQLException
	 */
	public abstract boolean update() throws SQLException;

	/**
	 * Creates and executes a delete statement for this object
	 * (irregardless of if it's already in the database)
	 * @return success
	 * @throws SQLException
	 */
	public abstract boolean delete() throws SQLException;

	/**
	 * Creates and executes an insert or update statement for this object
	 * based on if the object came from the database or not.
	 * @return success
	 * @throws SQLException
	 */
	public abstract boolean save() throws SQLException;

	/**
	 * Returns if this object exists in the database. &nbsp;
	 * Executes SQL looking for the PK values, or all columns if no PK.
	 * @return success
	 * @throws SQLException
	 */
	public abstract boolean exists() throws SQLException;

	/**
	 * Creates and executes an insert statement for this object
	 * (irregardless of if it's already in the database)
	 * @return success
	 * @throws SQLException
	 */
	public abstract boolean insert(DataSource ds) throws SQLException;

	/**
	 * Creates and executes an update statement for this object
	 * (irregardless of if it's already in the database)
	 * @return success
	 * @throws SQLException
	 */
	public abstract boolean update(DataSource ds) throws SQLException;

	/**
	 * Creates and executes a delete statement for this object
	 * (irregardless of if it's already in the database)
	 * @return success
	 * @throws SQLException
	 */
	public abstract boolean delete(DataSource ds) throws SQLException;

	/**
	 * Creates and executes an insert or update statement for this object
	 * based on if the object came from the database or not.
	 * @return success
	 * @throws SQLException
	 */
	public abstract boolean save(DataSource ds) throws SQLException;

	/**
	 * Returns if this object exists in the database. &nbsp;
	 * Executes SQL looking for the PK values, or all columns if no PK.
	 * @return success
	 * @throws SQLException
	 */
	public abstract boolean exists(DataSource ds) throws SQLException;

	static Map<Table,java.lang.reflect.Field> _pkCache = new HashMap<Table, java.lang.reflect.Field>();
	static Field.PK GET_TABLE_PK(Table table) {
		if (!_pkCache.containsKey(table)) {
			java.lang.reflect.Field field = null;
			try {
				field = table.getClass().getDeclaredField("PK");
			} catch (SecurityException e) {
				e.printStackTrace();
			} catch (NoSuchFieldException e) {
				e.printStackTrace();
			}
			_pkCache.put(table, field);
		}
		try {
			return (PK) _pkCache.get(table).get(table);
		} catch (IllegalArgumentException e) {
			e.printStackTrace();
		} catch (IllegalAccessException e) {
			e.printStackTrace();
		}
		return null;
	}

	/**
	 * Please do not use.
	 * @return
	 */
	public boolean sameTable(Table t) {
		if (t == null) return false;
		return t.SCHEMA_NAME() == SCHEMA_NAME() && t.TABLE_NAME() == TABLE_NAME();
	}

	/**
	 * This is used for conditional statements where the fields are ambiguous. (ie: a self-join) &nbsp;
	 * All generated subclasses of {@code Table} will contain a static {@code MyTable.as(String s)} method. &nbsp;
	 * It will return an instance of this class. &nbsp; You will likely never have to create one manually. &nbsp;
	 * (the constructor is public only because the generated classes are in a different package scope)
	 * <p>
	 * Example:
	 * <pre>  {@code MyTable.ALL.cross(MyTable.as("t2")).where(MyTable.ID.from("t2").eq(MyTable.PARENT_ID))}</pre>
	 * @author Derek Anderson
	 * @param <S>
	 */
	public static class __Alias<S extends Table> {

		final Class<S> table;
		final String alias;
		public final Query<S> ALL;

		public __Alias(Class<S> table, String alias) {
			this.table = table;
			this.alias = alias;
			Query<S> all = new DBQuery<S>(this);
			try {
				java.lang.reflect.Field f = table.getDeclaredField("ALL");
				f.setAccessible(true);
				@SuppressWarnings("unchecked")
				DBQuery<S> q = (DBQuery<S>) f.get(null);
				all = all.use(q.ds);
			} catch (SecurityException e) {
				e.printStackTrace();
			} catch (NoSuchFieldException e) {
				e.printStackTrace();
			} catch (IllegalArgumentException e) {
				e.printStackTrace();
			} catch (IllegalAccessException e) {
				e.printStackTrace();
			}
			this.ALL = all;
		}

	}

	public static interface __PrimaryKey<S extends Table> {
		<R> R get(Field<R> field);
	}

	public static interface __SimplePrimaryKey<S extends Table,V> extends __PrimaryKey<S> {
		public V value();
	}

	@Override
	public int hashCode() {
	    final int prime = 31;
	    int result = 1;
	    Field.PK<?> pk = Misc.getPK(this);
	    Field<?>[] fields = pk == null ? this.FIELDS() : pk.GET_FIELDS();
	    for (Field<?> f : fields) {
	    	Object o = this.get(f);
		    result = prime * result + ((o == null) ? 0 : o.hashCode());
	    }
	    return result;
	}

	@Override
	public boolean equals(Object other) {
		if (other == this) return true;
		if (other == null) return false;
		if (!(other instanceof Table)) return false;
	    Field.PK<?> pk = Misc.getPK(this);
	    Field<?>[] fields = pk == null ? this.FIELDS() : pk.GET_FIELDS();
	    for (Field<?> f : fields) {
	    	Object o1 = this.get(f);
	    	Object o2 = ((Table)other).get(f);
	    	if (!((o1 == null) ? (o2 == null) : o1.equals(o2))) return false;
	    }
	    return true;
	}

	/**
	 * Internal function - please don't use. &nbsp; Subject to change.
	 * @param o
	 * @return
	 */
	public abstract java.lang.Object __NOSCO_PRIVATE_mapType(java.lang.Object o);

}
