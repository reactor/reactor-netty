package reactor.ipc.netty;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.net.URISyntaxException;
import java.nio.channels.Channels;
import java.nio.channels.WritableByteChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.NoSuchAlgorithmException;
import java.security.cert.CertificateException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import javax.net.ssl.SSLException;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.DefaultFileRegion;
import io.netty.channel.FileRegion;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.MessageToMessageEncoder;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import io.netty.handler.ssl.SslHandler;
import io.netty.handler.ssl.util.SelfSignedCertificate;
import io.netty.handler.stream.ChunkedFile;
import io.netty.handler.stream.ChunkedInput;
import io.netty.handler.stream.ChunkedWriteHandler;
import io.netty.util.CharsetUtil;
import io.netty.util.ReferenceCountUtil;
import org.junit.Test;
import reactor.core.Exceptions;
import reactor.ipc.netty.channel.data.FileChunkedStrategy;

import static org.assertj.core.api.Assertions.assertThat;

public class NettyOutboundTest {

	@Test
	public void onWriteIdleReplaces() throws Exception {
		EmbeddedChannel channel = new EmbeddedChannel();
		NettyContext mockContext = () -> channel;
		NettyOutbound outbound = () -> mockContext;

		AtomicLong idle1 = new AtomicLong();
		AtomicLong idle2 = new AtomicLong();

		outbound.onWriteIdle(100, idle1::incrementAndGet);
		outbound.onWriteIdle(150, idle2::incrementAndGet);
		ReactorNetty.OutboundIdleStateHandler idleStateHandler =
				(ReactorNetty.OutboundIdleStateHandler) channel.pipeline().get(NettyPipeline.OnChannelWriteIdle);
		idleStateHandler.onWriteIdle.run();

		assertThat(channel.pipeline().names()).containsExactly(
				NettyPipeline.OnChannelWriteIdle,
				"DefaultChannelPipeline$TailContext#0");

		assertThat(idle1.intValue()).isZero();
		assertThat(idle2.intValue()).isEqualTo(1);
	}

	@Test
	public void sendFileWithoutTlsUsesFileRegion() throws URISyntaxException,
	                                                      IOException {
		List<Class<?>> messageClasses = new ArrayList<>(2);

		EmbeddedChannel channel = new EmbeddedChannel(
				new MessageToMessageEncoder<FileRegion>() {

					@Override
					protected void encode(ChannelHandlerContext ctx, FileRegion msg,
							List<Object> out) throws Exception {
						ByteArrayOutputStream bais = new ByteArrayOutputStream();
						WritableByteChannel wbc = Channels.newChannel(bais);

						msg.transferTo(wbc, msg.position());
						out.add(new String(bais.toByteArray()));
					}
				},
				new MessageToMessageEncoder<Object>() {
					@Override
					protected void encode(ChannelHandlerContext ctx, Object msg,
							List<Object> out) throws Exception {
						messageClasses.add(msg.getClass());
						ReferenceCountUtil.retain(msg);
						out.add(msg);
					}
				});
		NettyContext mockContext = () -> channel;
		NettyOutbound outbound = new NettyOutbound() {
			@Override
			public NettyContext context() {
				return mockContext;
			}

			@Override
			public FileChunkedStrategy getFileChunkedStrategy() {
				return FILE_CHUNKED_STRATEGY_1024_NOPIPELINE;
			}
		};
		channel.writeOneOutbound(1);

		outbound.sendFile(Paths.get(getClass().getResource("/largeFile.txt").toURI()))
		        .then().block();

		assertThat(channel.inboundMessages()).isEmpty();
		assertThat(channel.outboundMessages()).hasSize(2);
		assertThat(messageClasses).containsExactly(Integer.class, DefaultFileRegion.class);

		assertThat(channel.outboundMessages())
				.element(1)
				.asString()
				.startsWith("This is an UTF-8 file that is larger than 1024 bytes.\nIt contains accents like é.\nGARBAGE")
				.endsWith("GARBAGE\nEnd of File");
	}

	@Test
	public void sendFileWithTlsUsesChunkedFile()
			throws URISyntaxException, NoSuchAlgorithmException, SSLException,
			       CertificateException {
		SelfSignedCertificate ssc = new SelfSignedCertificate();
		SslContext sslCtx = SslContextBuilder.forServer(ssc.certificate(), ssc.privateKey()).build();
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
							List<Object> out) throws Exception {
						clearMessages.add(msg.toString(CharsetUtil.UTF_8));
						out.add(msg.retain()); //the encoder will release the buffer, make sure it is retained for SslHandler
					}
				},
				//transform the ChunkedFile into ByteBuf chunks:
				new ChunkedWriteHandler(),
				//helps to ensure a ChunkedFile was written outs
				new MessageToMessageEncoder<Object>() {
					@Override
					protected void encode(ChannelHandlerContext ctx, Object msg, List<Object> out)
							throws Exception {
						messageWritten.add(msg.getClass());
						//passing the ChunkedFile through this method releases it, which is undesired
						ReferenceCountUtil.retain(msg);
						out.add(msg);
					}
				});

		NettyContext mockContext = () -> channel;
		NettyOutbound outbound = new NettyOutbound() {
			@Override
			public NettyContext context() {
				return mockContext;
			}

			@Override
			public FileChunkedStrategy getFileChunkedStrategy() {
				return FILE_CHUNKED_STRATEGY_1024_NOPIPELINE;
			}
		};
		channel.writeOneOutbound(1);

		try{
			outbound.sendFile(Paths.get(getClass().getResource("/largeFile.txt").toURI()))
			        .then()
			        .block(Duration.ofSeconds(1)); //TODO investigate why this hangs
		} catch (IllegalStateException e) {
			if (!"Timeout on blocking read for 1000 MILLISECONDS".equals(e.getMessage()))
				throw e;
			System.err.println(e);
		}

		assertThat(messageWritten).containsExactly(Integer.class, ChunkedFile.class);

		assertThat(clearMessages)
				.hasSize(2)
				.element(0)
				.asString()
				.startsWith("This is an UTF-8 file that is larger than 1024 bytes.\nIt contains accents like é.\nGARBAGE")
				.endsWith("1024 mark here ->");
		assertThat(clearMessages)
				.element(1)
				.asString()
				.startsWith("<- 1024 mark here")
				.endsWith("End of File");
	}

	@Test
	public void sendFileWithForceChunkedFileUsesStrategyChunks()
			throws URISyntaxException, NoSuchAlgorithmException, IOException {
		List<Class<?>> messageWritten = new ArrayList<>(2);
		EmbeddedChannel channel = new EmbeddedChannel(
				//outbound: pipeline reads inverted
				//transform the ByteBuf chunks into Strings:
				new MessageToMessageEncoder<ByteBuf>() {
					@Override
					protected void encode(ChannelHandlerContext ctx, ByteBuf msg,
							List<Object> out) throws Exception {
						out.add(msg.toString(CharsetUtil.UTF_8));
					}
				},
				//transform the ChunkedFile into ByteBuf chunks:
				new ChunkedWriteHandler(),
				//helps to ensure a ChunkedFile was written outs
				new MessageToMessageEncoder<Object>() {
					@Override
					protected void encode(ChannelHandlerContext ctx, Object msg, List<Object> out)
							throws Exception {
						messageWritten.add(msg.getClass());
						out.add(msg);
					}
				});
		NettyContext mockContext = () -> channel;
		NettyOutbound outbound = new NettyOutbound() {
			@Override
			public NettyContext context() {
				return mockContext;
			}

			@Override
			public FileChunkedStrategy getFileChunkedStrategy() {
				return FILE_CHUNKED_STRATEGY_1024_NOPIPELINE;
			}
		};
		Path path = Paths.get(getClass().getResource("/largeFile.txt").getFile());

		channel.writeOneOutbound(1);
		outbound.sendFileChunked(path, 0, Files.size(path))
		        .then().block();

		assertThat(channel.inboundMessages()).isEmpty();
		assertThat(messageWritten).containsExactly(Integer.class, ChunkedFile.class);

		assertThat(channel.outboundMessages())
				.hasSize(3)
				.element(1)
				.asString()
				.startsWith("This is an UTF-8 file that is larger than 1024 bytes.\nIt contains accents like é.\nGARBAGE")
				.endsWith("1024 mark here ->");

		assertThat(channel.outboundMessages())
				.last()
				.asString()
				.startsWith("<- 1024 mark here")
				.endsWith("End of File");
	}

	private static final FileChunkedStrategy FILE_CHUNKED_STRATEGY_1024_NOPIPELINE =
			new FileChunkedStrategy<ByteBuf>() {
				@Override
				public ChunkedInput<ByteBuf> chunkFile(RandomAccessFile file) {
					try {
						return new ChunkedFile(file, 1024);
					}
					catch (IOException e) {
						throw Exceptions.propagate(e);
					}
				}

				@Override
				public void preparePipeline(NettyContext context) {
					//NO-OP
				}

				@Override
				public void cleanupPipeline(NettyContext context) {
					//NO-OP
				}
			};
}