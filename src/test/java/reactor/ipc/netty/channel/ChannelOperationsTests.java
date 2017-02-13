package reactor.ipc.netty.channel;

import java.util.Arrays;

import io.netty.channel.Channel;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerAdapter;
import io.netty.channel.DefaultChannelPromise;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.LineBasedFrameDecoder;
import io.netty.handler.codec.base64.Base64Decoder;
import org.junit.Test;
import reactor.core.publisher.Mono;
import reactor.ipc.netty.NettyContext;
import reactor.ipc.netty.NettyPipeline;

import static org.junit.Assert.*;

/**
 * @author Simon Baslé
 */
public class ChannelOperationsTests {

	private NettyContext createMockNettyContext(Channel channel) {
		ServerContextHandler handler =
				(ServerContextHandler) Mono.<NettyContext>create(sink -> {
					NettyContext result =
							new ServerContextHandler((ch, contextHandler, objectMessage) -> null,
									null,
									sink,
									null,
									null);
					sink.success(result);
				}).block();
		handler.setFuture(new DefaultChannelPromise(channel));
		return handler;
	}

	@Test
	public void addByteDecoderWhenNoCodec() throws Exception {
		ChannelHandler decoder = new LineBasedFrameDecoder(12);
		Channel channel = new EmbeddedChannel();
		channel.pipeline()
		       .addLast("toto", new ChannelHandlerAdapter() { })
		       .addLast(NettyPipeline.ReactiveBridge, new ChannelHandlerAdapter() {});

		NettyContext nettyContext = createMockNettyContext(channel);
		ChannelOperations.addDecoder(nettyContext, channel, "decoder", decoder, name -> {});

		assertEquals(channel.pipeline().names(),
				Arrays.asList("toto", "decoder", NettyPipeline.ReactiveBridge, "DefaultChannelPipeline$TailContext#0"));
	}

	@Test
	public void addByteDecoderWhenCodec() throws Exception {
		ChannelHandler decoder = new LineBasedFrameDecoder(12);
		Channel channel = new EmbeddedChannel();
		channel.pipeline()
		       .addLast("toto", new ChannelHandlerAdapter() { })
		       .addLast("codec", new Base64Decoder())
		       .addLast(NettyPipeline.ReactiveBridge, new ChannelHandlerAdapter() {});

		NettyContext nettyContext = createMockNettyContext(channel);
		ChannelOperations.addDecoder(nettyContext, channel, "decoder", decoder, name -> {});

		assertEquals(channel.pipeline().names(), Arrays.asList(
				"toto", "codec",
				"decoder$extract", "decoder",
				NettyPipeline.ReactiveBridge, "DefaultChannelPipeline$TailContext#0"));
	}

	@Test
	public void nonByteDecoderIgnoredWhenCodec() throws Exception {
		ChannelHandler decoder = new ChannelHandlerAdapter() { };
		Channel channel = new EmbeddedChannel();
		channel.pipeline()
		       .addLast("toto", new ChannelHandlerAdapter() { })
		       .addLast("codec", new Base64Decoder())
		       .addLast(NettyPipeline.ReactiveBridge, new ChannelHandlerAdapter() {});

		NettyContext nettyContext = createMockNettyContext(channel);
		ChannelOperations.addDecoder(nettyContext, channel, "decoder", decoder, name -> {});

		assertEquals(channel.pipeline().names(), Arrays.asList("toto", "codec",
				NettyPipeline.ReactiveBridge, "DefaultChannelPipeline$TailContext#0"));
	}

	@Test
	public void nonByteDecoderAddedWhenNoCodec() throws Exception {
		ChannelHandler decoder = new ChannelHandlerAdapter() { };
		Channel channel = new EmbeddedChannel();
		channel.pipeline()
		       .addLast("toto", new ChannelHandlerAdapter() { })
		       .addLast(NettyPipeline.ReactiveBridge, new ChannelHandlerAdapter() {});

		NettyContext nettyContext = createMockNettyContext(channel);
		ChannelOperations.addDecoder(nettyContext, channel, "decoder", decoder, name -> {});

		assertEquals(channel.pipeline().names(), Arrays.asList("toto",
				"decoder",
				NettyPipeline.ReactiveBridge, "DefaultChannelPipeline$TailContext#0"));
	}

}
