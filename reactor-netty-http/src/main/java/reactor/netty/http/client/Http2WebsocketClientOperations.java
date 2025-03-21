/*
 * Copyright (c) 2025 VMware, Inc. or its affiliates, All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package reactor.netty.http.client;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.ChannelPromise;
import io.netty.handler.codec.CodecException;
import io.netty.handler.codec.compression.ZlibCodecFactory;
import io.netty.handler.codec.http.DefaultHttpContent;
import io.netty.handler.codec.http.DefaultHttpRequest;
import io.netty.handler.codec.http.DefaultHttpResponse;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpContent;
import io.netty.handler.codec.http.HttpContentDecompressor;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpHeaderValues;
import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpResponse;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpScheme;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.codec.http.LastHttpContent;
import io.netty.handler.codec.http.websocketx.CloseWebSocketFrame;
import io.netty.handler.codec.http.websocketx.WebSocket13FrameDecoder;
import io.netty.handler.codec.http.websocketx.WebSocket13FrameEncoder;
import io.netty.handler.codec.http.websocketx.WebSocketClientHandshakeException;
import io.netty.handler.codec.http.websocketx.WebSocketCloseStatus;
import io.netty.handler.codec.http.websocketx.WebSocketScheme;
import io.netty.handler.codec.http.websocketx.extensions.WebSocketClientExtension;
import io.netty.handler.codec.http.websocketx.extensions.WebSocketClientExtensionHandshaker;
import io.netty.handler.codec.http.websocketx.extensions.WebSocketExtensionData;
import io.netty.handler.codec.http.websocketx.extensions.WebSocketExtensionDecoder;
import io.netty.handler.codec.http.websocketx.extensions.WebSocketExtensionEncoder;
import io.netty.handler.codec.http.websocketx.extensions.compression.DeflateFrameClientExtensionHandshaker;
import io.netty.handler.codec.http.websocketx.extensions.compression.PerMessageDeflateClientExtensionHandshaker;
import io.netty.handler.codec.http2.Http2Headers;
import io.netty.handler.codec.http2.Http2HeadersFrame;
import io.netty.util.AsciiString;
import io.netty.util.NetUtil;
import reactor.netty.NettyPipeline;
import reactor.util.annotation.Nullable;

import java.net.URI;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;

import static io.netty.handler.codec.http.LastHttpContent.EMPTY_LAST_CONTENT;
import static io.netty.handler.codec.http.websocketx.WebSocketVersion.V13;
import static io.netty.handler.codec.http.websocketx.extensions.compression.PerMessageDeflateServerExtensionHandshaker.MAX_WINDOW_SIZE;
import static reactor.netty.http.websocket.WebSocketExtensionUtil.computeMergeExtensionsHeaderValue;
import static reactor.netty.http.websocket.WebSocketExtensionUtil.extractExtensions;

final class Http2WebsocketClientOperations extends WebsocketClientOperations {

	WebsocketClientHandshaker handshakerHttp2;

	Http2WebsocketClientOperations(URI currentURI, WebsocketClientSpec websocketClientSpec, HttpClientOperations replaced) {
		super(currentURI, websocketClientSpec, replaced);
	}

	@Override
	@SuppressWarnings("FutureReturnValueIgnored")
	public void onInboundNext(ChannelHandlerContext ctx, Object msg) {
		if (msg instanceof FullHttpResponse) {
			FullHttpResponse response = (FullHttpResponse) msg;
			HttpResponseStatus status = response.status();
			response.content().release();
			String errorMsg = !HttpResponseStatus.OK.equals(status) ?
					"Invalid websocket handshake response status [" + status + "]." :
					"Failed to upgrade to websocket. End of stream is received.";
			onInboundError(new WebSocketClientHandshakeException(errorMsg, response));
			//"FutureReturnValueIgnored" this is deliberate
			ctx.close();
		}
		else if (msg instanceof HttpResponse) {
			started = true;

			HttpResponse response = (HttpResponse) msg;

			setNettyResponse(response);

			if (notRedirected(response)) {
				try {
					HttpResponseStatus status = response.status();
					if (!HttpResponseStatus.OK.equals(status)) {
						throw new WebSocketClientHandshakeException(
								"Invalid websocket handshake response status [" + status + "].", response);
					}

					handshakerHttp2.finishHandshake(channel(), response);
					// This change is needed after the Netty change https://github.com/netty/netty/pull/11966
					ctx.read();
					listener().onStateChange(this, HttpClientState.RESPONSE_RECEIVED);
				}
				catch (Exception e) {
					onInboundError(e);
					//"FutureReturnValueIgnored" this is deliberate
					ctx.close();
				}
			}
			else {
				listener().onUncaughtException(this, redirecting);
			}
		}
		else {
			super.onInboundNext(ctx, msg);
		}
	}

	@Override
	@Nullable
	public String selectedSubprotocol() {
		return handshakerHttp2.actualSubProtocol;
	}

	@Override
	void initHandshaker(URI currentURI, WebsocketClientSpec websocketClientSpec) {
		if (websocketClientSpec.version() != V13) {
			throw new WebSocketClientHandshakeException(
					"Websocket version " + websocketClientSpec.version() + " is not supported.");
		}

		removeHandler(NettyPipeline.HttpMetricsHandler);

		if (websocketClientSpec.compress()) {
			requestHeaders().remove(HttpHeaderNames.ACCEPT_ENCODING);
			// Returned value is deliberately ignored
			removeHandler(NettyPipeline.HttpDecompressor);
			// Returned value is deliberately ignored
			PerMessageDeflateClientExtensionHandshaker perMessageDeflateClientExtensionHandshaker =
					new PerMessageDeflateClientExtensionHandshaker(6, ZlibCodecFactory.isSupportingWindowSizeAndMemLevel(),
							MAX_WINDOW_SIZE, websocketClientSpec.compressionAllowClientNoContext(),
							websocketClientSpec.compressionRequestedServerNoContext());
			addHandlerFirst(NettyPipeline.WsCompressionHandler,
					new WebsocketClientExtensionHandler(Arrays.asList(
							perMessageDeflateClientExtensionHandshaker,
							new DeflateFrameClientExtensionHandshaker(false),
							new DeflateFrameClientExtensionHandshaker(true))));
		}

		String subProtocols = websocketClientSpec.protocols();
		handshakerHttp2 = new WebsocketClientHandshaker(
				currentURI,
				subProtocols != null && !subProtocols.isEmpty() ? subProtocols : null,
				requestHeaders().remove(HttpHeaderNames.HOST),
				websocketClientSpec.maxFramePayloadLength());

		Channel channel = channel();
		handshakerHttp2.handshake(channel)
		               .addListener(f -> {
		                   markPersistent(false);
		                   channel.read();
		               });
	}

	@Override
	boolean isHandshakeComplete() {
		return handshakerHttp2.handshakeComplete;
	}

	@Override
	@SuppressWarnings("FutureReturnValueIgnored")
	void sendCloseNow(CloseWebSocketFrame frame, WebSocketCloseStatus closeStatus) {
		if (!frame.isFinalFragment()) {
			//"FutureReturnValueIgnored" this is deliberate
			channel().writeAndFlush(frame);
			return;
		}
		if (CLOSE_SENT.getAndSet(this, 1) == 0) {
			// EmitResult is ignored as CLOSE_SENT guarantees that there will be only one emission
			// Whether there are subscribers or the subscriber cancels is not of interest
			// Evaluated EmitResult: FAIL_TERMINATED, FAIL_OVERFLOW, FAIL_CANCELLED, FAIL_NON_SERIALIZED
			// FAIL_ZERO_SUBSCRIBER
			onCloseState.tryEmitValue(closeStatus);
			channel().write(frame);
			channel().writeAndFlush(EMPTY_LAST_CONTENT);
		}
		else {
			frame.release();
		}
	}

	static final class WebsocketClientExtensionHandler extends ChannelDuplexHandler {

		final List<WebSocketClientExtensionHandshaker> extensionHandshakers;

		WebsocketClientExtensionHandler(List<WebSocketClientExtensionHandshaker> extensionHandshakers) {
			this.extensionHandshakers = extensionHandshakers;
		}

		@Override
		public void channelRead(ChannelHandlerContext ctx, Object msg) {
			if (msg != LastHttpContent.EMPTY_LAST_CONTENT) {
				if (msg.getClass() == DefaultHttpResponse.class) {
					onHttpResponseChannelRead(ctx, (DefaultHttpResponse) msg);
				}
				else if (msg instanceof HttpResponse && !(msg instanceof FullHttpResponse)) {
					onHttpResponseChannelRead(ctx, (HttpResponse) msg);
				}
				else {
					ctx.fireChannelRead(msg);
				}
			}
			else {
				ctx.fireChannelRead(msg);
			}
		}

		@Override
		public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) {
			if (msg != Unpooled.EMPTY_BUFFER && !(msg instanceof ByteBuf)) {
				if (msg.getClass() == DefaultHttpRequest.class) {
					onHttpRequestWrite(ctx, (DefaultHttpRequest) msg, promise);
				}
				else if (msg instanceof HttpRequest) {
					onHttpRequestWrite(ctx, (HttpRequest) msg, promise);
				}
				else {
					ctx.write(msg, promise);
				}
			}
			else {
				ctx.write(msg, promise);
			}
		}

		void onHttpRequestWrite(ChannelHandlerContext ctx, HttpRequest request, ChannelPromise promise) {
			String headerValue = request.headers().getAsString(HttpHeaderNames.SEC_WEBSOCKET_EXTENSIONS);
			List<WebSocketExtensionData> extraExtensions = new ArrayList<>(extensionHandshakers.size());
			for (WebSocketClientExtensionHandshaker extensionHandshaker : extensionHandshakers) {
				extraExtensions.add(extensionHandshaker.newRequestData());
			}
			String newHeaderValue = computeMergeExtensionsHeaderValue(headerValue, extraExtensions);

			request.headers().set(HttpHeaderNames.SEC_WEBSOCKET_EXTENSIONS, newHeaderValue);

			ctx.write(request, promise);
		}

		void onHttpResponseChannelRead(ChannelHandlerContext ctx, HttpResponse response) {
			if (HttpResponseStatus.OK.equals(response.status())) {
				String extensionsHeader = response.headers().getAsString(HttpHeaderNames.SEC_WEBSOCKET_EXTENSIONS);

				if (extensionsHeader != null) {
					List<WebSocketExtensionData> extensions = extractExtensions(extensionsHeader);
					List<WebSocketClientExtension> validExtensions = new ArrayList<>(extensions.size());
					int rsv = 0;

					for (WebSocketExtensionData extensionData : extensions) {
						Iterator<WebSocketClientExtensionHandshaker> extensionHandshakersIterator =
								extensionHandshakers.iterator();
						WebSocketClientExtension validExtension = null;

						while (validExtension == null && extensionHandshakersIterator.hasNext()) {
							WebSocketClientExtensionHandshaker extensionHandshaker =
									extensionHandshakersIterator.next();
							validExtension = extensionHandshaker.handshakeExtension(extensionData);
						}

						if (validExtension != null && ((validExtension.rsv() & rsv) == 0)) {
							rsv = rsv | validExtension.rsv();
							validExtensions.add(validExtension);
						}
						else {
							throw new CodecException("invalid WebSocket Extension handshake for [" + extensionsHeader + ']');
						}
					}

					for (WebSocketClientExtension validExtension : validExtensions) {
						WebSocketExtensionDecoder decoder = validExtension.newExtensionDecoder();
						WebSocketExtensionEncoder encoder = validExtension.newExtensionEncoder();
						ctx.pipeline().addAfter(ctx.name(), decoder.getClass().getName(), decoder);
						ctx.pipeline().addAfter(ctx.name(), encoder.getClass().getName(), encoder);
					}
				}

				ctx.pipeline().remove(ctx.name());
			}

			ctx.fireChannelRead(response);
		}
	}

	static final class WebsocketClientHandshaker {

		static final String HTTP_SCHEME_PREFIX = HttpScheme.HTTP + "://";
		static final String HTTPS_SCHEME_PREFIX = HttpScheme.HTTPS + "://";
		static final AsciiString V13 = AsciiString.cached("13");

		final HttpHeaders customHeaders;
		final String expectedSubProtocol;
		final int maxFramePayloadLength;
		final URI uri;

		volatile String actualSubProtocol;

		volatile boolean handshakeComplete;

		WebsocketClientHandshaker(URI uri, @Nullable String subProtocol, HttpHeaders customHeaders, int maxFramePayloadLength) {
			this.uri = uri;
			this.expectedSubProtocol = subProtocol;
			this.customHeaders = customHeaders;
			this.maxFramePayloadLength = maxFramePayloadLength;
		}

		/*
		https://datatracker.ietf.org/doc/html/rfc8441#section-5.1

		HEADERS + END_HEADERS
		:status = 200
		sec-websocket-protocol = chat
		 */
		void finishHandshake(Channel channel, HttpResponse response) {
			// Verify the subProtocol that we received from the server.
			// This must be one of our expected subProtocols - or null/empty if we didn't want to speak a subProtocol
			String receivedProtocol = response.headers().get(HttpHeaderNames.SEC_WEBSOCKET_PROTOCOL);
			receivedProtocol = receivedProtocol != null ? receivedProtocol.trim() : null;
			String expectedProtocol = expectedSubProtocol != null ? expectedSubProtocol : "";
			boolean protocolValid = false;

			if (expectedProtocol.isEmpty() && receivedProtocol == null) {
				// No subProtocol required and none received
				protocolValid = true;
				this.actualSubProtocol = expectedSubProtocol; // null or "" - we echo what the user requested
			}
			else if (!expectedProtocol.isEmpty() && receivedProtocol != null && !receivedProtocol.isEmpty()) {
				// We require a subProtocol and received one -> verify it
				for (String protocol : expectedProtocol.split(",")) {
					if (protocol.trim().equals(receivedProtocol)) {
						protocolValid = true;
						this.actualSubProtocol = receivedProtocol;
						break;
					}
				}
			} // else mixed cases - which are all errors

			if (!protocolValid) {
				throw new WebSocketClientHandshakeException(String.format(
						"Invalid subprotocol. Actual [%s]. Expected one of [%s]",
						receivedProtocol, expectedSubProtocol), response);
			}

			handshakeComplete = true;

			ChannelPipeline p = channel.pipeline();
			// Remove decompressor from pipeline if its in use
			HttpContentDecompressor decompressor = p.get(HttpContentDecompressor.class);
			if (decompressor != null) {
				p.remove(decompressor);
			}

			// Remove aggregator if present before
			HttpObjectAggregator aggregator = p.get(HttpObjectAggregator.class);
			if (aggregator != null) {
				p.remove(aggregator);
			}

			ChannelHandlerContext ctx = p.context("ws-encoder");
			if (ctx == null) {
				throw new WebSocketClientHandshakeException(
						"ChannelPipeline does not contain an ws-encoder", response);
			}
			else {
				p.addAfter(ctx.name(), "ws-decoder", newWebsocketDecoder(maxFramePayloadLength));
			}
		}

		ChannelFuture handshake(Channel channel) {
			ChannelPromise promise = channel.newPromise();

			ChannelPipeline pipeline = channel.pipeline();
			ChannelHandlerContext codec = pipeline.context(NettyPipeline.H2ToHttp11Codec);
			if (codec == null) {
				promise.setFailure(new WebSocketClientHandshakeException(
						"ChannelPipeline does not contain an Http2StreamFrameToHttpObjectCodec"));
				return promise;
			}

			pipeline.addBefore(codec.name(), "protocolHeaderHandler", ProtocolHeaderHandler.INSTANCE);

			HttpRequest request = newHandshakeRequest();

			channel.writeAndFlush(request).addListener((ChannelFutureListener) future -> {
				if (future.isSuccess()) {
					ChannelPipeline p = future.channel().pipeline();
					ChannelHandlerContext ctx = p.context(NettyPipeline.HttpTrafficHandler);
					if (ctx == null) {
						promise.setFailure(new WebSocketClientHandshakeException(
								"ChannelPipeline does not contain an Http2StreamBridgeClientHandler"));
						return;
					}
					p.addAfter(ctx.name(), "ws-encoder", newWebSocketEncoder());
					p.replace(ctx.name(), "websocketStreamBridgeClientHandler", WebsocketStreamBridgeClientHandler.INSTANCE);

					promise.setSuccess();
				}
				else {
					promise.setFailure(future.cause());
				}
			});
			return promise;
		}

		/*
		https://datatracker.ietf.org/doc/html/rfc8441#section-5.1

		HEADERS + END_HEADERS
		:method = CONNECT
		:protocol = websocket
		:scheme = https
		:path = /chat
		:authority = server.example.com
		sec-websocket-protocol = chat, superchat
		sec-websocket-extensions = permessage-deflate
		sec-websocket-version = 13
		origin = http://www.example.com
		 */
		HttpRequest newHandshakeRequest() {
			HttpRequest request = new DefaultHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.CONNECT, upgradeUrl(uri));
			HttpHeaders headers = request.headers();

			headers.add(customHeaders);

			headers.set(HttpHeaderNames.HOST, websocketHostValue(uri));

			if (!headers.contains(HttpHeaderNames.ORIGIN)) {
				headers.set(HttpHeaderNames.ORIGIN, websocketOriginValue(uri));
			}

			if (expectedSubProtocol != null && !expectedSubProtocol.isEmpty()) {
				headers.set(HttpHeaderNames.SEC_WEBSOCKET_PROTOCOL, expectedSubProtocol);
			}

			headers.set(HttpHeaderNames.SEC_WEBSOCKET_VERSION, V13);
			return request;
		}

		static ChannelHandler newWebsocketDecoder(int maxFramePayloadLength) {
			return new WebSocket13FrameDecoder(false, true, maxFramePayloadLength, false);
		}

		static ChannelHandler newWebSocketEncoder() {
			return new WebSocket13FrameEncoder(true);
		}

		static String upgradeUrl(URI wsURL) {
			String path = wsURL.getRawPath();
			path = path == null || path.isEmpty() ? "/" : path;
			String query = wsURL.getRawQuery();
			return query != null && !query.isEmpty() ? path + '?' + query : path;
		}

		static CharSequence websocketHostValue(URI wsURL) {
			int port = wsURL.getPort();
			if (port == -1) {
				return wsURL.getHost();
			}
			String host = wsURL.getHost();
			String scheme = wsURL.getScheme();
			if (port == HttpScheme.HTTP.port()) {
				return HttpScheme.HTTP.name().contentEquals(scheme)
						|| WebSocketScheme.WS.name().contentEquals(scheme) ?
						host : NetUtil.toSocketAddressString(host, port);
			}
			if (port == HttpScheme.HTTPS.port()) {
				return HttpScheme.HTTPS.name().contentEquals(scheme)
						|| WebSocketScheme.WSS.name().contentEquals(scheme) ?
						host : NetUtil.toSocketAddressString(host, port);
			}

			// if the port is not standard (80/443) it's needed to add the port to the header.
			// See https://tools.ietf.org/html/rfc6454#section-6.2
			return NetUtil.toSocketAddressString(host, port);
		}

		static CharSequence websocketOriginValue(URI wsURL) {
			String scheme = wsURL.getScheme();
			final String schemePrefix;
			int port = wsURL.getPort();
			final int defaultPort;
			if (WebSocketScheme.WSS.name().contentEquals(scheme)
					|| HttpScheme.HTTPS.name().contentEquals(scheme)
					|| (scheme == null && port == WebSocketScheme.WSS.port())) {

				schemePrefix = HTTPS_SCHEME_PREFIX;
				defaultPort = WebSocketScheme.WSS.port();
			}
			else {
				schemePrefix = HTTP_SCHEME_PREFIX;
				defaultPort = WebSocketScheme.WS.port();
			}

			// Convert uri-host to lower case (by RFC 6454, chapter 4 "Origin of a URI")
			String host = wsURL.getHost().toLowerCase(Locale.US);

			if (port != defaultPort && port != -1) {
				// if the port is not standard (80/443) it's needed to add the port to the header.
				// See https://tools.ietf.org/html/rfc6454#section-6.2
				return schemePrefix + NetUtil.toSocketAddressString(host, port);
			}
			return schemePrefix + host;
		}

		static final class ProtocolHeaderHandler extends ChannelOutboundHandlerAdapter {
			static final ProtocolHeaderHandler INSTANCE = new ProtocolHeaderHandler();

			@Override
			public boolean isSharable() {
				return true;
			}

			@Override
			public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) {
				if (msg instanceof Http2HeadersFrame) {
					((Http2HeadersFrame) msg).headers().set(Http2Headers.PseudoHeaderName.PROTOCOL.value(), HttpHeaderValues.WEBSOCKET);
					ctx.pipeline().remove(this);
				}
				ctx.write(msg, promise);
			}
		}

		static final class WebsocketStreamBridgeClientHandler extends ChannelDuplexHandler {
			static final WebsocketStreamBridgeClientHandler INSTANCE = new WebsocketStreamBridgeClientHandler();

			@Override
			public void channelRead(ChannelHandlerContext ctx, Object msg) {
				if (!(msg instanceof FullHttpResponse) && msg instanceof HttpContent) {
					ctx.fireChannelRead(((HttpContent) msg).content());
				}
				else {
					ctx.fireChannelRead(msg);
				}
			}

			@Override
			public boolean isSharable() {
				return true;
			}

			@Override
			@SuppressWarnings("FutureReturnValueIgnored")
			public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) {
				if (msg instanceof ByteBuf) {
					//"FutureReturnValueIgnored" this is deliberate
					ctx.write(new DefaultHttpContent((ByteBuf) msg), promise);
				}
				else {
					//"FutureReturnValueIgnored" this is deliberate
					ctx.write(msg, promise);
				}
			}
		}
	}
}
