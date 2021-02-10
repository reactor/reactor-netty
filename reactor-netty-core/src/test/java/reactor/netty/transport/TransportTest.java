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
package reactor.netty.transport;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.Charset;
import java.util.Collections;
import java.util.Map;

import io.netty.channel.Channel;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.handler.logging.ByteBufFormat;
import io.netty.handler.logging.LogLevel;
import io.netty.handler.logging.LoggingHandler;
import org.junit.jupiter.api.Test;
import reactor.netty.ChannelPipelineConfigurer;
import reactor.netty.ConnectionObserver;
import reactor.netty.channel.ChannelMetricsRecorder;
import reactor.netty.transport.logging.AdvancedByteBufFormat;
import reactor.netty.resources.LoopResources;

/**
 * @author Violeta Georgieva
 */
class TransportTest {

	@Test
	void testWiretap() {
		TestTransportConfig config = new TestTransportConfig(Collections.emptyMap());
		TestTransport transport = new TestTransport(config);

		doTestWiretap(transport.wiretap(true), LogLevel.DEBUG, ByteBufFormat.HEX_DUMP);
		doTestWiretap(transport.wiretap("category"), LogLevel.DEBUG, ByteBufFormat.HEX_DUMP);
		doTestWiretap(transport.wiretap("category", LogLevel.DEBUG), LogLevel.DEBUG, ByteBufFormat.HEX_DUMP);
		doTestWiretap(transport.wiretap("category", LogLevel.INFO), LogLevel.INFO, ByteBufFormat.HEX_DUMP);
		doTestWiretap(
			transport.wiretap("category", LogLevel.INFO, AdvancedByteBufFormat.HEX_DUMP),
			LogLevel.INFO,
			ByteBufFormat.HEX_DUMP);
		doTestWiretap(
			transport.wiretap("category", LogLevel.DEBUG, AdvancedByteBufFormat.SIMPLE),
			LogLevel.DEBUG,
			ByteBufFormat.SIMPLE);

		doTestWiretapForTextualLogger(
			transport.wiretap("category", LogLevel.DEBUG, AdvancedByteBufFormat.TEXTUAL), LogLevel.DEBUG);
		doTestWiretapForTextualLogger(
			transport.wiretap("category", LogLevel.DEBUG, AdvancedByteBufFormat.TEXTUAL, Charset.defaultCharset()), LogLevel.DEBUG);
	}

	private void doTestWiretap(TestTransport transport, LogLevel expectedLevel, ByteBufFormat expectedFormat) {
		LoggingHandler loggingHandler = transport.config.loggingHandler;

		assertThat(loggingHandler.level()).isSameAs(expectedLevel);
		assertThat(loggingHandler.byteBufFormat()).isSameAs(expectedFormat);
	}

	private void doTestWiretapForTextualLogger(TestTransport transport, LogLevel expectedLevel) {
		LoggingHandler loggingHandler = transport.config.loggingHandler;

		assertThat(loggingHandler).hasFieldOrProperty("charset");
		assertThat(loggingHandler.level()).isSameAs(expectedLevel);
	}

	static final class TestTransport extends Transport<TestTransport, TestTransportConfig> {

		final TestTransportConfig config;

		TestTransport(TestTransportConfig config) {
			this.config = config;
		}

		@Override
		public TestTransportConfig configuration() {
			return config;
		}

		@Override
		protected TestTransport duplicate() {
			return new TestTransport(new TestTransportConfig(config));
		}

	}

	static final class TestTransportConfig extends TransportConfig {

		static final LoggingHandler LOGGING_HANDLER = new LoggingHandler(TestTransport.class);

		TestTransportConfig(Map<ChannelOption<?>, ?> options) {
			super(options);
		}

		TestTransportConfig(TransportConfig parent) {
			super(parent);
		}

		@Override
		protected Class<? extends Channel> channelType(boolean isDomainSocket) {
			return null;
		}

		@Override
		protected ConnectionObserver defaultConnectionObserver() {
			return null;
		}

		@Override
		protected LoggingHandler defaultLoggingHandler() {
			return LOGGING_HANDLER;
		}

		@Override
		protected LoopResources defaultLoopResources() {
			return null;
		}

		@Override
		protected ChannelMetricsRecorder defaultMetricsRecorder() {
			return null;
		}

		@Override
		protected ChannelPipelineConfigurer defaultOnChannelInit() {
			return null;
		}

		@Override
		protected EventLoopGroup eventLoopGroup() {
			return null;
		}

	}

}
