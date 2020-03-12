/*
 * Copyright (c) 2011-Present VMware, Inc. or its affiliates, All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *       https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package reactor.netty.channel;

import javax.annotation.Nullable;
import java.util.Objects;

/**
 * @author Violeta Georgieva
 * @since 0.9.3
 */
public final class MeterKey {

	private final String uri;
	private final String remoteAddress;
	private final String method;
	private final String status;

	public MeterKey(@Nullable String uri, @Nullable String remoteAddress,
			@Nullable String method, @Nullable String status) {
		this.uri = uri;
		this.remoteAddress = remoteAddress;
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
				Objects.equals(method, meterKey.method) &&
				Objects.equals(status, meterKey.status);
	}

	@Override
	public int hashCode() {
		return Objects.hash(uri, remoteAddress, method, status);
	}
}
