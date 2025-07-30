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
import java.util.Arrays;
import java.util.Base64;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.ietf.jgss.GSSContext;
import org.ietf.jgss.GSSException;
import reactor.core.publisher.Mono;
import reactor.util.Logger;
import reactor.util.Loggers;

/**
 * Provides SPNEGO authentication for Reactor Netty HttpClient.
 * <p>
 * This provider is responsible for generating and attaching a SPNEGO (Kerberos) token
 * to the HTTP Authorization header for outgoing requests, enabling single sign-on and
 * secure authentication in enterprise environments.
 * </p>
 * <p>
 * The provider supports authentication caching and retry mechanisms to handle token
 * expiration and authentication failures gracefully. It can be configured with different
 * service names, unauthorized status codes, and hostname resolution strategies.
 * </p>
 *
 * <p>Basic usage with JAAS:</p>
 * <pre>
 *     SpnegoAuthProvider provider = SpnegoAuthProvider
 *         .builder(new JaasAuthenticator("KerberosLogin"))
 *         .build();
 *
 *     HttpClient client = HttpClient.create()
 *         .spnego(provider);
 * </pre>
 *
 * <p>Advanced configuration:</p>
 * <pre>
 *     SpnegoAuthProvider provider = SpnegoAuthProvider
 *         .builder(new GssCredentialAuthenticator(credential))
 *         .serviceName("CIFS")
 *         .unauthorizedStatusCode(401)
 *         .resolveCanonicalHostname(true)
 *         .build();
 * </pre>
 *
 * @author raccoonback
 * @since 1.3.0
 */
public final class SpnegoAuthProvider {

	private static final Logger log = Loggers.getLogger(SpnegoAuthProvider.class);
	private static final String SPNEGO_HEADER = "Negotiate";

	private final SpnegoAuthenticator authenticator;
	private final int unauthorizedStatusCode;
	private final String serviceName;
	private final boolean resolveCanonicalHostname;

	private final AtomicReference<String> verifiedAuthHeader = new AtomicReference<>();
	private final AtomicInteger retryCount = new AtomicInteger(0);
	private static final int MAX_RETRY_COUNT = 1;

	/**
	 * Constructs a new SpnegoAuthProvider with the specified configuration.
	 * <p>
	 * This constructor is private and should only be used by the {@link Builder}.
	 * Use {@link #builder(SpnegoAuthenticator)} to create instances.
	 * </p>
	 *
	 * @param authenticator the authenticator to use for SPNEGO authentication (must not be null)
	 * @param unauthorizedStatusCode the HTTP status code that indicates authentication failure
	 * @param serviceName the service name for constructing service principal names
	 * @param resolveCanonicalHostname whether to resolve canonical hostnames for service principals
	 */
	private SpnegoAuthProvider(SpnegoAuthenticator authenticator, int unauthorizedStatusCode, String serviceName, boolean resolveCanonicalHostname) {
		this.authenticator = authenticator;
		this.unauthorizedStatusCode = unauthorizedStatusCode;
		this.serviceName = serviceName;
		this.resolveCanonicalHostname = resolveCanonicalHostname;
	}

	/**
	 * Creates a new builder for configuring SPNEGO authentication provider.
	 *
	 * @param authenticator the authenticator to use for SPNEGO authentication
	 * @return a new builder instance
	 */
	public static Builder builder(SpnegoAuthenticator authenticator) {
		return new Builder(authenticator);
	}

	/**
	 * Builder for creating SpnegoAuthProvider instances.
	 */
	public static final class Builder {
		private final SpnegoAuthenticator authenticator;
		private int unauthorizedStatusCode = 401;
		private String serviceName = "HTTP";
		private boolean resolveCanonicalHostname;

		private Builder(SpnegoAuthenticator authenticator) {
			this.authenticator = authenticator;
		}

		/**
		 * Sets the HTTP status code that indicates authentication failure.
		 *
		 * @param statusCode the status code (default: 401)
		 * @return this builder
		 */
		public Builder unauthorizedStatusCode(int statusCode) {
			this.unauthorizedStatusCode = statusCode;
			return this;
		}

		/**
		 * Sets the service name for the service principal.
		 *
		 * @param serviceName the service name (default: "HTTP")
		 * @return this builder
		 */
		public Builder serviceName(String serviceName) {
			this.serviceName = serviceName;
			return this;
		}

		/**
		 * Sets whether to resolve canonical hostname.
		 *
		 * @param resolveCanonicalHostname true to resolve canonical hostname (default: false)
		 * @return this builder
		 */
		public Builder resolveCanonicalHostname(boolean resolveCanonicalHostname) {
			this.resolveCanonicalHostname = resolveCanonicalHostname;
			return this;
		}

		/**
		 * Builds the SpnegoAuthProvider instance.
		 *
		 * @return a new SpnegoAuthProvider
		 */
		public SpnegoAuthProvider build() {
			return new SpnegoAuthProvider(authenticator, unauthorizedStatusCode, serviceName, resolveCanonicalHostname);
		}
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
	 * @throws SpnegoAuthenticationException if login or token generation fails
	 */
	public Mono<Void> apply(HttpClientRequest request, InetSocketAddress address) {
		String cachedToken = verifiedAuthHeader.get();
		if (cachedToken != null) {
			request.header(HttpHeaderNames.AUTHORIZATION, cachedToken);
			return Mono.empty();
		}

		return Mono.fromCallable(() -> {
				try {
					String hostName = resolveHostName(address);
					byte[] token = generateSpnegoToken(hostName);
					String authHeader = SPNEGO_HEADER + " " + Base64.getEncoder().encodeToString(token);

					verifiedAuthHeader.set(authHeader);
					request.header(HttpHeaderNames.AUTHORIZATION, authHeader);
					return token;
				}
				catch (Exception e) {
					throw new SpnegoAuthenticationException("Failed to generate SPNEGO token", e);
				}
			})
			.subscribeOn(boundedElastic())
			.then();
	}

	/**
	 * Resolves the hostname from the given socket address.
	 * <p>
	 * This method returns either the hostname or canonical hostname based on the
	 * {@code resolveCanonicalHostname} configuration. When canonical hostname resolution
	 * is enabled, it performs a reverse DNS lookup to get the fully qualified domain name.
	 * </p>
	 *
	 * @param address the socket address to resolve hostname from
	 * @return the resolved hostname (canonical if configured, otherwise standard hostname)
	 */
	private String resolveHostName(InetSocketAddress address) {
		String hostName = address.getHostName();
		if (resolveCanonicalHostname) {
			hostName = address.getAddress().getCanonicalHostName();
		}
		return hostName;
	}

	/**
	 * Generates a SPNEGO token for the given host name.
	 * <p>
	 * This method uses the authenticator to create a GSSContext and generate a SPNEGO token
	 * for the specified service principal (HTTP/hostName).
	 * </p>
	 *
	 * @param hostName the target server host name
	 * @return the raw SPNEGO token bytes
	 * @throws Exception if token generation fails
	 */
	private byte[] generateSpnegoToken(String hostName) throws Exception {
		if (hostName == null || hostName.trim().isEmpty()) {
			throw new IllegalArgumentException("Host name cannot be null or empty");
		}

		GSSContext context = null;
		try {
			context = authenticator.createContext(serviceName, hostName.trim());
			return context.initSecContext(new byte[0], 0, 0);
		}
		finally {
			if (context != null) {
				try {
					context.dispose();
				}
				catch (GSSException e) {
					// Log but don't propagate disposal errors
					if (log.isDebugEnabled()) {
						log.debug("Failed to dispose GSSContext", e);
					}
				}
			}
		}
	}

	/**
	 * Invalidates the cached authentication token.
	 */
	public void invalidateTokenHeader() {
		this.verifiedAuthHeader.set(null);
	}

	/**
	 * Checks if SPNEGO authentication retry is allowed.
	 *
	 * @return true if retry is allowed, false otherwise
	 */
	public boolean canRetry() {
		return retryCount.get() < MAX_RETRY_COUNT;
	}

	/**
	 * Increments the retry count for SPNEGO authentication attempts.
	 */
	public void incrementRetryCount() {
		retryCount.incrementAndGet();
	}

	/**
	 * Resets the retry count for SPNEGO authentication.
	 */
	public void resetRetryCount() {
		retryCount.set(0);
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
		if (header == null) {
			return false;
		}

		return Arrays.stream(header.split(","))
			.map(String::trim)
			.anyMatch(auth -> auth.toLowerCase().startsWith(SPNEGO_HEADER.toLowerCase()));
	}
}
