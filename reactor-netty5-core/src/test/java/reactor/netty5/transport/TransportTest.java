/*
 * Copyright (c) 2020-2023 VMware, Inc. or its affiliates, All Rights Reserved.
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
package reactor.netty5.transport;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.Charset;
import java.util.Collections;
import java.util.Map;

import io.netty5.channel.Channel;
import io.netty5.channel.ChannelOption;
import io.netty5.channel.EventLoopGroup;
import io.netty5.handler.logging.BufferFormat;
import io.netty5.handler.logging.LogLevel;
import io.netty5.handler.logging.LoggingHandler;
import org.junit.jupiter.api.Test;
import reactor.netty5.ChannelPipelineConfigurer;
import reactor.netty5.ConnectionObserver;
import reactor.netty5.channel.ChannelMetricsRecorder;
import reactor.netty5.transport.logging.AdvancedBufferFormat;
import reactor.netty5.resources.LoopResources;

/**
 * This test class verifies {@link Transport}.
 *
 * @author Violeta Georgieva
 */
class TransportTest {

	@Test
	void testWiretap() {
		TestTransportConfig config = new TestTransportConfig(Collections.emptyMap());
		TestTransport transport = new TestTransport(config);

		doTestWiretap(transport.wiretap(true), LogLevel.DEBUG, BufferFormat.HEX_DUMP);
		doTestWiretap(transport.wiretap("category"), LogLevel.DEBUG, BufferFormat.HEX_DUMP);
		doTestWiretap(transport.wiretap("category", LogLevel.DEBUG), LogLevel.DEBUG, BufferFormat.HEX_DUMP);
		doTestWiretap(transport.wiretap("category", LogLevel.INFO), LogLevel.INFO, BufferFormat.HEX_DUMP);
		doTestWiretap(
			transport.wiretap("category", LogLevel.INFO, AdvancedBufferFormat.HEX_DUMP),
			LogLevel.INFO,
			BufferFormat.HEX_DUMP);
		doTestWiretap(
			transport.wiretap("category", LogLevel.DEBUG, AdvancedBufferFormat.SIMPLE),
			LogLevel.DEBUG,
			BufferFormat.SIMPLE);

		doTestWiretapForTextualLogger(
			transport.wiretap("category", LogLevel.DEBUG, AdvancedBufferFormat.TEXTUAL), LogLevel.DEBUG);
		doTestWiretapForTextualLogger(
			transport.wiretap("category", LogLevel.DEBUG, AdvancedBufferFormat.TEXTUAL, Charset.defaultCharset()), LogLevel.DEBUG);
	}

	private void doTestWiretap(TestTransport transport, LogLevel expectedLevel, BufferFormat expectedFormat) {
		LoggingHandler loggingHandler = transport.config.loggingHandler;

		assertThat(loggingHandler.level()).isSameAs(expectedLevel);
		assertThat(loggingHandler.bufferFormat()).isSameAs(expectedFormat);
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

		static final LoggingHandler LOGGING_HANDLER =
			AdvancedBufferFormat.HEX_DUMP
					.toLoggingHandler(TestTransport.class.getName(), LogLevel.DEBUG, Charset.defaultCharset());

		TestTransportConfig(Map<ChannelOption<?>, ?> options) {
			super(options);
		}

		TestTransportConfig(TransportConfig parent) {
			super(parent);
		}

		@Override
		protected Class<? extends Channel> channelType() {
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
