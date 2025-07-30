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

import java.security.PrivilegedExceptionAction;
import javax.security.auth.Subject;
import javax.security.auth.login.LoginContext;
import org.ietf.jgss.GSSContext;
import org.ietf.jgss.GSSManager;
import org.ietf.jgss.GSSName;
import org.ietf.jgss.Oid;

/**
 * A JAAS-based Authenticator implementation for use with SPNEGO providers.
 * <p>
 * This authenticator performs a JAAS login using the specified context name and creates a GSSContext
 * for SPNEGO authentication. It relies on JAAS configuration to obtain Kerberos credentials.
 * </p>
 * <p>
 * The JAAS configuration should define a login context that acquires Kerberos credentials,
 * typically using the Krb5LoginModule. The login context name provided to this authenticator
 * must match the entry name in the JAAS configuration file.
 * </p>
 *
 * <p>Example usage:</p>
 * <pre>
 *     JaasAuthenticator authenticator = new JaasAuthenticator("KerberosLogin");
 *     SpnegoAuthProvider provider = SpnegoAuthProvider.builder(authenticator).build();
 * </pre>
 *
 * @author raccoonback
 * @since 1.3.0
 */
public class JaasAuthenticator implements SpnegoAuthenticator {

	private final String loginContext;

	/**
	 * Creates a new JaasAuthenticator with the given context name.
	 *
	 * @param loginContext the JAAS login context name
	 */
	public JaasAuthenticator(String loginContext) {
		this.loginContext = loginContext;
	}

	/**
	 * Creates a GSSContext for the specified service and remote host using JAAS authentication.
	 * <p>
	 * This method performs a JAAS login, obtains the authenticated Subject, and creates
	 * a GSSContext within the Subject's security context. The service principal name
	 * is constructed as serviceName/remoteHost.
	 * </p>
	 *
	 * @param serviceName the service name (e.g., "HTTP", "CIFS")
	 * @param remoteHost the remote host to authenticate with
	 * @return the created GSSContext configured for SPNEGO authentication
	 * @throws Exception if JAAS login or context creation fails
	 */
	@Override
	public GSSContext createContext(String serviceName, String remoteHost) throws Exception {
		LoginContext lc = new LoginContext(loginContext);
		lc.login();
		Subject subject = lc.getSubject();

		return Subject.doAs(subject, (PrivilegedExceptionAction<GSSContext>) () -> {
			GSSManager manager = GSSManager.getInstance();
			GSSName serverName = manager.createName(serviceName + "/" + remoteHost, GSSName.NT_HOSTBASED_SERVICE);
			GSSContext context = manager.createContext(
					serverName,
					new Oid("1.3.6.1.5.5.2"), // SPNEGO
					null,
					GSSContext.DEFAULT_LIFETIME
			);
			context.requestMutualAuth(true);
			return context;
		});
	}
}
