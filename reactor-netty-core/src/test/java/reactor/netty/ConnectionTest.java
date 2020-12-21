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

import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.Disposable;
import reactor.core.publisher.Mono;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * @author Simon Baslé
 */
class ConnectionTest {

	Connection      testContext;
	EmbeddedChannel channel;

	static final BiConsumer<? super ChannelHandlerContext, Object> ADD_EXTRACTOR =
			ChannelHandlerContext::fireChannelRead;

	@BeforeEach
	void init() {
		channel = new EmbeddedChannel();
		testContext = () -> channel;
	}

	@Test
	void addByteDecoderWhenNoLeft() {

		channel.pipeline()
		       .addLast(NettyPipeline.ReactiveBridge, new ChannelHandlerAdapter() {
		       });
		ChannelHandler decoder = new LineBasedFrameDecoder(12);

		testContext.addHandlerLast("decoder", decoder)
		           .addHandlerFirst("decoder$extract",
				           NettyPipeline.inboundHandler(ADD_EXTRACTOR));

		assertThat(channel.pipeline().names())
				.containsExactly("decoder$extract", "decoder", NettyPipeline.ReactiveBridge, "DefaultChannelPipeline$TailContext#0");
	}

	@Test
	void addByteDecoderWhenNoRight() {

		channel.pipeline()
		       .addLast(NettyPipeline.HttpCodec, new ChannelHandlerAdapter() {
		       });
		ChannelHandler decoder = new LineBasedFrameDecoder(12);

		testContext.addHandlerLast("decoder", decoder)
		           .addHandlerFirst("decoder$extract",
				           NettyPipeline.inboundHandler(ADD_EXTRACTOR));

		assertThat(channel.pipeline().names())
				.containsExactly(NettyPipeline.HttpCodec, "decoder$extract", "decoder", "DefaultChannelPipeline$TailContext#0");
	}

	@Test
	void addByteDecoderWhenEmptyPipeline() {

		ChannelHandler decoder = new LineBasedFrameDecoder(12);

		testContext.addHandlerLast("decoder", decoder)
		           .addHandlerFirst("decoder$extract",
				           NettyPipeline.inboundHandler(ADD_EXTRACTOR));

		assertThat(channel.pipeline().names())
				.containsExactly("decoder$extract", "decoder", "DefaultChannelPipeline$TailContext#0");
	}

	@Test
	void addByteDecoderWhenFullReactorPipeline() {

		channel.pipeline()
		       .addLast(NettyPipeline.HttpCodec, new HttpServerCodec())
		       .addLast(NettyPipeline.HttpTrafficHandler, new ChannelDuplexHandler())
		       .addLast(NettyPipeline.ReactiveBridge, new ChannelHandlerAdapter() {
		       });
		ChannelHandler decoder = new LineBasedFrameDecoder(12);

		testContext.addHandlerLast("decoder", decoder)
		           .addHandlerFirst("decoder$extract",
				           NettyPipeline.inboundHandler(ADD_EXTRACTOR));

		assertThat(channel.pipeline().names())
				.containsExactly(NettyPipeline.HttpCodec, NettyPipeline.HttpTrafficHandler, "decoder$extract",
						"decoder", NettyPipeline.ReactiveBridge, "DefaultChannelPipeline$TailContext#0");
	}

	@Test
	void addNonByteDecoderWhenNoLeft() {

		channel.pipeline()
		       .addLast(NettyPipeline.ReactiveBridge, new ChannelHandlerAdapter() {
		       });
		ChannelHandler decoder = new ChannelHandlerAdapter() {
		};

		testContext.addHandlerLast("decoder", decoder);

		assertThat(channel.pipeline().names())
				.containsExactly("decoder", NettyPipeline.ReactiveBridge, "DefaultChannelPipeline$TailContext#0");
	}

	@Test
	void addNonByteDecoderWhenNoRight() {

		channel.pipeline()
		       .addLast(NettyPipeline.HttpCodec, new ChannelHandlerAdapter() {
		       });
		ChannelHandler decoder = new ChannelHandlerAdapter() {
		};

		testContext.addHandlerLast("decoder", decoder);

		assertThat(channel.pipeline().names())
				.containsExactly(NettyPipeline.HttpCodec, "decoder", "DefaultChannelPipeline$TailContext#0");
	}

	@Test
	void addNonByteDecoderWhenEmptyPipeline() {

		ChannelHandler decoder = new ChannelHandlerAdapter() {
		};

		testContext.addHandlerLast("decoder", decoder);

		assertThat(channel.pipeline().names())
				.containsExactly("decoder", "DefaultChannelPipeline$TailContext#0");
	}

	@Test
	void addNonByteDecoderWhenFullReactorPipeline() {

		channel.pipeline()
		       .addLast(NettyPipeline.HttpCodec, new HttpServerCodec())
		       .addLast(NettyPipeline.HttpTrafficHandler, new ChannelDuplexHandler())
		       .addLast(NettyPipeline.ReactiveBridge, new ChannelHandlerAdapter() {
		       });
		ChannelHandler decoder = new ChannelHandlerAdapter() {
		};

		testContext.addHandlerLast("decoder", decoder);

		assertThat(channel.pipeline().names())
				.containsExactly(NettyPipeline.HttpCodec, NettyPipeline.HttpTrafficHandler, "decoder",
						NettyPipeline.ReactiveBridge, "DefaultChannelPipeline$TailContext#0");
	}

	@Test
	void addSeveralByteDecodersWhenCodec() {
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

		assertThat(channel.pipeline().names())
				.containsExactly(NettyPipeline.HttpCodec, NettyPipeline.HttpTrafficHandler, "decoder1$extract",
						"decoder1", "decoder2$extract", "decoder2",
						NettyPipeline.ReactiveBridge, "DefaultChannelPipeline$TailContext#0");
	}

	@Test
	void addByteEncoderWhenNoLeft() {

		channel.pipeline()
		       .addLast(NettyPipeline.ReactiveBridge, new ChannelHandlerAdapter() {
		       });
		ChannelHandler encoder = new LineBasedFrameDecoder(12);

		testContext.addHandlerFirst("encoder", encoder);

		assertThat(channel.pipeline().names())
				.containsExactly("encoder", NettyPipeline.ReactiveBridge, "DefaultChannelPipeline$TailContext#0");
	}

	@Test
	void addByteEncoderWhenNoRight() {

		channel.pipeline()
		       .addLast(NettyPipeline.HttpCodec, new ChannelHandlerAdapter() {
		       });
		ChannelHandler encoder = new LineBasedFrameDecoder(12);

		testContext.addHandlerFirst("encoder", encoder);

		assertThat(channel.pipeline().names())
				.containsExactly(NettyPipeline.HttpCodec, "encoder", "DefaultChannelPipeline$TailContext#0");
	}

	@Test
	void addByteEncoderWhenEmptyPipeline() {

		ChannelHandler encoder = new LineBasedFrameDecoder(12);

		testContext.addHandlerFirst("encoder", encoder);

		assertThat(channel.pipeline().names())
				.containsExactly("encoder", "DefaultChannelPipeline$TailContext#0");
	}

	@Test
	void addByteEncoderWhenFullReactorPipeline() {

		channel.pipeline()
		       .addLast(NettyPipeline.HttpCodec, new HttpServerCodec())
		       .addLast(NettyPipeline.HttpTrafficHandler, new ChannelDuplexHandler())
		       .addLast(NettyPipeline.ReactiveBridge, new ChannelHandlerAdapter() {
		       });
		ChannelHandler encoder = new LineBasedFrameDecoder(12);

		testContext.addHandlerFirst("encoder", encoder);

		assertThat(channel.pipeline().names())
				.containsExactly(NettyPipeline.HttpCodec, NettyPipeline.HttpTrafficHandler, "encoder",
						NettyPipeline.ReactiveBridge, "DefaultChannelPipeline$TailContext#0");
	}

	@Test
	void addNonByteEncoderWhenNoLeft() {

		channel.pipeline()
		       .addLast(NettyPipeline.ReactiveBridge, new ChannelHandlerAdapter() {
		       });
		ChannelHandler encoder = new ChannelHandlerAdapter() {
		};

		testContext.addHandlerFirst("encoder", encoder);

		assertThat(channel.pipeline().names())
				.containsExactly("encoder", NettyPipeline.ReactiveBridge, "DefaultChannelPipeline$TailContext#0");
	}

	@Test
	void addNonByteEncoderWhenNoRight() {

		channel.pipeline()
		       .addLast(NettyPipeline.HttpCodec, new ChannelHandlerAdapter() {
		       });
		ChannelHandler encoder = new ChannelHandlerAdapter() {
		};

		testContext.addHandlerFirst("encoder", encoder);

		assertThat(channel.pipeline().names())
				.containsExactly(NettyPipeline.HttpCodec, "encoder", "DefaultChannelPipeline$TailContext#0");
	}

	@Test
	void addNonByteEncoderWhenEmptyPipeline() {

		ChannelHandler encoder = new ChannelHandlerAdapter() {
		};

		testContext.addHandlerFirst("encoder", encoder);

		assertThat(channel.pipeline().names())
				.containsExactly("encoder", "DefaultChannelPipeline$TailContext#0");
	}

	@Test
	void addNonByteEncoderWhenFullReactorPipeline() {

		channel.pipeline()
		       .addLast(NettyPipeline.HttpCodec, new HttpServerCodec())
		       .addLast(NettyPipeline.HttpTrafficHandler, new ChannelDuplexHandler())
		       .addLast(NettyPipeline.ReactiveBridge, new ChannelHandlerAdapter() {
		       });
		ChannelHandler encoder = new ChannelHandlerAdapter() {
		};

		testContext.addHandlerFirst("encoder", encoder);

		assertThat(channel.pipeline().names())
				.containsExactly(NettyPipeline.HttpCodec, NettyPipeline.HttpTrafficHandler, "encoder",
						NettyPipeline.ReactiveBridge, "DefaultChannelPipeline$TailContext#0");
	}

	@Test
	void addSeveralByteEncodersWhenCodec() {
		ChannelHandler encoder1 = new LineBasedFrameDecoder(12);
		ChannelHandler encoder2 = new LineBasedFrameDecoder(13);

		channel.pipeline()
		       .addLast(NettyPipeline.HttpCodec, new HttpServerCodec())
		       .addLast(NettyPipeline.HttpTrafficHandler, new ChannelDuplexHandler())
		       .addLast(NettyPipeline.ReactiveBridge, new ChannelHandlerAdapter() {
		       });

		testContext.addHandlerFirst("encoder1", encoder1)
		           .addHandlerFirst("encoder2", encoder2);

		assertThat(channel.pipeline().names())
				.containsExactly(NettyPipeline.HttpCodec, NettyPipeline.HttpTrafficHandler, "encoder2",
						"encoder1", NettyPipeline.ReactiveBridge, "DefaultChannelPipeline$TailContext#0");
	}

	@Test
	void encoderSupportSkipsOnCloseIfAttributeClosedChannel() {
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

		assertThat(c.isPersistent()).isFalse();
		assertThat(closeCount.intValue()).isEqualTo(0);
	}

	@Test
	void decoderSupportSkipsOnCloseIfAttributeClosedChannel() {
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

		assertThat(c.isPersistent()).isFalse();
		assertThat(closeCount.intValue()).isEqualTo(0);
	}

	@Test
	void addDecoderSkipsIfExist() {
		channel.pipeline()
		       .addFirst("foo", new Utf8FrameValidator());

		testContext.addHandlerFirst("foo", new LineBasedFrameDecoder(10));

		assertThat(channel.pipeline().names())
				.containsExactly("foo", "DefaultChannelPipeline$TailContext#0");
		assertThat(channel.pipeline().get("foo")).isInstanceOf(Utf8FrameValidator.class);
	}

	@Test
	void addEncoderSkipsIfExist() {
		channel.pipeline()
		       .addFirst("foo", new Utf8FrameValidator());

		testContext.addHandlerFirst("foo", new LineBasedFrameDecoder(10));

		assertThat(channel.pipeline().names())
				.containsExactly("foo", "DefaultChannelPipeline$TailContext#0");
		assertThat(channel.pipeline().get("foo")).isInstanceOf(Utf8FrameValidator.class);
	}

	@Test
	void testSenderUnavailable() {
		doTestUnavailable(testContext.outbound().sendObject("test").then(), "Sender Unavailable");
	}

	@Test
	void testReceiverUnavailable() {
		doTestUnavailable(testContext.inbound().receive().then(), "Receiver Unavailable");

		doTestUnavailable(testContext.inbound().receiveObject().then(), "Receiver Unavailable");
	}

	private void doTestUnavailable(Mono<Void> publisher, String expectation) {
		AtomicReference<Throwable> throwable = new AtomicReference<>();

		publisher.onErrorResume(t -> {
		             throwable.set(t);
		             return Mono.empty();
		         })
		         .block(Duration.ofSeconds(30));

		assertThat(throwable.get())
				.isNotNull()
				.isInstanceOf(IllegalStateException.class)
				.hasMessage(expectation);

		assertThat(channel.isActive()).isTrue();
	}
}
