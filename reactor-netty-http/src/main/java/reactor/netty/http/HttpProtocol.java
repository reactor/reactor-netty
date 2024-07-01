/*
 * Copyright (c) 2018-2024 VMware, Inc. or its affiliates, All Rights Reserved.
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
package reactor.netty.http;

import reactor.util.annotation.Incubating;

/**
 * An enum defining various HTTP negotiations between H2, H2C-upgrade,
 * H2C-prior-knowledge and HTTP/1.1.
 *
 * @author Stephane Maldini
 */
public enum HttpProtocol {

	/**
	 * The default supported HTTP protocol by HttpServer and HttpClient.
	 */
	HTTP11,

	/**
	 * HTTP/2.0 support with TLS
	 * <p>If used along with HTTP/1.1 protocol, HTTP/2.0 will be the preferred protocol.
	 * While negotiating the application level protocol, HTTP/2.0 or HTTP/1.1 can be chosen.
	 * <p>If used without HTTP/1.1 protocol, HTTP/2.0 will always be offered as a protocol
	 * for communication with no fallback to HTTP/1.1.
	 */
	H2,

	/**
	 * HTTP/2.0 support with clear-text.
	 * <p>If used along with HTTP/1.1 protocol, will support H2C "upgrade":
	 * Request or consume requests as HTTP/1.1 first, looking for HTTP/2.0 headers
	 * and {@literal Connection: Upgrade}. A server will typically reply a successful
	 * 101 status if upgrade is successful or a fallback HTTP/1.1 response. When
	 * successful the client will start sending HTTP/2.0 traffic.
	 * <p>If used without HTTP/1.1 protocol, will support H2C "prior-knowledge": Doesn't
	 * require {@literal Connection: Upgrade} handshake between a client and server but
	 * fallback to HTTP/1.1 will not be supported.
	 */
	H2C,

	/**
	 * HTTP/3.0 support.
	 * @since 1.2.0
	 */
	@Incubating
	HTTP3
}
