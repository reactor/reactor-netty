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

import java.util.Objects;
import org.ietf.jgss.GSSContext;
import org.ietf.jgss.GSSCredential;
import org.ietf.jgss.GSSManager;
import org.ietf.jgss.GSSName;
import org.ietf.jgss.Oid;

/**
 * A GSSCredential-based Authenticator implementation for use with SPNEGO providers.
 * <p>
 * This authenticator uses a pre-existing GSSCredential to create a GSSContext,
 * bypassing the need for JAAS login configuration. This is useful when you already
 * have obtained Kerberos credentials through other means or want more direct control
 * over the authentication process.
 * </p>
 * <p>
 * The GSSCredential should contain valid Kerberos credentials that can be used
 * for SPNEGO authentication. The credential's lifetime and validity are managed
 * externally to this authenticator.
 * </p>
 *
 * <p>Example usage:</p>
 * <pre>
 *     GSSCredential credential = // ... obtain credential
 *     GssCredentialAuthenticator authenticator = new GssCredentialAuthenticator(credential);
 *     SpnegoAuthProvider provider = SpnegoAuthProvider.builder(authenticator).build();
 * </pre>
 *
 * @author raccoonback
 * @since 1.3.0
 */
public class GssCredentialAuthenticator implements SpnegoAuthenticator {

	private final GSSCredential credential;

	/**
	 * Creates a new GssCredentialAuthenticator with the given GSSCredential.
	 *
	 * @param credential the GSSCredential to use for authentication
	 */
	public GssCredentialAuthenticator(GSSCredential credential) {
		Objects.requireNonNull(credential, "GSSCredential cannot be null");
		this.credential = credential;
	}

	/**
	 * Creates a GSSContext for the specified service and remote host using the provided GSSCredential.
	 * <p>
	 * This method uses the pre-existing GSSCredential to create a GSSContext for SPNEGO
	 * authentication. The service principal name is constructed as serviceName/remoteHost.
	 * </p>
	 *
	 * @param serviceName the service name (e.g., "HTTP", "FTP")
	 * @param remoteHost the remote host to authenticate with
	 * @return the created GSSContext configured for SPNEGO authentication
	 * @throws Exception if context creation fails
	 */
	@Override
	public GSSContext createContext(String serviceName, String remoteHost) throws Exception {
		GSSManager manager = GSSManager.getInstance();
		GSSName serverName = manager.createName(serviceName + "/" + remoteHost, GSSName.NT_HOSTBASED_SERVICE);
		GSSContext context = manager.createContext(
				serverName,
				new Oid("1.3.6.1.5.5.2"),
				credential,
				GSSContext.DEFAULT_LIFETIME
		);
		context.requestMutualAuth(true);
		return context;
	}
}