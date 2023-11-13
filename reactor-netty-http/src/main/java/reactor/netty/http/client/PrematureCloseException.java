/*
 * Copyright (c) 2019-2023 VMware, Inc. or its affiliates, All Rights Reserved.
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
package reactor.netty.http.client;

import io.netty.channel.ChannelInboundHandler;
import reactor.netty.channel.ChannelOperations;

import java.io.IOException;

/**
 * An error for signalling that the connection was closed prematurely
 * {@link ChannelInboundHandler#channelInactive(io.netty.channel.ChannelHandlerContext)},
 * {@link ChannelOperations#onInboundClose()},
 * {@link ChannelOperations#onInboundError()}.
 *
 * @author Violeta Georgieva
 */
public final class PrematureCloseException extends IOException {

	public static final PrematureCloseException TEST_EXCEPTION =
			new PrematureCloseException("Simulated prematurely closed connection");

	PrematureCloseException(String message) {
		super(message);
	}

	PrematureCloseException(Throwable throwable) {
		super(throwable);
	}

	@Override
	public synchronized Throwable fillInStackTrace() {
		// omit stacktrace for this exception
		return this;
	}

	private static final long serialVersionUID = -3569621032752341973L;
}
