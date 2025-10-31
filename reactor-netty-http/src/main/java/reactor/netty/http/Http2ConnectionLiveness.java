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
import io.netty.handler.codec.http2.Http2DataFrame;
import io.netty.handler.codec.http2.Http2FrameCodec;
import io.netty.handler.codec.http2.Http2FrameWriter;
import io.netty.handler.codec.http2.Http2HeadersFrame;
import io.netty.handler.codec.http2.Http2PingFrame;

import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadLocalRandom;

import static java.util.concurrent.TimeUnit.NANOSECONDS;
import static reactor.netty.ReactorNetty.format;

/**
 * Supports connection health checks using HTTP/2 PING frames.
 *
 * <p>This class implements liveness detection for HTTP/2 connections by sending PING frames
 * when the connection becomes idle (no read/write activity). The peer must respond with a PING ACK
 * within the configured timeout, otherwise the connection is considered unresponsive.</p>
 *
 * <h3>Behavior</h3>
 * <ul>
 *   <li>PING frames are only sent when the connection is idle (no active reads/writes)</li>
 *   <li>Each PING frame must receive an ACK within {@code pingAckTimeout}</li>
 *   <li>If no ACK is received, retry up to {@code pingAckDropThreshold} times</li>
 *   <li>If all attempts fail, the connection is closed</li>
 *   <li>Receiving any HTTP/2 frame (HEADERS, DATA, or PING ACK) cancels the health check</li>
 * </ul>
 *
 * <h3>Thread Safety</h3>
 * <p>This class is designed to be used with a single channel. All operations, including
 * scheduled tasks, execute on the channel's event loop thread, eliminating the need for
 * explicit synchronization. A new instance is created per channel.</p>
 *
 * <h3>Configuration Guidelines</h3>
 * <ul>
 *   <li><strong>pingAckTimeout:</strong> Should account for expected network latency and load.
 *       Values that are too short may cause false positives due to temporary delays.</li>
 *   <li><strong>pingAckDropThreshold:</strong> Balance between quick failure detection and tolerance
 *       for transient issues. Higher values increase detection time but reduce false positives.</li>
 * </ul>
 *
 * @author raccoonback
 * @author Violeta Georgieva
 * @since 1.2.12
 */
public final class Http2ConnectionLiveness implements HttpConnectionLiveness {

	private final Http2FrameWriter http2FrameWriter;
	private final int pingAckDropThreshold;
	private final long pingAckTimeoutNanos;

	private boolean isPingAckPending;
	private long lastSentPingData;
	private ScheduledFuture<?> pingScheduler;
	private int pingAttempts;

	/**
	 * Constructs a new {@code Http2ConnectionLiveness} instance.
	 *
	 * @param http2FrameCodec the HTTP/2 frame codec
	 * @param pingAckDropThreshold the maximum number of PING frame transmission attempts before closing the connection
	 * @param pingAckTimeoutNanos the timeout in nanoseconds for receiving a PING ACK response
	 */
	public Http2ConnectionLiveness(Http2FrameCodec http2FrameCodec, int pingAckDropThreshold, long pingAckTimeoutNanos) {
		this.http2FrameWriter = http2FrameCodec.encoder().frameWriter();
		this.pingAckDropThreshold = pingAckDropThreshold;
		this.pingAckTimeoutNanos = pingAckTimeoutNanos;
	}

	/**
	 * Cancels the scheduled ping task.
	 */
	@Override
	public void cancel() {
		isPingAckPending = false;
		pingAttempts = 0;
		if (pingScheduler != null) {
			pingScheduler.cancel(false);
			pingScheduler = null;
		}
	}

	/**
	 * Checks the liveness of the connection and schedules a ping if necessary.
	 *
	 * @param ctx the {@link ChannelHandlerContext} of the connection
	 */
	@Override
	@SuppressWarnings("FutureReturnValueIgnored")
	public void check(ChannelHandlerContext ctx) {
		if (!isPingAckPending) {
			if (log.isDebugEnabled()) {
				log.debug(format(ctx.channel(), "Connection was idle. Starting probing with PING frame: timeout={} ns."),
						pingAckTimeoutNanos);
			}

			PingTimeoutTask pingTimeoutTask = new PingTimeoutTask(ctx);
			pingTimeoutTask.writePing();
		}
	}

	/**
	 * Receives a message from the peer and processes it if it is a ping frame.
	 *
	 * @param msg the message received from the peer
	 */
	@Override
	public void receive(Object msg) {
		if (isPingAckPending) {
			if (msg instanceof Http2PingFrame) {
				Http2PingFrame frame = (Http2PingFrame) msg;
				if (frame.ack() && frame.content() == lastSentPingData) {
					cancel();
				}
			}
			else if (msg instanceof Http2HeadersFrame || msg instanceof Http2DataFrame) {
				cancel();
			}
		}
	}

	/**
	 * A task that handles ping timeouts.
	 */
	class PingTimeoutTask implements ChannelFutureListener, Runnable {

		private final ChannelHandlerContext ctx;

		PingTimeoutTask(ChannelHandlerContext ctx) {
			this.ctx = ctx;
		}

		@Override
		public void operationComplete(ChannelFuture future) {
			if (!future.channel().isActive()) {
				// Channel is not active, don't schedule next check
				return;
			}

			if (future.isSuccess()) {
				if (log.isDebugEnabled()) {
					log.debug(format(future.channel(), "PING frame was sent: ping data={}, ping attempts={}."), lastSentPingData, pingAttempts);
				}
			}
			else {
				if (log.isDebugEnabled()) {
					log.debug(format(future.channel(), "Failed to send PING frame: ping data={}, ping attempts={}. " +
							"Will wait timeout and retry based on threshold."), lastSentPingData, pingAttempts);
				}
			}

			// Schedule timeout check - whether send succeeded or failed,
			// wait the configured timeout before retry/close decision
			pingScheduler = invokeNextSchedule();
		}

		@Override
		@SuppressWarnings("FutureReturnValueIgnored")
		public void run() {
			Channel channel = ctx.channel();
			if (channel == null || !channel.isActive() || !isPingAckPending) {
				return;
			}

			if (isExceedAckDropThreshold()) {
				if (log.isDebugEnabled()) {
					log.debug(format(channel, "Closing connection due to delayed PING ACK response: timeout={} ns, attempts={}, threshold={}."),
							pingAckTimeoutNanos, pingAttempts, pingAckDropThreshold);
				}

				//"FutureReturnValueIgnored" this is deliberate
				ctx.close();
			}
			else {
				if (log.isDebugEnabled()) {
					log.debug(format(channel, "PING ACK response delayed: timeout={} ns, attempts={}, threshold={}. Retrying PING frame."),
							pingAckTimeoutNanos, pingAttempts, pingAckDropThreshold);
				}

				writePing();
			}
		}

		private ScheduledFuture<?> invokeNextSchedule() {
			return ctx.executor().schedule(this, pingAckTimeoutNanos, NANOSECONDS);
		}

		private boolean isExceedAckDropThreshold() {
			return pingAttempts >= pingAckDropThreshold;
		}

		private void writePing() {
			isPingAckPending = true;
			pingAttempts++;

			lastSentPingData = ThreadLocalRandom.current().nextLong();

			http2FrameWriter.writePing(ctx, false, lastSentPingData, ctx.newPromise())
			                .addListener(this);

			ctx.flush();
		}
	}
}
