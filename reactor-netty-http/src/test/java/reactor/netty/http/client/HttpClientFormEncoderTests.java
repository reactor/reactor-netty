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

import io.netty.buffer.UnpooledByteBufAllocator;
import io.netty.handler.codec.http.DefaultFullHttpRequest;
import io.netty.handler.codec.http.HttpContent;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.codec.http.multipart.Attribute;
import io.netty.handler.codec.http.multipart.DefaultHttpDataFactory;
import io.netty.handler.codec.http.multipart.FileUpload;
import io.netty.handler.codec.http.multipart.HttpDataFactory;
import io.netty.handler.codec.http.multipart.HttpPostRequestEncoder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Named;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.ByteArrayInputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.function.Consumer;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

class HttpClientFormEncoderTests {

	DefaultFullHttpRequest request;
	HttpClientFormEncoder encoder;

	@BeforeEach
	void setUp() throws Exception {
		request = newRequest();
		encoder = newEncoder(new DefaultHttpDataFactory(false), request);
	}

	@AfterEach
	void tearDown() {
		encoder.cleanFiles();
		request.release();
	}

	@Test
	void charsetIsPreservedWhenMultipartIsConfiguredLast() throws Exception {
		String body = encode(form -> form.charset(StandardCharsets.ISO_8859_1).multipart(false).attr("key", "é"));
		assertThat(body).isEqualTo("key=%E9");
	}

	@Test
	void encodingIsPreservedWhenMultipartIsConfiguredLast() throws Exception {
		String body = encode(form -> form.encoding(HttpPostRequestEncoder.EncoderMode.RFC3986).multipart(false).attr("key", "*"));
		assertThat(body).isEqualTo("key=%2A");
	}

	@Test
	void subsequentCallsUseTheLatestEncoder() throws Exception {
		String body = encode(form ->
			form.charset(StandardCharsets.ISO_8859_1)
			    .attr("first", "é")
			    .encoding(HttpPostRequestEncoder.EncoderMode.RFC3986)
			    .multipart(false)
			    .attr("second", "*"));
		assertThat(body).isEqualTo("first=%E9&second=%2A");
	}

	@ParameterizedTest
	@ValueSource(booleans = {true, false})
	void customFactoryIsUsedRegardlessOfMultipartSetterOrder(boolean factoryFirst) throws Exception {
		CountingHttpDataFactory defaultFactory = new CountingHttpDataFactory();
		CountingHttpDataFactory customFactory = new CountingHttpDataFactory();
		encoder = newEncoder(defaultFactory, request);

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
		assertThat(customFactory.createAttributeCalls).isPositive();
		assertThat(customFactory.cleanRequestCalls).isOne();
	}

	@Test
	void customFactoryLimitUsesFinalCharset() throws Exception {
		String body = encode(form -> form.factory(limitedFactory()).attr("key", "é").charset(StandardCharsets.ISO_8859_1));
		assertThat(body).isEqualTo("key=%E9");
	}

	@ParameterizedTest
	@ValueSource(booleans = {true, false})
	void customFactoryLimitUsesFinalMultipartSetting(boolean fileUpload) throws Exception {
		encoder.factory(limitedFactory());
		if (fileUpload) {
			encoder.file("key", "é", new ByteArrayInputStream("test".getBytes(StandardCharsets.UTF_8)), "text/plain");
		}
		else {
			encoder.attr("key", "é");
		}
		encoder.multipart(true);
		encoder = encoder.applyChanges(request);
		encoder.finalizeRequest();

		StringBuilder body = new StringBuilder();
		while (!encoder.isEndOfInput()) {
			HttpContent chunk = encoder.readChunk(UnpooledByteBufAllocator.DEFAULT);
			if (chunk != null) {
				try {
					body.append(chunk.content().toString(StandardCharsets.UTF_8));
				}
				finally {
					chunk.release();
				}
			}
		}

		assertThat(body.toString()).contains(fileUpload ? "filename=\"é\"" : "\r\n\r\né\r\n");
	}

	@Test
	void customFactoryLimitStillRejectsOversizedFinalEncoding() {
		encoder.factory(limitedFactory()).attr("key", "é");
		assertThatIllegalArgumentException().isThrownBy(() -> encoder.applyChanges(request).finalizeRequest())
		                                    .withMessage("Attribute bigger than maxSize allowed");
	}

	@Test
	void customFactoryIsUsedAndCleanedForStreamUploadWhenMultipartIsConfiguredLast() throws Exception {
		CountingHttpDataFactory defaultFactory = new CountingHttpDataFactory();
		CountingHttpDataFactory customFactory = new CountingHttpDataFactory();
		encoder = newEncoder(defaultFactory, request);

		encoder.factory(customFactory)
		       .multipart(false)
		       .file("file", "file.txt", new ByteArrayInputStream("test".getBytes(StandardCharsets.UTF_8)), "text/plain");

		encoder = encoder.applyChanges(request);
		encoder.finalizeRequest();
		encoder.cleanFiles();

		assertThat(customFactory.createFileUploadCalls).isOne();
		assertThat(customFactory.cleanRequestCalls).isOne();
		assertThat(defaultFactory.cleanRequestCalls).isZero();
	}

	@ParameterizedTest
	@MethodSource("configurationChanges")
	void cleanOnTerminateIsPreservedWhenConfigurationChanges(Consumer<HttpClientForm> formChange) {
		encoder.cleanOnTerminate(false);
		formChange.accept(encoder);

		HttpClientFormEncoder changedEncoder = encoder.applyChanges(request);

		assertThat(changedEncoder).isNotSameAs(encoder);
		assertThat(changedEncoder.cleanOnTerminate).isFalse();
	}

	@ParameterizedTest
	@MethodSource("unchangedConfigurations")
	void encoderIsNotRebuiltWhenConfigurationIsUnchanged(Consumer<HttpClientForm> formChange) throws Exception {
		encoder = newEncoder(DEFAULT_TEST_FACTORY, request);
		formChange.accept(encoder);
		assertThat(encoder.applyChanges(request)).isSameAs(encoder);
	}

	@Test
	void configurationChangesBetweenPartsAreAppliedOnce() throws Exception {
		CountingHttpDataFactory factory = new CountingHttpDataFactory();
		encoder = newEncoder(factory, request);
		int parts = 100;

		HttpClientForm form = encoder;
		for (int i = 0; i < parts; i++) {
			form = form.charset(i % 2 == 0 ? StandardCharsets.UTF_8 : StandardCharsets.ISO_8859_1)
			           .encoding(i % 2 == 0 ? HttpPostRequestEncoder.EncoderMode.RFC1738 : HttpPostRequestEncoder.EncoderMode.RFC3986)
			           .attr("k" + i, "*");
		}

		assertThat(form).isSameAs(encoder);

		encoder = encoder.applyChanges(request);
		encoder.finalizeRequest();

		assertThat(factory.createAttributeCalls).isLessThan(4 * parts);
		assertThat(request.content().toString(StandardCharsets.US_ASCII))
				.startsWith("k0=%2A&k1=%2A&")
				.endsWith("&k99=%2A");
	}

	private String encode(Consumer<HttpClientForm> formConfig) throws Exception {
		formConfig.accept(encoder);

		encoder = encoder.applyChanges(request);
		encoder.finalizeRequest();

		return request.content().toString(StandardCharsets.US_ASCII);
	}

	static Stream<Named<Consumer<HttpClientForm>>> configurationChanges() {
		return Stream.of(
				Named.of("charset", form -> form.charset(StandardCharsets.ISO_8859_1)),
				Named.of("encoding", form -> form.encoding(HttpPostRequestEncoder.EncoderMode.RFC3986)),
				Named.of("multipart", form -> form.multipart(true)),
				Named.of("factory", form -> form.factory(new DefaultHttpDataFactory(false))));
	}

	static Stream<Named<Consumer<HttpClientForm>>> unchangedConfigurations() {
		return Stream.of(
				Named.of("charset", form -> form.charset(StandardCharsets.UTF_8)),
				Named.of("encoding", form -> form.encoding(HttpPostRequestEncoder.EncoderMode.RFC1738)),
				Named.of("multipart", form -> form.multipart(false)),
				Named.of("factory", form -> form.factory(DEFAULT_TEST_FACTORY)));
	}

	private static HttpDataFactory limitedFactory() {
		DefaultHttpDataFactory factory = new DefaultHttpDataFactory(DefaultHttpDataFactory.MINSIZE);
		// "é" fits, but its UTF-8 URL encoding "%C3%A9" does not
		factory.setMaxLimit(4);
		return factory;
	}

	private static HttpClientFormEncoder newEncoder(HttpDataFactory factory, HttpRequest request) throws Exception {
		return new HttpClientFormEncoder(factory, request, false, StandardCharsets.UTF_8, HttpPostRequestEncoder.EncoderMode.RFC1738);
	}

	private static DefaultFullHttpRequest newRequest() {
		return new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.POST, "/");
	}

	static final HttpDataFactory DEFAULT_TEST_FACTORY = new DefaultHttpDataFactory(false);

	static final class CountingHttpDataFactory extends DefaultHttpDataFactory {

		int cleanRequestCalls;
		int createAttributeCalls;
		int createFileUploadCalls;

		CountingHttpDataFactory() {
			super(false);
		}

		@Override
		public Attribute createAttribute(HttpRequest request, String name, String value) {
			createAttributeCalls++;
			return super.createAttribute(request, name, value);
		}

		@Override
		public FileUpload createFileUpload(HttpRequest request, String name, String filename,
				String contentType, String contentTransferEncoding, Charset charset, long size) {
			createFileUploadCalls++;
			return super.createFileUpload(request, name, filename, contentType, contentTransferEncoding, charset, size);
		}

		@Override
		public void cleanRequestHttpData(HttpRequest request) {
			cleanRequestCalls++;
			super.cleanRequestHttpData(request);
		}
	}
}
