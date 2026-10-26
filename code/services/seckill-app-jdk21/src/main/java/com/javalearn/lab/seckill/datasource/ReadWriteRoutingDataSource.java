package com.javalearn.lab.seckill.datasource;

import org.springframework.jdbc.datasource.lookup.AbstractRoutingDataSource;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Routes each JDBC connection to the read or the write pool based on the
 * read-only flag of the Spring transaction that is currently active on the
 * calling thread.
 *
 * <p>The lookup key is resolved when a physical connection is requested. For
 * the routing to see the transaction's {@code readOnly} flag, the connection
 * must be acquired <em>after</em> the transaction has been started, which is
 * why the primary {@code DataSource} bean wraps this one in a
 * {@code LazyConnectionDataSourceProxy} instead of exposing it directly.</p>
 *
 * <p>Calls that are not inside a transaction default to {@link DataSourceType#WRITE};
 * the same is true for read-write transactions. The routing is deliberately
 * conservative: a read-only pool must never be used for a statement that can
 * write, and a stale read connection must never see uncommitted data of the
 * current write transaction.</p>
 */
public class ReadWriteRoutingDataSource extends AbstractRoutingDataSource {

    @Override
    protected Object determineCurrentLookupKey() {
        return TransactionSynchronizationManager.isCurrentTransactionReadOnly()
                ? DataSourceType.READ
                : DataSourceType.WRITE;
    }
}
