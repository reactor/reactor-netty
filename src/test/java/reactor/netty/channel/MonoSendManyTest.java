/*
 * Copyright (c) 2011-2019 Pivotal Software Inc, All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *       https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package reactor.netty.channel;

import java.lang.ref.WeakReference;

import io.netty.channel.ChannelHandlerAdapter;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.timeout.WriteTimeoutHandler;
import org.junit.Test;
import org.reactivestreams.Subscription;
import reactor.core.Exceptions;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * @author Stephane Maldini
 */
public class MonoSendManyTest {

	@Test
	public void testPromiseSendTimeout() {
		//use an extra handler
		EmbeddedChannel channel = new EmbeddedChannel(new WriteTimeoutHandler(1), new ChannelHandlerAdapter() {});

		Mono<Void> m = MonoSendMany.objectSource(Flux.just("test"), channel, false);

		StepVerifier.create(m)
		            .then(() -> {
		            	channel.runPendingTasks(); //run flush
			            assertThat(channel.<String>readOutbound()).isEqualToIgnoringCase("test");
		            })
		            .verifyComplete();
	}

	@Test
	@SuppressWarnings("unchecked")
	public void cleanupFuseableSyncCloseFuture() {
		//use an extra handler
		EmbeddedChannel channel = new EmbeddedChannel(new ChannelHandlerAdapter() {});

		Mono<Void> m = MonoSendMany.objectSource(Flux.fromArray(new String[]{"test", "test2"}), channel, false);

		WeakReference[] _w = new WeakReference[1];
		StepVerifier.create(m)
		            .consumeSubscriptionWith(s -> {
			            _w[0] = new WeakReference(s);
		            })
		            .then(() -> {
			            channel.runPendingTasks();
			            assertThat(channel.<String>readOutbound()).isEqualToIgnoringCase("test");
			            assertThat(channel.<String>readOutbound()).isEqualToIgnoringCase("test2");
		            })
		            .verifyComplete();

		System.gc();
		wait(_w[0]);
	}

	@Test
	@SuppressWarnings("unchecked")
	public void cleanupFuseableAsyncCloseFuture() {
		//use an extra handler
		EmbeddedChannel channel = new EmbeddedChannel(new ChannelHandlerAdapter() {});

		Mono<Void> m = MonoSendMany.objectSource(Flux.fromArray(new String[]{"test", "test2"}).limitRate(10), channel, false);

		WeakReference[] _w = new WeakReference[1];
		StepVerifier.create(m)
		            .consumeSubscriptionWith(s -> {
			            _w[0] = new WeakReference(s);
		            })
		            .then(() -> {
			            channel.runPendingTasks();
			            assertThat(channel.<String>readOutbound()).isEqualToIgnoringCase("test");
			            assertThat(channel.<String>readOutbound()).isEqualToIgnoringCase("test2");
		            })
		            .verifyComplete();

		System.gc();
		wait(_w[0]);
	}

	@Test
	@SuppressWarnings("unchecked")
	public void cleanupFuseableErrorCloseFuture() {
		//use an extra handler
		EmbeddedChannel channel = new EmbeddedChannel(new ChannelHandlerAdapter() {});

		Mono<Void> m = MonoSendMany.objectSource(Flux.fromArray(new String[]{"test", "test2"}).concatWith(Mono.error(new Exception("boo"))).limitRate(10), channel, false);

		WeakReference[] _w = new WeakReference[1];
		StepVerifier.create(m)
		            .consumeSubscriptionWith(s -> {
			            _w[0] = new WeakReference(s);
		            })
		            .then(() -> {
			            channel.runPendingTasks();
			            assertThat(channel.<String>readOutbound()).isEqualToIgnoringCase("test");
			            assertThat(channel.<String>readOutbound()).isEqualToIgnoringCase("test2");
		            })
		            .verifyErrorMessage("boo");

		System.gc();
		wait(_w[0]);
	}

	@Test
	@SuppressWarnings("unchecked")
	public void cleanupCancelCloseFuture() {
		//use an extra handler
		EmbeddedChannel channel = new EmbeddedChannel(new ChannelHandlerAdapter() {});

		Mono<Void> m = MonoSendMany.objectSource(Flux.fromArray(new String[]{"test", "test2"}).concatWith(Mono.never()), channel, false);

		WeakReference[] _w = new WeakReference[1];
		StepVerifier.create(m)
		            .consumeSubscriptionWith(s -> {
			            _w[0] = new WeakReference(s);
		            })
		            .then(() -> {
			            channel.runPendingTasks();
		            })
		            .thenCancel()
		            .verify();

		System.gc();
		wait(_w[0]);
	}

	@Test
	@SuppressWarnings("unchecked")
	public void cleanupErrorCloseFuture() {
		//use an extra handler
		EmbeddedChannel channel = new EmbeddedChannel(new ChannelHandlerAdapter() {});

		Mono<Void> m = MonoSendMany.objectSource(Mono.error(new Exception("boo")), channel, false);

		WeakReference[] _w = new WeakReference[1];
		StepVerifier.create(m)
		            .consumeSubscriptionWith(s -> {
			            _w[0] = new WeakReference(s);
		            })
		            .then(() -> {
			            channel.runPendingTasks();
		            })
		            .verifyErrorMessage("boo");

		System.gc();
		wait(_w[0]);
	}

	static void wait(WeakReference<?> ref){
		int duration = 5_000;
		int spins = duration / 100;
		int i = 0;
		while(ref.get() != null && i < spins) {
			try {
				Thread.sleep(100);
				i++;
			}
			catch (Throwable e) {
				throw Exceptions.propagate(e);
			}
		}
		if (ref.get() != null) {
			throw new IllegalStateException("Has not cleaned");
		}
	}
}
