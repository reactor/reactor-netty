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
package reactor.ipc.netty.channel;

import java.io.IOException;

import io.netty.channel.ChannelHandlerContext;
import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;

/**
 * @author Stephane Maldini
 */
final class CloseSubscriber implements Subscriber<Void> {

	final ChannelHandlerContext ctx;

	public CloseSubscriber(ChannelHandlerContext ctx) {
		this.ctx = ctx;
	}

	@Override
	public void onComplete() {
		if (NettyOperations.log.isDebugEnabled()) {
			NettyOperations.log.debug("Closing connection");
		}
		ctx.channel()
		   .eventLoop()
		   .execute(ctx::close);
	}

	@Override
	public void onError(Throwable t) {
		if (t instanceof IOException && t.getMessage()
		                                 .contains("Broken pipe")) {
			if (NettyOperations.log.isDebugEnabled()) {
				NettyOperations.log.debug("Connection closed remotely", t);
			}
			return;
		}

		NettyOperations.log.error("Error processing connection. Closing the channel.", t);

		ctx.channel()
		   .eventLoop()
		   .execute(ctx::close);
	}

	@Override
	public void onNext(Void aVoid) {
	}

	@Override
	public void onSubscribe(Subscription s) {
		s.request(Long.MAX_VALUE);
	}
}
