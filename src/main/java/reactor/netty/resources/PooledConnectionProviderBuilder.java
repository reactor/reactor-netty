package reactor.netty.resources;

/**
 * {@link PooledConnectionProvider} builder
 * @author Dmitrii Borin
 */
final public class PooledConnectionProviderBuilder {
    private String name;
    private PooledConnectionProvider.PoolFactory poolFactory;
    private long acquireTimeout;
    private int maxConnections;

    private PooledConnectionProviderBuilder() {
    }

    public static PooledConnectionProviderBuilder newInstance() {
        return new PooledConnectionProviderBuilder();
    }

    public PooledConnectionProvider build() {
        return new PooledConnectionProvider(name, poolFactory,
                maxConnections == PooledConnectionProvider.MAX_CONNECTIONS_ELASTIC ? 0 : acquireTimeout, maxConnections);
    }

    public PooledConnectionProviderBuilder name(String name) {
        this.name = name;
        return this;
    }

    public PooledConnectionProviderBuilder poolFactory(PooledConnectionProvider.PoolFactory poolFactory) {
        this.poolFactory = poolFactory;
        return this;
    }

    public PooledConnectionProviderBuilder acquireTimeout(long acquireTimeout) {
        this.acquireTimeout = acquireTimeout;
        return this;
    }

    public PooledConnectionProviderBuilder maxConnections(int maxConnections) {
        this.maxConnections = maxConnections;
        return this;
    }
}
