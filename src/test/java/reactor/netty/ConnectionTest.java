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
import reactor.core.Disposable;

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
	public void addByteDecoderWhenNoLeft() {

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
	public void addByteDecoderWhenNoRight() {

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
	public void addByteDecoderWhenEmptyPipeline() {

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
	public void addByteDecoderWhenFullReactorPipeline() {

		channel.pipeline()
		       .addLast(NettyPipeline.HttpCodec, new HttpServerCodec())
		       .addLast(NettyPipeline.HttpTrafficHandler, new ChannelDuplexHandler())
		       .addLast(NettyPipeline.ReactiveBridge, new ChannelHandlerAdapter() {
		       });
		ChannelHandler decoder = new LineBasedFrameDecoder(12);

		testContext.addHandlerLast("decoder", decoder)
		           .addHandlerFirst("decoder$extract",
				           NettyPipeline.inboundHandler(ADD_EXTRACTOR));

		assertEquals(channel.pipeline()
		                    .names(),
				Arrays.asList(NettyPipeline.HttpCodec,
						NettyPipeline.HttpTrafficHandler,
						"decoder$extract",
						"decoder",
						NettyPipeline.ReactiveBridge,
						"DefaultChannelPipeline$TailContext#0"));
	}

	@Test
	public void addNonByteDecoderWhenNoLeft() {

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
	public void addNonByteDecoderWhenNoRight() {

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
	public void addNonByteDecoderWhenEmptyPipeline() {

		ChannelHandler decoder = new ChannelHandlerAdapter() {
		};

		testContext.addHandlerLast("decoder", decoder);

		assertEquals(channel.pipeline()
		                    .names(),
				Arrays.asList("decoder", "DefaultChannelPipeline$TailContext#0"));
	}

	@Test
	public void addNonByteDecoderWhenFullReactorPipeline() {

		channel.pipeline()
		       .addLast(NettyPipeline.HttpCodec, new HttpServerCodec())
		       .addLast(NettyPipeline.HttpTrafficHandler, new ChannelDuplexHandler())
		       .addLast(NettyPipeline.ReactiveBridge, new ChannelHandlerAdapter() {
		       });
		ChannelHandler decoder = new ChannelHandlerAdapter() {
		};

		testContext.addHandlerLast("decoder", decoder);

		assertEquals(channel.pipeline()
		                    .names(),
				Arrays.asList(NettyPipeline.HttpCodec,
						NettyPipeline.HttpTrafficHandler,
						"decoder",
						NettyPipeline.ReactiveBridge,
						"DefaultChannelPipeline$TailContext#0"));
	}

	@Test
	public void addSeveralByteDecodersWhenCodec() {
		ChannelHandler decoder1 = new LineBasedFrameDecoder(12);
		ChannelHandler decoder2 = new LineBasedFrameDecoder(13);

		channel.pipeline()
		       .addLast(NettyPipeline.HttpCodec, new HttpServerCodec())
		       .addLast(NettyPipeline.HttpTrafficHandler, new ChannelDuplexHandler())
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
						NettyPipeline.HttpTrafficHandler,
						"decoder1$extract",
						"decoder1",
						"decoder2$extract",
						"decoder2",
						NettyPipeline.ReactiveBridge,
						"DefaultChannelPipeline$TailContext#0"));
	}

	@Test
	public void addByteEncoderWhenNoLeft() {

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
	public void addByteEncoderWhenNoRight() {

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
	public void addByteEncoderWhenEmptyPipeline() {

		ChannelHandler encoder = new LineBasedFrameDecoder(12);

		testContext.addHandlerFirst("encoder", encoder);

		assertEquals(channel.pipeline()
		                    .names(),
				Arrays.asList("encoder", "DefaultChannelPipeline$TailContext#0"));
	}

	@Test
	public void addByteEncoderWhenFullReactorPipeline() {

		channel.pipeline()
		       .addLast(NettyPipeline.HttpCodec, new HttpServerCodec())
		       .addLast(NettyPipeline.HttpTrafficHandler, new ChannelDuplexHandler())
		       .addLast(NettyPipeline.ReactiveBridge, new ChannelHandlerAdapter() {
		       });
		ChannelHandler encoder = new LineBasedFrameDecoder(12);

		testContext.addHandlerFirst("encoder", encoder);

		assertEquals(channel.pipeline()
		                    .names(),
				Arrays.asList(NettyPipeline.HttpCodec,
						NettyPipeline.HttpTrafficHandler,
						"encoder",
						NettyPipeline.ReactiveBridge,
						"DefaultChannelPipeline$TailContext#0"));
	}

	@Test
	public void addNonByteEncoderWhenNoLeft() {

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
	public void addNonByteEncoderWhenNoRight() {

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
	public void addNonByteEncoderWhenEmptyPipeline() {

		ChannelHandler encoder = new ChannelHandlerAdapter() {
		};

		testContext.addHandlerFirst("encoder", encoder);

		assertEquals(channel.pipeline()
		                    .names(),
				Arrays.asList("encoder", "DefaultChannelPipeline$TailContext#0"));
	}

	@Test
	public void addNonByteEncoderWhenFullReactorPipeline() {

		channel.pipeline()
		       .addLast(NettyPipeline.HttpCodec, new HttpServerCodec())
		       .addLast(NettyPipeline.HttpTrafficHandler, new ChannelDuplexHandler())
		       .addLast(NettyPipeline.ReactiveBridge, new ChannelHandlerAdapter() {
		       });
		ChannelHandler encoder = new ChannelHandlerAdapter() {
		};

		testContext.addHandlerFirst("encoder", encoder);

		assertEquals(channel.pipeline()
		                    .names(),
				Arrays.asList(NettyPipeline.HttpCodec,
						NettyPipeline.HttpTrafficHandler,
						"encoder",
						NettyPipeline.ReactiveBridge,
						"DefaultChannelPipeline$TailContext#0"));
	}

	@Test
	public void addSeveralByteEncodersWhenCodec() {
		ChannelHandler encoder1 = new LineBasedFrameDecoder(12);
		ChannelHandler encoder2 = new LineBasedFrameDecoder(13);

		channel.pipeline()
		       .addLast(NettyPipeline.HttpCodec, new HttpServerCodec())
		       .addLast(NettyPipeline.HttpTrafficHandler, new ChannelDuplexHandler())
		       .addLast(NettyPipeline.ReactiveBridge, new ChannelHandlerAdapter() {
		       });

		testContext.addHandlerFirst("encoder1", encoder1)
		           .addHandlerFirst("encoder2", encoder2);

		assertEquals(channel.pipeline()
		                    .names(),
				Arrays.asList(NettyPipeline.HttpCodec,
						NettyPipeline.HttpTrafficHandler,
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
			public Connection onDispose(Disposable onClose) {
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

		assertThat(c.isPersistent(), is(false));
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
			public Connection onDispose(Disposable onClose) {
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

		assertThat(c.isPersistent(), is(false));
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
