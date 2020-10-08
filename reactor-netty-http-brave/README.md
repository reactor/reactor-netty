# Brave instrumentation for Reactor Netty HTTP

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
