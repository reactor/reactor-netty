/*
 * Copyright (c) 2017-2022 VMware, Inc. or its affiliates, All Rights Reserved.
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
package reactor.netty5;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.channels.Channels;
import java.nio.channels.WritableByteChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.cert.CertificateException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Predicate;
import javax.net.ssl.SSLException;

import io.netty5.buffer.Buffer;
import io.netty5.buffer.BufferAllocator;
import io.netty5.channel.ChannelHandlerContext;
import io.netty5.channel.DefaultFileRegion;
import io.netty5.channel.FileRegion;
import io.netty5.channel.embedded.EmbeddedChannel;
import io.netty5.handler.codec.MessageToMessageEncoder;
import io.netty5.handler.ssl.SslContext;
import io.netty5.handler.ssl.SslContextBuilder;
import io.netty5.handler.ssl.SslHandler;
import io.netty5.handler.ssl.util.SelfSignedCertificate;
import io.netty5.handler.stream.ChunkedNioFile;
import io.netty5.handler.stream.ChunkedWriteHandler;
import io.netty5.util.concurrent.Future;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.reactivestreams.Publisher;
import reactor.core.publisher.Mono;

import static io.netty5.buffer.DefaultBufferAllocators.preferredAllocator;
import static org.assertj.core.api.Assertions.assertThat;

class NettyOutboundTest {

	static SelfSignedCertificate ssc;

	@BeforeAll
	static void createSelfSignedCertificate() throws CertificateException {
		ssc = new SelfSignedCertificate();
	}

	@Test
	void sendFileWithoutTlsUsesFileRegion() throws URISyntaxException {
		List<Class<?>> messageClasses = new ArrayList<>(2);

		EmbeddedChannel channel = new EmbeddedChannel(
				new MessageToMessageEncoder<FileRegion>() {

					@Override
					protected void encodeAndClose(ChannelHandlerContext ctx, FileRegion msg,
							List<Object> out) throws Exception {
						ByteArrayOutputStream bais = new ByteArrayOutputStream();
						WritableByteChannel wbc = Channels.newChannel(bais);

						msg.transferTo(wbc, msg.position());
						out.add(bais.toString(StandardCharsets.UTF_8));
					}
				},
				new MessageToMessageEncoder<>() {
					@Override
					protected void encodeAndClose(ChannelHandlerContext ctx, Object msg,
							List<Object> out) {
						messageClasses.add(msg.getClass());
						out.add(msg);
					}
				});
		Connection mockContext = () -> channel;
		NettyOutbound outbound = new NettyOutbound() {
			@Override
			public NettyOutbound sendObject(Publisher<?> dataStream, Predicate<Object> predicate) {
				return this;
			}

			@Override
			public NettyOutbound sendObject(Object message) {
				return this;
			}

			@Override
			public NettyOutbound send(Publisher<? extends Buffer> dataStream, Predicate<Buffer> predicate) {
				return this;
			}

			@Override
			public BufferAllocator alloc() {
				return preferredAllocator();
			}

			@Override
			public <S> NettyOutbound sendUsing(Callable<? extends S> sourceInput,
					BiFunction<? super Connection, ? super S, ?> mappedInput,
					Consumer<? super S> sourceCleanup) {
				return then(mockSendUsing(mockContext, sourceInput, mappedInput, sourceCleanup));
			}

			@Override
			public NettyOutbound withConnection(Consumer<? super Connection> withConnection) {
				withConnection.accept(mockContext);
				return this;
			}
		};
		Future<Void> f = channel.writeOneOutbound(1);

		outbound.sendFile(Paths.get(getClass().getResource("/largeFile.txt").toURI()))
		        .then().block();

		assertThat(channel.inboundMessages()).isEmpty();
		assertThat(channel.outboundMessages()).hasSize(2);
		assertThat(messageClasses).containsExactly(Integer.class, DefaultFileRegion.class);

		assertThat(channel.outboundMessages())
				.element(1)
				.asString()
				.startsWith("This is an UTF-8 file that is larger than 1024 bytes. It contains accents like é. GARBAGE")
				.endsWith("GARBAGE End of File");

		assertThat(f.isSuccess()).isTrue();
		assertThat(channel.finishAndReleaseAll()).isTrue();
	}

	@Test
	void sendFileWithTlsUsesChunkedFile() throws URISyntaxException, SSLException {
		SslContext sslCtx = SslContextBuilder.forServer(ssc.certificate(), ssc.privateKey()).build();
		final SslHandler sslHandler = sslCtx.newHandler(preferredAllocator());

		List<Class<?>> messageWritten = new ArrayList<>(2);
		List<Object> clearMessages = new ArrayList<>(2);

		EmbeddedChannel channel = new EmbeddedChannel(
				//outbound: pipeline reads inverted
				//bytes are encrypted
				sslHandler,
				//capture the chunks unencrypted, transform as Strings:
				new MessageToMessageEncoder<Buffer>() {
					@Override
					protected void encodeAndClose(ChannelHandlerContext ctx, Buffer msg,
							List<Object> out) {
						clearMessages.add(msg.readCharSequence(msg.readableBytes(), StandardCharsets.UTF_8));
						out.add(msg.split());
						msg.close();
					}
				},
				//transform the ChunkedFile into Buffer chunks:
				new ChunkedWriteHandler(),
				//helps to ensure a ChunkedFile was written outs
				new MessageToMessageEncoder<>() {
					@Override
					protected void encodeAndClose(ChannelHandlerContext ctx, Object msg, List<Object> out) {
						messageWritten.add(msg.getClass());
						out.add(msg);
					}
				});

		Connection mockContext = () -> channel;
		NettyOutbound outbound = new NettyOutbound() {
			@Override
			public NettyOutbound sendObject(Publisher<?> dataStream, Predicate<Object> predicate) {
				return this;
			}

			@Override
			public NettyOutbound sendObject(Object message) {
				return this;
			}

			@Override
			public NettyOutbound send(Publisher<? extends Buffer> dataStream, Predicate<Buffer> predicate) {
				return this;
			}

			@Override
			public BufferAllocator alloc() {
				return preferredAllocator();
			}

			@Override
			public <S> NettyOutbound sendUsing(Callable<? extends S> sourceInput,
					BiFunction<? super Connection, ? super S, ?> mappedInput,
					Consumer<? super S> sourceCleanup) {
				return then(mockSendUsing(mockContext, sourceInput, mappedInput, sourceCleanup));
			}

			@Override
			public NettyOutbound withConnection(Consumer<? super Connection> withConnection) {
				withConnection.accept(mockContext);
				return this;
			}
		};
		Future<Void> f = channel.writeOneOutbound(1);

		try {
			outbound.sendFile(Paths.get(getClass().getResource("/largeFile.txt").toURI()))
			        .then()
			        .block(Duration.ofSeconds(1)); //TODO investigate why this hangs
		}
		catch (IllegalStateException e) {
			if (!"Timeout on blocking read for 1000 MILLISECONDS".equals(e.getMessage())) {
				throw e;
			}
			e.printStackTrace();
		}

		assertThat(messageWritten).containsExactly(Integer.class, ChunkedNioFile.class);

		assertThat(clearMessages)
				.hasSize(2)
				.element(0)
				.asString()
				.startsWith("This is an UTF-8 file that is larger than 1024 bytes. It contains accents like é. GARBAGE")
				.endsWith("1024 mark here ->");
		assertThat(clearMessages)
				.element(1)
				.asString()
				.startsWith("<- 1024 mark here")
				.endsWith("End of File");

		assertThat(f.isSuccess()).isFalse();
		assertThat(channel.finishAndReleaseAll()).isTrue();
	}

	@Test
	void sendFileWithForceChunkedFileUsesStrategyChunks()
			throws URISyntaxException, IOException {
		List<Class<?>> messageWritten = new ArrayList<>(2);
		EmbeddedChannel channel = new EmbeddedChannel(
				//outbound: pipeline reads inverted
				//transform the Buffer chunks into Strings:
				new MessageToMessageEncoder<Buffer>() {
					@Override
					protected void encodeAndClose(ChannelHandlerContext ctx, Buffer msg,
							List<Object> out) {
						out.add(msg.readCharSequence(msg.readableBytes(), StandardCharsets.UTF_8));
						msg.close();
					}
				},
				//transform the ChunkedFile into Buffer chunks:
				new ChunkedWriteHandler(),
				//helps to ensure a ChunkedFile was written outs
				new MessageToMessageEncoder<>() {
					@Override
					protected void encodeAndClose(ChannelHandlerContext ctx, Object msg, List<Object> out) {
						messageWritten.add(msg.getClass());
						out.add(msg);
					}
				});
		Connection mockContext = () -> channel;
		NettyOutbound outbound = new NettyOutbound() {
			@Override
			public NettyOutbound sendObject(Publisher<?> dataStream, Predicate<Object> predicate) {
				return this;
			}

			@Override
			public NettyOutbound sendObject(Object message) {
				return this;
			}

			@Override
			public NettyOutbound send(Publisher<? extends Buffer> dataStream, Predicate<Buffer> predicate) {
				return this;
			}

			@Override
			public BufferAllocator alloc() {
				return preferredAllocator();
			}

			@Override
			public <S> NettyOutbound sendUsing(Callable<? extends S> sourceInput,
					BiFunction<? super Connection, ? super S, ?> mappedInput,
					Consumer<? super S> sourceCleanup) {
				return then(mockSendUsing(mockContext, sourceInput, mappedInput, sourceCleanup));
			}

			@Override
			public NettyOutbound withConnection(Consumer<? super Connection> withConnection) {
				withConnection.accept(mockContext);
				return this;
			}
		};
		Path path = Paths.get(getClass().getResource("/largeFile.txt").toURI());

		Future<Void> f = channel.writeOneOutbound(1);
		outbound.sendFileChunked(path, 0, Files.size(path))
		        .then().block();

		assertThat(channel.inboundMessages()).isEmpty();
		assertThat(messageWritten).containsExactly(Integer.class, ChunkedNioFile.class);

		assertThat(channel.outboundMessages())
				.hasSize(3)
				.element(1)
				.asString()
				.startsWith("This is an UTF-8 file that is larger than 1024 bytes. It contains accents like é. GARBAGE")
				.endsWith("1024 mark here ->");

		assertThat(channel.outboundMessages())
				.last()
				.asString()
				.startsWith("<- 1024 mark here")
				.endsWith("End of File");

		assertThat(f.isSuccess()).isTrue();
		assertThat(channel.finishAndReleaseAll()).isTrue();
	}

	static <S> Mono<Void> mockSendUsing(Connection c, Callable<? extends S> sourceInput,
			BiFunction<? super Connection, ? super S, ?> mappedInput,
			Consumer<? super S> sourceCleanup) {
		return Mono.using(
				sourceInput,
				s -> Mono.fromCompletionStage(c.channel()
				                               .writeAndFlush(mappedInput.apply(c, s)).asStage()),
				sourceCleanup
		);
	}
}
