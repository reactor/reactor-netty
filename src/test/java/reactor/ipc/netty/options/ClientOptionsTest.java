package reactor.ipc.netty.options;

import org.junit.Test;

import static org.assertj.core.api.Assertions.assertThat;

public class ClientOptionsTest {

	@Test
	public void asSimpleString() {
		ClientOptions opt = ClientOptions.create();

		assertThat(opt.asSimpleString()).isEqualTo("connecting to no base address");

		//proxy
		opt.proxy(ClientOptions.Proxy.SOCKS4, "http://proxy", 456);
		assertThat(opt.asSimpleString()).isEqualTo("connecting to no base address through SOCKS4 proxy");

		//address
		opt.connect("http://google.com", 123);
		assertThat(opt.asSimpleString()).isEqualTo("connecting to http://google.com:123 through SOCKS4 proxy");
	}

	@Test
	public void asDetailedString() {
		ClientOptions opt = ClientOptions.create();

		assertThat(opt.asDetailedString())
				.startsWith("connectAddress=null, proxy=null");

		//proxy
		opt.proxy(ClientOptions.Proxy.SOCKS4, "http://proxy", 456);
		assertThat(opt.asDetailedString())
				.startsWith("connectAddress=null, proxy=SOCKS4(http://proxy:456)");

		//address
		opt.connect("http://google.com", 123);
		assertThat(opt.asDetailedString())
				.startsWith("connectAddress=http://google.com:123, proxy=SOCKS4(http://proxy:456)");
	}

	@Test
	public void toStringContainsAsDetailedString() {
		ClientOptions opt = ClientOptions.create()
		                                         .connect("http://google.com", 123)
		                                         .proxy(ClientOptions.Proxy.SOCKS4, "http://proxy", 456);
		assertThat(opt.toString())
				.startsWith("ClientOptions{connectAddress=http://google.com:123, proxy=SOCKS4(http://proxy:456)")
				.endsWith("}");
	}

}