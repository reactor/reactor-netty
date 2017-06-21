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

}