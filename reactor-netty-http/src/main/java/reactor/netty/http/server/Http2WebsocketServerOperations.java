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
package reactor.netty.http.server;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.ChannelPromise;
import io.netty.handler.codec.compression.ZlibCodecFactory;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.DefaultHttpContent;
import io.netty.handler.codec.http.DefaultHttpRequest;
import io.netty.handler.codec.http.DefaultHttpResponse;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.HttpContent;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpHeaderValues;
import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpResponse;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.codec.http.LastHttpContent;
import io.netty.handler.codec.http.websocketx.CloseWebSocketFrame;
import io.netty.handler.codec.http.websocketx.WebSocket13FrameDecoder;
import io.netty.handler.codec.http.websocketx.WebSocket13FrameEncoder;
import io.netty.handler.codec.http.websocketx.WebSocketCloseStatus;
import io.netty.handler.codec.http.websocketx.WebSocketDecoderConfig;
import io.netty.handler.codec.http.websocketx.WebSocketFrameDecoder;
import io.netty.handler.codec.http.websocketx.WebSocketFrameEncoder;
import io.netty.handler.codec.http.websocketx.WebSocketServerHandshakeException;
import io.netty.handler.codec.http.websocketx.WebSocketVersion;
import io.netty.handler.codec.http.websocketx.extensions.WebSocketExtensionData;
import io.netty.handler.codec.http.websocketx.extensions.WebSocketExtensionDecoder;
import io.netty.handler.codec.http.websocketx.extensions.WebSocketExtensionEncoder;
import io.netty.handler.codec.http.websocketx.extensions.WebSocketExtensionUtil;
import io.netty.handler.codec.http.websocketx.extensions.WebSocketServerExtension;
import io.netty.handler.codec.http.websocketx.extensions.WebSocketServerExtensionHandshaker;
import io.netty.handler.codec.http.websocketx.extensions.compression.DeflateFrameServerExtensionHandshaker;
import io.netty.handler.codec.http.websocketx.extensions.compression.PerMessageDeflateServerExtensionHandshaker;
import io.netty.util.AsciiString;
import io.netty.util.internal.EmptyArrays;
import org.reactivestreams.Subscriber;
import reactor.core.publisher.Mono;
import reactor.netty.FutureMono;
import reactor.netty.NettyPipeline;
import reactor.netty.ReactorNetty;
import reactor.util.annotation.Nullable;
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

import static io.netty.buffer.Unpooled.EMPTY_BUFFER;
import static io.netty.handler.codec.http.HttpMethod.CONNECT;
import static io.netty.handler.codec.http.HttpVersion.HTTP_1_1;
import static io.netty.handler.codec.http.LastHttpContent.EMPTY_LAST_CONTENT;
import static io.netty.handler.codec.http.websocketx.extensions.compression.DeflateFrameServerExtensionHandshaker.DEFAULT_COMPRESSION_LEVEL;
import static io.netty.handler.codec.http.websocketx.extensions.compression.PerMessageDeflateServerExtensionHandshaker.MAX_WINDOW_SIZE;
import static reactor.netty.NettyPipeline.LEFT;
import static reactor.netty.ReactorNetty.format;

final class Http2WebsocketServerOperations extends WebsocketServerOperations {
	static final AsciiString V13 = AsciiString.cached("13");

	WebsocketServerHandshaker handshakerHttp2;

	Http2WebsocketServerOperations(String wsUrl, WebsocketServerSpec websocketServerSpec, HttpServerOperations replaced) {
		super(wsUrl, websocketServerSpec, replaced);
	}

	@Override
	@Nullable
	public String selectedSubprotocol() {
		return handshakerHttp2.selectedSubProtocol;
	}

	@Override
	protected void onInboundCancel() {
		if (log.isDebugEnabled()) {
			log.debug(format(channel(), INBOUND_CANCEL_LOG));
		}
		sendCloseNow(new CloseWebSocketFrame(), WebSocketCloseStatus.ABNORMAL_CLOSURE, EMPTY);
	}

	@Override
	void initHandshaker(String wsUrl, WebsocketServerSpec websocketServerSpec, HttpServerOperations replaced) {
		handshakerResult = channel().newPromise();

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
								websocketServerSpec.compressionPreferredClientNoContext(), 0);
				WebsocketServerExtensionHandler wsServerExtensionHandler =
						new WebsocketServerExtensionHandler(Arrays.asList(
								perMessageDeflateServerExtensionHandshaker,
								new DeflateFrameServerExtensionHandshaker(DEFAULT_COMPRESSION_LEVEL, 0)));
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
			handshakerHttp2.handshake(channel, request, responseHeaders.remove(HttpHeaderNames.TRANSFER_ENCODING), handshakerResult)
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
		else if (!requestHeaders().contains("x-http2-protocol", HttpHeaderValues.WEBSOCKET, true)) {
			msg = "Invalid websocket request, missing [:protocol=websocket] header.";
		}
		else {
			CharSequence version = requestHeaders().get(HttpHeaderNames.SEC_WEBSOCKET_VERSION);
			if (version == null || !version.equals(WebSocketVersion.V13.toHttpHeaderValue())) {
				msg = "Websocket version [" + version + "] is not supported.";
			}
		}

		if (msg != null) {
			HttpResponse res = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.BAD_REQUEST, EMPTY_BUFFER);
			res.headers().set(HttpHeaderNames.CONTENT_LENGTH, "0");
			Throwable handshakeException = new WebSocketServerHandshakeException(msg, nettyRequest);
			channel().writeAndFlush(res)
			         .addListener(f -> handshakerResult.setFailure(handshakeException));

			return false;
		}
		else {
			return true;
		}
	}

	@Override
	@SuppressWarnings("FutureReturnValueIgnored")
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
					//"FutureReturnValueIgnored" this is deliberate
					channel().write(frame);
					return channel().writeAndFlush(EMPTY_LAST_CONTENT)
					                .addListener(ChannelFutureListener.CLOSE);
				}
				frame.release();
				return channel().newSucceededFuture();
			}).doOnCancel(() -> ReactorNetty.safeRelease(frame));
		}
		frame.release();
		return Mono.empty();
	}

	@Override
	@SuppressWarnings("FutureReturnValueIgnored")
	void sendCloseNow(CloseWebSocketFrame frame, WebSocketCloseStatus closeStatus, ChannelFutureListener listener) {
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
			//"FutureReturnValueIgnored" this is deliberate
			channel().write(frame);
			channel().writeAndFlush(EMPTY_LAST_CONTENT)
			         .addListener(listener);
		}
		else {
			frame.release();
		}
	}

	static final ChannelFutureListener EMPTY = f -> {};

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

		String selectedSubProtocol;

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

		ChannelFuture handshake(Channel channel, HttpRequest req, HttpHeaders responseHeaders, ChannelPromise promise) {
			HttpResponse response = newHandshakeResponse(req.headers().get(HttpHeaderNames.SEC_WEBSOCKET_PROTOCOL), responseHeaders);

			ChannelPipeline p = channel.pipeline();
			channel.writeAndFlush(response).addListener(future -> {
				if (future.isSuccess()) {
					ChannelHandlerContext ctx = p.context(NettyPipeline.HttpTrafficHandler);
					p.addAfter(ctx.name(), "wsdecoder", newWebsocketDecoder(decoderConfig));
					p.addAfter(ctx.name(), "wsencoder", newWebsocketEncoder());
					p.replace(ctx.name(), WebsocketStreamBridgeServerHandler.NAME, WebsocketStreamBridgeServerHandler.INSTANCE);

					promise.setSuccess();
				}
				else {
					promise.setFailure(future.cause());
				}
			});
			return promise;
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
		HttpResponse newHandshakeResponse(@Nullable String subProtocols, HttpHeaders headers) {
			HttpResponse res = new DefaultHttpResponse(HTTP_1_1, HttpResponseStatus.OK);
			res.headers().add(headers);

			if (subProtocols != null) {
				String selectedSubProtocol = selectSubProtocol(subProtocols);
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

		static final class WebsocketStreamBridgeServerHandler extends ChannelDuplexHandler {
			static final WebsocketStreamBridgeServerHandler INSTANCE = new WebsocketStreamBridgeServerHandler();
			static final String NAME = LEFT + "websocketStreamBridgeServerHandler";

			@Override
			public void channelRead(ChannelHandlerContext ctx, Object msg) {
				if (!(msg instanceof FullHttpRequest) && msg instanceof HttpContent) {
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

	/* This class is based on io.netty.handler.codec.http.websocketx.extensions.WebSocketServerExtensionHandler but adapted for HTTP/2 */
	static final class WebsocketServerExtensionHandler extends ChannelDuplexHandler {

		final List<WebSocketServerExtensionHandshaker> extensionHandshakers;

		final Queue<List<WebSocketServerExtension>> validExtensions = new ArrayDeque<>(4);

		WebsocketServerExtensionHandler(List<WebSocketServerExtensionHandshaker> extensionHandshakers) {
			this.extensionHandshakers = extensionHandshakers;
		}

		@Override
		public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
			if (msg != LastHttpContent.EMPTY_LAST_CONTENT) {
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

			String extensionsHeader = request.headers().getAsString(HttpHeaderNames.SEC_WEBSOCKET_EXTENSIONS);

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
		@SuppressWarnings("FutureReturnValueIgnored")
		public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) {
			if (msg != Unpooled.EMPTY_BUFFER && !(msg instanceof ByteBuf)) {
				if (msg.getClass() == DefaultHttpResponse.class) {
					onHttpResponseWrite(ctx, (DefaultHttpResponse) msg, promise);
				}
				else if (msg instanceof HttpResponse) {
					onHttpResponseWrite(ctx, (HttpResponse) msg, promise);
				}
				else {
					//"FutureReturnValueIgnored" this is deliberate
					ctx.write(msg, promise);
				}
			}
			else {
				//"FutureReturnValueIgnored" this is deliberate
				ctx.write(msg, promise);
			}
		}

		@SuppressWarnings("FutureReturnValueIgnored")
		void onHttpResponseWrite(ChannelHandlerContext ctx, HttpResponse response, ChannelPromise promise) {
			List<WebSocketServerExtension> validExtensionsList = validExtensions.poll();
			if (HttpResponseStatus.OK.equals(response.status())) {
				handlePotentialUpgrade(ctx, promise, response, validExtensionsList);
			}
			//"FutureReturnValueIgnored" this is deliberate
			ctx.write(response, promise);
		}

		void handlePotentialUpgrade(ChannelHandlerContext ctx, ChannelPromise promise, HttpResponse httpResponse,
				@Nullable List<WebSocketServerExtension> validExtensionsList) {
			HttpHeaders headers = httpResponse.headers();

			if (validExtensionsList != null && !validExtensionsList.isEmpty()) {
				String headerValue = headers.getAsString(HttpHeaderNames.SEC_WEBSOCKET_EXTENSIONS);
				List<WebSocketExtensionData> extraExtensions = new ArrayList<>(extensionHandshakers.size());
				for (WebSocketServerExtension extension : validExtensionsList) {
					extraExtensions.add(extension.newReponseData());
				}
				String newHeaderValue = computeMergeExtensionsHeaderValue(headerValue, extraExtensions);
				promise.addListener(future -> {
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
				});

				if (!newHeaderValue.isEmpty()) {
					headers.set(HttpHeaderNames.SEC_WEBSOCKET_EXTENSIONS, newHeaderValue);
				}
			}

			promise.addListener(future -> {
				if (future.isSuccess()) {
					ctx.pipeline().remove(WebsocketServerExtensionHandler.this);
				}
			});
		}

		static final String EXTENSION_SEPARATOR = ",";
		static final String PARAMETER_SEPARATOR = ";";
		static final char PARAMETER_EQUAL = '=';

		static String computeMergeExtensionsHeaderValue(@Nullable String userDefinedHeaderValue, List<WebSocketExtensionData> extraExtensions) {
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
