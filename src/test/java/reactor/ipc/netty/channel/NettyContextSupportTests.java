/*
 * Copyright (c) 2011-2017 Pivotal Software Inc, All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package reactor.ipc.netty.channel;

import java.util.Arrays;
import java.util.concurrent.atomic.AtomicLong;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerAdapter;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.LineBasedFrameDecoder;
import io.netty.handler.codec.http.DefaultHttpContent;
import io.netty.handler.codec.http.DefaultHttpResponse;
import io.netty.handler.codec.http.DefaultLastHttpContent;
import io.netty.handler.codec.http.HttpContent;
import io.netty.handler.codec.http.HttpRequestDecoder;
import io.netty.handler.codec.http.HttpResponse;
import io.netty.handler.codec.http.HttpResponseEncoder;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.codec.http.LastHttpContent;
import io.netty.handler.codec.http.websocketx.Utf8FrameValidator;
import io.netty.handler.codec.json.JsonObjectDecoder;
import io.netty.util.CharsetUtil;
import org.junit.Test;
import reactor.ipc.netty.NettyContext;
import reactor.ipc.netty.NettyPipeline;

import static org.hamcrest.CoreMatchers.*;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThat;
import static reactor.ipc.netty.channel.NettyContextSupport.ADD_EXTRACTOR;
import static reactor.ipc.netty.channel.NettyContextSupport.HTTP_EXTRACTOR;

/**
 * @author Simon Baslé
 */
public class NettyContextSupportTests {

	@Test
	public void addByteDecoderWhenNoLeft() throws Exception {
		Channel channel = new EmbeddedChannel();
		NettyContext c = () -> channel;
		channel.pipeline()
		       .addLast(NettyPipeline.ReactiveBridge, new ChannelHandlerAdapter() {});
		ChannelHandler decoder = new LineBasedFrameDecoder(12);

		NettyContextSupport.addDecoderBeforeReactorEndHandlers(c,
				"decoder",
				decoder,
				ADD_EXTRACTOR);

		assertEquals(channel.pipeline().names(),
				Arrays.asList("decoder$extract", "decoder", NettyPipeline.ReactiveBridge, "DefaultChannelPipeline$TailContext#0"));
	}

	@Test
	public void addByteDecoderWhenNoRight() throws Exception {
		Channel channel = new EmbeddedChannel();
		NettyContext c = () -> channel;
		channel.pipeline()
		       .addLast(NettyPipeline.HttpDecoder, new ChannelHandlerAdapter() {});
		ChannelHandler decoder = new LineBasedFrameDecoder(12);

		NettyContextSupport.addDecoderBeforeReactorEndHandlers(c,
				"decoder",
				decoder,
				ADD_EXTRACTOR);

		assertEquals(channel.pipeline().names(),
				Arrays.asList(NettyPipeline.HttpDecoder, "decoder$extract", "decoder", "DefaultChannelPipeline$TailContext#0"));
	}

	@Test
	public void addByteDecoderWhenEmptyPipeline() throws Exception {
		Channel channel = new EmbeddedChannel();
		NettyContext c = () -> channel;
		ChannelHandler decoder = new LineBasedFrameDecoder(12);

		NettyContextSupport.addDecoderBeforeReactorEndHandlers(c,
				"decoder",
				decoder,
				ADD_EXTRACTOR);

		assertEquals(channel.pipeline().names(),
				Arrays.asList("decoder$extract", "decoder", "DefaultChannelPipeline$TailContext#0"));
	}

	@Test
	public void addByteDecoderWhenFullReactorPipeline() throws Exception {
		Channel channel = new EmbeddedChannel();
		NettyContext c = () -> channel;
		channel.pipeline()
		       .addLast(NettyPipeline.HttpDecoder, new HttpRequestDecoder())
		       .addLast(NettyPipeline.HttpEncoder, new HttpResponseEncoder())
		       .addLast(NettyPipeline.HttpServerHandler, new ChannelDuplexHandler())
		       .addLast(NettyPipeline.ReactiveBridge, new ChannelHandlerAdapter() {});
		ChannelHandler decoder = new LineBasedFrameDecoder(12);

		NettyContextSupport.addDecoderBeforeReactorEndHandlers(c,
				"decoder",
				decoder,
				ADD_EXTRACTOR);

		assertEquals(channel.pipeline().names(), Arrays.asList(
				NettyPipeline.HttpDecoder, NettyPipeline.HttpEncoder, NettyPipeline.HttpServerHandler,
				"decoder$extract", "decoder",
				NettyPipeline.ReactiveBridge, "DefaultChannelPipeline$TailContext#0"));
	}



	@Test
	public void addNonByteDecoderWhenNoLeft() throws Exception {
		Channel channel = new EmbeddedChannel();
		NettyContext c = () -> channel;
		channel.pipeline()
		       .addLast(NettyPipeline.ReactiveBridge, new ChannelHandlerAdapter() {});
		ChannelHandler decoder = new ChannelHandlerAdapter() { };

		NettyContextSupport.addDecoderBeforeReactorEndHandlers(c,
				"decoder",
				decoder,
				ADD_EXTRACTOR);

		assertEquals(channel.pipeline().names(),
				Arrays.asList("decoder", NettyPipeline.ReactiveBridge, "DefaultChannelPipeline$TailContext#0"));
	}

	@Test
	public void addNonByteDecoderWhenNoRight() throws Exception {
		Channel channel = new EmbeddedChannel();
		NettyContext c = () -> channel;
		channel.pipeline()
		       .addLast(NettyPipeline.HttpDecoder, new ChannelHandlerAdapter() {});
		ChannelHandler decoder = new ChannelHandlerAdapter() { };

		NettyContextSupport.addDecoderBeforeReactorEndHandlers(c,
				"decoder",
				decoder,
				ADD_EXTRACTOR);

		assertEquals(channel.pipeline().names(),
				Arrays.asList(NettyPipeline.HttpDecoder, "decoder", "DefaultChannelPipeline$TailContext#0"));
	}

	@Test
	public void addNonByteDecoderWhenEmptyPipeline() throws Exception {
		Channel channel = new EmbeddedChannel();
		NettyContext c = () -> channel;

		ChannelHandler decoder = new ChannelHandlerAdapter() { };

		NettyContextSupport.addDecoderBeforeReactorEndHandlers(c,
				"decoder",
				decoder,
				ADD_EXTRACTOR);

		assertEquals(channel.pipeline().names(),
				Arrays.asList("decoder", "DefaultChannelPipeline$TailContext#0"));
	}

	@Test
	public void addNonByteDecoderWhenFullReactorPipeline() throws Exception {
		Channel channel = new EmbeddedChannel();
		NettyContext c = () -> channel;
		channel.pipeline()
		       .addLast(NettyPipeline.HttpDecoder, new HttpRequestDecoder())
		       .addLast(NettyPipeline.HttpEncoder, new HttpResponseEncoder())
		       .addLast(NettyPipeline.HttpServerHandler, new ChannelDuplexHandler())
		       .addLast(NettyPipeline.ReactiveBridge, new ChannelHandlerAdapter() {});
		ChannelHandler decoder = new ChannelHandlerAdapter() { };

		NettyContextSupport.addDecoderBeforeReactorEndHandlers(c,
				"decoder",
				decoder,
				ADD_EXTRACTOR);

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
		NettyContext c = () -> channel;
		channel.pipeline()
		       .addLast(NettyPipeline.HttpDecoder, new HttpRequestDecoder())
		       .addLast(NettyPipeline.HttpEncoder, new HttpResponseEncoder())
		       .addLast(NettyPipeline.HttpServerHandler, new ChannelDuplexHandler())
		       .addLast(NettyPipeline.ReactiveBridge, new ChannelHandlerAdapter() {});

		NettyContextSupport.addDecoderBeforeReactorEndHandlers(c,
				"decoder1",
				decoder1,
				ADD_EXTRACTOR);
		NettyContextSupport.addDecoderBeforeReactorEndHandlers(c,
				"decoder2",
				decoder2,
				ADD_EXTRACTOR);

		assertEquals(channel.pipeline().names(), Arrays.asList(
				NettyPipeline.HttpDecoder, NettyPipeline.HttpEncoder, NettyPipeline.HttpServerHandler,
				"decoder1$extract", "decoder1",
				"decoder2$extract", "decoder2",
				NettyPipeline.ReactiveBridge, "DefaultChannelPipeline$TailContext#0"));
	}



	@Test
	public void addByteEncoderWhenNoLeft() throws Exception {
		Channel channel = new EmbeddedChannel();
		NettyContext c = () -> channel;
		channel.pipeline()
		       .addLast(NettyPipeline.ReactiveBridge, new ChannelHandlerAdapter() {});
		ChannelHandler encoder = new LineBasedFrameDecoder(12);

		NettyContextSupport.addEncoderAfterReactorCodecs(c,
				"encoder",
				encoder,
				ADD_EXTRACTOR);

		assertEquals(channel.pipeline().names(),
				Arrays.asList("encoder$extract", "encoder", NettyPipeline.ReactiveBridge, "DefaultChannelPipeline$TailContext#0"));
	}

	@Test
	public void addByteEncoderWhenNoRight() throws Exception {
		Channel channel = new EmbeddedChannel();
		NettyContext c = () -> channel;
		channel.pipeline()
		       .addLast(NettyPipeline.HttpDecoder, new ChannelHandlerAdapter() {});
		ChannelHandler encoder = new LineBasedFrameDecoder(12);

		NettyContextSupport.addEncoderAfterReactorCodecs(c,
				"encoder",
				encoder,
				ADD_EXTRACTOR);

		assertEquals(channel.pipeline().names(),
				Arrays.asList(NettyPipeline.HttpDecoder, "encoder$extract", "encoder", "DefaultChannelPipeline$TailContext#0"));
	}

	@Test
	public void addByteEncoderWhenEmptyPipeline() throws Exception {
		Channel channel = new EmbeddedChannel();
		NettyContext c = () -> channel;
		ChannelHandler encoder = new LineBasedFrameDecoder(12);

		NettyContextSupport.addEncoderAfterReactorCodecs(c,
				"encoder",
				encoder,
				ADD_EXTRACTOR);

		assertEquals(channel.pipeline().names(),
				Arrays.asList("encoder$extract", "encoder", "DefaultChannelPipeline$TailContext#0"));
	}

	@Test
	public void addByteEncoderWhenFullReactorPipeline() throws Exception {
		Channel channel = new EmbeddedChannel();
		NettyContext c = () -> channel;
		channel.pipeline()
		       .addLast(NettyPipeline.HttpDecoder, new HttpRequestDecoder())
		       .addLast(NettyPipeline.HttpEncoder, new HttpResponseEncoder())
		       .addLast(NettyPipeline.HttpServerHandler, new ChannelDuplexHandler())
		       .addLast(NettyPipeline.ReactiveBridge, new ChannelHandlerAdapter() {});
		ChannelHandler encoder = new LineBasedFrameDecoder(12);

		NettyContextSupport.addEncoderAfterReactorCodecs(c,
				"encoder",
				encoder,
				ADD_EXTRACTOR);

		assertEquals(channel.pipeline().names(), Arrays.asList(
				NettyPipeline.HttpDecoder, NettyPipeline.HttpEncoder, NettyPipeline.HttpServerHandler,
				"encoder$extract", "encoder",
				NettyPipeline.ReactiveBridge, "DefaultChannelPipeline$TailContext#0"));
	}



	@Test
	public void addNonByteEncoderWhenNoLeft() throws Exception {
		Channel channel = new EmbeddedChannel();
		NettyContext c = () -> channel;
		channel.pipeline()
		       .addLast(NettyPipeline.ReactiveBridge, new ChannelHandlerAdapter() {});
		ChannelHandler encoder = new ChannelHandlerAdapter() { };

		NettyContextSupport.addEncoderAfterReactorCodecs(c,
				"encoder",
				encoder,
				ADD_EXTRACTOR);

		assertEquals(channel.pipeline().names(),
				Arrays.asList("encoder", NettyPipeline.ReactiveBridge, "DefaultChannelPipeline$TailContext#0"));
	}

	@Test
	public void addNonByteEncoderWhenNoRight() throws Exception {
		Channel channel = new EmbeddedChannel();
		NettyContext c = () -> channel;
		channel.pipeline()
		       .addLast(NettyPipeline.HttpDecoder, new ChannelHandlerAdapter() {});
		ChannelHandler encoder = new ChannelHandlerAdapter() { };

		NettyContextSupport.addEncoderAfterReactorCodecs(c,
				"encoder",
				encoder,
				ADD_EXTRACTOR);

		assertEquals(channel.pipeline().names(),
				Arrays.asList(NettyPipeline.HttpDecoder, "encoder", "DefaultChannelPipeline$TailContext#0"));
	}

	@Test
	public void addNonByteEncoderWhenEmptyPipeline() throws Exception {
		Channel channel = new EmbeddedChannel();
		NettyContext c = () -> channel;

		ChannelHandler encoder = new ChannelHandlerAdapter() { };

		NettyContextSupport.addEncoderAfterReactorCodecs(c,
				"encoder",
				encoder,
				ADD_EXTRACTOR);

		assertEquals(channel.pipeline().names(),
				Arrays.asList("encoder", "DefaultChannelPipeline$TailContext#0"));
	}

	@Test
	public void addNonByteEncoderWhenFullReactorPipeline() throws Exception {
		Channel channel = new EmbeddedChannel();
		NettyContext c = () -> channel;
		channel.pipeline()
		       .addLast(NettyPipeline.HttpDecoder, new HttpRequestDecoder())
		       .addLast(NettyPipeline.HttpEncoder, new HttpResponseEncoder())
		       .addLast(NettyPipeline.HttpServerHandler, new ChannelDuplexHandler())
		       .addLast(NettyPipeline.ReactiveBridge, new ChannelHandlerAdapter() {});
		ChannelHandler encoder = new ChannelHandlerAdapter() { };

		NettyContextSupport.addEncoderAfterReactorCodecs(c,
				"encoder",
				encoder,
				ADD_EXTRACTOR);

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
		NettyContext c = () -> channel;
		channel.pipeline()
		       .addLast(NettyPipeline.HttpDecoder, new HttpRequestDecoder())
		       .addLast(NettyPipeline.HttpEncoder, new HttpResponseEncoder())
		       .addLast(NettyPipeline.HttpServerHandler, new ChannelDuplexHandler())
		       .addLast(NettyPipeline.ReactiveBridge, new ChannelHandlerAdapter() {});

		NettyContextSupport.addEncoderAfterReactorCodecs(c,
				"encoder1",
				encoder1,
				ADD_EXTRACTOR);
		NettyContextSupport.addEncoderAfterReactorCodecs(c,
				"encoder2",
				encoder2,
				ADD_EXTRACTOR);

		assertEquals(channel.pipeline().names(), Arrays.asList(
				NettyPipeline.HttpDecoder, NettyPipeline.HttpEncoder, NettyPipeline.HttpServerHandler,
				"encoder2$extract", "encoder2",
				"encoder1$extract", "encoder1",
				NettyPipeline.ReactiveBridge, "DefaultChannelPipeline$TailContext#0"));
	}



	@Test
	public void encoderSupportSkipsOnCloseIfAttributeClosedChannel() {
		EmbeddedChannel channel = new EmbeddedChannel();
		channel.attr(ContextHandler.CLOSE_CHANNEL)
		       .set(true);

		AtomicLong closeCount = new AtomicLong();
		NettyContext c = new NettyContext() {
			@Override
			public Channel channel() {
				return channel;
			}

			@Override
			public NettyContext onClose(Runnable onClose) {
				closeCount.incrementAndGet();
				return this;
			}

			@Override
			public NettyContext removeHandler(String name) {
				return this;
			}
		};


		NettyContextSupport.addEncoderAfterReactorCodecs(c,
				"byteencoder",
				new Utf8FrameValidator(),
				ADD_EXTRACTOR);
		NettyContextSupport.addEncoderAfterReactorCodecs(c,
				"encoder",
				new ChannelHandlerAdapter() {
				},
				ADD_EXTRACTOR);

		assertThat(closeCount.intValue(), is(0));
	}

	@Test
	public void decoderSupportSkipsOnCloseIfAttributeClosedChannel() {
		EmbeddedChannel channel = new EmbeddedChannel();
		channel.attr(ContextHandler.CLOSE_CHANNEL)
		       .set(true);

		AtomicLong closeCount = new AtomicLong();
		NettyContext c = new NettyContext() {
			@Override
			public Channel channel() {
				return channel;
			}

			@Override
			public NettyContext onClose(Runnable onClose) {
				closeCount.incrementAndGet();
				return this;
			}

			@Override
			public NettyContext removeHandler(String name) {
				return this;
			}
		};

		NettyContextSupport.addDecoderBeforeReactorEndHandlers(c,
				"byteDecoder",
				new Utf8FrameValidator(),
				ADD_EXTRACTOR);
		NettyContextSupport.addDecoderBeforeReactorEndHandlers(c,
				"decoder",
				new ChannelHandlerAdapter() {
				},
				ADD_EXTRACTOR);

		assertThat(closeCount.intValue(), is(0));
	}

	@Test
	public void addDecoderSkipsIfExist() {
		EmbeddedChannel channel = new EmbeddedChannel();
		NettyContext c = () -> channel;
		channel.pipeline()
		       .addFirst("foo", new Utf8FrameValidator());

		NettyContextSupport.addDecoderBeforeReactorEndHandlers(c,
				"foo",
				new LineBasedFrameDecoder(10),
				ADD_EXTRACTOR);

		assertEquals(channel.pipeline().names(), Arrays.asList("foo", "DefaultChannelPipeline$TailContext#0"));
		assertThat(channel.pipeline().get("foo"), is(instanceOf(Utf8FrameValidator.class)));
	}

	@Test
	public void addEncoderSkipsIfExist() {
		EmbeddedChannel channel = new EmbeddedChannel();
		NettyContext c = () -> channel;
		channel.pipeline()
		       .addFirst("foo", new Utf8FrameValidator());

		NettyContextSupport.addEncoderAfterReactorCodecs(c,
				"foo",
				new LineBasedFrameDecoder(10),
				ADD_EXTRACTOR);

		assertEquals(channel.pipeline().names(), Arrays.asList("foo", "DefaultChannelPipeline$TailContext#0"));
		assertThat(channel.pipeline().get("foo"), is(instanceOf(Utf8FrameValidator.class)));
	}

	@Test
	public void httpAndJsonDecoders() {
		EmbeddedChannel channel = new EmbeddedChannel();
		NettyContext c = () -> channel;

		NettyContextSupport.addDecoderBeforeReactorEndHandlers(c,
				"foo",
				new JsonObjectDecoder(true),
				HTTP_EXTRACTOR);

		String json1 = "[{\"some\": 1} , {\"valu";
		String json2 = "e\": true, \"test\": 1}]";

		Object[] content = new Object[3];
		content[0] = new DefaultHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK);
		content[1] = new DefaultHttpContent(Unpooled.copiedBuffer(json1, CharsetUtil.UTF_8));
		content[2] = new DefaultLastHttpContent(Unpooled.copiedBuffer(json2, CharsetUtil.UTF_8));

		channel.writeInbound(content);

		Object t = channel.readInbound();
		assertThat(t, instanceOf(HttpResponse.class));
		assertThat(t, not(instanceOf(HttpContent.class)));

		t = channel.readInbound();
		assertThat(t, instanceOf(ByteBuf.class));
		assertThat(((ByteBuf) t).toString(CharsetUtil.UTF_8), is("{\"some\": 1}"));

		t = channel.readInbound();
		assertThat(t, instanceOf(ByteBuf.class));
		assertThat(((ByteBuf) t).toString(CharsetUtil.UTF_8), is("{\"value\": true, \"test\": 1}"));

		t = channel.readInbound();
		assertThat(t, is(LastHttpContent.EMPTY_LAST_CONTENT));

		t = channel.readInbound();
		assertThat(t, nullValue());
	}

}
