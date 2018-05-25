/*
 * Copyright (c) 2011-2018 Pivotal Software Inc, All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package reactor.ipc.netty.http.server;

import java.util.Objects;
import java.util.function.BiFunction;

import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http2.Http2DataFrame;
import io.netty.handler.codec.http2.Http2Headers;
import io.netty.handler.codec.http2.Http2HeadersFrame;
import io.netty.handler.codec.http2.Http2StreamChannel;
import io.netty.handler.codec.http2.Http2StreamFrameToHttpObjectCodec;
import io.netty.handler.codec.http2.HttpConversionUtil;
import org.reactivestreams.Publisher;
import reactor.core.Exceptions;
import reactor.core.publisher.Mono;
import reactor.ipc.netty.Connection;
import reactor.ipc.netty.ConnectionObserver;
import reactor.ipc.netty.http2.server.Http2ServerOperations;
import reactor.ipc.netty.http2.server.Http2ServerRequest;
import reactor.ipc.netty.http2.server.Http2ServerResponse;

/**
 * @author Stephane Maldini
 */
public class HttpToH2Operations extends HttpServerOperations {

	static HttpToH2Operations create(Connection c, ConnectionObserver listener,
			Http2HeadersFrame headersFrame,
			ConnectionInfo connectionInfo) {
		try {
			if (headersFrame.isEndStream()) {
				return new HttpToH2Operations(c,
						listener,
						HttpConversionUtil.toFullHttpRequest(-1,
								headersFrame.headers(),
								c.channel()
								 .alloc(),
								false),
						headersFrame.headers(),
						connectionInfo);
			}
			else {
				return new HttpToH2Operations(c,
						listener,
						HttpConversionUtil.toHttpRequest(-1,
								headersFrame.headers(),
								false),
						headersFrame.headers(),
						connectionInfo);
			}
		}
		catch(Exception e) {
			throw Exceptions.propagate(e);
		}
	}


	final Http2Headers http2Headers;

	HttpToH2Operations(Connection c,
			ConnectionObserver listener,
			HttpRequest request,
			Http2Headers headers,
			ConnectionInfo connectionInfo) {
		super(c, listener, null, request, connectionInfo);

		this.http2Headers = headers;
	}

	@Override
	protected void onInboundNext(ChannelHandlerContext ctx, Object msg) {
		if (msg instanceof Http2DataFrame) {
			Http2DataFrame data = (Http2DataFrame) msg;
			super.onInboundNext(ctx, data.content());
			if (data.isEndStream()) {
				onInboundComplete();
			}
			return;
		}
		else if(msg instanceof Http2HeadersFrame) {
			listener().onStateChange(this, ConnectionObserver.State.CONFIGURED);
			if (((Http2HeadersFrame) msg).isEndStream()) {
				super.onInboundNext(ctx, msg);
			}
			return;
		}
		super.onInboundNext(ctx, msg);
	}

	@Override
	public Mono<Void> asHttp2(BiFunction<? super Http2ServerRequest, ? super Http2ServerResponse, ? extends Publisher<Void>> handler) {
		return withHttp2Support(handler);
	}

	final Mono<Void> withHttp2Support(
			BiFunction<? super Http2ServerRequest, ? super Http2ServerResponse, ? extends Publisher<Void>> handler) {
		Objects.requireNonNull(handler, "handler");
		if (markSentHeaders()) {
			int streamId = ((Http2StreamChannel) channel()).stream()
			                                               .id();

			Http2ServerOperations ops = new Http2ServerOperations(connection(),
					listener(),
					http2Headers,
					false,
					streamId);

			if (rebind(ops)) {
				channel().pipeline()
				         .remove(Http2StreamFrameToHttpObjectCodec.class);
				return Mono.defer(() -> Mono.fromDirect(handler.apply(ops, ops)))
				           .doAfterSuccessOrError(ops);
			}
			else {
				log.error("{} Cannot enable HTTP/2 if headers have already been sent",
						channel());
			}
		}
		return Mono.error(new IllegalStateException("Failed to upgrade to HTTP/2"));
	}
}
