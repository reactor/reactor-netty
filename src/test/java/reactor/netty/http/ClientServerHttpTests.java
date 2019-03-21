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

package reactor.netty.http;

import java.nio.charset.Charset;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.function.Function;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import org.junit.After;
import org.junit.Before;
import org.junit.Ignore;
import org.junit.Test;
import org.reactivestreams.Processor;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.TopicProcessor;
import reactor.core.publisher.WorkQueueProcessor;
import reactor.netty.DisposableServer;
import reactor.netty.NettyPipeline;
import reactor.netty.http.client.HttpClient;
import reactor.netty.http.server.HttpServer;
import reactor.util.Logger;
import reactor.util.Loggers;

import static org.hamcrest.Matchers.*;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertThat;

/**
 * @author Stephane Maldini
 */
@Ignore
public class ClientServerHttpTests {

	static final Logger log = Loggers.getLogger(ClientServerHttpTests.class);

	private DisposableServer          httpServer;
	private Processor<String, String> broadcaster;

	@Test
	public void testSingleConsumerWithOneSession() {
		Sender sender = new Sender();
		sender.sendNext(10);
		List<String> data = getClientDataPromise().block(Duration.ofSeconds(30));
		assertNotNull(data);
		System.out.println(data);
		assertThat(data.size(), is(3));
		assertThat(split(data.get(0)), contains("0", "1", "2", "3", "4"));
		assertThat(split(data.get(1)), contains("5", "6", "7", "8", "9"));
		assertThat(data.get(2), containsString("END"));
		assertThat(data.get(2), is("END\n"));
	}

	@Test
	public void testSingleConsumerWithTwoSession() {
		Sender sender = new Sender();
		sender.sendNext(10);
		List<String> data1 = getClientDataPromise().block(Duration.ofSeconds(30));
		assertNotNull(data1);

		assertThat(data1.size(), is(3));
		assertThat(split(data1.get(0)), contains("0", "1", "2", "3", "4"));
		assertThat(split(data1.get(1)), contains("5", "6", "7", "8", "9"));
		assertThat(data1.get(2), containsString("END"));
		assertThat(data1.get(2), is("END\n"));

		sender.sendNext(10);
		List<String> data2 = getClientDataPromise().block(Duration.ofSeconds(30));
		assertNotNull(data2);

		// we miss batches in between client sessions so this fails
		System.out.println(data2);
		assertThat(data2.size(), is(3));
		assertThat(split(data2.get(0)), contains("10", "11", "12", "13", "14"));
		assertThat(split(data2.get(1)), contains("15", "16", "17", "18", "19"));
		assertThat(data2.get(2), containsString("END"));
		assertThat(data2.get(2), is("END\n"));
	}

	@Test
	public void testSingleConsumerWithTwoSessionBroadcastAfterConnect() throws Exception {
		Sender sender = new Sender();

		final List<String> data1 = new ArrayList<>();
		final CountDownLatch latch1 = new CountDownLatch(1);
		Runnable runner1 = () -> {
			try {
				List<String> data = getClientDataPromise().block(Duration.ofSeconds(30));
				assertNotNull(data);
				latch1.countDown();
				data1.addAll(data);
			}
			catch (Exception ie) {
				log.error("", ie);
			}
		};

		Thread t1 = new Thread(runner1, "SmokeThread1");
		t1.start();
		latch1.await();
		Thread.sleep(1000);
		sender.sendNext(10);
		t1.join();
		assertThat(data1.size(), is(3));
		assertThat(split(data1.get(0)), contains("0", "1", "2", "3", "4"));
		assertThat(split(data1.get(1)), contains("5", "6", "7", "8", "9"));
		assertThat(data1.get(2), containsString("END"));
		assertThat(data1.get(2), is("END\n"));

		final List<String> data2 = new ArrayList<>();
		final CountDownLatch latch2 = new CountDownLatch(1);
		Runnable runner2 = () -> {
			try {
				List<String> data = getClientDataPromise().block(Duration.ofSeconds(30));
				assertNotNull(data);
				latch2.countDown();
				data2.addAll(data);
			}
			catch (Exception ie) {
				log.error("", ie);
			}
		};

		Thread t2 = new Thread(runner2, "SmokeThread2");
		t2.start();
		latch2.await();
		Thread.sleep(1000);
		sender.sendNext(10);
		t2.join();
		assertThat(data2.size(), is(3));
		assertThat(split(data2.get(1)), contains("10", "11", "12", "13", "14"));
		assertThat(split(data2.get(0)), contains("15", "16", "17", "18", "19"));
		assertThat(data2.get(2), containsString("END"));
		assertThat(data2.get(2), is("END\n"));
	}

	@Test
	public void testSingleConsumerWithThreeSession() {
		Sender sender = new Sender();
		sender.sendNext(10);
		List<String> data1 = getClientDataPromise().block(Duration.ofSeconds(30));
		assertNotNull(data1);

		assertThat(data1.size(), is(3));
		assertThat(split(data1.get(0)), contains("0", "1", "2", "3", "4"));
		assertThat(split(data1.get(1)), contains("5", "6", "7", "8", "9"));
		assertThat(data1.get(2), containsString("END"));
		assertThat(data1.get(2), is("END\n"));

		sender.sendNext(10);
		List<String> data2 = getClientDataPromise().block(Duration.ofSeconds(30));
		assertNotNull(data2);

		assertThat(data2.size(), is(3));
		assertThat(split(data2.get(0)), contains("10", "11", "12", "13", "14"));
		assertThat(split(data2.get(1)), contains("15", "16", "17", "18", "19"));
		assertThat(data2.get(2), containsString("END"));
		assertThat(data2.get(2), is("END\n"));

		sender.sendNext(10);
		List<String> data3 = getClientDataPromise().block(Duration.ofSeconds(30));
		assertNotNull(data3);

		assertThat(data3.size(), is(3));
		assertThat(split(data3.get(0)), contains("20", "21", "22", "23", "24"));
		assertThat(split(data3.get(1)), contains("25", "26", "27", "28", "29"));
		assertThat(data3.get(2), containsString("END"));
		assertThat(data3.get(2), is("END\n"));
	}

	@Test
	public void testMultipleConsumersMultipleTimes() throws Exception {
		Sender sender = new Sender();

		int count = 1000;
		int threads = 5;

		for (int t = 0; t < 10; t++) {
			List<List<String>> clientDatas = getClientDatas(threads, sender, count);

			assertThat(clientDatas.size(), is(threads));

			int total = 0;
			List<String> numbersNoEnds = new ArrayList<>();
			List<Integer> numbersNoEndsInt = new ArrayList<>();
			for (int i = 0; i < clientDatas.size(); i++) {
				List<String> datas = clientDatas.get(i);
				assertThat(datas, notNullValue());
				for (int j = 0; j < datas.size(); j++) {
					String data = datas.get(j);
					List<String> split = split(data);
					for (int x = 0; x < split.size(); x++) {
						if (!split.get(x)
						          .contains("END") && !numbersNoEnds.contains(split.get(x))) {
							numbersNoEnds.add(split.get(x));
							numbersNoEndsInt.add(Integer.parseInt(split.get(x)));
						}
					}
					total += split.size();
				}
			}

			String msg =
					"Run number " + t + ", total " + total + " dups=" + findDuplicates(
							numbersNoEndsInt);
			if (numbersNoEndsInt.size() != count) {
				Collections.sort(numbersNoEndsInt);
				System.out.println(numbersNoEndsInt);
			}
			assertThat(msg, numbersNoEndsInt.size(), is(count));
			assertThat(msg, numbersNoEnds.size(), is(count));
			// should have total + END with each thread/client
			assertThat(msg, total, is(count + threads));
		}

	}

	@Before
	public void loadEnv() {
		setupFakeProtocolListener();
	}

	@After
	public void clean() {
		httpServer.disposeNow();
	}

	public Set<Integer> findDuplicates(List<Integer> listContainingDuplicates) {
		final Set<Integer> setToReturn = new HashSet<>();
		final Set<Integer> set1 = new HashSet<>();

		for (Integer yourInt : listContainingDuplicates) {
			if (!set1.add(yourInt)) {
				setToReturn.add(yourInt);
			}
		}
		return setToReturn;
	}

	private void setupFakeProtocolListener() {
		broadcaster = TopicProcessor.create();
		final Processor<List<String>, List<String>> processor =
				WorkQueueProcessor.<List<String>>builder().autoCancel(false).build();
		Flux.from(broadcaster)
		    .buffer(5)
		    .subscribe(processor);

		httpServer = HttpServer.create()
		                       .port(0)
		                       .route(r -> r.get("/data",
				                       (req, resp) -> resp.options(NettyPipeline.SendOptions::flushOnEach)
				                                          .send(Flux.from(processor)
				                                                    .log("server")
				                                                    .timeout(Duration.ofSeconds(
						                                                    2),
						                                                    Flux.empty())
				                                                    .concatWith(Flux.just(
						                                                    new ArrayList<>()))
				                                                    .map(new DummyListEncoder(
						                                                    resp.alloc()
				                                                    )))))
		                       .wiretap(true)
		                       .bindNow();
	}

	private Mono<List<String>> getClientDataPromise() {
		HttpClient httpClient =
				HttpClient.create()
				          .port(httpServer.address().getPort())
				          .wiretap(true);

		return httpClient.get()
		                 .uri("/data")
		                 .responseContent()
		                 .asString()
		                 .log("client")
		                 .collectList()
		                 .cache()
		                 .toProcessor();
	}

	private List<List<String>> getClientDatas(int threadCount, Sender sender, int count)
			throws Exception {
		final CountDownLatch latch = new CountDownLatch(1);
		final CountDownLatch promiseLatch = new CountDownLatch(threadCount);
		final ArrayList<Thread> joins = new ArrayList<>();
		final ArrayList<List<String>> datas = new ArrayList<>();

		for (int i = 0; i < threadCount; ++i) {
			Runnable runner = () -> {
				try {
					latch.await();
					Mono<List<String>> clientDataPromise = getClientDataPromise();
					promiseLatch.countDown();
					datas.add(clientDataPromise.block(Duration.ofSeconds(10)));
				}
				catch (Exception ie) {
					log.error("", ie);
				}
			};
			Thread t = new Thread(runner, "SmokeThread" + i);
			joins.add(t);
			t.start();
		}
		latch.countDown();
		promiseLatch.await();
		Thread.sleep(1000);
		sender.sendNext(count);
		for (Thread t : joins) {
			try {
				t.join();
			}
			catch (InterruptedException e) {
				log.error("", e);
			}
		}

		return datas;
	}

	private static List<String> split(String data) {
		return Arrays.asList(data.split("\\r?\\n"));
	}

	class Sender {

		int x = 0;

		void sendNext(int count) {
			for (int i = 0; i < count; i++) {
				System.out.println("XXXX " + x);
				broadcaster.onNext(x++ + "\n");
			}
		}
	}

	static class DummyListEncoder implements Function<List<String>, ByteBuf> {

		final ByteBufAllocator alloc;

		DummyListEncoder(ByteBufAllocator alloc) {
			this.alloc = alloc;
		}

		@Override
		public ByteBuf apply(List<String> t) {
			StringBuilder buf = new StringBuilder();
			if (t.isEmpty()) {
				buf.append("END\n");
			}
			else {
				for (String n : t) {
					buf.append(n);
				}
			}
			ByteBuf buffer = alloc.buffer();
			buffer.writeCharSequence(buf.toString(), Charset.defaultCharset());
			return buffer;
		}
	}
}
