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

	@Test
	public void formatSchemeAndHostRelative() throws Exception {
		String test1 = HttpClientOptions.create()
		                                .formatSchemeAndHost("/foo", false);
		String test2 = HttpClientOptions.create()
		                                .formatSchemeAndHost("/foo", true);

		assertThat(test1).isEqualTo("http://localhost/foo");
		assertThat(test2).isEqualTo("ws://localhost/foo");
	}

	@Test
	public void formatSchemeAndHostRelativeSslSupport() throws Exception {
		String test1 = HttpClientOptions.create()
		                                .sslSupport()
		                                .formatSchemeAndHost("/foo", false);
		String test2 = HttpClientOptions.create()
		                                .sslSupport()
		                                .formatSchemeAndHost("/foo", true);

		assertThat(test1).isEqualTo("https://localhost/foo");
		assertThat(test2).isEqualTo("wss://localhost/foo");
	}

	@Test
	public void formatSchemeAndHostRelativeNoLeadingSlash() throws Exception {
		String test1 = HttpClientOptions.create()
		                                .formatSchemeAndHost("foo:8080/bar", false);
		String test2 = HttpClientOptions.create()
		                                .formatSchemeAndHost("foo:8080/bar", true);

		assertThat(test1).isEqualTo("http://foo:8080/bar");
		assertThat(test2).isEqualTo("ws://foo:8080/bar");
	}

	@Test
	public void formatSchemeAndHostRelativeAddress() throws Exception {
		String test1 = HttpClientOptions.create()
		                                .connect("127.0.0.1", 8080)
		                                .formatSchemeAndHost("/foo", false);
		String test2 = HttpClientOptions.create()
		                                .connect("127.0.0.1", 8080)
		                                .formatSchemeAndHost("/foo", true);

		assertThat(test1).isEqualTo("http://127.0.0.1:8080/foo");
		assertThat(test2).isEqualTo("ws://127.0.0.1:8080/foo");
	}

	@Test
	public void formatSchemeAndHostRelativeAddressSsl() throws Exception {
		String test1 = HttpClientOptions.create()
		                                .connect("example", 8080)
		                                .sslSupport()
		                                .formatSchemeAndHost("/foo", false);
		String test2 = HttpClientOptions.create()
		                                .connect("example", 8080)
		                                .sslSupport()
		                                .formatSchemeAndHost("/foo", true);

		assertThat(test1).isEqualTo("https://example:8080/foo");
		assertThat(test2).isEqualTo("wss://example:8080/foo");
	}

	@Test
	public void formatSchemeAndHostAbsoluteHttp() throws Exception {
		String test1 = HttpClientOptions.create()
		                                .formatSchemeAndHost("https://localhost/foo", false);
		String test2 = HttpClientOptions.create()
		                                .formatSchemeAndHost("http://localhost/foo", true);

		String test3 = HttpClientOptions.create()
		                                .sslSupport()
		                                .formatSchemeAndHost("http://localhost/foo", false);
		String test4 = HttpClientOptions.create()
		                                .sslSupport()
		                                .formatSchemeAndHost("https://localhost/foo", true);

		assertThat(test1).isEqualTo("https://localhost/foo");
		assertThat(test2).isEqualTo("http://localhost/foo");
		assertThat(test3).isEqualTo("http://localhost/foo");
		assertThat(test4).isEqualTo("https://localhost/foo");
	}

	@Test
	public void formatSchemeAndHostAbsoluteWs() throws Exception {
		String test1 = HttpClientOptions.create()
		                                .formatSchemeAndHost("wss://localhost/foo", false);
		String test2 = HttpClientOptions.create()
		                                .formatSchemeAndHost("ws://localhost/foo", true);

		String test3 = HttpClientOptions.create()
		                                .sslSupport()
		                                .formatSchemeAndHost("ws://localhost/foo", false);
		String test4 = HttpClientOptions.create()
		                                .sslSupport()
		                                .formatSchemeAndHost("wss://localhost/foo", true);

		assertThat(test1).isEqualTo("wss://localhost/foo");
		assertThat(test2).isEqualTo("ws://localhost/foo");
		assertThat(test3).isEqualTo("ws://localhost/foo");
		assertThat(test4).isEqualTo("wss://localhost/foo");
	}

}