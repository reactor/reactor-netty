/*
 * Copyright (c) 2011-2024 VMware, Inc. or its affiliates, All Rights Reserved.
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

import java.util.concurrent.atomic.AtomicIntegerFieldUpdater;

import io.netty.buffer.ByteBuf;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.ChannelPromise;
import io.netty.handler.codec.compression.ZlibCodecFactory;
import io.netty.handler.codec.http.DefaultFullHttpRequest;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpServerCodec;
import io.netty.handler.codec.http.LastHttpContent;
import io.netty.handler.codec.http.websocketx.CloseWebSocketFrame;
import io.netty.handler.codec.http.websocketx.PingWebSocketFrame;
import io.netty.handler.codec.http.websocketx.PongWebSocketFrame;
import io.netty.handler.codec.http.websocketx.WebSocketCloseStatus;
import io.netty.handler.codec.http.websocketx.WebSocketServerHandshaker;
import io.netty.handler.codec.http.websocketx.WebSocketServerHandshakerFactory;
import io.netty.handler.codec.http.websocketx.extensions.WebSocketServerExtensionHandler;
import io.netty.handler.codec.http.websocketx.extensions.compression.DeflateFrameServerExtensionHandshaker;
import io.netty.handler.codec.http.websocketx.extensions.compression.PerMessageDeflateServerExtensionHandshaker;
import org.reactivestreams.Publisher;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import reactor.netty.FutureMono;
import reactor.netty.NettyOutbound;
import reactor.netty.NettyPipeline;
import reactor.netty.ReactorNetty;
import reactor.netty.http.HttpOperations;
import reactor.netty.http.websocket.WebsocketInbound;
import reactor.netty.http.websocket.WebsocketOutbound;
import reactor.util.annotation.Nullable;

import static io.netty.handler.codec.http.websocketx.extensions.compression.PerMessageDeflateServerExtensionHandshaker.MAX_WINDOW_SIZE;
import static reactor.netty.ReactorNetty.format;

/**
 * Conversion between Netty types and Reactor types ({@link HttpOperations}.
 *
 * @author Stephane Maldini
 * @author Simon Baslé
 */
final class WebsocketServerOperations extends HttpServerOperations
		implements WebsocketInbound, WebsocketOutbound {

	final WebSocketServerHandshaker           handshaker;
	final ChannelPromise                      handshakerResult;
	final Sinks.One<WebSocketCloseStatus>     onCloseState;
	final boolean                             proxyPing;

	volatile int closeSent;

	static final String INBOUND_CANCEL_LOG = "WebSocket server inbound receiver cancelled, closing Websocket.";

	@SuppressWarnings("FutureReturnValueIgnored")
	WebsocketServerOperations(String wsUrl, WebsocketServerSpec websocketServerSpec, HttpServerOperations replaced) {
		super(replaced);
		this.proxyPing = websocketServerSpec.handlePing();

		Channel channel = replaced.channel();
		onCloseState = Sinks.unsafe().one();

		// Handshake
		WebSocketServerHandshakerFactory wsFactory =
				new WebSocketServerHandshakerFactory(wsUrl, websocketServerSpec.protocols(), true, websocketServerSpec.maxFramePayloadLength());
		handshaker = wsFactory.newHandshaker(replaced.nettyRequest);
		if (handshaker == null) {
			//"FutureReturnValueIgnored" this is deliberate
			WebSocketServerHandshakerFactory.sendUnsupportedVersionResponse(channel);
			handshakerResult = null;
		}
		else {
			removeHandler(NettyPipeline.HttpTrafficHandler);
			removeHandler(NettyPipeline.AccessLogHandler);
			ChannelHandler handler = channel.pipeline().get(NettyPipeline.HttpMetricsHandler);
			if (handler != null) {
				replaceHandler(NettyPipeline.HttpMetricsHandler,
						new WebsocketHttpServerMetricsHandler((AbstractHttpServerMetricsHandler) handler));
			}

			handshakerResult = channel.newPromise();
			HttpRequest request = new DefaultFullHttpRequest(replaced.version(),
					replaced.method(),
					replaced.uri());

			request.headers()
			       .set(replaced.nettyRequest.headers());

			if (websocketServerSpec.compress()) {
				removeHandler(NettyPipeline.CompressionHandler);

				PerMessageDeflateServerExtensionHandshaker perMessageDeflateServerExtensionHandshaker =
						new PerMessageDeflateServerExtensionHandshaker(6, ZlibCodecFactory.isSupportingWindowSizeAndMemLevel(),
								MAX_WINDOW_SIZE, websocketServerSpec.compressionAllowServerNoContext(),
								websocketServerSpec.compressionPreferredClientNoContext());
				WebSocketServerExtensionHandler wsServerExtensionHandler =
						new WebSocketServerExtensionHandler(
								perMessageDeflateServerExtensionHandshaker,
								new DeflateFrameServerExtensionHandshaker());
				try {
					ChannelPipeline pipeline = channel.pipeline();
					wsServerExtensionHandler.channelRead(pipeline.context(NettyPipeline.ReactiveBridge), request);

					String baseName = null;
					if (pipeline.get(NettyPipeline.HttpCodec) != null) {
						baseName = NettyPipeline.HttpCodec;
					}
					else {
						ChannelHandler httpServerCodec = pipeline.get(HttpServerCodec.class);
						if (httpServerCodec != null) {
							baseName = pipeline.context(httpServerCodec).name();
						}
					}

					pipeline.addAfter(baseName, NettyPipeline.WsCompressionHandler, wsServerExtensionHandler);
				}
				catch (Throwable e) {
					log.error(format(channel(), ""), e);
				}
			}

			handshaker.handshake(channel,
			                     request,
			                     replaced.responseHeaders
			                             .remove(HttpHeaderNames.TRANSFER_ENCODING),
			                     handshakerResult)
			          .addListener(f -> {
			              if (replaced.rebind(this)) {
			                  markPersistent(false);
			                  // This change is needed after the Netty change https://github.com/netty/netty/pull/11966
			                  channel.read();
			              }
			              else if (log.isDebugEnabled()) {
			                  log.debug(format(channel, "Cannot bind WebsocketServerOperations after the handshake."));
			              }
			          });
		}
	}

	@Override
	public NettyOutbound send(Publisher<? extends ByteBuf> dataStream) {
		return sendObject(Flux.from(dataStream).map(bytebufToWebsocketFrame));
	}

	@Override
	public HttpHeaders headers() {
		return requestHeaders();
	}

	@Override
	@SuppressWarnings("FutureReturnValueIgnored")
	public void onInboundNext(ChannelHandlerContext ctx, Object frame) {
		if (frame instanceof CloseWebSocketFrame && ((CloseWebSocketFrame) frame).isFinalFragment()) {
			if (log.isDebugEnabled()) {
				log.debug(format(channel(), "CloseWebSocketFrame detected. Closing Websocket"));
			}
			CloseWebSocketFrame closeFrame = new CloseWebSocketFrame(true, ((CloseWebSocketFrame) frame).rsv(),
					((CloseWebSocketFrame) frame).content());
			if (closeFrame.statusCode() != -1) {
				// terminate() will invoke onInboundComplete()
				sendCloseNow(closeFrame, f -> terminate());
			}
			else {
				// terminate() will invoke onInboundComplete()
				sendCloseNow(closeFrame, WebSocketCloseStatus.EMPTY, f -> terminate());
			}
			return;
		}
		if (!this.proxyPing && frame instanceof PingWebSocketFrame) {
			//"FutureReturnValueIgnored" this is deliberate
			ctx.writeAndFlush(new PongWebSocketFrame(((PingWebSocketFrame) frame).content()));
			ctx.read();
			return;
		}
		if (frame != LastHttpContent.EMPTY_LAST_CONTENT) {
			super.onInboundNext(ctx, frame);
		}
	}

	@Override
	protected void onOutboundComplete() {
	}

	@Override
	protected void onOutboundError(Throwable err) {
		if (channel().isActive()) {
			if (log.isDebugEnabled()) {
				log.debug(format(channel(), "Outbound error happened"), err);
			}
			sendCloseNow(new CloseWebSocketFrame(WebSocketCloseStatus.PROTOCOL_ERROR),
					f -> terminate());
		}
	}

	@Override
	protected void onInboundCancel() {
		if (log.isDebugEnabled()) {
			log.debug(format(channel(), INBOUND_CANCEL_LOG));
		}
		sendCloseNow(new CloseWebSocketFrame(), WebSocketCloseStatus.ABNORMAL_CLOSURE, f -> terminate());
	}

	@Override
	public Mono<Void> sendClose() {
		return sendClose(new CloseWebSocketFrame());
	}

	@Override
	public Mono<Void> sendClose(int rsv) {
		return sendClose(new CloseWebSocketFrame(true, rsv));
	}

	@Override
	public Mono<Void> sendClose(int statusCode, @Nullable String reasonText) {
		return sendClose(new CloseWebSocketFrame(statusCode, reasonText));
	}

	@Override
	public Mono<Void> sendClose(int rsv, int statusCode, @Nullable String reasonText) {
		return sendClose(new CloseWebSocketFrame(true, rsv, statusCode, reasonText));
	}

	@Override
	@SuppressWarnings("unchecked")
	public Mono<WebSocketCloseStatus> receiveCloseStatus() {
		return onCloseState.asMono().or((Mono) onTerminate());
	}

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
					return channel().writeAndFlush(frame)
					                .addListener(ChannelFutureListener.CLOSE);
				}
				frame.release();
				return channel().newSucceededFuture();
			}).doOnCancel(() -> ReactorNetty.safeRelease(frame));
		}
		frame.release();
		return Mono.empty();
	}
	void sendCloseNow(CloseWebSocketFrame frame, ChannelFutureListener listener) {
		sendCloseNow(frame, new WebSocketCloseStatus(frame.statusCode(), frame.reasonText()), listener);
	}

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
			channel().writeAndFlush(frame)
			         .addListener(listener);
		}
		else {
			frame.release();
		}
	}

	@Override
	public boolean isWebsocket() {
		return true;
	}

	@Override
	@Nullable
	public String selectedSubprotocol() {
		return handshaker.selectedSubprotocol();
	}

	static final AtomicIntegerFieldUpdater<WebsocketServerOperations> CLOSE_SENT =
			AtomicIntegerFieldUpdater.newUpdater(WebsocketServerOperations.class,
					"closeSent");

	static final class WebsocketHttpServerMetricsHandler extends AbstractHttpServerMetricsHandler {

		final HttpServerMetricsRecorder recorder;

		WebsocketHttpServerMetricsHandler(AbstractHttpServerMetricsHandler copy) {
			super(copy);
			this.recorder = copy.recorder();
		}

		@Override
		public void channelActive(ChannelHandlerContext ctx) {
			ctx.fireChannelActive();
		}

		@Override
		public void channelInactive(ChannelHandlerContext ctx) {
			try {
				if (channelOpened && recorder instanceof MicrometerHttpServerMetricsRecorder) {
					// For custom user recorders, we don't propagate the channelInactive event, because this will be done
					// by the ChannelMetricsHandler itself. ChannelMetricsHandler is only present when the recorder is
					// not our MicrometerHttpServerMetricsRecorder. See HttpServerConfig class.
					channelOpened = false;
					// Always use the real connection local address without any proxy information
					recorder.recordServerConnectionClosed(ctx.channel().localAddress());
				}

				if (channelActivated) {
					channelActivated = false;
					// Always use the real connection local address without any proxy information
					recorder.recordServerConnectionInactive(ctx.channel().localAddress());
				}
			}
			catch (RuntimeException e) {
				// Allow request-response exchange to continue, unaffected by metrics problem
				if (log.isWarnEnabled()) {
					log.warn(format(ctx.channel(), "Exception caught while recording metrics."), e);
				}
			}
			finally {
				ctx.fireChannelInactive();
			}
		}

		@Override
		public void channelRead(ChannelHandlerContext ctx, Object msg) {
			ctx.fireChannelRead(msg);
		}

		@Override
		public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
			ctx.fireExceptionCaught(cause);
		}

		@Override
		@SuppressWarnings("FutureReturnValueIgnored")
		public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) {
			//"FutureReturnValueIgnored" this is deliberate
			ctx.write(msg, promise);
		}

		@Override
		public HttpServerMetricsRecorder recorder() {
			return recorder;
		}
	}
}
