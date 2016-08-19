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

package reactor.ipc.netty.nexus;

import java.io.UnsupportedEncodingException;
import java.net.InetSocketAddress;
import java.util.Collection;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReferenceFieldUpdater;
import java.util.function.Function;
import java.util.logging.Level;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.util.AsciiString;
import org.reactivestreams.Publisher;
import reactor.core.Exceptions;
import reactor.core.Loopback;
import reactor.core.publisher.BlockingSink;
import reactor.core.publisher.EmitterProcessor;
import reactor.core.publisher.Flux;
import reactor.core.publisher.FluxProcessor;
import reactor.core.publisher.Mono;
import reactor.core.publisher.UnicastProcessor;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;
import reactor.core.scheduler.TimedScheduler;
import reactor.ipc.Channel;
import reactor.ipc.netty.common.DuplexSocket;
import reactor.ipc.netty.http.HttpChannel;
import reactor.ipc.netty.http.HttpClient;
import reactor.ipc.netty.http.HttpInbound;
import reactor.ipc.netty.http.HttpServer;
import reactor.ipc.netty.tcp.TcpServer;
import reactor.ipc.util.FlowSerializerUtils;
import reactor.util.Logger;
import reactor.util.Loggers;

import static reactor.ipc.util.FlowSerializerUtils.property;

/**
 * @author Stephane Maldini
 * @since 2.5
 */
public final class Nexus extends DuplexSocket<ByteBuf, ByteBuf, Channel<ByteBuf, ByteBuf>>
		implements Function<HttpChannel, Publisher<Void>>, Loopback {

	/**
	 * Bind a new Console HTTP server to "loopback" on port {@literal 12012}. The default server
	 * implementation is scanned from the classpath on Class init. Support for Netty is provided
	 * as long as the relevant library dependencies are on the classpath. <p> To reply data on the active connection,
	 * {@link Channel#send} can subscribe to any passed {@link Publisher}. <p> Note
	 * that read will pause when capacity number of elements have been
	 * dispatched. <p> Emitted channels will run on the same thread they have beem receiving IO events.
	 *
	 * <p> The type of emitted data or received data is {@link ByteBuf}
	 * @return a new Stream of Channel, typically a peer of connections.
	 */
	public static Nexus create() {
		return create(DEFAULT_BIND_ADDRESS);
	}

	/**
	 * Bind a new TCP server to "loopback" on the given port. The default server implementation is scanned
	 * from the classpath on Class init. Support for Netty is provided as long as the relevant
	 * library dependencies are on the classpath. <p> A {@link TcpServer} is a specific kind of {@link
	 * Publisher} that will emit: - onNext {@link Channel} to consume data from - onComplete
	 * when server is shutdown - onError when any error (more specifically IO error) occurs From the emitted {@link
	 * Channel}, one can decide to add in-channel consumers to read any incoming data. <p> To reply data on the
	 * active connection, {@link Channel#send} can subscribe to any passed {@link
	 * Publisher}. <p> Note that  read will pause when capacity
	 * number of elements have been dispatched. <p> Emitted channels will run on the same thread they have beem
	 * receiving IO events.
	 *
	 * <p> The type of emitted data or received data is {@link ByteBuf}
	 * @param port the port to listen on loopback
	 * @return a new Stream of Channel, typically a peer of connections.
	 */
	public static Nexus create(int port) {
		return create(DEFAULT_BIND_ADDRESS, port);
	}

	/**
	 * Bind a new TCP server to the given bind address on port {@literal 12012}. The default server
	 * implementation is scanned from the classpath on Class init. Support for Netty is provided
	 * as long as the relevant library dependencies are on the classpath. <p> A {@link TcpServer} is a specific kind of
	 * {@link Publisher} that will emit: - onNext {@link Channel} to consume data from -
	 * onComplete when server is shutdown - onError when any error (more specifically IO error) occurs From the emitted
	 * {@link Channel}, one can decide to add in-channel consumers to read any incoming data. <p> To reply data
	 * on the active connection, {@link Channel#send} can subscribe to any passed {@link
	 * Publisher}. <p> Note that  read will pause when capacity
	 * number of elements have been dispatched. <p> Emitted channels will run on the same thread they have beem
	 * receiving IO events.
	 *
	 * <p> The type of emitted data or received data is {@link ByteBuf}
	 * @param bindAddress bind address (e.g. "127.0.0.1") to create the server on the default port 12012
	 * @return a new Stream of Channel, typically a peer of connections.
	 */
	public static Nexus create(String bindAddress) {
		return create(bindAddress, DEFAULT_PORT);
	}

	/**
	 * Bind a new TCP server to the given bind address and port. The default server implementation is scanned
	 * from the classpath on Class init. Support for Netty is provided as long as the relevant
	 * library dependencies are on the classpath. <p> A {@link TcpServer} is a specific kind of {@link
	 * Publisher} that will emit: - onNext {@link Channel} to consume data from - onComplete
	 * when server is shutdown - onError when any error (more specifically IO error) occurs From the emitted {@link
	 * Channel}, one can decide to add in-channel consumers to read any incoming data. <p> To reply data on the
	 * active connection, {@link Channel#send} can subscribe to any passed {@link
	 * Publisher}. <p> Note that  read will pause when capacity
	 * number of elements have been dispatched. <p> Emitted channels will run on the same thread they have beem
	 * receiving IO events.
	 *
	 * <p> The type of emitted data or received data is {@link ByteBuf}
	 * @param port the port to listen on the passed bind address
	 * @param bindAddress bind address (e.g. "127.0.0.1") to create the server on the passed port
	 * @return a new Stream of Channel, typically a peer of connections.
	 */
	public static Nexus create(final String bindAddress, final int port) {
		return create(HttpServer.create(bindAddress, port));
	}

	/**
	 * @param server
	 *
	 * @return
	 */
	public static Nexus create(HttpServer server) {

		Nexus nexus = new Nexus(server);
		log.info("Warping Nexus...");

		server.get(API_STREAM_URL, nexus);

		return nexus;
	}

	public static void main(String... args) throws Exception {
		log.info("Deploying Nexus... ");

		Nexus nexus = create();

		final CountDownLatch stopped = new CountDownLatch(1);

		nexus.startAndAwait();

		log.info("CTRL-C to return...");
		stopped.await();
	}

	final HttpServer                     server;
	final GraphEvent                     lastState;
	final SystemEvent                    lastSystemState;
	final FluxProcessor<Event, Event>    eventStream;
	final Scheduler                      group;
	final Function<Event, Event>         lastStateMerge;
	final TimedScheduler                 timer;
	final BlockingSink<Publisher<Event>> cannons;

	static final AsciiString ALL = new AsciiString("*");

	@SuppressWarnings("unused")
	volatile FederatedClient[] federatedClients;
	long                 systemStatsPeriod;
	boolean              systemStats;
	long websocketCapacity = 1L;

	Nexus(HttpServer server) {
		this.server = server;
		this.eventStream = EmitterProcessor.create(false);
		this.lastStateMerge = new LastGraphStateMap();
		this.timer = Schedulers.newTimer("nexus-poller");
		this.group = Schedulers.newParallel("nexus", 4);

		FluxProcessor<Publisher<Event>, Publisher<Event>> cannons = EmitterProcessor.create();

		Flux.merge(cannons)
		    .subscribe(eventStream);

		this.cannons = cannons.connectSink();

		lastState = new GraphEvent(server.getListenAddress()
		                                 .toString(), FlowSerializerUtils.createGraph());

		lastSystemState = new SystemEvent(server.getListenAddress()
		                                        .toString());
	}

	@Override
	public Publisher<Void> apply(HttpChannel channel) {
		channel.responseHeader(HttpHeaderNames.ACCESS_CONTROL_ALLOW_ORIGIN,  ALL);

		Flux<Event> eventStream = this.eventStream.publishOn(group)
		                                          .map(lastStateMerge);

		Publisher<Void> p;
		if (channel.isWebsocket()) {
			p = channel.upgradeToTextWebsocket()
			           .then(channel.send(federateAndEncode(channel, eventStream)));
		}
		else {
			p = channel.send(federateAndEncode(channel, eventStream));
		}

		channel.receiveString()
		       .subscribe(command -> {
			       int indexArg = command.indexOf("\n");
			       if (indexArg > 0) {
				       String action = command.substring(0, indexArg);
				       String arg = command.length() > indexArg ? command.substring(indexArg + 1) : null;
				       log.info("Received " + "[" + action + "]" + " " + "[" + arg + ']');
//					if(action.equals("pause") && !arg.isEmpty()){
//						((EmitterProcessor)Nexus.this.eventStream).pause();
//					}
//					else if(action.equals("resume") && !arg.isEmpty()){
//						((EmitterProcessor)Nexus.this.eventStream).resume();
//					}
			       }
		       });

		return p;
	}

	@Override
	public Object connectedInput() {
		return eventStream;
	}

	@Override
	public Object connectedOutput() {
		return server;
	}


	/**
	 * @param urls
	 *
	 * @return
	 */
	@SuppressWarnings("unchecked")
	public final Nexus federate(String... urls) {
		if (urls == null || urls.length == 0) {
			return this;
		}

		for (; ; ) {
			FederatedClient[] clients = federatedClients;

			int n;
			if (clients != null) {
				n = clients.length;
			}
			else {
				n = 0;
			}
			FederatedClient[] newClients = new FederatedClient[n + urls.length];

			if (n > 0) {
				System.arraycopy(clients, 0, newClients, 0, n);
			}

			for (int i = n; i < newClients.length; i++) {
				newClients[i] = new FederatedClient(urls[i - n]);
			}

			if (FEDERATED.compareAndSet(this, clients, newClients)) {
				break;
			}
		}

		return this;
	}

	/**
	 * @return
	 */
	public HttpServer getServer() {
		return server;
	}

	/**
	 * @return
	 */
	public final BlockingSink<Object> metricCannon() {
		UnicastProcessor<Object> p = UnicastProcessor.create();
		this.cannons.submit(p.map(new MetricMapper()));
		return p.connectSink();
	}

	/**
	 * @param o
	 * @param <E>
	 *
	 * @return
	 */
	public final <E> E monitor(E o) {
		return monitor(o, -1L);
	}

	/**
	 * @param o
	 * @param period
	 * @param <E>
	 *
	 * @return
	 */
	public final <E> E monitor(E o, long period) {
		return monitor(o, period, null);
	}

	/**
	 * @param o
	 * @param period
	 * @param unit
	 * @param <E>
	 *
	 * @return
	 */
	public final <E> E monitor(final E o, long period, TimeUnit unit) {

		final long _period = period > 0 ?  period : 400L;

		UnicastProcessor<Object> p = UnicastProcessor.create();
		log.info("State Monitoring Starting on " + FlowSerializerUtils.getName(o));
		timer.schedulePeriodically(() -> {
				if (!p.isCancelled()) {
					p.onNext(FlowSerializerUtils.scan(o));
				}
				else {
					log.info("State Monitoring stopping on " + FlowSerializerUtils.getName(o));
					throw Exceptions.failWithCancel();
				}
		}, 0L, _period, unit != null ? unit : TimeUnit.MILLISECONDS);

		this.cannons.submit(p.map(new GraphMapper()));

		return o;
	}

	/**
	 * @see this#start(Function)
	 */
	public final Mono<Void> start() throws InterruptedException {
		return start(null);
	}

	/**
	 * @see this#start(Function)
	 */
	public final void startAndAwait() throws InterruptedException {
		start().block();
		InetSocketAddress addr = server.getListenAddress();
		log.info("Nexus Warped. Transmitting signal to troops under http://" + addr.getHostName() + ":" + addr.getPort() +
				API_STREAM_URL);
	}

	/**
	 * @return
	 */
	public final BlockingSink<Object> streamCannon() {
		UnicastProcessor<Object> p = UnicastProcessor.create();
		this.cannons.submit(p.map(new GraphMapper()));
		return p.connectSink();
	}

	/**
	 * @return
	 */
	public final Nexus useCapacity(long capacity) {
		this.websocketCapacity = capacity;
		return this;
	}


	/**
	 * @return
	 */
	public final Nexus withSystemStats() {
		return withSystemStats(true, 1);
	}

	/**
	 * @param enabled
	 * @param period
	 *
	 * @return
	 */
	public final Nexus withSystemStats(boolean enabled, long period) {
		return withSystemStats(enabled, period, TimeUnit.SECONDS);
	}

	/**
	 * @param enabled
	 * @param period
	 * @param unit
	 *
	 * @return
	 */
	public final Nexus withSystemStats(boolean enabled, long period, TimeUnit unit) {
		this.systemStatsPeriod = unit == null || period < 1L ? 1000 : TimeUnit.MILLISECONDS.convert(period, unit);
		this.systemStats = enabled;
		return this;
	}

	@Override
	protected Mono<Void> doStart(Function<? super Channel<ByteBuf, ByteBuf>, ? extends Publisher<Void>> handler) {

		if (systemStats) {
			UnicastProcessor<Event> p = UnicastProcessor.create();
			this.cannons.submit(p);
			log.info("System Monitoring Starting");
			timer.schedulePeriodically(() -> {
					if (!p.isCancelled()) {
						p.onNext(lastSystemState.scan());
					}
					else {
						log.info("System Monitoring Stopped");
						throw Exceptions.failWithCancel();
					}
			}, 0L, systemStatsPeriod, TimeUnit.MILLISECONDS);
		}

		return server.start();
	}

	@Override
	protected Mono<Void> doShutdown() {
		timer.shutdown();
		this.cannons.finish();
		this.eventStream.onComplete();
		return server.shutdown();
	}

	Flux<? extends ByteBuf> federateAndEncode(HttpChannel c, Flux<Event> stream) {
		FederatedClient[] clients = federatedClients;
		if (clients == null || clients.length == 0) {
			return stream.map(BUFFER_STRING_FUNCTION);
		}
		Flux<ByteBuf> mergedUpstreams = Flux.merge(Flux.fromArray(clients)
		                                              .map(new FederatedMerger(c)));

		return Flux.merge(stream.map(BUFFER_STRING_FUNCTION), mergedUpstreams);
	}

	static class Event {

		final String nexusHost;

		public Event(String nexusHost) {
			this.nexusHost = nexusHost;
		}

		public String getNexusHost() {
			return nexusHost;
		}

		public String getType() {
			return getClass().getSimpleName();
		}
	}

	final static class GraphEvent extends Event {

		final FlowSerializerUtils.Graph graph;

		public GraphEvent(String name, FlowSerializerUtils.Graph graph) {
			super(name);
			this.graph = graph;
		}

		public FlowSerializerUtils.Graph getStreams() {
			return graph;
		}

		@Override
		public String toString() {
			return "{ " + property("streams", getStreams()) +
					", " + property("type", getType()) +
					", " + property("timestamp", System.currentTimeMillis()) +
					", " + property("nexusHost", getNexusHost()) + " }";
		}
	}

	final static class RemovedGraphEvent extends Event {

		final Collection<String> ids;

		public RemovedGraphEvent(String name, Collection<String> ids) {
			super(name);
			this.ids = ids;
		}

		public Collection<String> getStreams() {
			return ids;
		}

		@Override
		public String toString() {
			return "{ " + property("streams", getStreams()) +
					", " + property("type", getType()) +
					", " + property("timestamp", System.currentTimeMillis()) +
					", " + property("nexusHost", getNexusHost()) + " }";
		}
	}

	final static class LogEvent extends Event {

		final String message;
		final String category;
		final Level  level;
		final long   threadId;
		final String origin;
		final String data;
		final String kind;
		final long timestamp = System.currentTimeMillis();

		public LogEvent(String name, String category, Level level, String message, Object... args) {
			super(name);
			this.threadId = Thread.currentThread()
			                      .getId();
			this.message = message;
			this.level = level;
			this.category = category;
			if (args != null && args.length == 3) {
				this.kind = args[0].toString();
				this.data = args[1] != null ? args[1].toString() : null;
				this.origin = FlowSerializerUtils.getIdOrDefault(args[2]);
			}
			else {
				this.origin = null;
				this.kind = null;
				this.data = null;
			}
		}

		public String getCategory() {
			return category;
		}

		public String getData() {
			return data;
		}

		public String getKind() {
			return kind;
		}

		public Level getLevel() {
			return level;
		}

		public String getMessage() {
			return message;
		}

		public String getOrigin() {
			return origin;
		}

		public long getThreadId() {
			return threadId;
		}

		public long getTimestamp() {
			return timestamp;
		}

		@Override
		public String toString() {
			return "{ " + property("timestamp", getTimestamp()) +
					", " + property("level", getLevel().getName()) +
					", " + property("category", getCategory()) +
					(kind != null ? ", " + property("kind", getKind()) : "") +
					(origin != null ? ", " + property("origin", getOrigin()) : "") +
					(data != null ? ", " + property("data", getData()) : "") +
					", " + property("message", getMessage()) +
					", " + property("threadId", getThreadId()) +
					", " + property("type", getType()) +
					", " + property("nexusHost", getNexusHost()) + " }";
		}
	}

	final static class MetricEvent extends Event {

		public MetricEvent(String hostname) {
			super(hostname);
		}

		@Override
		public String toString() {
			return "{ " + property("nexusHost", getNexusHost()) +
					", " + property("type", getType()) +
					", " + property("timestamp", System.currentTimeMillis()) +
					" }";
		}
	}

	final static class SystemEvent extends Event {

		final Map<Thread, ThreadState> threads = new WeakHashMap<>();
		public SystemEvent(String hostname) {
			super(hostname);
		}

		public JvmStats getJvmStats() {
			return jvmStats;
		}

		public Collection<ThreadState> getThreads() {
			return threads.values();
		}

		@Override
		public String toString() {
			return "{ " + property("jvmStats", getJvmStats()) +
					", " + property("threads", getThreads()) +
					", " + property("type", getType()) +
					", " + property("timestamp", System.currentTimeMillis()) +
					", " + property("nexusHost", getNexusHost()) + " }";
		}

		SystemEvent scan() {
			int active = Thread.activeCount();
			Thread[] currentThreads = new Thread[active];
			int n = Thread.enumerate(currentThreads);

			for (int i = 0; i < n; i++) {
				if (!threads.containsKey(currentThreads[i])) {
					threads.put(currentThreads[i], new ThreadState(currentThreads[i]));
				}
			}
			return this;
		}

		final static class JvmStats {

			public int getActiveThreads() {
				return Thread.activeCount();
			}

			public int getAvailableProcessors() {
				return runtime.availableProcessors();
			}

			public long getFreeMemory() {
				return runtime.freeMemory(); //bytes
			}

			public long getMaxMemory() {
				return runtime.maxMemory(); //bytes
			}

			public long getUsedMemory() {
				return runtime.totalMemory(); //bytes
			}

			@Override
			public String toString() {
				return "{ " + property("freeMemory", getFreeMemory()) +
						", " + property("maxMemory", getMaxMemory()) +
						", " + property("usedMemory", getUsedMemory()) +
						", " + property("activeThreads", getActiveThreads()) +
						", " + property("availableProcessors", getAvailableProcessors()) + " }";
			}
		}

		final static class ThreadState {

			transient final Thread thread;

			public ThreadState(Thread thread) {
				this.thread = thread;
			}

			public long getContextHash() {
				if (thread.getContextClassLoader() != null) {
					return thread.getContextClassLoader()
					             .hashCode();
				}
				else {
					return -1;
				}
			}

			public long getId() {
				return thread.getId();
			}

			public String getName() {
				return thread.getName();
			}

			public int getPriority() {
				return thread.getPriority();
			}

			public Thread.State getState() {
				return thread.getState();
			}

			public String getThreadGroup() {
				ThreadGroup group = thread.getThreadGroup();
				return group != null ? thread.getThreadGroup()
				                             .getName() : null;
			}

			public boolean isAlive() {
				return thread.isAlive();
			}

			public boolean isDaemon() {
				return thread.isDaemon();
			}

			public boolean isInterrupted() {
				return thread.isInterrupted();
			}

			@Override
			public String toString() {
				return "{ " + property("id", getId()) +
						", " + property("name", getName()) +
						", " + property("alive", isAlive()) +
						", " + property("state", getState().name()) +
						(getThreadGroup() != null ? ", " + property("threadGroup", getThreadGroup()) : "") +
						(getContextHash() != -1 ? ", " + property("contextHash", getContextHash()) : "") +
						", " + property("interrupted", isInterrupted()) +
						", " + property("priority", getPriority()) +
						", " + property("daemon", isDaemon()) + " }";
			}
		}

		static final Runtime  runtime  = Runtime.getRuntime();
		static final JvmStats jvmStats = new JvmStats();
	}

	static class StringToBuffer implements Function<Event, ByteBuf> {

		@Override
		public ByteBuf apply(Event event) {
			try {
				return Unpooled.wrappedBuffer(event.toString()
				                     .getBytes("UTF-8"));
			}
			catch (UnsupportedEncodingException e) {
				throw Exceptions.propagate(e);
			}
		}
	}

	static final class FederatedMerger
			implements Function<FederatedClient, Publisher<ByteBuf>> {

		final HttpChannel c;

		public FederatedMerger(HttpChannel c) {
			this.c = c;
		}

		@Override
		public Publisher<ByteBuf> apply(FederatedClient o) {
			return o.client.ws(o.targetAPI)
			               .flatMap(HttpInbound::receive);
		}
	}

	static final class FederatedClient {

		final HttpClient client;
		final String     targetAPI;

		public FederatedClient(String targetAPI) {
			this.targetAPI = targetAPI;
			this.client = HttpClient.create();
		}
	}

	static final AtomicReferenceFieldUpdater<Nexus, FederatedClient[]> FEDERATED              =
			AtomicReferenceFieldUpdater.newUpdater(Nexus.class,
					FederatedClient[].class,
					"federatedClients");
	static final Logger                                                log                    =
			Loggers.getLogger(Nexus.class);
	static final String                                                API_STREAM_URL         =
			"/nexus/stream";
	static final Function<Event, ByteBuf>
	                                                                   BUFFER_STRING_FUNCTION =
			new StringToBuffer();

	class LastGraphStateMap implements Function<Event, Event> {

		@Override
		public Event apply(Event event) {
			if (GraphEvent.class.equals(event.getClass())) {
				lastState.graph.mergeWith(((GraphEvent) event).graph);
//				Collection<String> removed = lastState.graph.removeTerminatedNodes();
//
//				if(removed != null && !removed.isEmpty()){
//					return Flux.from(
//							Arrays.asList(lastState, new RemovedGraphEvent(server.getListenAddress().getHostName(), removed)));
//				}

				return lastState;
			}
			return event;
		}

		@Override
		public String toString() {
			return "ScanIfGraphEvent";
		}
	}

	final class MetricMapper implements Function<Object, Event> {

		@Override
		public Event apply(Object o) {
			return new MetricEvent(server.getListenAddress()
			                             .toString());
		}

	}

	final class GraphMapper implements Function<Object, Event> {

		@Override
		public Event apply(Object o) {
			return new GraphEvent(server.getListenAddress()
			                            .toString(),
					FlowSerializerUtils.Graph.class.equals(o.getClass()) ? ((FlowSerializerUtils.Graph) o) :
							FlowSerializerUtils.scan(o));
		}

	}
}
