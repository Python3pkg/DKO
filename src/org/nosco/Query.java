package org.nosco;

import java.sql.SQLException;
import java.util.Collection;
import java.util.Map;

import org.nosco.Constants.DIRECTION;



public interface Query<T extends Table> extends Iterable<T> {
	
	public Query<T> where(Condition... conditions);

	public T get(Condition... conditions);

	public int count() throws SQLException;

	public int size() throws SQLException;

	public Query<T> exclude(Condition... conditions);

	public Query<T> orderBy(Field<?>... field);

	public Query<T> top(int i);

	public Query<T> limit(int i);

	public Query<T> distinct();

	public Query<T> with(Field.FK... fields);

	public Query<T> deferFields(Field<?>... field);

	public Query<T> onlyFields(Field<?>... field);

	public T latest(Field<?> name);

	public T first();

	public boolean isEmpty() throws SQLException;

	public int update() throws SQLException;

	public int deleteAll() throws SQLException;

	public Statistics stats(Field<?>... field);

	public Iterable<T> all();

	public Iterable<T> none();

	public Query<T> orderBy(DIRECTION direction, Field<?>... fields);

	public Query<T> set(Field<?> key, Object value);

	public Query<T> set(Map<Field<?>,Object> values);

	public Object insert() throws SQLException;

	public T getTheOnly();

}
