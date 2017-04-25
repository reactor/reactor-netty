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
package reactor.ipc.netty.http

import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import reactor.core.scheduler.Schedulers
import reactor.ipc.netty.http.client.HttpClient
import reactor.ipc.netty.http.client.HttpClientException
import reactor.ipc.netty.http.server.HttpServer
import spock.lang.Specification

import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.function.Function

/**
 * @author Stephane Maldini
 */
class HttpSpec extends Specification {

  def "http responds empty"() {

	when: "the server is prepared"

	//prepare post request consumer on /test/* and capture the URL parameter "param"
	def server = HttpServer.create(0).newRouter { r ->
	  r.post('/test/{param}') {
		req, res -> Mono.empty()
	  }
	}.block(Duration.ofSeconds(30))

	//Prepare a client using default impl (Netty) to connect on http://localhost:port/ and assign global codec to send/receive String data
	def client = HttpClient.create { opts ->
	  opts.connect("localhost", server.address()
			  .port)
	}

	//prepare an http post request-reply flow
	def content = client.post('/test/World') { req ->
	  //prepare content-type
	  req.header('Content-Type', 'text/plain')

	  //return a producing stream to send some data along the request
	  req.sendString(Mono.just("Hello").log('client-send'))

	}.then({
	  replies
		->
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

	cleanup:
	server.dispose()
  }

  def "http responds to requests from clients"() {
	when: "the server is prepared"

	//prepare post request consumer on /test/* and capture the URL parameter "param"
	def server = HttpServer.create(0).newRouter { r ->
	  r.post('/test/{param}') {
		req, res
		  ->

		  //log then transform then log received http request content from the request body and the resolved URL parameter "param"
		  //the returned stream is bound to the request stream and will auto read/close accordingly

		  res.sendString(req.receive()
				  .asString()
				  .log('server-received')
				  .map { it + ' ' + req.param('param') + '!' }
				  .log('server-reply'))

	  }
	}.block(Duration.ofSeconds(30))

	//Prepare a client using default impl (Netty) to connect on http://localhost:port/ and assign global codec to send/receive String data
	def client = HttpClient.create("localhost", server.address().port)

	//prepare an http post request-reply flow
	def content = client.post('/test/World') { req ->
	  //prepare content-type
	  req.header('Content-Type', 'text/plain')

	  //return a producing stream to send some data along the request
	  req.sendString(Flux.just("Hello")
			  .log('client-send'))

	}.then( { replies ->
		//successful request, listen for the first returned next reply and pass it downstream
		replies.receive()
				.aggregate()
				.asString()
				.log('client-received')
	} as Function)
			.doOnError {
	  //something failed during the request or the reply processing
	  println "Failed requesting server: $it"
	}



	then: "data was recieved"
	//the produced reply should be there soon
	content.block(Duration.ofSeconds(5000)) == "Hello World!"

	cleanup: "the client/server where stopped"
	//note how we order first the client then the server shutdown
	server?.dispose()
  }

  def "http error with requests from clients"() {

	when: "the server is prepared"

	CountDownLatch errored = new CountDownLatch(1)

	def server = HttpServer.create(0).newRouter { r ->
	  		 r.get('/test') { req, res ->
			   throw new Exception()
			  }
			  .get('/test2') { req, res ->
				 res.send(Flux.error(new Exception()))
				    .then()
				    .log("send")
					.doOnError {
		  				errored.countDown()
			 		}
			  }
	  		  .get('/test3') { req, res ->
			  	 Flux.error(new Exception())
			  }
	}.block(Duration.ofSeconds(30))

	def client = HttpClient.create("localhost", server.address().port)

	then:
	server.address().port



	when: "data is sent with Reactor HTTP support"

	//prepare an http post request-reply flow
	client
			.get('/test')
			.then({ replies ->
	  Mono.just(replies.status().code())
			  .log("received-status-1")
	} as Function)
			.block(Duration.ofSeconds(30))



	then: "data was recieved"
	//the produced reply should be there soon
	thrown HttpClientException

	when:
	//prepare an http post request-reply flow
	def content = client
			.get('/test2')
			.flatMapMany { replies -> replies.receive().log("received-status-2")
	}
	.next()
			.block(Duration.ofSeconds(30))

	then: "data was recieved"
	//the produced reply should be there soon
	errored.await(30, TimeUnit.SECONDS)
	!content

	when:
	//prepare an http post request-reply flow
	client
			.get('/test3')
			.flatMapMany { replies ->
	  Flux.just(replies.status().code)
			  .log("received-status-3")
	}
	.next()
			.block(Duration.ofSeconds(5))

	then: "data was recieved"
	//the produced reply should be there soon
	thrown HttpClientException

	cleanup: "the client/server where stopped"
	//note how we order first the client then the server shutdown
	server?.dispose()
  }

  def "WebSocket responds to requests from clients"() {
	given: "a simple HttpServer"

	//Listen on localhost using default impl (Netty) and assign a global codec to receive/reply String data

	AtomicInteger clientRes = new AtomicInteger()
	AtomicInteger serverRes = new AtomicInteger()

	when: "the server is prepared"

	//prepare websocket request consumer on /test/* and capture the URL parameter "param"
	def server = HttpServer.create(0).newRouter { r ->
	  r.get('/test/{param}') {
		req, resp
		  ->
		  println req.requestHeaders().get('test')
		  //log then transform then log received http request content from the request body and the resolved URL parameter "param"
		  //the returned stream is bound to the request stream and will auto read/close accordingly
		  resp.header("content-type", "text/plain")
				  .sendWebsocket { i, o ->
			         o.options{ x -> x.flushOnEach() }
					  .sendString(i.receive()
						.asString()
						.publishOn(Schedulers.single())
						.doOnNext { serverRes.incrementAndGet() }
						.map { it + ' ' + req.param('param') + '!' }
						.log('server-reply'))
				  }
	  }
	}.block(Duration.ofSeconds(5))

	//Prepare a client using default impl (Netty) to connect on http://localhost:port/ and assign global codec to send/receive String data
	def client = HttpClient.create("localhost", server.address().port)

	//prepare an http websocket request-reply flow
	def content = client.get('/test/World') { req ->
	  //prepare content-type
	  req.header('Content-Type', 'text/plain').header("test", "test")

	  //return a producing stream to send some data along the request
	  req.options { o -> o.flushOnEach() }
			  .sendWebsocket()
			  .sendString(Flux
				.range(1, 1000)
				.log('client-send')
				.map { it.toString() })
	}.flatMap {
	  replies
		->
		//successful handshake, listen for the first returned next replies and pass it downstream
		replies
				.receive()
				.asString()
				.log('client-received')
				.publishOn(Schedulers.parallel())
				.doOnNext { clientRes.incrementAndGet() }
	}
	.take(1000)
			.collectList()
			.cache()
			.doOnError {
	  //something failed during the request or the reply processing
	  println "Failed requesting server: $it"
	}


	println "STARTING: server[$serverRes] / client[$clientRes]"

	then: "data was recieved"
	//the produced reply should be there soon
	//content.block(Duration.ofSeconds(15))[1000 - 1] == "1000 World!"
	content.block(Duration.ofSeconds(30))[1000 - 1] == "1000 World!"

	cleanup: "the client/server where stopped"
	println "FINISHED: server[$serverRes] / client[$clientRes]"
	//note how we order first the client then the server shutdown
	server?.dispose()
  }

}