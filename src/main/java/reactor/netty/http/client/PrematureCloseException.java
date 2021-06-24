/*
 * Copyright (c) 2011-Present VMware, Inc. or its affiliates, All Rights Reserved.
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
 * {@link ChannelOperations#onInboundError()}
 *
 * @author Violeta Georgieva
 */
public final class PrematureCloseException extends IOException {

	/**
	 * @deprecated Should not be singleton
	 */
	@Deprecated
	public static final PrematureCloseException BEFORE_RESPONSE_SENDING_REQUEST =
			new PrematureCloseException("Connection has been closed BEFORE response, while sending request body");

	/**
	 * @deprecated Should not be singleton
	 */
	@Deprecated
	public static final PrematureCloseException BEFORE_RESPONSE =
			new PrematureCloseException("Connection prematurely closed BEFORE response");

	/**
	 * @deprecated Should not be singleton
	 */
	@Deprecated
	public static final PrematureCloseException DURING_RESPONSE =
			new PrematureCloseException("Connection prematurely closed DURING response");

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

	private static final long serialVersionUID = -8551235699066818521L;
}
