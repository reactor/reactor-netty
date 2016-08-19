/*
 * Copyright (c) 2011-2016 Pivotal Software Inc, All Rights Reserved.
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

package reactor.ipc.netty.http;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.function.Function;
import java.util.function.Predicate;

import io.netty.channel.Channel;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpUtil;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.util.AsciiString;
import org.reactivestreams.Publisher;
import org.reactivestreams.Subscriber;
import reactor.core.Exceptions;
import reactor.core.publisher.DirectProcessor;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.MonoSource;
import reactor.core.publisher.Operators;
import reactor.ipc.netty.common.ChannelBridge;

/**
 * @author Stephane Maldini
 */
final class MonoHttpClientChannel extends Mono<HttpClientResponse> {

	final HttpClient                                                     client;
	final URI                                                            currentURI;
	final HttpMethod                                                     method;
	final Function<? super HttpClientRequest, ? extends Publisher<Void>> handler;

	static final AsciiString ALL = new AsciiString("*/*");

	MonoHttpClientChannel(HttpClient client,
			URI currentURI,
			HttpMethod method,
			Function<? super HttpClientRequest, ? extends Publisher<Void>> handler) {
		this.client = client;
		this.currentURI = currentURI;
		this.method = method;
		this.handler = handler;
	}

	@Override
	public void subscribe(final Subscriber<? super HttpClientResponse> subscriber) {
		ReconnectableBridge bridge = new ReconnectableBridge(currentURI.getScheme()
		                                                               .equalsIgnoreCase(
				                                                               HttpClient.HTTPS_SCHEME) || currentURI.getScheme()
		                                                                                                             .equalsIgnoreCase(
				                                                                                                             HttpClient.WSS_SCHEME));

		bridge.activeURI = currentURI;

		Mono.defer(() -> {
			DirectProcessor<Void> connectSignal = DirectProcessor.create();
			return client.doStart(bridge.activeURI, bridge, c -> {
			try {
				URI uri = bridge.activeURI;
				HttpClientChannel ch = (HttpClientChannel) c;
				ch.getNettyRequest()
				  .setUri(uri.getPath() +
						  (uri.getQuery() == null ? "" : "?" + uri.getRawQuery()))
				  .setMethod(method)
				  .setProtocolVersion(HttpVersion.HTTP_1_1)
				  .headers()
				  .add(HttpHeaderNames.HOST, uri.getHost())
				  .add(HttpHeaderNames.ACCEPT, ALL);

				if(method == HttpMethod.GET ||
						method == HttpMethod.HEAD){
					ch.removeTransferEncodingChunked();
				}

				if(ch.delegate().eventLoop().inEventLoop()) {
					ch.delegate()
					  .pipeline()
					  .get(NettyHttpClientHandler.class)
					  .bridgeReply(subscriber, connectSignal);
				}
				else{
					ch.delegate().eventLoop().execute(() -> ch.delegate()
					                                          .pipeline()
					                                          .get(NettyHttpClientHandler.class)
					                                          .bridgeReply(subscriber, connectSignal));
				}

				if (handler != null) {
					return handler.apply(ch);
				}
				else {
					HttpUtil.setTransferEncodingChunked(ch.getNettyResponse(), false);
					return ch.sendHeaders();
				}
			}
			catch (Throwable t) {
				return Mono.error(t);
			}
			})
			             .concatWith(connectSignal).as(MonoSource::wrap);
		})
		    .retry(bridge)
		    //ignore doStart mono except for fatal errors since replySubscriber is
		    // subscribed via channel event (active)
		    .subscribe(null, reason -> Operators.error(subscriber, reason));
	}
}

final class ReconnectableBridge
		implements ChannelBridge<HttpClientChannel>, Predicate<Throwable> {

	final boolean isSecure;

	URI      activeURI;
	String[] redirectedFrom;

	public ReconnectableBridge(boolean isSecure) {
		this.isSecure = isSecure;
	}

	@Override
	public HttpClientChannel createChannelBridge(Channel ioChannel, Flux<Object>
			input, Object... parameters) {
		return new HttpClientChannel(ioChannel, input, isSecure, redirectedFrom);
	}

	void redirect(String to) {
		URI from = activeURI;
		try {
			activeURI = new URI(to);
		}
		catch (URISyntaxException e) {
			throw Exceptions.propagate(e);
		}
		if (redirectedFrom == null) {
			redirectedFrom = new String[]{from.toString()};
		}
		else {
			String[] newRedirectedFrom = new String[redirectedFrom.length + 1];
			System.arraycopy(redirectedFrom,
					0,
					newRedirectedFrom,
					0,
					redirectedFrom.length);
			newRedirectedFrom[redirectedFrom.length] = from.toString();
			redirectedFrom = newRedirectedFrom;
		}
	}

	@Override
	public boolean test(Throwable throwable) {
		if (throwable instanceof RedirectException) {
			RedirectException re = (RedirectException) throwable;
			redirect(re.location);
			return true;
		}
		return false;
	}
}