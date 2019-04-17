package reactor.netty.instrumentation.zipkin;

import reactor.netty.http.client.HttpClientRequest;
import reactor.netty.http.client.HttpClientResponse;

/**
 * Brave HTTP adapter for client requests
 */
class HttpClientAdapter extends brave.http.HttpClientAdapter<HttpClientRequest, HttpClientResponse> {
  @Override
  public String method(HttpClientRequest request) {
    return request.method().name();
  }

  @Override
  public String url(HttpClientRequest request) {
    return request.uri();
  }

  @Override
  public String path(HttpClientRequest request) {
    return "/" + request.uri();
  }

  @Override
  public String requestHeader(HttpClientRequest request, String name) {
    return request.requestHeaders().getAsString(name);
  }

  @Override
  public Integer statusCode(HttpClientResponse response) {
    return response.status().code();
  }

  @Override
  public int statusCodeAsInt(HttpClientResponse response) {
    return response.status().code();
  }
}
