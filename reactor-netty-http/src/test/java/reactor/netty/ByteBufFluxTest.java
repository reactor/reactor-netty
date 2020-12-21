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
package reactor.netty;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.Iterator;
import java.util.concurrent.atomic.AtomicLong;

import io.netty.buffer.ByteBuf;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import io.netty.handler.ssl.util.InsecureTrustManagerFactory;
import io.netty.handler.ssl.util.SelfSignedCertificate;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import reactor.netty.http.client.HttpClient;
import reactor.netty.http.server.HttpServer;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link ByteBufFlux}
 *
 * @author Silvano Riz
 */
class ByteBufFluxTest {

	private static File temporaryDirectory;

	@BeforeAll
	static void createTempDir() {
		temporaryDirectory = createTemporaryDirectory();
	}

	@AfterAll
	static void deleteTempDir() {
		deleteTemporaryDirectoryRecursively(temporaryDirectory);
	}

	@Test
	void testFromPath() throws Exception {

		// Create a temporary file with some binary data that will be read in chunks using the ByteBufFlux
		final int chunkSize = 3;
		final Path tmpFile = new File(temporaryDirectory, "content.in").toPath();
		final byte[] data = new byte[]{0x0, 0x1, 0x2, 0x3, 0x4, 0x5, 0x6, 0x7, 0x8, 0x9};
		Files.write(tmpFile, data);
		// Make sure the file is 10 bytes (i.e. the same as the data length)
		assertThat(data.length).isEqualTo(Files.size(tmpFile));

		// Use the ByteBufFlux to read the file in chunks of 3 bytes max and write them into a ByteArrayOutputStream for verification
		final Iterator<ByteBuf> it = ByteBufFlux.fromPath(tmpFile, chunkSize)
		                                        .toIterable()
		                                        .iterator();
		final ByteArrayOutputStream out = new ByteArrayOutputStream();
		while (it.hasNext()) {
			ByteBuf bb = it.next();
			byte[] read = new byte[bb.readableBytes()];
			bb.readBytes(read);
			bb.release();
			assertThat(bb.readableBytes()).isEqualTo(0);
			out.write(read);
		}

		// Verify that we read the file.
		assertThat(data).isEqualTo(out.toByteArray());
		System.out.println(Files.exists(tmpFile));

	}

	private static File createTemporaryDirectory() {
		try {
			final File tempDir = File.createTempFile("ByteBufFluxTest", "", null);
			assertThat(tempDir.delete()).isTrue();
			assertThat(tempDir.mkdir()).isTrue();
			return tempDir;
		} catch (Exception e) {
			throw new RuntimeException("Error creating the temporary directory", e);
		}
	}

	private static void deleteTemporaryDirectoryRecursively(final File file) {
		if (temporaryDirectory == null || !temporaryDirectory.exists()){
			return;
		}
		final File[] files = file.listFiles();
		if (files != null) {
			for (File childFile : files) {
				deleteTemporaryDirectoryRecursively(childFile);
			}
		}
		file.deleteOnExit();
	}

	@Test
	void testByteBufFluxFromPathWithoutSecurity() throws Exception {
		doTestByteBufFluxFromPath(false);
	}

	@Test
	void testByteBufFluxFromPathWithSecurity() throws Exception {
		doTestByteBufFluxFromPath(true);
	}

	private void doTestByteBufFluxFromPath(boolean withSecurity) throws Exception {
		final int serverPort = SocketUtils.findAvailableTcpPort();
		HttpServer server = HttpServer.create()
		                              .port(serverPort)
		                              .wiretap(true);
		HttpClient client = HttpClient.create()
		                              .port(serverPort)
		                              .wiretap(true);
		if (withSecurity) {
			SelfSignedCertificate ssc = new SelfSignedCertificate();
			SslContext sslServer = SslContextBuilder.forServer(ssc.certificate(), ssc.privateKey()).build();
			SslContext sslClient = SslContextBuilder.forClient()
			                                        .trustManager(InsecureTrustManagerFactory.INSTANCE).build();
			server = server.secure(ssl -> ssl.sslContext(sslServer));
			client = client.secure(ssl -> ssl.sslContext(sslClient));
		}

		Path path = Paths.get(getClass().getResource("/largeFile.txt").toURI());
		DisposableServer c = server.handle((req, res) ->
		                                      res.send(ByteBufFlux.fromPath(path))
		                                         .then())
		                           .bindNow();

		AtomicLong counter = new AtomicLong(0);
		client.get()
		      .uri("/download")
		      .responseContent()
		      .doOnNext(b -> counter.addAndGet(b.readableBytes()))
		      .blockLast(Duration.ofSeconds(30));

		assertThat(counter.get()).isEqualTo(1245);

		c.disposeNow();
	}
}