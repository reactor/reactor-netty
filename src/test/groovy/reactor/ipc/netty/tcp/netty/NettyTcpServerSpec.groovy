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

package reactor.ipc.netty.tcp.netty

import reactor.core.publisher.Flux
import reactor.ipc.codec.json.JsonCodec
import reactor.ipc.netty.common.NettyCodec
import reactor.ipc.netty.tcp.TcpClient
import reactor.ipc.netty.tcp.TcpServer
import spock.lang.Specification

import java.nio.ByteBuffer
import java.nio.channels.SocketChannel
import java.nio.charset.Charset
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * @author Jon Brisbin
 * @author Stephane Maldini
 */
class NettyTcpServerSpec extends Specification {

  def "TcpServer responds to requests from clients"() {
		given: "a simple TcpServer"
			def dataLatch = new CountDownLatch(1)
			def server = TcpServer.create("127.0.0.1")

		when: "the server is started"
		server.start { conn -> conn.sendString(Flux.just("Hello World!")) }.block()


			def client = new SimpleClient(server.listenAddress.port, dataLatch,
					"Hello World!")
			client.start()
			dataLatch.await(5, TimeUnit.SECONDS)

		then: "data was recieved"
			client.data?.remaining() == 12
			new String(client.data.array()) == "Hello World!"
			dataLatch.count == 0

		cleanup: "the server is stopped"
			server.shutdown()
	}

  def "TcpServer can encode and decode JSON"() {
		given: "a TcpServer with JSON defaultCodec"
			def dataLatch = new CountDownLatch(1)
		TcpServer server = TcpServer.create()

		when: "the server is started"
		server.start({ conn ->
		  conn.map(conn.receive(NettyCodec.json(Pojo)).log().take(1).map { pojo ->
							assert pojo.name == "John Doe"
							new Pojo(name: "Jane Doe")
						}
				  , NettyCodec.json(Pojo))
		}).block()

			def client = new SimpleClient(server.listenAddress.port, dataLatch, "{\"name\":\"John Doe\"}")
			client.start()
			dataLatch.await(5, TimeUnit.SECONDS)

		then: "data was recieved"
			client.data?.remaining() == 19
			new String(client.data.array()) == "{\"name\":\"Jane Doe\"}"
			dataLatch.count == 0

		cleanup: "the server is stopped"
			server?.shutdown()
	}

	def "flush every 5 elems with manual decoding"() {
		given: "a TcpServer and a TcpClient"
			def latch = new CountDownLatch(10)

			def server = TcpServer.create()
			def codec = new JsonCodec<Pojo, Pojo>(Pojo)

		when: "the client/server are prepared"
			server.start { input ->
			  input.mapAndFlush(input.receive(NettyCodec.from(codec))
								.log('serve').window(5), NettyCodec.from(codec)
				)
			}.block()

			def client = TcpClient.create("localhost", server.listenAddress.port)
			client.start { input ->
			  input.receive(NettyCodec.from(codec))
						.log('receive')
						.subscribe { latch.countDown() }

			  input.map(
						Flux.range(1, 10)
								.map { new Pojo(name: 'test' + it) }
								.log('send'), NettyCodec.from(codec)
				).subscribe()

			  Flux.never()
			}.block()

		then: "the client/server were started"
			latch.await(5, TimeUnit.SECONDS)


		cleanup: "the client/server where stopped"
			client.shutdown()
			server.shutdown()
	}


	def "retry strategies when server fails"() {
		given: "a TcpServer and a TcpClient"
			def elem = 10
			def latch = new CountDownLatch(elem)

		TcpServer server = TcpServer.create("localhost")
			def codec = new JsonCodec<Pojo, Pojo>(Pojo)
			def i = 0

		when: "the client/server are prepared"
			server.start { input ->
			  input.mapAndFlush(input.receive(NettyCodec.from(codec))
						.map {
						  Flux.just(it)
						  .doOnNext {
							if (i++ < 2) {
							  throw new Exception("test")
							}
						  }
						  .retry(2).doOnComplete{ println 'wow '+it }.log('flatmap-retry')
			  			}, NettyCodec.from(codec))
			}.block()

			def client = TcpClient.create("localhost", server.listenAddress.port)
			client.start { input ->
			  input.receive(NettyCodec.from(codec))
						.log('receive')
						.subscribe { latch.countDown() }

			  input.map(
						Flux.range(1, elem)
								.map { new Pojo(name: 'test' + it) }
								.log('send'), NettyCodec.from(codec)
				).subscribe()

			  Flux.never()
			}.block()

		then: "the client/server were started"
			latch.await(10, TimeUnit.SECONDS)


		cleanup: "the client/server where stopped"
			client.shutdown()
			server.shutdown()
	}


	static class SimpleClient extends Thread {
		final int port
		final CountDownLatch latch
		final String output
		ByteBuffer data

		SimpleClient(int port, CountDownLatch latch, String output) {
			this.port = port
			this.latch = latch
			this.output = output
		}

		@Override
		void run() {
			def ch = SocketChannel.open(new InetSocketAddress("127.0.0.1", port))
			def len = ch.write(ByteBuffer.wrap(output.getBytes(Charset.defaultCharset())))
			assert ch.connected
			data = ByteBuffer.allocate(len)
			int read = ch.read(data)
			assert read > 0
			data.flip()
			latch.countDown()
		}
	}

	static class Pojo {
		String name

		@Override
		String toString() {
			name
		}
	}

}
