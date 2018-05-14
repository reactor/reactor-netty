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

package reactor.ipc.netty.http.client;

import java.io.InputStream;
import java.net.InetSocketAddress;
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
import java.util.concurrent.atomic.AtomicReference;
import javax.net.ssl.SSLException;

import io.netty.buffer.ByteBuf;
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
import reactor.ipc.netty.FutureMono;
import reactor.ipc.netty.ByteBufFlux;
import reactor.ipc.netty.DisposableServer;
import reactor.ipc.netty.channel.AbortedException;
import reactor.ipc.netty.http.server.HttpServer;
import reactor.ipc.netty.resources.PoolResources;
import reactor.ipc.netty.tcp.ProxyProvider;
import reactor.ipc.netty.tcp.TcpServer;
import reactor.test.StepVerifier;
import reactor.util.function.Tuple2;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * @author Stephane Maldini
 * @since 0.6
 */
public class HttpClientTest {

	@Test
	public void abort() {
		DisposableServer x = TcpServer.create()
		                        .port(0)
		                        .handler((in, out) -> in.receive()
		                                                     .take(1)
		                                                     .thenMany(Flux.defer(() ->
						                                                     out.withConnection(c ->
								                                                     c.addHandlerFirst(new HttpResponseEncoder()))
						                                                        .sendObject(new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.ACCEPTED))
						                                                        .then(Mono.delay(Duration.ofSeconds(2)).then()))
		                                                     )
		                          )
		                        .wiretap()
		                        .bindNow();

		PoolResources pool = PoolResources.fixed("test", 1);

		HttpClient.prepare(pool)
		          .port(x.address().getPort())
		          .wiretap()
		          .get()
		          .uri("/")
		          .responseSingle((r, buf) -> Mono.just(r.status().code()))
		          .log()
		          .block(Duration.ofSeconds(30));

		ByteBuf resp =
				HttpClient.prepare(pool)
				          .port(x.address().getPort())
				          .wiretap()
				          .get()
				          .uri("/")
				          .responseContent()
				          .log()
				          .blockLast(Duration.ofSeconds(30));

		resp = HttpClient.prepare(pool)
		                 .port(x.address().getPort())
		                 .wiretap()
		                 .get()
		                 .uri("/")
		                 .responseContent()
		                 .log()
		                 .blockLast(Duration.ofSeconds(30));

		x.dispose();

		pool.dispose();
	}

	DefaultFullHttpResponse response() {
		DefaultFullHttpResponse r = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1,
				HttpResponseStatus.ACCEPTED);
		r.headers()
		 .set(HttpHeaderNames.CONTENT_LENGTH, 0);
		return r;
	}

	@Test
	@Ignore
	public void userIssue() throws Exception {
		final PoolResources pool = PoolResources.fixed("local", 1);
		CountDownLatch latch = new CountDownLatch(3);
		Set<String> localAddresses = ConcurrentHashMap.newKeySet();
		DisposableServer serverContext = HttpServer.create()
		                                     .port(8080)
		                                     .router(r -> r.post("/",
				                                       (req, resp) -> req.receive()
				                                                         .asString()
				                                                         .flatMap(data -> {
					                                                         latch.countDown();
					                                                         return resp.status(
							                                                         200)
					                                                                    .send();
				                                                         })))
		                                     .wiretap()
		                                     .bindNow();

		final HttpClient client =
				HttpClient.prepare(pool)
				          .addressSupplier(() -> new InetSocketAddress(8080))
				          .wiretap();
		Flux.just("1", "2", "3")
		    .concatMap(data -> client.post()
		                             .uri("/")
		                             .send(ByteBufFlux.fromString(Flux.just(data)))
		                             .response((res, buf) -> {
		                                       buf.subscribe();
		                                       localAddresses.add(res.channel()
		                                                             .localAddress()
		                                                             .toString());
		                                       return Mono.empty();
		                             }));

		latch.await();
		pool.dispose();
		serverContext.dispose();
		System.out.println("Local Addresses used: " + localAddresses);
	}

	@Test
	@Ignore
	public void pipelined() {
		DisposableServer x = TcpServer.create()
		                        .host("localhost")
		                        .port(0)
		                        .handler((in, out) -> out.withConnection(c -> c.addHandlerFirst(new
				                          HttpResponseEncoder()))
		                                                      .sendObject(Flux.just(
				                                                      response(),
				                                                      response()))
		                                                      .neverComplete())
		                        .wiretap()
		                        .bindNow();

		PoolResources pool = PoolResources.fixed("test", 1);

		HttpClient.prepare(pool)
		          .port(x.address().getPort())
		          .tcpConfiguration(tcpClient -> tcpClient.host("localhost"))
		          .wiretap()
		          .get()
		          .uri("/")
		          .responseSingle((r, buf) -> Mono.just(r.status().code()))
		          .log()
		          .block(Duration.ofSeconds(30));

		try {
			HttpClient.prepare(pool)
			          .port(x.address().getPort())
			          .tcpConfiguration(tcpClient -> tcpClient.host("localhost"))
			          .wiretap()
			          .get()
			          .uri("/")
			          .responseContent()
			          .blockLast(Duration.ofSeconds(30));
		}
		catch (AbortedException ae) {
			return;
		}

		x.dispose();
		pool.dispose();
		Assert.fail("Not aborted");
	}

	@Test
	public void backpressured() throws Exception {
		Path resource = Paths.get(getClass().getResource("/public").toURI());
		DisposableServer c = HttpServer.create()
		                         .port(0)
		                         .router(routes -> routes.directory("/test", resource))
		                         .wiretap()
		                         .bindNow();

		ByteBufFlux remote =
				HttpClient.prepare()
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
		c.dispose();
	}

	@Test
	@Ignore
	public void serverInfiniteClientClose() throws Exception {

		CountDownLatch latch = new CountDownLatch(1);
		DisposableServer c = HttpServer.create()
		                         .port(0)
		                         .handler((req, resp) -> {
			                           req.withConnection(
			                              cn -> cn.onDispose(latch::countDown));

			                           return Flux.interval(Duration.ofSeconds(1))
			                                      .flatMap(d ->
				                                      resp.withConnection(cn ->
						                                      cn.channel()
						                                        .config()
						                                        .setAutoRead(true))
				                                          .sendObject(Unpooled.EMPTY_BUFFER)
				                                                 .then()
				                                                 .doOnSuccess(x ->
						                                                 req.withConnection(cn ->
								                                                 cn.channel()
								                                                   .config()
								                                                   .setAutoRead(false))));
		})
		                         .wiretap()
		                         .bindNow();

		Object remote =
				HttpClient.prepare()
				          .port(c.address().getPort())
				          .wiretap()
				          .get()
				          .uri("/")
				          .responseContent()
				          .blockLast();

		latch.await();
		c.dispose();
	}

	@Test
	@Ignore
	public void proxy() {
		String remote =
				HttpClient.prepare()
				          .tcpConfiguration(tcpClient -> tcpClient.proxy(ops -> ops.type(ProxyProvider.Proxy.HTTP)
				                                                                   .host("127.0.0.1")
				                                                                   .port(8888)))
				          .wiretap()
				          .followRedirect()
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
				HttpClient.prepare()
				          .tcpConfiguration(tcpClient -> tcpClient.proxy(ops -> ops.type(ProxyProvider.Proxy.HTTP)
				                                                                   .host("127.0.0.1")
				                                                                   .port(8888)
				                                                                   .nonProxyHosts("spring.io")))
				          .wiretap();
		Mono<String> remote1 = client.followRedirect()
		                             .get()
		                             .uri("https://projectreactor.io")
		                             .responseContent()
		                             .retain()
		                             .asString()
		                             .limitRate(1)
		                             .reduce(String::concat);
		Mono<String> remote2 = client.followRedirect()
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

	//@Test
	public void postUpload() {
		InputStream f = getClass().getResourceAsStream("/public/index.html");
		//Path f = Paths.get("/Users/smaldini/Downloads/IMG_6702.mp4");
		int res = HttpClient.prepare()
		                    .tcpConfiguration(tcpClient -> tcpClient.host("google.com"))
		                    .wiretap()
		                    .put()
		                    .uri("/post")
		                    .send((c, out) -> {
		                           c.sendForm(form -> form.multipart(true)
		                                                  .file("test", f)
		                                                  .attr("att1", "attr2")
		                                                  .file("test2", f))
		                                                  .log()
		                                                  .then();
		                           return out;
		                    })
		                    .responseSingle((r, buf) -> Mono.just(r.status().code()))
		                    .block(Duration.ofSeconds(30));
		res = HttpClient.prepare()
		                .tcpConfiguration(tcpClient -> tcpClient.host("google.com"))
		                .wiretap()
		                .followRedirect()
		                .get()
		                .uri("/search")
		                .responseSingle((r, out) -> Mono.just(r.status().code()))
		                .log()
		                .block(Duration.ofSeconds(30));

		if (res != 200) {
			throw new IllegalStateException("test status failed with " + res);
		}
	}

	@Test
	public void simpleTest404() {
		doSimpleTest404(HttpClient.create("google.com"));
	}

	@Test
	public void simpleTest404_1() {
		HttpClient client =
				HttpClient.prepare(PoolResources.fixed("http", 1))
				          .port(80)
				          .tcpConfiguration(tcpClient -> tcpClient.host("google.com"))
				          .wiretap();
		doSimpleTest404(client);
		doSimpleTest404(client);
	}

	private void doSimpleTest404(HttpClient client) {
		int res = client.followRedirect()
		                .doAfterResponse(c -> System.out.println("LOLOL"))
				        .get()
				        .uri("/unsupportedURI")
				        .responseSingle((r, buf) -> Mono.just(r.status().code()))
				        .log()
				        .block();

		if (res != 404) {
			throw new IllegalStateException("test status failed with " + res);
		}
	}

	@Test
	public void disableChunkForced() {
		HttpClientResponse r =
				HttpClient.newConnection()
				          .tcpConfiguration(tcpClient -> tcpClient.host("google.com"))
				          .wiretap()
				          .noChunkedTransfer()
				          .request(HttpMethod.GET)
				          .uri("/unsupportedURI")
				          .send((c, out) -> c.sendString(Flux.just("hello")))
				          .response()
				          .block(Duration.ofSeconds(30));

		FutureMono.from(r.channel()
				.closeFuture())
				.block(Duration.ofSeconds(5));

		Assert.assertTrue(Objects.equals(r.status(), HttpResponseStatus.NOT_FOUND));
		r.dispose();
	}

	@Test
	public void disableChunkForced2() {
		HttpClientResponse r =
				HttpClient.newConnection()
				          .tcpConfiguration(tcpClient -> tcpClient.host("google.com"))
				          .wiretap()
				          .noChunkedTransfer()
				          .request(HttpMethod.GET)
				          .uri("/unsupportedURI")
				          .send((c, out) -> c.keepAlive(false))
				          .response()
				          .block(Duration.ofSeconds(30));


		FutureMono.from(r.channel()
				.closeFuture())
				.block(Duration.ofSeconds(5));

		Assert.assertTrue(Objects.equals(r.status(), HttpResponseStatus.NOT_FOUND));
		r.dispose();
	}

	@Test
	@Ignore
	public void simpleClientPooling() {
		PoolResources p = PoolResources.fixed("test", 1);
		AtomicReference<Channel> ch1 = new AtomicReference<>();
		AtomicReference<Channel> ch2 = new AtomicReference<>();

		HttpResponseStatus r =
				HttpClient.prepare(p)
				          .wiretap()
				          .get()
				          .uri("http://google.com/unsupportedURI")
				          .responseSingle((res, buf) -> {
				              res.withConnection(c -> ch1.set(c.channel()));
				              return Mono.just(res.status());
				          })
				          .block(Duration.ofSeconds(30));

		HttpResponseStatus r2 =
				HttpClient.prepare(p)
				          .wiretap()
				          .get()
				          .uri("http://google.com/unsupportedURI")
				          .responseSingle((res, buf) -> {
				              res.withConnection(c -> ch2.set(c.channel()));
				              return Mono.just(res.status());
				          })
				          .block(Duration.ofSeconds(30));

		AtomicBoolean same = new AtomicBoolean();

		same.set(ch1.get() == ch2.get());

		Assert.assertTrue(same.get());

		Assert.assertTrue(Objects.equals(r,HttpResponseStatus.NOT_FOUND));
		p.dispose();
	}

	@Test
	public void disableChunkImplicitDefault() {
		PoolResources p = PoolResources.fixed("test", 1);
		HttpClientResponse r =
				HttpClient.prepare(p)
				          .tcpConfiguration(tcpClient -> tcpClient.host("google.com")
				                                                  .noSSL())
				          .wiretap()
				          .noChunkedTransfer()
				          .get()
				          .uri("/unsupportedURI")
				          .response()
				          .block(Duration.ofSeconds(30));


		HttpClientResponse r2 =
				HttpClient.prepare(p)
				          .tcpConfiguration(tcpClient -> tcpClient.host("google.com")
				                                                  .noSSL())
				          .wiretap()
				          .noChunkedTransfer()
				          .get()
				          .uri("/unsupportedURI")
				          .response()
				          .block(Duration.ofSeconds(30));

		Assert.assertTrue(r.channel() == r2.channel());

		Assert.assertTrue(Objects.equals(r.status(), HttpResponseStatus.NOT_FOUND));
		r.dispose();
		r2.dispose();
		p.dispose();
	}

	@Test
	public void contentHeader() {
		PoolResources fixed = PoolResources.fixed("test", 1);
		HttpResponseStatus r =
				HttpClient.prepare(fixed)
				          .wiretap()
				          .headers(h -> h.add("content-length", "1"))
				          .request(HttpMethod.GET)
				          .uri("http://google.com")
				          .send(ByteBufFlux.fromString(Mono.just(" ")))
				          .responseSingle((res, buf) -> Mono.just(res.status()))
				          .block(Duration.ofSeconds(30));

		HttpResponseStatus r1 =
				HttpClient.prepare(fixed)
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

		StepVerifier.create(HttpClient.prepare()
		                              .wiretap()
		                              .get()
		                              .uri("https://developer.chrome.com")
		                              .response((r, buf) -> Mono.just(r.status().code())))
		            .expectNextMatches(status -> status >= 200 && status < 400)
		            .expectComplete()
		            .verify();

		StepVerifier.create(HttpClient.prepare()
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
		DisposableServer x = TcpServer.create()
		                        .host("localhost")
		                        .port(0)
		                        .handler((in, out) -> {
										signal.onComplete();
										return out.withConnection(c -> c.addHandlerFirst(
												new HttpResponseEncoder()))
										          .sendObject(Mono.delay(Duration
												          .ofSeconds(2))
												          .map(t ->
												          new DefaultFullHttpResponse(
														          HttpVersion.HTTP_1_1,
														          HttpResponseStatus
																          .PROCESSING)))
												.neverComplete();
		                          })
		                        .wiretap()
		                        .bindNow();

		StepVerifier.create(createHttpClientForContext(x)
		                              .get()
		                              .uri("/")
		                              .responseContent()
		                              .timeout(signal)
		)
		            .verifyError(TimeoutException.class);
//		Thread.sleep(1000000);
	}

	@Test
	public void gzip() {

		String content = "HELLO WORLD";

		DisposableServer c =
				HttpServer.create()
				          .compress()
				          .port(0)
				          .handler((req, res) -> res.sendString(Mono.just(content)))
				          .bindNow();


		//verify gzip is negotiated (when no decoder)
		StepVerifier.create(
		        HttpClient.prepare()
		                  .port(c.address().getPort())
		                  .wiretap()
		                  .headers(h -> h.add("Accept-Encoding", "gzip")
		                                 .add("Accept-Encoding", "deflate"))
		                  .followRedirect()
		                  .get()
		                  .uri("http://www.httpwatch.com")
		                  .response((r, buf) -> buf.asString()
		                                           .elementAt(0)
		                                           .map(s -> s.substring(0, Math.min(s.length() -1, 100)))
		                                           .zipWith(Mono.just(r.responseHeaders().get("Content-Encoding", "")))
		                                           .zipWith(Mono.just(r))))
		            .expectNextMatches(tuple -> {
		                               return !tuple.getT1().getT1().equals(content)
		                                      && "gzip".equals(tuple.getT1().getT2());
		                               })
		            .expectComplete()
		            .verify(Duration.ofSeconds(30));

		//verify decoder does its job and removes the header
		StepVerifier.create(
				HttpClient.prepare()
				          .port(c.address().getPort())
				          .wiretap()
				          .followRedirect()
				          .request(HttpMethod.GET)
				          .uri("/")
				          .send((req, out) ->
					          req.withConnection(conn -> conn.addHandlerFirst
							          ("gzipDecompressor", new
							          HttpContentDecompressor()))
					             .addHeader
							          ("Accept-Encoding", "gzip")
					                    .addHeader("Accept-Encoding", "deflate")
				          )
				          .response((r, buf) -> buf.asString()
				                                   .elementAt(0)
				                                   .map(s -> s.substring(0, Math.min(s.length() -1, 100)))
				                                   .zipWith(Mono.just(r.responseHeaders().get("Content-Encoding", "")))
				                                   .zipWith(Mono.just(r))))
		            .expectNextMatches(tuple -> {
		                               return tuple.getT1().getT1().equals(content)
		                                      && "".equals(tuple.getT1().getT2());
		                               })
		            .expectComplete()
		            .verify(Duration.ofSeconds(30));
		c.dispose();
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
		DisposableServer server = HttpServer.create()
		                         .port(0)
		                         .handler((req,res) -> res.sendString(
		                Mono.just(req.requestHeaders().get(HttpHeaderNames.ACCEPT_ENCODING, "no gzip"))))
		                         .wiretap()
		                         .bindNow();
		HttpClient client = HttpClient.prepare()
		                              .port(server.address().getPort())
		                              .wiretap();
		if (gzipEnabled){
			client = client.compress();
		}

		StepVerifier.create(client.get()
		                  .uri("/")
		                  .response((r, buf) -> buf.asString()
		                                           .elementAt(0)
		                                           .zipWith(Mono.just(r))))
		            .expectNextMatches(tuple -> {
		                tuple.getT2().dispose();
		                return expectedResponse.equals(tuple.getT1());
		            })
		            .expectComplete()
		            .verify(Duration.ofSeconds(30));

		server.dispose();
	}

	@Test
	public void testUserAgent() {
		DisposableServer c = HttpServer.create()
		                         .port(0)
		                         .handler((req, resp) -> {
			                           Assert.assertTrue(""+req.requestHeaders()
			                                                   .get(HttpHeaderNames.USER_AGENT),
					                           req.requestHeaders()
			                                               .contains(HttpHeaderNames.USER_AGENT) && req.requestHeaders()
			                                                                                           .get(HttpHeaderNames.USER_AGENT)
			                                                                                           .equals(HttpClient.USER_AGENT));

			                           return resp;
		                           })
		                         .wiretap()
		                         .bindNow();

		ByteBuf resp = HttpClient.prepare()
		                         .port(c.address().getPort())
		                         .wiretap()
		                         .get()
		                         .uri("/")
		                         .responseContent()
		                         .blockLast();

		c.dispose();
	}

	@Test
	public void gettingOptionsDuplicates() {
		HttpClient client = HttpClient.prepare()
		                              .tcpConfiguration(tcpClient -> tcpClient.host("foo"))
		                              .wiretap()
		                              .port(123)
		                              .compress();
		assertThat(client.tcpConfiguration())
		        .isNotSameAs(HttpClient.DEFAULT_TCP_CLIENT)
		        .isNotSameAs(client.tcpConfiguration());
	}

	@Test
	public void sshExchangeRelativeGet() throws CertificateException, SSLException {
		SelfSignedCertificate ssc = new SelfSignedCertificate();
		SslContext sslServer = SslContextBuilder.forServer(ssc.certificate(), ssc.privateKey())
		                                        .build();
		SslContext sslClient = SslContextBuilder.forClient()
		                                        .trustManager(InsecureTrustManagerFactory.INSTANCE)
		                                        .build();

		DisposableServer context =
				HttpServer.create()
				          .tcpConfiguration(tcpServer -> tcpServer.secure(sslServer))
				          .handler((req, resp) -> resp.sendString(Flux.just("hello ", req.uri())))
				          .wiretap()
				          .bindNow();


		String responseString =
				HttpClient.prepare()
				          .addressSupplier(context::address)
				          .tcpConfiguration(tcpClient -> tcpClient.secure(sslClient))
				          .wiretap()
				          .get()
				          .uri("/foo")
				          .responseSingle((res, buf) -> buf.asString(CharsetUtil.UTF_8))
				          .block(Duration.ofMillis(200));
		context.dispose();
		context.onDispose().block();

		assertThat(responseString).isEqualTo("hello /foo");

	}

	@Test
	public void sshExchangeAbsoluteGet() throws CertificateException, SSLException {
		SelfSignedCertificate ssc = new SelfSignedCertificate();
		SslContext sslServer = SslContextBuilder.forServer(ssc.certificate(), ssc.privateKey()).build();
		SslContext sslClient = SslContextBuilder.forClient()
		                                        .trustManager(InsecureTrustManagerFactory.INSTANCE).build();

		DisposableServer context =
				HttpServer.create()
				          .tcpConfiguration(tcpServer -> tcpServer.secure(sslServer))
				          .handler((req, resp) -> resp.sendString(Flux.just("hello ", req.uri())))
				          .wiretap()
				          .bindNow();

		String responseString = HttpClient.prepare()
		                                  .addressSupplier(() -> context.address())
		                                  .tcpConfiguration(tcpClient -> tcpClient.secure(sslClient))
		                                  .wiretap()
		                                  .get()
		                                  .uri("/foo")
		                                  .responseSingle((res, buf) -> buf.asString(CharsetUtil.UTF_8))
		                                  .block();
		context.dispose();
		context.onDispose().block();

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
				          .tcpConfiguration(tcpServer -> tcpServer.secure(sslServer))
				          .router(r -> r.post("/upload", (req, resp) ->
						          req.receive()
						             .aggregate()
						             .asString(StandardCharsets.UTF_8)
						             .doOnNext(uploaded::set)
						             .then(resp.status(201).sendString(Mono.just("Received File")).then())))
				          .wiretap()
				          .bindNow();

		Tuple2<String, Integer> response =
				HttpClient.prepare()
				          .addressSupplier(() -> context.address())
				          .tcpConfiguration(tcpClient -> tcpClient.secure(sslClient))
				          .wiretap()
				          .post()
				          .uri("/upload")
				          .send((r, out) -> r.sendFile(largeFile))
				          .responseSingle((res, buf) -> buf.asString()
				                                           .zipWith(Mono.just(res.status().code())))
				          .block(Duration.ofSeconds(120));

		context.dispose();
		context.onDispose().block();

		assertThat(response.getT2()).isEqualTo(201);
		assertThat(response.getT1()).isEqualTo("Received File");

		assertThat(uploaded.get())
				.startsWith("This is an UTF-8 file that is larger than 1024 bytes. " + "It contains accents like é.")
				.contains("1024 mark here -><- 1024 mark here")
				.endsWith("End of File");
	}

	@Test
	public void chunkedSendFile() throws URISyntaxException {
		Path largeFile = Paths.get(getClass().getResource("/largeFile.txt").toURI());
		AtomicReference<String> uploaded = new AtomicReference<>();

		DisposableServer context =
				HttpServer.create()
				          .tcpConfiguration(tcpServer -> tcpServer.host("localhost"))
				          .router(r -> r.post("/upload", (req, resp) ->
						          req
								          .receive()
								          .aggregate()
								          .asString(StandardCharsets.UTF_8)
								          .doOnNext(uploaded::set)
								          .then(resp.status(201).sendString(Mono.just("Received File")).then())))
				          .wiretap()
				          .bindNow();

		Tuple2<String, Integer> response =
				createHttpClientForContext(context)
				          .post()
				          .uri("/upload")
				          .send((r, out) -> r.sendFile(largeFile))
				          .responseSingle((res, buf) -> buf.asString()
				                                           .zipWith(Mono.just(res.status().code())))
				          .block(Duration.ofSeconds(120));

		context.dispose();
		context.onDispose().block();

		assertThat(response.getT2()).isEqualTo(201);
		assertThat(response.getT1()).isEqualTo("Received File");

		assertThat(uploaded.get())
				.startsWith("This is an UTF-8 file that is larger than 1024 bytes. " + "It contains accents like é.")
				.contains("1024 mark here -><- 1024 mark here")
				.endsWith("End of File");
	}

	@Test
	public void test() {
		DisposableServer context =
				HttpServer.create()
				          .tcpConfiguration(tcpServer -> tcpServer.host("localhost"))
				          .router(r -> r.put("/201", (req, res) -> res.addHeader("Content-Length", "0")
				                                                         .status(HttpResponseStatus.CREATED)
				                                                         .sendHeaders())
				                           .put("/204", (req, res) -> res.status(HttpResponseStatus.NO_CONTENT)
				                                                         .sendHeaders())
				                           .get("/200", (req, res) -> res.addHeader("Content-Length", "0")
				                                                         .sendHeaders()))
				          .bindNow();

		ByteBuf response1 =
				createHttpClientForContext(context)
						.doOnRequest(r -> System.out.println("onReq: "+r))
						.doAfterRequest(r -> System.out.println("afterReq: "+r))
						.doOnResponse(r -> System.out.println("onResp: "+r))
						.doAfterResponse(r -> System.out.println("afterResp: "+r))
				          .put()
				          .uri("/201")
				          .responseContent()
				          .blockLast();

		ByteBuf response2 =
				createHttpClientForContext(context)
				          .put()
				          .uri("/204")
				          .responseContent()
				          .blockLast(Duration.ofSeconds(30));

		ByteBuf response3 =
				createHttpClientForContext(context)
				          .get()
				          .uri("/200")
				          .responseContent()
				          .blockLast(Duration.ofSeconds(30));

		context.dispose();
	}

	@Test
	public void closePool() {
		PoolResources pr = PoolResources.fixed("wstest", 1);
		DisposableServer httpServer = HttpServer.create()
		                       .port(0)
		                       .handler((in, out) ->  out.options(opt -> opt.flushOnEach())
				                                  .sendString(
						                                  Mono.just("test")
						                                      .delayElement(Duration.ofMillis(100))
						                                      .repeat()))
		                       .wiretap()
		                       .bindNow();

		Flux<String> ws = HttpClient.prepare(pr)
		                            .port(httpServer.address().getPort())
		                            .get()
		                            .uri("/")
		                            .responseContent()
		                            .asString();

		StepVerifier.create(
				Flux.range(1, 10)
				    .concatMap(i -> ws.take(2)
				                      .log())
		)
		            .expectNextSequence(Flux.range(1, 20)
		                                    .map(v -> "test")
		                                    .toIterable())
		            .expectComplete()
		            .verify();

		httpServer.dispose();
		pr.dispose();
	}

	@Test
	public void testIssue303() {
		DisposableServer server =
				HttpServer.create()
				          .port(0)
				          .handler((req, resp) -> resp.sendString(Mono.just("OK")))
				          .wiretap()
				          .bindNow();

		Mono<String> content =
				HttpClient.prepare()
				          .port(server.address().getPort())
				          .request(HttpMethod.GET)
				          .uri("/")
				          .send((req, out) ->
				                  req.sendByteArray(Mono.defer(() -> Mono.just("Hello".getBytes(Charset.defaultCharset())))))
				          .responseContent()
				          .aggregate()
				          .asString();

		StepVerifier.create(content)
		            .expectNextMatches(s -> "OK".equals(s))
		            .expectComplete()
		            .verify(Duration.ofSeconds(30));

		server.dispose();
	}

	private HttpClient createHttpClientForContext(DisposableServer context) {
		return HttpClient.prepare()
		                 .addressSupplier(() -> context.address())
		                 .wiretap();
	}
}
