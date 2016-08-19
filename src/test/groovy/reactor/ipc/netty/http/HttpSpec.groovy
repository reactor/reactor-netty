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
package reactor.ipc.netty.http

import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import reactor.ipc.netty.config.ClientOptions
import reactor.ipc.netty.http.HttpClient
import reactor.ipc.netty.http.HttpException
import reactor.ipc.netty.http.HttpServer
import spock.lang.Specification

import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.function.Function

/**
 * @author Stephane Maldini
 */
class HttpSpec extends Specification {

  def "http responds empty"() {
	given: "a simple HttpServer"

	//Listen on localhost using default impl (Netty) and assign a global codec to receive/reply String data
	def server = HttpServer.create(0)

	when: "the server is prepared"

	//prepare post request consumer on /test/* and capture the URL parameter "param"
	server.post('/test/{param}') {
	  req
		->

		//log then transform then log received http request content from the request body and the resolved URL parameter "param"
		//the returned stream is bound to the request stream and will auto read/close accordingly
		req.send(Flux.empty())
	}

	then: "the server was started"
	server
	!server.start().block(Duration.ofSeconds(500))

	when: "data is sent with Reactor HTTP support"

	//Prepare a client using default impl (Netty) to connect on http://localhost:port/ and assign global codec to send/receive String data
	def client = HttpClient.create(ClientOptions.to("localhost", server.listenAddress.port))

	//prepare an http post request-reply flow
	def content = client.post('/test/World') { req ->
	  //prepare content-type
	  req.header('Content-Type', 'text/plain')

	  //return a producing stream to send some data along the request
	  req.sendString(Mono.just("Hello").log('client-send'))

	}.then ({ replies ->
	  //successful request, listen for the first returned next reply and pass it downstream
	  replies.receive().log('client-received').next()
	} as Function)
	.doOnError {
	  //something failed during the request or the reply processing
	  println "Failed requesting server: $it"
	}

	then: "data was not recieved"
	//the produced reply should be there soon
	!content.block(Duration.ofSeconds(5000))
  }


	def "http responds to requests from clients"() {
	given: "a simple HttpServer"

	//Listen on localhost using default impl (Netty) and assign a global codec to receive/reply String data
	def server = HttpServer.create(0)

	when: "the server is prepared"

	//prepare post request consumer on /test/* and capture the URL parameter "param"
	server.post('/test/{param}') { req ->

		//log then transform then log received http request content from the request body and the resolved URL parameter "param"
		//the returned stream is bound to the request stream and will auto read/close accordingly

		req.sendString(req.receiveString()
				.log('server-received')
				.map { it + ' ' + req.param('param') + '!' }
				.log('server-reply'))

	}

	then: "the server was started"
	server
	!server.start().block(Duration.ofSeconds(5))

	when: "data is sent with Reactor HTTP support"

	//Prepare a client using default impl (Netty) to connect on http://localhost:port/ and assign global codec to send/receive String data
	def client = HttpClient.create("localhost", server.listenAddress.port)

	//prepare an http post request-reply flow
	def content = client.post('/test/World') { req ->
	  //prepare content-type
	  req.header('Content-Type', 'text/plain')

	  //return a producing stream to send some data along the request
	  req.sendString(Flux.just("Hello")
			  .log('client-send'))

	}.flatMap { replies ->
	  //successful request, listen for the first returned next reply and pass it downstream
	  replies.receiveString()
			  .log('client-received')
	}
	.publishNext()
			.doOnError {
	  //something failed during the request or the reply processing
	  println "Failed requesting server: $it"
	}



	then: "data was recieved"
	//the produced reply should be there soon
	content.block(Duration.ofSeconds(5000)) == "Hello World!"

	cleanup: "the client/server where stopped"
	//note how we order first the client then the server shutdown
	client?.shutdown()
	server?.shutdown()
  }

  def "http error with requests from clients"() {
	given: "a simple HttpServer"

	//Listen on localhost using default impl (Netty) and assign a global codec to receive/reply String data
	def server = HttpServer.create(0)

	when: "the server is prepared"

	CountDownLatch errored = new CountDownLatch(1)

	server.get('/test') { req -> throw new Exception()
	}.get('/test2') { req ->
	  req.send(Flux.error(new Exception())).log("send").doOnError({
		errored
				.countDown()
	  })
	}.get('/test3') { req -> return Flux.error(new Exception())
	}

	then: "the server was started"
	server
	!server.start().block(Duration.ofSeconds(5))

	when:
	def client = HttpClient.create("localhost", server.listenAddress.port)

	then:
	server.listenAddress.port



	when: "data is sent with Reactor HTTP support"

	//prepare an http post request-reply flow
	client
			.get('/test')
			.then ({ replies ->
	 			 Mono.just(replies.status().code())
			  .log("received-status-1")
			} as Function)
			.block(Duration.ofSeconds(5))



	then: "data was recieved"
	//the produced reply should be there soon
	thrown HttpException

	when:
	//prepare an http post request-reply flow
	def content = client
			.get('/test2')
			.flatMap { replies -> replies.receive().log("received-status-2")
	}
	.next()
			.block(Duration.ofSeconds(3))

	then: "data was recieved"
	//the produced reply should be there soon
	thrown IllegalStateException
	errored.await(5, TimeUnit.SECONDS)
	!content

	when:
	//prepare an http post request-reply flow
	client
			.get('/test3')
			.flatMap { replies ->
	  Flux.just(replies.status().code)
			  .log("received-status-3")
	}
	.next()
			.block(Duration.ofSeconds(5))

	then: "data was recieved"
	//the produced reply should be there soon
	thrown HttpException

	cleanup: "the client/server where stopped"
	//note how we order first the client then the server shutdown
	client?.shutdown()
	server?.shutdown()
  }

  def "WebSocket responds to requests from clients"() {
	given: "a simple HttpServer"

	//Listen on localhost using default impl (Netty) and assign a global codec to receive/reply String data
	def server = HttpServer.create(0)

	def clientRes = 0
	def serverRes = 0

	when: "the server is prepared"

	//prepare websocket request consumer on /test/* and capture the URL parameter "param"
	server
			.get('/test/{param}') {
	  req
		->
		println req.headers().get('test')
		//log then transform then log received http request content from the request body and the resolved URL parameter "param"
		//the returned stream is bound to the request stream and will auto read/close accordingly
		req.responseHeader("content-type", "text/plain")
				.upgradeToWebsocket()
				.thenMany(req
				.flushEach()
					.sendString(req.receiveString()
						.doOnNext { serverRes++ }
						.map { it + ' ' + req.param('param') + '!' }
				.log('server-reply')))
	}
	server.start().block(Duration.ofSeconds(5))

	then: "the server was started"
	server

	when: "data is sent with Reactor HTTP support"

	//Prepare a client using default impl (Netty) to connect on http://localhost:port/ and assign global codec to send/receive String data
	def client = HttpClient.create("localhost", server.listenAddress.port)

	//prepare an http websocket request-reply flow
	def content = client.get('/test/World') { req ->
	  //prepare content-type
	  req.header('Content-Type', 'text/plain').header("test", "test")

	  //return a producing stream to send some data along the request
	  req.flushEach()
			  .upgradeToTextWebsocket().concatWith(req.sendString(Flux
				.range(1, 1000)
				  .log('client-send')
				.map { it.toString() }))

	}.flatMap { replies ->
	  //successful handshake, listen for the first returned next replies and pass it downstream
	  replies
			  .receiveString()
			  .log('client-received')
			  .doOnNext { clientRes++ }
	}
	.take(1000)
			.collectList()
			.cache()
			.doOnError {
	  			//something failed during the request or the reply processing
	  			println "Failed requesting server: $it"
			}


	println "server: $serverRes / client: $clientRes"

	then: "data was recieved"
	//the produced reply should be there soon
	content.block(Duration.ofSeconds(15))[1000 - 1] == "1000 World!"

	cleanup: "the client/server where stopped"
	//note how we order first the client then the server shutdown
	client?.shutdown()
	server?.shutdown()
  }

}