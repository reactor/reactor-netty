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

package reactor.ipc.netty.http;

import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import io.netty.buffer.Unpooled;
import org.junit.After;
import org.junit.Before;
import org.junit.Ignore;
import org.junit.Test;
import org.reactivestreams.Processor;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.TopicProcessor;
import reactor.core.publisher.WorkQueueProcessor;
import reactor.ipc.netty.Connection;
import reactor.ipc.netty.NettyPipeline;
import reactor.ipc.netty.http.client.HttpClient;
import reactor.ipc.netty.http.server.HttpServer;

import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.is;
import static org.junit.Assert.assertThat;

/**
 * @author Stephane Maldini
 */
@Ignore
public class SmokeTests {

	private Processor<ByteBuf, ByteBuf> processor;
	private Connection                  httpServer;

	private final AtomicInteger postReduce         = new AtomicInteger();
	private final AtomicInteger windows            = new AtomicInteger();
	private final AtomicInteger integer            = new AtomicInteger();
	private final AtomicInteger integerPostTimeout = new AtomicInteger();
	private final AtomicInteger integerPostTake    = new AtomicInteger();
	private final AtomicInteger integerPostConcat  = new AtomicInteger();

	private final int     count           = 20_000_000;
	private final int     threads         = 6;
	private final int     iter            = 20;
	private final int     windowBatch     = 200;
	private final int     takeCount       = 1000;
	private final boolean addToWindowData = count < 50_000;

	private int port;
	private boolean dummy = true;

	public SmokeTests() {
		this.port = 0;
	}
/*
    public SmokeTests(int port) {
		this.port = port;
	}*/

	@SuppressWarnings("unchecked")
	private List<Integer> windowsData  = new ArrayList<>();

	private WorkQueueProcessor<ByteBuf> workProcessor;

	@Before
	public void loadEnv() throws Exception {
		setupFakeProtocolListener();
	}

	@After
	public void clean() throws Exception {
		processor.onComplete();
		httpServer.dispose();
	}

	public Sender newSender() {
		return new Sender();
	}

	class Sender {

		int x = 0;

		void sendNext(int count) {
			for (int i = 0; i < count; i++) {
//				System.out.println("XXXX " + x);
				String data = x++ + "\n";
				processor.onNext(Unpooled.copiedBuffer(data.getBytes()));
			}
		}
	}

	public static void main(String... args) throws Exception {
		SmokeTests smokeTests = new SmokeTests();
		smokeTests.port = 8080;
		smokeTests.loadEnv();

		System.out.println("Starting on " + smokeTests.httpServer.address());
		System.out.println("Should setup a loop of wget for :" + (smokeTests.count / (smokeTests.windowBatch * smokeTests.takeCount)));

		final int count = 1_030_020;
		Runnable srunner = new Runnable() {
			final Sender sender = smokeTests.newSender();

			public void run() {
				try {
					long start = System.currentTimeMillis();
					System.out.println("Starting emitting : " + new Date(start));

					sender.sendNext(count);

					long end = System.currentTimeMillis();

					System.out.println("Finishing emitting : " + new Date(end));

					long duration = ((end - start) / 1000);

					System.out.println("Duration: " + duration);
					System.out.println("Rate: " + count / duration + " packets/sec");

					smokeTests.processor.onComplete();
					smokeTests.printStats(1);
				}
				catch (Exception ie) {
					ie.printStackTrace();
				}
			}
		};
		Thread st = new Thread(srunner, "SenderThread");
		st.start();
		System.in.read();

	}

	@Test
	public void testMultipleConsumersMultipleTimes() throws Exception {
		int fulltotalints = 0;

		for (int t = 0; t < iter; t++) {
			List<Integer> clientDatas = new ArrayList<>();
			try {
				clientDatas.addAll(getClientDatas(threads, new Sender(), count));
				Collections.sort(clientDatas);

				fulltotalints += clientDatas.size();

				System.out.println(clientDatas.size() + "/" + (integerPostConcat.get() * windowBatch));

				for (int i = 0; i < clientDatas.size(); i++) {
					if (i > 0) {
						assertThat(clientDatas.get(i - 1), is(clientDatas.get(i) - 1));
					}
				}
				assertThat(clientDatas.size(), greaterThanOrEqualTo(count));

			}
			catch (Throwable ae) {
				System.out.println("Client received: " + clientDatas.size() + " - " + (
						addToWindowData ? clientDatas : ""));
				List<Integer> dups = findDuplicates(clientDatas);
				Collections.sort(dups);
				System.out.println("Dups: " + dups.size() + " - " + dups);
				Collections.sort(windowsData);
				System.out.println("Server received: " + windowsData.size() + " - " + (
						addToWindowData ? windowsData : ""));
				dups = findDuplicates(windowsData);
				Collections.sort(dups);
				System.out.println("Dups: " + dups.size() + " - " + dups);
				throw ae;
			}
			finally {
				printStats(t);
			}
		}

		// check full totals because we know what this should be

		assertThat(fulltotalints, is(count * iter));
	}

	@Test
	public void testMultipleConsumersMultipleTimesSize() throws Exception {
		int fulltotalints = 0;

		for (int t = 0; t < iter; t++) {
			int size = 0;
			try {
				size = getClientDataSize(threads, new Sender(), count);

				fulltotalints += size;

				System.out.println(size + "/" + (postReduce.get() * windowBatch));

				assertThat(size, greaterThanOrEqualTo(count));

			}
			catch (Throwable ae) {
				System.out.println("Client received: " + size);
				Collections.sort(windowsData);
				System.out.println("Server received: " + windowsData.size() + " - " + (
						addToWindowData ? windowsData : ""));
				List<Integer> dups = findDuplicates(windowsData);
				Collections.sort(dups);
				System.out.println("Dups: " + dups.size() + " - " + dups);
				throw ae;
			}
			finally {
				printStats(t);
			}
		}

		// check full totals because we know what this should be

		assertThat(fulltotalints, is(count * iter));
	}

	private void setupFakeProtocolListener() throws Exception {

		processor = TopicProcessor.<ByteBuf>builder().autoCancel(false).build();
		workProcessor = WorkQueueProcessor.<ByteBuf>builder().autoCancel(false).build();
		Flux<ByteBuf> bufferStream = Flux.from(processor)
		                                 .window(windowBatch)
		                                 .doOnNext(d -> windows.getAndIncrement())
		                                 .flatMap(s -> s.take(Duration.ofSeconds(2))
		                                                .reduceWith(Unpooled::buffer,
				                                                ByteBuf::writeBytes))
		                                 .doOnNext(d -> postReduce.getAndIncrement())
		                                 //.log("log", LogOperator.REQUEST)
		                                 .subscribeWith(workProcessor);

		httpServer = HttpServer.create(opts -> opts.port(port))
		                       .newHandler((request, response) -> {
			                       response.chunkedTransfer(false);

			                       return response.addHeader("Content-type", "text/plain")
			                                      .addHeader("Expires", "0")
			                                      .addHeader("X-GPFDIST-VERSION",
					                                      "Spring XD")
			                                      .addHeader("X-GP-PROTO", "1")
			                                      .addHeader("Cache-Control", "no-cache")
			                                      .addHeader("Connection", "close")
			                                      .options(NettyPipeline.SendOptions::flushOnEach)
			                                      .send(bufferStream.doOnNext(d -> integer.getAndIncrement())
			                                                        .take(takeCount)
			                                                        .doOnNext(d -> integerPostTake.getAndIncrement())
			                                                        .timeout(Duration.ofSeconds(2), Flux.empty())
			                                                        .doOnNext(d -> integerPostTimeout.getAndIncrement())
			                                                        .concatWith(Flux.just(
					                                                        dummy ?
							                                                        Unpooled.copiedBuffer(
									                                                        new byte[0]) :
							                                                        Unpooled.copiedBuffer(
									                                                        "END".getBytes()))
			                                                                        .doOnComplete(
					                                                                        integerPostConcat::decrementAndGet))//END
			                                                        .doOnNext(d -> integerPostConcat.getAndIncrement())
			                                                        .map(dummy ?
					                                                        Function.identity() :
					                                                        new GpdistCodec(
							                                                        response
							                                                               .alloc())));
		                       })
		                       .block(Duration.ofSeconds(30));

	}

	private List<String> getClientDataPromise() throws Exception {
		HttpClient httpClient =
				HttpClient.create(opts -> opts.host("localhost")
						              .port(httpServer.address().getPort()));

		Mono<List<String>> content = httpClient.get("/data")
		                                       .flatMap(f -> f.receive()
		                                                   .asString()
		                                                   .collectList())
		                                       .cache();

		List<String> res = content.block(Duration.ofSeconds(30));
		return res;
	}

	@SuppressWarnings("unchecked")
	private List<Integer> getClientDatas(int threadCount, final Sender sender, int count)
			throws Exception {
		final CountDownLatch latch = new CountDownLatch(1);
		final List<Integer> datas = new ArrayList<>();

		windowsData.clear();
		Thread.sleep(1500);
		Runnable srunner = new Runnable() {
			public void run() {
				try {
					sender.sendNext(count);
				}
				catch (Exception ie) {
					ie.printStackTrace();
				}
			}
		};
		Thread st = new Thread(srunner, "SenderThread");
		st.start();
		CountDownLatch thread = new CountDownLatch(threadCount);
		AtomicInteger counter = new AtomicInteger();
		for (int i = 0; i < threadCount; ++i) {
			Runnable runner = () -> {
				try {
					boolean empty = false;
					while (true) {
						List<String> res = getClientDataPromise();
						if (res == null) {
							if (empty) {
								break;
							}
							empty = true;
							continue;
						}

						List<Integer> collected = parseCollection(res);
						Collections.sort(collected);
						int size = collected.size();

						//previous empty
						if (size == 0 && empty) {
							break;
						}

						synchronized (datas) {
							datas.addAll(collected);
						}
						counter.addAndGet(size);
						empty = size == 0;
						System.out.println("Client received " + size + " elements, current total: " + counter + ", batches: " + integerPostConcat + ", between [ " + (
								size > 0 ?
										collected.get(0) + " -> " + collected.get(size - 1) :
										"") + " ]");
					}
					System.out.println("Client finished");
				}
				catch (Exception ie) {
					ie.printStackTrace();
				}
				finally {
					thread.countDown();
				}
			};
			Thread t = new Thread(runner, "SmokeThread" + i);
			t.start();
		}
		latch.countDown();

		thread.await(500, TimeUnit.SECONDS);
		return datas;
	}

	public List<Integer> findDuplicates(List<Integer> listContainingDuplicates) {
		final List<Integer> setToReturn = new ArrayList<>();
		final Set<Integer> set1 = new HashSet<>();

		for (Integer yourInt : listContainingDuplicates) {
			if (!set1.add(yourInt)) {
				setToReturn.add(yourInt);
			}
		}
		return setToReturn;
	}

	@SuppressWarnings("unchecked")
	private int getClientDataSize(int threadCount, final Sender sender, int count)
			throws Exception {
		final CountDownLatch latch = new CountDownLatch(1);

		windowsData.clear();
		Thread.sleep(1500);
		Runnable srunner = () -> {
			try {
				sender.sendNext(count);
			}
			catch (Exception ie) {
				ie.printStackTrace();
			}
		};
		Thread st = new Thread(srunner, "SenderThread");
		st.start();
		CountDownLatch thread = new CountDownLatch(threadCount);
		AtomicInteger counter = new AtomicInteger();
		for (int i = 0; i < threadCount; ++i) {
			Runnable runner = () -> {
				try {
					boolean empty = false;
					while (true) {
						List<String> res = getClientDataPromise();
						if (res == null) {
							if (empty) {
								break;
							}
							empty = true;
							continue;
						}

						List<Integer> collected = parseCollection(res);
						Collections.sort(collected);
						int size = collected.size();

						//previous empty
						if (size == 0 && empty) {
							break;
						}

						counter.addAndGet(size);
						empty = size == 0;
						System.out.println("Client received " + size + " elements, current total: " + counter + ", batches: " + integerPostConcat + ", between [ " + (
								size > 0 ?
										collected.get(0) + " -> " + collected.get(size - 1) :
										"") + " ]");
					}
					System.out.println("Client finished");
				}
				catch (Exception ie) {
					ie.printStackTrace();
				}
				finally {
					thread.countDown();
				}
			};
			Thread t = new Thread(runner, "SmokeThread" + i);
			t.start();
		}
		latch.countDown();

		thread.await(500, TimeUnit.SECONDS);
		return counter.get();
	}

	private List<Integer> parseCollection(List<String> res) {
		StringBuilder buf = new StringBuilder();
		for (int j = 0; j < res.size(); j++) {
			buf.append(res.get(j));
		}
		return parseCollection(buf.toString());
	}

	private List<Integer> parseCollection(String res) {
		List<Integer> integers = new ArrayList<>();

		//System.out.println(Thread.currentThread()+res.replaceAll("\n",","));
		List<String> split = split(res);
		for (String d : split) {
			if (d != null && !d.trim()
			                   .isEmpty() && !d.contains("END")) {
				integers.add(Integer.parseInt(d));
			}
		}
		return integers;
	}

	private static List<String> split(String data) {
		return Arrays.asList(data.split("\\r?\\n"));
	}

	private void printStats(int t) {
		System.out.println("\n" + "---- STATISTICS ----------------- \n" + "run: " + (t + 1) + " \n" + "windowed : " + windows + " \n" + "post reduce: " + postReduce + " \n" + "client batches: " + integer + " \n" + "post take batches: " + integerPostTake + "\n" + "post timeout batches: " + integerPostTimeout + "\n" + "post concat batches: " + integerPostConcat + "\n" + "-----------------------------------");

	}

	public static class GpdistCodec implements Function<ByteBuf, ByteBuf> {

		static final byte[] h1 = Character.toString('D')
		                                  .getBytes(Charset.forName("UTF-8"));

		final ByteBufAllocator alloc;

		public GpdistCodec(ByteBufAllocator alloc) {
			this.alloc = alloc;
		}

		@SuppressWarnings("resource")
		@Override
		public ByteBuf apply(ByteBuf t) {
//			Buffer b = t.flip();
//			//System.out.println("XXXXXX " + Thread.currentThread()+" "+b.asString().replaceAll("\n", ", "));
//			return b;
			int size = t.capacity();
			byte[] h2 = ByteBuffer.allocate(4)
			                      .putInt(size)
			                      .array();
			return alloc.buffer()
			            .writeBytes(h1)
			            .writeBytes(h2)
			            .writeBytes(t);
		}
	}

	/*

	Flux<Buffer> bufferStream = Streams
				.wrap(processor)
				.window(windowBatch, 2, TimeUnit.SECONDS)
				.flatMap(s -> s
						.doOnNext(d ->
										windows.getAndIncrement()
						)
						.reduce(new Buffer(), Buffer::append)
						.doOnNext(d ->
										postReduce.getAndIncrement()
						))
				.subscribeWith(workProcessor);


	request.send(bufferStream
//							.doOnNext(d ->
//											integer.getAndIncrement()
//							)
							.take(takeCount)
//							.doOnNext(d ->
//											integerPostTake.getAndIncrement()
//							)
							.timeout(2, TimeUnit.SECONDS, Streams.<Buffer>empty())
//							.doOnNext(d ->
//											integerPostTimeout.getAndIncrement()
//							)
									//.concatWith(Streams.just(new Buffer().append("END".getBytes(Charset.forName
									("UTF-8")))))

							.concatWith(Streams.just(GpdistCodec.class.equals(codec.getClass()) ?  Buffer.wrap(new
							byte[0]) : Buffer
							.wrap("END")))//END
//							.doOnNext(d -> {
//										if (addToWindowData) {
//											windowsData.addAll(parseCollection(d.asString()));
//										}
//									}
//							)
//							.doOnNext(d ->
//											integerPostConcat.getAndIncrement()
//							)
//							.doOnComplete(no -> {
//										integerPostConcat.decrementAndGet();
//										System.out.println("YYYYY COMPLETE " + Thread.currentThread());
//									}
//							)
					//.log("writer")
	 */
}
