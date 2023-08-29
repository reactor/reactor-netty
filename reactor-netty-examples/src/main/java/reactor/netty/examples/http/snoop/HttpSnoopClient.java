/*
 * Copyright (c) 2023 VMware, Inc. or its affiliates, All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package reactor.netty.examples.http.snoop;

import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpHeaderValues;
import reactor.core.publisher.Mono;
import reactor.netty.ByteBufFlux;
import reactor.netty.http.client.HttpClient;

/**
 * @author Kun.Long
 * a http client demo that sends two requests to the http snoop server and then wait for the completion of the two requests
 * @see HttpSnoopServer
 **/
public class HttpSnoopClient {

	private final HttpClient client = HttpClient.create().wiretap(false);

	public static void main(String[] args) {
		HttpSnoopClient httpSnoopClient = new HttpSnoopClient();
		httpSnoopClient.client.warmup().block();
		System.out.println("client is ready");

		// we fire two requests to the server asynchronously
		Mono<String> respOfGet = httpSnoopClient.get();
		Mono<String> respOfPost = httpSnoopClient.postSomeJsonData();
		System.out.println("requests have fired");

		// then let the main thread wait for the completion of the two requests
		Mono.when(respOfGet, respOfPost).block();
		System.out.println("main thread exits");
	}

	public Mono<String> get() {
		return client.headers(hds -> hds.set(HttpHeaderNames.CONTENT_TYPE, HttpHeaderValues.TEXT_PLAIN))
			  .get().uri("http://localhost:8080/hello?q1=a&q2=b").responseContent().aggregate()
		      .asString()
			  .doOnNext(resp -> System.out.println("resp of get(): \n" + resp));
	}

	public Mono<String> postSomeJsonData() {
		return client.headers(hds -> hds.set(HttpHeaderNames.CONTENT_TYPE, HttpHeaderValues.APPLICATION_JSON))
			  .post().uri("http://localhost:8080/hello2")
		      .send(ByteBufFlux.fromString(Mono.just("{\"foo\": 1,\"bar\": 2}")))
		      .responseContent().aggregate()
			  .asString()
			  .doOnNext(resp -> System.out.println("resp of postSomeJsonData(): \n" + resp));
	}

}
