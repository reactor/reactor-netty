/*
 * Copyright (c) 2019-2022 VMware, Inc. or its affiliates, All Rights Reserved.
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
package reactor.netty5.channel;

import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.ToIntFunction;

import io.netty.buffer.ByteBufHolder;
import io.netty5.buffer.Buffer;
import io.netty5.util.Resource;
import io.netty5.channel.Channel;
import io.netty5.channel.ChannelHandlerContext;
import io.netty5.channel.FileRegion;
import reactor.core.publisher.Mono;
import reactor.netty5.ReactorNetty;

/**
 * @author Stephane Maldini
 */
abstract class MonoSend<I, O> extends Mono<Void> {

	final ChannelHandlerContext            ctx;
	final Function<? super I, ? extends O> transformer;
	final Consumer<? super I>              sourceCleanup;
	final ToIntFunction<? super O>         sizeOf; //FIXME use MessageSizeEstimator ?

	MonoSend(Channel channel,
			Function<? super I, ? extends O> transformer,
			Consumer<? super I> sourceCleanup,
			ToIntFunction<? super O> sizeOf) {
		this.transformer = Objects.requireNonNull(transformer, "source transformer cannot be null");
		this.sourceCleanup = Objects.requireNonNull(sourceCleanup, "source cleanup handler cannot be null");
		this.sizeOf = Objects.requireNonNull(sizeOf, "message size mapper cannot be null");

		this.ctx = Objects.requireNonNull(channel.pipeline().lastContext(),
				"reactiveBridge is not installed");
	}

	static final int                    MAX_SIZE    = 128;

	static final int                    REFILL_SIZE = MAX_SIZE / 2;

	static final Function<Buffer, Buffer> TRANSFORMATION_FUNCTION_BB =
		msg -> {
			if (ReactorNetty.PREDICATE_GROUP_FLUSH.test(msg)) {
				return null;
			}
			return msg;
		};

	static final Function<Object, Object>   TRANSFORMATION_FUNCTION    = Function.identity();

	static final Consumer<Object>  CONSUMER_NOCHECK_CLEANUP    = Resource::dispose;

	static final ToIntFunction<Buffer> SIZE_OF_BB  = Buffer::readableBytes;

	static final ToIntFunction<Object>  SIZE_OF     = msg -> {
		if (msg instanceof ByteBufHolder byteBufHolder) {
			return byteBufHolder.content()
			                            .readableBytes();
		}
		if (msg instanceof Buffer buffer) {
			return buffer.readableBytes();
		}
		if (msg instanceof FileRegion) {
			// aligns with DefaultMessageSizeEstimator.DEFAULT
			return 0;
			// alternatively could have used
			// return (int) Math.min(Integer.MAX_VALUE, ((FileRegion) msg).count());
		}
		return -1;
	};
}
