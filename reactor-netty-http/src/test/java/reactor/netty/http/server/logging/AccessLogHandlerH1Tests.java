/*
 * Copyright (c) 2020-2023 VMware, Inc. or its affiliates, All Rights Reserved.
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
package reactor.netty.http.server.logging;

import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpVersion;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.netty.BaseHttpTest;
import reactor.test.StepVerifier;

import java.net.SocketAddress;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static reactor.netty.http.server.logging.LoggingTests.HEADER_CONNECTION_NAME;
import static reactor.netty.http.server.logging.LoggingTests.HEADER_CONNECTION_VALUE;
import static reactor.netty.http.server.logging.LoggingTests.RESPONSE_CONTENT_STRING;
import static reactor.netty.http.server.logging.LoggingTests.URI;

/**
 * This test class verifies {@link AccessLogHandlerH1}.
 *
 * @author limaoning
 */
class AccessLogHandlerH1Tests extends BaseHttpTest {

	@ParameterizedTest
	@ValueSource(booleans = {true, false})
	void responseChunked(boolean chunked) {
		disposableServer =
				createServer()
				        .handle((req, res) ->
				                res.withConnection(conn ->
				                        conn.addHandlerLast(new AccessLogHandlerH1(
				                                args -> {
				                                    assertAccessLogArgProvider(args, conn.channel().remoteAddress(), chunked);
				                                    return AccessLog.create("{}={}", HEADER_CONNECTION_NAME,
				                                            args.requestHeader(HEADER_CONNECTION_NAME));
				                                })))
				                   .chunkedTransfer(chunked)
				                   .sendString(chunked ? Flux.just(RESPONSE_CONTENT_STRING, "") :
				                           Mono.just(RESPONSE_CONTENT_STRING)))
				        .bindNow();

		createClient(disposableServer.port())
		        .get()
		        .uri("/")
		        .responseContent()
		        .aggregate()
		        .asString()
		        .as(StepVerifier::create)
		        .expectNext(RESPONSE_CONTENT_STRING)
		        .expectComplete()
		        .verify(Duration.ofSeconds(5));
	}

	@SuppressWarnings("deprecation")
	private void assertAccessLogArgProvider(AccessLogArgProvider args, SocketAddress remoteAddress, boolean chunked) {
		assertThat(args.remoteAddress()).isEqualTo(remoteAddress);
		assertThat(args.user()).isEqualTo(AbstractAccessLogArgProvider.MISSING);
		assertThat(args.accessDateTime()).isNotNull();
		assertThat(args.zonedDateTime()).isNotNull();
		assertThat(args.method()).isEqualTo(HttpMethod.GET.name());
		assertThat(args.uri()).isEqualTo(URI);
		assertThat(args.protocol()).isEqualTo(HttpVersion.HTTP_1_1.text());
		assertThat(args.status()).isEqualTo(HttpResponseStatus.OK.codeAsText());
		if (chunked) {
			assertThat(args.contentLength()).isEqualTo(RESPONSE_CONTENT_STRING.length() << 1);
		}
		else {
			assertThat(args.contentLength()).isEqualTo(RESPONSE_CONTENT_STRING.length());
		}
		assertThat(args.requestHeader(HEADER_CONNECTION_NAME)).isEqualTo(HEADER_CONNECTION_VALUE);
	}

}
