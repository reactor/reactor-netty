/*
 * Copyright (c) 2021-2023 VMware, Inc. or its affiliates, All Rights Reserved.
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
package reactor.netty.http.server;

import io.netty.handler.codec.http.HttpMethod;
import reactor.util.annotation.Nullable;

import java.util.Comparator;

/**
 * Provides the metadata that a given handler can handle.
 * Used with the {@link HttpServerRoutes#comparator(Comparator)}.
 *
 * @author chentong
 * @since 1.0.7
 */
public interface HttpRouteHandlerMetadata {

	/**
	 * Get the http path this handler can handle.
	 *
	 * @return the http path
	 */
	@Nullable
	String getPath();

	/**
	 * Get the http method this handler can handle.
	 *
	 * @return the http method {@link HttpMethod}
	 * @since 1.0.11
	 */
	@Nullable
	HttpMethod getMethod();
}
