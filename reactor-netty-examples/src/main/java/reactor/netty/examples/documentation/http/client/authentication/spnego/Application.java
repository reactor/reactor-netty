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
package reactor.netty.examples.documentation.http.client.authentication.spnego;

import io.netty.handler.codec.http.HttpHeaderNames;
import org.ietf.jgss.GSSContext;
import org.ietf.jgss.GSSException;
import org.ietf.jgss.GSSManager;
import org.ietf.jgss.GSSName;
import org.ietf.jgss.Oid;
import reactor.core.publisher.Mono;
import reactor.netty.http.client.HttpClient;

import java.net.InetSocketAddress;
import java.util.Base64;

public class Application {

	public static void main(String[] args) {
		HttpClient client =
				HttpClient.create()
				          // This is a simplified example demonstrating how the HTTP authentication API
				          // can be used for SPNEGO/Kerberos.
				          .httpAuthenticationWhen(
				              (req, res) -> res.status().code() == 401 && // <1>
				                            res.responseHeaders().contains("WWW-Authenticate", "Negotiate", true),
				              (req, addr) -> { // <2>
				                  try {
				                      GSSManager manager = GSSManager.getInstance();
				                      String hostName = ((InetSocketAddress) addr).getHostString();
				                      String serviceName = "HTTP@" + hostName;
				                      GSSName serverName = manager.createName(serviceName, GSSName.NT_HOSTBASED_SERVICE);

				                      Oid krb5Mechanism = new Oid("1.2.840.113554.1.2.2");
				                      GSSContext context = manager.createContext(
				                          serverName, krb5Mechanism, null, GSSContext.DEFAULT_LIFETIME);

				                      byte[] token = context.initSecContext(new byte[0], 0, 0);
				                      String encodedToken = Base64.getEncoder().encodeToString(token);

				                      req.header(HttpHeaderNames.AUTHORIZATION, "Negotiate " + encodedToken);

				                      context.dispose();
				                  }
				                  catch (GSSException e) {
				                      return Mono.error(new RuntimeException(
				                          "Failed to generate SPNEGO token", e));
				                  }
				                  return Mono.empty();
				              },
				              2 // <3>
				          );

		client.get()
		      .uri("https://example.com/")
		      .response()
		      .block();
	}
}