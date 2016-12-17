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

import java.util.function.Consumer;

import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;
import reactor.core.Cancellation;
import reactor.core.publisher.Operators;

/**
 * @author Stephane Maldini
 */
final class OutboundCloseSubscriber implements Subscriber<Void>, Runnable,
                                               Consumer<Throwable> {

	final ChannelOperations<?, ?> parent;

	Cancellation c;
	boolean      done;
	Subscription subscription;

	OutboundCloseSubscriber(ChannelOperations<?, ?> ops) {
		this.parent = ops;
	}

	@Override
	public void accept(Throwable throwable) {
		Subscription subscription = this.subscription;
		this.subscription = null;
		if (subscription != null) {
			subscription.cancel();
		}
	}

	@Override
	public void onComplete() {
		if (done) {
			return;
		}
		done = true;
		parent.channel.eventLoop()
		              .execute(parent::onOutboundComplete);
	}

	@Override
	public void onError(Throwable t) {
		if (done) {
			Operators.onErrorDropped(t);
			return;
		}
		done = true;
		parent.channel.eventLoop()
		              .execute(() -> parent.onOutboundError(t));
	}

	@Override
	public void onNext(Void aVoid) {
	}

	@Override
	public void onSubscribe(Subscription s) {
		if (Operators.validate(subscription, s)) {
			if (parent.channel.isOpen()) {
				subscription = s;

				this.c = parent.onInactive.subscribe(null, this, this);

				s.request(Long.MAX_VALUE);
				if (!parent.context.getClass()
				                   .equals(ServerContextHandler.class)) {
					parent.channel.read();
				}
			}
			else {
				s.cancel();
			}
		}
	}

	@Override
	public void run() {
		Subscription subscription = this.subscription;
		this.subscription = null;
		if (subscription != null) {
			subscription.cancel();
		}
	}

}
