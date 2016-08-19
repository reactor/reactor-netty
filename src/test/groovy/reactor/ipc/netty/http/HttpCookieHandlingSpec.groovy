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

import io.netty.handler.codec.http.cookie.DefaultCookie
import reactor.core.publisher.Mono
import reactor.ipc.netty.http.HttpClient
import reactor.ipc.netty.http.HttpServer
import spock.lang.Specification

import java.time.Duration
import java.util.function.Function

public class HttpCookieHandlingSpec extends Specification{

  def "client without cookie gets a new one from server"(){

	given: "a http server setting cookie 1.0"
	def server = HttpServer.create(0)

	when: "server is prepared"
	server.get("/test"){
	   req ->
		req.addResponseCookie(new DefaultCookie("cookie1", "test_value"))
			.send(req.receive().log("server received"))
	}

	then: "the server was started"
	server
	!server.start().block(Duration.ofSeconds(5))

	when: "a request with URI is sent onto the server"
	def client = HttpClient.create("localhost", server.listenAddress.port)

	def cookieResponse = client.get('/test').then({
	  replies -> Mono.just(replies.cookies())
	} as Function)
	.doOnSuccess{
	  println it
	}
	.doOnError {
	  println "Failed requesting server: $it"
	}

	then: "data with cookies was received"
	def value = ""
	try {
	  def receivedCookies = cookieResponse.block(Duration.ofSeconds(5))
	  value = receivedCookies.get("cookie1")[0].value()
	} catch (RuntimeException ex) {
	  println ex.getMessage();
	}
	value == "test_value"

	cleanup: "the client/server where stopped"
	client?.shutdown()
	server?.shutdown()
  }

}
