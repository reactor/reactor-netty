/*
 * Copyright (c) 2011-2019 Pivotal Software Inc, All Rights Reserved.
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

package reactor.ipc.netty.http.server;

import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.ByteBuffer;
import java.nio.channels.AsynchronousFileChannel;
import java.nio.channels.Channel;
import java.nio.channels.CompletionHandler;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BiFunction;
import java.util.function.BiPredicate;
import java.util.function.Consumer;
import java.util.function.Function;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpMethod;
import org.junit.Test;
import org.reactivestreams.Publisher;
import reactor.core.publisher.Flux;
import reactor.core.publisher.FluxSink;
import reactor.ipc.netty.NettyContext;
import reactor.ipc.netty.NettyOutbound;
import reactor.ipc.netty.http.client.HttpClient;
import reactor.ipc.netty.http.client.HttpClientOptions;
import reactor.ipc.netty.http.client.HttpClientResponse;

import static org.assertj.core.api.Assertions.assertThat;

public class HttpSendFileTests {
	protected void customizeClientOptions(HttpClientOptions.Builder options) {

	}

	protected void customizeServerOptions(HttpServerOptions.Builder options) {

	}

	@Test
	public void sendFileChunked() throws IOException, URISyntaxException {
		Path largeFile = Paths.get(getClass().getResource("/largeFile.txt").toURI());
		long fileSize = Files.size(largeFile);
		assertSendFile(out -> out.sendFileChunked(largeFile, 0, fileSize));
	}

	@Test
	public void sendFileChunkedOffset() throws IOException, URISyntaxException {
		Path largeFile = Paths.get(getClass().getResource("/largeFile.txt").toURI());
		long fileSize = Files.size(largeFile);
		assertSendFile(out -> out.sendFileChunked(largeFile, 1024, fileSize - 1024),
		               false, -1, (req, res) -> false,
		               body -> assertThat(body).startsWith("<- 1024 mark here")
		                                       .endsWith("End of File"));
	}

	@Test
	public void sendZipFileChunked() throws IOException {
		Path path = Files.createTempFile(null, ".zip");
		Files.copy(this.getClass().getResourceAsStream("/zipFile.zip"), path, StandardCopyOption.REPLACE_EXISTING);
		path.toFile().deleteOnExit();

		try (FileSystem zipFs = FileSystems.newFileSystem(path, null)) {
			Path fromZipFile = zipFs.getPath("/largeFile.txt");
			long fileSize = Files.size(fromZipFile);
			assertSendFile(out -> out.sendFileChunked(fromZipFile, 0, fileSize));
		}
	}

	@Test
	public void sendZipFileDefault() throws IOException {
		Path path = Files.createTempFile(null, ".zip");
		Files.copy(this.getClass().getResourceAsStream("/zipFile.zip"), path, StandardCopyOption.REPLACE_EXISTING);
		path.toFile().deleteOnExit();

		try (FileSystem zipFs = FileSystems.newFileSystem(path, null)) {
			Path fromZipFile = zipFs.getPath("/largeFile.txt");
			long fileSize = Files.size(fromZipFile);

			assertSendFile(out -> out.sendFile(fromZipFile, 0, fileSize));
		}
	}

	@Test
	public void sendZipFileCompressionOn() throws IOException {
		Path path = Files.createTempFile(null, ".zip");
		Files.copy(this.getClass().getResourceAsStream("/zipFile.zip"), path, StandardCopyOption.REPLACE_EXISTING);
		path.toFile().deleteOnExit();

		try (FileSystem zipFs = FileSystems.newFileSystem(path, null)) {
			Path fromZipFile = zipFs.getPath("/largeFile.txt");
			long fileSize = Files.size(fromZipFile);

			assertSendFile(out -> out.compression(true).sendFile(fromZipFile, 0, fileSize), true, -1, (req, res) -> false);
		}
	}

	@Test
	public void sendZipFileCompressionSize_1() throws IOException {
		Path path = Files.createTempFile(null, ".zip");
		Files.copy(this.getClass().getResourceAsStream("/zipFile.zip"), path, StandardCopyOption.REPLACE_EXISTING);
		path.toFile().deleteOnExit();

		try (FileSystem zipFs = FileSystems.newFileSystem(path, null)) {
			Path fromZipFile = zipFs.getPath("/largeFile.txt");
			long fileSize = Files.size(fromZipFile);

			assertSendFile(out -> out.addHeader(HttpHeaderNames.CONTENT_LENGTH, "1245")
			                         .sendFile(fromZipFile, 0, fileSize), true, 2048, null);
		}
	}

	@Test
	public void sendZipFileCompressionSize_2() throws IOException {
		Path path = Files.createTempFile(null, ".zip");
		Files.copy(this.getClass().getResourceAsStream("/zipFile.zip"), path, StandardCopyOption.REPLACE_EXISTING);
		path.toFile().deleteOnExit();

		try (FileSystem zipFs = FileSystems.newFileSystem(path, null)) {
			Path fromZipFile = zipFs.getPath("/largeFile.txt");
			long fileSize = Files.size(fromZipFile);

			assertSendFile(out -> out.addHeader(HttpHeaderNames.CONTENT_LENGTH, "1245")
			                         .sendFile(fromZipFile, 0, fileSize), true, 2048, (req, res) -> true);
		}
	}

	@Test
	public void sendZipFileCompressionSize_3() throws IOException {
		Path path = Files.createTempFile(null, ".zip");
		Files.copy(this.getClass().getResourceAsStream("/zipFile.zip"), path, StandardCopyOption.REPLACE_EXISTING);
		path.toFile().deleteOnExit();

		try (FileSystem zipFs = FileSystems.newFileSystem(path, null)) {
			Path fromZipFile = zipFs.getPath("/largeFile.txt");
			long fileSize = Files.size(fromZipFile);

			assertSendFile(out -> out.addHeader(HttpHeaderNames.CONTENT_LENGTH, "1245")
			                         .sendFile(fromZipFile, 0, fileSize), true, 512, null);
		}
	}

	@Test
	public void sendZipFileCompressionSize_4() throws IOException {
		Path path = Files.createTempFile(null, ".zip");
		Files.copy(this.getClass().getResourceAsStream("/zipFile.zip"), path, StandardCopyOption.REPLACE_EXISTING);
		path.toFile().deleteOnExit();

		try (FileSystem zipFs = FileSystems.newFileSystem(path, null)) {
			Path fromZipFile = zipFs.getPath("/largeFile.txt");
			long fileSize = Files.size(fromZipFile);

			assertSendFile(out -> out.addHeader(HttpHeaderNames.CONTENT_LENGTH, "1245")
			                         .sendFile(fromZipFile, 0, fileSize), true, 512, (req, res) -> false);
		}
	}

	@Test
	public void sendZipFileCompressionPredicate_1() throws IOException {
		Path path = Files.createTempFile(null, ".zip");
		Files.copy(this.getClass().getResourceAsStream("/zipFile.zip"), path, StandardCopyOption.REPLACE_EXISTING);
		path.toFile().deleteOnExit();

		try (FileSystem zipFs = FileSystems.newFileSystem(path, null)) {
			Path fromZipFile = zipFs.getPath("/largeFile.txt");
			long fileSize = Files.size(fromZipFile);

			assertSendFile(out -> out.sendFile(fromZipFile, 0, fileSize), true, -1, (req, res) -> true);
		}
	}

	@Test
	public void sendZipFileCompressionPredicate_2() throws IOException {
		Path path = Files.createTempFile(null, ".zip");
		Files.copy(this.getClass().getResourceAsStream("/zipFile.zip"), path, StandardCopyOption.REPLACE_EXISTING);
		path.toFile().deleteOnExit();

		try (FileSystem zipFs = FileSystems.newFileSystem(path, null)) {
			Path fromZipFile = zipFs.getPath("/largeFile.txt");
			long fileSize = Files.size(fromZipFile);

			assertSendFile(out -> out.addHeader("test", "test").sendFile(fromZipFile, 0, fileSize), true,
					-1, (req, res) -> res.responseHeaders().contains("test"));
		}
	}

	@Test
	public void sendZipFileCompressionPredicate_3() throws IOException {
		Path path = Files.createTempFile(null, ".zip");
		Files.copy(this.getClass().getResourceAsStream("/zipFile.zip"), path, StandardCopyOption.REPLACE_EXISTING);
		path.toFile().deleteOnExit();

		try (FileSystem zipFs = FileSystems.newFileSystem(path, null)) {
			Path fromZipFile = zipFs.getPath("/largeFile.txt");
			long fileSize = Files.size(fromZipFile);

			assertSendFile(out -> out.addHeader("test", "test").sendFile(fromZipFile, 0, fileSize), true,
					-1, (req, res) -> !res.responseHeaders().contains("test"));
		}
	}

	private void assertSendFile(Function<HttpServerResponse, NettyOutbound> fn) {
		assertSendFile(fn, false, -1, (req, res) -> false);
	}

	private void assertSendFile(Function<HttpServerResponse, NettyOutbound> fn, boolean compression,
			int compressionSize, BiPredicate<HttpServerRequest, HttpServerResponse> compressionPredicate) {
		assertSendFile(fn, compression, compressionSize, compressionPredicate,
		               body ->
		                   assertThat(body).startsWith("This is an UTF-8 file that is larger than 1024 bytes. "
		                                               + "It contains accents like é.")
		                                   .contains("1024 mark here -><- 1024 mark here")
		                                   .endsWith("End of File"));
	}

	private void assertSendFile(Function<HttpServerResponse, NettyOutbound> fn, boolean compression, int compressionSize,
			BiPredicate<HttpServerRequest, HttpServerResponse> compressionPredicate, Consumer<String> bodyAssertion) {
		NettyContext context =
				HttpServer.create(opt -> {
				    opt.host("localhost");
				    if (compressionPredicate != null) {
				        opt.compression(compressionPredicate);
				    }
				    if (compressionSize > -1) {
				        opt.compression(compressionSize);
				    }
				    customizeServerOptions(opt);
				})
				          .newHandler((req, resp) -> fn.apply(resp))
				          .block();
		assertThat(context).isNotNull();

		HttpClientResponse response =
				HttpClient.create(opt -> customizeClientOptions(opt.connectAddress(context::address)
				                                                   .compression(compression)))
				          .get("/foo")
				          .block(Duration.ofSeconds(30));
		assertThat(response).isNotNull();

		context.dispose();
		context.onClose().block();

		String body = response.receive().aggregate().asString(StandardCharsets.UTF_8).block();
		bodyAssertion.accept(body);
	}

	@Test
	public void sendFileAsync4096() throws IOException, URISyntaxException {
		doTestSendFileAsync((req, resp) -> resp.sendByteArray(req.receive()
				                               .aggregate()
				                               .asByteArray()),
				4096, null);
	}

	@Test
	public void sendFileAsync4096Negative() throws IOException, URISyntaxException {
		doTestSendFileAsync((req, resp) -> req.receive()
				                              .take(10)
				                              .doOnNext(b -> resp.status(500)
				                                                 .header(HttpHeaderNames.CONNECTION, "close"))
				                              .then(),
				4096, "error".getBytes(Charset.defaultCharset()));
	}

	@Test
	public void sendFileAsync1024() throws IOException, URISyntaxException {
		doTestSendFileAsync((req, resp) -> resp.sendByteArray(req.receive()
				                               .aggregate()
				                               .asByteArray()),
				1024, null);
	}

	private void doTestSendFileAsync(BiFunction<? super HttpServerRequest, ? super
			HttpServerResponse, ? extends Publisher<Void>> fn, int chunk, byte[] expectedContent) throws IOException, URISyntaxException {
		Path largeFile = Paths.get(getClass().getResource("/largeFile.txt").toURI());
		Path tempFile = Files.createTempFile(largeFile.getParent(),"temp", ".txt");
		tempFile.toFile().deleteOnExit();

		byte[] fileBytes = Files.readAllBytes(largeFile);
		for (int i = 0; i < 1000; i++) {
			Files.write(tempFile, fileBytes, StandardOpenOption.APPEND);
		}

		ByteBufAllocator allocator = ByteBufAllocator.DEFAULT;

		Flux<ByteBuf> content =
				Flux.using(
				        () -> AsynchronousFileChannel.open(tempFile, StandardOpenOption.READ),
				        ch -> Flux.create(fluxSink -> {
				                ByteBuffer buf = ByteBuffer.allocate(chunk);
				                ch.read(buf, 0, buf, new TestCompletionHandler(ch, fluxSink, allocator, chunk));
				        }),
				        this::closeChannel);

		NettyContext context =
				HttpServer.create(opt -> customizeServerOptions(opt.host("localhost")))
				          .newHandler(fn)
				          .block();
		assertThat(context).isNotNull();

		byte[] response =
				HttpClient.create(opt -> customizeClientOptions(opt.connectAddress(context::address)))
				          .request(HttpMethod.POST, "/",
				                  req -> req.send(content))
				          .flatMap(res -> res.receive()
				                             .aggregate()
				                             .asByteArray())
				          .onErrorReturn(IOException.class,
				              "error".getBytes(Charset.defaultCharset()))
				          .block();

		assertThat(response).isEqualTo(expectedContent == null ? Files.readAllBytes(tempFile) : expectedContent);
		context.dispose();
	}

	private void closeChannel(Channel channel) {
		if (channel != null && channel.isOpen()) {
			try {
				channel.close();
			}
			catch (IOException ignored) {
			}
		}
	}

	private static final class TestCompletionHandler implements CompletionHandler<Integer, ByteBuffer> {

		private final AsynchronousFileChannel channel;

		private final FluxSink<ByteBuf> sink;

		private final ByteBufAllocator allocator;

		private final int chunk;

		private AtomicLong position;

		TestCompletionHandler(AsynchronousFileChannel channel, FluxSink<ByteBuf> sink,
							  ByteBufAllocator allocator, int chunk) {
			this.channel = channel;
			this.sink = sink;
			this.allocator = allocator;
			this.chunk = chunk;
			this.position = new AtomicLong(0);
		}

		@Override
		public void completed(Integer read, ByteBuffer dataBuffer) {
			if (read != -1) {
				long pos = this.position.addAndGet(read);
				dataBuffer.flip();
				ByteBuf buf = allocator.buffer().writeBytes(dataBuffer);
				this.sink.next(buf);

				if (!this.sink.isCancelled()) {
					ByteBuffer newByteBuffer = ByteBuffer.allocate(chunk);
					this.channel.read(newByteBuffer, pos, newByteBuffer, this);
				}
			}
			else {
				try {
					if (channel != null) {
						channel.close();
					}
				}
				catch (IOException ignored) {
				}
				this.sink.complete();
			}
		}

		@Override
		public void failed(Throwable exc, ByteBuffer dataBuffer) {
			try {
				if (channel != null) {
					channel.close();
				}
			}
			catch (IOException ignored) {
			}
			this.sink.error(exc);
		}
	}
}
