/*
 * Copyright (c) 2011-2017 Pivotal Software Inc, All Rights Reserved.
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

package reactor.ipc.netty.http.multipart;

import java.util.concurrent.atomic.AtomicIntegerFieldUpdater;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import org.reactivestreams.Subscription;
import reactor.core.CoreSubscriber;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Operators;
import reactor.core.publisher.UnicastProcessor;
import reactor.ipc.netty.ByteBufFlux;
import reactor.util.concurrent.Queues;

/**
 * @author Ben Hale
 * @author Stephane Maldini
 */

final class MultipartParser
		implements CoreSubscriber<MultipartTokenizer.Token>, Subscription, Disposable {

	final CoreSubscriber<? super ByteBufFlux> actual;
	final ByteBufAllocator                alloc;

	volatile int wip;
	@SuppressWarnings("rawtypes")
	static final AtomicIntegerFieldUpdater<MultipartParser> WIP =
			AtomicIntegerFieldUpdater.newUpdater(MultipartParser.class, "wip");

	volatile int once;
	@SuppressWarnings("rawtypes")
	static final AtomicIntegerFieldUpdater<MultipartParser> ONCE =
			AtomicIntegerFieldUpdater.newUpdater(MultipartParser.class, "once");

	Subscription s;

	UnicastProcessor<ByteBuf> window;

	boolean done;

	MultipartParser(CoreSubscriber<? super ByteBufFlux> actual, ByteBufAllocator alloc) {
		this.actual = actual;
		this.wip = 1;
		this.alloc = alloc;
	}

	@Override
	public void onSubscribe(Subscription s) {
		if (Operators.validate(this.s, s)) {
			this.s = s;
			actual.onSubscribe(this);
		}
	}

	@Override
	public void onNext(MultipartTokenizer.Token token) {
		if (done) {
			Operators.onNextDropped(token, actual.currentContext());
			return;
		}

		UnicastProcessor<ByteBuf> w = window;

		switch (token.getKind()) {
			case BODY:
				if (window != null) {
					token.getByteBuf().touch();
					window.onNext(token.getByteBuf().retain());
					return;
				}
				s.cancel();
				actual.onError(new IllegalStateException("Body received before " +
						"delimiter"));
				break;
			case CLOSE_DELIMITER:
				s.cancel();
				onComplete();
				break;
			case DELIMITER:
				if (window != null) {
					window = null;
					w.onComplete();
				}

				WIP.getAndIncrement(this);

				w = UnicastProcessor.create(Queues.<ByteBuf>unbounded().get(), this);

				window = w;

				actual.onNext(ByteBufFlux.fromInbound(
						w.flatMap(b -> Flux.using(() -> b, Flux::just,
								ByteBuf::release)),
						alloc));
		}
	}

	@Override
	public void onError(Throwable t) {
		if (done) {
			Operators.onErrorDropped(t, actual.currentContext());
			return;
		}
		UnicastProcessor<ByteBuf> w = window;
		if (w != null) {
			window = null;
			w.onError(t);
		}

		actual.onError(t);
	}

	@Override
	public void onComplete() {
		if (done) {
			return;
		}

		UnicastProcessor<ByteBuf> w = window;
		if (w != null) {
			window = null;
			w.onComplete();
		}

		actual.onComplete();
	}

	@Override
	public void request(long n) {
		s.request(n);
	}

	@Override
	public void cancel() {
		if (ONCE.compareAndSet(this, 0, 1)) {
			dispose();
		}
	}

	@Override
	public void dispose() {
		if (WIP.decrementAndGet(this) == 0) {
			s.cancel();
		}
	}
}