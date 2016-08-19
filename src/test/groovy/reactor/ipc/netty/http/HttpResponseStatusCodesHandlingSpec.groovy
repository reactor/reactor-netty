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
import reactor.ipc.netty.http.HttpClient
import reactor.ipc.netty.http.HttpServer
import spock.lang.Specification

import java.time.Duration

/**
 * @author Anatoly Kadyshev
 */
public class HttpResponseStatusCodesHandlingSpec extends Specification {

    def "http status code 404 is handled by the client"() {
        given: "a simple HttpServer"
            def server = HttpServer.create(0)

        when: "the server is prepared"
            server.post('/test') { req ->
                req.send(
                        req.log('server-received')
                )
            }

        then: "the server was started"
          server
          !server.start().block(Duration.ofSeconds(5))

        when: "a request with unsupported URI is sent onto the server"
            def client = HttpClient.create("localhost", server.listenAddress.port)

            def replyReceived = ""
            def content = client.get('/unsupportedURI') { req ->
                //prepare content-type
                req.header('Content-Type', 'text/plain')

                //return a producing stream to send some data along the request
                req.sendString(
                    Flux
                            .just("Hello")
                            .log('client-send')
                )
            }
            .flatMap { replies ->
                //successful request, listen for replies
                replies
                        .receiveString()
                        .log('client-received')
                        .doOnNext { s ->
                            replyReceived = s
                        }
            }
            .next()
            .doOnError {
                //something failed during the request or the reply processing
                println "Failed requesting server: $it"
            }

        then: "error is thrown with a message and no reply received"
            def exceptionMessage = ""

            try {
                content.block();
            } catch (RuntimeException ex) {
                exceptionMessage = ex.getMessage();
            }

            exceptionMessage == "HTTP request failed with code: 404"
            replyReceived == ""

        cleanup: "the client/server where stopped"
        client?.shutdown()
        server?.shutdown()
    }
}
