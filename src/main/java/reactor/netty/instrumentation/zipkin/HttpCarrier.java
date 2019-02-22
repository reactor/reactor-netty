package reactor.netty.instrumentation.zipkin;

import brave.propagation.Propagation.Getter;
import brave.propagation.Propagation.Setter;
import io.netty.handler.codec.http.HttpHeaders;

/**
 * HTTP headers injector/extractor
 */
class HttpCarrier implements Getter<HttpHeaders, String>, Setter<HttpHeaders, String> {
  @Override
  public String get(HttpHeaders carrier, String key) {
    return carrier.get(key);
  }

  @Override
  public void put(HttpHeaders carrier, String key, String value) {
    carrier.set(key, value);
  }

  @Override
  public String toString() {
    return "HttpHeaders";
  }
}
