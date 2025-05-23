/*
 * Copyright (c) 2025 VMware, Inc. or its affiliates, All Rights Reserved.
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
package reactor.netty.http.server.logging.error;

import org.jspecify.annotations.Nullable;

import java.net.SocketAddress;
import java.util.function.Supplier;

/**
 * A provider of the args required for error log.
 *
 * @author raccoonback
 * @author Violeta Georgieva
 */
abstract class AbstractErrorLogArgProvider<SELF extends AbstractErrorLogArgProvider<SELF>>
		implements ErrorLogArgProvider, Supplier<SELF> {

	final @Nullable SocketAddress remoteAddress;

	AbstractErrorLogArgProvider(@Nullable SocketAddress remoteAddress) {
		this.remoteAddress = remoteAddress;
	}

	@Override
	public @Nullable SocketAddress remoteAddress() {
		return remoteAddress;
	}
}
