# Brave instrumentation for Reactor Netty HTTP

:exclamation: This module is **DEPRECATED** as of `Reactor Netty 1.1.0` and will be removed in `Reactor Netty 2.0.0`.
Prefer using the standard `HttpClient/HttpServer` metrics functionality which has integration with
[Micrometer Observation API](https://github.com/micrometer-metrics/micrometer/wiki/Migrating-to-new-1.10.0-Observation-API)

This module contains tracing decorators for `HttpClient` and `HttpServer`.

## Configuration

Enable tracing for `HttpServer`

```java
HttpServer server = HttpServer.create()
                              .port(0)
                              ...

ReactorNettyHttpTracing reactorNettyHttpTracing = ReactorNettyHttpTracing.create(httpTracing);
HttpServer decoratedServer = reactorNettyHttpTracing.decorateHttpServer(server);
```

Enable tracing for `HttpClient`

```java
HttpClient client = HttpClient.create()
                              .port(0)
                              ...

ReactorNettyHttpTracing reactorNettyHttpTracing = ReactorNettyHttpTracing.create(httpTracing);
HttpClient decoratedClient = reactorNettyHttpTracing.decorateHttpClient(client);
```
