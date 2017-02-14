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

package reactor.ipc.netty.tcp.netty

import com.fasterxml.jackson.databind.ObjectMapper
import io.netty.buffer.Unpooled
import io.netty.handler.codec.json.JsonObjectDecoder
import io.netty.util.NetUtil
import reactor.core.publisher.Flux
import reactor.ipc.netty.tcp.TcpClient
import reactor.ipc.netty.tcp.TcpServer
import spock.lang.Specification

import java.nio.ByteBuffer
import java.nio.channels.SocketChannel
import java.nio.charset.Charset
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * @author Jon Brisbin
 * @author Stephane Maldini
 */
class NettyTcpServerSpec extends Specification {

  def m = new ObjectMapper()

  def jsonEncoder = { pojo ->
	def out = new ByteArrayOutputStream()
	m.writeValue(out, pojo)
	Unpooled.copiedBuffer(out.toByteArray())
  }

  def jsonDecoder = { bb -> m.readValue(bb, Pojo)
  }

  def "TcpServer can encode and decode JSON"() {
	when: "the server is started"
	def dataLatch = new CountDownLatch(1)

	def server = TcpServer.create().newHandler { i, o ->
	  o.send(i.receive()
			  .asString()
			  .map(jsonDecoder)
			  .log()
			  .take(1)
			  .map { pojo ->
		assert pojo.name == "John Doe"
		new Pojo(name: "Jane Doe")
	  }
	  .map(jsonEncoder))
	}.block(Duration.ofSeconds(30))

	def client = new SimpleClient(server.address().port, dataLatch,
			"{\"name\":\"John Doe\"}")
	client.start()
	dataLatch.await(5, TimeUnit.SECONDS)

	then: "data was recieved"
	client.data?.remaining() == 19
	new String(client.data.array()) == "{\"name\":\"Jane Doe\"}"
	dataLatch.count == 0

	cleanup: "the server is stopped"
	server?.dispose()
  }

  def "flush every 5 elems with manual decoding"() {
	when: "the client/server are prepared"
	def latch = new CountDownLatch(10)
	def server = TcpServer.create().newHandler { i, o ->
	  i.context().addDecoder(new JsonObjectDecoder())
	  i.receive()
			  .asString()
			  .log('serve')
			  .map { bb -> m.readValue(bb, Pojo[]) }
			  .concatMap { d -> Flux.fromArray(d) }
			  .window(5)
			  .concatMap { w -> o.send(w.collectList().map(jsonEncoder)) }
	}.block(Duration.ofSeconds(30))

	def client = TcpClient.create(server.address().port)
	client.newHandler { i, o ->
	  i.context().addDecoder(new JsonObjectDecoder())

	  i.receive()
			  .asString()
			  .map { bb -> m.readValue(bb, Pojo[]) }
			  .concatMap { d -> Flux.fromArray(d) }
			  .log('receive')
			  .subscribe { latch.countDown() }

	  o.send(Flux.range(1, 10)
			  .map { new Pojo(name: 'test' + it) }
			  .log('send')
			  .collectList()
			  .map(jsonEncoder))
	    .neverComplete()
	}.block(Duration.ofSeconds(30))

	then: "the client/server were started"
	latch.await(30, TimeUnit.SECONDS)


	cleanup: "the client/server where stopped"
	server.dispose()
  }

  def "retry strategies when server fails"() {
	when: "the client/server are prepared"
	def elem = 10
	def latch = new CountDownLatch(elem)

	def j = 0
	def server = TcpServer.create("localhost").newHandler { i, o ->
	  o.sendGroups(i.receive()
			  .asString()
			  .map { bb -> m.readValue(bb, Pojo[]) }
			  .map { d ->
				  Flux.fromArray(d)
						  .doOnNext {
							  if (j++ < 2) {
								throw new Exception("test")
							  }
							}
						  .retry(2)
						  .collectList()
						  .map(jsonEncoder)
				}
	  		  .doOnComplete { println 'wow ' + it }
			  .log('flatmap-retry'))
	}.block(Duration.ofSeconds(30))

	def client = TcpClient.create("localhost", server.address().port)
	client.newHandler { i, o ->
	  i.receive()
			  .asString()
			  .map { bb -> m.readValue(bb, Pojo[]) }
			  .concatMap { d -> Flux.fromArray(d) }
			  .log('receive')
			  .subscribe { latch.countDown() }

	  o.send(Flux.range(1, elem)
			  .map { new Pojo(name: 'test' + it) }
			  .log('send')
			  .collectList().map(jsonEncoder))
			  .neverComplete()

	}.block(Duration.ofSeconds(30))

	then: "the client/server were started"
	latch.await(10, TimeUnit.SECONDS)


	cleanup: "the client/server where stopped"
	server.dispose()
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
	  def ch = SocketChannel.
			  open(new InetSocketAddress(NetUtil.LOCALHOST.getHostAddress(), port))
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
