/*
 * Copyright (c) 2020-2025 VMware, Inc. or its affiliates, All Rights Reserved.
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
package reactor.netty.http.server.logging;

import io.netty.channel.ChannelHandler;
import org.jspecify.annotations.Nullable;

import java.util.function.Function;

/**
 * Use to create an access-log handler.
 *
 * @author limaoning
 */
public enum AccessLogHandlerFactory {

	/**
	 * HTTP/1.1.
	 */
	H1,
	/**
	 * HTTP/2.0.
	 */
	H2,
	/**
	 * HTTP/3.0.
	 */
	H3;

	/**
	 * Create an access log handler, {@link AccessLogHandlerH1} or {@link AccessLogHandlerH2}.
	 *
	 * @param accessLog apply an {@link AccessLog} by an {@link AccessLogArgProvider}
	 * @return the access log handler
	 */
	public ChannelHandler create(@Nullable Function<AccessLogArgProvider, @Nullable AccessLog> accessLog) {
		switch (this) {
			case H3:
				return new AccessLogHandlerH3(accessLog);
			case H2:
				return new AccessLogHandlerH2(accessLog);
			case H1:
			default:
				return new AccessLogHandlerH1(accessLog);
		}
	}

}
