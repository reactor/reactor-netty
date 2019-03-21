/*
 * Copyright (c) 2011-2018 Pivotal Software Inc, All Rights Reserved.
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
import java.nio.channels.CompletionHandler;
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
import java.util.function.Consumer;
import java.util.function.Function;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import io.netty.handler.codec.http.HttpMethod;
import org.junit.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.FluxSink;
import reactor.core.publisher.Mono;
import reactor.ipc.netty.DisposableServer;
import reactor.ipc.netty.NettyOutbound;
import reactor.ipc.netty.http.client.HttpClient;

import static org.assertj.core.api.Assertions.assertThat;

public class HttpSendFileTests {
	protected HttpClient customizeClientOptions(HttpClient httpClient) {
		return httpClient;
	}

	protected HttpServer customizeServerOptions(HttpServer httpServer) {
		return httpServer;
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
		               false,
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

			assertSendFile(out -> out.compression(true).sendFile(fromZipFile, 0, fileSize), true);
		}
	}

	private void assertSendFile(Function<HttpServerResponse, NettyOutbound> fn) {
		assertSendFile(fn, false);
	}

	private void assertSendFile(Function<HttpServerResponse, NettyOutbound> fn, boolean compression) {
		assertSendFile(fn, compression,
		               body ->
		                   assertThat(body).startsWith("This is an UTF-8 file that is larger than 1024 bytes. "
		                                               + "It contains accents like é.")
		                                   .contains("1024 mark here -><- 1024 mark here")
		                                   .endsWith("End of File"));
	}

	private void assertSendFile(Function<HttpServerResponse, NettyOutbound> fn, boolean compression, Consumer<String> bodyAssertion) {
		DisposableServer context =
				customizeServerOptions(HttpServer.create())
				          .handle((req, resp) -> fn.apply(resp))
				          .bindNow();

		HttpClient client;
		if (compression) {
			client = HttpClient.prepare()
			                   .addressSupplier(() -> context.address())
			                   .compress();
		}
		else {
			client = HttpClient.prepare()
			                   .addressSupplier(() -> context.address());
		}
		Mono<String> response =
				customizeClientOptions(client)
				          .get()
				          .uri("/foo")
				          .responseSingle((res, byteBufMono) -> byteBufMono.asString(StandardCharsets.UTF_8));

		String body = response.block(Duration.ofSeconds(5));

		context.disposeNow();

		bodyAssertion.accept(body);
	}

	@Test
	public void sendFileAsync4096() throws IOException, URISyntaxException {
		doTestSendFileAsync(4096);
	}

	@Test
	public void sendFileAsync1024() throws IOException, URISyntaxException {
		doTestSendFileAsync(1024);
	}

	protected void doTestSendFileAsync(int chunk) throws IOException, URISyntaxException {
		Path largeFile = Paths.get(getClass().getResource("/largeFile.txt").toURI());
		Path tempFile = Files.createTempFile(largeFile.getParent(),"temp", ".txt");
		tempFile.toFile().deleteOnExit();

		byte[] fileBytes = Files.readAllBytes(largeFile);
		for (int i = 0; i < 1000; i++) {
			Files.write(tempFile, fileBytes, StandardOpenOption.APPEND);
		}

		ByteBufAllocator allocator = ByteBufAllocator.DEFAULT;
		AsynchronousFileChannel channel =
				AsynchronousFileChannel.open(tempFile, StandardOpenOption.READ);

		Flux<ByteBuf> content = Flux.create(fluxSink -> {
			fluxSink.onDispose(() -> {
				try {
					if (channel != null) {
						channel.close();
					}
				}
				catch (IOException ignored) {
				}
			});

			ByteBuffer buf = ByteBuffer.allocate(chunk);
			channel.read(buf, 0, buf, new TestCompletionHandler(channel, fluxSink, allocator, chunk));
		});

		DisposableServer context =
				customizeServerOptions(HttpServer.create()
				                                 .tcpConfiguration(tcpServer -> tcpServer.host("localhost")))
				          .handle((req, resp) -> resp.sendByteArray(req.receive()
				                                                       .aggregate()
				                                                       .asByteArray()))
				          .bindNow();

		try {
			byte[] response = customizeClientOptions(HttpClient.prepare()
			                                                   .addressSupplier(() -> context.address())).request(
					HttpMethod.POST)
			                                                                                             .uri("/")
			                                                                                             .send(content)
			                                                                                             .responseContent()
			                                                                                             .aggregate()
			                                                                                             .asByteArray()
			                                                                                             .block();

			assertThat(response).isEqualTo(Files.readAllBytes(tempFile));
		}
		finally {
			context.dispose();
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
