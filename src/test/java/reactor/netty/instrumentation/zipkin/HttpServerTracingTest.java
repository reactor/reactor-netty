package reactor.netty.instrumentation.zipkin;

import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.hasEntry;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.lessThan;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.Assert.assertThat;

import brave.Tracing;
import brave.internal.Platform;
import java.time.Duration;
import java.util.List;
import java.util.stream.Collectors;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import reactor.core.publisher.Mono;
import reactor.netty.DisposableServer;
import reactor.netty.http.client.HttpClient;
import reactor.netty.http.server.HttpServer;
import reactor.netty.http.server.HttpServerRequest;
import reactor.netty.http.server.HttpServerResponse;
import reactor.netty.http.server.HttpServerRoutes;
import reactor.test.StepVerifier;
import zipkin2.Annotation;
import zipkin2.Span;
import zipkin2.Span.Kind;

public class HttpServerTracingTest {
  private InMemoryReporter spanReporter;
  private Tracing tracing;
  private HttpServerTracing serverTracing;

  @Before
  public void before() {
    spanReporter = new InMemoryReporter();
    tracing = Tracing.newBuilder()
        .localServiceName(HttpServerTracingTest.class.getSimpleName())
        .spanReporter(spanReporter)
        .build();
    serverTracing = HttpServerTracing.create(tracing);
  }

  @After
  public void after() {
    if (tracing != null) {
      tracing.close();
    }
  }

  @Test
  public void testNewTraceWithOK() {
    DisposableServer c = HttpServer.create()
        .port(0)
        .handle(serverTracing.andThen(this::getPing))
        .bindNow();

    Mono<String> client =  HttpClient.create()
        .port(c.address().getPort())
        .get()
        .uri("/ping")
        .responseContent()
        .aggregate()
        .asString();

    StepVerifier.create(client)
        .expectNext("pong")
        .expectComplete()
        .verify(Duration.ofSeconds(10));

    c.disposeNow();
    Span span = spanReporter.await().span(0);

    assertThat(span.duration(), greaterThan(100000L));
    assertThat(span.duration(), lessThan(200000L));
    assertThat(span.parentId(), nullValue());
    assertThat(span.remoteEndpoint(), not(nullValue()));
    assertThat(span.name(), is("get /ping"));
    assertThat(span.kind(), is(Kind.SERVER));
    assertThat(span.tags(), hasEntry("http.method", "GET"));
    assertThat(span.tags(), hasEntry("http.path", "/ping"));
    assertThat(annotations(span), hasItem("ping"));
  }

  @Test
  public void testNewTraceWithRoutes() {
    HttpServerRoutes routes = HttpServerRoutes.newRoutes()
        .get("/ping", this::getPing);

    DisposableServer c = HttpServer.create()
        .port(0)
        .handle(serverTracing.andThen(routes))
        .bindNow();

    Mono<String> client =  HttpClient.create()
        .port(c.address().getPort())
        .get()
        .uri("/ping")
        .responseContent()
        .aggregate()
        .asString();

    StepVerifier.create(client)
        .expectNext("pong")
        .expectComplete()
        .verify(Duration.ofSeconds(10));

    c.disposeNow();
    Span span = spanReporter.await().span(0);

    assertThat(span.parentId(), nullValue());
    assertThat(span.remoteEndpoint(), not(nullValue()));
    assertThat(span.name(), is("get /ping"));
    assertThat(span.kind(), is(Kind.SERVER));
    assertThat(span.tags(), hasEntry("http.method", "GET"));
    assertThat(span.tags(), hasEntry("http.path", "/ping"));
    assertThat(annotations(span), hasItem("ping"));
  }

  @Test
  public void testNewTraceWithXForwardedFor() {
    DisposableServer c = HttpServer.create()
        .port(0)
        .handle(serverTracing.andThen(this::getPing))
        .bindNow();

    Mono<String> client =  HttpClient.create()
        .port(c.address().getPort())
        .headers(h -> h.add("X-Forwarded-For", "192.168.1.1"))
        .get()
        .uri("/ping")
        .responseContent()
        .aggregate()
        .asString();

    StepVerifier.create(client)
        .expectNext("pong")
        .expectComplete()
        .verify(Duration.ofSeconds(10));

    c.disposeNow();
    Span span = spanReporter.await().span(0);

    assertThat(span.parentId(), nullValue());
    assertThat(span.remoteEndpoint().ipv4(), is("192.168.1.1"));
    assertThat(span.remoteEndpoint().port(), is(nullValue()));
    assertThat(span.name(), is("get /ping"));
    assertThat(span.kind(), is(Kind.SERVER));
    assertThat(span.tags(), hasEntry("http.method", "GET"));
    assertThat(span.tags(), hasEntry("http.path", "/ping"));
    assertThat(annotations(span), hasItem("ping"));
  }

  @Test
  public void testNewTraceWithError() {
    DisposableServer c = HttpServer.create()
        .port(0)
        .handle(serverTracing.andThen(this::raiseBoom))
        .bindNow();

    Mono<Integer> client =  HttpClient.create()
        .port(c.address().getPort())
        .get()
        .uri("/ping")
        .response()
        .map(res -> res.status().code());

    StepVerifier.create(client)
        .expectNext(500)
        .expectComplete()
        .verify(Duration.ofSeconds(10));

    c.disposeNow();
    Span span = spanReporter.await().span(0);

    assertThat(span.duration(), greaterThan(1L));
    assertThat(span.name(), is("get /boom"));
    assertThat(span.kind(), is(Kind.SERVER));
    assertThat(span.tags(), hasEntry("http.method", "GET"));
    assertThat(span.tags(), hasEntry("http.path", "/ping"));
    assertThat(span.tags(), hasEntry("error", "boom"));
  }

  @Test
  public void testNewTraceWithInternalServerError() {
    DisposableServer c = HttpServer.create()
        .port(0)
        .handle(serverTracing.andThen(this::internalServerError))
        .bindNow();

    Mono<Integer> client =  HttpClient.create()
        .port(c.address().getPort())
        .get()
        .uri("/ping")
        .response()
        .map(res -> res.status().code());

    StepVerifier.create(client)
        .expectNext(500)
        .expectComplete()
        .verify(Duration.ofSeconds(10));

    c.disposeNow();
    Span span = spanReporter.await().span(0);

    assertThat(span.duration(), greaterThan(1L));
    assertThat(span.name(), is("get"));
    assertThat(span.kind(), is(Kind.SERVER));
    assertThat(span.tags(), hasEntry("http.method", "GET"));
    assertThat(span.tags(), hasEntry("http.path", "/ping"));
    assertThat(span.tags(), hasEntry("http.status_code", "500"));
    assertThat(span.tags(), hasEntry("error", "500"));
  }

  @Test
  public void testJoinTrace() {
    brave.Span root = tracing.tracer().newTrace().start().kind(brave.Span.Kind.CLIENT);

    DisposableServer c = HttpServer.create()
        .port(0)
        .handle(serverTracing.andThen(this::getPing))
        .bindNow();

    root.remoteIpAndPort(Platform.get().getHostString(c.address()), c.address().getPort());

    Mono<String> client = HttpClient.create()
        .port(c.address().getPort())
        .headers(h -> tracing.propagation().injector(new HttpCarrier()).inject(root.context(), h))
        .get()
        .uri("/ping")
        .responseContent()
        .aggregate()
        .asString();

    StepVerifier.create(client)
        .expectNext("pong")
        .expectComplete()
        .verify(Duration.ofSeconds(10));

    c.disposeNow();
    root.finish();

    Span serverSpan = spanReporter.await().span(0);

    assertThat(serverSpan.duration(), greaterThan(100000L));
    assertThat(serverSpan.parentId(), nullValue());
    assertThat(serverSpan.remoteEndpoint(), not(nullValue()));
    assertThat(serverSpan.name(), is("get /ping"));
    assertThat(serverSpan.kind(), is(Kind.SERVER));
    assertThat(serverSpan.tags(), hasEntry("http.method", "GET"));
    assertThat(serverSpan.tags(), hasEntry("http.path", "/ping"));
    assertThat(annotations(serverSpan), hasItem("ping"));

    Span clientSpan = spanReporter.await().span(1);
    assertThat(clientSpan.traceId(), is(serverSpan.traceId()));
    assertThat(clientSpan.id(), is(serverSpan.id()));
  }

  private static List<String> annotations(Span span) {
    return span.annotations().stream().map(Annotation::value).collect(Collectors.toList());
  }

  private Mono<Void> getPing(HttpServerRequest request, HttpServerResponse response) {
    return Mono
        .subscriberContext()
        .delayElement(Duration.ofMillis(100))
        .flatMap(ctx -> {
          TracingContext.of(ctx).span(s -> s.annotate("ping"));
          return response.routeName("/ping").sendString(Mono.just("pong")).then();
        });
  }

  private Mono<Void> raiseBoom(HttpServerRequest request, HttpServerResponse response) {
    response.routeName("/boom");
    throw new RuntimeException("boom");
  }

  private Mono<Void> internalServerError(HttpServerRequest request, HttpServerResponse response) {
    return response.status(500).sendString(Mono.just("500")).then();
  }
}
