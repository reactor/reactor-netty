/*
 * Copyright (c) 2011-2016 Pivotal Software Inc, All Rights Reserved.
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

import java.io.IOException;

import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.handler.codec.http.DefaultHttpResponse;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.codec.http.LastHttpContent;
import io.netty.handler.codec.http.websocketx.CloseWebSocketFrame;
import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;
import reactor.core.Receiver;
import reactor.core.Trackable;
import reactor.core.publisher.Operators;

/**
 * @author Stephane Maldini
 */
final class HttpServerCloseSubscriber
		implements Subscriber<Void>, Receiver, Trackable {

	final HttpServerOperations parent;
	Subscription subscription;

	public HttpServerCloseSubscriber(HttpServerOperations parent) {
		this.parent = parent;
	}

	@Override
	public void onSubscribe(Subscription s) {
		if (Operators.validate(subscription, s)) {
			subscription = s;
			s.request(Long.MAX_VALUE);
		}
	}

	@Override
	public void onError(Throwable t) {
		if (t != null && t instanceof IOException && t.getMessage() != null && t.getMessage()
		                                                                        .contains(
				                                                                        "Broken " + "pipe")) {
			if (HttpServerOperations.log.isDebugEnabled()) {
				HttpServerOperations.log.debug("Connection closed remotely", t);
			}
			return;
		}
		HttpServerOperations.log.error("Error processing connection. Closing the channel.", t);
		if (parent.markHeadersAsSent()) {
			parent.channel()
			      .writeAndFlush(new DefaultHttpResponse(HttpVersion.HTTP_1_1,
					      HttpResponseStatus.INTERNAL_SERVER_ERROR))
			      .addListener(ChannelFutureListener.CLOSE);
		}
	}

	@Override
	public void onNext(Void aVoid) {

	}

	@Override
	public boolean isStarted() {
		return parent.channel()
		             .isActive();
	}

	@Override
	public boolean isTerminated() {
		return !parent.channel()
		              .isOpen();
	}

	@Override
	public Object upstream() {
		return subscription;
	}

	@Override
	public void onComplete() {
		if (parent.channel()
		          .isOpen()) {
			if (HttpServerOperations.log.isDebugEnabled()) {
				HttpServerOperations.log.debug("Last Http Response packet");
			}
			ChannelFuture f;
			if (!parent.isWebsocket()) {
				if (parent.markHeadersAsSent()) {
					parent.channel()
					      .write(parent.nettyResponse);
				}
				f = parent.channel()
				          .writeAndFlush(LastHttpContent.EMPTY_LAST_CONTENT);
			}
			else {
				f = parent.channel()
				          .writeAndFlush(new CloseWebSocketFrame());
			}
			if (!parent.isKeepAlive()) {
				f.addListener(ChannelFutureListener.CLOSE);
			}
		}
	}
}
