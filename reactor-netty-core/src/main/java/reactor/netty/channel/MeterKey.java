/*
 * Copyright (c) 2020-2024 VMware, Inc. or its affiliates, All Rights Reserved.
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
package reactor.netty.channel;

import reactor.util.annotation.Nullable;

import java.util.Objects;

/**
 * Contains meters' tags values. Used as a key when caching meters.
 *
 * @author Violeta Georgieva
 * @since 0.9.3
 */
public final class MeterKey {

	private final String uri;
	private final String remoteAddress;
	private final String proxyAddress;
	private final String method;
	private final String status;

	/**
	 * Creates a new meter key.
	 *
	 * @param uri the requested URI
	 * @param remoteAddress the remote address
	 * @param method the HTTP method
	 * @param status the HTTP status
	 * @deprecated as of 1.1.17. Prefer using {@link #MeterKey(String, String, String, String, String)} constructor.
	 * This method will be removed in version 1.3.0.
	 */
	@Deprecated
	public MeterKey(@Nullable String uri, @Nullable String remoteAddress,
			@Nullable String method, @Nullable String status) {
		this(uri, remoteAddress, null, method, status);
	}

	/**
	 * Creates a new meter key.
	 *
	 * @param uri the requested URI
	 * @param remoteAddress the remote address
	 * @param proxyAddress the proxy address
	 * @param method the HTTP method
	 * @param status the HTTP status
	 * @since 1.1.17
	 */
	public MeterKey(@Nullable String uri, @Nullable String remoteAddress, @Nullable String proxyAddress,
			@Nullable String method, @Nullable String status) {
		this.uri = uri;
		this.remoteAddress = remoteAddress;
		this.proxyAddress = proxyAddress;
		this.method = method;
		this.status = status;
	}

	@Override
	public boolean equals(Object o) {
		if (this == o) {
			return true;
		}
		if (o == null || getClass() != o.getClass()) {
			return false;
		}
		MeterKey meterKey = (MeterKey) o;
		return Objects.equals(uri, meterKey.uri) &&
				Objects.equals(remoteAddress, meterKey.remoteAddress) &&
				Objects.equals(proxyAddress, meterKey.proxyAddress) &&
				Objects.equals(method, meterKey.method) &&
				Objects.equals(status, meterKey.status);
	}

	@Override
	public int hashCode() {
		int result = 1;
		result = 31 * result + Objects.hashCode(uri);
		result = 31 * result + Objects.hashCode(remoteAddress);
		result = 31 * result + Objects.hashCode(proxyAddress);
		result = 31 * result + Objects.hashCode(method);
		result = 31 * result + Objects.hashCode(status);
		return result;
	}
}
