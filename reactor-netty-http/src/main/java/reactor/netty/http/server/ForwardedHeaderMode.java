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
package reactor.netty.http.server;

/**
 * Specifies which forwarded request headers an {@link HttpServer} uses for deriving
 * information about a connection.
 *
 * @author Arnab Nandy
 * @since 1.4.0
 */
public enum ForwardedHeaderMode {

	/**
	 * Use the {@code "Forwarded"} header when present, otherwise use the
	 * {@code "X-Forwarded-*"} headers.
	 */
	AUTO,

	/**
	 * Use only the {@code "Forwarded"} header.
	 */
	FORWARDED,

	/**
	 * Use only the {@code "X-Forwarded-*"} headers.
	 */
	X_FORWARDED
}
