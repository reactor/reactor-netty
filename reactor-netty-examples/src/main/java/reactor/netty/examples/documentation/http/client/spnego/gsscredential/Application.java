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
package reactor.netty.examples.documentation.http.client.spnego.gsscredential;

import org.ietf.jgss.GSSCredential;
import reactor.netty.http.client.GssCredentialAuthenticator;
import reactor.netty.http.client.HttpClient;
import reactor.netty.http.client.SpnegoAuthProvider;

public class Application {

	public static void main(String[] args) {
		// Assuming you have obtained a GSSCredential from elsewhere
		GSSCredential credential = obtainGSSCredential(); // <1>

		HttpClient client = HttpClient.create()
				.spnego(
						SpnegoAuthProvider.builder(new GssCredentialAuthenticator(credential)) // <2>
								.build()
				);

		client.get()
				.uri("http://protected.example.com/")
				.responseSingle((res, content) -> content.asString())
				.block();
	}

	private static GSSCredential obtainGSSCredential() {
		// Implement your logic to obtain a GSSCredential
		// This could involve using a Kerberos library or other means
		return null;
	}
}
