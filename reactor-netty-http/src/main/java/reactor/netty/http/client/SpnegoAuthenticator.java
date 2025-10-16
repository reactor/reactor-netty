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

import org.ietf.jgss.GSSContext;

/**
 * An abstraction for authentication logic used by SPNEGO providers.
 * <p>
 * Implementations are responsible for creating a GSSContext for the specified remote host.
 * </p>
 *
 * @author raccoonback
 * @since 1.3.0
 */
public interface SpnegoAuthenticator {

	/**
	 * Creates a GSSContext for the specified remote host.
	 *
	 * @param serviceName the service name (e.g., "HTTP", "FTP")
	 * @param remoteHost the remote host to authenticate with
	 * @return the created GSSContext
	 * @throws Exception if context creation fails
	 */
	GSSContext createContext(String serviceName, String remoteHost) throws Exception;
}
