/*
 * Copyright (c) 2011-Present Pivotal Software Inc, All Rights Reserved.
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

import java.util.Objects;

/**
 * @author Violeta Georgieva
 */
public final class MeterKey {

	public static MeterKey.Builder builder() {
		return new MeterKey.Builder();
	}

	private final String uri;
	private final String remoteAddress;
	private final String method;
	private final String status;

	private MeterKey(Builder builder) {
		this.uri = builder.uri;
		this.remoteAddress = builder.remoteAddress;
		this.method = builder.method;
		this.status = builder.status;
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

	public static final class Builder {
		String uri;
		String remoteAddress;
		String method;
		String status;

		public Builder uri(String uri) {
			this.uri = uri;
			return this;
		}

		public Builder remoteAddress(String remoteAddress) {
			this.remoteAddress = remoteAddress;
			return this;
		}

		public Builder method(String method) {
			this.method = method;
			return this;
		}

		public Builder status(String status) {
			this.status = status;
			return this;
		}

		public MeterKey build() {
			return new MeterKey(this);
		}
	}
}
