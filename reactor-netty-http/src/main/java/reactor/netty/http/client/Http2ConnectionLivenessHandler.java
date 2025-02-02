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

import io.netty.channel.Channel;
import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPromise;
import io.netty.handler.codec.http2.DefaultHttp2PingFrame;
import io.netty.handler.codec.http2.Http2ConnectionEncoder;
import reactor.util.Logger;
import reactor.util.Loggers;
import reactor.util.annotation.Nullable;

import java.time.Duration;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadLocalRandom;

import static java.util.concurrent.TimeUnit.NANOSECONDS;

/**
 * Handler that supports connection health checks using HTTP/2 Ping Frames.
 *
 * <p>This Handler sends a ping frame at the specified interval when no frame is being read or written,
 * ensuring the connection health is monitored. If a ping ACK frame is not received within the configured interval,
 * the connection will be closed.</p>
 *
 * <p>Ping frame checking will not be performed while a read or write operation is in progress.</p>
 *
 * <p>Be cautious when setting a very short interval, as it may cause the connection to be closed,
 * even if the keep-alive setting is enabled.</p>
 *
 * <p>If no interval is specified, no ping frame checking will be performed.</p>
 *
 * @author raccoonback
 * @since 1.2.3
 */
final class Http2ConnectionLivenessHandler extends ChannelDuplexHandler {

	private static final Logger log = Loggers.getLogger(Http2ConnectionLivenessHandler.class);

	private ScheduledFuture<?> pingScheduler;
	private final ChannelFutureListener pingWriteListener = new PingWriteListener();
	private final Http2ConnectionEncoder encoder;
	private final long pingIntervalNanos;
	private long lastSentPingData;
	private long lastReceivedPingTime;
	private long lastIoTime;
	private boolean isPingAckPending;

	public Http2ConnectionLivenessHandler(Http2ConnectionEncoder encoder, @Nullable Duration pingInterval) {
		this.encoder = encoder;

		if (pingInterval != null) {
			this.pingIntervalNanos = pingInterval.toNanos();
		}
		else {
			this.pingIntervalNanos = 0L;
		}
	}

	@Override
	public void channelActive(ChannelHandlerContext ctx) throws Exception {
		if (isPingIntervalConfigured()) {
			isPingAckPending = false;
			pingScheduler = ctx.executor()
					.schedule(
							new PingChecker(ctx),
							pingIntervalNanos,
							NANOSECONDS
					);
		}

		ctx.fireChannelActive();
	}

	@Override
	public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
		if (msg instanceof DefaultHttp2PingFrame) {
			DefaultHttp2PingFrame frame = (DefaultHttp2PingFrame) msg;
			if (frame.ack() && frame.content() == lastSentPingData) {
				lastReceivedPingTime = System.nanoTime();
			}
		}
		else {
			lastIoTime = System.nanoTime();
		}

		ctx.fireChannelRead(msg);
	}

	@Override
	@SuppressWarnings("FutureReturnValueIgnored")
	public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) throws Exception {
		lastIoTime = System.nanoTime();

		ctx.write(msg, promise);
	}

	@Override
	public void channelInactive(ChannelHandlerContext ctx) throws Exception {
		cancel();
		ctx.fireChannelInactive();
	}

	@Override
	public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
		cancel();
		ctx.fireExceptionCaught(cause);
	}

	private boolean isPingIntervalConfigured() {
		return pingIntervalNanos > 0;
	}

	private void cancel() {
		if (pingScheduler != null) {
			pingScheduler.cancel(false);
		}
	}

	private class PingChecker implements Runnable {

		private final ChannelHandlerContext ctx;

		PingChecker(ChannelHandlerContext ctx) {
			this.ctx = ctx;
		}

		@Override
		public void run() {
			Channel channel = ctx.channel();
			if (channel == null || !channel.isOpen()) {

				return;
			}

			if (lastIoTime == 0 || isIoInProgress()) {
				if (log.isDebugEnabled()) {
					log.debug("{} channel is currently reading or writing data.", channel);
				}

				isPingAckPending = false;
				pingScheduler = invokeNextSchedule();
				return;
			}

			if (!isPingAckPending) {
				if (log.isDebugEnabled()) {
					log.debug("Attempting to send a ping frame to {} channel.", channel);
				}

				writePing(ctx);
				pingScheduler = invokeNextSchedule();
				return;
			}

			if (isOutOfTimeRange()) {
				if (log.isInfoEnabled()) {
					log.info("Closing {} channel due to delayed ping frame response (timeout: {} ns).", channel, pingIntervalNanos);
				}

				close(channel);
				return;
			}

			isPingAckPending = false;
			pingScheduler = invokeNextSchedule();
		}

		private void writePing(ChannelHandlerContext ctx) {
			lastSentPingData = ThreadLocalRandom.current().nextLong();

			encoder.frameWriter()
					.writePing(ctx, false, lastSentPingData, ctx.newPromise())
					.addListener(pingWriteListener);
			ctx.flush();
		}

		private boolean isIoInProgress() {
			return pingIntervalNanos > (System.nanoTime() - lastIoTime);
		}

		private boolean isOutOfTimeRange() {
			return pingIntervalNanos < (System.nanoTime() - lastReceivedPingTime);
		}

		private ScheduledFuture<?> invokeNextSchedule() {
			return ctx.executor()
					.schedule(
							new PingChecker(ctx),
							pingIntervalNanos,
							NANOSECONDS
					);
		}

		private void close(Channel channel) {
			channel.close()
					.addListener(future -> {
						if (future.isSuccess()) {
							if (log.isDebugEnabled()) {
								log.debug("{} channel closed an channel", channel);
							}
						}
						else if (log.isDebugEnabled()) {
							log.debug("{} channel failed to close an channel", channel, future.cause());
						}
					});
		}
	}

	private class PingWriteListener implements ChannelFutureListener {

		@Override
		public void operationComplete(ChannelFuture future) throws Exception {
			if (future.isSuccess()) {
				if (log.isDebugEnabled()) {
					log.debug("Wrote PING frame to {} channel.", future.channel());
				}

				isPingAckPending = true;
			}
			else if (log.isDebugEnabled()) {
				log.debug("Failed to wrote PING frame to {} channel.", future.channel());
			}
		}
	}
}
