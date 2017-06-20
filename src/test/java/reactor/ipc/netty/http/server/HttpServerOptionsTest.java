package reactor.ipc.netty.http.server;

import org.junit.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

public class HttpServerOptionsTest {

	@Test
	public void minResponseForCompressionNegative() {
		HttpServerOptions options = HttpServerOptions.create();

		assertThatExceptionOfType(IllegalArgumentException.class)
				.isThrownBy(() -> options.compression(-1))
				.withMessage("minResponseSize must be positive");
	}

	@Test
	public void minResponseForCompressionZero() {
		HttpServerOptions options = HttpServerOptions.create();
		options.compression(0);

		assertThat(options.minCompressionResponseSize).isZero();
	}

	@Test
	public void minResponseForCompressionPositive() {
		HttpServerOptions options = HttpServerOptions.create();
		options.compression(10);

		assertThat(options.minCompressionResponseSize).isEqualTo(10);
	}

	@Test
	public void asSimpleString() {
		HttpServerOptions opt = HttpServerOptions.create();

		assertThat(opt.asSimpleString()).isEqualTo("listening on 0.0.0.0/0.0.0.0:0");

		//address
		opt.listen("foo", 123);
		assertThat(opt.asSimpleString()).isEqualTo("listening on foo:123");

		//gzip
		opt.compression(true);
		assertThat(opt.asSimpleString()).isEqualTo("listening on foo:123, gzip");

		//gzip with threshold
		opt.compression(534);
		assertThat(opt.asSimpleString()).isEqualTo("listening on foo:123, gzip over 534 bytes");
	}

	@Test
	public void asDetailedString() {
		HttpServerOptions opt = HttpServerOptions.create();

		assertThat(opt.asDetailedString())
				.startsWith("address=0.0.0.0/0.0.0.0:0")
				.endsWith(", minCompressionResponseSize=-1");

		//address
		opt.listen("foo", 123);
		assertThat(opt.asDetailedString())
				.startsWith("address=foo:123")
				.endsWith(", minCompressionResponseSize=-1");

		//gzip
		opt.compression(true);
		assertThat(opt.asDetailedString())
				.startsWith("address=foo:123")
				.endsWith(", minCompressionResponseSize=0");

		//gzip with threshold
		opt.compression(534);
		assertThat(opt.asDetailedString())
				.startsWith("address=foo:123")
				.endsWith(", minCompressionResponseSize=534");
	}

	@Test
	public void toStringContainsAsDetailedString() {
		HttpServerOptions opt = HttpServerOptions.create()
		                                         .listen("http://google.com", 123)
		                                         .compression(534);
		assertThat(opt.toString())
				.startsWith("HttpServerOptions{address=http://google.com:123")
				.endsWith(", minCompressionResponseSize=534}");
	}

}