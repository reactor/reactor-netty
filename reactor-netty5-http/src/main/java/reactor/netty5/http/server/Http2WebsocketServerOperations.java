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
package reactor.netty5.http.server;

import io.netty5.buffer.Buffer;
import io.netty5.channel.Channel;
import io.netty5.channel.ChannelFutureListeners;
import io.netty5.channel.ChannelHandler;
import io.netty5.channel.ChannelHandlerContext;
import io.netty5.channel.ChannelPipeline;
import io.netty5.handler.codec.compression.ZlibCodecFactory;
import io.netty5.handler.codec.http.DefaultFullHttpResponse;
import io.netty5.handler.codec.http.DefaultHttpContent;
import io.netty5.handler.codec.http.DefaultHttpRequest;
import io.netty5.handler.codec.http.DefaultHttpResponse;
import io.netty5.handler.codec.http.EmptyLastHttpContent;
import io.netty5.handler.codec.http.FullHttpRequest;
import io.netty5.handler.codec.http.HttpContent;
import io.netty5.handler.codec.http.HttpHeaderNames;
import io.netty5.handler.codec.http.HttpHeaderValues;
import io.netty5.handler.codec.http.HttpRequest;
import io.netty5.handler.codec.http.HttpResponse;
import io.netty5.handler.codec.http.HttpResponseStatus;
import io.netty5.handler.codec.http.HttpVersion;
import io.netty5.handler.codec.http.headers.HttpHeaders;
import io.netty5.handler.codec.http.websocketx.CloseWebSocketFrame;
import io.netty5.handler.codec.http.websocketx.WebSocket13FrameDecoder;
import io.netty5.handler.codec.http.websocketx.WebSocket13FrameEncoder;
import io.netty5.handler.codec.http.websocketx.WebSocketCloseStatus;
import io.netty5.handler.codec.http.websocketx.WebSocketDecoderConfig;
import io.netty5.handler.codec.http.websocketx.WebSocketFrameDecoder;
import io.netty5.handler.codec.http.websocketx.WebSocketFrameEncoder;
import io.netty5.handler.codec.http.websocketx.WebSocketServerHandshakeException;
import io.netty5.handler.codec.http.websocketx.WebSocketVersion;
import io.netty5.handler.codec.http.websocketx.extensions.WebSocketExtensionData;
import io.netty5.handler.codec.http.websocketx.extensions.WebSocketExtensionDecoder;
import io.netty5.handler.codec.http.websocketx.extensions.WebSocketExtensionEncoder;
import io.netty5.handler.codec.http.websocketx.extensions.WebSocketExtensionUtil;
import io.netty5.handler.codec.http.websocketx.extensions.WebSocketServerExtension;
import io.netty5.handler.codec.http.websocketx.extensions.WebSocketServerExtensionHandshaker;
import io.netty5.handler.codec.http.websocketx.extensions.compression.DeflateFrameServerExtensionHandshaker;
import io.netty5.handler.codec.http.websocketx.extensions.compression.PerMessageDeflateServerExtensionHandshaker;
import io.netty5.util.concurrent.Future;
import io.netty5.util.concurrent.FutureListener;
import io.netty5.util.internal.EmptyArrays;
import org.jspecify.annotations.Nullable;
import org.reactivestreams.Subscriber;
import reactor.core.publisher.Mono;
import reactor.netty5.FutureMono;
import reactor.netty5.NettyPipeline;
import reactor.netty5.ReactorNetty;
import reactor.util.context.Context;
import reactor.util.context.ContextView;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Queue;

import static io.netty5.handler.codec.http.HttpMethod.CONNECT;
import static io.netty5.handler.codec.http.HttpVersion.HTTP_1_1;
import static io.netty5.handler.codec.http.websocketx.extensions.compression.PerMessageDeflateServerExtensionHandshaker.MAX_WINDOW_SIZE;
import static reactor.netty5.NettyPipeline.LEFT;
import static reactor.netty5.ReactorNetty.format;

final class Http2WebsocketServerOperations extends WebsocketServerOperations {
	@SuppressWarnings("NullAway")
	// Deliberately suppress "NullAway"
	// This is a lazy initialization
	WebsocketServerHandshaker handshakerHttp2;

	Http2WebsocketServerOperations(String wsUrl, WebsocketServerSpec websocketServerSpec, HttpServerOperations replaced) {
		super(wsUrl, websocketServerSpec, replaced);
	}

	@Override
	public @Nullable String selectedSubprotocol() {
		return handshakerHttp2.selectedSubProtocol;
	}

	@Override
	protected void onInboundCancel() {
		if (log.isDebugEnabled()) {
			log.debug(format(channel(), INBOUND_CANCEL_LOG));
		}
		sendCloseNow(new CloseWebSocketFrame(true, 0, channel().bufferAllocator().allocate(0)),
				WebSocketCloseStatus.ABNORMAL_CLOSURE, EMPTY);
	}

	@Override
	void initHandshaker(String wsUrl, WebsocketServerSpec websocketServerSpec, HttpServerOperations replaced) {
		if (isValid()) {
			Channel channel = channel();

			removeHandler(NettyPipeline.AccessLogHandler);

			ChannelHandler handler = channel.pipeline().get(NettyPipeline.HttpMetricsHandler);
			if (handler != null) {
				replaceHandler(NettyPipeline.HttpMetricsHandler,
						new WebsocketHttpServerMetricsHandler((AbstractHttpServerMetricsHandler) handler));
			}

			HttpRequest request = new DefaultHttpRequest(replaced.version(), replaced.method(), replaced.uri());
			request.headers().set(replaced.nettyRequest.headers());

			if (websocketServerSpec.compress()) {
				removeHandler(NettyPipeline.CompressionHandler);

				PerMessageDeflateServerExtensionHandshaker perMessageDeflateServerExtensionHandshaker =
						new PerMessageDeflateServerExtensionHandshaker(6, ZlibCodecFactory.isSupportingWindowSizeAndMemLevel(),
								MAX_WINDOW_SIZE, websocketServerSpec.compressionAllowServerNoContext(),
								websocketServerSpec.compressionPreferredClientNoContext());
				WebsocketServerExtensionHandler wsServerExtensionHandler =
						new WebsocketServerExtensionHandler(Arrays.asList(
								perMessageDeflateServerExtensionHandshaker,
								new DeflateFrameServerExtensionHandshaker()));
				try {
					ChannelPipeline pipeline = channel.pipeline();
					wsServerExtensionHandler.channelRead(pipeline.context(NettyPipeline.ReactiveBridge), request);

					if (pipeline.get(NettyPipeline.HttpTrafficHandler) != null) {
						pipeline.addAfter(NettyPipeline.HttpTrafficHandler, NettyPipeline.WsCompressionHandler, wsServerExtensionHandler);
					}
				}
				catch (Throwable e) {
					log.error(format(channel, ""), e);
				}
			}

			handshakerHttp2 = new WebsocketServerHandshaker(wsUrl, websocketServerSpec);
			HttpHeaders responseHeaders = replaced.responseHeaders();
			responseHeaders.remove(HttpHeaderNames.TRANSFER_ENCODING);
			handshakerHttp2.handshake(channel, request, responseHeaders)
			               .addListener(f -> {
			                   if (replaced.rebind(this)) {
			                       markPersistent(false);
			                       // This change is needed after the Netty change https://github.com/netty/netty/pull/11966
			                       channel.read();
			                   }
			                   else if (log.isDebugEnabled()) {
			                       log.debug(format(channel, "Cannot bind Http2WebsocketServerOperations after the handshake."));
			                   }
			               });
		}
	}

	@SuppressWarnings("UndefinedEquals")
	boolean isValid() {
		String msg = null;
		if (this.nettyRequest instanceof FullHttpRequest) {
			msg = "Failed to upgrade to websocket. End of stream is received.";
		}
		else if (!CONNECT.equals(method())) {
			msg = "Invalid websocket request handshake method [" + method() + "].";
		}
		else if (!requestHeaders().containsIgnoreCase("x-http2-protocol", HttpHeaderValues.WEBSOCKET)) {
			msg = "Invalid websocket request, missing [:protocol=websocket] header.";
		}
		else {
			CharSequence version = requestHeaders().get(HttpHeaderNames.SEC_WEBSOCKET_VERSION);
			if (version == null || !version.equals(WebSocketVersion.V13.toHttpHeaderValue())) {
				msg = "Websocket version [" + version + "] is not supported.";
			}
		}

		if (msg != null) {
			HttpResponse res = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.BAD_REQUEST,
					channel().bufferAllocator().allocate(0));
			res.headers().set(HttpHeaderNames.CONTENT_LENGTH, "0");
			Throwable handshakeException = new WebSocketServerHandshakeException(msg, nettyRequest);
			channel().writeAndFlush(res)
			         .addListener(f -> {
			             handshakerResult = channel().newFailedFuture(handshakeException);
			         });

			return false;
		}
		else {
			return true;
		}
	}

	@Override
	Mono<Void> sendClose(CloseWebSocketFrame frame) {
		if (CLOSE_SENT.get(this) == 0) {
			//commented for now as we assume the close is always scheduled (deferFuture runs)
			//onTerminate().subscribe(null, null, () -> ReactorNetty.safeRelease(frame));
			return FutureMono.deferFuture(() -> {
				if (CLOSE_SENT.getAndSet(this, 1) == 0) {
					discard();
					// EmitResult is ignored as CLOSE_SENT guarantees that there will be only one emission
					// Whether there are subscribers or the subscriber cancels is not of interest
					// Evaluated EmitResult: FAIL_TERMINATED, FAIL_OVERFLOW, FAIL_CANCELLED, FAIL_NON_SERIALIZED
					// FAIL_ZERO_SUBSCRIBER
					onCloseState.tryEmitValue(new WebSocketCloseStatus(frame.statusCode(), frame.reasonText()));
					channel().write(frame);
					return channel().writeAndFlush(new EmptyLastHttpContent(channel().bufferAllocator()))
					                .addListener(channel(), ChannelFutureListeners.CLOSE);
				}
				frame.close();
				return channel().newSucceededFuture();
			}).doOnCancel(() -> ReactorNetty.safeRelease(frame));
		}
		frame.close();
		return Mono.empty();
	}

	@Override
	void sendCloseNow(CloseWebSocketFrame frame, WebSocketCloseStatus closeStatus, FutureListener<Void> listener) {
		if (!frame.isFinalFragment()) {
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
			channel().writeAndFlush(new EmptyLastHttpContent(channel().bufferAllocator()))
			         .addListener(listener);
		}
		else {
			frame.close();
		}
	}

	static final FutureListener<Void> EMPTY = f -> {};

	@Override
	Subscriber<Void> websocketSubscriber(ContextView contextView) {
		return new WebsocketSubscriber(this, Context.of(contextView), EMPTY);
	}

	/* This class is based on io.netty.handler.codec.http.websocketx.WebSocketServerHandshaker but adapted for HTTP/2 */
	static final class WebsocketServerHandshaker {
		/**
		 * Use this as wildcard to support all requested sub-protocols.
		 */
		static final String SUB_PROTOCOL_WILDCARD = "*";

		final String uri;
		final String[] subProtocols;
		final WebSocketDecoderConfig decoderConfig;

		@Nullable String selectedSubProtocol;

		WebsocketServerHandshaker(String uri, WebsocketServerSpec websocketServerSpec) {
			this.uri = uri;
			String protocols = websocketServerSpec.protocols();
			if (protocols != null) {
				String[] subProtocolArray = protocols.split(",");
				for (int i = 0; i < subProtocolArray.length; i++) {
					subProtocolArray[i] = subProtocolArray[i].trim();
				}
				this.subProtocols = subProtocolArray;
			}
			else {
				this.subProtocols = EmptyArrays.EMPTY_STRINGS;
			}
			this.decoderConfig =
					WebSocketDecoderConfig.newBuilder()
					                      .allowExtensions(true)
					                      .maxFramePayloadLength(websocketServerSpec.maxFramePayloadLength())
					                      .allowMaskMismatch(false)
					                      .build();
		}

		Future<Void> handshake(Channel channel, HttpRequest req, HttpHeaders responseHeaders) {
			HttpResponse response = newHandshakeResponse(req.headers().get(HttpHeaderNames.SEC_WEBSOCKET_PROTOCOL), responseHeaders);

			ChannelPipeline p = channel.pipeline();
			return channel.writeAndFlush(response).addListener(future -> {
				if (future.isSuccess()) {
					ChannelHandlerContext ctx = p.context(NettyPipeline.HttpTrafficHandler);
					p.addAfter(ctx.name(), "wsdecoder", newWebsocketDecoder(decoderConfig));
					p.addAfter(ctx.name(), "wsencoder", newWebsocketEncoder());
					p.replace(ctx.name(), WebsocketStreamBridgeServerHandler.NAME, WebsocketStreamBridgeServerHandler.INSTANCE);
				}
			});
		}

		@Nullable
		@SuppressWarnings("StringSplitter")
		String selectSubProtocol(@Nullable String requestedSubProtocols) {
			if (requestedSubProtocols == null || subProtocols.length == 0) {
				return null;
			}

			String[] requestedSubProtocolArray = requestedSubProtocols.split(",");
			for (String p : requestedSubProtocolArray) {
				String requestedSubProtocol = p.trim();

				for (String supportedSubProtocol : subProtocols) {
					if (SUB_PROTOCOL_WILDCARD.equals(supportedSubProtocol)
							|| requestedSubProtocol.equals(supportedSubProtocol)) {
						selectedSubProtocol = requestedSubProtocol;
						return requestedSubProtocol;
					}
				}
			}

			return null;
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

		HEADERS + END_HEADERS
		:status = 200
		sec-websocket-protocol = chat
		 */
		HttpResponse newHandshakeResponse(@Nullable CharSequence subProtocols, HttpHeaders headers) {
			HttpResponse res = new DefaultHttpResponse(HTTP_1_1, HttpResponseStatus.OK);
			res.headers().add(headers);

			if (subProtocols != null) {
				String selectedSubProtocol = selectSubProtocol(subProtocols.toString());
				if (selectedSubProtocol == null) {
					if (log.isDebugEnabled()) {
						log.debug("Requested subprotocol(s) not supported: {}", subProtocols);
					}
				}
				else {
					res.headers().set(HttpHeaderNames.SEC_WEBSOCKET_PROTOCOL, selectedSubProtocol);
				}
			}

			return res;
		}

		static WebSocketFrameDecoder newWebsocketDecoder(WebSocketDecoderConfig decoderConfig) {
			return new WebSocket13FrameDecoder(decoderConfig);
		}

		static WebSocketFrameEncoder newWebsocketEncoder() {
			return new WebSocket13FrameEncoder(false);
		}

		static final class WebsocketStreamBridgeServerHandler implements ChannelHandler {
			static final WebsocketStreamBridgeServerHandler INSTANCE = new WebsocketStreamBridgeServerHandler();
			static final String NAME = LEFT + "websocketStreamBridgeServerHandler";

			@Override
			public void channelRead(ChannelHandlerContext ctx, Object msg) {
				if (!(msg instanceof FullHttpRequest) && msg instanceof HttpContent<?> content) {
					ctx.fireChannelRead(content.payload());
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
			public Future<Void> write(ChannelHandlerContext ctx, Object msg) {
				if (msg instanceof Buffer buffer) {
					return ctx.write(new DefaultHttpContent(buffer));
				}
				else {
					return ctx.write(msg);
				}
			}
		}
	}

	/* This class is based on io.netty.handler.codec.http.websocketx.extensions.WebSocketServerExtensionHandler but adapted for HTTP/2 */
	static final class WebsocketServerExtensionHandler implements ChannelHandler {

		final List<WebSocketServerExtensionHandshaker> extensionHandshakers;

		final Queue<List<WebSocketServerExtension>> validExtensions = new ArrayDeque<>(4);

		WebsocketServerExtensionHandler(List<WebSocketServerExtensionHandshaker> extensionHandshakers) {
			this.extensionHandshakers = extensionHandshakers;
		}

		@Override
		public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
			if (!(msg instanceof EmptyLastHttpContent)) {
				if (msg instanceof DefaultHttpRequest) {
					onHttpRequestChannelRead(ctx, (DefaultHttpRequest) msg);
				}
				else if (msg instanceof HttpRequest) {
					onHttpRequestChannelRead(ctx, (HttpRequest) msg);
				}
				else {
					ctx.fireChannelRead(msg);
				}
			}
			else {
				ctx.fireChannelRead(msg);
			}
		}

		void onHttpRequestChannelRead(ChannelHandlerContext ctx, HttpRequest request) {
			List<WebSocketServerExtension> validExtensionsList = null;

			CharSequence extensionsHeader = request.headers().get(HttpHeaderNames.SEC_WEBSOCKET_EXTENSIONS);

			if (extensionsHeader != null) {
				List<WebSocketExtensionData> extensions = WebSocketExtensionUtil.extractExtensions(extensionsHeader);
				int rsv = 0;

				for (WebSocketExtensionData extensionData : extensions) {
					Iterator<WebSocketServerExtensionHandshaker> extensionHandshakersIterator =
							extensionHandshakers.iterator();
					WebSocketServerExtension validExtension = null;

					while (validExtension == null && extensionHandshakersIterator.hasNext()) {
						WebSocketServerExtensionHandshaker extensionHandshaker =
								extensionHandshakersIterator.next();
						validExtension = extensionHandshaker.handshakeExtension(extensionData);
					}

					if (validExtension != null && ((validExtension.rsv() & rsv) == 0)) {
						if (validExtensionsList == null) {
							validExtensionsList = new ArrayList<>(1);
						}
						rsv = rsv | validExtension.rsv();
						validExtensionsList.add(validExtension);
					}
				}
			}

			if (validExtensionsList == null) {
				validExtensionsList = Collections.emptyList();
			}
			validExtensions.offer(validExtensionsList);
			ctx.fireChannelRead(request);
		}

		@Override
		public Future<Void> write(ChannelHandlerContext ctx, Object msg) {
			if (!(msg instanceof Buffer)) {
				if (msg.getClass() == DefaultHttpResponse.class) {
					return onHttpResponseWrite(ctx, (DefaultHttpResponse) msg);
				}
				else if (msg instanceof HttpResponse) {
					return onHttpResponseWrite(ctx, (HttpResponse) msg);
				}
				else {
					return ctx.write(msg);
				}
			}
			else {
				return ctx.write(msg);
			}
		}

		Future<Void> onHttpResponseWrite(ChannelHandlerContext ctx, HttpResponse response) {
			List<WebSocketServerExtension> validExtensionsList = validExtensions.poll();
			if (HttpResponseStatus.OK.equals(response.status())) {
				return handlePotentialUpgrade(ctx, response, validExtensionsList);
			}
			return ctx.write(response);
		}

		Future<Void> handlePotentialUpgrade(ChannelHandlerContext ctx, HttpResponse httpResponse,
				@Nullable List<WebSocketServerExtension> validExtensionsList) {
			HttpHeaders headers = httpResponse.headers();

			FutureListener<Void> listener = null;
			if (validExtensionsList != null && !validExtensionsList.isEmpty()) {
				CharSequence headerValue = headers.get(HttpHeaderNames.SEC_WEBSOCKET_EXTENSIONS);
				List<WebSocketExtensionData> extraExtensions = new ArrayList<>(this.extensionHandshakers.size());
				for(WebSocketServerExtension extension : validExtensionsList) {
					extraExtensions.add(extension.newResponseData());
				}
				String newHeaderValue = computeMergeExtensionsHeaderValue(headerValue, extraExtensions);
				listener = future -> {
					if (future.isSuccess()) {
						for (WebSocketServerExtension extension : validExtensionsList) {
							WebSocketExtensionDecoder decoder = extension.newExtensionDecoder();
							WebSocketExtensionEncoder encoder = extension.newExtensionEncoder();
							String name = ctx.name();
							ctx.pipeline()
							   .addAfter(name, decoder.getClass().getName(), decoder)
							   .addAfter(name, encoder.getClass().getName(), encoder);
						}
					}
				};

				if (!newHeaderValue.isEmpty()) {
					headers.set(HttpHeaderNames.SEC_WEBSOCKET_EXTENSIONS, newHeaderValue);
				}
			}

			Future<Void> f = ctx.write(httpResponse);
			if (listener != null) {
				f.addListener(listener);
			}

			f.addListener(future -> {
				if (future.isSuccess()) {
					ctx.pipeline().remove(WebsocketServerExtensionHandler.this);
				}
			});
			return f;
		}

		static final String EXTENSION_SEPARATOR = ",";
		static final String PARAMETER_SEPARATOR = ";";
		static final char PARAMETER_EQUAL = '=';

		static String computeMergeExtensionsHeaderValue(@Nullable CharSequence userDefinedHeaderValue, List<WebSocketExtensionData> extraExtensions) {
			List<WebSocketExtensionData> userDefinedExtensions =
					userDefinedHeaderValue != null ? WebSocketExtensionUtil.extractExtensions(userDefinedHeaderValue) : Collections.emptyList();

			for (WebSocketExtensionData userDefined : userDefinedExtensions) {
				WebSocketExtensionData matchingExtra = null;
				int i;
				for (i = 0; i < extraExtensions.size(); i++) {
					WebSocketExtensionData extra = extraExtensions.get(i);
					if (extra.name().equals(userDefined.name())) {
						matchingExtra = extra;
						break;
					}
				}
				if (matchingExtra == null) {
					extraExtensions.add(userDefined);
				}
				else {
					// merge with higher precedence to user defined parameters
					Map<String, String> mergedParameters = new HashMap<>(matchingExtra.parameters());
					mergedParameters.putAll(userDefined.parameters());
					extraExtensions.set(i, new WebSocketExtensionData(matchingExtra.name(), mergedParameters));
				}
			}

			StringBuilder sb = new StringBuilder(150);

			for (WebSocketExtensionData data : extraExtensions) {
				sb.append(data.name());
				for (Map.Entry<String, String> parameter : data.parameters().entrySet()) {
					sb.append(PARAMETER_SEPARATOR);
					sb.append(parameter.getKey());
					if (parameter.getValue() != null) {
						sb.append(PARAMETER_EQUAL);
						sb.append(parameter.getValue());
					}
				}
				sb.append(EXTENSION_SEPARATOR);
			}

			if (!extraExtensions.isEmpty()) {
				sb.setLength(sb.length() - EXTENSION_SEPARATOR.length());
			}

			return sb.toString();
		}
	}
}
