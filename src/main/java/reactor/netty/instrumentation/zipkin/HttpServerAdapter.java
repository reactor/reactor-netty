/*
 * Copyright (c) 2011-2019 Pivotal Software Inc, All Rights Reserved.
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

package reactor.netty.instrumentation.zipkin;

import brave.Span;
import java.net.InetSocketAddress;
import reactor.netty.http.server.HttpServerRequest;
import reactor.netty.http.server.HttpServerResponse;

/**
 * Brave HTTP adapter for server requests
 */
class HttpServerAdapter extends brave.http.HttpServerAdapter<HttpServerRequest, HttpServerResponse> {

  @Override
  public String method(HttpServerRequest request) {
    return request.method().name();
  }

  @Override
  public String methodFromResponse(HttpServerResponse response) {
    return response.method().name();
  }

  @Override
  public String url(HttpServerRequest request) {
    return request.uri();
  }

  @Override
  public String path(HttpServerRequest request) {
    return request.uri();
  }

  @Override
  public String requestHeader(HttpServerRequest request, String name) {
    return request.requestHeaders().getAsString(name);
  }

  @Override
  public Integer statusCode(HttpServerResponse response) {
    return response.status().code();
  }

  @Override
  public int statusCodeAsInt(HttpServerResponse response) {
    return response.status().code();
  }

  @Override
  public String route(HttpServerResponse response) {
    return response.routeName();
  }

  @Override
  public boolean parseClientIpAndPort(HttpServerRequest request, Span span) {
    if (parseClientIpFromXForwardedFor(request, span)) {
      return true;
    }

    InetSocketAddress remoteAddress = request.remoteAddress();
    if (remoteAddress.getAddress() == null) return false;
    return span.remoteIpAndPort(remoteAddress.getHostString(), remoteAddress.getPort());
  }
}
