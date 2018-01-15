/*
 * Copyright (c) 2011-2018 Pivotal Software Inc, All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package reactor.ipc.netty.http;

import java.io.ByteArrayInputStream;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;
import java.util.zip.GZIPInputStream;

import io.netty.buffer.ByteBuf;
import io.netty.handler.codec.http.HttpHeaders;
import org.junit.Assert;
import org.junit.Test;
import reactor.core.publisher.Mono;
import reactor.ipc.netty.DisposableServer;
import reactor.ipc.netty.http.client.HttpClient;
import reactor.ipc.netty.http.server.HttpServer;
import reactor.util.function.Tuple2;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * @author mostroverkhov
 */
public class HttpCompressionClientServerTests {

	@Test
	public void trueEnabledIncludeContentEncoding() throws Exception {

		HttpServer server = HttpServer.create()
		                              .port(0)
		                              .compress();

		DisposableServer runningServer =
				server.handler((in, out) -> out.sendString(Mono.just("reply")))
				      .wiretap()
				      .bindNow(Duration.ofMillis(10_000));

		HttpClient client = HttpClient.prepare()
		                              .addressSupplier(() -> address(runningServer))
		                              .wiretap()
		                              .compress();
		ByteBuf res =
				client.headers(h -> Assert.assertTrue(h.contains("Accept-Encoding", "gzip", true)))
				      .get()
				      .uri("/test")
				      .responseContent()
				      .blockLast();

		runningServer.dispose();
		runningServer.onDispose()
		            .block();
	}

	@Test
	public void serverCompressionDefault() throws Exception {
		HttpServer server = HttpServer.create()
		                              .port(0);

		DisposableServer runningServer =
				server.handler((in, out) -> out.sendString(Mono.just("reply")))
				      .wiretap()
				      .bindNow(Duration.ofMillis(10_000));

		HttpClient client = HttpClient.prepare()
				                      .addressSupplier(() -> address(runningServer))
				                      .wiretap();
		Tuple2<String, HttpHeaders> resp =
				client.headers(h -> h.add("Accept-Encoding", "gzip"))
				      .get()
				      .uri("/test")
				      .response((res, buf) -> buf.asString()
				                                 .zipWith(Mono.just(res.responseHeaders())))
				      .blockFirst();

		assertThat(resp.getT2().get("content-encoding")).isNull();

		Assert.assertEquals("reply", resp.getT1());

		runningServer.dispose();
		runningServer.onDispose()
		            .block();
	}

	@Test
	public void serverCompressionDisabled() throws Exception {
		HttpServer server = HttpServer.create()
		                              .port(0)
		                              .noCompression();

		DisposableServer runningServer =
				server.handler((in, out) -> out.sendString(Mono.just("reply")))
				      .wiretap()
				      .bindNow(Duration.ofMillis(10_000));

		//don't activate compression on the client options to avoid auto-handling (which removes the header)
		HttpClient client = HttpClient.prepare()
				                      .addressSupplier(() -> address(runningServer))
				                      .wiretap();
		Tuple2<String, HttpHeaders> resp =
				//edit the header manually to attempt to trigger compression on server side
				client.headers(h -> h.add("Accept-Encoding", "gzip"))
				      .get()
				      .uri("/test")
				      .response((res, buf) -> buf.asString()
				                                 .zipWith(Mono.just(res.responseHeaders())))
				      .blockFirst();

		assertThat(resp.getT2().get("content-encoding")).isNull();

		Assert.assertEquals("reply", resp.getT1());

		runningServer.dispose();
		runningServer.onDispose()
		            .block();
	}

	@Test
	public void serverCompressionAlwaysEnabled() throws Exception {
		HttpServer server = HttpServer.create()
		                              .port(0)
		                              .compress();

		DisposableServer runningServer =
				server.handler((in, out) -> out.sendString(Mono.just("reply")))
				      .wiretap()
				      .bindNow(Duration.ofMillis(10_000));

		//don't activate compression on the client options to avoid auto-handling (which removes the header)
		HttpClient client = HttpClient.prepare()
				                      .addressSupplier(() -> address(runningServer))
		                              .noCompression()
				                      .wiretap();
		Tuple2<byte[], HttpHeaders> resp =
				//edit the header manually to attempt to trigger compression on server side
				client.headers(h -> h.add("Accept-Encoding", "gzip"))
				      .get()
				      .uri("/test")
				      .responseSingle((res, buf) -> buf.asByteArray()
				                                       .zipWith(Mono.just(res.responseHeaders())))
				      .block();

		assertThat(resp.getT2().get("content-encoding")).isEqualTo("gzip");

		assertThat(new String(resp.getT1())).isNotEqualTo("reply");

		GZIPInputStream gis = new GZIPInputStream(new ByteArrayInputStream(resp.getT1()));
		byte deflatedBuf[] = new byte[1024];
		int readable = gis.read(deflatedBuf);
		gis.close();

		assertThat(readable).isGreaterThan(0);

		String deflated = new String(deflatedBuf, 0, readable);

		assertThat(deflated).isEqualTo("reply");

		runningServer.dispose();
		runningServer.onDispose()
		          .block();
	}

	@Test
	public void serverCompressionEnabledSmallResponse() throws Exception {
		HttpServer server = HttpServer.create()
		                              .port(0)
		                              .compress(25);

		DisposableServer runningServer =
				server.handler((in, out) -> out.sendString(Mono.just("reply")))
				      .wiretap()
				      .bindNow(Duration.ofMillis(10_000));

		//don't activate compression on the client options to avoid auto-handling (which removes the header)
		HttpClient client = HttpClient.prepare()
				                      .addressSupplier(() -> address(runningServer))
				                      .wiretap();
		Tuple2<String, HttpHeaders> resp =
				//edit the header manually to attempt to trigger compression on server side
				client.headers(h -> h.add("Accept-Encoding", "gzip"))
				      .get()
				      .uri("/test")
				      .response((res, buf) -> buf.asString()
				                                 .zipWith(Mono.just(res.responseHeaders())))
				      .blockFirst();

		//check the server didn't send the gzip header, only transfer-encoding
		HttpHeaders headers = resp.getT2();
		assertThat(headers.get("transFER-encoding")).isEqualTo("chunked");
		assertThat(headers.get("conTENT-encoding")).isNull();

		//check the server sent plain text
		Assert.assertEquals("reply", resp.getT1());

		runningServer.dispose();
		runningServer.onDispose()
		            .block();
	}

	@Test
	public void serverCompressionEnabledBigResponse() throws Exception {
		HttpServer server = HttpServer.create()
		                              .port(0)
		                              .compress(4);

		DisposableServer runningServer =
				server.handler((in, out) -> out.sendString(Mono.just("reply")))
				      .wiretap()
				      .bindNow(Duration.ofMillis(10_000));

		//don't activate compression on the client options to avoid auto-handling (which removes the header)
		HttpClient client = HttpClient.prepare()
				                      .addressSupplier(() -> address(runningServer))
				                      .wiretap();
		Tuple2<byte[], HttpHeaders> resp =
				//edit the header manually to attempt to trigger compression on server side
				client.headers(h -> h.add("accept-encoding", "gzip"))
				      .get()
				      .uri("/test")
				      .responseSingle((res, buf) -> buf.asByteArray()
				                                       .zipWith(Mono.just(res.responseHeaders())))
				      .block();

		assertThat(resp.getT2().get("content-encoding")).isEqualTo("gzip");

		assertThat(new String(resp.getT1())).isNotEqualTo("reply");

		GZIPInputStream gis = new GZIPInputStream(new ByteArrayInputStream(resp.getT1()));
		byte deflatedBuf[] = new byte[1024];
		int readable = gis.read(deflatedBuf);
		gis.close();

		assertThat(readable).isGreaterThan(0);

		String deflated = new String(deflatedBuf, 0, readable);

		assertThat(deflated).isEqualTo("reply");

		runningServer.dispose();
		runningServer.onDispose()
		          .block();
	}

	@Test
	public void compressionServerEnabledClientDisabledIsNone() throws Exception {
		HttpServer server = HttpServer.create()
		                              .port(0)
		                              .compress();

		String serverReply = "reply";
		DisposableServer runningServer =
				server.handler((in, out) -> out.sendString(Mono.just(serverReply)))
				      .wiretap()
				      .bindNow(Duration.ofMillis(10_000));

		HttpClient client = HttpClient.prepare()
				                      .addressSupplier(() -> address(runningServer))
				                      .wiretap()
				                      .noCompression();

		Tuple2<String, HttpHeaders> resp =
				client.get()
				      .uri("/test")
				      .response((res, buf) -> buf.asString()
				                                 .zipWith(Mono.just(res.responseHeaders())))
				      .blockFirst();

		assertThat(resp.getT2().get("Content-Encoding")).isNull();
		assertThat(resp.getT1()).isEqualTo(serverReply);

		runningServer.dispose();
		runningServer.onDispose()
		            .block();
	}


	@Test
	public void compressionServerDefaultClientDefaultIsNone() throws Exception {
		HttpServer server = HttpServer.create()
		                              .port(0);

		DisposableServer runningServer =
				server.handler((in, out) -> out.sendString(Mono.just("reply")))
				      .wiretap()
				      .bindNow(Duration.ofMillis(10_000));

		HttpClient client = HttpClient.prepare()
				                      .addressSupplier(() -> address(runningServer))
				                      .wiretap();

		Tuple2<String, HttpHeaders> resp =
				client.get()
				      .uri("/test")
				      .response((res, buf) -> buf.asString()
				                                 .zipWith(Mono.just(res.responseHeaders())))
				      .blockFirst();

		assertThat(resp.getT2().get("Content-Encoding")).isNull();
		assertThat(resp.getT1()).isEqualTo("reply");

		runningServer.dispose();
		runningServer.onDispose()
		            .block();
	}

	@Test
	public void compressionActivatedOnClientAddsHeader() {
		AtomicReference<String> zip = new AtomicReference<>("fail");

		HttpServer server = HttpServer.create()
		                              .port(0)
		                              .compress();
		DisposableServer runningServer =
				server.handler((in, out) -> out.sendString(Mono.just("reply")))
				      .wiretap()
				      .bindNow(Duration.ofMillis(10_000));
		HttpClient client = HttpClient.prepare()
				                      .addressSupplier(() -> address(runningServer))
				                      .wiretap()
				                      .compress();

		ByteBuf resp =
				client.headers(h -> zip.set(h.get("accept-encoding")))
				      .get()
				      .uri("/test")
				      .responseContent()
				      .blockLast();

		assertThat(zip.get()).isEqualTo("gzip");
		runningServer.dispose();
		runningServer.onDispose()
				.block();
	}

	private InetSocketAddress address(DisposableServer runningServer) {
		return runningServer.address();
	}
}
