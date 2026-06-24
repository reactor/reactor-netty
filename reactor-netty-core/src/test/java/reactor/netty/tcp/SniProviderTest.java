/*
 * Copyright (c) 2026 VMware, Inc. or its affiliates, All Rights Reserved.
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
package reactor.netty.tcp;

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.DecoderException;
import io.netty.util.AsyncMapping;
import io.netty.util.concurrent.Promise;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SniProviderTest {

	static final AsyncMapping<String, SslProvider> UNUSED_MAPPING = (input, promise) -> promise.setSuccess(null);

	/**
	 * Reproduces https://github.com/reactor/reactor-netty/issues/4229: when the connection is closed
	 * (TCP FIN) before the SNI lookup completes, the lookup callback runs against an already inactive
	 * channel. The handler must then short-circuit silently instead of propagating a misleading decode
	 * error down the pipeline.
	 */
	@Test
	void onLookupCompleteIsSilentWhenChannelInactive() {
		SniProvider.SniHandler handler = new SniProvider(UNUSED_MAPPING, 0).newSniHandler();
		EmbeddedChannel channel = new EmbeddedChannel(handler);
		try {
			ChannelHandlerContext ctx = channel.pipeline().context(handler);

			channel.close();
			assertThat(channel.isActive()).isFalse();

			Promise<SslProvider> future = channel.eventLoop().newPromise();
			future.setFailure(new DecoderException("failed to get the SslContext for example.com"));

			assertThatCode(() -> handler.onLookupComplete(ctx, "example.com", future))
					.doesNotThrowAnyException();
		}
		finally {
			channel.finishAndReleaseAll();
		}
	}

	@Test
	void onLookupCompletePropagatesFailureWhenChannelActive() {
		SniProvider.SniHandler handler = new SniProvider(UNUSED_MAPPING, 0).newSniHandler();
		EmbeddedChannel channel = new EmbeddedChannel(handler);
		try {
			ChannelHandlerContext ctx = channel.pipeline().context(handler);
			assertThat(channel.isActive()).isTrue();

			Promise<SslProvider> future = channel.eventLoop().newPromise();
			future.setFailure(new DecoderException(
					"failed to get the SslContext for example.com: the SNI mapping returned null"));

			assertThatThrownBy(() -> handler.onLookupComplete(ctx, "example.com", future))
					.isInstanceOf(DecoderException.class)
					.hasMessageContaining("the SNI mapping returned null");
		}
		finally {
			channel.finishAndReleaseAll();
		}
	}
}
