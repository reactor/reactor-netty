/*
 * Copyright (c) 2017-2021 VMware, Inc. or its affiliates, All Rights Reserved.
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
package reactor.netty.tcp;

import reactor.util.Logger;
import reactor.util.Loggers;

/**
 * Initializes the default {@link SslProvider} for the TCP client.
 *
 * @author Stephane Maldini
 * @author Violeta Georgieva
 */
final class TcpClientSecure {

	private static final Logger log = Loggers.getLogger(TcpClientSecure.class);

	static final SslProvider DEFAULT_SSL_PROVIDER;

	static {
		SslProvider sslProvider;
		try {
			sslProvider =
					SslProvider.builder()
					           .sslContext(TcpSslContextSpec.forClient())
					           .build();
		}
		catch (Exception e) {
			log.error("Failed to build default ssl provider", e);
			sslProvider = null;
		}
		DEFAULT_SSL_PROVIDER = sslProvider;
	}
}
