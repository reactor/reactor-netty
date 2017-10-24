/*
 * Copyright (c) 2011-2017 Pivotal Software Inc, All Rights Reserved.
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
import java.util.function.*;

import io.netty.channel.Channel;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.util.AsciiString;
import org.reactivestreams.Publisher;
import reactor.core.CoreSubscriber;
import reactor.core.publisher.Mono;
import reactor.ipc.netty.NettyInbound;
import reactor.ipc.netty.NettyOutbound;
import reactor.ipc.netty.channel.AbortedException;

/**
 * @author Stephane Maldini
 */
final class MonoHttpClientResponse extends Mono<HttpClientResponse> {

	final HttpClient                                                     parent;
	final UriEndpoint                                                    startURI;
	final HttpMethod                                                     method;
	final Function<? super HttpClientRequest, ? extends Publisher<Void>> handler;

	static final AsciiString ALL = new AsciiString("*/*");

	MonoHttpClientResponse(HttpClient parent, String url,
			HttpMethod method,
			Function<? super HttpClientRequest, ? extends Publisher<Void>> handler) {
		this.parent = parent;
		boolean isWs = method == HttpClient.WS;
		this.startURI = parent.options.createUriEndpoint(url, isWs);
		this.method = isWs ? HttpMethod.GET : method;
		this.handler = handler;

	}

	@Override
	@SuppressWarnings("unchecked")
	public void subscribe(final CoreSubscriber<? super HttpClientResponse> subscriber) {
		ReconnectableBridge bridge = new ReconnectableBridge(parent);
		bridge.activeURI = startURI;

		Mono.defer(() -> parent.client.newHandler(new HttpClientHandler(this, bridge),
				bridge.activeURI.getRemoteAddress(),
				bridge.activeURI.isSecure(),
				bridge))
		    .retry(bridge)
		    .cast(HttpClientResponse.class)
		    .subscribe(subscriber);
	}

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
			activeURI = parent.options.createUriEndpoint(to, activeURI.isWs());
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

