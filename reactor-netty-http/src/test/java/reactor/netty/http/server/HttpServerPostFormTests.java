/*
 * Copyright (c) 2021-2024 VMware, Inc. or its affiliates, All Rights Reserved.
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
package reactor.netty.http.server;

import io.netty.buffer.CompositeByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.handler.codec.http.multipart.HttpData;
import io.netty.handler.ssl.util.InsecureTrustManagerFactory;
import io.netty.handler.ssl.util.SelfSignedCertificate;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.netty.BaseHttpTest;
import reactor.netty.http.Http2SslContextSpec;
import reactor.netty.http.HttpProtocol;
import reactor.netty.http.client.HttpClient;
import reactor.util.annotation.Nullable;
import reactor.util.function.Tuple2;
import reactor.util.function.Tuples;

import java.io.IOException;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * This test class verifies {@link HttpServer} post form handling.
 *
 * @author Violeta Georgieva
 * @since 1.0.11
 */
class HttpServerPostFormTests extends BaseHttpTest {

	@Retention(RetentionPolicy.RUNTIME)
	@Target(ElementType.METHOD)
	@ParameterizedTest(name = "{displayName}({0}, {1})")
	@MethodSource("data")
	@interface ParameterizedPostFormTest {
	}

	@SuppressWarnings("deprecation")
	static Object[][] data() throws Exception {
		SelfSignedCertificate cert = new SelfSignedCertificate();
		Http2SslContextSpec serverCtx = Http2SslContextSpec.forServer(cert.certificate(), cert.privateKey());
		Http2SslContextSpec clientCtx =
				Http2SslContextSpec.forClient()
						.configure(builder -> builder.trustManager(InsecureTrustManagerFactory.INSTANCE));

		HttpServer server = HttpServer.create().httpRequestDecoder(spec -> spec.h2cMaxContentLength(32 * 1024));

		HttpServer h2Server = server.protocol(HttpProtocol.H2)
				.secure(spec -> spec.sslContext(serverCtx));

		HttpServer h2Http1Server = server.protocol(HttpProtocol.H2, HttpProtocol.HTTP11)
				.secure(spec -> spec.sslContext(serverCtx));

		HttpClient client = HttpClient.create();

		HttpClient h2Client = client.protocol(HttpProtocol.H2)
				.secure(spec -> spec.sslContext(clientCtx));

		HttpClient h2Http1Client = client.protocol(HttpProtocol.H2, HttpProtocol.HTTP11)
				.secure(spec -> spec.sslContext(clientCtx));

		return new Object[][] {{server, client},
				{server.protocol(HttpProtocol.H2C), client.protocol(HttpProtocol.H2C)},
				{server.protocol(HttpProtocol.H2C, HttpProtocol.HTTP11), client.protocol(HttpProtocol.H2C, HttpProtocol.HTTP11)},
				{h2Server, h2Client},
				{h2Http1Server, h2Http1Client}};
	}

	@ParameterizedPostFormTest
	void testMultipartExceedsMaxSizeInMemoryConfigOnRequest(HttpServer server, HttpClient client) throws Exception {
		doTestPostForm(server, client, spec -> spec.maxInMemorySize(-1).maxSize(8 * 1024), false, true, false,
				"Size exceed allowed maximum capacity");
	}

	@ParameterizedPostFormTest
	void testMultipartExceedsMaxSizeInMemoryConfigOnServer(HttpServer server, HttpClient client) throws Exception {
		doTestPostForm(server, client, spec -> spec.maxInMemorySize(-1).maxSize(8 * 1024), true, true, false,
				"Size exceed allowed maximum capacity");
	}

	@ParameterizedPostFormTest
	void testMultipartExceedsMaxSizeMixedConfigOnRequest(HttpServer server, HttpClient client) throws Exception {
		doTestPostForm(server, client, spec -> spec.maxInMemorySize(256).maxSize(8 * 1024), false, true, false,
				"Size exceed allowed maximum capacity");
	}

	@ParameterizedPostFormTest
	void testMultipartExceedsMaxSizeMixedConfigOnServer(HttpServer server, HttpClient client) throws Exception {
		doTestPostForm(server, client, spec -> spec.maxInMemorySize(256).maxSize(8 * 1024), true, true, false,
				"Size exceed allowed maximum capacity");
	}

	@ParameterizedPostFormTest
	void testMultipartExceedsMaxSizeOnDiskConfigOnRequest(HttpServer server, HttpClient client) throws Exception {
		doTestPostForm(server, client, spec -> spec.maxInMemorySize(0).maxSize(8 * 1024), false, true, false,
				"Size exceed allowed maximum capacity");
	}

	@ParameterizedPostFormTest
	void testMultipartExceedsMaxSizeOnDiskConfigOnServer(HttpServer server, HttpClient client) throws Exception {
		doTestPostForm(server, client, spec -> spec.maxInMemorySize(0).maxSize(8 * 1024), true, true, false,
				"Size exceed allowed maximum capacity");
	}

	@ParameterizedPostFormTest
	void testMultipartExceedsMaxSizeStreamingConfigOnRequest(HttpServer server, HttpClient client) throws Exception {
		doTestPostForm(server, client, spec -> spec.streaming(true).maxSize(8 * 1024), false, true, true,
				"Size exceed allowed maximum capacity");
	}

	@ParameterizedPostFormTest
	void testMultipartExceedsMaxSizeStreamingConfigOnServer(HttpServer server, HttpClient client) throws Exception {
		doTestPostForm(server, client, spec -> spec.streaming(true).maxSize(8 * 1024), true, true, true,
				"Size exceed allowed maximum capacity");
	}

	@ParameterizedPostFormTest
	void testMultipartInMemoryConfigOnRequest(HttpServer server, HttpClient client) throws Exception {
		doTestPostForm(server, client, spec -> spec.maxInMemorySize(-1), false, true, false,
				"[test1 MemoryFileUpload true] [attr1 MemoryAttribute true] [test2 MemoryFileUpload true] ");
	}

	@ParameterizedPostFormTest
	void testMultipartInMemoryConfigOnServer(HttpServer server, HttpClient client) throws Exception {
		doTestPostForm(server, client, spec -> spec.maxInMemorySize(-1), true, true, false,
				"[test1 MemoryFileUpload true] [attr1 MemoryAttribute true] [test2 MemoryFileUpload true] ");
	}

	@ParameterizedPostFormTest
	void testMultipartMixedConfigOnRequest(HttpServer server, HttpClient client) throws Exception {
		doTestPostForm(server, client, spec -> spec.maxInMemorySize(256), false, true, false,
				"[test1 MixedFileUpload true] [attr1 MixedAttribute true] [test2 MixedFileUpload true] ");
	}

	@ParameterizedPostFormTest
	void testMultipartMixedConfigOnServer(HttpServer server, HttpClient client) throws Exception {
		doTestPostForm(server, client, spec -> spec.maxInMemorySize(256), true, true, false,
				"[test1 MixedFileUpload true] [attr1 MixedAttribute true] [test2 MixedFileUpload true] ");
	}

	@ParameterizedPostFormTest
	void testMultipartOnDiskConfigOnRequest(HttpServer server, HttpClient client) throws Exception {
		doTestPostForm(server, client, spec -> spec.maxInMemorySize(0), false, true, false,
				"[test1 DiskFileUpload true] [attr1 DiskAttribute true] [test2 DiskFileUpload true] ");
	}

	@ParameterizedPostFormTest
	void testMultipartOnDiskConfigOnServer(HttpServer server, HttpClient client) throws Exception {
		doTestPostForm(server, client, spec -> spec.maxInMemorySize(0), true, true, false,
				"[test1 DiskFileUpload true] [attr1 DiskAttribute true] [test2 DiskFileUpload true] ");
	}

	@ParameterizedPostFormTest
	void testMultipartStreamingConfigOnRequest(HttpServer server, HttpClient client) throws Exception {
		doTestPostForm(server, client, spec -> spec.streaming(true), false, true, true, null);
	}

	@ParameterizedPostFormTest
	void testMultipartStreamingConfigOnServer(HttpServer server, HttpClient client) throws Exception {
		doTestPostForm(server, client, spec -> spec.streaming(true), true, true, true, null);
	}

	@ParameterizedPostFormTest
	void testUrlencodedExceedsMaxSizeInMemoryConfigOnRequest(HttpServer server, HttpClient client) throws Exception {
		doTestPostForm(server, client, spec -> spec.maxInMemorySize(-1).maxSize(10), false, false, false,
				"Size exceed allowed maximum capacity");
	}

	@ParameterizedPostFormTest
	void testUrlencodedExceedsMaxSizeInMemoryConfigOnServer(HttpServer server, HttpClient client) throws Exception {
		doTestPostForm(server, client, spec -> spec.maxInMemorySize(-1).maxSize(10), true, false, false,
				"Size exceed allowed maximum capacity");
	}

	@ParameterizedPostFormTest
	void testUrlencodedExceedsMaxSizeMixedConfigOnRequest(HttpServer server, HttpClient client) throws Exception {
		doTestPostForm(server, client, spec -> spec.maxInMemorySize(256).maxSize(10), false, false, false,
				"Size exceed allowed maximum capacity");
	}

	@ParameterizedPostFormTest
	void testUrlencodedExceedsMaxSizeMixedConfigOnServer(HttpServer server, HttpClient client) throws Exception {
		doTestPostForm(server, client, spec -> spec.maxInMemorySize(256).maxSize(10), true, false, false,
				"Size exceed allowed maximum capacity");
	}

	@ParameterizedPostFormTest
	void testUrlencodedExceedsMaxSizeOnDiskConfigOnRequest(HttpServer server, HttpClient client) throws Exception {
		doTestPostForm(server, client, spec -> spec.maxInMemorySize(0).maxSize(10), false, false, false,
				"Size exceed allowed maximum capacity");
	}

	@ParameterizedPostFormTest
	void testUrlencodedExceedsMaxSizeOnDiskConfigOnServer(HttpServer server, HttpClient client) throws Exception {
		doTestPostForm(server, client, spec -> spec.maxInMemorySize(0).maxSize(10), true, false, false,
				"Size exceed allowed maximum capacity");
	}

	@ParameterizedPostFormTest
	void testUrlencodedInMemoryConfigOnRequest(HttpServer server, HttpClient client) throws Exception {
		doTestPostForm(server, client, spec -> spec.maxInMemorySize(-1), false, false, false,
				"[test1 MemoryAttribute true] [attr1 MemoryAttribute true] [test2 MemoryAttribute true] ");
	}

	@ParameterizedPostFormTest
	void testUrlencodedInMemoryConfigOnServer(HttpServer server, HttpClient client) throws Exception {
		doTestPostForm(server, client, spec -> spec.maxInMemorySize(-1), true, false, false,
				"[test1 MemoryAttribute true] [attr1 MemoryAttribute true] [test2 MemoryAttribute true] ");
	}

	@ParameterizedPostFormTest
	void testUrlencodedMixedConfigOnRequest(HttpServer server, HttpClient client) throws Exception {
		doTestPostForm(server, client, spec -> spec.maxInMemorySize(256), false, false, false,
				"[test1 MixedAttribute true] [attr1 MixedAttribute true] [test2 MixedAttribute true] ");
	}

	@ParameterizedPostFormTest
	void testUrlencodedMixedConfigOnServer(HttpServer server, HttpClient client) throws Exception {
		doTestPostForm(server, client, spec -> spec.maxInMemorySize(256), true, false, false,
				"[test1 MixedAttribute true] [attr1 MixedAttribute true] [test2 MixedAttribute true] ");
	}

	@ParameterizedPostFormTest
	void testUrlencodedOnDiskConfigOnRequest(HttpServer server, HttpClient client) throws Exception {
		doTestPostForm(server, client, spec -> spec.maxInMemorySize(0), false, false, false,
				"[test1 DiskAttribute true] [attr1 DiskAttribute true] [test2 DiskAttribute true] ");
	}

	@ParameterizedPostFormTest
	void testUrlencodedOnDiskConfigOnServer(HttpServer server, HttpClient client) throws Exception {
		doTestPostForm(server, client, spec -> spec.maxInMemorySize(0), true, false, false,
				"[test1 DiskAttribute true] [attr1 DiskAttribute true] [test2 DiskAttribute true] ");
	}

	private void doTestPostForm(HttpServer server, HttpClient client,
			Consumer<HttpServerFormDecoderProvider.Builder> provider, boolean configOnServer,
			boolean multipart, boolean streaming, @Nullable String expectedResponse) throws Exception {
		AtomicReference<List<HttpData>> originalHttpData1 = new AtomicReference<>(new ArrayList<>());
		AtomicReference<List<HttpData>> originalHttpData2 = new AtomicReference<>(new ArrayList<>());
		AtomicReference<Map<String, CompositeByteBuf>> copiedHttpData = new AtomicReference<>(new HashMap<>());
		server = (configOnServer ? server.httpFormDecoder(provider) : server)
		        .handle((req, res) ->
		            res.sendString((configOnServer ? req.receiveForm() : req.receiveForm(provider))
		                    .flatMap(data -> {
		                        if ("0".equals(req.path())) {
		                            originalHttpData1.get().add(data);

		                            if (streaming) {
		                                CompositeByteBuf copy = copiedHttpData.get()
		                                        .computeIfAbsent(data.getName(), k -> Unpooled.compositeBuffer());
		                                try {
		                                    // In case of streaming this is not a blocking call
		                                    copy.writeBytes(data.get());
		                                }
		                                catch (IOException e) {
		                                    return Mono.error(e);
		                                }
		                            }
		                        }
		                        else {
		                            originalHttpData2.get().add(data);
		                        }
		                        return Mono.just('[' + data.getName() + ' ' +
		                                data.getClass().getSimpleName() + ' ' +
		                                data.isCompleted() + "] ");
		                    })
		                    .onErrorResume(t -> Mono.just(t.getCause().getMessage()))
		                    .log()));

		disposableServer = server.bindNow();

		List<Tuple2<Integer, String>> responses;
		Path file = Paths.get(getClass().getResource("/largeFile1.txt").toURI());
		responses =
				Flux.range(0, 2)
				    .flatMap(i ->
				            client.port(disposableServer.port())
				                  .post()
				                  .uri("/" + i)
				                  .sendForm((req, form) -> form.multipart(multipart)
				                                               .file("test1", "largeFile1.txt", file.toFile(), null)
				                                               .attr("attr1", "attr2")
				                                               .file("test2", "largeFile1.txt", file.toFile(), null))
				                  .responseSingle((r, buf) -> buf.asString().map(s -> Tuples.of(r.status().code(), s))))
				    .collectList()
				    .block(Duration.ofSeconds(30));

		assertThat(responses).as("response").isNotNull();

		for (Tuple2<Integer, String> response : responses) {
			assertThat(response.getT1()).as("status code").isEqualTo(200);

			if (expectedResponse != null) {
				assertThat(response.getT2()).as("response body reflecting request").contains(expectedResponse);
			}
		}

		assertThat(originalHttpData1.get()).allMatch(data -> data.refCnt() == 0);
		assertThat(originalHttpData2.get()).allMatch(data -> data.refCnt() == 0);

		if (streaming) {
			if (expectedResponse == null) {
				assertThat(copiedHttpData.get()).hasSize(3);

				byte[] fileBytes = Files.readAllBytes(file);
				testContent(copiedHttpData.get().get("test1"), fileBytes);
				testContent(copiedHttpData.get().get("test2"), fileBytes);

				copiedHttpData.get().forEach((s, buffer) -> buffer.release());
			}
			else {
				List<HttpProtocol> serverProtocols = Arrays.asList(server.configuration().protocols());
				if (serverProtocols.size() == 1 && serverProtocols.get(0).equals(HttpProtocol.HTTP11)) {
					assertThat(copiedHttpData.get()).hasSize(1);
					copiedHttpData.get().forEach((s, buffer) -> buffer.release());
				}
				else {
					assertThat(copiedHttpData.get()).hasSize(0);
				}
			}
		}
	}

	private void testContent(CompositeByteBuf file, byte[] expectedBytes) {
		byte[] fileBytes = new byte[file.readableBytes()];
		file.readBytes(fileBytes);
		assertThat(fileBytes).isEqualTo(expectedBytes);
	}
}
