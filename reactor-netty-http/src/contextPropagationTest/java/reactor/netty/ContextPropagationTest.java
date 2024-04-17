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
package reactor.netty;

import io.micrometer.context.ContextRegistry;
import io.micrometer.context.ContextSnapshot;
import io.micrometer.context.ThreadLocalAccessor;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.ChannelPromise;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http2.Http2StreamChannel;
import io.netty.handler.ssl.util.InsecureTrustManagerFactory;
import io.netty.handler.ssl.util.SelfSignedCertificate;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import reactor.core.publisher.Hooks;
import reactor.core.publisher.Mono;
import reactor.netty.channel.ChannelOperations;
import reactor.netty.http.Http11SslContextSpec;
import reactor.netty.http.Http2SslContextSpec;
import reactor.netty.http.HttpProtocol;
import reactor.netty.http.client.HttpClient;
import reactor.netty.http.server.HttpServer;
import reactor.netty.resources.ConnectionProvider;
import reactor.test.StepVerifier;

import java.nio.charset.Charset;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static reactor.netty.ReactorNetty.getChannelContext;

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
				          .handle((in, out) -> out.send(in.receive().retain()));
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
					      .send(ByteBufMono.fromString(Mono.just("test")));

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

	static final class TestChannelOutboundHandler extends ChannelOutboundHandlerAdapter {

		static final ChannelHandler INSTANCE = new TestChannelOutboundHandler();

		@Override
		public boolean isSharable() {
			return true;
		}

		@Override
		@SuppressWarnings("FutureReturnValueIgnored")
		public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) {
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
				if (msg instanceof FullHttpRequest) {
					ByteBuf buffer1;
					try (ContextSnapshot.Scope scope = ContextSnapshot.setAllThreadLocalsFrom(ctx.channel())) {
						buffer1 = Unpooled.wrappedBuffer(TestThreadLocalHolder.value().getBytes(Charset.defaultCharset()));
					}
					ByteBuf buffer2 = Unpooled.wrappedBuffer(TestThreadLocalHolder.value().getBytes(Charset.defaultCharset()));
					FullHttpRequest originalRequest = (FullHttpRequest) msg;
					ByteBuf composite = ctx.alloc().compositeBuffer()
							.addComponents(true, originalRequest.content(), buffer1, buffer2);
					FullHttpRequest request = originalRequest.replace(composite);
					request.headers().set(HttpHeaderNames.CONTENT_LENGTH, composite.readableBytes());
					//"FutureReturnValueIgnored" this is deliberate
					ctx.write(request, promise);
				}
				else {
					//"FutureReturnValueIgnored" this is deliberate
					ctx.write(msg, promise);
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
