/*
 * Copyright (c) 2017-2023 VMware, Inc. or its affiliates, All Rights Reserved.
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

import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;

import io.netty5.channel.Channel;
import io.netty5.channel.ChannelHandler;
import io.netty5.channel.ChannelHandlerAdapter;
import io.netty5.channel.ChannelHandlerContext;
import io.netty5.channel.embedded.EmbeddedChannel;
import io.netty5.handler.codec.LineBasedFrameDecoder;
import io.netty5.handler.codec.string.StringDecoder;
import io.netty5.handler.codec.string.StringEncoder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.Disposable;
import reactor.core.publisher.Mono;

import static org.assertj.core.api.Assertions.assertThat;
import static reactor.netty5.NettyPipeline.RIGHT;

/**
 * This test class verifies {@link Connection}.
 *
 * @author Simon Baslé
 * @author Violeta Georgieva
 */
class ConnectionTest {

	static final BiConsumer<? super ChannelHandlerContext, Object> ADD_EXTRACTOR = ChannelHandlerContext::fireChannelRead;

	static final String ANOTHER_RIGHT = RIGHT + "test";
	static final String DECODER_NAME = "decoder";
	static final String DECODER_EXTRACT_NAME = "decoder$extract";
	static final String ENCODER_NAME = "encoder";

	ChannelHandler anotherRight;
	EmbeddedChannel channel;
	ChannelHandler decoder;
	ChannelHandler encoder;
	ChannelHandler httpServerCodecMock;
	ChannelHandler httpTrafficHandlerMock;
	ChannelHandler reactiveBridgeMock;
	Connection testContext;

	@BeforeEach
	void init() {
		anotherRight = new ChannelHandlerAdapter(){};
		channel = new EmbeddedChannel();
		decoder = new LineBasedFrameDecoder(12);
		encoder = new LineBasedFrameDecoder(12);
		httpServerCodecMock = new ChannelHandlerAdapter(){};
		httpTrafficHandlerMock = new ChannelHandlerAdapter(){};
		reactiveBridgeMock = new ChannelHandlerAdapter(){};
		testContext = () -> channel;
	}

	@Test
	void addByteDecoderWhenNoLeft() {
		channel.pipeline()
		       .addLast(NettyPipeline.ReactiveBridge, reactiveBridgeMock);

		testContext.addHandlerLast(DECODER_NAME, decoder)
		           .addHandlerFirst(DECODER_EXTRACT_NAME, NettyPipeline.inboundHandler(ADD_EXTRACTOR));

		assertThat(channel.pipeline().names())
				.containsExactly(DECODER_EXTRACT_NAME, DECODER_NAME, NettyPipeline.ReactiveBridge);
	}

	@Test
	void addByteDecoderWhenManyRight() {
		channel.pipeline()
		       .addLast(ANOTHER_RIGHT, anotherRight)
		       .addLast(NettyPipeline.ReactiveBridge, reactiveBridgeMock);

		testContext.addHandlerLast(DECODER_NAME, decoder)
		           .addHandlerFirst(DECODER_EXTRACT_NAME, NettyPipeline.inboundHandler(ADD_EXTRACTOR));

		assertThat(channel.pipeline().names())
				.containsExactly(DECODER_EXTRACT_NAME, DECODER_NAME, ANOTHER_RIGHT, NettyPipeline.ReactiveBridge);
	}

	@Test
	void addByteDecoderWhenNoRight() {
		channel.pipeline()
		       .addLast(NettyPipeline.HttpCodec, httpServerCodecMock);

		testContext.addHandlerLast(DECODER_NAME, decoder)
		           .addHandlerFirst(DECODER_EXTRACT_NAME, NettyPipeline.inboundHandler(ADD_EXTRACTOR));

		assertThat(channel.pipeline().names())
				.containsExactly(NettyPipeline.HttpCodec, DECODER_EXTRACT_NAME, DECODER_NAME);
	}

	@Test
	void addByteDecoderWhenEmptyPipeline() {
		testContext.addHandlerLast(DECODER_NAME, decoder)
		           .addHandlerFirst(DECODER_EXTRACT_NAME, NettyPipeline.inboundHandler(ADD_EXTRACTOR));

		assertThat(channel.pipeline().names()).containsExactly(DECODER_EXTRACT_NAME, DECODER_NAME);
	}

	@Test
	void addByteDecoderWhenFullReactorPipeline() {
		channel.pipeline()
		       .addLast(NettyPipeline.HttpCodec, httpServerCodecMock)
		       .addLast(NettyPipeline.HttpTrafficHandler, httpTrafficHandlerMock)
		       .addLast(NettyPipeline.ReactiveBridge, reactiveBridgeMock);

		testContext.addHandlerLast(DECODER_NAME, decoder)
		           .addHandlerFirst(DECODER_EXTRACT_NAME, NettyPipeline.inboundHandler(ADD_EXTRACTOR));

		assertThat(channel.pipeline().names())
				.containsExactly(NettyPipeline.HttpCodec, NettyPipeline.HttpTrafficHandler, DECODER_EXTRACT_NAME,
						DECODER_NAME, NettyPipeline.ReactiveBridge);
	}

	@Test
	void addNonByteDecoderWhenNoLeft() {
		channel.pipeline()
		       .addLast(NettyPipeline.ReactiveBridge, reactiveBridgeMock);

		testContext.addHandlerLast(DECODER_NAME, decoder);

		assertThat(channel.pipeline().names())
				.containsExactly(DECODER_NAME, NettyPipeline.ReactiveBridge);
	}

	@Test
	void addNonByteDecoderWhenManyRight() {
		channel.pipeline()
		       .addLast(ANOTHER_RIGHT, anotherRight)
		       .addLast(NettyPipeline.ReactiveBridge, reactiveBridgeMock);

		testContext.addHandlerLast(DECODER_NAME, decoder);

		assertThat(channel.pipeline().names())
				.containsExactly(DECODER_NAME, ANOTHER_RIGHT, NettyPipeline.ReactiveBridge);
	}

	@Test
	void addNonByteDecoderWhenNoRight() {
		channel.pipeline()
		       .addLast(NettyPipeline.HttpCodec, httpServerCodecMock);

		testContext.addHandlerLast(DECODER_NAME, decoder);

		assertThat(channel.pipeline().names()).containsExactly(NettyPipeline.HttpCodec, DECODER_NAME);
	}

	@Test
	void addNonByteDecoderWhenEmptyPipeline() {
		testContext.addHandlerLast(DECODER_NAME, decoder);

		assertThat(channel.pipeline().names()).containsExactly(DECODER_NAME);
	}

	@Test
	void addNonByteDecoderWhenFullReactorPipeline() {
		channel.pipeline()
		       .addLast(NettyPipeline.HttpCodec, httpServerCodecMock)
		       .addLast(NettyPipeline.HttpTrafficHandler, httpTrafficHandlerMock)
		       .addLast(NettyPipeline.ReactiveBridge, reactiveBridgeMock);

		testContext.addHandlerLast(DECODER_NAME, decoder);

		assertThat(channel.pipeline().names())
				.containsExactly(NettyPipeline.HttpCodec, NettyPipeline.HttpTrafficHandler, DECODER_NAME,
						NettyPipeline.ReactiveBridge);
	}

	@Test
	void addSeveralByteDecodersWhenCodec() {
		ChannelHandler decoder1 = new LineBasedFrameDecoder(12);
		ChannelHandler decoder2 = new LineBasedFrameDecoder(13);

		channel.pipeline()
		       .addLast(NettyPipeline.HttpCodec, httpServerCodecMock)
		       .addLast(NettyPipeline.HttpTrafficHandler, httpTrafficHandlerMock)
		       .addLast(NettyPipeline.ReactiveBridge, reactiveBridgeMock);

		testContext.addHandlerLast("decoder1$extract", NettyPipeline.inboundHandler(ADD_EXTRACTOR))
		           .addHandlerLast("decoder1", decoder1)

		           .addHandlerLast("decoder2$extract", NettyPipeline.inboundHandler(ADD_EXTRACTOR))
		           .addHandlerLast("decoder2", decoder2);

		assertThat(channel.pipeline().names())
				.containsExactly(NettyPipeline.HttpCodec, NettyPipeline.HttpTrafficHandler, "decoder1$extract",
						"decoder1", "decoder2$extract", "decoder2", NettyPipeline.ReactiveBridge);
	}

	@Test
	void addByteEncoderWhenNoLeft() {
		channel.pipeline()
		       .addLast(NettyPipeline.ReactiveBridge, reactiveBridgeMock);

		testContext.addHandlerFirst(ENCODER_NAME, encoder);

		assertThat(channel.pipeline().names())
				.containsExactly(ENCODER_NAME, NettyPipeline.ReactiveBridge);
	}

	@Test
	void addByteEncoderWhenNoRight() {
		channel.pipeline()
		       .addLast(NettyPipeline.HttpCodec, httpServerCodecMock);

		testContext.addHandlerFirst(ENCODER_NAME, encoder);

		assertThat(channel.pipeline().names()).containsExactly(NettyPipeline.HttpCodec, ENCODER_NAME);
	}

	@Test
	void addByteEncoderWhenEmptyPipeline() {
		testContext.addHandlerFirst(ENCODER_NAME, encoder);

		assertThat(channel.pipeline().names()).containsExactly(ENCODER_NAME);
	}

	@Test
	void addByteEncoderWhenFullReactorPipeline() {
		channel.pipeline()
		       .addLast(NettyPipeline.HttpCodec, httpServerCodecMock)
		       .addLast(NettyPipeline.HttpTrafficHandler, httpTrafficHandlerMock)
		       .addLast(NettyPipeline.ReactiveBridge, reactiveBridgeMock);
		ChannelHandler encoder = new LineBasedFrameDecoder(12);

		testContext.addHandlerFirst(ENCODER_NAME, encoder);

		assertThat(channel.pipeline().names())
				.containsExactly(NettyPipeline.HttpCodec, NettyPipeline.HttpTrafficHandler, ENCODER_NAME,
						NettyPipeline.ReactiveBridge);
	}

	@Test
	void addNonByteEncoderWhenNoLeft() {
		channel.pipeline()
		       .addLast(NettyPipeline.ReactiveBridge, reactiveBridgeMock);

		testContext.addHandlerFirst(ENCODER_NAME, encoder);

		assertThat(channel.pipeline().names())
				.containsExactly(ENCODER_NAME, NettyPipeline.ReactiveBridge);
	}

	@Test
	void addNonByteEncoderWhenNoRight() {
		channel.pipeline()
		       .addLast(NettyPipeline.HttpCodec, httpServerCodecMock);

		testContext.addHandlerFirst(ENCODER_NAME, encoder);

		assertThat(channel.pipeline().names()).containsExactly(NettyPipeline.HttpCodec, ENCODER_NAME);
	}

	@Test
	void addNonByteEncoderWhenEmptyPipeline() {
		testContext.addHandlerFirst(ENCODER_NAME, encoder);

		assertThat(channel.pipeline().names()).containsExactly(ENCODER_NAME);
	}

	@Test
	void addNonByteEncoderWhenFullReactorPipeline() {
		channel.pipeline()
		       .addLast(NettyPipeline.HttpCodec, httpServerCodecMock)
		       .addLast(NettyPipeline.HttpTrafficHandler, httpTrafficHandlerMock)
		       .addLast(NettyPipeline.ReactiveBridge, reactiveBridgeMock);

		testContext.addHandlerFirst(ENCODER_NAME, encoder);

		assertThat(channel.pipeline().names())
				.containsExactly(NettyPipeline.HttpCodec, NettyPipeline.HttpTrafficHandler, ENCODER_NAME,
						NettyPipeline.ReactiveBridge);
	}

	@Test
	void addSeveralByteEncodersWhenCodec() {
		ChannelHandler encoder1 = new LineBasedFrameDecoder(12);
		ChannelHandler encoder2 = new LineBasedFrameDecoder(13);

		channel.pipeline()
		       .addLast(NettyPipeline.HttpCodec, httpServerCodecMock)
		       .addLast(NettyPipeline.HttpTrafficHandler, httpTrafficHandlerMock)
		       .addLast(NettyPipeline.ReactiveBridge, reactiveBridgeMock);

		testContext.addHandlerFirst("encoder1", encoder1)
		           .addHandlerFirst("encoder2", encoder2);

		assertThat(channel.pipeline().names())
				.containsExactly(NettyPipeline.HttpCodec, NettyPipeline.HttpTrafficHandler, "encoder2",
						"encoder1", NettyPipeline.ReactiveBridge);
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
		 .addHandlerFirst("byteEncoder", new StringEncoder())
		 .addHandlerFirst(ENCODER_NAME, encoder);

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
		 .addHandlerLast("byteDecoder", new StringDecoder())
		 .addHandlerLast(DECODER_NAME, decoder);

		assertThat(c.isPersistent()).isFalse();
		assertThat(closeCount.intValue()).isEqualTo(0);
	}

	@Test
	void addDecoderSkipsIfExist() {
		channel.pipeline()
		       .addFirst(DECODER_NAME, new StringDecoder());

		testContext.addHandlerFirst(DECODER_NAME, decoder);

		assertThat(channel.pipeline().names()).containsExactly(DECODER_NAME);
		assertThat(channel.pipeline().get(DECODER_NAME)).isInstanceOf(StringDecoder.class);
	}

	@Test
	void addEncoderSkipsIfExist() {
		channel.pipeline()
		       .addFirst(ENCODER_NAME, new StringEncoder());

		testContext.addHandlerFirst(ENCODER_NAME, new LineBasedFrameDecoder(10));

		assertThat(channel.pipeline().names()).containsExactly(ENCODER_NAME);
		assertThat(channel.pipeline().get(ENCODER_NAME)).isInstanceOf(StringEncoder.class);
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
