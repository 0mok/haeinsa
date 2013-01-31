package kr.co.vcnc.haeinsa;

import java.io.IOException;
import java.util.Collection;
import java.util.List;

import kr.co.vcnc.haeinsa.thrift.generated.TRowLock;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.hbase.HBaseConfiguration;
import org.apache.hadoop.hbase.HTableDescriptor;
import org.apache.hadoop.hbase.client.HTableFactory;
import org.apache.hadoop.hbase.client.HTableInterface;
import org.apache.hadoop.hbase.client.HTableInterfaceFactory;
import org.apache.hadoop.hbase.client.Result;
import org.apache.hadoop.hbase.client.ResultScanner;
import org.apache.hadoop.hbase.client.Scan;
import org.apache.hadoop.hbase.util.Bytes;
import org.apache.hadoop.hbase.util.PoolMap;
import org.apache.hadoop.hbase.util.PoolMap.PoolType;

public class HaeinsaTablePool {
	private final PoolMap<String, HaeinsaTableInterface.Private> tables;
	private final int maxSize;
	private final PoolType poolType;
	private final Configuration config;
	private final HTableInterfaceFactory tableFactory;

	/**
	 * Default Constructor. Default HBaseConfiguration and no limit on pool
	 * size.
	 */
	public HaeinsaTablePool() {
		this(HBaseConfiguration.create(), Integer.MAX_VALUE);
	}

	/**
	 * Constructor to set maximum versions and use the specified configuration.
	 * 
	 * @param config
	 *            configuration
	 * @param maxSize
	 *            maximum number of references to keep for each table
	 */
	public HaeinsaTablePool(final Configuration config, final int maxSize) {
		this(config, maxSize, null, null);
	}

	/**
	 * Constructor to set maximum versions and use the specified configuration
	 * and table factory.
	 * 
	 * @param config
	 *            configuration
	 * @param maxSize
	 *            maximum number of references to keep for each table
	 * @param tableFactory
	 *            table factory
	 */
	public HaeinsaTablePool(final Configuration config, final int maxSize,
			final HTableInterfaceFactory tableFactory) {
		this(config, maxSize, tableFactory, PoolType.Reusable);
	}

	/**
	 * Constructor to set maximum versions and use the specified configuration
	 * and pool type.
	 * 
	 * @param config
	 *            configuration
	 * @param maxSize
	 *            maximum number of references to keep for each table
	 * @param poolType
	 *            pool type which is one of {@link PoolType#Reusable} or
	 *            {@link PoolType#ThreadLocal}
	 */
	public HaeinsaTablePool(final Configuration config, final int maxSize,
			final PoolType poolType) {
		this(config, maxSize, null, poolType);
	}

	/**
	 * Constructor to set maximum versions and use the specified configuration,
	 * table factory and pool type. The HTablePool supports the
	 * {@link PoolType#Reusable} and {@link PoolType#ThreadLocal}. If the pool
	 * type is null or not one of those two values, then it will default to
	 * {@link PoolType#Reusable}.
	 * 
	 * @param config
	 *            configuration
	 * @param maxSize
	 *            maximum number of references to keep for each table
	 * @param tableFactory
	 *            table factory
	 * @param poolType
	 *            pool type which is one of {@link PoolType#Reusable} or
	 *            {@link PoolType#ThreadLocal}
	 */
	public HaeinsaTablePool(final Configuration config, final int maxSize,
			final HTableInterfaceFactory tableFactory, PoolType poolType) {
		// Make a new configuration instance so I can safely cleanup when
		// done with the pool.
		this.config = config == null ? new Configuration() : config;
		this.maxSize = maxSize;
		this.tableFactory = tableFactory == null ? new HTableFactory()
				: tableFactory;
		if (poolType == null) {
			this.poolType = PoolType.Reusable;
		} else {
			switch (poolType) {
			case Reusable:
			case ThreadLocal:
				this.poolType = poolType;
				break;
			default:
				this.poolType = PoolType.Reusable;
				break;
			}
		}
		this.tables = new PoolMap<String, HaeinsaTableInterface.Private>(
				this.poolType, this.maxSize);
	}

	/**
	 * Get a reference to the specified table from the pool.
	 * <p>
	 * <p/>
	 * 
	 * @param tableName
	 *            table name
	 * @return a reference to the specified table
	 * @throws RuntimeException
	 *             if there is a problem instantiating the HTable
	 */
	public HaeinsaTableInterface getTable(String tableName) {
		// call the old getTable implementation renamed to findOrCreateTable
		HaeinsaTableInterface.Private table = findOrCreateTable(tableName);
		// return a proxy table so when user closes the proxy, the actual table
		// will be returned to the pool
		try {
			return new PooledHaeinsaTable(table);
		} catch (IOException ioe) {
			throw new RuntimeException(ioe);
		}
	}

	/**
	 * Get a reference to the specified table from the pool.
	 * <p>
	 * 
	 * Create a new one if one is not available.
	 * 
	 * @param tableName
	 *            table name
	 * @return a reference to the specified table
	 * @throws RuntimeException
	 *             if there is a problem instantiating the HTable
	 */
	private HaeinsaTableInterface.Private findOrCreateTable(String tableName) {
		HaeinsaTableInterface.Private table = tables.get(tableName);
		if (table == null) {
			table = createHTable(tableName);
		}
		return table;
	}

	/**
	 * Get a reference to the specified table from the pool.
	 * <p>
	 * 
	 * Create a new one if one is not available.
	 * 
	 * @param tableName
	 *            table name
	 * @return a reference to the specified table
	 * @throws RuntimeException
	 *             if there is a problem instantiating the HTable
	 */
	public HaeinsaTableInterface getTable(byte[] tableName) {
		return getTable(Bytes.toString(tableName));
	}

	/**
	 * This method is not needed anymore, clients should call
	 * HTableInterface.close() rather than returning the tables to the pool
	 * 
	 * @param table
	 *            the proxy table user got from pool
	 * @deprecated
	 */
	public void putTable(HaeinsaTableInterface table) throws IOException {
		// we need to be sure nobody puts a proxy implementation in the pool
		// but if the client code is not updated
		// and it will continue to call putTable() instead of calling close()
		// then we need to return the wrapped table to the pool instead of the
		// proxy
		// table
		if (table instanceof PooledHaeinsaTable) {
			returnTable(((PooledHaeinsaTable) table).getWrappedTable());
		} else {
			// normally this should not happen if clients pass back the same
			// table
			// object they got from the pool
			// but if it happens then it's better to reject it
			throw new IllegalArgumentException("not a pooled table: " + table);
		}
	}

	/**
	 * Puts the specified HTable back into the pool.
	 * <p>
	 * 
	 * If the pool already contains <i>maxSize</i> references to the table, then
	 * the table instance gets closed after flushing buffered edits.
	 * 
	 * @param table
	 *            table
	 */
	private void returnTable(HaeinsaTableInterface.Private table)
			throws IOException {
		// this is the old putTable method renamed and made private
		String tableName = Bytes.toString(table.getTableName());
		if (tables.size(tableName) >= maxSize) {
			// release table instance since we're not reusing it
			this.tables.remove(tableName, table);
			release(table);
			return;
		}
		tables.put(tableName, table);
	}

	protected HaeinsaTableInterface.Private createHTable(String tableName) {
		return new HaeinsaTable(this.tableFactory.createHTableInterface(config,
				Bytes.toBytes(tableName)));
	}

	private void release(HaeinsaTableInterface table) throws IOException {
		if (table instanceof HaeinsaTableInterface.Private) {
			HaeinsaTableInterface.Private privateTable = (HaeinsaTableInterface.Private) table;
			this.tableFactory.releaseHTableInterface(privateTable.getHTable());
		} else {
			table.close();
		}
	}

	/**
	 * Closes all the HTable instances , belonging to the given table, in the
	 * table pool.
	 * <p>
	 * Note: this is a 'shutdown' of the given table pool and different from
	 * {@link #putTable(HTableInterface)}, that is used to return the table
	 * instance to the pool for future re-use.
	 * 
	 * @param tableName
	 */
	public void closeTablePool(final String tableName) throws IOException {
		Collection<HaeinsaTableInterface.Private> tables = this.tables
				.values(tableName);
		if (tables != null) {
			for (HaeinsaTableInterface table : tables) {
				release(table);
			}
		}
		this.tables.remove(tableName);
	}

	/**
	 * See {@link #closeTablePool(String)}.
	 * 
	 * @param tableName
	 */
	public void closeTablePool(final byte[] tableName) throws IOException {
		closeTablePool(Bytes.toString(tableName));
	}

	/**
	 * Closes all the HTable instances , belonging to all tables in the table
	 * pool.
	 * <p>
	 * Note: this is a 'shutdown' of all the table pools.
	 */
	public void close() throws IOException {
		for (String tableName : tables.keySet()) {
			closeTablePool(tableName);
		}
		this.tables.clear();
	}

	int getCurrentPoolSize(String tableName) {
		return tables.size(tableName);
	}

	class PooledHaeinsaTable implements HaeinsaTableInterface.Private {
		private HaeinsaTableInterface.Private table;

		public PooledHaeinsaTable(HaeinsaTableInterface.Private table)
				throws IOException {
			this.table = table;
		}

		@Override
		public byte[] getTableName() {
			return table.getTableName();
		}

		@Override
		public Configuration getConfiguration() {
			return table.getConfiguration();
		}

		@Override
		public HTableDescriptor getTableDescriptor() throws IOException {
			return table.getTableDescriptor();
		}

		@Override
		public Result get(Transaction tx, HaeinsaGet get) throws IOException {
			return table.get(tx, get);
		}

		@Override
		public Result[] get(Transaction tx, List<HaeinsaGet> gets)
				throws IOException {
			return table.get(tx, gets);
		}

		@Override
		public ResultScanner getScanner(Transaction tx, Scan scan)
				throws IOException {
			return table.getScanner(tx, scan);
		}

		@Override
		public ResultScanner getScanner(Transaction tx, byte[] family)
				throws IOException {
			return table.getScanner(tx, family);
		}

		@Override
		public ResultScanner getScanner(Transaction tx, byte[] family,
				byte[] qualifier) throws IOException {
			return table.getScanner(tx, family, qualifier);
		}

		@Override
		public void put(Transaction tx, HaeinsaPut put) throws IOException {
			table.put(tx, put);
		}

		@Override
		public void put(Transaction tx, List<HaeinsaPut> puts)
				throws IOException {
			table.put(tx, puts);

		}

		@Override
		public void delete(Transaction tx, HaeinsaDelete delete)
				throws IOException {
			table.delete(tx, delete);

		}

		@Override
		public void delete(Transaction tx, List<HaeinsaDelete> deletes)
				throws IOException {
			table.delete(tx, deletes);

		}

		@Override
		public void close() throws IOException {
			returnTable(table);
		}

		HaeinsaTableInterface.Private getWrappedTable() {
			return table;
		}

		@Override
		public void prewrite(RowTransaction rowTxState, byte[] row,
				boolean isPrimary) throws IOException {
			table.prewrite(rowTxState, row, isPrimary);
		}

		@Override
		public void applyMutations(RowTransaction rowTxState, byte[] row)
				throws IOException {
			table.applyMutations(rowTxState, row);
		}

		@Override
		public void makeStable(RowTransaction rowTxState, byte[] row)
				throws IOException {
			table.makeStable(rowTxState, row);
		}

		@Override
		public void commitPrimary(RowTransaction rowTxState, byte[] row)
				throws IOException {
			table.commitPrimary(rowTxState, row);
		}

		@Override
		public TRowLock getRowLock(byte[] row) throws IOException {
			return table.getRowLock(row);
		}

		@Override
		public void abortPrimary(RowTransaction rowTxState, byte[] row)
				throws IOException {
			table.abortPrimary(rowTxState, row);
		}

		@Override
		public void deletePrewritten(RowTransaction rowTxState, byte[] row)
				throws IOException {
			table.deletePrewritten(rowTxState, row);
		}

		@Override
		public HTableInterface getHTable() {
			return table.getHTable();
		}

	}
}