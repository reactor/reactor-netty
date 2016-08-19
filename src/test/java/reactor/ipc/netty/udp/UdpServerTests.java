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
package reactor.ipc.netty.udp;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.MulticastSocket;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.net.StandardProtocolFamily;
import java.nio.ByteBuffer;
import java.nio.channels.DatagramChannel;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Enumeration;
import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import io.netty.util.NetUtil;
import org.junit.After;
import org.junit.Before;
import org.junit.Ignore;
import org.junit.Test;
import reactor.core.publisher.Flux;
import reactor.core.scheduler.Schedulers;
import reactor.ipc.netty.udp.UdpServer;
import reactor.util.Logger;
import reactor.util.Loggers;
import reactor.ipc.netty.config.ServerOptions;
import reactor.ipc.netty.util.SocketUtils;

import static org.hamcrest.MatcherAssert.assertThat;

/**
 * @author Jon Brisbin
 * @author Stephane Maldini
 */
public class UdpServerTests {

	final Logger log = Loggers.getLogger(getClass());

	ExecutorService threadPool;

	@Before
	public void setup() {
		threadPool = Executors.newCachedThreadPool();
	}

	@After
	public void cleanup() throws InterruptedException {
		threadPool.shutdown();
		threadPool.awaitTermination(5, TimeUnit.SECONDS);
		Schedulers.shutdownNow();
	}

	@Test
	@Ignore
	public void supportsReceivingDatagrams() throws InterruptedException {
		final int port = SocketUtils.findAvailableUdpPort();
		final CountDownLatch latch = new CountDownLatch(4);

		final UdpServer server = UdpServer.create(port);

		server.start(ch -> {
			ch.receiveByteArray()
			  .log()
			  .subscribe(bytes -> {
				if (bytes.length == 1024) {
					latch.countDown();
				}
			});
			return Flux.never();
		}).doOnSuccess(v -> {
			try {
				DatagramChannel udp = DatagramChannel.open();
				udp.configureBlocking(true);
				udp.connect(new InetSocketAddress(InetAddress.getLocalHost(), port));

				byte[] data = new byte[1024];
				new Random().nextBytes(data);
				for (int i = 0; i < 4; i++) {
					udp.write(ByteBuffer.wrap(data));
				}

				udp.close();
			}
			catch (IOException e) {
				e.printStackTrace();
			}
		});

		assertThat("latch was counted down", latch.await(10, TimeUnit.SECONDS));
		server.shutdown().block();
	}

	@Test
	@SuppressWarnings("unchecked")
	public void supportsUdpMulticast() throws Exception {
		final int port = SocketUtils.findAvailableUdpPort();
		final CountDownLatch latch = new CountDownLatch(4 ^ 2);

		final InetAddress multicastGroup = InetAddress.getByName("230.0.0.1");
		final NetworkInterface multicastInterface = findMulticastEnabledIPv4Interface();
		log.info("Using network interface '{}' for multicast", multicastInterface);
		final Collection<UdpServer> servers = new ArrayList<>();

		for (int i = 0; i < 4; i++) {
			UdpServer server = UdpServer.create(ServerOptions.create()
			                                                 .reuseAddr(true)
			                                                 .listen(port)
			                                                 .protocolFamily(StandardProtocolFamily.INET)
			);

			server.start(ch -> {
				ch.receiveByteArray()
				  .log()
				  .subscribe(bytes -> {
					//log.info("{} got {} bytes", ++count, bytes.length);
					if (bytes.length == 1024) {
						latch.countDown();
					}
				});
				return Flux.never();
			}).doOnSuccess(v -> {
				try {
					for (Enumeration<NetworkInterface> ifaces =
					     NetworkInterface.getNetworkInterfaces();
					     ifaces.hasMoreElements(); ) {
						NetworkInterface iface = ifaces.nextElement();
						if (isMulticastEnabledIPv4Interface(iface)) {
							server.join(multicastGroup, iface);
						}
					}
				}
				catch (Throwable t) {
					throw new IllegalStateException(t);
				}
			}).block();

			servers.add(server);
		}

		for (int i = 0; i < Schedulers.DEFAULT_POOL_SIZE; i++) {
			threadPool.submit(() -> {
				try {
					MulticastSocket multicast = new MulticastSocket();
					multicast.joinGroup(new InetSocketAddress(multicastGroup, port), multicastInterface);

					byte[] data = new byte[1024];
					new Random().nextBytes(data);

					multicast.send(new DatagramPacket(data, data.length, multicastGroup, port));

					multicast.close();
				} catch (Exception e) {
					throw new IllegalStateException(e);
				}
			}).get(5, TimeUnit.SECONDS);
		}

		latch.await(5, TimeUnit.SECONDS);
		assertThat("latch was not counted down enough: "+latch.getCount()+" left on "+(4 ^ 2), latch.getCount() == 0 );

		for (UdpServer s : servers) {
			s.shutdown().block();
		}
	}

	private boolean isMulticastEnabledIPv4Interface(NetworkInterface iface) throws SocketException {
		if (!iface.supportsMulticast() || !iface.isUp()) {
			return false;
		}

		for (Enumeration<InetAddress> i = iface.getInetAddresses(); i.hasMoreElements(); ) {
			InetAddress address = i.nextElement();
			if (address.getClass() == Inet4Address.class) {
				return true;
			}
		}

		return false;
	}

	private NetworkInterface findMulticastEnabledIPv4Interface() throws SocketException {
		if (isMulticastEnabledIPv4Interface(NetUtil.LOOPBACK_IF)) {
			return NetUtil.LOOPBACK_IF;
		}

		for (Enumeration<NetworkInterface> ifaces = NetworkInterface.getNetworkInterfaces(); ifaces.hasMoreElements();
		  ) {
			NetworkInterface iface = ifaces.nextElement();
			if (isMulticastEnabledIPv4Interface(iface)) {
				return iface;
			}
		}

		throw new UnsupportedOperationException("This test requires a multicast enabled IPv4 network interface, but " +
		  "none" +
		  " " +
		  "were found");
	}
}
