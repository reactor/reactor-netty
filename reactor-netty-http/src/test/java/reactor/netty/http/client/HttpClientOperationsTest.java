/*
 * Copyright (c) 2017-2025 VMware, Inc. or its affiliates, All Rights Reserved.
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
package reactor.netty.http.client;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerAdapter;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.DefaultHttpHeaders;
import io.netty.handler.codec.http.DefaultLastHttpContent;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.LastHttpContent;
import io.netty.handler.codec.http.cookie.ClientCookieDecoder;
import io.netty.handler.codec.http.cookie.ClientCookieEncoder;
import io.netty.handler.codec.http.cookie.DefaultCookie;
import io.netty.handler.codec.json.JsonObjectDecoder;
import io.netty.handler.ssl.util.InsecureTrustManagerFactory;
import io.netty.pkitesting.CertificateBuilder;
import io.netty.pkitesting.X509Bundle;
import io.netty.util.CharsetUtil;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Named;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import reactor.core.publisher.Mono;
import reactor.netty.BaseHttpTest;
import reactor.netty.ByteBufMono;
import reactor.netty.ConnectionObserver;
import reactor.netty.NettyPipeline;
import reactor.netty.http.Http11SslContextSpec;
import reactor.netty.http.Http2SslContextSpec;
import reactor.netty.http.HttpProtocol;
import reactor.netty.http.logging.ReactorNettyHttpMessageLogFactory;
import reactor.netty.http.server.HttpServer;
import reactor.netty.resources.ConnectionProvider;
import reactor.netty.tcp.SslProvider;
import reactor.util.function.Tuple2;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;
import java.util.function.BiPredicate;
import java.util.function.Consumer;
import java.util.stream.Stream;

import static io.netty.handler.codec.http.HttpVersion.HTTP_1_1;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * This test class verifies basic {@link HttpClient} functionality.
 *
 * @author Simon Baslé
 */
class HttpClientOperationsTest extends BaseHttpTest {
	static X509Bundle ssc;
	static Http11SslContextSpec serverCtx11;
	static Http2SslContextSpec serverCtx2;
	static Http11SslContextSpec clientCtx11;
	static Http2SslContextSpec clientCtx2;

	@BeforeAll
	static void createSelfSignedCertificate() throws Exception {
		ssc = new CertificateBuilder().subject("CN=localhost").setIsCertificateAuthority(true).buildSelfSigned();
		serverCtx11 = Http11SslContextSpec.forServer(ssc.toTempCertChainPem(), ssc.toTempPrivateKeyPem());
		serverCtx2 = Http2SslContextSpec.forServer(ssc.toTempCertChainPem(), ssc.toTempPrivateKeyPem());
		clientCtx11 = Http11SslContextSpec.forClient()
		                                  .configure(builder -> builder.trustManager(InsecureTrustManagerFactory.INSTANCE));
		clientCtx2 = Http2SslContextSpec.forClient()
		                                .configure(builder -> builder.trustManager(InsecureTrustManagerFactory.INSTANCE));
	}

	@Test
	void addDecoderReplaysLastHttp() {
		ByteBuf buf = Unpooled.copiedBuffer("{\"foo\":1}", CharsetUtil.UTF_8);
		EmbeddedChannel channel = new EmbeddedChannel();
		new HttpClientOperations(() -> channel, ConnectionObserver.emptyListener(),
				ClientCookieEncoder.STRICT, ClientCookieDecoder.STRICT, ReactorNettyHttpMessageLogFactory.INSTANCE)
				.addHandler(new JsonObjectDecoder());
		channel.writeInbound(new DefaultLastHttpContent(buf));

		assertThat(channel.pipeline().names()).first().isEqualTo("JsonObjectDecoder$extractor");

		Object content = channel.readInbound();
		assertThat(content).isInstanceOf(ByteBuf.class);
		((ByteBuf) content).release();

		content = channel.readInbound();
		assertThat(content).isInstanceOf(LastHttpContent.class);
		((LastHttpContent) content).release();

		content = channel.readInbound();
		assertThat(content).isNull();
	}

	@Test
	void addNamedDecoderReplaysLastHttp() {
		ByteBuf buf = Unpooled.copiedBuffer("{\"foo\":1}", CharsetUtil.UTF_8);
		EmbeddedChannel channel = new EmbeddedChannel();
		new HttpClientOperations(() -> channel, ConnectionObserver.emptyListener(),
				ClientCookieEncoder.STRICT, ClientCookieDecoder.STRICT, ReactorNettyHttpMessageLogFactory.INSTANCE)
				.addHandler("json", new JsonObjectDecoder());
		channel.writeInbound(new DefaultLastHttpContent(buf));

		assertThat(channel.pipeline().names()).first().isEqualTo("json$extractor");

		Object content = channel.readInbound();
		assertThat(content).isInstanceOf(ByteBuf.class);
		((ByteBuf) content).release();

		content = channel.readInbound();
		assertThat(content).isInstanceOf(LastHttpContent.class);
		((LastHttpContent) content).release();

		content = channel.readInbound();
		assertThat(content).isNull();
	}

	@Test
	void addEncoderReplaysLastHttp() {
		ByteBuf buf = Unpooled.copiedBuffer("{\"foo\":1}", CharsetUtil.UTF_8);
		EmbeddedChannel channel = new EmbeddedChannel();
		new HttpClientOperations(() -> channel, ConnectionObserver.emptyListener(),
				ClientCookieEncoder.STRICT, ClientCookieDecoder.STRICT, ReactorNettyHttpMessageLogFactory.INSTANCE)
				.addHandler(new JsonObjectDecoder());
		channel.writeInbound(new DefaultLastHttpContent(buf));

		assertThat(channel.pipeline().names()).first().isEqualTo("JsonObjectDecoder$extractor");

		Object content = channel.readInbound();
		assertThat(content).isInstanceOf(ByteBuf.class);
		((ByteBuf) content).release();

		content = channel.readInbound();
		assertThat(content).isInstanceOf(LastHttpContent.class);
		((LastHttpContent) content).release();

		content = channel.readInbound();
		assertThat(content).isNull();
	}

	@Test
	void addNamedEncoderReplaysLastHttp() {
		ByteBuf buf = Unpooled.copiedBuffer("{\"foo\":1}", CharsetUtil.UTF_8);
		EmbeddedChannel channel = new EmbeddedChannel();
		new HttpClientOperations(() -> channel, ConnectionObserver.emptyListener(),
				ClientCookieEncoder.STRICT, ClientCookieDecoder.STRICT, ReactorNettyHttpMessageLogFactory.INSTANCE)
				.addHandler("json", new JsonObjectDecoder());
		channel.writeInbound(new DefaultLastHttpContent(buf));

		assertThat(channel.pipeline().names()).first().isEqualTo("json$extractor");

		Object content = channel.readInbound();
		assertThat(content).isInstanceOf(ByteBuf.class);
		((ByteBuf) content).release();

		content = channel.readInbound();
		assertThat(content).isInstanceOf(LastHttpContent.class);
		((LastHttpContent) content).release();

		content = channel.readInbound();
		assertThat(content).isNull();
	}

	@Test
	void testConstructorWithProvidedReplacement_1() {
		EmbeddedChannel channel = new EmbeddedChannel();
		channel.pipeline().addFirst(NettyPipeline.SslHandler, new ChannelHandlerAdapter() {
		});

		HttpClientOperations ops1 = new HttpClientOperations(() -> channel,
				ConnectionObserver.emptyListener(),
				ClientCookieEncoder.STRICT, ClientCookieDecoder.STRICT, ReactorNettyHttpMessageLogFactory.INSTANCE);
		ops1.followRedirectPredicate((req, res) -> true);
		ops1.started = true;
		ops1.retrying = true;
		ops1.redirecting = new RedirectClientException(new DefaultHttpHeaders().add(HttpHeaderNames.LOCATION, "/"),
				HttpResponseStatus.MOVED_PERMANENTLY);
		ops1.authenticating = new HttpClientAuthenticationException();

		HttpClientOperations ops2 = new HttpClientOperations(ops1);

		assertThat(ops1.channel()).isSameAs(ops2.channel());
		assertThat(ops1.started).isSameAs(ops2.started);
		assertThat(ops1.retrying).isSameAs(ops2.retrying);
		assertThat(ops1.redirecting).isSameAs(ops2.redirecting);
		assertThat(ops1.authenticating).isSameAs(ops2.authenticating);
		assertThat(ops1.redirectedFrom).isSameAs(ops2.redirectedFrom);
		assertThat(ops1.isSecure).isSameAs(ops2.isSecure);
		assertThat(ops1.nettyRequest).isSameAs(ops2.nettyRequest);
		assertThat(ops1.responseState).isSameAs(ops2.responseState);
		assertThat(ops1.followRedirectPredicate).isSameAs(ops2.followRedirectPredicate);
		assertThat(ops1.requestHeaders).isSameAs(ops2.requestHeaders);
	}

	@Test
	void testStatus() {
		doTestStatus(HttpResponseStatus.OK);
		doTestStatus(new HttpResponseStatus(200, "Some custom reason phrase for 200 status code"));
	}

	private static void doTestStatus(HttpResponseStatus status) {
		EmbeddedChannel channel = new EmbeddedChannel();
		HttpClientOperations ops = new HttpClientOperations(() -> channel,
				ConnectionObserver.emptyListener(),
				ClientCookieEncoder.STRICT, ClientCookieDecoder.STRICT, ReactorNettyHttpMessageLogFactory.INSTANCE);
		ops.setNettyResponse(new DefaultFullHttpResponse(HTTP_1_1, status, Unpooled.EMPTY_BUFFER));
		assertThat(ops.status().reasonPhrase()).isEqualTo(status.reasonPhrase());
	}

	@ParameterizedTest
	@MethodSource("httpCompatibleProtocols")
	@SuppressWarnings("deprecation")
	void testConstructorWithProvidedReplacement_2(HttpProtocol[] serverProtocols, HttpProtocol[] clientProtocols,
			SslProvider.@Nullable ProtocolSslContextSpec serverCtx, SslProvider.@Nullable ProtocolSslContextSpec clientCtx) throws Exception {
		boolean isH2CUpgrade = serverProtocols.length == 2 && clientProtocols.length == 2 &&
				serverProtocols[0] == HttpProtocol.H2C && clientProtocols[0] == HttpProtocol.H2C;

		ConnectionProvider provider = ConnectionProvider.create("testConstructorWithProvidedReplacement_2", 1);
		try {
			HttpServer server = serverCtx == null ?
					createServer().protocol(serverProtocols) :
					createServer().protocol(serverProtocols).secure(spec -> spec.sslContext(serverCtx));

			disposableServer =
					server.httpRequestDecoder(spec -> spec.h2cMaxContentLength(256))
					      .handle((req, res) -> res.sendString(Mono.just("testConstructorWithProvidedReplacement_2")))
					      .bindNow();

			HttpClient client = clientCtx == null ?
					createClient(disposableServer.port()).protocol(clientProtocols) :
					createClient(disposableServer.port()).protocol(clientProtocols).secure(spec -> spec.sslContext(clientCtx));

			AtomicReference<@Nullable HttpClientRequest> request = new AtomicReference<>();
			AtomicReference<@Nullable Channel> requestChannel = new AtomicReference<>();
			AtomicReference<@Nullable Channel> responseChannel = new AtomicReference<>();
			AtomicReference<@Nullable ConnectionObserver> requestListener = new AtomicReference<>();
			AtomicReference<@Nullable ConnectionObserver> responseListener = new AtomicReference<>();
			CountDownLatch terminated1 = new CountDownLatch(1);
			Tuple2<String, HttpClientResponse> response =
					client.doAfterRequest((req, conn) -> {
					          request.set(req);
					          requestChannel.set(conn.channel());
					          requestListener.set(((HttpClientOperations) req).listener());
					          conn.onTerminate().subscribe(null, null, terminated1::countDown);
					      })
					      .doAfterResponseSuccess((res, conn) -> {
					          responseChannel.set(conn.channel());
					          responseListener.set(((HttpClientOperations) res).listener());
					      })
					      .headers(headers -> headers.set("test1", "testConstructorWithProvidedReplacement_2"))
					      .cookie(new DefaultCookie("test1", "testConstructorWithProvidedReplacement_2"))
					      .responseTimeout(Duration.ofSeconds(10))
					      .httpMessageLogFactory(new ReactorNettyHttpMessageLogFactory())
					      .post()
					      .uri("/test1")
					      .send(ByteBufMono.fromString(Mono.just("testConstructorWithProvidedReplacement_2")))
					      .responseSingle((res, bytes) -> bytes.asString().zipWith(Mono.just(res)))
					      .block(Duration.ofSeconds(5));

			assertThat(terminated1.await(5, TimeUnit.SECONDS)).isTrue();
			assertThat(response).isNotNull();
			assertThat(response.getT1()).isEqualTo("testConstructorWithProvidedReplacement_2");
			if (isH2CUpgrade) {
				assertThat(requestListener.get()).isNotSameAs(responseListener.get());
			}
			else {
				assertThat(requestListener.get()).isSameAs(responseListener.get());
			}
			checkRequest(request.get(), response.getT2(), requestChannel.get(), responseChannel.get(), isH2CUpgrade, false);

			request.set(null);
			requestChannel.set(null);
			responseChannel.set(null);
			requestListener.set(null);
			responseListener.set(null);
			CountDownLatch terminated2 = new CountDownLatch(1);
			response =
					client.doAfterRequest((req, conn) -> {
					          request.set(req);
					          requestChannel.set(conn.channel());
					          requestListener.set(((HttpClientOperations) req).listener());
					          conn.onTerminate().subscribe(null, null, terminated2::countDown);
					      })
					      .doAfterResponseSuccess((res, conn) -> {
					          responseChannel.set(conn.channel());
					          responseListener.set(((HttpClientOperations) res).listener());
					      })
					      .headers(headers -> headers.set("test2", "testConstructorWithProvidedReplacement_2"))
					      .cookie(new DefaultCookie("test2", "testConstructorWithProvidedReplacement_2"))
					      .responseTimeout(Duration.ofSeconds(5))
					      .httpMessageLogFactory(new ReactorNettyHttpMessageLogFactory())
					      .delete()
					      .uri("/test2")
					      .responseSingle((res, bytes) -> bytes.asString().zipWith(Mono.just(res)))
					      .block(Duration.ofSeconds(5));

			assertThat(terminated2.await(5, TimeUnit.SECONDS)).isTrue();
			assertThat(response).isNotNull();
			assertThat(response.getT1()).isEqualTo("testConstructorWithProvidedReplacement_2");
			assertThat(requestListener.get()).isSameAs(responseListener.get());
			checkRequest(request.get(), response.getT2(), requestChannel.get(), responseChannel.get(), false, false);
		}
		finally {
			provider.disposeLater()
			        .block(Duration.ofSeconds(5));
		}
	}

	@ParameterizedTest
	@MethodSource("httpCompatibleProtocols")
	@SuppressWarnings("deprecation")
	void testConstructorWithProvidedReplacement_3(HttpProtocol[] serverProtocols, HttpProtocol[] clientProtocols,
			SslProvider.@Nullable ProtocolSslContextSpec serverCtx, SslProvider.@Nullable ProtocolSslContextSpec clientCtx) throws Exception {
		boolean isH2CUpgrade = serverProtocols.length == 2 && clientProtocols.length == 2 &&
				serverProtocols[0] == HttpProtocol.H2C && clientProtocols[0] == HttpProtocol.H2C;

		ConnectionProvider provider = ConnectionProvider.create("testConstructorWithProvidedReplacement_3", 1);
		try {
			HttpServer server = serverCtx == null ?
					createServer().protocol(serverProtocols) :
					createServer().protocol(serverProtocols).secure(spec -> spec.sslContext(serverCtx));

			disposableServer =
					server.route(r -> r.get("/1", (req, res) -> res.sendRedirect("/3"))
					                   .get("/2", (req, res) -> res.sendRedirect("/4"))
					                   .get("/3", (req, res) -> res.sendString(Mono.just("testConstructorWithProvidedReplacement_3")))
					                   .get("/4", (req, res) -> res.sendString(Mono.just("testConstructorWithProvidedReplacement_3"))))
					      .bindNow();

			HttpClient client = clientCtx == null ?
					createClient(disposableServer.port()).protocol(clientProtocols) :
					createClient(disposableServer.port()).protocol(clientProtocols).secure(spec -> spec.sslContext(clientCtx));

			AtomicReference<@Nullable HttpClientRequest> request = new AtomicReference<>();
			AtomicReference<@Nullable HttpClientResponse> response = new AtomicReference<>();
			AtomicReference<@Nullable Channel> requestChannel = new AtomicReference<>();
			AtomicReference<@Nullable Channel> responseChannel = new AtomicReference<>();
			AtomicReference<@Nullable ConnectionObserver> requestListener = new AtomicReference<>();
			AtomicReference<@Nullable ConnectionObserver> responseListener = new AtomicReference<>();
			assertThat(followRedirect(client, request, response, requestChannel, responseChannel,
					requestListener, responseListener, null, null))
					.isNotNull()
					.isEqualTo("testConstructorWithProvidedReplacement_3");
			if (isH2CUpgrade) {
				assertThat(requestListener.get()).isNotSameAs(responseListener.get());
			}
			else {
				assertThat(requestListener.get()).isSameAs(responseListener.get());
			}
			checkRequest(request.get(), response.get(), requestChannel.get(), responseChannel.get(), isH2CUpgrade, true);

			request.set(null);
			response.set(null);
			requestChannel.set(null);
			responseChannel.set(null);
			requestListener.set(null);
			responseListener.set(null);
			assertThat(followRedirect(client, request, response, requestChannel, responseChannel,
					requestListener, responseListener, (req, res) -> res.status().code() == 302, null, null))
					.isNotNull()
					.isEqualTo("testConstructorWithProvidedReplacement_3");
			assertThat(requestListener.get()).isSameAs(responseListener.get());
			checkRequest(request.get(), response.get(), requestChannel.get(), responseChannel.get(), false, true);

			request.set(null);
			response.set(null);
			requestChannel.set(null);
			responseChannel.set(null);
			requestListener.set(null);
			responseListener.set(null);
			assertThat(followRedirect(client, request, response, requestChannel, responseChannel,
					requestListener, responseListener, (req) -> {}, null))
					.isNotNull()
					.isEqualTo("testConstructorWithProvidedReplacement_3");
			if (isH2CUpgrade) {
				assertThat(requestListener.get()).isNotSameAs(responseListener.get());
			}
			else {
				assertThat(requestListener.get()).isSameAs(responseListener.get());
			}
			checkRequest(request.get(), response.get(), requestChannel.get(), responseChannel.get(), isH2CUpgrade, true);

			request.set(null);
			response.set(null);
			requestChannel.set(null);
			responseChannel.set(null);
			requestListener.set(null);
			responseListener.set(null);
			assertThat(followRedirect(client, request, response, requestChannel, responseChannel,
					requestListener, responseListener, (req, res) -> res.status().code() == 302, (req) -> {}, null))
					.isNotNull()
					.isEqualTo("testConstructorWithProvidedReplacement_3");
			assertThat(requestListener.get()).isSameAs(responseListener.get());
			checkRequest(request.get(), response.get(), requestChannel.get(), responseChannel.get(), false, true);

			request.set(null);
			response.set(null);
			requestChannel.set(null);
			responseChannel.set(null);
			requestListener.set(null);
			responseListener.set(null);
			assertThat(followRedirect(client, request, response, requestChannel, responseChannel,
					requestListener, responseListener, null, (h, req) -> {}))
					.isNotNull()
					.isEqualTo("testConstructorWithProvidedReplacement_3");
			if (isH2CUpgrade) {
				assertThat(requestListener.get()).isNotSameAs(responseListener.get());
			}
			else {
				assertThat(requestListener.get()).isSameAs(responseListener.get());
			}
			checkRequest(request.get(), response.get(), requestChannel.get(), responseChannel.get(), isH2CUpgrade, true);

			request.set(null);
			response.set(null);
			requestChannel.set(null);
			responseChannel.set(null);
			requestListener.set(null);
			responseListener.set(null);
			assertThat(followRedirect(client, request, response, requestChannel, responseChannel,
					requestListener, responseListener, (req, res) -> res.status().code() == 302, null, (h, req) -> {}))
					.isNotNull()
					.isEqualTo("testConstructorWithProvidedReplacement_3");
			assertThat(requestListener.get()).isSameAs(responseListener.get());
			checkRequest(request.get(), response.get(), requestChannel.get(), responseChannel.get(), false, true);
		}
		finally {
			provider.disposeLater()
			        .block(Duration.ofSeconds(5));
		}
	}

	private static String followRedirect(HttpClient originalClient, AtomicReference<@Nullable HttpClientRequest> request,
			AtomicReference<@Nullable HttpClientResponse> response, AtomicReference<@Nullable Channel> requestChannel,
			AtomicReference<@Nullable Channel> responseChannel, AtomicReference<@Nullable ConnectionObserver> requestListener,
			AtomicReference<@Nullable ConnectionObserver> responseListener, @Nullable Consumer<HttpClientRequest> redirectRequestConsumer,
			@Nullable BiConsumer<HttpHeaders, HttpClientRequest> redirectRequestBiConsumer) {
		HttpClient client = redirectRequestBiConsumer == null ?
				originalClient.followRedirect(true, redirectRequestConsumer) :
				originalClient.followRedirect(true, redirectRequestBiConsumer);
		return client.doAfterRequest((req, conn) -> {
		                 if (request.get() == null) {
		                     requestChannel.set(conn.channel());
		                     requestListener.set(((HttpClientOperations) req).listener());
		                     request.set(req);
		                 }
		             })
		             .doOnRedirect((res, conn) -> {
		                 if (response.get() == null) {
		                     responseChannel.set(conn.channel());
		                     responseListener.set(((HttpClientOperations) res).listener());
		                     response.set(res);
		                 }
		             })
		             .get()
		             .uri("/1")
		             .responseSingle((res, bytes) -> bytes.asString())
		             .block(Duration.ofSeconds(5));
	}

	@SuppressWarnings("FutureReturnValueIgnored")
	private static String followRedirect(HttpClient originalClient, AtomicReference<@Nullable HttpClientRequest> request,
			AtomicReference<@Nullable HttpClientResponse> response, AtomicReference<@Nullable Channel> requestChannel,
			AtomicReference<@Nullable Channel> responseChannel, AtomicReference<@Nullable ConnectionObserver> requestListener,
			AtomicReference<@Nullable ConnectionObserver> responseListener, BiPredicate<HttpClientRequest, HttpClientResponse> predicate,
			@Nullable Consumer<HttpClientRequest> redirectRequestConsumer,
			@Nullable BiConsumer<HttpHeaders, HttpClientRequest> redirectRequestBiConsumer) throws Exception {
		HttpClient client = redirectRequestBiConsumer == null ?
				originalClient.followRedirect(predicate, redirectRequestConsumer) :
				originalClient.followRedirect(predicate, redirectRequestBiConsumer);
		CountDownLatch closed = new CountDownLatch(1);
		String responseStr =
				client.doAfterRequest((req, conn) -> {
				          if (request.get() == null) {
				              requestChannel.set(conn.channel());
				              requestListener.set(((HttpClientOperations) req).listener());
				              request.set(req);
				          }
				      })
				      .doOnRedirect((res, conn) -> {
				          if (response.get() == null) {
				              responseChannel.set(conn.channel());
				              responseListener.set(((HttpClientOperations) res).listener());
				              response.set(res);
				          }
				      })
				      .doAfterResponseSuccess((res, conn) -> {
				          if (conn.channel().parent() != null) {
				              // Suppressing FutureReturnValueIgnored is deliberate
				              conn.channel().parent().close();
				              conn.channel().parent().closeFuture().addListener(f -> closed.countDown());
				          }
				          else {
				              closed.countDown();
				          }
				      })
				      .get()
				      .uri("/2")
				      .responseSingle((res, bytes) -> bytes.asString())
				      .block(Duration.ofSeconds(5));

		assertThat(closed.await(5, TimeUnit.SECONDS)).isTrue();
		return responseStr;
	}

	private static void checkRequest(HttpClientRequest request, HttpClientResponse response,
			Channel requestChannel, Channel responseChannel, boolean upgrade, boolean redirecting) {
		HttpClientOperations req = (HttpClientOperations) request;
		HttpClientOperations res = (HttpClientOperations) response;
		assertThat(req.retrying).isSameAs(res.retrying);
		assertThat(req.redirectedFrom).isSameAs(res.redirectedFrom);
		assertThat(req.redirectRequestConsumer).isSameAs(res.redirectRequestConsumer);
		assertThat(req.previousRequestHeaders).isSameAs(res.previousRequestHeaders);
		assertThat(req.redirectRequestBiConsumer).isSameAs(res.redirectRequestBiConsumer);
		assertThat(req.isSecure).isSameAs(res.isSecure);
		assertThat(req.nettyRequest).isSameAs(res.nettyRequest);
		assertThat(req.followRedirectPredicate).isSameAs(res.followRedirectPredicate);
		assertThat(req.authenticationPredicate).isSameAs(res.authenticationPredicate);
		assertThat(req.requestHeaders).isSameAs(res.requestHeaders);
		assertThat(req.cookieEncoder).isSameAs(res.cookieEncoder);
		assertThat(req.cookieDecoder).isSameAs(res.cookieDecoder);
		assertThat(req.cookieList).isSameAs(res.cookieList);
		assertThat(req.resourceUrl).isSameAs(res.resourceUrl);
		assertThat(req.path).isSameAs(res.path);
		assertThat(req.responseTimeout).isSameAs(res.responseTimeout);
		assertThat(req.is100Continue).isSameAs(res.is100Continue);
		assertThat(req.trailerHeaders).isSameAs(res.trailerHeaders);
		if (upgrade) {
			assertThat(requestChannel).isNotSameAs(responseChannel);
			assertThat(requestChannel).isSameAs(responseChannel.parent());
			assertThat(req.asShortText()).isNotSameAs(res.asShortText());
			assertThat(req.started).isNotSameAs(res.started);
			if  (redirecting) {
				assertThat(req.redirecting).isNotSameAs(res.redirecting);
			}
			else {
				assertThat(req.redirecting).isSameAs(res.redirecting);
			}
			assertThat(req.authenticating).isSameAs(res.authenticating);
			assertThat(req.responseState).isNotSameAs(res.responseState);
			assertThat(req.version).isNotSameAs(res.version);
		}
		else {
			assertThat(requestChannel).isSameAs(responseChannel);
			assertThat(req.asShortText()).isSameAs(res.asShortText());
			assertThat(req.started).isSameAs(res.started);
			assertThat(req.redirecting).isSameAs(res.redirecting);
			assertThat(req.authenticating).isSameAs(res.authenticating);
			assertThat(req.responseState).isSameAs(res.responseState);
			assertThat(req.version).isSameAs(res.version);
		}
	}

	@ParameterizedTest
	@MethodSource("httpCompatibleProtocols")
	void testConstructorWithProvidedAuthentication(HttpProtocol[] serverProtocols, HttpProtocol[] clientProtocols,
			SslProvider.@Nullable ProtocolSslContextSpec serverCtx, SslProvider.@Nullable ProtocolSslContextSpec clientCtx) {
		ConnectionProvider provider = ConnectionProvider.create("testConstructorWithProvidedAuthentication", 1);
		try {
			HttpServer server = serverCtx == null ?
					createServer().protocol(serverProtocols) :
					createServer().protocol(serverProtocols).secure(spec -> spec.sslContext((SslProvider.GenericSslContextSpec<?>) serverCtx));

			disposableServer =
					server.route(r -> r.get("/protected", (req, res) -> {
					                      String authHeader = req.requestHeaders().get(HttpHeaderNames.AUTHORIZATION);
					                      if (authHeader == null || !authHeader.equals("Bearer test-token")) {
					                          return res.status(HttpResponseStatus.UNAUTHORIZED).send();
					                      }
					                      return res.sendString(Mono.just("testConstructorWithProvidedAuthentication"));
					                  }))
					      .bindNow();

			HttpClient client = clientCtx == null ?
					createClient(disposableServer.port()).protocol(clientProtocols) :
					createClient(disposableServer.port()).protocol(clientProtocols).secure(spec -> spec.sslContext((SslProvider.GenericSslContextSpec<?>) clientCtx));

			AtomicReference<@Nullable HttpClientRequest> request = new AtomicReference<>();
			AtomicReference<@Nullable HttpClientResponse> response = new AtomicReference<>();
			AtomicReference<@Nullable Channel> requestChannel = new AtomicReference<>();
			AtomicReference<@Nullable Channel> responseChannel = new AtomicReference<>();
			AtomicReference<@Nullable ConnectionObserver> requestListener = new AtomicReference<>();
			AtomicReference<@Nullable ConnectionObserver> responseListener = new AtomicReference<>();
			String result = httpAuthentication(client, request, response, requestChannel, responseChannel,
					requestListener, responseListener);
			assertThat(result).isNotNull().isEqualTo("testConstructorWithProvidedAuthentication");
			assertThat(requestListener.get()).isSameAs(responseListener.get());
			checkRequest(request.get(), response.get(), requestChannel.get(), responseChannel.get(), false, false);
		}
		finally {
			provider.disposeLater()
			        .block(Duration.ofSeconds(5));
		}
	}

	private static @Nullable String httpAuthentication(HttpClient originalClient, AtomicReference<@Nullable HttpClientRequest> request,
			AtomicReference<@Nullable HttpClientResponse> response, AtomicReference<@Nullable Channel> requestChannel,
			AtomicReference<@Nullable Channel> responseChannel, AtomicReference<@Nullable ConnectionObserver> requestListener,
			AtomicReference<@Nullable ConnectionObserver> responseListener) {
		HttpClient client = originalClient.httpAuthentication(
				(req, res) -> res.status().equals(HttpResponseStatus.UNAUTHORIZED),
				(req, addr) -> {
					req.header(HttpHeaderNames.AUTHORIZATION, "Bearer test-token");
				});
		return client.doAfterRequest((req, conn) -> {
		                 requestChannel.set(conn.channel());
		                 requestListener.set(((HttpClientOperations) req).listener());
		                 request.set(req);
		             })
		             .doOnResponse((res, conn) -> {
		                 responseChannel.set(conn.channel());
		                 responseListener.set(((HttpClientOperations) res).listener());
		                 response.set(res);
		             })
		             .get()
		             .uri("/protected")
		             .responseSingle((res, bytes) -> bytes.asString())
		             .block(Duration.ofSeconds(5));
	}

	static Stream<Arguments> httpCompatibleProtocols() {
		return Stream.of(
				Arguments.of(new HttpProtocol[]{HttpProtocol.HTTP11}, new HttpProtocol[]{HttpProtocol.HTTP11}, null, null),
				Arguments.of(new HttpProtocol[]{HttpProtocol.HTTP11}, new HttpProtocol[]{HttpProtocol.H2C, HttpProtocol.HTTP11}, null, null),
				Arguments.of(new HttpProtocol[]{HttpProtocol.HTTP11}, new HttpProtocol[]{HttpProtocol.HTTP11},
						Named.of("Http11SslContextSpec", serverCtx11), Named.of("Http11SslContextSpec", clientCtx11)),
				Arguments.of(new HttpProtocol[]{HttpProtocol.HTTP11}, new HttpProtocol[]{HttpProtocol.H2, HttpProtocol.HTTP11},
						Named.of("Http11SslContextSpec", serverCtx11), Named.of("Http11SslContextSpec", clientCtx11)),
				Arguments.of(new HttpProtocol[]{HttpProtocol.H2}, new HttpProtocol[]{HttpProtocol.H2},
						Named.of("Http2SslContextSpec", serverCtx2), Named.of("Http2SslContextSpec", clientCtx2)),
				Arguments.of(new HttpProtocol[]{HttpProtocol.H2}, new HttpProtocol[]{HttpProtocol.H2, HttpProtocol.HTTP11},
						Named.of("Http2SslContextSpec", serverCtx2), Named.of("Http2SslContextSpec", clientCtx2)),
				Arguments.of(new HttpProtocol[]{HttpProtocol.H2, HttpProtocol.HTTP11}, new HttpProtocol[]{HttpProtocol.HTTP11},
						Named.of("Http2SslContextSpec", serverCtx2), Named.of("Http11SslContextSpec", clientCtx11)),
				Arguments.of(new HttpProtocol[]{HttpProtocol.H2, HttpProtocol.HTTP11}, new HttpProtocol[]{HttpProtocol.H2},
						Named.of("Http2SslContextSpec", serverCtx2), Named.of("Http2SslContextSpec", clientCtx2)),
				Arguments.of(new HttpProtocol[]{HttpProtocol.H2, HttpProtocol.HTTP11}, new HttpProtocol[]{HttpProtocol.H2, HttpProtocol.HTTP11},
						Named.of("Http2SslContextSpec", serverCtx2), Named.of("Http2SslContextSpec", clientCtx2)),
				Arguments.of(new HttpProtocol[]{HttpProtocol.H2C}, new HttpProtocol[]{HttpProtocol.H2C}, null, null),
				Arguments.of(new HttpProtocol[]{HttpProtocol.H2C, HttpProtocol.HTTP11}, new HttpProtocol[]{HttpProtocol.HTTP11}, null, null),
				Arguments.of(new HttpProtocol[]{HttpProtocol.H2C, HttpProtocol.HTTP11}, new HttpProtocol[]{HttpProtocol.H2C}, null, null),
				Arguments.of(new HttpProtocol[]{HttpProtocol.H2C, HttpProtocol.HTTP11}, new HttpProtocol[]{HttpProtocol.H2C, HttpProtocol.HTTP11}, null, null)
		);
	}
}
