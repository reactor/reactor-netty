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
package reactor.netty.http;

import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http2.Http2FrameCodec;
import io.netty.handler.codec.http2.Http2FrameWriter;
import io.netty.handler.codec.http2.Http2PingFrame;
import reactor.util.Logger;
import reactor.util.Loggers;
import reactor.util.annotation.Nullable;

import java.time.Duration;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadLocalRandom;

import static java.util.concurrent.TimeUnit.NANOSECONDS;

/**
 * Supports connection health checks using HTTP/2 Ping Frames.
 *
 * <p> Http2ConnectionLiveness sends a ping frame at the specified interval when no frame is being read or written,
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
 * @since 1.2.5
 */
public final class Http2ConnectionLiveness implements HttpConnectionLiveness {

	static final Logger log = Loggers.getLogger(Http2ConnectionLiveness.class);

	private ScheduledFuture<?> pingScheduler;

	private final ChannelFutureListener pingWriteListener = new PingWriteListener();
	private final Http2FrameWriter http2FrameWriter;
	private final long pingAckTimeoutNanos;
	private final long pingScheduleIntervalNanos;
	private final int pingAckDropThreshold;

	private int pingAckDropCount;
	private long lastSentPingData;
	private long lastReceivedPingTime;
	private long lastSendingPingTime;
	private boolean isPingAckPending;

	/**
	 * Constructs a new {@code Http2ConnectionLiveness} instance.
	 *
	 * @param http2FrameCodec      the HTTP/2 frame codec
	 * @param pingAckTimeout       the ping ACK timeout duration
	 * @param pingScheduleInterval the ping schedule interval duration
	 * @param pingAckDropThreshold the ping ACK drop threshold
	 */
	public Http2ConnectionLiveness(
			Http2FrameCodec http2FrameCodec,
			@Nullable Duration pingAckTimeout,
			@Nullable Duration pingScheduleInterval,
			@Nullable Integer pingAckDropThreshold
	) {
		this.http2FrameWriter = http2FrameCodec.encoder()
				.frameWriter();

		if (pingAckTimeout != null) {
			this.pingAckTimeoutNanos = pingAckTimeout.toNanos();
		}
		else {
			this.pingAckTimeoutNanos = 0L;
		}

		if (pingScheduleInterval != null) {
			this.pingScheduleIntervalNanos = pingScheduleInterval.toNanos();
		}
		else {
			this.pingScheduleIntervalNanos = 0L;
		}

		if (pingAckDropThreshold != null) {
			this.pingAckDropThreshold = pingAckDropThreshold;
		}
		else {
			this.pingAckDropThreshold = 0;
		}
	}

	/**
	 * Checks the liveness of the connection and schedules a ping if necessary.
	 *
	 * @param ctx the {@link ChannelHandlerContext} of the connection
	 */
	public void check(ChannelHandlerContext ctx) {
		if (isPingIntervalConfigured()) {
			if (pingScheduler == null) {
				isPingAckPending = false;
				pingAckDropCount = 0;
				pingScheduler = ctx.executor()
						.schedule(
								new PingTimeoutTask(ctx),
								pingAckTimeoutNanos,
								NANOSECONDS
						);
			}

			return;
		}

		ctx.close();
	}

	/**
	 * Receives a message from the peer and processes it if it is a ping frame.
	 *
	 * @param msg the message received from the peer
	 */
	public void receive(Object msg) {
		if (msg instanceof Http2PingFrame) {
			Http2PingFrame frame = (Http2PingFrame) msg;
			if (frame.ack() && frame.content() == lastSentPingData) {
				lastReceivedPingTime = System.nanoTime();
			}
		}
	}

	/**
	 * Checks if the ping interval is configured.
	 *
	 * @return {@code true} if the ping interval is configured, {@code false} otherwise
	 */
	public boolean isPingIntervalConfigured() {
		return pingAckTimeoutNanos > 0
				&& pingScheduleIntervalNanos > 0;
	}

	/**
	 * Cancels the scheduled ping task.
	 */
	public void cancel() {
		if (pingScheduler != null) {
			pingScheduler.cancel(false);
			pingScheduler = null;
		}
	}

	/**
	 * A task that handles ping timeouts.
	 */
	class PingTimeoutTask implements Runnable {

		private final ChannelHandlerContext ctx;

		PingTimeoutTask(ChannelHandlerContext ctx) {
			this.ctx = ctx;
		}

		@Override
		public void run() {
			Channel channel = ctx.channel();
			if (channel == null || !channel.isOpen()) {
				return;
			}

			if (!isPingAckPending) {
				if (log.isDebugEnabled()) {
					log.debug("Attempting to send a ping frame to the channel: {}", channel);
				}

				writePing(ctx);
				pingScheduler = invokeNextSchedule();
				return;
			}

			if (isOutOfTimeRange()) {
				countPingDrop();

				if (isExceedAckDropThreshold()) {
					if (log.isInfoEnabled()) {
						log.info("Closing the channel due to delayed ping frame response (timeout: {} ns). {}", pingAckTimeoutNanos, channel);
					}

					close();
					return;
				}

				if (log.isInfoEnabled()) {
					log.info("Dropping ping ACK frame in channel (ping data: {}). channel: {}", lastSentPingData, channel);
				}

				writePing(ctx);
				pingScheduler = invokeNextSchedule();
				return;
			}

			isPingAckPending = false;
			pingAckDropCount = 0;
			pingScheduler = invokeNextSchedule();
		}

		private void writePing(ChannelHandlerContext ctx) {
			lastSentPingData = ThreadLocalRandom.current().nextLong();

			http2FrameWriter
					.writePing(ctx, false, lastSentPingData, ctx.newPromise())
					.addListener(pingWriteListener);
			ctx.flush();
		}

		private boolean isOutOfTimeRange() {
			return pingAckTimeoutNanos < Math.abs(lastReceivedPingTime - lastSendingPingTime);
		}

		private void countPingDrop() {
			pingAckDropCount++;
		}

		private boolean isExceedAckDropThreshold() {
			return pingAckDropCount > pingAckDropThreshold;
		}

		private ScheduledFuture<?> invokeNextSchedule() {
			return ctx.executor()
					.schedule(
							this,
							pingScheduleIntervalNanos,
							NANOSECONDS
					);
		}

		private void close() {
			ctx.close()
					.addListener(future -> {
						if (future.isSuccess()) {
							if (log.isDebugEnabled()) {
								log.debug("Channel closed after liveness check: {}", ctx.channel());
							}
						}
						else if (log.isDebugEnabled()) {
							log.debug("Failed to close the channel: {}. Cause: {}", ctx.channel(), future.cause());
						}
					});
		}
	}

	/**
	 * A listener that handles the completion of ping frame writes.
	 */
	private class PingWriteListener implements ChannelFutureListener {

		@Override
		public void operationComplete(ChannelFuture future) {
			if (future.isSuccess()) {
				if (log.isDebugEnabled()) {
					log.debug("Successfully wrote PING frame to the channel: {}", future.channel());
				}

				isPingAckPending = true;
				lastSendingPingTime = System.nanoTime();
			}
			else {
				if (log.isDebugEnabled()) {
					log.debug("Failed to write PING frame to the channel: {}", future.channel());
				}
			}
		}
	}
}
