package reactor.ipc.netty.http.client;

import org.junit.Test;
import reactor.ipc.netty.options.ClientOptions;

import static org.assertj.core.api.Assertions.assertThat;

public class HttpClientOptionsTest {

	@Test
	public void asSimpleString() {
		HttpClientOptions opt = HttpClientOptions.create();

		assertThat(opt.asSimpleString()).isEqualTo("connecting to no base address");

		//proxy
		opt.proxy(ClientOptions.Proxy.SOCKS4, "http://proxy", 456);
		assertThat(opt.asSimpleString()).isEqualTo("connecting to no base address through SOCKS4 proxy");

		//address
		opt.connect("http://google.com", 123);
		assertThat(opt.asSimpleString()).isEqualTo("connecting to http://google.com:123 through SOCKS4 proxy");

		//gzip
		opt.compression(true);
		assertThat(opt.asSimpleString()).isEqualTo("connecting to http://google.com:123 through SOCKS4 proxy with gzip");
	}

	@Test
	public void asDetailedString() {
		HttpClientOptions opt = HttpClientOptions.create();

		assertThat(opt.asDetailedString())
				.startsWith("connectAddress=null, proxy=null")
				.endsWith(", acceptGzip=false");

		//proxy
		opt.proxy(ClientOptions.Proxy.SOCKS4, "http://proxy", 456);
		assertThat(opt.asDetailedString())
				.startsWith("connectAddress=null, proxy=SOCKS4(http://proxy:456)")
				.endsWith(", acceptGzip=false");

		//address
		opt.connect("http://google.com", 123);
		assertThat(opt.asDetailedString())
				.startsWith("connectAddress=http://google.com:123, proxy=SOCKS4(http://proxy:456)")
				.endsWith(", acceptGzip=false");

		//gzip
		opt.compression(true);
		assertThat(opt.asDetailedString())
				.startsWith("connectAddress=http://google.com:123, proxy=SOCKS4(http://proxy:456)")
				.endsWith(", acceptGzip=true");
	}

	@Test
	public void toStringContainsAsDetailedString() {
		HttpClientOptions opt = HttpClientOptions.create()
		                                         .connect("http://google.com", 123)
		                                         .proxy(ClientOptions.Proxy.SOCKS4, "http://proxy", 456)
		                                         .compression(true);
		assertThat(opt.toString())
				.startsWith("HttpClientOptions{connectAddress=http://google.com:123, proxy=SOCKS4(http://proxy:456)")
				.endsWith(", acceptGzip=true}");
	}

}