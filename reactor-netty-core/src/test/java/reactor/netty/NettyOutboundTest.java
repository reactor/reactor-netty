/*
 * Copyright (c) 2017-2025 VMware, Inc. or its affiliates, All Rights Reserved.
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
package reactor.netty;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.channels.Channels;
import java.nio.channels.WritableByteChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Predicate;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.DefaultFileRegion;
import io.netty.channel.FileRegion;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.MessageToMessageEncoder;
import io.netty.handler.codec.UnsupportedMessageTypeException;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import io.netty.handler.ssl.SslHandler;
import io.netty.handler.stream.ChunkedNioFile;
import io.netty.handler.stream.ChunkedWriteHandler;
import io.netty.pkitesting.CertificateBuilder;
import io.netty.pkitesting.X509Bundle;
import io.netty.util.CharsetUtil;
import io.netty.util.ReferenceCountUtil;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.reactivestreams.Publisher;
import reactor.core.publisher.Mono;

import static org.assertj.core.api.Assertions.assertThat;

class NettyOutboundTest {

	static X509Bundle ssc;

	@BeforeAll
	static void createSelfSignedCertificate() throws Exception {
		ssc = new CertificateBuilder().subject("CN=localhost").setIsCertificateAuthority(true).buildSelfSigned();
	}

	@Test
	void sendFileWithoutTlsUsesFileRegion() throws URISyntaxException {
		List<Class<?>> messageClasses = new ArrayList<>(2);

		EmbeddedChannel channel = new EmbeddedChannel(
				new MessageToMessageEncoder<FileRegion>() {

					@Override
					protected void encode(ChannelHandlerContext ctx, FileRegion msg,
							List<Object> out) throws Exception {
						ByteArrayOutputStream bais = new ByteArrayOutputStream();
						WritableByteChannel wbc = Channels.newChannel(bais);

						msg.transferTo(wbc, msg.position());
						out.add(bais.toString("UTF-8"));
					}
				},
				new MessageToMessageEncoder<Object>() {
					@Override
					protected void encode(ChannelHandlerContext ctx, Object msg,
							List<Object> out) {
						messageClasses.add(msg.getClass());
						ReferenceCountUtil.retain(msg);
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
			public NettyOutbound send(Publisher<? extends ByteBuf> dataStream, Predicate<ByteBuf> predicate) {
				return this;
			}

			@Override
			public ByteBufAllocator alloc() {
				return ByteBufAllocator.DEFAULT;
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
		ChannelFuture f = channel.writeOneOutbound(1);

		outbound.sendFile(Paths.get(getClass().getResource("/largeFile.txt").toURI()))
		        .then().block(Duration.ofSeconds(5));

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
	void sendFileWithTlsUsesChunkedFile() throws Exception {
		SslContext sslCtx = SslContextBuilder.forServer(ssc.toTempCertChainPem(), ssc.toTempPrivateKeyPem()).build();
		final SslHandler sslHandler = sslCtx.newHandler(ByteBufAllocator.DEFAULT);

		List<Class<?>> messageWritten = new ArrayList<>(2);
		List<Object> clearMessages = new ArrayList<>(2);

		EmbeddedChannel channel = new EmbeddedChannel(
				//outbound: pipeline reads inverted
				//bytes are encrypted
				sslHandler,
				//capture the chunks unencrypted, transform as Strings:
				new MessageToMessageEncoder<ByteBuf>() {
					@Override
					protected void encode(ChannelHandlerContext ctx, ByteBuf msg,
							List<Object> out) {
						clearMessages.add(msg.readCharSequence(msg.readableBytes(), CharsetUtil.UTF_8));
						out.add(msg.retain()); //the encoder will release the buffer, make sure it is retained for SslHandler
					}
				},
				//transform the ChunkedFile into ByteBuf chunks:
				new ChunkedWriteHandler(),
				//helps to ensure a ChunkedFile was written outs
				new MessageToMessageEncoder<Object>() {
					@Override
					protected void encode(ChannelHandlerContext ctx, Object msg, List<Object> out) {
						messageWritten.add(msg.getClass());
						//passing the ChunkedFile through this method releases it, which is undesired
						ReferenceCountUtil.retain(msg);
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
			public NettyOutbound send(Publisher<? extends ByteBuf> dataStream, Predicate<ByteBuf> predicate) {
				return this;
			}

			@Override
			public ByteBufAllocator alloc() {
				return ByteBufAllocator.DEFAULT;
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
		ChannelFuture f = channel.writeOneOutbound(1);

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
		assertThat(f.cause()).isNotNull()
				.isInstanceOf(UnsupportedMessageTypeException.class);
		assertThat(channel.finishAndReleaseAll()).isTrue();
	}

	@Test
	void sendFileWithForceChunkedFileUsesStrategyChunks()
			throws URISyntaxException, IOException {
		List<Class<?>> messageWritten = new ArrayList<>(2);
		EmbeddedChannel channel = new EmbeddedChannel(
				//outbound: pipeline reads inverted
				//transform the ByteBuf chunks into Strings:
				new MessageToMessageEncoder<ByteBuf>() {
					@Override
					protected void encode(ChannelHandlerContext ctx, ByteBuf msg,
							List<Object> out) {
						out.add(msg.readCharSequence(msg.readableBytes(), CharsetUtil.UTF_8));
					}
				},
				//transform the ChunkedFile into ByteBuf chunks:
				new ChunkedWriteHandler(),
				//helps to ensure a ChunkedFile was written outs
				new MessageToMessageEncoder<Object>() {
					@Override
					protected void encode(ChannelHandlerContext ctx, Object msg, List<Object> out) {
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
			public NettyOutbound send(Publisher<? extends ByteBuf> dataStream, Predicate<ByteBuf> predicate) {
				return this;
			}

			@Override
			public ByteBufAllocator alloc() {
				return ByteBufAllocator.DEFAULT;
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

		ChannelFuture f = channel.writeOneOutbound(1);
		outbound.sendFileChunked(path, 0, Files.size(path))
		        .then().block(Duration.ofSeconds(5));

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
				s -> FutureMono.from(c.channel()
				                      .writeAndFlush(mappedInput.apply(c, s))),
				sourceCleanup
		);
	}
}