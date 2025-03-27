/*
 * Copyright (c) 2011-2025 VMware, Inc. or its affiliates, All Rights Reserved.
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

import java.net.URI;
import java.util.concurrent.atomic.AtomicIntegerFieldUpdater;

import io.netty.buffer.ByteBuf;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.compression.ZlibCodecFactory;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.websocketx.CloseWebSocketFrame;
import io.netty.handler.codec.http.websocketx.PingWebSocketFrame;
import io.netty.handler.codec.http.websocketx.PongWebSocketFrame;
import io.netty.handler.codec.http.websocketx.WebSocketClientHandshakeException;
import io.netty.handler.codec.http.websocketx.WebSocketClientHandshaker;
import io.netty.handler.codec.http.websocketx.WebSocketClientHandshakerFactory;
import io.netty.handler.codec.http.websocketx.WebSocketCloseStatus;
import io.netty.handler.codec.http.websocketx.extensions.WebSocketClientExtensionHandler;
import io.netty.handler.codec.http.websocketx.extensions.compression.DeflateFrameClientExtensionHandshaker;
import io.netty.handler.codec.http.websocketx.extensions.compression.PerMessageDeflateClientExtensionHandshaker;
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

import static io.netty.handler.codec.http.LastHttpContent.EMPTY_LAST_CONTENT;
import static io.netty.handler.codec.http.websocketx.extensions.compression.PerMessageDeflateServerExtensionHandshaker.MAX_WINDOW_SIZE;
import static reactor.netty.ReactorNetty.format;

/**
 * Conversion between Netty types and Reactor types ({@link HttpOperations}.
 *
 * @author Stephane Maldini
 * @author Simon Baslé
 */
class WebsocketClientOperations extends HttpClientOperations
		implements WebsocketInbound, WebsocketOutbound {

	WebSocketClientHandshaker handshakerHttp11;
	final Sinks.One<WebSocketCloseStatus> onCloseState;
	final boolean proxyPing;

	volatile int closeSent;

	static final String INBOUND_CANCEL_LOG = "WebSocket client inbound receiver cancelled, closing Websocket.";

	WebsocketClientOperations(URI currentURI,
			WebsocketClientSpec websocketClientSpec,
			HttpClientOperations replaced) {
		super(replaced);
		this.proxyPing = websocketClientSpec.handlePing();
		onCloseState = Sinks.unsafe().one();
		initHandshaker(currentURI, websocketClientSpec);
	}

	void initHandshaker(URI currentURI, WebsocketClientSpec websocketClientSpec) {
		// Returned value is deliberately ignored
		addHandlerFirst(NettyPipeline.HttpAggregator, new HttpObjectAggregator(8192));

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
					new WebSocketClientExtensionHandler(
							perMessageDeflateClientExtensionHandshaker,
							new DeflateFrameClientExtensionHandshaker(false),
							new DeflateFrameClientExtensionHandshaker(true)));
		}

		String subprotocols = websocketClientSpec.protocols();
		handshakerHttp11 = WebSocketClientHandshakerFactory.newHandshaker(currentURI,
				websocketClientSpec.version(),
				subprotocols != null && !subprotocols.isEmpty() ? subprotocols : null,
				true,
				requestHeaders().remove(HttpHeaderNames.HOST),
				websocketClientSpec.maxFramePayloadLength());

		Channel channel = channel();
		handshakerHttp11.handshake(channel)
		                .addListener(f -> {
		                    markPersistent(false);
		                    channel.read();
		                });
	}

	@Override
	public HttpHeaders headers() {
		return responseHeaders();
	}

	@Override
	public boolean isWebsocket() {
		return true;
	}

	@Override
	@Nullable
	public String selectedSubprotocol() {
		return handshakerHttp11.actualSubprotocol();
	}

	@Override
	@SuppressWarnings("FutureReturnValueIgnored")
	public void onInboundNext(ChannelHandlerContext ctx, Object msg) {
		if (msg instanceof FullHttpResponse) {
			started = true;
			channel().pipeline()
			         .remove(HttpObjectAggregator.class);
			FullHttpResponse response = (FullHttpResponse) msg;

			setNettyResponse(response);

			if (notRedirected(response)) {
				try {
					handshakerHttp11.finishHandshake(channel(), response);
					// This change is needed after the Netty change https://github.com/netty/netty/pull/11966
					ctx.read();
					listener().onStateChange(this, HttpClientState.RESPONSE_RECEIVED);
				}
				catch (Exception e) {
					onInboundError(e);
					//"FutureReturnValueIgnored" this is deliberate
					ctx.close();
				}
				finally {
					//Release unused content (101 status)
					response.content()
					        .release();
				}
			}
			else {
				response.content()
				        .release();
				listener().onUncaughtException(this, redirecting);
			}
			return;
		}
		if (!this.proxyPing && msg instanceof PingWebSocketFrame) {
			//"FutureReturnValueIgnored" this is deliberate
			ctx.writeAndFlush(new PongWebSocketFrame(((PingWebSocketFrame) msg).content()));
			ctx.read();
			return;
		}
		if (msg instanceof CloseWebSocketFrame &&
				((CloseWebSocketFrame) msg).isFinalFragment()) {
			if (log.isDebugEnabled()) {
				log.debug(format(channel(), "CloseWebSocketFrame detected. Closing Websocket"));
			}
			CloseWebSocketFrame closeFrame = new CloseWebSocketFrame(true, ((CloseWebSocketFrame) msg).rsv(),
					((CloseWebSocketFrame) msg).content());
			if (closeFrame.statusCode() != -1) {
				sendCloseNow(closeFrame);
			}
			else {
				sendCloseNow(closeFrame, WebSocketCloseStatus.EMPTY);
			}
			onInboundComplete();
		}
		else if (msg != EMPTY_LAST_CONTENT) {
			super.onInboundNext(ctx, msg);
		}
	}

	@Override
	protected void onInboundCancel() {
		if (log.isDebugEnabled()) {
			log.debug(format(channel(), INBOUND_CANCEL_LOG));
		}
		sendCloseNow(new CloseWebSocketFrame(), WebSocketCloseStatus.ABNORMAL_CLOSURE);
	}

	@Override
	protected void onInboundClose() {
		if (isHandshakeComplete()) {
			terminate();
		}
		else {
			onInboundError(new WebSocketClientHandshakeException("Connection prematurely closed BEFORE " +
					"opening handshake is complete."));
		}
	}

	boolean isHandshakeComplete() {
		return handshakerHttp11.isHandshakeComplete();
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
			sendCloseNow(new CloseWebSocketFrame(WebSocketCloseStatus.PROTOCOL_ERROR));
		}
	}

	@Override
	public NettyOutbound send(Publisher<? extends ByteBuf> dataStream) {
		return sendObject(Flux.from(dataStream).map(bytebufToWebsocketFrame));
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

	void sendCloseNow(CloseWebSocketFrame frame) {
		sendCloseNow(frame, new WebSocketCloseStatus(frame.statusCode(), frame.reasonText()));
	}

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
			channel().writeAndFlush(frame)
			         .addListener(ChannelFutureListener.CLOSE);
		}
		else {
			frame.release();
		}
	}

	static final AtomicIntegerFieldUpdater<WebsocketClientOperations> CLOSE_SENT =
			AtomicIntegerFieldUpdater.newUpdater(WebsocketClientOperations.class,
					"closeSent");
}