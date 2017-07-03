package reactor.ipc.netty.options;

import org.junit.Before;
import org.junit.Test;

import static org.assertj.core.api.Assertions.assertThat;

public class ClientOptionsTest {
	private ClientOptions.Builder<?> builder;
	private ClientProxyOptions proxyOptions;

	@Before
	public void setUp() {
		this.builder = ClientOptions.builder();
		this.proxyOptions =
				ClientProxyOptions.builder()
				                  .type(ClientProxyOptions.Proxy.SOCKS4)
				                  .host("http://proxy")
				                  .port(456)
				                  .build();
	}

	@Test
	public void asSimpleString() {
		assertThat(this.builder.build().asSimpleString()).isEqualTo("connecting to no base address");

		//proxy
		this.builder.proxyOptions(this.proxyOptions);
		assertThat(this.builder.build().asSimpleString()).isEqualTo("connecting to no base address through SOCKS4 proxy");

		//address
		this.builder.host("http://google.com").port(123);
		assertThat(this.builder.build().asSimpleString()).isEqualTo("connecting to http://google.com:123 through SOCKS4 proxy");
	}

	@Test
	public void asDetailedString() {
		assertThat(this.builder.build().asDetailedString())
				.startsWith("connectAddress=null, proxy=null");

		//proxy
		this.builder.proxyOptions(this.proxyOptions);
		assertThat(this.builder.build().asDetailedString())
				.startsWith("connectAddress=null, proxy=SOCKS4(http://proxy:456)");

		//address
		this.builder.host("http://google.com").port(123);
		assertThat(this.builder.build().asDetailedString())
				.startsWith("connectAddress=http://google.com:123, proxy=SOCKS4(http://proxy:456)");
	}

	@Test
	public void toStringContainsAsDetailedString() {
		this.builder.host("http://google.com")
		            .port(123)
		            .proxyOptions(proxyOptions)
		            .build();
		assertThat(this.builder.build().toString())
				.startsWith("ClientOptions{connectAddress=http://google.com:123, proxy=SOCKS4(http://proxy:456)")
				.endsWith("}");
	}

}