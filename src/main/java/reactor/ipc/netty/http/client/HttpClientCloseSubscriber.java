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
package reactor.ipc.netty.http.client;

import java.io.IOException;

import io.netty.channel.ChannelHandlerContext;
import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;
import reactor.core.Exceptions;
import reactor.core.publisher.Operators;

/**
 * @author Stephane Maldini
 */
class HttpClientCloseSubscriber implements Subscriber<Void> {

	final ChannelHandlerContext ctx;
	final HttpClientOperations parent;


	public HttpClientCloseSubscriber(HttpClientOperations parent, ChannelHandlerContext ctx) {
		this.parent = parent;
		this.ctx = ctx;
	}

	@Override
	public void onComplete() {
	}

	@Override
	public void onError(Throwable t) {
		if (t == null) {
			throw Exceptions.argumentIsNullException();
		}
		if (t instanceof IOException && t.getMessage() != null && t.getMessage()
		                                                           .contains(
				                                                           "Broken pipe")) {
			if (HttpClientOperations.log.isDebugEnabled()) {
				HttpClientOperations.log.debug("Connection closed remotely", t);
			}
			return;
		}
		if (ctx.channel()
		       .isOpen()) {
			if (HttpClientOperations.log.isDebugEnabled()) {
				HttpClientOperations.log.error("Disposing HTTP channel due to error", t);
			}
			parent.cancel();
		}
	}

	@Override
	public void onNext(Void aVoid) {
	}

	@Override
	public void onSubscribe(final Subscription s) {
		ctx.read();
		Operators.validate(null, s);
		s.request(Long.MAX_VALUE);
	}
}
