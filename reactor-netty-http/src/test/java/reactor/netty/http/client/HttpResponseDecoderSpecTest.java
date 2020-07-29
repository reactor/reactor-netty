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
package reactor.netty.http.client;

import org.junit.Before;
import org.junit.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * @author Violeta Georgieva
 */
public class HttpResponseDecoderSpecTest {

	private HttpResponseDecoderSpec conf;

	@Before
	public void setUp() {
		conf = new HttpResponseDecoderSpec();
	}

	@Test
	public void failOnMissingResponse() {
		conf.failOnMissingResponse(true);

		assertThat(conf.failOnMissingResponse).as("fail on missing response").isTrue();

		assertThat(conf.maxInitialLineLength()).as("default initial line length")
				.isEqualTo(HttpResponseDecoderSpec.DEFAULT_MAX_INITIAL_LINE_LENGTH);
		assertThat(conf.maxHeaderSize()).as("default header size")
				.isEqualTo(HttpResponseDecoderSpec.DEFAULT_MAX_HEADER_SIZE);
		assertThat(conf.maxChunkSize()).as("default chunk size")
				.isEqualTo(HttpResponseDecoderSpec.DEFAULT_MAX_CHUNK_SIZE);
		assertThat(conf.validateHeaders()).as("default validate headers")
				.isEqualTo(HttpResponseDecoderSpec.DEFAULT_VALIDATE_HEADERS);
		assertThat(conf.initialBufferSize()).as("default initial buffer sizes")
				.isEqualTo(HttpResponseDecoderSpec.DEFAULT_INITIAL_BUFFER_SIZE);
		assertThat(conf.parseHttpAfterConnectRequest).as("default parse http after connect request")
				.isEqualTo(HttpResponseDecoderSpec.DEFAULT_PARSE_HTTP_AFTER_CONNECT_REQUEST);
	}

	@Test
	public void parseHttpAfterConnectRequest() {
		conf.parseHttpAfterConnectRequest(true);

		assertThat(conf.parseHttpAfterConnectRequest).as("parse http after connect request").isTrue();

		assertThat(conf.maxInitialLineLength()).as("default initial line length")
				.isEqualTo(HttpResponseDecoderSpec.DEFAULT_MAX_INITIAL_LINE_LENGTH);
		assertThat(conf.maxHeaderSize()).as("default header size")
				.isEqualTo(HttpResponseDecoderSpec.DEFAULT_MAX_HEADER_SIZE);
		assertThat(conf.maxChunkSize()).as("default chunk size")
				.isEqualTo(HttpResponseDecoderSpec.DEFAULT_MAX_CHUNK_SIZE);
		assertThat(conf.validateHeaders()).as("default validate headers")
				.isEqualTo(HttpResponseDecoderSpec.DEFAULT_VALIDATE_HEADERS);
		assertThat(conf.initialBufferSize()).as("default initial buffer sizes")
				.isEqualTo(HttpResponseDecoderSpec.DEFAULT_INITIAL_BUFFER_SIZE);
		assertThat(conf.failOnMissingResponse).as("default fail on missing response")
				.isEqualTo(HttpResponseDecoderSpec.DEFAULT_FAIL_ON_MISSING_RESPONSE);
	}
}
