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
package reactor.netty.channel;

import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.ToIntFunction;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufHolder;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.FileRegion;
import io.netty.util.ReferenceCountUtil;
import reactor.core.publisher.Mono;
import reactor.netty.ReactorNetty;

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

	@SuppressWarnings("unchecked")
	static <O> ToIntFunction<O> defaultSizeOf() {
		return (ToIntFunction) SIZE_OF;
	}

	static final int                    MAX_SIZE    = 128;

	static final int                    REFILL_SIZE = MAX_SIZE / 2;

	static final Function<ByteBuf, ByteBuf> TRANSFORMATION_FUNCTION_BB =
		msg -> {
			if (ReactorNetty.PREDICATE_GROUP_FLUSH.test(msg)) {
				return null;
			}
			return msg;
		};

	static final Function<Object, Object>   TRANSFORMATION_FUNCTION    = Function.identity();

	static final Consumer<ByteBuf> CONSUMER_BB_NOCHECK_CLEANUP = ByteBuf::release;

	static final Consumer<Object>  CONSUMER_NOCHECK_CLEANUP    = ReferenceCountUtil::release;

	static final ToIntFunction<ByteBuf> SIZE_OF_BB  = ByteBuf::readableBytes;

	static final ToIntFunction<Object>  SIZE_OF     = msg -> {
		if (msg instanceof ByteBufHolder) {
			return ((ByteBufHolder) msg).content()
			                            .readableBytes();
		}
		if (msg instanceof ByteBuf) {
			return ((ByteBuf) msg).readableBytes();
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
