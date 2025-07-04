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
package reactor.netty.http.client;

import static reactor.core.scheduler.Schedulers.boundedElastic;

import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpHeaders;
import java.net.InetSocketAddress;
import java.security.PrivilegedAction;
import java.util.Base64;
import javax.security.auth.Subject;
import javax.security.auth.login.LoginException;
import org.ietf.jgss.GSSContext;
import org.ietf.jgss.GSSException;
import org.ietf.jgss.GSSManager;
import org.ietf.jgss.GSSName;
import org.ietf.jgss.Oid;
import reactor.core.publisher.Mono;

/**
 * Provides SPNEGO authentication for Reactor Netty HttpClient.
 * <p>
 * This provider is responsible for generating and attaching a SPNEGO (Kerberos) token
 * to the HTTP Authorization header for outgoing requests, enabling single sign-on and
 * secure authentication in enterprise environments.
 * </p>
 *
 * <p>Typical usage:</p>
 * <pre>
 *     HttpClient client = HttpClient.create()
 *         .spnego(SpnegoAuthProvider.create(new JaasAuthenticator("KerberosLogin")));
 * </pre>
 *
 * @author raccoonback
 * @since 1.3.0
 */
public final class SpnegoAuthProvider {

	private static final String SPNEGO_HEADER = "Negotiate";
	private static final String STR_OID = "1.3.6.1.5.5.2";

	private final SpnegoAuthenticator authenticator;
	private final GSSManager gssManager;
	private final int unauthorizedStatusCode;

	private volatile String verifiedAuthHeader;

	/**
	 * Constructs a new SpnegoAuthProvider with the given authenticator and GSSManager.
	 *
	 * @param authenticator the authenticator to use for JAAS login
	 * @param gssManager the GSSManager to use for SPNEGO token generation
	 */
	private SpnegoAuthProvider(SpnegoAuthenticator authenticator, GSSManager gssManager, int unauthorizedStatusCode) {
		this.authenticator = authenticator;
		this.gssManager = gssManager;
		this.unauthorizedStatusCode = unauthorizedStatusCode;
	}

	/**
	 * Creates a new SPNEGO authentication provider using the default GSSManager instance.
	 *
	 * @param authenticator the authenticator to use for JAAS login
	 * @param unauthorizedStatusCode the HTTP status code that indicates authentication failure
	 * @return a new SPNEGO authentication provider
	 */
	public static SpnegoAuthProvider create(SpnegoAuthenticator authenticator, int unauthorizedStatusCode) {
		return create(authenticator, GSSManager.getInstance(), unauthorizedStatusCode);
	}

	/**
	 * Creates a new SPNEGO authentication provider with a custom GSSManager instance.
	 * <p>
	 * This overload is intended for testing or advanced scenarios where a custom GSSManager is needed.
	 * </p>
	 *
	 * @param authenticator the authenticator to use for JAAS login
	 * @param gssManager the GSSManager to use for SPNEGO token generation
	 * @param unauthorizedStatusCode the HTTP status code that indicates authentication failure
	 * @return a new SPNEGO authentication provider
	 */
	public static SpnegoAuthProvider create(SpnegoAuthenticator authenticator, GSSManager gssManager, int unauthorizedStatusCode) {
		return new SpnegoAuthProvider(authenticator, gssManager, unauthorizedStatusCode);
	}

	/**
	 * Applies SPNEGO authentication to the given HTTP client request.
	 * <p>
	 * This method generates a SPNEGO token for the specified address and attaches it
	 * as an Authorization header to the outgoing HTTP request.
	 * </p>
	 *
	 * @param request the HTTP client request to authenticate
	 * @param address the target server address (used for service principal)
	 * @return a Mono that completes when the authentication is applied
	 * @throws RuntimeException if login or token generation fails
	 */
	public Mono<Void> apply(HttpClientRequest request, InetSocketAddress address) {
		if (verifiedAuthHeader != null) {
			request.header(HttpHeaderNames.AUTHORIZATION, verifiedAuthHeader);
			return Mono.empty();
		}

		return Mono.fromCallable(() -> {
				try {
					return Subject.doAs(
						authenticator.login(),
						(PrivilegedAction<byte[]>) () -> {
							try {
								byte[] token = generateSpnegoToken(address.getHostName());
								String authHeader = SPNEGO_HEADER + " " + Base64.getEncoder().encodeToString(token);

								verifiedAuthHeader = authHeader;
								request.header(HttpHeaderNames.AUTHORIZATION, authHeader);
								return token;
							}
							catch (GSSException e) {
								throw new RuntimeException("Failed to generate SPNEGO token", e);
							}
						}
					);
				}
				catch (LoginException e) {
					throw new RuntimeException("Failed to login with SPNEGO", e);
				}
			})
			.subscribeOn(boundedElastic())
			.then();
	}

	/**
	 * Generates a SPNEGO token for the given host name.
	 * <p>
	 * This method uses the GSSManager to create a GSSContext and generate a SPNEGO token
	 * for the specified service principal (HTTP/hostName).
	 * </p>
	 *
	 * @param hostName the target server host name
	 * @return the raw SPNEGO token bytes
	 * @throws GSSException if token generation fails
	 */
	private byte[] generateSpnegoToken(String hostName) throws GSSException {
		GSSName serverName = gssManager.createName("HTTP/" + hostName, GSSName.NT_HOSTBASED_SERVICE);
		Oid spnegoOid = new Oid(STR_OID); // SPNEGO OID

		GSSContext context = gssManager.createContext(serverName, spnegoOid, null, GSSContext.DEFAULT_LIFETIME);
		try {
			return context.initSecContext(new byte[0], 0, 0);
		} finally {
			context.dispose();
		}
	}

	/**
	 * Invalidates the cached authentication token.
	 * <p>
	 * This method should be called when a response indicates that the current token
	 * is no longer valid (typically after receiving an unauthorized status code).
	 * The next request will generate a new authentication token.
	 * </p>
	 */
	public void invalidateCache() {
		this.verifiedAuthHeader = null;
	}

	/**
	 * Checks if the response indicates an authentication failure that requires a new token.
	 * <p>
	 * This method checks both the status code and the WWW-Authenticate header to determine
	 * if a new SPNEGO token needs to be generated.
	 * </p>
	 *
	 * @param status the HTTP status code
	 * @param headers the HTTP response headers
	 * @return true if the response indicates an authentication failure
	 */
	public boolean isUnauthorized(int status, HttpHeaders headers) {
		if (status != unauthorizedStatusCode) {
			return false;
		}

		String header = headers.get(HttpHeaderNames.WWW_AUTHENTICATE);
		return header != null && header.startsWith(SPNEGO_HEADER);
	}
}
