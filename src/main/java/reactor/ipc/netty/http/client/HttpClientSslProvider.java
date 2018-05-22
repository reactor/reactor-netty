/*
 * Copyright (c) 2011-2018 Pivotal Software Inc, All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package reactor.ipc.netty.http.client;

import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLParameters;

import io.netty.handler.ssl.SslHandler;
import reactor.ipc.netty.tcp.SslProvider;

/**
 * @author Stephane Maldini
 */
final class HttpClientSslProvider extends SslProvider {

	HttpClientSslProvider(SslProvider.Build builder) {
		super(builder);
	}

	@Override
	public void configure(SslHandler handler) {
		SSLEngine sslEngine = handler.engine();
		SSLParameters sslParameters = sslEngine.getSSLParameters();
		sslParameters.setEndpointIdentificationAlgorithm("HTTPS");
		sslEngine.setSSLParameters(sslParameters);
	}

	static final class HttpBuild extends Build {

		@Override
		public SslProvider build() {
			return new HttpClientSslProvider(this);
		}
	}
}
