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

package reactor.ipc.netty;

import java.util.Arrays;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BiConsumer;

import io.netty.channel.Channel;
import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerAdapter;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.LineBasedFrameDecoder;
import io.netty.handler.codec.http.HttpServerCodec;
import io.netty.handler.codec.http.websocketx.Utf8FrameValidator;
import org.junit.Before;
import org.junit.Test;

import static org.hamcrest.CoreMatchers.instanceOf;
import static org.hamcrest.CoreMatchers.is;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThat;

/**
 * @author Simon Baslé
 */
public class ConnectionTest {

	Connection      testContext;
	EmbeddedChannel channel;

	static final BiConsumer<? super ChannelHandlerContext, Object> ADD_EXTRACTOR =
			ChannelHandlerContext::fireChannelRead;

	@Before
	public void init() {
		channel = new EmbeddedChannel();
		testContext = () -> channel;
	}

	@Test
	public void addByteDecoderWhenNoLeft() throws Exception {

		channel.pipeline()
		       .addLast(NettyPipeline.ReactiveBridge, new ChannelHandlerAdapter() {
		       });
		ChannelHandler decoder = new LineBasedFrameDecoder(12);

		testContext.addHandlerLast("decoder", decoder)
		           .addHandlerFirst("decoder$extract",
				           NettyPipeline.inboundHandler(ADD_EXTRACTOR));

		assertEquals(channel.pipeline()
		                    .names(),
				Arrays.asList("decoder$extract",
						"decoder",
						NettyPipeline.ReactiveBridge,
						"DefaultChannelPipeline$TailContext#0"));
	}

	@Test
	public void addByteDecoderWhenNoRight() throws Exception {

		channel.pipeline()
		       .addLast(NettyPipeline.HttpCodec, new ChannelHandlerAdapter() {
		       });
		ChannelHandler decoder = new LineBasedFrameDecoder(12);

		testContext.addHandlerLast("decoder", decoder)
		           .addHandlerFirst("decoder$extract",
				           NettyPipeline.inboundHandler(ADD_EXTRACTOR));

		assertEquals(channel.pipeline()
		                    .names(),
				Arrays.asList(NettyPipeline.HttpCodec,
						"decoder$extract",
						"decoder",
						"DefaultChannelPipeline$TailContext#0"));
	}

	@Test
	public void addByteDecoderWhenEmptyPipeline() throws Exception {

		ChannelHandler decoder = new LineBasedFrameDecoder(12);

		testContext.addHandlerLast("decoder", decoder)
		           .addHandlerFirst("decoder$extract",
				           NettyPipeline.inboundHandler(ADD_EXTRACTOR));

		assertEquals(channel.pipeline()
		                    .names(),
				Arrays.asList("decoder$extract",
						"decoder",
						"DefaultChannelPipeline$TailContext#0"));
	}

	@Test
	public void addByteDecoderWhenFullReactorPipeline() throws Exception {

		channel.pipeline()
		       .addLast(NettyPipeline.HttpCodec, new HttpServerCodec())
		       .addLast(NettyPipeline.HttpServerHandler, new ChannelDuplexHandler())
		       .addLast(NettyPipeline.ReactiveBridge, new ChannelHandlerAdapter() {
		       });
		ChannelHandler decoder = new LineBasedFrameDecoder(12);

		testContext.addHandlerLast("decoder", decoder)
		           .addHandlerFirst("decoder$extract",
				           NettyPipeline.inboundHandler(ADD_EXTRACTOR));

		assertEquals(channel.pipeline()
		                    .names(),
				Arrays.asList(NettyPipeline.HttpCodec,
						NettyPipeline.HttpServerHandler,
						"decoder$extract",
						"decoder",
						NettyPipeline.ReactiveBridge,
						"DefaultChannelPipeline$TailContext#0"));
	}

	@Test
	public void addNonByteDecoderWhenNoLeft() throws Exception {

		channel.pipeline()
		       .addLast(NettyPipeline.ReactiveBridge, new ChannelHandlerAdapter() {
		       });
		ChannelHandler decoder = new ChannelHandlerAdapter() {
		};

		testContext.addHandlerLast("decoder", decoder);

		assertEquals(channel.pipeline()
		                    .names(),
				Arrays.asList("decoder",
						NettyPipeline.ReactiveBridge,
						"DefaultChannelPipeline$TailContext#0"));
	}

	@Test
	public void addNonByteDecoderWhenNoRight() throws Exception {

		channel.pipeline()
		       .addLast(NettyPipeline.HttpCodec, new ChannelHandlerAdapter() {
		       });
		ChannelHandler decoder = new ChannelHandlerAdapter() {
		};

		testContext.addHandlerLast("decoder", decoder);

		assertEquals(channel.pipeline()
		                    .names(),
				Arrays.asList(NettyPipeline.HttpCodec,
						"decoder",
						"DefaultChannelPipeline$TailContext#0"));
	}

	@Test
	public void addNonByteDecoderWhenEmptyPipeline() throws Exception {

		ChannelHandler decoder = new ChannelHandlerAdapter() {
		};

		testContext.addHandlerLast("decoder", decoder);

		assertEquals(channel.pipeline()
		                    .names(),
				Arrays.asList("decoder", "DefaultChannelPipeline$TailContext#0"));
	}

	@Test
	public void addNonByteDecoderWhenFullReactorPipeline() throws Exception {

		channel.pipeline()
		       .addLast(NettyPipeline.HttpCodec, new HttpServerCodec())
		       .addLast(NettyPipeline.HttpServerHandler, new ChannelDuplexHandler())
		       .addLast(NettyPipeline.ReactiveBridge, new ChannelHandlerAdapter() {
		       });
		ChannelHandler decoder = new ChannelHandlerAdapter() {
		};

		testContext.addHandlerLast("decoder", decoder);

		assertEquals(channel.pipeline()
		                    .names(),
				Arrays.asList(NettyPipeline.HttpCodec,
						NettyPipeline.HttpServerHandler,
						"decoder",
						NettyPipeline.ReactiveBridge,
						"DefaultChannelPipeline$TailContext#0"));
	}

	@Test
	public void addSeveralByteDecodersWhenCodec() throws Exception {
		ChannelHandler decoder1 = new LineBasedFrameDecoder(12);
		ChannelHandler decoder2 = new LineBasedFrameDecoder(13);

		channel.pipeline()
		       .addLast(NettyPipeline.HttpCodec, new HttpServerCodec())
		       .addLast(NettyPipeline.HttpServerHandler, new ChannelDuplexHandler())
		       .addLast(NettyPipeline.ReactiveBridge, new ChannelHandlerAdapter() {
		       });

		testContext.addHandlerLast("decoder1$extract",
				NettyPipeline.inboundHandler(ADD_EXTRACTOR))
		           .addHandlerLast("decoder1", decoder1)

		           .addHandlerLast("decoder2$extract",
				           NettyPipeline.inboundHandler(ADD_EXTRACTOR))
		           .addHandlerLast("decoder2", decoder2);

		assertEquals(channel.pipeline()
		                    .names(),
				Arrays.asList(NettyPipeline.HttpCodec,
						NettyPipeline.HttpServerHandler,
						"decoder1$extract",
						"decoder1",
						"decoder2$extract",
						"decoder2",
						NettyPipeline.ReactiveBridge,
						"DefaultChannelPipeline$TailContext#0"));
	}

	@Test
	public void addByteEncoderWhenNoLeft() throws Exception {

		channel.pipeline()
		       .addLast(NettyPipeline.ReactiveBridge, new ChannelHandlerAdapter() {
		       });
		ChannelHandler encoder = new LineBasedFrameDecoder(12);

		testContext.addHandlerFirst("encoder", encoder);

		assertEquals(channel.pipeline()
		                    .names(),
				Arrays.asList("encoder",
						NettyPipeline.ReactiveBridge,
						"DefaultChannelPipeline$TailContext#0"));
	}

	@Test
	public void addByteEncoderWhenNoRight() throws Exception {

		channel.pipeline()
		       .addLast(NettyPipeline.HttpCodec, new ChannelHandlerAdapter() {
		       });
		ChannelHandler encoder = new LineBasedFrameDecoder(12);

		testContext.addHandlerFirst("encoder", encoder);

		assertEquals(channel.pipeline()
		                    .names(),
				Arrays.asList(NettyPipeline.HttpCodec,
						"encoder",
						"DefaultChannelPipeline$TailContext#0"));
	}

	@Test
	public void addByteEncoderWhenEmptyPipeline() throws Exception {

		ChannelHandler encoder = new LineBasedFrameDecoder(12);

		testContext.addHandlerFirst("encoder", encoder);

		assertEquals(channel.pipeline()
		                    .names(),
				Arrays.asList("encoder", "DefaultChannelPipeline$TailContext#0"));
	}

	@Test
	public void addByteEncoderWhenFullReactorPipeline() throws Exception {

		channel.pipeline()
		       .addLast(NettyPipeline.HttpCodec, new HttpServerCodec())
		       .addLast(NettyPipeline.HttpServerHandler, new ChannelDuplexHandler())
		       .addLast(NettyPipeline.ReactiveBridge, new ChannelHandlerAdapter() {
		       });
		ChannelHandler encoder = new LineBasedFrameDecoder(12);

		testContext.addHandlerFirst("encoder", encoder);

		assertEquals(channel.pipeline()
		                    .names(),
				Arrays.asList(NettyPipeline.HttpCodec,
						NettyPipeline.HttpServerHandler,
						"encoder",
						NettyPipeline.ReactiveBridge,
						"DefaultChannelPipeline$TailContext#0"));
	}

	@Test
	public void addNonByteEncoderWhenNoLeft() throws Exception {

		channel.pipeline()
		       .addLast(NettyPipeline.ReactiveBridge, new ChannelHandlerAdapter() {
		       });
		ChannelHandler encoder = new ChannelHandlerAdapter() {
		};

		testContext.addHandlerFirst("encoder", encoder);

		assertEquals(channel.pipeline()
		                    .names(),
				Arrays.asList("encoder",
						NettyPipeline.ReactiveBridge,
						"DefaultChannelPipeline$TailContext#0"));
	}

	@Test
	public void addNonByteEncoderWhenNoRight() throws Exception {

		channel.pipeline()
		       .addLast(NettyPipeline.HttpCodec, new ChannelHandlerAdapter() {
		       });
		ChannelHandler encoder = new ChannelHandlerAdapter() {
		};

		testContext.addHandlerFirst("encoder", encoder);

		assertEquals(channel.pipeline()
		                    .names(),
				Arrays.asList(NettyPipeline.HttpCodec,
						"encoder",
						"DefaultChannelPipeline$TailContext#0"));
	}

	@Test
	public void addNonByteEncoderWhenEmptyPipeline() throws Exception {

		ChannelHandler encoder = new ChannelHandlerAdapter() {
		};

		testContext.addHandlerFirst("encoder", encoder);

		assertEquals(channel.pipeline()
		                    .names(),
				Arrays.asList("encoder", "DefaultChannelPipeline$TailContext#0"));
	}

	@Test
	public void addNonByteEncoderWhenFullReactorPipeline() throws Exception {

		channel.pipeline()
		       .addLast(NettyPipeline.HttpCodec, new HttpServerCodec())
		       .addLast(NettyPipeline.HttpServerHandler, new ChannelDuplexHandler())
		       .addLast(NettyPipeline.ReactiveBridge, new ChannelHandlerAdapter() {
		       });
		ChannelHandler encoder = new ChannelHandlerAdapter() {
		};

		testContext.addHandlerFirst("encoder", encoder);

		assertEquals(channel.pipeline()
		                    .names(),
				Arrays.asList(NettyPipeline.HttpCodec,
						NettyPipeline.HttpServerHandler,
						"encoder",
						NettyPipeline.ReactiveBridge,
						"DefaultChannelPipeline$TailContext#0"));
	}

	@Test
	public void addSeveralByteEncodersWhenCodec() throws Exception {
		ChannelHandler encoder1 = new LineBasedFrameDecoder(12);
		ChannelHandler encoder2 = new LineBasedFrameDecoder(13);

		channel.pipeline()
		       .addLast(NettyPipeline.HttpCodec, new HttpServerCodec())
		       .addLast(NettyPipeline.HttpServerHandler, new ChannelDuplexHandler())
		       .addLast(NettyPipeline.ReactiveBridge, new ChannelHandlerAdapter() {
		       });

		testContext.addHandlerFirst("encoder1", encoder1)
		           .addHandlerFirst("encoder2", encoder2);

		assertEquals(channel.pipeline()
		                    .names(),
				Arrays.asList(NettyPipeline.HttpCodec,
						NettyPipeline.HttpServerHandler,
						"encoder2",
						"encoder1",
						NettyPipeline.ReactiveBridge,
						"DefaultChannelPipeline$TailContext#0"));
	}

	@Test
	public void encoderSupportSkipsOnCloseIfAttributeClosedChannel() {
		AtomicLong closeCount = new AtomicLong();
		Connection c = new Connection() {
			@Override
			public Channel channel() {
				return channel;
			}

			@Override
			public Connection onClose(Runnable onClose) {
				closeCount.incrementAndGet();
				return this;
			}

			@Override
			public Connection removeHandler(String name) {
				return this;
			}
		};

		c.markPersistent(false)
		 .addHandlerFirst("byteencoder", new Utf8FrameValidator())
		 .addHandlerFirst("encoder", new ChannelHandlerAdapter() {
		 });

		assertThat(Connection.isPersistent(channel), is(false));
		assertThat(closeCount.intValue(), is(0));
	}

	@Test
	public void decoderSupportSkipsOnCloseIfAttributeClosedChannel() {
		AtomicLong closeCount = new AtomicLong();
		Connection c = new Connection() {
			@Override
			public Channel channel() {
				return channel;
			}

			@Override
			public Connection onClose(Runnable onClose) {
				closeCount.incrementAndGet();
				return this;
			}

			@Override
			public Connection removeHandler(String name) {
				return this;
			}
		};

		c.markPersistent(false)
		 .addHandlerLast("byteDecoder", new Utf8FrameValidator())
		 .addHandlerLast("decoder", new ChannelHandlerAdapter() {
		 });

		assertThat(Connection.isPersistent(channel), is(false));
		assertThat(closeCount.intValue(), is(0));
	}

	@Test
	public void addDecoderSkipsIfExist() {
		channel.pipeline()
		       .addFirst("foo", new Utf8FrameValidator());

		testContext.addHandlerFirst("foo", new LineBasedFrameDecoder(10));

		assertEquals(channel.pipeline()
		                    .names(),
				Arrays.asList("foo", "DefaultChannelPipeline$TailContext#0"));
		assertThat(channel.pipeline()
		                  .get("foo"), is(instanceOf(Utf8FrameValidator.class)));
	}

	@Test
	public void addEncoderSkipsIfExist() {
		channel.pipeline()
		       .addFirst("foo", new Utf8FrameValidator());

		testContext.addHandlerFirst("foo", new LineBasedFrameDecoder(10));

		assertEquals(channel.pipeline()
		                    .names(),
				Arrays.asList("foo", "DefaultChannelPipeline$TailContext#0"));
		assertThat(channel.pipeline()
		                  .get("foo"), is(instanceOf(Utf8FrameValidator.class)));
	}

}
