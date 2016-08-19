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
package reactor.ipc.netty.common;

import java.util.function.Supplier;

import io.netty.util.concurrent.Future;
import io.netty.util.concurrent.GenericFutureListener;
import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;
import reactor.core.publisher.Mono;

/**
 * @author Stephane Maldini
 */
public class MonoChannelFuture<C extends Future> extends Mono<Void> {

	public static Mono<Void> from(Future future){
		return new MonoChannelFuture<Future>(future);
	}

	public static Mono<Void> from(Supplier<? extends Future> deferredFuture){
		return Mono.defer(() -> new MonoChannelFuture<Future>(deferredFuture.get()));
	}

	final C future;

	protected MonoChannelFuture(C future) {
		this.future = future;
	}
	@Override
	@SuppressWarnings("unchecked")
	public final void subscribe(final Subscriber<? super Void> s) {
		future.addListener(new SubscriberFutureBridge(s));
	}

	protected void doComplete(C future, Subscriber<? super Void> s) {
		s.onComplete();
	}

	protected void doError(Subscriber<? super Void> s, Throwable throwable) {
		s.onError(throwable);
	}

	final class SubscriberFutureBridge implements GenericFutureListener<Future<?>> {

		private final Subscriber<? super Void> s;

		public SubscriberFutureBridge(Subscriber<? super Void> s) {
			this.s = s;
			s.onSubscribe(new Subscription() {
				@Override
				public void request(long n) {

				}

				@Override
				@SuppressWarnings("unchecked")
				public void cancel() {
					future.removeListener(SubscriberFutureBridge.this);
				}
			});
		}

		@Override
		@SuppressWarnings("unchecked")
		public void operationComplete(Future<?> future) throws Exception {
			if (!future.isSuccess()) {
				doError(s, future.cause());
			}
			else {
				doComplete((C) future, s);
			}
		}
	}
}
