package reactor.ipc.netty.channel;

import java.util.Arrays;
import java.util.concurrent.atomic.AtomicLong;

import io.netty.channel.Channel;
import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerAdapter;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.LineBasedFrameDecoder;
import io.netty.handler.codec.http.HttpRequestDecoder;
import io.netty.handler.codec.http.HttpResponseEncoder;
import io.netty.handler.codec.http.websocketx.Utf8FrameValidator;
import org.junit.Test;
import reactor.ipc.netty.NettyPipeline;

import static org.hamcrest.CoreMatchers.is;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThat;
import static reactor.ipc.netty.channel.NettyContextSupport.NO_HANDLER_REMOVE;
import static reactor.ipc.netty.channel.NettyContextSupport.NO_ONCLOSE;

/**
 * @author Simon Baslé
 */
public class NettyContextSupportTests {

	@Test
	public void addByteDecoderWhenNoLeft() throws Exception {
		Channel channel = new EmbeddedChannel();
		channel.pipeline()
		       .addLast(NettyPipeline.ReactiveBridge, new ChannelHandlerAdapter() {});
		ChannelHandler decoder = new LineBasedFrameDecoder(12);

		NettyContextSupport.addDecoderBeforeReactorEndHandlers(channel, "decoder", decoder, NO_ONCLOSE, NO_HANDLER_REMOVE);

		assertEquals(channel.pipeline().names(),
				Arrays.asList("decoder$extract", "decoder", NettyPipeline.ReactiveBridge, "DefaultChannelPipeline$TailContext#0"));
	}

	@Test
	public void addByteDecoderWhenNoRight() throws Exception {
		Channel channel = new EmbeddedChannel();
		channel.pipeline()
		       .addLast(NettyPipeline.HttpDecoder, new ChannelHandlerAdapter() {});
		ChannelHandler decoder = new LineBasedFrameDecoder(12);

		NettyContextSupport.addDecoderBeforeReactorEndHandlers(channel, "decoder", decoder, NO_ONCLOSE, NO_HANDLER_REMOVE);

		assertEquals(channel.pipeline().names(),
				Arrays.asList(NettyPipeline.HttpDecoder, "decoder$extract", "decoder", "DefaultChannelPipeline$TailContext#0"));
	}

	@Test
	public void addByteDecoderWhenEmptyPipeline() throws Exception {
		Channel channel = new EmbeddedChannel();
		ChannelHandler decoder = new LineBasedFrameDecoder(12);

		NettyContextSupport.addDecoderBeforeReactorEndHandlers(channel, "decoder", decoder, NO_ONCLOSE, NO_HANDLER_REMOVE);

		assertEquals(channel.pipeline().names(),
				Arrays.asList("decoder$extract", "decoder", "DefaultChannelPipeline$TailContext#0"));
	}

	@Test
	public void addByteDecoderWhenFullReactorPipeline() throws Exception {
		Channel channel = new EmbeddedChannel();
		channel.pipeline()
		       .addLast(NettyPipeline.HttpDecoder, new HttpRequestDecoder())
		       .addLast(NettyPipeline.HttpEncoder, new HttpResponseEncoder())
		       .addLast(NettyPipeline.HttpServerHandler, new ChannelDuplexHandler())
		       .addLast(NettyPipeline.ReactiveBridge, new ChannelHandlerAdapter() {});
		ChannelHandler decoder = new LineBasedFrameDecoder(12);

		NettyContextSupport.addDecoderBeforeReactorEndHandlers(channel, "decoder", decoder, NO_ONCLOSE, NO_HANDLER_REMOVE);

		assertEquals(channel.pipeline().names(), Arrays.asList(
				NettyPipeline.HttpDecoder, NettyPipeline.HttpEncoder, NettyPipeline.HttpServerHandler,
				"decoder$extract", "decoder",
				NettyPipeline.ReactiveBridge, "DefaultChannelPipeline$TailContext#0"));
	}



	@Test
	public void addNonByteDecoderWhenNoLeft() throws Exception {
		Channel channel = new EmbeddedChannel();
		channel.pipeline()
		       .addLast(NettyPipeline.ReactiveBridge, new ChannelHandlerAdapter() {});
		ChannelHandler decoder = new ChannelHandlerAdapter() { };

		NettyContextSupport.addDecoderBeforeReactorEndHandlers(channel, "decoder", decoder, NO_ONCLOSE, NO_HANDLER_REMOVE);

		assertEquals(channel.pipeline().names(),
				Arrays.asList("decoder", NettyPipeline.ReactiveBridge, "DefaultChannelPipeline$TailContext#0"));
	}

	@Test
	public void addNonByteDecoderWhenNoRight() throws Exception {
		Channel channel = new EmbeddedChannel();
		channel.pipeline()
		       .addLast(NettyPipeline.HttpDecoder, new ChannelHandlerAdapter() {});
		ChannelHandler decoder = new ChannelHandlerAdapter() { };

		NettyContextSupport.addDecoderBeforeReactorEndHandlers(channel, "decoder", decoder, NO_ONCLOSE, NO_HANDLER_REMOVE);

		assertEquals(channel.pipeline().names(),
				Arrays.asList(NettyPipeline.HttpDecoder, "decoder", "DefaultChannelPipeline$TailContext#0"));
	}

	@Test
	public void addNonByteDecoderWhenEmptyPipeline() throws Exception {
		Channel channel = new EmbeddedChannel();

		ChannelHandler decoder = new ChannelHandlerAdapter() { };

		NettyContextSupport.addDecoderBeforeReactorEndHandlers(channel, "decoder", decoder, NO_ONCLOSE, NO_HANDLER_REMOVE);

		assertEquals(channel.pipeline().names(),
				Arrays.asList("decoder", "DefaultChannelPipeline$TailContext#0"));
	}

	@Test
	public void addNonByteDecoderWhenFullReactorPipeline() throws Exception {
		Channel channel = new EmbeddedChannel();
		channel.pipeline()
		       .addLast(NettyPipeline.HttpDecoder, new HttpRequestDecoder())
		       .addLast(NettyPipeline.HttpEncoder, new HttpResponseEncoder())
		       .addLast(NettyPipeline.HttpServerHandler, new ChannelDuplexHandler())
		       .addLast(NettyPipeline.ReactiveBridge, new ChannelHandlerAdapter() {});
		ChannelHandler decoder = new ChannelHandlerAdapter() { };

		NettyContextSupport.addDecoderBeforeReactorEndHandlers(channel, "decoder", decoder, NO_ONCLOSE, NO_HANDLER_REMOVE);

		assertEquals(channel.pipeline().names(), Arrays.asList(
				NettyPipeline.HttpDecoder, NettyPipeline.HttpEncoder, NettyPipeline.HttpServerHandler,
				"decoder",
				NettyPipeline.ReactiveBridge, "DefaultChannelPipeline$TailContext#0"));
	}


	@Test
	public void addSeveralByteDecodersWhenCodec() throws Exception {
		ChannelHandler decoder1 = new LineBasedFrameDecoder(12);
		ChannelHandler decoder2 = new LineBasedFrameDecoder(13);
		Channel channel = new EmbeddedChannel();
		channel.pipeline()
		       .addLast(NettyPipeline.HttpDecoder, new HttpRequestDecoder())
		       .addLast(NettyPipeline.HttpEncoder, new HttpResponseEncoder())
		       .addLast(NettyPipeline.HttpServerHandler, new ChannelDuplexHandler())
		       .addLast(NettyPipeline.ReactiveBridge, new ChannelHandlerAdapter() {});

		NettyContextSupport.addDecoderBeforeReactorEndHandlers(channel, "decoder1", decoder1, NO_ONCLOSE, NO_HANDLER_REMOVE);
		NettyContextSupport.addDecoderBeforeReactorEndHandlers(channel, "decoder2", decoder2, NO_ONCLOSE, NO_HANDLER_REMOVE);

		assertEquals(channel.pipeline().names(), Arrays.asList(
				NettyPipeline.HttpDecoder, NettyPipeline.HttpEncoder, NettyPipeline.HttpServerHandler,
				"decoder1$extract", "decoder1",
				"decoder2$extract", "decoder2",
				NettyPipeline.ReactiveBridge, "DefaultChannelPipeline$TailContext#0"));
	}



	@Test
	public void addByteEncoderWhenNoLeft() throws Exception {
		Channel channel = new EmbeddedChannel();
		channel.pipeline()
		       .addLast(NettyPipeline.ReactiveBridge, new ChannelHandlerAdapter() {});
		ChannelHandler encoder = new LineBasedFrameDecoder(12);

		NettyContextSupport.addEncoderAfterReactorCodecs(channel, "encoder", encoder, NO_ONCLOSE, NO_HANDLER_REMOVE);

		assertEquals(channel.pipeline().names(),
				Arrays.asList("encoder$extract", "encoder", NettyPipeline.ReactiveBridge, "DefaultChannelPipeline$TailContext#0"));
	}

	@Test
	public void addByteEncoderWhenNoRight() throws Exception {
		Channel channel = new EmbeddedChannel();
		channel.pipeline()
		       .addLast(NettyPipeline.HttpDecoder, new ChannelHandlerAdapter() {});
		ChannelHandler encoder = new LineBasedFrameDecoder(12);

		NettyContextSupport.addEncoderAfterReactorCodecs(channel, "encoder", encoder, NO_ONCLOSE, NO_HANDLER_REMOVE);

		assertEquals(channel.pipeline().names(),
				Arrays.asList(NettyPipeline.HttpDecoder, "encoder$extract", "encoder", "DefaultChannelPipeline$TailContext#0"));
	}

	@Test
	public void addByteEncoderWhenEmptyPipeline() throws Exception {
		Channel channel = new EmbeddedChannel();
		ChannelHandler encoder = new LineBasedFrameDecoder(12);

		NettyContextSupport.addEncoderAfterReactorCodecs(channel, "encoder", encoder, NO_ONCLOSE, NO_HANDLER_REMOVE);

		assertEquals(channel.pipeline().names(),
				Arrays.asList("encoder$extract", "encoder", "DefaultChannelPipeline$TailContext#0"));
	}

	@Test
	public void addByteEncoderWhenFullReactorPipeline() throws Exception {
		Channel channel = new EmbeddedChannel();
		channel.pipeline()
		       .addLast(NettyPipeline.HttpDecoder, new HttpRequestDecoder())
		       .addLast(NettyPipeline.HttpEncoder, new HttpResponseEncoder())
		       .addLast(NettyPipeline.HttpServerHandler, new ChannelDuplexHandler())
		       .addLast(NettyPipeline.ReactiveBridge, new ChannelHandlerAdapter() {});
		ChannelHandler encoder = new LineBasedFrameDecoder(12);

		NettyContextSupport.addEncoderAfterReactorCodecs(channel, "encoder", encoder, NO_ONCLOSE, NO_HANDLER_REMOVE);

		assertEquals(channel.pipeline().names(), Arrays.asList(
				NettyPipeline.HttpDecoder, NettyPipeline.HttpEncoder, NettyPipeline.HttpServerHandler,
				"encoder$extract", "encoder",
				NettyPipeline.ReactiveBridge, "DefaultChannelPipeline$TailContext#0"));
	}



	@Test
	public void addNonByteEncoderWhenNoLeft() throws Exception {
		Channel channel = new EmbeddedChannel();
		channel.pipeline()
		       .addLast(NettyPipeline.ReactiveBridge, new ChannelHandlerAdapter() {});
		ChannelHandler encoder = new ChannelHandlerAdapter() { };

		NettyContextSupport.addEncoderAfterReactorCodecs(channel, "encoder", encoder, NO_ONCLOSE, NO_HANDLER_REMOVE);

		assertEquals(channel.pipeline().names(),
				Arrays.asList("encoder", NettyPipeline.ReactiveBridge, "DefaultChannelPipeline$TailContext#0"));
	}

	@Test
	public void addNonByteEncoderWhenNoRight() throws Exception {
		Channel channel = new EmbeddedChannel();
		channel.pipeline()
		       .addLast(NettyPipeline.HttpDecoder, new ChannelHandlerAdapter() {});
		ChannelHandler encoder = new ChannelHandlerAdapter() { };

		NettyContextSupport.addEncoderAfterReactorCodecs(channel, "encoder", encoder, NO_ONCLOSE, NO_HANDLER_REMOVE);

		assertEquals(channel.pipeline().names(),
				Arrays.asList(NettyPipeline.HttpDecoder, "encoder", "DefaultChannelPipeline$TailContext#0"));
	}

	@Test
	public void addNonByteEncoderWhenEmptyPipeline() throws Exception {
		Channel channel = new EmbeddedChannel();

		ChannelHandler encoder = new ChannelHandlerAdapter() { };

		NettyContextSupport.addEncoderAfterReactorCodecs(channel, "encoder", encoder, NO_ONCLOSE, NO_HANDLER_REMOVE);

		assertEquals(channel.pipeline().names(),
				Arrays.asList("encoder", "DefaultChannelPipeline$TailContext#0"));
	}

	@Test
	public void addNonByteEncoderWhenFullReactorPipeline() throws Exception {
		Channel channel = new EmbeddedChannel();
		channel.pipeline()
		       .addLast(NettyPipeline.HttpDecoder, new HttpRequestDecoder())
		       .addLast(NettyPipeline.HttpEncoder, new HttpResponseEncoder())
		       .addLast(NettyPipeline.HttpServerHandler, new ChannelDuplexHandler())
		       .addLast(NettyPipeline.ReactiveBridge, new ChannelHandlerAdapter() {});
		ChannelHandler encoder = new ChannelHandlerAdapter() { };

		NettyContextSupport.addEncoderAfterReactorCodecs(channel, "encoder", encoder, NO_ONCLOSE, NO_HANDLER_REMOVE);

		assertEquals(channel.pipeline().names(), Arrays.asList(
				NettyPipeline.HttpDecoder, NettyPipeline.HttpEncoder, NettyPipeline.HttpServerHandler,
				"encoder",
				NettyPipeline.ReactiveBridge, "DefaultChannelPipeline$TailContext#0"));
	}


	@Test
	public void addSeveralByteEncodersWhenCodec() throws Exception {
		ChannelHandler encoder1 = new LineBasedFrameDecoder(12);
		ChannelHandler encoder2 = new LineBasedFrameDecoder(13);
		Channel channel = new EmbeddedChannel();
		channel.pipeline()
		       .addLast(NettyPipeline.HttpDecoder, new HttpRequestDecoder())
		       .addLast(NettyPipeline.HttpEncoder, new HttpResponseEncoder())
		       .addLast(NettyPipeline.HttpServerHandler, new ChannelDuplexHandler())
		       .addLast(NettyPipeline.ReactiveBridge, new ChannelHandlerAdapter() {});

		NettyContextSupport.addEncoderAfterReactorCodecs(channel, "encoder1", encoder1, NO_ONCLOSE, NO_HANDLER_REMOVE);
		NettyContextSupport.addEncoderAfterReactorCodecs(channel, "encoder2", encoder2, NO_ONCLOSE, NO_HANDLER_REMOVE);

		assertEquals(channel.pipeline().names(), Arrays.asList(
				NettyPipeline.HttpDecoder, NettyPipeline.HttpEncoder, NettyPipeline.HttpServerHandler,
				"encoder2$extract", "encoder2",
				"encoder1$extract", "encoder1",
				NettyPipeline.ReactiveBridge, "DefaultChannelPipeline$TailContext#0"));
	}



	@Test
	public void encoderSupportSkipsOnCloseIfAttributeClosedChannel() {
		EmbeddedChannel channel = new EmbeddedChannel();
		channel.attr(ContextHandler.CLOSE_CHANNEL).set(true);

		AtomicLong closeCount = new AtomicLong();

		NettyContextSupport.addEncoderAfterReactorCodecs(channel, "byteencoder", new Utf8FrameValidator(),
				runnable -> closeCount.incrementAndGet(),
				NettyContextSupport.NO_HANDLER_REMOVE);
		NettyContextSupport.addEncoderAfterReactorCodecs(channel, "encoder", new ChannelHandlerAdapter() {},
				runnable -> closeCount.incrementAndGet(),
				NettyContextSupport.NO_HANDLER_REMOVE);

		assertThat(closeCount.intValue(), is(0));
	}

	@Test
	public void decoderSupportSkipsOnCloseIfAttributeClosedChannel() {
		EmbeddedChannel channel = new EmbeddedChannel();
		channel.attr(ContextHandler.CLOSE_CHANNEL).set(true);

		AtomicLong closeCount = new AtomicLong();

		NettyContextSupport.addDecoderBeforeReactorEndHandlers(channel, "byteDecoder", new Utf8FrameValidator(),
				runnable -> closeCount.incrementAndGet(),
				NettyContextSupport.NO_HANDLER_REMOVE);
		NettyContextSupport.addDecoderBeforeReactorEndHandlers(channel, "decoder", new ChannelHandlerAdapter() {},
				runnable -> closeCount.incrementAndGet(),
				NettyContextSupport.NO_HANDLER_REMOVE);

		assertThat(closeCount.intValue(), is(0));
	}

}
