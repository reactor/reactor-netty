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
import java.util.Map;
import java.util.Objects;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.function.Supplier;
import javax.annotation.Nullable;

import io.netty.bootstrap.Bootstrap;
import io.netty.channel.Channel;
import io.netty.channel.EventLoopGroup;
import io.netty.handler.codec.http.HttpClientCodec;
import io.netty.handler.codec.http.HttpContentDecompressor;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpHeaderValues;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.ssl.JdkSslContext;
import io.netty.util.AsciiString;
import io.netty.util.AttributeKey;
import org.reactivestreams.Publisher;
import reactor.core.Exceptions;
import reactor.core.publisher.Mono;
import reactor.ipc.netty.Connection;
import reactor.ipc.netty.NettyInbound;
import reactor.ipc.netty.NettyOutbound;
import reactor.ipc.netty.NettyPipeline;
import reactor.ipc.netty.channel.AbortedException;
import reactor.ipc.netty.channel.BootstrapHandlers;
import reactor.ipc.netty.channel.ChannelOperations;
import reactor.ipc.netty.http.HttpResources;
import reactor.ipc.netty.resources.LoopResources;
import reactor.ipc.netty.tcp.InetSocketAddressUtil;
import reactor.ipc.netty.tcp.TcpClient;
import reactor.util.Logger;
import reactor.util.Loggers;

/**
 * @author Stephane Maldini
 */
final class HttpClientConnect extends HttpClient {

	static final HttpClientConnect INSTANCE = new HttpClientConnect();
	static final AsciiString       ALL      = new AsciiString("*/*");

	static final AttributeKey<String>     URI    = AttributeKey.newInstance("uri");
	static final AttributeKey<HttpMethod> METHOD = AttributeKey.newInstance("method");

	static final AttributeKey<BiFunction<? super HttpClientRequest, ? super NettyOutbound, ? extends NettyOutbound>>
			BODY = AttributeKey.newInstance("body");

	static final String                     MONO_URI_MARKER = "[deferred]";
	static final AttributeKey<Mono<String>> MONO_URI        =
			AttributeKey.newInstance("mono_uri");

	static final Logger log = Loggers.getLogger(HttpClientFinalizer.class);


	final TcpClient tcpClient;

	HttpClientConnect() {
		this(DEFAULT_TCP_CLIENT);
	}

	HttpClientConnect(TcpClient tcpClient) {
		this.tcpClient = Objects.requireNonNull(tcpClient, "tcpClient");
	}

	@Override
	protected TcpClient tcpConfiguration() {
		return tcpClient;
	}

	@SuppressWarnings("unchecked")
	@Override
	protected Mono<? extends Connection> connect(TcpClient delegate) {

		Bootstrap b;
		try {
			b = delegate.configure();
		}
		catch (Throwable t) {
			Exceptions.throwIfFatal(t);
			return Mono.error(t);
		}
		final Bootstrap bootstrap = b;

		if (b.config()
		     .group() == null) {
			LoopResources loops = HttpResources.get();

			boolean useNative =
					LoopResources.DEFAULT_NATIVE && !(delegate.sslContext() instanceof JdkSslContext);

			EventLoopGroup elg = loops.onClient(useNative);

			b.group(elg)
			 .channel(loops.onChannel(elg));
		}

		Map<AttributeKey<?>, Object> attrs = bootstrap.config()
		                                              .attrs();

		boolean compress = attrs.get(ACCEPT_GZIP) != null && (Boolean) attrs.get(ACCEPT_GZIP);


		String uri = (String)attrs.get(URI);
		//Todo handle Mono<String> uri
		//Todo handle baseUri

		ReconnectableUriEndpoint endpoint = new ReconnectableUriEndpoint(bootstrap, delegate.isSecure());
		HttpClientHandler handler = new HttpClientHandler(attrs, endpoint, compress);


		b.attr(ACCEPT_GZIP, null)
		 .attr(BODY, null)
		 .attr(URI, null)
		 .attr(MONO_URI, null)
		 .attr(METHOD, null);

		endpoint.activeURI = endpoint.uriEndpointFactory.createUriEndpoint(uri, handler.method == HttpClient.WS);

		BootstrapHandlers.updateConfiguration(b,
				NettyPipeline.HttpInitializer,
				(listener, channel) -> {
			channel.pipeline().addLast(NettyPipeline.HttpCodec, new HttpClientCodec());

			if (compress) {
				channel.pipeline().addAfter(NettyPipeline.HttpCodec,
						NettyPipeline.HttpDecompressor,
						new HttpContentDecompressor());
			}

		});

		return Mono.defer(() -> delegate.doOnConnected(c -> {
			endpoint.accept(c.channel());
			if (log.isDebugEnabled()) {
				log.debug("{} handler is being applied: {}", c.channel(), handler);
			}

			ChannelOperations<?, ?> ops = ChannelOperations.get(c.channel());
			Mono.fromDirect(handler.apply(ops, ops))
			    .subscribe(c.disposeSubscriber());
		})
		                                //todo add secure handling
		                                .connect(bootstrap.remoteAddress(endpoint.activeURI.getRemoteAddress())))
		           .retry(endpoint);
	}

	static final class HttpClientHandler
			implements BiFunction<NettyInbound, NettyOutbound, Publisher<Void>> {

		final ReconnectableUriEndpoint                                                             bridge;
		final HttpMethod                                                                           method;
		final BiFunction<? super HttpClientRequest, ? super NettyOutbound, ? extends NettyOutbound>handler;

		@SuppressWarnings("unchecked")
		HttpClientHandler(Map<AttributeKey<?>, Object> attrs, ReconnectableUriEndpoint bridge, boolean compress) {
			this.method = (HttpMethod) attrs.get(METHOD);


			BiFunction<? super HttpClientRequest, ? super NettyOutbound, ? extends NettyOutbound>
					requestHandler =
					(BiFunction<? super HttpClientRequest, ? super NettyOutbound, ?
							extends NettyOutbound>) attrs.get(BODY);

			if (compress) {
				if (requestHandler != null) {
					this.handler = (req, out) -> requestHandler.apply(req.header
							(HttpHeaderNames.ACCEPT_ENCODING, HttpHeaderValues.GZIP), out);
				}
				else {
					this.handler = (req, out) -> req.header(HttpHeaderNames.ACCEPT_ENCODING, HttpHeaderValues.GZIP);
				}
			}
			else {
				this.handler = requestHandler;
			}

			this.bridge = bridge;
		}

		@Override
		public Publisher<Void> apply(NettyInbound in, NettyOutbound out) {
			try {
				UriEndpoint uri = bridge.activeURI;
				HttpClientOperations ch = (HttpClientOperations) in;
				ch.getNettyRequest()
				  .setUri(uri.getPathAndQuery())
				  .setMethod(method)
				  .setProtocolVersion(HttpVersion.HTTP_1_1)
				  .headers()
				  .add(HttpHeaderNames.HOST, resolveHostHeaderValue(ch.address()))
				  .add(HttpHeaderNames.ACCEPT, ALL);

				if (method == HttpMethod.GET || method == HttpMethod.HEAD || method == HttpMethod.DELETE) {
					ch.chunkedTransfer(false);
				}

				if (handler != null) {
					return handler.apply(ch, out);
				}
				else {
					return ch.send();
				}
			}
			catch (Throwable t) {
				return Mono.error(t);
			}
		}

		private static String resolveHostHeaderValue(@Nullable InetSocketAddress remoteAddress) {
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
			return "{" + "uri=" + bridge.activeURI + ", method=" + method + '}';
		}
	}

	static final class ReconnectableUriEndpoint
			implements Predicate<Throwable>, Consumer<Channel> {

		final UriEndpointFactory uriEndpointFactory;

		volatile UriEndpoint        activeURI;
		volatile Supplier<String>[] redirectedFrom;

		ReconnectableUriEndpoint(Bootstrap bootstrap, boolean secure) {
			this.uriEndpointFactory = new UriEndpointFactory(() -> bootstrap.config()
			                                                                .remoteAddress(),
					secure,
					(hostname, port) -> InetSocketAddressUtil.createInetSocketAddress(
							hostname,
							port,
							false));
		}

		void redirect(String to) {
			Supplier<String>[] redirectedFrom = this.redirectedFrom;
			UriEndpoint from = activeURI;
			activeURI = uriEndpointFactory.createUriEndpoint(to, activeURI.isWs());
			this.redirectedFrom = addToRedirectedFromArray(redirectedFrom, from);
		}

		@SuppressWarnings("unchecked")
		private static Supplier<String>[] addToRedirectedFromArray(@Nullable Supplier<String>[] redirectedFrom,
				UriEndpoint from) {
			Supplier<String> fromUrlSupplier = from::toExternalForm;
			if (redirectedFrom == null) {
				return new Supplier[]{fromUrlSupplier};
			}
			else {
				Supplier<String>[] newRedirectedFrom =
						new Supplier[redirectedFrom.length + 1];
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
