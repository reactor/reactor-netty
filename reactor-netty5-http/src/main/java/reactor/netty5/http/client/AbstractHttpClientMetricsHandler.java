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
package reactor.netty5.http.client;

import io.netty5.buffer.Buffer;
import io.netty5.channel.Channel;
import io.netty5.channel.ChannelHandlerAdapter;
import io.netty5.channel.ChannelHandlerContext;
import io.netty5.handler.codec.http.HttpContent;
import io.netty5.handler.codec.http.HttpRequest;
import io.netty5.handler.codec.http.HttpResponse;
import io.netty5.handler.codec.http.LastHttpContent;
import io.netty5.util.concurrent.Future;
import reactor.netty5.channel.ChannelOperations;
import reactor.util.annotation.Nullable;
import reactor.util.context.ContextView;
import reactor.util.Logger;
import reactor.util.Loggers;

import java.net.SocketAddress;
import java.time.Duration;
import java.util.function.Function;

import static reactor.netty5.ReactorNetty.format;

/**
 * {@link ChannelDuplexHandler} for handling {@link HttpClient} metrics.
 *
 * @author Violeta Georgieva
 * @since 1.0.8
 */
abstract class AbstractHttpClientMetricsHandler extends ChannelHandlerAdapter {

	private static final Logger log = Loggers.getLogger(AbstractHttpClientMetricsHandler.class);

	final SocketAddress proxyAddress;
	final SocketAddress remoteAddress;

	String path;

	String method;

	String status;

	ContextView contextView;


	long dataReceived;

	long dataSent;


	long dataReceivedTime;

	long dataSentTime;

	final Function<String, String> uriTagValue;

	int lastReadSeq;

	int lastWriteSeq;

	protected AbstractHttpClientMetricsHandler(SocketAddress remoteAddress, @Nullable SocketAddress proxyAddress, @Nullable Function<String, String> uriTagValue) {
		this.proxyAddress = proxyAddress;
		this.remoteAddress = remoteAddress;
		this.uriTagValue = uriTagValue;
	}

	protected AbstractHttpClientMetricsHandler(AbstractHttpClientMetricsHandler copy) {
		this.contextView = copy.contextView;
		this.dataReceived = copy.dataReceived;
		this.dataReceivedTime = copy.dataReceivedTime;
		this.dataSent = copy.dataSent;
		this.dataSentTime = copy.dataSentTime;
		this.method = copy.method;
		this.path = copy.path;
		this.proxyAddress = copy.proxyAddress;
		this.remoteAddress = copy.remoteAddress;
		this.status = copy.status;
		this.uriTagValue = copy.uriTagValue;
		this.lastWriteSeq = copy.lastWriteSeq;
		this.lastReadSeq = copy.lastReadSeq;
	}

	@Override
	public Future<Void> write(ChannelHandlerContext ctx, Object msg) {
		try {
			if (msg instanceof HttpRequest request) {
				extractDetailsFromHttpRequest(ctx, request);
			}

			dataSent += extractProcessedDataFromBuffer(msg);

			if (msg instanceof LastHttpContent) {
				int currentLastWriteSeq = lastWriteSeq;
				return ctx.write(msg)
				          .addListener(future -> {
				              try {
				                  /**
				                   * Records write metrics information after sending the last part of the request.
				                   * There are two cases to consider:
				                   *
				                   * 1. Netty's Selector processes OP_READ operations after OP_WRITE operations, but in Netty 5,
				                   *    write listeners are invoked asynchronously. This can lead to a situation where the
				                   *    channelRead method has already read the response before write listener has been invoked.
				                   *    In such cases, the channelRead method has already called 'recordWrites' and 'reset' itself.
				                   *
				                   * 2. When sending a large POST request, it's possible that an early response has already been
				                   *    read by the channelRead method even if we are still in the process of writing the request.
				                   *    In this scenario (e.g., encountering a 400 Bad Request), the channelRead method may have
				                   *    already called 'recordWrites' and 'reset' itself.
				                   *
				                   * To determine whether the channelRead method has already invoked 'recordWrites' and 'reset',
				                   * we use the 'lastWriteSeq' and 'lastReadSeq' sequence numbers.
				                   */
				                  if (currentLastWriteSeq == lastWriteSeq) {
				                      lastWriteSeq = (lastWriteSeq + 1) & 0x7F_FF_FF_FF;
				                      recordWrite(remoteAddress);
				                  }
				              }
				              catch (RuntimeException e) {
				                  // Allow request-response exchange to continue, unaffected by metrics problem
				                  if (log.isWarnEnabled()) {
				                      log.warn(format(ctx.channel(), "Exception caught while recording metrics."), e);
				                  }
				              }
				});
			}
		}
		catch (RuntimeException e) {
			// Allow request-response exchange to continue, unaffected by metrics problem
			if (log.isWarnEnabled()) {
				log.warn(format(ctx.channel(), "Exception caught while recording metrics."), e);
			}
		}

		return ctx.write(msg);
	}

	@Override
	public void channelRead(ChannelHandlerContext ctx, Object msg) {
		try {
			if (msg instanceof HttpResponse response) {
				status = response.status().codeAsText().toString();

				startRead(response);
			}

			dataReceived += extractProcessedDataFromBuffer(msg);

			if (msg instanceof LastHttpContent) {
				// Detect if we have received an early response before the write listener has been invoked.
				// In this case, invoke #recordWrite now (because next we will reset all class fields).
				lastReadSeq = (lastReadSeq + 1) & 0x7F_FF_FF_FF;
				if ((lastReadSeq > lastWriteSeq) || (lastReadSeq == 0 && lastWriteSeq == Integer.MAX_VALUE)) {
					lastWriteSeq = (lastWriteSeq + 1) & 0x7F_FF_FF_FF;
					recordWrite(remoteAddress);
				}
				recordRead(ctx.channel(), remoteAddress);
				reset();
			}
		}
		catch (RuntimeException e) {
			// Allow request-response exchange to continue, unaffected by metrics problem
			if (log.isWarnEnabled()) {
				log.warn(format(ctx.channel(), "Exception caught while recording metrics."), e);
			}
		}

		ctx.fireChannelRead(msg);
	}

	@Override
	public void channelExceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
		try {
			recordException(ctx);
		}
		catch (RuntimeException e) {
			// Allow request-response exchange to continue, unaffected by metrics problem
			if (log.isWarnEnabled()) {
				log.warn(format(ctx.channel(), "Exception caught while recording metrics."), e);
			}
		}

		ctx.fireChannelExceptionCaught(cause);
	}

	private void extractDetailsFromHttpRequest(ChannelHandlerContext ctx, HttpRequest request) {
		method = request.method().name();

		ChannelOperations<?, ?> channelOps = ChannelOperations.get(ctx.channel());
		if (channelOps instanceof HttpClientOperations ops) {
			path = uriTagValue == null ? ops.path : uriTagValue.apply(ops.path);
			contextView = ops.currentContextView();
		}

		startWrite(request, ctx.channel(), remoteAddress);
	}

	private long extractProcessedDataFromBuffer(Object msg) {
		if (msg instanceof Buffer buffer) {
			return buffer.readableBytes();
		}
		else if (msg instanceof HttpContent<?> httpContent) {
			return httpContent.payload().readableBytes();
		}
		return 0;
	}

	protected abstract HttpClientMetricsRecorder recorder();

	protected void recordException(ChannelHandlerContext ctx) {
		if (proxyAddress == null) {
			recorder().incrementErrorsCount(remoteAddress, path != null ? path : resolveUri(ctx));
		}
		else {
			recorder().incrementErrorsCount(remoteAddress, proxyAddress, path != null ? path : resolveUri(ctx));
		}
	}

	protected void recordRead(Channel channel, SocketAddress address) {
		if (proxyAddress == null) {
			recorder().recordDataReceivedTime(address, path, method, status,
					Duration.ofNanos(System.nanoTime() - dataReceivedTime));

			recorder().recordResponseTime(address, path, method, status,
					Duration.ofNanos(System.nanoTime() - dataSentTime));

			recorder().recordDataReceived(address, path, dataReceived);
		}
		else {
			recorder().recordDataReceivedTime(address, proxyAddress, path, method, status,
					Duration.ofNanos(System.nanoTime() - dataReceivedTime));

			recorder().recordResponseTime(address, proxyAddress, path, method, status,
					Duration.ofNanos(System.nanoTime() - dataSentTime));

			recorder().recordDataReceived(address, proxyAddress, path, dataReceived);
		}
	}

	protected void recordWrite(SocketAddress address) {
		if (proxyAddress == null) {
			recorder().recordDataSentTime(address, path, method,
					Duration.ofNanos(System.nanoTime() - dataSentTime));

			recorder().recordDataSent(address, path, dataSent);
		}
		else {
			recorder().recordDataSentTime(address, proxyAddress, path, method,
					Duration.ofNanos(System.nanoTime() - dataSentTime));

			recorder().recordDataSent(address, proxyAddress, path, dataSent);
		}
	}

	protected void reset() {
		path = null;
		method = null;
		status = null;
		contextView = null;
		dataReceived = 0;
		dataSent = 0;
		dataReceivedTime = 0;
		dataSentTime = 0;
		// don't reset lastWriteSeq and lastReadSeq, which must be incremented forever
	}

	protected void startRead(HttpResponse msg) {
		dataReceivedTime = System.nanoTime();
	}

	protected void startWrite(HttpRequest msg, Channel channel, SocketAddress address) {
		dataSentTime = System.nanoTime();
	}

	private String resolveUri(ChannelHandlerContext ctx) {
		ChannelOperations<?, ?> channelOps = ChannelOperations.get(ctx.channel());
		if (channelOps instanceof HttpClientOperations ops) {
			String path = ops.uri();
			return uriTagValue == null ? path : uriTagValue.apply(path);
		}
		else {
			return "unknown";
		}
	}
}
