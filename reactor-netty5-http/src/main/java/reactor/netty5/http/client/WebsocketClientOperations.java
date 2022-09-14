/*
 * Copyright (c) 2011-2022 VMware, Inc. or its affiliates, All Rights Reserved.
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
package reactor.netty5.http.client;

import java.net.URI;
import java.util.concurrent.atomic.AtomicIntegerFieldUpdater;

import io.netty5.buffer.Buffer;
import io.netty5.channel.Channel;
import io.netty5.channel.ChannelFutureListeners;
import io.netty5.channel.ChannelHandlerContext;
import io.netty5.handler.codec.http.EmptyLastHttpContent;
import io.netty5.handler.codec.http.FullHttpResponse;
import io.netty5.handler.codec.http.HttpHeaderNames;
import io.netty5.handler.codec.http.headers.HttpHeaders;
import io.netty5.handler.codec.http.HttpObjectAggregator;
import io.netty5.handler.codec.http.websocketx.CloseWebSocketFrame;
import io.netty5.handler.codec.http.websocketx.PingWebSocketFrame;
import io.netty5.handler.codec.http.websocketx.PongWebSocketFrame;
import io.netty5.handler.codec.http.websocketx.WebSocketClientHandshakeException;
import io.netty5.handler.codec.http.websocketx.WebSocketClientHandshaker;
import io.netty5.handler.codec.http.websocketx.WebSocketClientHandshakerFactory;
import io.netty5.handler.codec.http.websocketx.WebSocketCloseStatus;
import org.reactivestreams.Publisher;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import reactor.netty5.NettyOutbound;
import reactor.netty5.ReactorNetty;
import reactor.netty5.http.websocket.WebsocketInbound;
import reactor.netty5.http.websocket.WebsocketOutbound;
import reactor.util.annotation.Nullable;

import static reactor.netty5.ReactorNetty.format;

/**
 * @author Stephane Maldini
 * @author Simon Baslé
 */
final class WebsocketClientOperations extends HttpClientOperations
		implements WebsocketInbound, WebsocketOutbound {

	final WebSocketClientHandshaker handshaker;
	final Sinks.One<WebSocketCloseStatus> onCloseState;
	final boolean proxyPing;

	volatile int closeSent;

	WebsocketClientOperations(URI currentURI,
			WebsocketClientSpec websocketClientSpec,
			HttpClientOperations replaced) {
		super(replaced);
		this.proxyPing = websocketClientSpec.handlePing();
		Channel channel = channel();
		onCloseState = Sinks.unsafe().one();

		String subprotocols = websocketClientSpec.protocols();
		HttpHeaders replacedRequestHeaders = replaced.requestHeaders();
		replacedRequestHeaders.remove(HttpHeaderNames.HOST);
		handshaker = WebSocketClientHandshakerFactory.newHandshaker(currentURI,
					websocketClientSpec.version(),
					subprotocols != null && !subprotocols.isEmpty() ? subprotocols : null,
					true,
				    replacedRequestHeaders,
					websocketClientSpec.maxFramePayloadLength());

		handshaker.handshake(channel)
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
		return handshaker.actualSubprotocol();
	}

	@Override
	public void onInboundNext(ChannelHandlerContext ctx, Object msg) {
		if (msg instanceof FullHttpResponse) {
			started = true;
			channel().pipeline()
			         .remove(HttpObjectAggregator.class);
			try (FullHttpResponse response = (FullHttpResponse) msg) {

				setNettyResponse(response);

				if (notRedirected(response)) {
					try {
						handshaker.finishHandshake(channel(), response);
						// This change is needed after the Netty change https://github.com/netty/netty/pull/11966
						ctx.read();
						listener().onStateChange(this, HttpClientState.RESPONSE_RECEIVED);
					}
					catch (Exception e) {
						onInboundError(e);
						ctx.close();
					}
				}
				else {
					listener().onUncaughtException(this, redirecting);
				}
			}
			return;
		}
		if (!this.proxyPing && msg instanceof PingWebSocketFrame pingWebSocketFrame) {
			ctx.writeAndFlush(new PongWebSocketFrame(pingWebSocketFrame.binaryData()));
			ctx.read();
			return;
		}
		if (msg instanceof CloseWebSocketFrame closeWebSocketFrame &&
				closeWebSocketFrame.isFinalFragment()) {
			if (log.isDebugEnabled()) {
				log.debug(format(channel(), "CloseWebSocketFrame detected. Closing Websocket"));
			}
			CloseWebSocketFrame closeFrame = new CloseWebSocketFrame(true, closeWebSocketFrame.rsv(),
					closeWebSocketFrame.binaryData());
			if (closeFrame.statusCode() != -1) {
				sendCloseNow(closeFrame);
			}
			else {
				sendCloseNow(closeFrame, WebSocketCloseStatus.EMPTY);
			}
			onInboundComplete();
		}
		else if (!(msg instanceof EmptyLastHttpContent)) {
			super.onInboundNext(ctx, msg);
		}
	}

	@Override
	protected void onInboundCancel() {
		if (log.isDebugEnabled()) {
			log.debug(format(channel(), "Cancelling Websocket inbound. Closing Websocket"));
		}
		sendCloseNow(new CloseWebSocketFrame(true, 0, channel().bufferAllocator().allocate(0)),
				WebSocketCloseStatus.ABNORMAL_CLOSURE);
	}

	@Override
	protected void onInboundClose() {
		if (handshaker.isHandshakeComplete()) {
			terminate();
		}
		else {
			onInboundError(new WebSocketClientHandshakeException("Connection prematurely closed BEFORE " +
					"opening handshake is complete."));
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
			sendCloseNow(new CloseWebSocketFrame(channel().bufferAllocator(), WebSocketCloseStatus.PROTOCOL_ERROR));
		}
	}

	@Override
	public NettyOutbound send(Publisher<? extends Buffer> dataStream) {
		return sendObject(Flux.from(dataStream).map(bytebufToWebsocketFrame));
	}

	@Override
	public Mono<Void> sendClose() {
		return sendClose(new CloseWebSocketFrame(true, 0, channel().bufferAllocator().allocate(0)));
	}

	@Override
	public Mono<Void> sendClose(int rsv) {
		return sendClose(new CloseWebSocketFrame(channel().bufferAllocator(), true, rsv));
	}

	@Override
	public Mono<Void> sendClose(int statusCode, @Nullable String reasonText) {
		return sendClose(new CloseWebSocketFrame(channel().bufferAllocator(), statusCode, reasonText));
	}

	@Override
	public Mono<Void> sendClose(int rsv, int statusCode, @Nullable String reasonText) {
		return sendClose(new CloseWebSocketFrame(channel().bufferAllocator(), true, rsv, statusCode, reasonText));
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
			return Mono.fromCompletionStage(() -> {
				if (CLOSE_SENT.getAndSet(this, 1) == 0) {
					discard();
					// EmitResult is ignored as CLOSE_SENT guarantees that there will be only one emission
					// Whether there are subscribers or the subscriber cancels is not of interest
					// Evaluated EmitResult: FAIL_TERMINATED, FAIL_OVERFLOW, FAIL_CANCELLED, FAIL_NON_SERIALIZED
					// FAIL_ZERO_SUBSCRIBER
					onCloseState.tryEmitValue(new WebSocketCloseStatus(frame.statusCode(), frame.reasonText()));
					return channel().writeAndFlush(frame)
					                .addListener(channel(), ChannelFutureListeners.CLOSE)
					                .asStage();
				}
				frame.close();
				return channel().newSucceededFuture().asStage();
			}).doOnCancel(() -> ReactorNetty.safeRelease(frame));
		}
		frame.close();
		return Mono.empty();
	}

	void sendCloseNow(CloseWebSocketFrame frame) {
		sendCloseNow(frame, new WebSocketCloseStatus(frame.statusCode(), frame.reasonText()));
	}

	void sendCloseNow(CloseWebSocketFrame frame, WebSocketCloseStatus closeStatus) {
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
			channel().writeAndFlush(frame)
			         .addListener(channel(), ChannelFutureListeners.CLOSE);
		}
		else {
			frame.close();
		}
	}

	static final AtomicIntegerFieldUpdater<WebsocketClientOperations> CLOSE_SENT =
			AtomicIntegerFieldUpdater.newUpdater(WebsocketClientOperations.class,
					"closeSent");
}