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
package reactor.netty.examples.documentation.http.client.spnego;

import reactor.netty.http.client.HttpClient;
import reactor.netty.http.client.JaasAuthenticator;
import reactor.netty.http.client.SpnegoAuthProvider;
import reactor.netty.http.client.SpnegoAuthenticator;

public class Application {

	public static void main(String[] args) {
		System.setProperty("java.security.auth.login.config", "/path/to/jaas.conf"); // <1>
		System.setProperty("java.security.krb5.conf", "/path/to/krb5.conf"); // <2>
		System.setProperty("sun.security.krb5.debug", "true"); // <3>

		SpnegoAuthenticator authenticator = new JaasAuthenticator("KerberosLogin"); // <4>
		HttpClient client = HttpClient.create()
				.spnego(SpnegoAuthProvider.create(authenticator, 401)); // <5>

		client.get()
				.uri("http://protected.example.com/")
				.responseSingle((res, content) -> content.asString())
				.block();
	}
}
