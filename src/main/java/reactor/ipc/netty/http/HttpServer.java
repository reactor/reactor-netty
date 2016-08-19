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

import java.io.File;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.TimeoutException;
import java.util.function.Function;
import java.util.function.Predicate;

import io.netty.buffer.ByteBuf;
import io.netty.channel.Channel;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.socket.SocketChannel;
import io.netty.handler.codec.http.DefaultHttpResponse;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpHeaderValues;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpServerCodec;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.logging.LoggingHandler;
import io.netty.handler.ssl.SslHandler;
import org.reactivestreams.Publisher;
import reactor.core.Exceptions;
import reactor.core.Loopback;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.ipc.netty.common.ChannelBridge;
import reactor.ipc.netty.common.DuplexSocket;
import reactor.ipc.netty.common.NettyChannel;
import reactor.ipc.netty.common.NettyHandlerNames;
import reactor.ipc.netty.config.ServerOptions;
import reactor.ipc.netty.tcp.TcpServer;
import reactor.util.Logger;
import reactor.util.Loggers;

/**
 * Base functionality needed by all servers that communicate with clients over HTTP.
 * @author Stephane Maldini
 */
public class HttpServer extends DuplexSocket<ByteBuf, ByteBuf, HttpChannel>
		implements Loopback, ChannelBridge<NettyHttpChannel> {

	/**
	 * Build a simple Netty HTTP server listening on 127.0.0.1 and 12012
	 * @return a simple HTTP Server
	 */
	public static HttpServer create() {
		return create(DEFAULT_BIND_ADDRESS);
	}

	/**
	 * Build a simple Netty HTTP server listening othe passed bind address and port
	 *
	 * @param options
	 *
	 * @return a simple HTTP server
	 */
	public static HttpServer create(ServerOptions options) {
		return new HttpServer(options);
	}

	/**
	 * Build a simple Netty HTTP server listening on 127.0.0.1 and the passed port
	 * @param port the port to listen to
	 * @return a simple HTTP server
	 */
	public static HttpServer create(int port) {
		return create(DEFAULT_BIND_ADDRESS, port);
	}

	/**
	 * Build a simple Netty HTTP server listening on 127.0.0.1 and 12012
	 * @param bindAddress address to listen for (e.g. 0.0.0.0 or 127.0.0.1)
	 * @return a simple HTTP server
	 */
	public static HttpServer create(String bindAddress) {
		return create(bindAddress, DEFAULT_PORT);
	}

	/**
	 * Build a simple Netty HTTP server listening othe passed bind address and port
	 * @param bindAddress address to listen for (e.g. 0.0.0.0 or 127.0.0.1)
	 * @param port the port to listen to
	 * @return a simple HTTP server
	 */
	public static HttpServer create(String bindAddress, int port) {
		return create(ServerOptions.create()
		                           .listen(bindAddress, port));
	}


	TcpServer    server;
	HttpMappings httpMappings;

	HttpServer(final ServerOptions options) {
		this.server = new TcpBridgeServer(options);
	}

	@Override
	public Object connectedInput() {
		return server;
	}

	@Override
	public TcpServer connectedOutput() {
		return server;
	}

	/**
	 * Listen for HTTP DELETE on the passed path to be used as a routing condition. Incoming connections will query the
	 * internal registry to invoke the matching handlers. <p> Additional regex matching is available when reactor-bus is
	 * on the classpath. e.g. "/test/{param}". Params are resolved using {@link HttpChannel#param(CharSequence)}
	 * @param path The {@link HttpMappings.HttpPredicate} to resolve against this path, pattern matching and capture
	 * are supported
	 * @param handler an handler to invoke for the given condition
	 * @return {@code this}
	 */
	public final HttpServer delete(String path, final Function<? super HttpChannel, ? extends Publisher<Void>> handler) {
		route(HttpMappings.delete(path), handler);
		return this;
	}

	/**
	 * Listen for HTTP GET on the passed path to be used as a routing condition. Incoming connections will query the
	 * internal registry to invoke the matching handlers. <p> Additional regex matching is available when reactor-bus is
	 * on the classpath. e.g. "/test/{param}". Params are resolved using {@link HttpChannel#param(CharSequence)}
	 * @param path The {@link HttpMappings.HttpPredicate} to resolve against this path, pattern matching and capture
	 * are supported
	 * @param directory the File to serve
	 * @return {@code this}
	 */
	public final HttpServer directory(String path, final File directory) {
		directory(path, directory.getAbsolutePath());
		return this;
	}

	/**
	 * Listen for HTTP GET on the passed path to be used as a routing condition. Incoming connections will query the
	 * internal registry to invoke the matching handlers. <p> Additional regex matching is available when reactor-bus is
	 * on the classpath. e.g. "/test/{param}". Params are resolved using {@link HttpChannel#param(CharSequence)}
	 * @param path The {@link HttpMappings.HttpPredicate} to resolve against this path, pattern matching and capture
	 * are supported
	 * @param directory the Path to the file to serve
	 * @return {@code this}
	 */
	public final HttpServer directory(final String path, final String directory) {
		return directory(path, directory, null);
	}

	/**
	 * Listen for HTTP GET on the passed path to be used as a routing condition. Incoming connections will query the
	 * internal registry to invoke the matching handlers. <p> Additional regex matching is available when reactor-bus is
	 * on the classpath. e.g. "/test/{param}". Params are resolved using {@link HttpChannel#param(CharSequence)}
	 * @param path The {@link HttpMappings.HttpPredicate} to resolve against this path, pattern matching and capture
	 * are supported
	 * @param directory the Path to the file to serve
	 * @return {@code this}
	 */
	public final HttpServer directory(final String path,
			final String directory,
			final Function<HttpChannel, HttpChannel> interceptor) {
		route(HttpMappings.prefix(path), channel -> {
			String strippedPrefix = channel.uri()
			                               .replaceFirst(path, "");
			int paramIndex = strippedPrefix.lastIndexOf("?");
			if (paramIndex != -1) {
				strippedPrefix = strippedPrefix.substring(0, paramIndex);
			}

			Path p = Paths.get(directory + strippedPrefix);
			if (Files.isReadable(p)) {

				if (interceptor != null) {
					return interceptor.apply(channel)
					                  .sendFile(p.toFile());
				}
				return channel.sendFile(p.toFile());
			}
			else {
				return Mono.error(Exceptions.failWithCancel());
			}
		});
		return this;
	}

	/**
	 * Listen for HTTP GET on the passed path to be used as a routing condition. Incoming connections will query the
	 * internal registry to invoke the matching handlers. <p> Additional regex matching is available when reactor-bus is
	 * on the classpath. e.g. "/test/{param}". Params are resolved using {@link HttpChannel#param(CharSequence)}
	 * @param path The {@link HttpMappings.HttpPredicate} to resolve against this path, pattern matching and capture
	 * are supported
	 * @param file the File to serve
	 * @return {@code this}
	 */
	public final HttpServer file(String path, final File file) {
		file(HttpMappings.get(path), file.getAbsolutePath(), null);
		return this;
	}

	/**
	 * Listen for HTTP GET on the passed path to be used as a routing condition. Incoming connections will query the
	 * internal registry to invoke the matching handlers. <p> Additional regex matching is available when reactor-bus is
	 * on the classpath. e.g. "/test/{param}". Params are resolved using {@link HttpChannel#param(CharSequence)}
	 * @param path The {@link HttpMappings.HttpPredicate} to resolve against this path, pattern matching and capture
	 * are supported
	 * @param filepath the Path to the file to serve
	 * @return {@code this}
	 */
	public final HttpServer file(String path, final String filepath) {
		file(HttpMappings.get(path), filepath, null);
		return this;
	}

	/**
	 * Listen for HTTP GET on the passed path to be used as a routing condition. Incoming connections will query the
	 * internal registry to invoke the matching handlers. <p> Additional regex matching is available when reactor-bus is
	 * on the classpath. e.g. "/test/{param}". Params are resolved using {@link HttpChannel#param(CharSequence)}
	 * @param path The {@link HttpMappings.HttpPredicate} to resolve against this path, pattern matching and capture
	 * are supported
	 * @param filepath the Path to the file to serve
	 * @param interceptor a channel pre-intercepting handler e.g. for content type header
	 * @return {@code this}
	 */
	public final HttpServer file(Predicate<HttpChannel> path,
			final String filepath,
			final Function<HttpChannel, HttpChannel> interceptor) {
		final File file = new File(filepath);
		route(path, channel -> {
			if (interceptor != null) {
				return interceptor.apply(channel)
				                  .sendFile(file);
			}
			return channel.sendFile(file);
		});
		return this;
	}

	/**
	 * Listen for HTTP GET on the passed path to be used as a routing condition. Incoming connections will query the
	 * internal registry to invoke the matching handlers. <p> Additional regex matching is available when reactor-bus is
	 * on the classpath. e.g. "/test/{param}". Params are resolved using {@link HttpChannel#param(CharSequence)}
	 * @param path The {@link HttpMappings.HttpPredicate} to resolve against this path, pattern matching and capture
	 * are supported
	 * @param handler an handler to invoke for the given condition
	 * @return {@code this}
	 */
	public final HttpServer get(String path, final Function<? super HttpChannel, ? extends Publisher<Void>> handler) {
		route(HttpMappings.get(path), handler);
		return this;
	}

	public InetSocketAddress getListenAddress() {
		return this.connectedOutput()
		           .getListenAddress();
	}

	@Override
	public boolean isShutdown() {
		return server.isShutdown();
	}

	/**
	 * Listen for HTTP POST on the passed path to be used as a routing condition. Incoming connections will query the
	 * internal registry to invoke the matching handlers. <p> Additional regex matching is available when reactor-bus is
	 * on the classpath. e.g. "/test/{param}". Params are resolved using {@link HttpChannel#param(CharSequence)}
	 * @param path The {@link HttpMappings.HttpPredicate} to resolve against this path, pattern matching and capture
	 * are supported
	 * @param handler an handler to invoke for the given condition
	 * @return {@code this}
	 */
	public final HttpServer post(String path, final Function<? super HttpChannel, ? extends Publisher<Void>> handler) {
		route(HttpMappings.post(path), handler);
		return this;
	}

	/**
	 * Listen for HTTP PUT on the passed path to be used as a routing condition. Incoming connections will query the
	 * internal registry to invoke the matching handlers. <p> Additional regex matching is available when reactor-bus is
	 * on the classpath. e.g. "/test/{param}". Params are resolved using {@link HttpChannel#param(CharSequence)}
	 * @param path The {@link HttpMappings.HttpPredicate} to resolve against this path, pattern matching and capture
	 * are supported
	 * @param handler an handler to invoke for the given condition
	 * @return {@code this}
	 */
	public final HttpServer put(String path, final Function<? super HttpChannel, ? extends Publisher<Void>> handler) {
		route(HttpMappings.put(path), handler);
		return this;
	}

	/**
	 * Register an handler for the given Selector condition, incoming connections will query the internal registry to
	 * invoke the matching handlers. Implementation may choose to reply 404 if no route matches.
	 * @param condition a {@link Predicate} to match the incoming connection with registered handler
	 * @param serviceFunction an handler to invoke for the given condition
	 * @return {@code this}
	 */
	@SuppressWarnings("unchecked")
	public HttpServer route(final Predicate<HttpChannel> condition,
			final Function<? super HttpChannel, ? extends Publisher<Void>> serviceFunction) {

		if (this.httpMappings == null) {
			this.httpMappings = HttpMappings.newMappings();
		}

		this.httpMappings.add(condition, serviceFunction);
		return this;
	}

	/***
	 * Additional regex matching is available when reactor-bus is on the classpath. Start the server without any global
	 * handler, only the specific routed methods (get, post...) will apply.
	 *
	 * @return a Promise fulfilled when server is started
	 */
	public final Mono<Void> start() {
		return start(null);
	}

	/**
	 * @see this#start()
	 */
	public final void startAndAwait() throws TimeoutException {
		start().block();
	}

	/**
	 * Listen for WebSocket on the passed path to be used as a routing condition. Incoming connections will query the
	 * internal registry to invoke the matching handlers. <p> Additional regex matching is available when reactor-bus is
	 * on the classpath. e.g. "/test/{param}". Params are resolved using {@link HttpChannel#param(CharSequence)}
	 * @param path The {@link HttpMappings.HttpPredicate} to resolve against this path, pattern matching and capture
	 * are supported
	 * @param handler an handler to invoke for the given condition
	 * @return {@code this}
	 */
	public final HttpServer ws(String path, final Function<? super HttpChannel, ? extends Publisher<Void>> handler) {
		return ws(path, handler, null);
	}

	/**
	 * Listen for WebSocket on the passed path to be used as a routing condition. Incoming connections will query the
	 * internal registry to invoke the matching handlers. <p> Additional regex matching is available when reactor-bus is
	 * on the classpath. e.g. "/test/{param}". Params are resolved using {@link HttpChannel#param(CharSequence)}
	 * @param path The {@link HttpMappings.HttpPredicate} to resolve against this path, pattern matching and capture
	 * are supported
	 * @param handler an handler to invoke for the given condition
	 * @param protocols
	 * @return {@code this}
	 */
	public final HttpServer ws(String path,
			final Function<? super HttpChannel, ? extends Publisher<Void>> handler,
			final String protocols) {
		return route(HttpMappings.get(path), channel -> {
			String connection = channel.headers()
			                           .get(HttpHeaderNames.CONNECTION);
			if (connection != null && connection.equals(HttpHeaderValues.UPGRADE.toString())) {
				onWebsocket(channel, protocols);
			}
			return handler.apply(channel);
		});
	}

	@Override
	protected Mono<Void> doStart(final Function<? super HttpChannel, ? extends Publisher<Void>> defaultHandler) {
		return server.start(ch -> {
			NettyHttpChannel request = (NettyHttpChannel) ch;

			try {
				Publisher<Void> afterHandlers = routeChannel(request);

				if (afterHandlers == null) {
					if (defaultHandler != null) {
						return defaultHandler.apply(request);
					}
					else if (request.markHeadersAsFlushed()) {
						//404
						request.delegate()
						       .writeAndFlush(new DefaultHttpResponse(HttpVersion.HTTP_1_1,
								       HttpResponseStatus.NOT_FOUND));
					}
					return Flux.empty();

				}
				else {
					return afterHandlers;
				}
			}
			catch (Throwable t) {
				Exceptions.throwIfFatal(t);
				return Mono.error(t);
			}
			//500
		});
	}

	protected final void onWebsocket(HttpChannel next, String protocols) {
		ChannelPipeline pipeline = next.delegate()
		                               .pipeline();
		pipeline.addLast(pipeline.remove(NettyHttpServerHandler.class)
		                         .withWebsocketSupport(next.uri(), protocols, false));
	}

	protected Publisher<Void> routeChannel(final HttpChannel ch) {

		if (httpMappings == null) {
			return null;
		}

		final Iterator<? extends Function<? super HttpChannel, ? extends Publisher<Void>>> selected =
				httpMappings.apply(ch)
				            .iterator();

		if (!selected.hasNext()) {
			return null;
		}

		Function<? super HttpChannel, ? extends Publisher<Void>> channelHandler = selected.next();

		if (!selected.hasNext()) {
			return channelHandler.apply(ch);
		}

		final List<Publisher<Void>> multiplexing = new ArrayList<>(4);

		multiplexing.add(channelHandler.apply(ch));

		do {
			channelHandler = selected.next();
			multiplexing.add(channelHandler.apply(ch));

		}
		while (selected.hasNext());

		return Flux.concat(Flux.fromIterable(multiplexing));
	}

	@Override
	protected final Mono<Void> doShutdown() {
		return server.shutdown();
	}

	final void bindHttpChannel(Function<? super NettyChannel, ? extends Publisher<Void>> handler,
			SocketChannel nativeChannel) {
		nativeChannel.pipeline()
		             .addLast(NettyHandlerNames.HttpCodecHandler, new HttpServerCodec())
		             .addLast(NettyHandlerNames.ReactiveBridge,
				new NettyHttpServerHandler(handler, this, nativeChannel));

	}

	@Override
	public NettyHttpChannel createChannelBridge(Channel ioChannel,
			Flux<Object> input, Object... parameters) {
		return new HttpServerChannel(ioChannel,
				input,
				parameters.length > 0 ? (HttpRequest) parameters[0] : null);
	}

	static final Logger log = Loggers.getLogger(HttpServer.class);

	final class TcpBridgeServer extends TcpServer {

		TcpBridgeServer(ServerOptions options) {
			super(options);
		}

		@Override
		protected void bindChannel(Function<? super NettyChannel, ? extends Publisher<Void>> handler,
				SocketChannel nativeChannel) {

			ChannelPipeline pipeline = nativeChannel.pipeline();

			if (getSslContext() != null) {
				SslHandler sslHandler = getSslContext().newHandler(nativeChannel.alloc());
				sslHandler.setHandshakeTimeoutMillis(getOptions().sslHandshakeTimeoutMillis());
				pipeline.addFirst(NettyHandlerNames.SslHandler, sslHandler);
			}

			if (log.isDebugEnabled()) {
				pipeline.addLast(NettyHandlerNames.LoggingHandler,
						new LoggingHandler(HttpServer.class));
			}

			if (null != getOptions() && null != getOptions().pipelineConfigurer()) {
				getOptions().pipelineConfigurer()
				            .accept(pipeline);
			}

			bindHttpChannel(handler, nativeChannel);
		}
	}
}
