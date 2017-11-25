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

import java.net.InetSocketAddress;
import java.util.Objects;
import java.util.function.*;

import io.netty.buffer.ByteBuf;
import io.netty.channel.Channel;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpHeaderValues;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.util.AsciiString;
import io.netty.util.Attribute;
import org.reactivestreams.Publisher;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.ipc.netty.ByteBufFlux;
import reactor.ipc.netty.ByteBufMono;
import reactor.ipc.netty.NettyInbound;
import reactor.ipc.netty.NettyOutbound;
import reactor.ipc.netty.channel.AbortedException;
import reactor.util.Logger;
import reactor.util.Loggers;

/**
 * @author Stephane Maldini
 */
final class MonoHttpClientResponse implements HttpClient.RequestSender {

	final HttpClient                                               parent;
	final HttpMethod                                               method;
	String                                                         uri;
	Function<? super HttpClientRequest, ? extends Publisher<Void>> handler;

	static final AsciiString ALL = new AsciiString("*/*");

	MonoHttpClientResponse(HttpClient parent, HttpMethod method) {
		this.parent = Objects.requireNonNull(parent, "parent");
		Objects.requireNonNull(method, "method");
		this.method = method == HttpClient.WS ? HttpMethod.GET : method;
	}

	// UriConfiguration methods

	@Override
	public HttpClient.RequestSender uri(String uri) {
		this.uri = Objects.requireNonNull(uri, "uri");
		return this;
	}

	@Override
	public HttpClient.RequestSender uri(Mono<String> uri) {
		// TODO
		return this;
	}


	// ResponseReceiver methods

	@Override
	public Mono<HttpClientResponse> response() {
		ReconnectableBridge bridge = new ReconnectableBridge(parent);

		return Mono.defer(() ->
				parent.connect()
				        .doOnNext(c -> {
				            Attribute<Boolean> acceptGzip = c.channel().attr(HttpClientOperations.ACCEPT_GZIP);
				              /*if (acceptGzip != null && acceptGzip.get()) {
				                  if (requestHandler != null) {
				                      requestHandler = req -> requestHandler.apply(req.header(HttpHeaderNames.ACCEPT_ENCODING,
				                                                                              HttpHeaderValues.GZIP));
				                  }
				                  else {
				                      requestHandler = req -> req.header(HttpHeaderNames.ACCEPT_ENCODING,
				                                                         HttpHeaderValues.GZIP);
				                  }
			                  }*/
				            HttpClientHandler handler = new HttpClientHandler(this, bridge);
				            bridge.activeURI = parent.uriEndpointFactory().createUriEndpoint(uri, method == HttpClient.WS);
				            bridge.accept(c.channel());
				            if (log.isDebugEnabled()) {
				                log.debug("{} handler is being applied: {}", c.channel(), handler);
				            }

				            Mono.fromDirect(handler.apply((NettyInbound) c, (NettyOutbound) c))
				                .subscribe(c.disposeSubscriber());
				        }))
				.retry(bridge)
				.cast(HttpClientResponse.class);
	}

	@Override
	public <V> Flux<V> response(BiFunction<? super HttpClientResponse, ? super ByteBufFlux, ? extends Publisher<? extends V>> receiver) {
		// TODO
		return null;
	}

	@Override
	public ByteBufFlux responseContent() {
		// TODO
		return null;
	}

	@Override
	public <V> Mono<V> responseSingle(BiFunction<? super HttpClientResponse, ? super ByteBufMono, ? extends Mono<? extends V>> receiver) {
		// TODO
		return null;
	}


	// RequestSender methods

	@Override
	public HttpClient.ResponseReceiver<?> send(Publisher<? extends ByteBuf> requestBody) {
		Objects.requireNonNull(requestBody, "requestBody");
		this.handler = req -> req.sendObject(requestBody);
		return this;
	}

	@Override
	public HttpClient.ResponseReceiver<?> send(BiFunction<? super HttpClientRequest, ? super NettyOutbound, ? extends NettyOutbound> sender) {
		// TODO
		return this;
	}

	static final Logger log = Loggers.getLogger(MonoHttpClientResponse.class);

	static final class HttpClientHandler
			implements BiFunction<NettyInbound, NettyOutbound, Publisher<Void>> {

		final MonoHttpClientResponse parent;
		final ReconnectableBridge    bridge;

		HttpClientHandler(MonoHttpClientResponse parent, ReconnectableBridge bridge) {
			this.bridge = bridge;
			this.parent = parent;
		}

		@Override
		public Publisher<Void> apply(NettyInbound in, NettyOutbound out) {
			try {
				UriEndpoint uri = bridge.activeURI;
				HttpClientOperations ch = (HttpClientOperations) in;
				ch.getNettyRequest()
				  .setUri(uri.getPathAndQuery())
				  .setMethod(parent.method)
				  .setProtocolVersion(HttpVersion.HTTP_1_1)
				  .headers()
				  .add(HttpHeaderNames.HOST, resolveHostHeaderValue(ch.address()))
				  .add(HttpHeaderNames.ACCEPT, ALL);

				if (parent.method == HttpMethod.GET
						|| parent.method == HttpMethod.HEAD
						|| parent.method == HttpMethod.DELETE) {
					ch.chunkedTransfer(false);
				}

				if (parent.handler != null) {
					return parent.handler.apply(ch);
				}
				else {
					return ch.send();
				}
			}
			catch (Throwable t) {
				return Mono.error(t);
			}
		}

		private static String resolveHostHeaderValue(InetSocketAddress remoteAddress) {
			if (remoteAddress != null) {
				String host = remoteAddress.getHostString();
				int port = remoteAddress.getPort();
				if (port != 80 && port != 443) {
					host = host + ':' + port;
				}
				return host;
			}
			else {
				return "localhost";
			}
		}

		@Override
		public String toString() {
			return "HttpClientHandler{" + "startURI=" + bridge.activeURI + ", method=" + parent.method + ", handler=" + parent.handler + '}';
		}

	}

	static final class ReconnectableBridge
			implements Predicate<Throwable>, Consumer<Channel> {

		private final HttpClient parent;
		volatile UriEndpoint activeURI;
		volatile Supplier<String>[] redirectedFrom;

		ReconnectableBridge(HttpClient parent) {
			this.parent = parent;
		}

		void redirect(String to) {
			Supplier<String>[] redirectedFrom = this.redirectedFrom;
			UriEndpoint from = activeURI;
			activeURI = parent.uriEndpointFactory().createUriEndpoint(to, activeURI.isWs());
			this.redirectedFrom = addToRedirectedFromArray(redirectedFrom, from);
		}

		@SuppressWarnings("unchecked")
		private static Supplier<String>[] addToRedirectedFromArray(Supplier<String>[] redirectedFrom, UriEndpoint from) {
			Supplier<String> fromUrlSupplier = () -> from.toExternalForm();
			if (redirectedFrom == null) {
				return new Supplier[] { fromUrlSupplier };
			}
			else {
				Supplier<String>[] newRedirectedFrom = new Supplier[redirectedFrom.length + 1];
				System.arraycopy(redirectedFrom,
						0,
						newRedirectedFrom,
						0,
						redirectedFrom.length);
				newRedirectedFrom[redirectedFrom.length] = fromUrlSupplier;
				return newRedirectedFrom;
			}
		}

		@Override
		public void accept(Channel channel) {
			Supplier<String>[] redirectedFrom = this.redirectedFrom;
			if (redirectedFrom != null) {
				channel.attr(HttpClientOperations.REDIRECT_ATTR_KEY)
				       .set(redirectedFrom);
			}
		}

		@Override
		public boolean test(Throwable throwable) {
			if (throwable instanceof RedirectClientException) {
				RedirectClientException re = (RedirectClientException) throwable;
				redirect(re.location);
				return true;
			}
			if (AbortedException.isConnectionReset(throwable)) {
				redirect(activeURI.toString());
				return true;
			}
			return false;
		}
	}
}

