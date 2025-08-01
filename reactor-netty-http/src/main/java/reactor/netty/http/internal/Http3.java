/*
 * Copyright (c) 2024-2025 VMware, Inc. or its affiliates, All Rights Reserved.
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
package reactor.netty.http.internal;

/**
 * Utility class around HTTP/3.
 * <p><strong>Note:</strong> This utility class is for internal use only. It can be removed at any time.
 *
 * @author Violeta Georgieva
 * @since 1.2.0
 */
public final class Http3 {

	static final boolean isHttp3Available;
	static {
		boolean http3;
		try {
			Class.forName("io.netty.handler.codec.http3.Http3");
			http3 = true;
		}
		catch (Throwable t) {
			http3 = false;
		}
		isHttp3Available = http3;
	}

	/**
	 * Check if the current runtime supports HTTP/3, by verifying if {@code io.netty:netty-codec-native-quic} is on the classpath.
	 *
	 * @return true if {@code io.netty:netty-codec-native-quic} is available
	 */
	public static boolean isHttp3Available() {
		return isHttp3Available;
	}

	private Http3() {}
}
