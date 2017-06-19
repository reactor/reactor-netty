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

import io.netty.handler.codec.http.cookie.Cookie
import io.netty.handler.codec.http.cookie.DefaultCookie
import reactor.core.publisher.Mono
import reactor.ipc.netty.http.client.HttpClient
import reactor.ipc.netty.http.client.HttpClientResponse
import reactor.ipc.netty.http.server.HttpServer
import spock.lang.Specification

import java.time.Duration
import java.util.function.Function

class HttpCookieHandlingSpec extends Specification {

  def "client without cookie gets a new one from server"() {

	when: "server is prepared"
	def server = HttpServer.create(0).newRouter {
	  it.get("/test") { req, resp ->
		resp.addCookie(new DefaultCookie("cookie1", "test_value"))
				.send(req.receive().log("server received"))
	  }
	}.block(Duration.ofSeconds(30))

	def cookieResponse = HttpClient.create("localhost", server.address().port).
			get('/test')
			.flatMap({ replies -> Mono.just(replies.cookies()) }
					as Function<HttpClientResponse, Mono<Map<CharSequence, Set<Cookie>>>>)
			.doOnSuccess {
	  			println it
			}
			.doOnError {
	  			println "Failed requesting server: $it"
			}

	then: "data with cookies was received"
	def value = ""
	try {
	  def receivedCookies = cookieResponse.block(Duration.ofSeconds(30))
	  value = receivedCookies.get("cookie1")[0].value()
	}
	catch (RuntimeException ex) {
	  println ex.getMessage()
	}
	value == "test_value"

	cleanup: "the client/server where stopped"
	server?.dispose()
  }

}
