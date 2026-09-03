/*
 * Copyright (c) 2026 VMware, Inc. or its affiliates, All Rights Reserved.
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
package reactor.netty.http.client;

import io.netty.handler.codec.http.DefaultFullHttpRequest;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.codec.http.multipart.Attribute;
import io.netty.handler.codec.http.multipart.DefaultHttpDataFactory;
import io.netty.handler.codec.http.multipart.HttpDataFactory;
import io.netty.handler.codec.http.multipart.HttpPostRequestEncoder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.charset.StandardCharsets;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;

class HttpClientFormEncoderTests {

	@Test
	void charsetIsPreservedWhenMultipartIsConfiguredLast() throws Exception {
		String body = encode(form -> form.charset(StandardCharsets.ISO_8859_1)
		                                 .multipart(false), "é");

		assertThat(body).isEqualTo("key=%E9");
	}

	@Test
	void encodingIsPreservedWhenMultipartIsConfiguredLast() throws Exception {
		String body = encode(form -> form.encoding(HttpPostRequestEncoder.EncoderMode.RFC3986)
		                                 .multipart(false), "*");

		assertThat(body).isEqualTo("key=%2A");
	}

	@Test
	void subsequentCallsUseTheLatestEncoder() throws Exception {
		DefaultFullHttpRequest request = newRequest();
		HttpClientFormEncoder encoder = newEncoder(new DefaultHttpDataFactory(false), request);

		try {
			encoder.charset(StandardCharsets.ISO_8859_1);
			encoder.attr("first", "é");
			encoder.encoding(HttpPostRequestEncoder.EncoderMode.RFC3986);
			encoder.multipart(false);
			encoder.attr("second", "*");

			encoder = encoder.applyChanges(request);
			encoder.finalizeRequest();

			assertThat(request.content().toString(StandardCharsets.US_ASCII))
					.isEqualTo("first=%E9&second=%2A");
		}
		finally {
			encoder.cleanFiles();
			request.release();
		}
	}

	@ParameterizedTest
	@ValueSource(booleans = {true, false})
	void customFactoryIsUsedRegardlessOfMultipartSetterOrder(boolean factoryFirst) throws Exception {
		CountingHttpDataFactory defaultFactory = new CountingHttpDataFactory();
		CountingHttpDataFactory customFactory = new CountingHttpDataFactory();
		DefaultFullHttpRequest request = newRequest();
		HttpClientFormEncoder encoder = newEncoder(defaultFactory, request);

		try {
			if (factoryFirst) {
				encoder.factory(customFactory).multipart(false);
			}
			else {
				encoder.multipart(false).factory(customFactory);
			}
			encoder.attr("key", "value");

			encoder = encoder.applyChanges(request);
			encoder.finalizeRequest();
			encoder.cleanFiles();

			assertThat(defaultFactory.createAttributeCalls).isZero();
			assertThat(defaultFactory.cleanRequestCalls).isZero();
			assertThat(customFactory.createAttributeCalls).isEqualTo(2);
			assertThat(customFactory.cleanRequestCalls).isOne();
		}
		finally {
			request.release();
		}
	}

	@Test
	void cleanOnTerminateIsPreservedWhenCharsetChanges() throws Exception {
		assertCleanOnTerminateIsPreserved(form -> form.charset(StandardCharsets.ISO_8859_1));
	}

	@Test
	void cleanOnTerminateIsPreservedWhenEncodingChanges() throws Exception {
		assertCleanOnTerminateIsPreserved(
				form -> form.encoding(HttpPostRequestEncoder.EncoderMode.RFC3986));
	}

	@Test
	void cleanOnTerminateIsPreservedWhenMultipartChanges() throws Exception {
		assertCleanOnTerminateIsPreserved(form -> form.multipart(true));
	}

	@Test
	void cleanOnTerminateIsPreservedWhenFactoryChanges() throws Exception {
		assertCleanOnTerminateIsPreserved(form -> form.factory(new DefaultHttpDataFactory(false)));
	}

	private static void assertCleanOnTerminateIsPreserved(Consumer<HttpClientForm> formChange) throws Exception {
		DefaultFullHttpRequest request = newRequest();
		HttpClientFormEncoder encoder = newEncoder(new DefaultHttpDataFactory(false), request);

		try {
			encoder.cleanOnTerminate(false);
			formChange.accept(encoder);

			HttpClientFormEncoder changedEncoder = encoder.applyChanges(request);

			assertThat(changedEncoder).isNotSameAs(encoder);
			assertThat(changedEncoder.cleanOnTerminate).isFalse();
		}
		finally {
			request.release();
		}
	}

	private static String encode(Consumer<HttpClientForm> formConfig, String value) throws Exception {
		DefaultFullHttpRequest request = newRequest();
		HttpClientFormEncoder encoder = newEncoder(new DefaultHttpDataFactory(false), request);

		try {
			formConfig.accept(encoder);
			encoder.attr("key", value);

			encoder = encoder.applyChanges(request);
			encoder.finalizeRequest();

			return request.content().toString(StandardCharsets.US_ASCII);
		}
		finally {
			encoder.cleanFiles();
			request.release();
		}
	}

	private static HttpClientFormEncoder newEncoder(HttpDataFactory factory, HttpRequest request) throws Exception {
		return new HttpClientFormEncoder(factory,
				request,
				false,
				StandardCharsets.UTF_8,
				HttpPostRequestEncoder.EncoderMode.RFC1738);
	}

	private static DefaultFullHttpRequest newRequest() {
		return new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.POST, "/");
	}

	static final class CountingHttpDataFactory extends DefaultHttpDataFactory {

		int cleanRequestCalls;
		int createAttributeCalls;

		CountingHttpDataFactory() {
			super(false);
		}

		@Override
		public Attribute createAttribute(HttpRequest request, String name, String value) {
			createAttributeCalls++;
			return super.createAttribute(request, name, value);
		}

		@Override
		public void cleanRequestHttpData(HttpRequest request) {
			cleanRequestCalls++;
			super.cleanRequestHttpData(request);
		}
	}
}
