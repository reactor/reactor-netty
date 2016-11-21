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

import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;
import reactor.core.publisher.Operators;

/**
 * @author Stephane Maldini
 */
final class OutboundCloseSubscriber implements Subscriber<Void> {

	final ChannelOperations<?, ?> parent;

	Subscription subscription;

	public OutboundCloseSubscriber(ChannelOperations<?, ?> ops) {
		this.parent = ops;
	}

	@Override
	public void onComplete() {
		if(parent.channel.eventLoop().inEventLoop()){
			parent.onOutboundComplete();
		}
		else {
			parent.channel.eventLoop()
			              .execute(parent::onOutboundComplete);
		}
	}

	@Override
	public void onError(Throwable t) {
		if(parent.channel.eventLoop().inEventLoop()){
			parent.onOutboundError(t);
		}
		else {
			parent.channel.eventLoop()
			              .execute(() -> parent.onOutboundError(t));
		}
	}

	@Override
	public void onNext(Void aVoid) {
	}

	@Override
	public void onSubscribe(Subscription s) {
		if (Operators.validate(subscription, s)) {
			subscription = s;
			s.request(Long.MAX_VALUE);
			if(!parent.context.getClass().equals(ServerContextHandler.class)){
				parent.channel.read();
			}
		}
	}
}
