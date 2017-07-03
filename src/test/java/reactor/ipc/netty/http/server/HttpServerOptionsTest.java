package reactor.ipc.netty.http.server;

import org.junit.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

public class HttpServerOptionsTest {

	@Test
	public void minResponseForCompressionNegative() {
		HttpServerOptions.Builder builder = HttpServerOptions.builder();

		assertThatExceptionOfType(IllegalArgumentException.class)
				.isThrownBy(() -> builder.compression(-1))
				.withMessage("minResponseSize must be positive");
	}

	@Test
	public void minResponseForCompressionZero() {
		HttpServerOptions.Builder builder = HttpServerOptions.builder();
		builder.compression(0);

		assertThat(builder.build().minCompressionResponseSize()).isZero();
	}

	@Test
	public void minResponseForCompressionPositive() {
		HttpServerOptions.Builder builder = HttpServerOptions.builder();
		builder.compression(10);

		assertThat(builder.build().minCompressionResponseSize()).isEqualTo(10);
	}

	@Test
	public void asSimpleString() {
		HttpServerOptions.Builder builder = HttpServerOptions.builder();

		assertThat(builder.build().asSimpleString()).isEqualTo("listening on 0.0.0.0/0.0.0.0:0");

		//address
		builder.host("foo").port(123);
		assertThat(builder.build().asSimpleString()).isEqualTo("listening on foo:123");

		//gzip
		builder.compression(true);
		assertThat(builder.build().asSimpleString()).isEqualTo("listening on foo:123, gzip");

		//gzip with threshold
		builder.compression(534);
		assertThat(builder.build().asSimpleString()).isEqualTo("listening on foo:123, gzip over 534 bytes");
	}

	@Test
	public void asDetailedString() {
		HttpServerOptions.Builder builder = HttpServerOptions.builder();

		assertThat(builder.build().asDetailedString())
				.startsWith("address=0.0.0.0/0.0.0.0:0")
				.endsWith(", minCompressionResponseSize=-1");

		//address
		builder.host("foo").port(123);
		assertThat(builder.build().asDetailedString())
				.startsWith("address=foo:123")
				.endsWith(", minCompressionResponseSize=-1");

		//gzip
		builder.compression(true);
		assertThat(builder.build().asDetailedString())
				.startsWith("address=foo:123")
				.endsWith(", minCompressionResponseSize=0");

		//gzip with threshold
		builder.compression(534);
		assertThat(builder.build().asDetailedString())
				.startsWith("address=foo:123")
				.endsWith(", minCompressionResponseSize=534");
	}

	@Test
	public void toStringContainsAsDetailedString() {
		HttpServerOptions.Builder builder = HttpServerOptions.builder()
		                                                     .compression(534)
		                                                     .host("http://google.com")
		                                                     .port(123);
		assertThat(builder.build().toString())
				.startsWith("HttpServerOptions{address=http://google.com:123")
				.endsWith(", minCompressionResponseSize=534}");
	}

}