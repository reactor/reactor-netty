/*
 * Copyright (c) 2011-Present VMware, Inc. or its affiliates, All Rights Reserved.
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

package reactor.netty.udp;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.MulticastSocket;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.nio.ByteBuffer;
import java.nio.channels.DatagramChannel;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Enumeration;
import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import io.netty.channel.ChannelOption;
import io.netty.channel.socket.InternetProtocolFamily;
import io.netty.util.NetUtil;
import org.assertj.core.api.Assertions;
import org.junit.After;
import org.junit.Before;
import org.junit.Ignore;
import org.junit.Test;
import reactor.core.publisher.Flux;
import reactor.core.scheduler.Schedulers;
import reactor.netty.ChannelBindException;
import reactor.netty.Connection;
import reactor.netty.SocketUtils;
import reactor.netty.resources.LoopResources;
import reactor.util.Logger;
import reactor.util.Loggers;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

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
		final Random rndm = new Random();
		final int port = SocketUtils.findAvailableUdpPort();
		final CountDownLatch latch = new CountDownLatch(4);

		final Connection server = UdpServer.create()
		                                   .port(port)
		                                   .handle((in, out) -> {
			                                   in.receive()
			                                     .asByteArray()
			                                     .log()
			                                     .subscribe(bytes -> {
				                                     if (bytes.length == 1024) {
					                                     latch.countDown();
				                                     }
			                                     });
			                                   return Flux.never();
		                                   })
		                                   .bind()
		                                   .doOnSuccess(v -> {
			                                   try {
				                                   DatagramChannel udp =
						                                   DatagramChannel.open();
				                                   udp.configureBlocking(true);
				                                   udp.connect(new InetSocketAddress(
						                                   InetAddress.getLocalHost(),
						                                   port));

				                                   byte[] data = new byte[1024];
				                                   rndm.nextBytes(data);
				                                   for (int i = 0; i < 4; i++) {
					                                   udp.write(ByteBuffer.wrap(data));
				                                   }

				                                   udp.close();
			                                   }
			                                   catch (IOException e) {
				                                   log.error("", e);
			                                   }
		                                   })
		                                   .block(Duration.ofSeconds(30));
		Assertions.assertThat(server).isNotNull();

		assertThat("latch was counted down", latch.await(10, TimeUnit.SECONDS));
		server.disposeNow();
	}

	@Test
	public void supportsUdpMulticast() throws Exception {
		final Random rndm = new Random();
		final int port = SocketUtils.findAvailableUdpPort();
		final CountDownLatch latch1 = new CountDownLatch(Schedulers.DEFAULT_POOL_SIZE);
		final CountDownLatch latch2 = new CountDownLatch(4);
		Enumeration<NetworkInterface> ifaces = NetworkInterface.getNetworkInterfaces();

		final InetAddress multicastGroup = InetAddress.getByName("230.0.0.1");
		final NetworkInterface multicastInterface = findMulticastEnabledIPv4Interface();
		log.info("Using network interface '{}' for multicast", multicastInterface);
		final Collection<Connection> servers = new ArrayList<>();

		LoopResources resources = LoopResources.create("test");
		for (int i = 0; i < 4; i++) {
			Connection server =
					UdpServer.create()
					         .option(ChannelOption.SO_REUSEADDR, true)
					         .addressSupplier(() -> new InetSocketAddress(port))
					         .runOn(resources, InternetProtocolFamily.IPv4)
					         .handle((in, out) -> {
						         Flux.<NetworkInterface>generate(s -> {
					                             if (ifaces.hasMoreElements()) {
						                             s.next(ifaces.nextElement());
					                             }
					                             else {
						                             s.complete();
					                             }
				                             }).flatMap(iface -> {
					                             if (isMulticastEnabledIPv4Interface(iface)) {
						                             return in.join(multicastGroup,
								                             iface);
					                             }
					                             return Flux.empty();
				                             })
				                               .thenMany(in.receive()
				                                           .asByteArray()
				                                           .doOnSubscribe(s -> latch2.countDown()))
				                               .log()
				                               .subscribe(bytes -> {
					                               if (bytes.length == 1024) {
						                               latch1.countDown();
					                               }
				                               });
				                             return Flux.never();
			                             })
			                 .bind()
					         .block(Duration.ofSeconds(30));

			servers.add(server);
		}

		assertTrue(latch2.await(5, TimeUnit.SECONDS));

		for (int i = 0; i < Schedulers.DEFAULT_POOL_SIZE; i++) {
			threadPool.submit(() -> {
				try {
					MulticastSocket multicast = new MulticastSocket();
					multicast.joinGroup(new InetSocketAddress(multicastGroup, port),
							multicastInterface);

					byte[] data = new byte[1024];
					rndm.nextBytes(data);

					multicast.send(new DatagramPacket(data,
							data.length,
							multicastGroup,
							port));

					multicast.close();
				}
				catch (Exception e) {
					throw new IllegalStateException(e);
				}
			})
			          .get(5, TimeUnit.SECONDS);
		}

		assertTrue("latch was not counted down enough: " + latch1.getCount() + " left on " + (4 ^ 2),
				latch1.await(5, TimeUnit.SECONDS));

		for (Connection s : servers) {
			s.disposeNow();
		}
	}

	private boolean isMulticastEnabledIPv4Interface(NetworkInterface iface) {
		try {
			if (!iface.supportsMulticast() || !iface.isUp()) {
				return false;
			}
		}
		catch (SocketException se) {
			return false;
		}

		for (Enumeration<InetAddress> i = iface.getInetAddresses();
		     i.hasMoreElements(); ) {
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

		for (Enumeration<NetworkInterface> ifaces =
		     NetworkInterface.getNetworkInterfaces(); ifaces.hasMoreElements(); ) {
			NetworkInterface iface = ifaces.nextElement();
			if (isMulticastEnabledIPv4Interface(iface)) {
				return iface;
			}
		}

		throw new UnsupportedOperationException(
				"This test requires a multicast enabled IPv4 network interface, but " + "none" + " " + "were found");
	}

	@Test
	public void portBindingException() {
		Connection conn =
				UdpServer.create()
				         .port(0)
				         .bindNow(Duration.ofSeconds(30));

		try {
			UdpServer.create()
			         .addressSupplier(conn::address)
			         .bindNow(Duration.ofSeconds(30));
			fail("illegal-success");
		}
		catch (ChannelBindException e) {
			assertEquals(e.localPort(), conn.address().getPort());
			e.printStackTrace();
		}

		conn.disposeNow();
	}
}
