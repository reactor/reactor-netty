# Brave instrumentation for Reactor Netty HTTP

:exclamation: This module is **DEPRECATED** as of `Reactor Netty 1.1.0` and will be removed in `Reactor Netty 2.0.0`.
We prefer to use the standard `HttpClient/HttpServer` integration with [Micrometer Tracing](https://micrometer.io/docs/tracing).

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
