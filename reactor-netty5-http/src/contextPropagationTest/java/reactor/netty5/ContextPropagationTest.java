/*
 * Copyright (c) 2022-2024 VMware, Inc. or its affiliates, All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package reactor.netty5;

import io.micrometer.context.ContextRegistry;
import io.micrometer.context.ContextSnapshot;
import io.micrometer.context.ThreadLocalAccessor;
import io.netty5.buffer.Buffer;
import io.netty5.channel.ChannelHandler;
import io.netty5.channel.ChannelHandlerAdapter;
import io.netty5.channel.ChannelHandlerContext;
import io.netty5.handler.codec.http.DefaultFullHttpRequest;
import io.netty5.handler.codec.http.FullHttpRequest;
import io.netty5.handler.codec.http.HttpHeaderNames;
import io.netty5.handler.codec.http.headers.HttpHeaders;
import io.netty5.handler.codec.http2.Http2StreamChannel;
import io.netty5.handler.ssl.util.InsecureTrustManagerFactory;
import io.netty5.handler.ssl.util.SelfSignedCertificate;
import io.netty5.util.concurrent.Future;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import reactor.core.publisher.Hooks;
import reactor.core.publisher.Mono;
import reactor.netty5.channel.ChannelOperations;
import reactor.netty5.http.Http11SslContextSpec;
import reactor.netty5.http.Http2SslContextSpec;
import reactor.netty5.http.HttpProtocol;
import reactor.netty5.http.client.HttpClient;
import reactor.netty5.http.server.HttpServer;
import reactor.netty5.resources.ConnectionProvider;
import reactor.test.StepVerifier;

import java.nio.charset.Charset;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static reactor.netty5.ReactorNetty.getChannelContext;

class ContextPropagationTest {
	static final ConnectionProvider provider = ConnectionProvider.create("testContextPropagation", 1);
	static final ContextRegistry registry = ContextRegistry.getInstance();

	static SelfSignedCertificate ssc;

	HttpServer baseServer;
	DisposableServer disposableServer;
	Http2SslContextSpec serverCtx;


	@BeforeAll
	static void createSelfSignedCertificate() throws Exception {
		ssc = new SelfSignedCertificate();
	}

	@AfterAll
	static void disposePool() {
		provider.disposeLater()
		        .block(Duration.ofSeconds(30));
	}

	@BeforeEach
	void setUp() {
		serverCtx = Http2SslContextSpec.forServer(ssc.certificate(), ssc.privateKey());
		baseServer =
				HttpServer.create()
				          .wiretap(true)
				          .httpRequestDecoder(spec -> spec.h2cMaxContentLength(256))
				          .handle((in, out) -> out.send(in.receive().transferOwnership()));
	}

	@ParameterizedTest
	@MethodSource("httpClientCombinations")
	void testContextPropagation(HttpClient client) {
		HttpServer server = client.configuration().sslProvider() != null ?
				baseServer.secure(spec -> spec.sslContext(serverCtx)).protocol(HttpProtocol.HTTP11, HttpProtocol.H2) :
				baseServer.protocol(HttpProtocol.HTTP11, HttpProtocol.H2C);

		disposableServer = server.bindNow();

		try {
			registry.registerThreadLocalAccessor(new TestThreadLocalAccessor());

			HttpClient localClient =
					client.port(disposableServer.port())
					      .wiretap(true);

			TestThreadLocalHolder.value("First");

			sendRequest(localClient.mapConnect(mono -> mono.contextWrite(ctx -> ContextSnapshot.captureAll(registry).updateContext(ctx))),
			            "TestFirstSecond");

			TestThreadLocalHolder.value("Third");

			sendRequest(localClient.mapConnect(mono -> mono.contextWrite(ctx -> ContextSnapshot.captureAll(registry).updateContext(ctx))),
			            "TestThirdSecond");

			sendRequest(localClient, "TestSecondSecond");
		}
		finally {
			TestThreadLocalHolder.reset();
			registry.removeThreadLocalAccessor(TestThreadLocalAccessor.KEY);
			disposableServer.disposeNow();
		}
	}

	@ParameterizedTest
	@MethodSource("httpClientCombinations")
	void testAutomaticContextPropagation(HttpClient client) {
		HttpServer server = client.configuration().sslProvider() != null ?
				baseServer.secure(spec -> spec.sslContext(serverCtx)).protocol(HttpProtocol.HTTP11, HttpProtocol.H2) :
				baseServer.protocol(HttpProtocol.HTTP11, HttpProtocol.H2C);

		disposableServer = server.bindNow();

		try {
			Hooks.enableAutomaticContextPropagation();

			registry.registerThreadLocalAccessor(new TestThreadLocalAccessor());

			TestThreadLocalHolder.value("First");

			HttpClient.ResponseReceiver<?> responseReceiver =
					client.port(disposableServer.port())
					      .wiretap(true)
					      .post()
					      .uri("/")
					      .send(BufferMono.fromString(Mono.just("test")));

			response(responseReceiver);
			responseConnection(responseReceiver);
			responseContent(responseReceiver);
			responseSingle(responseReceiver);
		}
		finally {
			TestThreadLocalHolder.reset();
			registry.removeThreadLocalAccessor(TestThreadLocalAccessor.KEY);
			Hooks.disableAutomaticContextPropagation();
			disposableServer.disposeNow();
		}
	}

	static Object[] httpClientCombinations() {
		Http11SslContextSpec clientCtxHttp11 =
				Http11SslContextSpec.forClient()
				                    .configure(builder -> builder.trustManager(InsecureTrustManagerFactory.INSTANCE));

		Http2SslContextSpec clientCtxHttp2 =
				Http2SslContextSpec.forClient()
				                   .configure(builder -> builder.trustManager(InsecureTrustManagerFactory.INSTANCE));

		// Default connection pool
		HttpClient client1 = HttpClient.create();

		// Disabled connection pool
		HttpClient client2 = HttpClient.newConnection();

		// Custom connection pool
		HttpClient client3 = HttpClient.create(provider);

		return new Object[]{
				client1, // by default protocol is HTTP/1.1
				client1.protocol(HttpProtocol.H2C),
				client1.protocol(HttpProtocol.HTTP11, HttpProtocol.H2C),
				client1.secure(spec -> spec.sslContext(clientCtxHttp11)), // by default protocol is HTTP/1.1
				client1.secure(spec -> spec.sslContext(clientCtxHttp2)).protocol(HttpProtocol.H2),
				client1.secure(spec -> spec.sslContext(clientCtxHttp2)).protocol(HttpProtocol.HTTP11, HttpProtocol.H2),
				client2, // by default protocol is HTTP/1.1
				client2.protocol(HttpProtocol.H2C),
				client2.protocol(HttpProtocol.HTTP11, HttpProtocol.H2C),
				client2.secure(spec -> spec.sslContext(clientCtxHttp11)), // by default protocol is HTTP/1.1
				client2.secure(spec -> spec.sslContext(clientCtxHttp2)).protocol(HttpProtocol.H2),
				client2.secure(spec -> spec.sslContext(clientCtxHttp2)).protocol(HttpProtocol.HTTP11, HttpProtocol.H2),
				client3, // by default protocol is HTTP/1.1
				client3.protocol(HttpProtocol.H2C),
				client3.protocol(HttpProtocol.HTTP11, HttpProtocol.H2C),
				client3.secure(spec -> spec.sslContext(clientCtxHttp11)), // by default protocol is HTTP/1.1
				client3.secure(spec -> spec.sslContext(clientCtxHttp2)).protocol(HttpProtocol.H2),
				client3.secure(spec -> spec.sslContext(clientCtxHttp2)).protocol(HttpProtocol.HTTP11, HttpProtocol.H2)
		};
	}

	static void response(HttpClient.ResponseReceiver<?> responseReceiver) {
		AtomicReference<String> threadLocal = new AtomicReference<>();
		responseReceiver.response()
		                .doOnNext(s -> threadLocal.set(TestThreadLocalHolder.value()))
		                .block(Duration.ofSeconds(5));

		assertThat(threadLocal.get()).isNotNull().isEqualTo("First");
	}

	static void responseConnection(HttpClient.ResponseReceiver<?> responseReceiver) {
		AtomicReference<String> threadLocal = new AtomicReference<>();
		responseReceiver.responseConnection((res, conn) -> conn.inbound().receive().aggregate().asString())
		                .next()
		                .doOnNext(s -> threadLocal.set(TestThreadLocalHolder.value()))
		                .block(Duration.ofSeconds(5));

		assertThat(threadLocal.get()).isNotNull().isEqualTo("First");
	}

	static void responseContent(HttpClient.ResponseReceiver<?> responseReceiver) {
		AtomicReference<String> threadLocal = new AtomicReference<>();
		responseReceiver.responseContent()
		                .aggregate()
		                .asString()
		                .doOnNext(s -> threadLocal.set(TestThreadLocalHolder.value()))
		                .block(Duration.ofSeconds(5));

		assertThat(threadLocal.get()).isNotNull().isEqualTo("First");
	}

	static void responseSingle(HttpClient.ResponseReceiver<?> responseReceiver) {
		AtomicReference<String> threadLocal = new AtomicReference<>();
		responseReceiver.responseSingle((res, bytes) -> bytes.asString())
		                .doOnNext(s -> threadLocal.set(TestThreadLocalHolder.value()))
		                .block(Duration.ofSeconds(5));

		assertThat(threadLocal.get()).isNotNull().isEqualTo("First");
	}

	static void sendRequest(HttpClient client, String expectation) {
		client.post()
		      .uri("/")
		      .send((req, out) ->
		              out.withConnection(conn -> conn.addHandlerLast(TestChannelOutboundHandler.INSTANCE))
		                 .sendString(Mono.just("Test")))
		      .responseContent()
		      .aggregate()
		      .asString()
		      .as(StepVerifier::create)
		      .expectNext(expectation)
		      .expectComplete()
		      .verify(Duration.ofSeconds(5));
	}

	static final class TestChannelOutboundHandler extends ChannelHandlerAdapter {

		static final ChannelHandler INSTANCE = new TestChannelOutboundHandler();

		@Override
		public boolean isSharable() {
			return true;
		}

		@Override
		public Future<Void> write(ChannelHandlerContext ctx, Object msg) {
			try {
				ChannelOperations<?, ?> ops = ChannelOperations.get(ctx.channel());
				String threadLocalValue;
				if (ctx.channel() instanceof Http2StreamChannel) {
					threadLocalValue = getChannelContext(ctx.channel().parent()) != null ? "Error" : "Second";
				}
				else {
					threadLocalValue = ops != null && ops.currentContext() == getChannelContext(ctx.channel()) ? "Second" : "Error";
				}
				TestThreadLocalHolder.value(threadLocalValue);
				if (msg instanceof FullHttpRequest originalRequest) {
					Buffer buffer1;
					try (ContextSnapshot.Scope scope = ContextSnapshot.setAllThreadLocalsFrom(ctx.channel())) {
						buffer1 = ctx.bufferAllocator().copyOf(TestThreadLocalHolder.value(), Charset.defaultCharset());
					}
					Buffer buffer2 = ctx.bufferAllocator().copyOf(TestThreadLocalHolder.value(), Charset.defaultCharset());
					Buffer composite = ctx.bufferAllocator().compose(List.of(originalRequest.payload().send(), buffer1.send(), buffer2.send()));
					FullHttpRequest request = new DefaultFullHttpRequest(originalRequest.protocolVersion(), originalRequest.method(),
							originalRequest.uri(), composite, originalRequest.headers().copy(), HttpHeaders.emptyHeaders());
					request.headers().set(HttpHeaderNames.CONTENT_LENGTH, composite.readableBytes() + "");
					return ctx.write(request);
				}
				else {
					return ctx.write(msg);
				}
			}
			finally {
				TestThreadLocalHolder.reset();
			}
		}
	}

	static final class TestThreadLocalAccessor implements ThreadLocalAccessor<String> {

		static final String KEY = "testContextPropagation";

		@Override
		public Object key() {
			return KEY;
		}

		@Override
		public String getValue() {
			return TestThreadLocalHolder.value();
		}

		@Override
		public void setValue(String value) {
			TestThreadLocalHolder.value(value);
		}

		@Override
		public void reset() {
			TestThreadLocalHolder.reset();
		}
	}

	static final class TestThreadLocalHolder {

		static final ThreadLocal<String> holder = new ThreadLocal<>();

		static void reset() {
			holder.remove();
		}

		static String value() {
			return holder.get();
		}

		static void value(String value) {
			holder.set(value);
		}
	}
}
