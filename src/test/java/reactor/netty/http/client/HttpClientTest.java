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

package reactor.netty.http.client;

import java.io.InputStream;
import java.net.URISyntaxException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.cert.CertificateException;
import java.time.Duration;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import javax.net.ssl.SSLException;

import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.HttpContentDecompressor;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpResponseEncoder;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import io.netty.handler.ssl.util.InsecureTrustManagerFactory;
import io.netty.handler.ssl.util.SelfSignedCertificate;
import io.netty.util.CharsetUtil;
import org.junit.Assert;
import org.junit.Ignore;
import org.junit.Test;
import reactor.core.publisher.DirectProcessor;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.netty.ByteBufFlux;
import reactor.netty.DisposableServer;
import reactor.netty.NettyPipeline;
import reactor.netty.channel.AbortedException;
import reactor.netty.http.server.HttpServer;
import reactor.netty.resources.ConnectionProvider;
import reactor.netty.tcp.ProxyProvider;
import reactor.netty.tcp.TcpServer;
import reactor.test.StepVerifier;
import reactor.util.function.Tuple2;
import reactor.util.function.Tuples;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * @author Stephane Maldini
 * @since 0.6
 */
public class HttpClientTest {

	@Test
	public void abort() {
		DisposableServer x =
				TcpServer.create()
				         .port(0)
				         .handle((in, out) ->
				                 in.receive()
				                   .take(1)
				                   .thenMany(Flux.defer(() ->
				                           out.withConnection(c ->
				                                   c.addHandlerFirst(new HttpResponseEncoder()))
				                              .sendObject(new DefaultFullHttpResponse(HttpVersion.HTTP_1_1,
				                                                                      HttpResponseStatus.ACCEPTED))
				                              .then(Mono.delay(Duration.ofSeconds(2)).then()))))
				         .wiretap()
				         .bindNow();

		ConnectionProvider pool = ConnectionProvider.fixed("test", 1);

		HttpClient.create(pool)
		          .port(x.address().getPort())
		          .wiretap()
		          .get()
		          .uri("/")
		          .responseSingle((r, buf) -> Mono.just(r.status().code()))
		          .log()
		          .block(Duration.ofSeconds(30));

		HttpClient.create(pool)
		          .port(x.address().getPort())
		          .wiretap()
		          .get()
		          .uri("/")
		          .responseContent()
		          .log()
		          .blockLast(Duration.ofSeconds(30));

		HttpClient.create(pool)
		          .port(x.address().getPort())
		          .wiretap()
		          .get()
		          .uri("/")
		          .responseContent()
		          .log()
		          .blockLast(Duration.ofSeconds(30));

		x.disposeNow();

		pool.dispose();
	}

	private DefaultFullHttpResponse response() {
		DefaultFullHttpResponse r =
				new DefaultFullHttpResponse(HttpVersion.HTTP_1_1,
				                            HttpResponseStatus.ACCEPTED);
		r.headers()
		 .set(HttpHeaderNames.CONTENT_LENGTH, 0);
		return r;
	}

	@Test
	public void userIssue() throws Exception {
		final ConnectionProvider pool = ConnectionProvider.fixed("local", 1);
		CountDownLatch latch = new CountDownLatch(3);
		Set<String> localAddresses = ConcurrentHashMap.newKeySet();
		DisposableServer serverContext =
				HttpServer.create()
				          .port(8080)
				          .route(r -> r.post("/",
				                  (req, resp) -> req.receive()
				                                    .asString()
				                                    .flatMap(data -> {
				                                        latch.countDown();
				                                        return resp.status(200)
				                                                   .send();
				                                    })))
				          .wiretap()
				          .bindNow();

		final HttpClient client =
				HttpClient.create(pool)
				          .addressSupplier(serverContext::address)
				          .wiretap();
		Flux.just("1", "2", "3")
		    .concatMap(data ->
		            client.doOnResponse((res, conn) ->
		                    localAddresses.add(conn.channel()
		                                           .localAddress()
		                                           .toString()))
		                  .post()
		                  .uri("/")
		                  .send(ByteBufFlux.fromString(Flux.just(data)))
		                  .responseContent()
		    )
		    .subscribe();


		latch.await();
		pool.dispose();
		serverContext.disposeNow();
		System.out.println("Local Addresses used: " + localAddresses);
	}

	@Test
	@Ignore
	public void pipelined() {
		DisposableServer x =
				TcpServer.create()
				         .host("localhost")
				         .port(0)
				         .handle((in, out) ->
				                 out.withConnection(c -> c.addHandlerFirst(new HttpResponseEncoder()))
				                    .sendObject(Flux.just(response(), response()))
				                    .neverComplete())
				         .wiretap()
				         .bindNow();

		ConnectionProvider pool = ConnectionProvider.fixed("test", 1);

		HttpClient.create(pool)
		          .addressSupplier(x::address)
		          .wiretap()
		          .get()
		          .uri("/")
		          .responseSingle((r, buf) -> buf.thenReturn(r.status().code()))
		          .log()
		          .block(Duration.ofSeconds(30));

		try {
			HttpClient.create(pool)
			          .addressSupplier(x::address)
			          .wiretap()
			          .get()
			          .uri("/")
			          .responseContent()
			          .blockLast(Duration.ofSeconds(30));
		}
		catch (AbortedException ae) {
			return;
		}

		x.disposeNow();
		pool.dispose();
		Assert.fail("Not aborted");
	}

	@Test
	public void testClientReuseIssue405(){
		DisposableServer c = HttpServer.create()
				.port(0)
				.handle((in,out)->out.sendString(Flux.just("hello")))
				.wiretap()
				.bindNow();

		ConnectionProvider pool = ConnectionProvider.fixed("test", 1);
		HttpClient httpClient = HttpClient.create(pool)
				.port(c.address().getPort()).wiretap();

		Mono<String> mono1 = httpClient.get()
				.responseSingle((r, buf) -> buf.asString())
				.log("mono1");

		Mono<String> mono2 = httpClient.get()
                .responseSingle((r, buf) -> buf.asString())
				.log("mono1");

		StepVerifier.create(Flux.zip(mono1,mono2))
				.expectNext(Tuples.of("hello","hello")).expectComplete().verify(Duration.ofSeconds(2000));
		
		c.disposeNow();
		pool.dispose();
	}

	@Test
	public void backpressured() throws Exception {
		Path resource = Paths.get(getClass().getResource("/public").toURI());
		DisposableServer c = HttpServer.create()
		                               .port(0)
		                               .route(routes -> routes.directory("/test", resource))
		                               .wiretap()
		                               .bindNow();

		ByteBufFlux remote =
				HttpClient.create()
				          .port(c.address().getPort())
				          .wiretap()
				          .get()
				          .uri("/test/test.css")
				          .responseContent();

		Mono<String> page = remote.asString()
		                          .limitRate(1)
		                          .reduce(String::concat);

		Mono<String> cancelledPage = remote.asString()
		                                   .take(5)
		                                   .limitRate(1)
		                                   .reduce(String::concat);

		page.block(Duration.ofSeconds(30));
		cancelledPage.block(Duration.ofSeconds(30));
		page.block(Duration.ofSeconds(30));
		c.disposeNow();
	}

	@Test
	public void serverInfiniteClientClose() throws Exception {

		CountDownLatch latch = new CountDownLatch(1);
		DisposableServer c =
				HttpServer.create()
				          .port(0)
				          .handle((req, resp) -> {
				                  req.withConnection(cn -> cn.onDispose(latch::countDown));

				                  return Flux.interval(Duration.ofSeconds(1))
				                             .flatMap(d -> resp.withConnection(cn -> cn.channel()
				                                                                       .config()
				                                                                       .setAutoRead(
				                                                                               true))
				                                               .sendObject(Unpooled.EMPTY_BUFFER)
				                                               .then()
				                                               .doOnSuccess(x -> req.withConnection(
				                                                       cn -> cn.channel()
				                                                               .config()
				                                                               .setAutoRead(false))));
				          })
				          .wiretap()
				          .bindNow();

		HttpClient.create()
		          .port(c.address().getPort())
		          .wiretap()
		          .get()
		          .uri("/")
		          .response()
		          .block();

		latch.await();
		c.disposeNow();
	}

	@Test
	@Ignore
	public void proxy() {
		HttpClient.create()
		          .tcpConfiguration(tcpClient -> tcpClient.proxy(ops -> ops.type(ProxyProvider.Proxy.HTTP)
		                                                                   .host("127.0.0.1")
		                                                                   .port(8888)))
		          .wiretap()
		          .followRedirect(true)
		          .get()
		          .uri("https://projectreactor.io")
		          .responseContent()
		          .retain()
		          .asString()
		          .limitRate(1)
		          .reduce(String::concat)
		          .block(Duration.ofSeconds(30));
	}

	@Test
	@Ignore
	public void nonProxyHosts() {
		HttpClient client =
				HttpClient.create()
				          .tcpConfiguration(tcpClient -> tcpClient.proxy(ops -> ops.type(ProxyProvider.Proxy.HTTP)
				                                                                   .host("127.0.0.1")
				                                                                   .port(8888)
				                                                                   .nonProxyHosts("spring.io")))
				          .wiretap();
		Mono<String> remote1 = client.followRedirect(true)
		                             .get()
		                             .uri("https://projectreactor.io")
		                             .responseContent()
		                             .retain()
		                             .asString()
		                             .limitRate(1)
		                             .reduce(String::concat);
		Mono<String> remote2 = client.followRedirect(true)
		                             .get()
		                             .uri("https://spring.io")
		                             .responseContent()
		                             .retain()
		                             .asString()
		                             .limitRate(1)
		                             .reduce(String::concat);

		StepVerifier.create(remote1)
		            .expectNextMatches(s -> s.contains("<title>Project Reactor</title>"))
		            .expectComplete()
		            .verify(Duration.ofSeconds(30));

		StepVerifier.create(remote2)
		            .expectNextMatches(s -> s.contains("<title>Spring</title>"))
		            .expectComplete()
		            .verify(Duration.ofSeconds(30));
	}

	@Test
	@Ignore
	public void postUpload() {
		InputStream f = getClass().getResourceAsStream("/public/index.html");
		HttpClient.create()
		          .tcpConfiguration(tcpClient -> tcpClient.host("google.com"))
		          .wiretap()
		          .put()
		          .uri("/post")
		          .sendForm((req, form) -> form.multipart(true)
		                                       .file("test", f)
		                                       .attr("att1", "attr2")
		                                       .file("test2", f))
		          .responseSingle((r, buf) -> Mono.just(r.status().code()))
		          .block(Duration.ofSeconds(30));
		Integer res = HttpClient.create()
		                        .tcpConfiguration(tcpClient -> tcpClient.host("google.com"))
		                        .wiretap()
		                        .followRedirect(true)
		                        .get()
		                        .uri("/search")
		                        .responseSingle((r, out) -> Mono.just(r.status().code()))
		                        .log()
		                        .block(Duration.ofSeconds(30));

		assertThat(res).isNotNull();
		if (res != 200) {
			throw new IllegalStateException("test status failed with " + res);
		}
	}

	@Test
	public void simpleTest404() {
		doSimpleTest404(HttpClient.create()
		                          .baseUrl("google.com"));
	}

	@Test
	public void simpleTest404_1() {
		HttpClient client =
				HttpClient.create(ConnectionProvider.fixed("http", 1))
				          .port(80)
				          .tcpConfiguration(tcpClient -> tcpClient.host("google.com"))
				          .wiretap();
		doSimpleTest404(client);
		doSimpleTest404(client);
	}

	private void doSimpleTest404(HttpClient client) {
		Integer res = client.followRedirect(true)
				            .get()
				            .uri("/unsupportedURI")
				            .responseSingle((r, buf) -> Mono.just(r.status().code()))
				            .log()
				            .block();

		assertThat(res).isNotNull();
		if (res != 404) {
			throw new IllegalStateException("test status failed with " + res);
		}
	}

	@Test
	public void disableChunkForced() {
		Tuple2<HttpResponseStatus, String> r =
				HttpClient.newConnection()
				          .tcpConfiguration(tcpClient -> tcpClient.host("google.com"))
				          .wiretap()
				          .chunkedTransfer(false)
				          .request(HttpMethod.GET)
				          .uri("/unsupportedURI")
				          .send(ByteBufFlux.fromString(Flux.just("hello")))
				          .responseSingle((res, conn) -> Mono.just(res.status())
				                                             .zipWith(conn.asString()))
				          .block(Duration.ofSeconds(30));

		assertThat(r).isNotNull();

		Assert.assertTrue(Objects.equals(r.getT1(), HttpResponseStatus.NOT_FOUND));
	}

	@Test
	public void disableChunkForced2() {
		Tuple2<HttpResponseStatus, String> r =
				HttpClient.newConnection()
				          .tcpConfiguration(tcpClient -> tcpClient.host("google.com"))
				          .wiretap()
				          .keepAlive(false)
				          .chunkedTransfer(false)
				          .get()
				          .uri("/unsupportedURI")
				          .responseSingle((res, conn) -> Mono.just(res.status())
				                                             .zipWith(conn.asString()))
				          .block(Duration.ofSeconds(30));

		assertThat(r).isNotNull();

		Assert.assertTrue(Objects.equals(r.getT1(), HttpResponseStatus.NOT_FOUND));
	}

	@Test
	public void simpleClientPooling() {
		ConnectionProvider p = ConnectionProvider.fixed("test", 1);
		AtomicReference<Channel> ch1 = new AtomicReference<>();
		AtomicReference<Channel> ch2 = new AtomicReference<>();

		HttpResponseStatus r =
				HttpClient.create(p)
				          .doOnResponse((res, c) -> ch1.set(c.channel()))
				          .wiretap()
				          .get()
				          .uri("http://google.com/unsupportedURI")
				          .responseSingle((res, buf) -> buf.thenReturn(res.status()))
				          .block(Duration.ofSeconds(30));

		HttpClient.create(p)
		          .doOnResponse((res, c) -> ch2.set(c.channel()))
		          .wiretap()
		          .get()
		          .uri("http://google.com/unsupportedURI")
		          .responseSingle((res, buf) -> buf.thenReturn(res.status()))
		          .block(Duration.ofSeconds(30));

		AtomicBoolean same = new AtomicBoolean();

		same.set(ch1.get() == ch2.get());

		Assert.assertTrue(same.get());

		Assert.assertTrue(Objects.equals(r,HttpResponseStatus.NOT_FOUND));
		p.dispose();
	}

	@Test
	public void disableChunkImplicitDefault() {
		ConnectionProvider p = ConnectionProvider.fixed("test", 1);
		Tuple2<HttpResponseStatus, Channel> r =
				HttpClient.create(p)
				          .tcpConfiguration(tcpClient -> tcpClient.host("google.com")
				                                                  .noSSL())
				          .wiretap()
				          .chunkedTransfer(false)
				          .get()
				          .uri("/unsupportedURI")
				          .responseConnection((res, conn) -> Mono.just(res.status())
				                                                 .delayUntil(s -> conn.inbound().receive())
				                                                 .zipWith(Mono.just(conn.channel())))
				          .blockLast(Duration.ofSeconds(30));

		assertThat(r).isNotNull();

		Channel r2 =
				HttpClient.create(p)
				          .tcpConfiguration(tcpClient -> tcpClient.host("google.com")
				                                                  .noSSL())
				          .wiretap()
				          .chunkedTransfer(false)
				          .get()
				          .uri("/unsupportedURI")
				          .responseConnection((res, conn) -> Mono.just(conn.channel())
				                                                 .delayUntil(s -> conn.inbound().receive()))
				          .blockLast(Duration.ofSeconds(30));

		assertThat(r2).isNotNull();

		Assert.assertSame(r.getT2(), r2);

		Assert.assertTrue(Objects.equals(r.getT1(), HttpResponseStatus.NOT_FOUND));
		p.dispose();
	}

	@Test
	public void contentHeader() {
		ConnectionProvider fixed = ConnectionProvider.fixed("test", 1);
		HttpResponseStatus r =
				HttpClient.create(fixed)
				          .wiretap()
				          .headers(h -> h.add("content-length", "1"))
				          .request(HttpMethod.GET)
				          .uri("http://google.com")
				          .send(ByteBufFlux.fromString(Mono.just(" ")))
				          .responseSingle((res, buf) -> Mono.just(res.status()))
				          .block(Duration.ofSeconds(30));

		HttpClient.create(fixed)
		          .wiretap()
		          .headers(h -> h.add("content-length", "1"))
		          .request(HttpMethod.GET)
		          .uri("http://google.com")
		          .send(ByteBufFlux.fromString(Mono.just(" ")))
		          .responseSingle((res, buf) -> Mono.just(res.status()))
		          .block(Duration.ofSeconds(30));

		Assert.assertTrue(Objects.equals(r, HttpResponseStatus.BAD_REQUEST));
		fixed.dispose();
	}

	@Test
	public void simpleTestHttps() {

		StepVerifier.create(HttpClient.create()
		                              .wiretap()
		                              .get()
		                              .uri("https://developer.chrome.com")
		                              .response((r, buf) -> Mono.just(r.status().code())))
		            .expectNextMatches(status -> status >= 200 && status < 400)
		            .expectComplete()
		            .verify();

		StepVerifier.create(HttpClient.create()
		                              .wiretap()
		                              .get()
		                              .uri("https://developer.chrome.com")
		                              .response((r, buf) -> Mono.just(r.status().code())))
		            .expectNextMatches(status -> status >= 200 && status < 400)
		            .expectComplete()
		            .verify();
	}

	@Test
	public void prematureCancel() {
		DirectProcessor<Void> signal = DirectProcessor.create();
		DisposableServer x =
				TcpServer.create()
				         .host("localhost")
				         .port(0)
				         .handle((in, out) -> {
				             signal.onComplete();
				             return out.withConnection(c -> c.addHandlerFirst(new HttpResponseEncoder()))
				                       .sendObject(Mono.delay(Duration.ofSeconds(2))
				                                       .map(t -> new DefaultFullHttpResponse(HttpVersion.HTTP_1_1,
				                                                                             HttpResponseStatus.PROCESSING)))
				                       .neverComplete();
				         })
				         .wiretap()
				         .bindNow(Duration.ofSeconds(30));

		StepVerifier.create(
				createHttpClientForContext(x).get()
				                             .uri("/")
				                             .responseContent()
				                             .timeout(signal))
				    .verifyError(TimeoutException.class);
	}

	@Test
	public void gzip() {
		String content = "HELLO WORLD";

		DisposableServer c =
				HttpServer.create()
				          .compress(true)
				          .port(0)
				          .handle((req, res) -> res.sendString(Mono.just(content)))
				          .bindNow();

		//verify gzip is negotiated (when no decoder)
		StepVerifier.create(
				HttpClient.create()
				          .port(c.address().getPort())
				          .wiretap()
				          .headers(h -> h.add("Accept-Encoding", "gzip")
				                         .add("Accept-Encoding", "deflate"))
				          .followRedirect(true)
				          .get()
				          .response((r, buf) -> buf.aggregate()
				                                   .asString()
				                                   .zipWith(Mono.just(r.responseHeaders()
				                                                       .get("Content-Encoding", "")))
				                                   .zipWith(Mono.just(r))))
				    .expectNextMatches(tuple -> {
				            String content1 = tuple.getT1().getT1();
				            return !content1.equals(content)
				                   && "gzip".equals(tuple.getT1().getT2());
				    })
				    .expectComplete()
				    .verify(Duration.ofSeconds(30));

		//verify decoder does its job and removes the header
		StepVerifier.create(
				HttpClient.create()
				          .port(c.address().getPort())
				          .wiretap()
				          .followRedirect(true)
				          .headers(h -> h.add("Accept-Encoding", "gzip")
				                         .add("Accept-Encoding", "deflate"))
				          .doOnRequest((req, conn) ->
				                  conn.addHandlerFirst("gzipDecompressor", new HttpContentDecompressor())
				          )
				          .get()
				          .response((r, buf) -> buf.aggregate()
				                                   .asString()
				                                   .zipWith(Mono.just(r.responseHeaders()
				                                                       .get("Content-Encoding", "")))
				                                   .zipWith(Mono.just(r))))
				    .expectNextMatches(tuple -> {
				            String content1 = tuple.getT1().getT1();
				            return content1.equals(content)
				                   && "".equals(tuple.getT1().getT2());
				    })
				    .expectComplete()
				    .verify(Duration.ofSeconds(30));
		c.disposeNow();
	}

	@Test
	public void gzipEnabled() {
		doTestGzip(true);
	}

	@Test
	public void gzipDisabled() {
		doTestGzip(false);
	}

	private void doTestGzip(boolean gzipEnabled) {
		String expectedResponse = gzipEnabled ? "gzip" : "no gzip";
		DisposableServer server =
				HttpServer.create()
				          .port(0)
				          .handle((req,res) -> res.sendString(Mono.just(req.requestHeaders()
				                                                           .get(HttpHeaderNames.ACCEPT_ENCODING,
				                                                                "no gzip"))))
				          .wiretap()
				          .bindNow();
		HttpClient client = HttpClient.create()
		                              .port(server.address().getPort())
		                              .wiretap();
		if (gzipEnabled){
			client = client.compress(true);
		}

		StepVerifier.create(client.get()
		                          .uri("/")
		                          .response((r, buf) -> buf.asString()
		                                                   .elementAt(0)
		                                                   .zipWith(Mono.just(r))))
		            .expectNextMatches(tuple -> expectedResponse.equals(tuple.getT1()))
		            .expectComplete()
		            .verify(Duration.ofSeconds(30));

		server.disposeNow();
	}

	@Test
	public void testUserAgent() {
		DisposableServer c =
				HttpServer.create()
				          .port(0)
				          .handle((req, resp) -> {
				                  Assert.assertTrue("" + req.requestHeaders()
				                                            .get(HttpHeaderNames.USER_AGENT),
				                                   req.requestHeaders()
				                                       .contains(HttpHeaderNames.USER_AGENT) &&
				                                   req.requestHeaders()
				                                      .get(HttpHeaderNames.USER_AGENT)
				                                      .equals(HttpClient.USER_AGENT));

				                  return req.receive().then();
				          })
				          .wiretap()
				          .bindNow();

		HttpClient.create()
		          .port(c.address().getPort())
		          .wiretap()
		          .get()
		          .uri("/")
		          .responseContent()
		          .blockLast();

		c.disposeNow();
	}

	@Test
	public void gettingOptionsDuplicates() {
		HttpClient client = HttpClient.create()
		                              .tcpConfiguration(tcpClient -> tcpClient.host("foo"))
		                              .wiretap()
		                              .port(123)
		                              .compress(true);
		assertThat(client.tcpConfiguration())
		        .isNotSameAs(HttpClient.DEFAULT_TCP_CLIENT)
		        .isNotSameAs(client.tcpConfiguration());
	}

	@Test
	public void sslExchangeRelativeGet() throws CertificateException, SSLException {
		SelfSignedCertificate ssc = new SelfSignedCertificate();
		SslContext sslServer = SslContextBuilder.forServer(ssc.certificate(), ssc.privateKey())
		                                        .build();
		SslContext sslClient = SslContextBuilder.forClient()
		                                        .trustManager(InsecureTrustManagerFactory.INSTANCE)
		                                        .build();

		DisposableServer context =
				HttpServer.create()
				          .secure(ssl -> ssl.sslContext(sslServer))
				          .handle((req, resp) -> resp.sendString(Flux.just("hello ", req.uri())))
				          .wiretap()
				          .bindNow();


		String responseString =
				HttpClient.create()
				          .addressSupplier(context::address)
				          .secure(ssl -> ssl.sslContext(sslClient))
				          .wiretap()
				          .get()
				          .uri("/foo")
				          .responseSingle((res, buf) -> buf.asString(CharsetUtil.UTF_8))
				          .block(Duration.ofMillis(200));
		context.disposeNow();

		assertThat(responseString).isEqualTo("hello /foo");

	}

	@Test
	public void sslExchangeAbsoluteGet() throws CertificateException, SSLException {
		SelfSignedCertificate ssc = new SelfSignedCertificate();
		SslContext sslServer = SslContextBuilder.forServer(ssc.certificate(), ssc.privateKey()).build();
		SslContext sslClient = SslContextBuilder.forClient()
		                                        .trustManager(InsecureTrustManagerFactory.INSTANCE).build();

		DisposableServer context =
				HttpServer.create()
				          .secure(ssl -> ssl.sslContext(sslServer))
				          .handle((req, resp) -> resp.sendString(Flux.just("hello ", req.uri())))
				          .wiretap()
				          .bindNow();

		String responseString = HttpClient.create()
		                                  .addressSupplier(context::address)
		                                  .secure(ssl -> ssl.sslContext(sslClient))
		                                  .wiretap()
		                                  .get()
		                                  .uri("/foo")
		                                  .responseSingle((res, buf) -> buf.asString(CharsetUtil.UTF_8))
		                                  .block();
		context.disposeNow();

		assertThat(responseString).isEqualTo("hello /foo");
	}

	@Test
	public void secureSendFile()
			throws CertificateException, SSLException, URISyntaxException {
		Path largeFile = Paths.get(getClass().getResource("/largeFile.txt").toURI());
		SelfSignedCertificate ssc = new SelfSignedCertificate();
		SslContext sslServer = SslContextBuilder.forServer(ssc.certificate(), ssc.privateKey()).build();
		SslContext sslClient = SslContextBuilder.forClient()
		                                        .trustManager(InsecureTrustManagerFactory.INSTANCE).build();
		AtomicReference<String> uploaded = new AtomicReference<>();

		DisposableServer context =
				HttpServer.create()
				          .port(9090)
				          .secure(ssl -> ssl.sslContext(sslServer))
				          .route(r -> r.post("/upload", (req, resp) ->
				                  req.receive()
				                     .aggregate()
				                     .asString(StandardCharsets.UTF_8)
				                     .log()
				                     .doOnNext(uploaded::set)
				                     .then(resp.status(201).sendString(Mono.just("Received File")).then())))
				          .wiretap()
				          .bindNow();

		Tuple2<String, Integer> response =
				HttpClient.create()
				          .addressSupplier(context::address)
				          .secure(ssl -> ssl.sslContext(sslClient))
				          .wiretap()
				          .post()
				          .uri("/upload")
				          .send((r, out) -> out.sendFile(largeFile))
				          .responseSingle((res, buf) -> buf.asString()
				                                           .zipWith(Mono.just(res.status().code())))
				          .block(Duration.ofSeconds(30));

		context.disposeNow();

		assertThat(response).isNotNull();
		assertThat(response.getT2()).isEqualTo(201);
		assertThat(response.getT1()).isEqualTo("Received File");

		assertThat(uploaded.get())
		                   .startsWith("This is an UTF-8 file that is larger than 1024 bytes. " +
		                           "It contains accents like é.")
		                   .contains("1024 mark here -><- 1024 mark here")
		                   .endsWith("End of File");
	}

	@Test
	public void chunkedSendFile() throws URISyntaxException {
		Path largeFile = Paths.get(getClass().getResource("/largeFile.txt").toURI());
		AtomicReference<String> uploaded = new AtomicReference<>();

		DisposableServer context =
				HttpServer.create()
				          .host("localhost")
				          .route(r -> r.post("/upload", (req, resp) ->
				                  req.receive()
				                    .aggregate()
				                    .asString(StandardCharsets.UTF_8)
				                    .doOnNext(uploaded::set)
				                    .then(resp.status(201)
				                              .sendString(Mono.just("Received File"))
				                              .then())))
				          .wiretap()
				          .bindNow();

		Tuple2<String, Integer> response =
				createHttpClientForContext(context)
				          .post()
				          .uri("/upload")
				          .send((r, out) -> out.sendFile(largeFile))
				          .responseSingle((res, buf) -> buf.asString()
				                                           .zipWith(Mono.just(res.status().code())))
				          .block(Duration.ofSeconds(30));

		context.disposeNow();

		assertThat(response).isNotNull();
		assertThat(response.getT2()).isEqualTo(201);
		assertThat(response.getT1()).isEqualTo("Received File");

		assertThat(uploaded.get())
		                   .startsWith("This is an UTF-8 file that is larger than 1024 bytes. " +
		                           "It contains accents like é.")
		                   .contains("1024 mark here -><- 1024 mark here")
		                   .endsWith("End of File");
	}

	@Test
	public void test() {
		DisposableServer context =
				HttpServer.create()
				          .host("localhost")
				          .route(r -> r.put("/201", (req, res) -> res.addHeader("Content-Length", "0")
				                                                     .status(HttpResponseStatus.CREATED)
				                                                     .sendHeaders())
				                       .put("/204", (req, res) -> res.status(HttpResponseStatus.NO_CONTENT)
				                                                     .sendHeaders())
				                       .get("/200", (req, res) -> res.addHeader("Content-Length", "0")
				                                                     .sendHeaders()))
				          .bindNow();

		createHttpClientForContext(context)
		        .doOnRequest((r, c) -> System.out.println("onReq: "+r))
		        .doAfterRequest((r, c) -> System.out.println("afterReq: "+r))
		        .doOnResponse((r, c) -> System.out.println("onResp: "+r))
		        .doAfterResponse((r, c) -> System.out.println("afterResp: "+r))
		        .put()
		        .uri("/201")
		        .responseContent()
		        .blockLast();

		createHttpClientForContext(context)
				.doOnRequest((r, c) -> System.out.println("onReq: "+r))
				.doAfterRequest((r, c) -> System.out.println("afterReq: "+r))
				.doOnResponse((r, c) -> System.out.println("onResp: "+r))
				.doAfterResponse((r, c) -> System.out.println("afterResp: "+r))
		          .put()
		          .uri("/204")
		          .responseContent()
		          .blockLast(Duration.ofSeconds(30));

		createHttpClientForContext(context)
				.doOnRequest((r, c) -> System.out.println("onReq: "+r))
				.doAfterRequest((r, c) -> System.out.println("afterReq: "+r))
				.doOnResponse((r, c) -> System.out.println("onResp: "+r))
				.doAfterResponse((r, c) -> System.out.println("afterResp: "+r))
		          .get()
		          .uri("/200")
		          .responseContent()
		          .blockLast(Duration.ofSeconds(30));

		context.disposeNow();
	}

	@Test
	public void testDeferredUri() {
		DisposableServer context =
				HttpServer.create()
				          .host("localhost")
				          .route(r -> r.get("/201", (req, res) -> res.addHeader
						          ("Content-Length", "0")
				                                                     .status(HttpResponseStatus.CREATED)
				                                                     .sendHeaders())
				                       .get("/204", (req, res) -> res.status
						                       (HttpResponseStatus.NO_CONTENT)
				                                                     .sendHeaders())
				                       .get("/200", (req, res) -> res.addHeader("Content-Length", "0")
				                                                     .sendHeaders()))
				          .bindNow();

		AtomicInteger i = new AtomicInteger();
		HttpClient.create()
		          .addressSupplier(context::address)
		          .wiretap()
		          .observe((c, s) -> System.out.println(s + "" + c))
		          .get()
		          .uri(Mono.fromCallable(() -> {
		          	switch (i.incrementAndGet()) {
			            case 1: return "/201";
			            case 2: return "/204";
			            case 3: return "/200";
			            default: return null;
		            }
		          }))
		          .responseContent()
		          .repeat(4)
		          .blockLast();

		context.disposeNow();
	}

	@Test
	public void testDeferredHeader() {
		DisposableServer context =
				HttpServer.create()
				          .host("localhost")
				          .route(r -> r.get("/201", (req, res) -> res.addHeader
						          ("Content-Length", "0")
				                                                     .status(HttpResponseStatus.CREATED)
				                                                     .sendHeaders()))
				          .bindNow();

		HttpClient.create()
		          .addressSupplier(context::address)
		          .headersWhen(h -> Mono.just(h.set("test", "test")).delayElement(Duration.ofSeconds(2)))
		          .wiretap()
		          .observe((c, s) -> System.out.println(s + "" + c))
		          .get()
		          .uri("/201")
		          .responseContent()
		          .repeat(4)
		          .blockLast();

		context.disposeNow();
	}

	@Test
	public void testCookie() {
		DisposableServer context = HttpServer.create()
		                                     .host("localhost")
		                                     .route(r -> r.get("/201",
				                                     (req, res) -> res.addHeader("test",
						                                     req.cookies()
						                                        .get("test")
						                                        .stream()
						                                        .findFirst()
						                                        .get()
						                                        .value())
				                                                      .status(HttpResponseStatus.CREATED)
				                                                      .sendHeaders()))
		                                     .bindNow();

		HttpClient.create()
		          .addressSupplier(context::address)
		          .cookie("test", c -> c.setValue("lol"))
		          .wiretap()
		          .get()
		          .uri("/201")
		          .responseContent()
		          .blockLast();

		context.disposeNow();
	}

	@Test
	public void closePool() {
		ConnectionProvider pr = ConnectionProvider.fixed("wstest", 1);
		DisposableServer httpServer =
				HttpServer.create()
				          .port(0)
				          .handle((in, out) ->  out.options(NettyPipeline.SendOptions::flushOnEach)
				                                   .sendString(Mono.just("test")
				                                                   .delayElement(Duration.ofMillis(100))
				                                                   .repeat()))
				          .wiretap()
				          .bindNow();

		Flux<String> ws = HttpClient.create(pr)
		                            .port(httpServer.address().getPort())
		                            .get()
		                            .uri("/")
		                            .responseContent()
		                            .asString();

		StepVerifier.create(
				Flux.range(1, 10)
				    .concatMap(i -> ws.take(2)
				                      .log()))
				    .expectNextSequence(
				            Objects.requireNonNull(Flux.range(1, 20)
				                                       .map(v -> "test")
				                                       .collectList()
				                                       .block()))
				    .expectComplete()
				    .verify();

		httpServer.disposeNow();
		pr.dispose();
	}

	@Test
	public void testIssue303() {
		DisposableServer server =
				HttpServer.create()
				          .port(0)
				          .handle((req, resp) -> resp.sendString(Mono.just("OK")))
				          .wiretap()
				          .bindNow();

		Mono<String> content =
				HttpClient.create()
				          .port(server.address().getPort())
				          .request(HttpMethod.GET)
				          .uri("/")
				          .send(ByteBufFlux.fromInbound(Mono.defer(() -> Mono.just("Hello".getBytes(Charset.defaultCharset())))))
				          .responseContent()
				          .aggregate()
				          .asString();

		StepVerifier.create(content)
		            .expectNextMatches("OK"::equals)
		            .expectComplete()
		            .verify(Duration.ofSeconds(30));

		server.disposeNow();
	}

	private HttpClient createHttpClientForContext(DisposableServer context) {
		return HttpClient.create()
		                 .addressSupplier(context::address);
	}

	@Test
	public void testIssue361() {
		DisposableServer server =
				HttpServer.create()
				          .port(0)
				          .handle((req, res) -> req.receive()
				                                   .aggregate()
				                                   .asString()
				          .flatMap(s -> res.sendString(Mono.just(s))
				                           .then()))
				          .bindNow();

		assertThat(server).isNotNull();

		ConnectionProvider connectionProvider = ConnectionProvider.fixed("test", 1);
		HttpClient client =
				HttpClient.create(connectionProvider)
				          .port(server.address().getPort());

		String response = client.post()
		                        .uri("/")
		                        .send(ByteBufFlux.fromString(Mono.just("test")
		                                         .then(Mono.error(new Exception("error")))))
		                        .responseContent()
		                        .aggregate()
		                        .asString()
		                        .onErrorResume(t -> Mono.just(t.getMessage()))
		                        .block(Duration.ofSeconds(30));

		assertThat(response).isEqualTo("error");

		response = client.post()
		                 .uri("/")
		                 .send(ByteBufFlux.fromString(Mono.just("test")))
		                 .responseContent()
		                 .aggregate()
		                 .asString()
		                 .block(Duration.ofSeconds(30));

		assertThat(response).isEqualTo("test");

		server.disposeNow();
		connectionProvider.dispose();
	}

	@Test
	public void testIssue473() throws Exception {
		SelfSignedCertificate cert = new SelfSignedCertificate();
		SslContextBuilder serverSslContextBuilder =
				SslContextBuilder.forServer(cert.certificate(), cert.privateKey());
		DisposableServer server =
				HttpServer.create()
				          .port(0)
				          .wiretap()
				          .secure(spec -> spec.sslContext(serverSslContextBuilder))
				          .bindNow();

		StepVerifier.create(
				HttpClient.create(ConnectionProvider.newConnection())
				          .secure()
				          .websocket()
				          .uri("wss://" + server.host() + ":" + server.port())
				          .handle((in, out) -> Mono.empty()))
				    .expectErrorMatches(t -> t.getCause() instanceof CertificateException)
				.verify(Duration.ofSeconds(30));

		server.disposeNow();
	}

	public void testIssue407() throws Exception {
		SelfSignedCertificate cert = new SelfSignedCertificate();
		SslContextBuilder serverSslContextBuilder = SslContextBuilder.forServer(cert.certificate(), cert.privateKey());
		DisposableServer server =
				HttpServer.create()
				          .port(0)
				          .secure(spec -> spec.sslContext(serverSslContextBuilder))
				          .handle((req, res) -> res.sendString(Mono.just("test")))
				          .wiretap()
				          .bindNow(Duration.ofSeconds(30));

		SslContextBuilder clientSslContextBuilder =
				SslContextBuilder.forClient()
				                 .trustManager(InsecureTrustManagerFactory.INSTANCE);
		HttpClient client =
				HttpClient.create()
				          .addressSupplier(server::address)
				          .secure(spec -> spec.sslContext(clientSslContextBuilder));

		StepVerifier.create(client.get()
				                  .uri("/1")
				                  .responseContent()
				                  .aggregate()
				                  .asString())
				    .expectNextMatches("test"::equals)
				    .expectComplete()
				    .verify(Duration.ofSeconds(30));

		StepVerifier.create(client.post()
				                  .uri("/2")
				                  .send(ByteBufFlux.fromString(Mono.just("test")))
				                  .responseContent()
				                  .aggregate()
				                  .asString())
				    .expectNextMatches("test"::equals)
				    .expectComplete()
				    .verify(Duration.ofSeconds(30));

		StepVerifier.create(
				client.secure(spec -> spec.sslContext(SslContextBuilder.forClient()))
				      .wiretap()
				      .post()
				      .uri("/3")
				      .responseContent()
				      .aggregate()
				      .asString())
				    .expectError()
				    .verify(Duration.ofSeconds(30));

		server.disposeNow();
	}
}
