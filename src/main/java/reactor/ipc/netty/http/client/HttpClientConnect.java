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
import java.net.SocketAddress;
import java.util.Map;
import java.util.Objects;
import java.util.function.BiFunction;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import javax.annotation.Nullable;

import io.netty.bootstrap.Bootstrap;
import io.netty.channel.Channel;
import io.netty.channel.EventLoopGroup;
import io.netty.handler.codec.http.DefaultHttpHeaders;
import io.netty.handler.codec.http.HttpClientCodec;
import io.netty.handler.codec.http.HttpContentDecompressor;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpHeaderValues;
import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.ssl.JdkSslContext;
import io.netty.util.AsciiString;
import io.netty.util.AttributeKey;
import org.reactivestreams.Publisher;
import reactor.core.CoreSubscriber;
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
import reactor.ipc.netty.tcp.SslProvider;
import reactor.ipc.netty.tcp.TcpClient;
import reactor.util.Logger;
import reactor.util.Loggers;

/**
 * @author Stephane Maldini
 */
final class HttpClientConnect extends HttpClient {

	static final HttpClientConnect INSTANCE = new HttpClientConnect();

	static final AsciiString          ALL = new AsciiString("*/*");
	static final AttributeKey<String> URI = AttributeKey.newInstance("uri");

	static final AttributeKey<HttpHeaders> HEADERS = AttributeKey.newInstance("headers");
	static final AttributeKey<HttpMethod>  METHOD  = AttributeKey.newInstance("method");
	static final AttributeKey<BiFunction<? super HttpClientRequest, ? super NettyOutbound, ? extends Publisher<Void>>>
	                                       BODY    = AttributeKey.newInstance("body");

	static final String MONO_URI_MARKER = "[deferred]";

	static final AttributeKey<Mono<String>> MONO_URI =
			AttributeKey.newInstance("mono_uri");
	static final Logger                     log      =
			Loggers.getLogger(HttpClientFinalizer.class);

	final TcpClient defaultClient;

	HttpClientConnect() {
		this(DEFAULT_TCP_CLIENT);
	}

	HttpClientConnect(TcpClient defaultClient) {
		this.defaultClient = Objects.requireNonNull(defaultClient, "tcpClient");
	}

	@Override
	protected TcpClient tcpConfiguration() {
		return defaultClient;
	}

	@SuppressWarnings("unchecked")
	@Override
	protected Mono<? extends Connection> connect(TcpClient tcpClient) {
		final Bootstrap bootstrap;
		try {
			bootstrap = tcpClient.configure();
		}
		catch (Throwable t) {
			Exceptions.throwIfFatal(t);
			return Mono.error(t);
		}

		channelFactoryAndLoops(tcpClient, bootstrap);

		Map<AttributeKey<?>, Object> attrs = bootstrap.config()
		                                              .attrs();

		bootstrap.attr(ACCEPT_GZIP, null)
		         .attr(FOLLOW_REDIRECT, null)
		         .attr(CHUNKED, null)
		         .attr(BODY, null)
		         .attr(HEADERS, null)
		         .attr(URI, null)
		         .attr(MONO_URI, null)
		         .attr(METHOD, null);

		return new MonoHttpConnect(bootstrap, attrs, tcpClient);
	}

	static void channelFactoryAndLoops(TcpClient tcpClient, Bootstrap b) {
		if (b.config()
		     .group() == null) {

			LoopResources loops = HttpResources.get();

			boolean useNative =
					LoopResources.DEFAULT_NATIVE && !(tcpClient.sslContext() instanceof JdkSslContext);

			EventLoopGroup elg = loops.onClient(useNative);

			b.group(elg)
			 .channel(loops.onChannel(elg));
		}
	}

	static final Predicate<HttpClientOperations.HttpClientEvent> filterHttpResponse =
			HttpClientOperations.HttpClientEvent.onResponse::equals;

	static final BiFunction<String, Integer, InetSocketAddress> URI_ADDRESS_MAPPER =
			InetSocketAddressUtil::createUnresolved;

	static final class MonoHttpConnect extends Mono<Connection> {

		final Bootstrap                    bootstrap;
		final Map<AttributeKey<?>, Object> attrs;
		final TcpClient                    tcpClient;

		MonoHttpConnect(Bootstrap bootstrap,
				Map<AttributeKey<?>, Object> attrs,
				TcpClient tcpClient) {
			this.bootstrap = bootstrap;
			this.attrs = attrs;
			this.tcpClient = tcpClient;
		}

		@Override
		public void subscribe(CoreSubscriber<? super Connection> actual) {
			final Bootstrap b = bootstrap.clone();

			HttpClientHandler handler = new HttpClientHandler(b.config()
			                                                   .remoteAddress(), attrs);

			BootstrapHandlers.updateConfiguration(b,
					NettyPipeline.HttpInitializer,
					(listener, channel) -> {
						channel.pipeline()
						       .addLast(NettyPipeline.HttpCodec, new HttpClientCodec());

						if (handler.compress) {
							channel.pipeline()
							       .addAfter(NettyPipeline.HttpCodec,
									       NettyPipeline.HttpDecompressor,
									       new HttpContentDecompressor());
						}

					});

			b.remoteAddress(handler);

			Mono.defer(() -> {
				Bootstrap finalBootstrap;
				//append secure handler if needed
				if (handler.activeURI.isSecure()) {
					finalBootstrap = SslProvider.addDefaultSslSupport(b.clone());
				}
				else {
					finalBootstrap = b;
				}

				return tcpClient.doOnConnected(c -> {
					if (log.isDebugEnabled()) {
						log.debug("{} handler is being applied: {}",
								c.channel(),
								handler);
					}

					ChannelOperations<?, ?> ops = ChannelOperations.get(c.channel());
					handler.channel(c.channel());
					Mono.fromDirect(handler.apply(ops, ops))
					    .subscribe(c.disposeSubscriber());
				})
				                .connect(finalBootstrap);
			})
			    .delayUntil(c -> {
				    HttpClientOperations ops = (HttpClientOperations) c;
				    return ops.httpClientEvents.filter(filterHttpResponse)
				                               .next()
				                               .map(evt -> ops);
			    })
			    .retry(handler)
			    .subscribe(actual);
		}
	}

	static final class HttpClientHandler extends SocketAddress
			implements BiFunction<NettyInbound, NettyOutbound, Publisher<Void>>,
			           Predicate<Throwable>, Supplier<SocketAddress> {

		final HttpMethod         method;
		final HttpHeaders        defaultHeaders;
		final BiFunction<? super HttpClientRequest, ? super NettyOutbound, ? extends NettyOutbound>
		                         handler;
		final boolean            followRedirect;
		final boolean            compress;
		final Boolean            chunkedTransfer;
		final UriEndpointFactory uriEndpointFactory;

		volatile UriEndpoint        activeURI;
		volatile Supplier<String>[] redirectedFrom;

		@SuppressWarnings("unchecked")
		HttpClientHandler(SocketAddress address, Map<AttributeKey<?>, Object> attrs) {
			this.method = (HttpMethod) attrs.get(METHOD);

			this.compress =
					attrs.get(ACCEPT_GZIP) != null && (Boolean) attrs.get(ACCEPT_GZIP);

			this.followRedirect =
					attrs.get(FOLLOW_REDIRECT) != null && (Boolean) attrs.get(
							FOLLOW_REDIRECT);

			this.chunkedTransfer = (Boolean) attrs.get(CHUNKED);

			String uri = (String) attrs.get(URI);

			uri = uri == null ? "/" : uri;

			Supplier<SocketAddress> addressSupplier;

			if (address instanceof Supplier) {
				addressSupplier = (Supplier<SocketAddress>) address;
			}
			else {
				addressSupplier = () -> address;
			}

			this.uriEndpointFactory =
					new UriEndpointFactory(addressSupplier, false, URI_ADDRESS_MAPPER);

			HttpHeaders defaultHeaders =
					(HttpHeaders) attrs.get(HttpClientConnect.HEADERS);

			BiFunction<? super HttpClientRequest, ? super NettyOutbound, ? extends NettyOutbound>
					requestHandler =
					(BiFunction<? super HttpClientRequest, ? super NettyOutbound, ? extends NettyOutbound>) attrs.get(
							BODY);

			if (compress) {
				if (defaultHeaders == null) {
					defaultHeaders = new DefaultHttpHeaders();
				}
				defaultHeaders.set(HttpHeaderNames.ACCEPT_ENCODING,
						HttpHeaderValues.GZIP);
			}

			this.defaultHeaders = defaultHeaders;
			this.handler = requestHandler;

			this.activeURI =
					uriEndpointFactory.createUriEndpoint(uri, method == HttpClient.WS);
		}

		@Override
		public SocketAddress get() {
			return activeURI.getRemoteAddress();
		}

		@Override
		public Publisher<Void> apply(NettyInbound in, NettyOutbound out) {
			try {
				UriEndpoint uri = activeURI;
				HttpClientOperations ch = (HttpClientOperations) in;
				HttpHeaders headers = ch.getNettyRequest()
				                        .setUri(uri.getPathAndQuery())
				                        .setMethod(method)
				                        .setProtocolVersion(HttpVersion.HTTP_1_1)
				                        .headers();

				if (defaultHeaders != null) {
					headers.set(defaultHeaders);
				}

				if (!headers.contains(HttpHeaderNames.USER_AGENT)) {
					headers.set(HttpHeaderNames.USER_AGENT, USER_AGENT);
				}

				if (!headers.contains(HttpHeaderNames.HOST)) {
					headers.set(HttpHeaderNames.HOST,
							resolveHostHeaderValue(ch.address()));
				}

				if (!headers.contains(HttpHeaderNames.ACCEPT)) {
					headers.set(HttpHeaderNames.ACCEPT, ALL);
				}

				ch.followRedirect(followRedirect);

				if (method == HttpMethod.GET || method == HttpMethod.HEAD || method == HttpMethod.DELETE) {
					ch.chunkedTransfer(false);
				}
				else {
					ch.chunkedTransfer(true);
				}

				if (chunkedTransfer != null) {
					ch.chunkedTransfer(chunkedTransfer);
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
				if (VALID_IPV6_PATTERN.matcher(host)
				                      .matches()) {
					host = "[" + host + "]";
				}
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
				Supplier<String>[] newRedirectedFrom = new Supplier[redirectedFrom.length + 1];
				System.arraycopy(redirectedFrom, 0, newRedirectedFrom, 0, redirectedFrom.length);
				newRedirectedFrom[redirectedFrom.length] = fromUrlSupplier;
				return newRedirectedFrom;
			}
		}

		void channel(Channel channel) {
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

		@Override
		public String toString() {
			return "{" + "uri=" + activeURI + ", method=" + method + '}';
		}
	}

	static final Pattern VALID_IPV6_PATTERN;

	static {
		try {
			VALID_IPV6_PATTERN = Pattern.compile("([0-9a-f]{1,4}:){7}([0-9a-f]){1,4}",
					Pattern.CASE_INSENSITIVE);
		}
		catch (PatternSyntaxException e) {
			throw new IllegalStateException("Impossible");
		}
	}
}
