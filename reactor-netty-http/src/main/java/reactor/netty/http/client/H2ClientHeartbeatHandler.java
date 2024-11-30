/*
 * Copyright (c) 2021-2024 VMware, Inc. or its affiliates, All Rights Reserved.
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

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.http2.DefaultHttp2PingFrame;
import io.netty.handler.codec.http2.Http2PingFrame;
import io.netty.util.concurrent.ScheduledFuture;
import reactor.util.Logger;
import reactor.util.Loggers;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

public class H2ClientHeartbeatHandler extends SimpleChannelInboundHandler<Http2PingFrame> {
    private static final Logger log = Loggers.getLogger(Http3Codec.class);

    private final AtomicBoolean lastPingResult = new AtomicBoolean(true);
    private final AtomicInteger pingFailedTimes = new AtomicInteger(0);
    private final Map<Long, Boolean> pingResultMap = new ConcurrentHashMap<>();
    private final AtomicLong counter = new AtomicLong(1);
    private ScheduledFuture<?> pingTask;
    private final Duration pingTime;
    private final Duration pingTimeout;
    private final int maxFailedTimes;

    public H2ClientHeartbeatHandler(Duration heartbeatTime, Duration heartbeatTimeout, int maxFailedTimes) {
        super();
        this.pingTime = heartbeatTime;
        this.pingTimeout = heartbeatTimeout;
        this.maxFailedTimes = maxFailedTimes;
    }

    @Override
    public void channelRegistered(ChannelHandlerContext ctx) throws Exception {
        if (log.isDebugEnabled()) {
            log.debug("channel active begin to send PING frame");
        }
        this.pingTask = ctx.executor()
                .scheduleWithFixedDelay(new Runnable() {
                    @Override
                    public void run() {
                        long pintContent = counter.getAndIncrement();
                        DefaultHttp2PingFrame defaultHttp2PingFrame = new DefaultHttp2PingFrame(pintContent, false);
                        if (log.isDebugEnabled()) {
                            log.debug("begin send PINT frame to {} with content {}", ctx.channel().remoteAddress(), defaultHttp2PingFrame.content());
                        }
                        ctx.writeAndFlush(defaultHttp2PingFrame);
                        ctx.executor().schedule(new HeartbeatCheckTask(ctx, pintContent), pingTimeout.getSeconds(), TimeUnit.SECONDS);
                    }
                }, 5, pingTime.getSeconds(), TimeUnit.SECONDS);

        ctx.fireChannelActive();
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, Http2PingFrame msg) throws Exception {
        if (msg.ack()) {
            if (log.isDebugEnabled()) {
                log.debug("get pong frame with content {} from {} ", msg.content(), ctx.channel().remoteAddress());
                pingResultMap.put(msg.content(), true);
            }
        } else {
            if (log.isDebugEnabled()) {
                log.debug("get ping frame with content {} from {} ", msg.content(), ctx.channel().remoteAddress());
            }
        }
    }

    private final class HeartbeatCheckTask implements Runnable {
        private final ChannelHandlerContext ctx;
        private final long id;

        public HeartbeatCheckTask(ChannelHandlerContext ctx, long id) {
            this.ctx = ctx;
            this.id = id;
        }

        @Override
        public void run() {
            boolean pingResult = pingResultMap.getOrDefault(id, false);
            if (pingResult) {
                lastPingResult.compareAndSet(false, true);
                pingFailedTimes.set(0);
            } else {
                lastPingResult.compareAndSet(true, false);
                pingFailedTimes.getAndIncrement();
            }

            // heartbeat failed begin to cloud channel
            if (!lastPingResult.get() && pingFailedTimes.getAndIncrement() >= maxFailedTimes) {
                log.warn("begin to close channel {}", ctx.channel().id());
                pingTask.cancel(true);

                if (ctx.channel().isOpen()) {
                    ctx.channel().close();
                }
            }
        }
    }
}
