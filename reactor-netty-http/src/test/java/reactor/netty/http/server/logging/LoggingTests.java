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
package reactor.netty.http.server.logging;

import io.netty.handler.codec.http.HttpHeaderNames;

import java.nio.charset.StandardCharsets;

/**
 * Common constants for access log tests.
 *
 * @author limaoning
 */
class LoggingTests {

	static final CharSequence HEADER_CONNECTION_NAME = HttpHeaderNames.CONNECTION;
	static final String HEADER_CONNECTION_VALUE = "keep-alive";
	static final String URI = "/hello";
	static final byte[] RESPONSE_CONTENT = "Hello".getBytes(StandardCharsets.UTF_8);

}
