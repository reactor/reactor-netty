package reactor.netty.resources;

import static org.junit.Assert.assertTrue;

import org.junit.Before;
import org.junit.Test;

import reactor.netty.ReactorNetty;

public class ConnectionProviderSystemPropertiesTest {

	private long maxLifeTimeMillis = 12345;
	private long maxConnections = 12345;
	private long acquireTimeout = 12345;
	private long maxIdleTimeMillis = 12345;
	
	@Before
	public void before() {
		
		System.setProperty(ReactorNetty.POOL_MAX_LIFE_TIME, ""+maxLifeTimeMillis);		
		System.setProperty(ReactorNetty.POOL_MAX_CONNECTIONS, ""+maxConnections);
		System.setProperty(ReactorNetty.POOL_ACQUIRE_TIMEOUT, ""+acquireTimeout);
		System.setProperty(ReactorNetty.POOL_MAX_IDLE_TIME, ""+maxIdleTimeMillis);
	}

	@Test
	public void itShouldBuildConnectionProviderWithProvidedSystemProperties() {
		
		PooledConnectionProvider provider =
				(PooledConnectionProvider) ConnectionProvider.builder("testIssue1232_SystemPropertyMaxLifeTimeAndOthers")
				                                             .build();
		
		assertTrue(provider.defaultPoolFactory.maxLifeTime == maxLifeTimeMillis);
		assertTrue(provider.defaultPoolFactory.maxConnections == maxConnections);
		assertTrue(provider.defaultPoolFactory.pendingAcquireTimeout == acquireTimeout);
		assertTrue(provider.defaultPoolFactory.maxIdleTime == maxIdleTimeMillis);
		provider.dispose();
	}


}
