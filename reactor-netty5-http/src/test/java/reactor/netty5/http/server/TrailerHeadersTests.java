/*
 * Copyright (c) 2021-2025 VMware, Inc. or its affiliates, All Rights Reserved.
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
package reactor.netty5.http.server;

import io.netty5.handler.codec.http.headers.HttpHeaders;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Locale;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

/**
 * This test class verifies {@link HttpServer} trailer headers handling.
 *
 * @author Violeta Georgieva
 */
class TrailerHeadersTests {

	static final String ERROR_MESSAGE_1 = "Header [%s] is not allowed as a trailer header";
	static final String ERROR_MESSAGE_2 = "Pseudo header [%s] is not allowed as a trailer header";

	static final String EMPTY = "";
	static final String HEADER_NAME_1 = "foo";
	static final String HEADER_NAME_2 = "bar";
	static final String HEADER_VALUE = "test";
	static final String PSEUDO_HEADER_NAME_1 = ":protocol";
	static final String PSEUDO_HEADER_NAME_2 = " :protocol";
	static final String PSEUDO_HEADER_NAME_3 = ":protocol ";
	static final String SPACE = " ";

	@ParameterizedTest
	@MethodSource("disallowedTrailerHeaderNames")
	void testDisallowedTrailerHeaderNames(String headerName) {
		String headerNameUpperCase = headerName.toUpperCase(Locale.ENGLISH);
		assertThatExceptionOfType(IllegalArgumentException.class)
				.isThrownBy(() -> new HttpServerOperations.TrailerHeaders(false).add(headerNameUpperCase, HEADER_VALUE))
				.withMessage(String.format(ERROR_MESSAGE_1, headerNameUpperCase));
	}

	@ParameterizedTest
	@ValueSource(strings = {EMPTY, SPACE})
	void testEmptyTrailerHeaderNames(String headerName) {
		assertThatExceptionOfType(IllegalArgumentException.class)
				.isThrownBy(() -> new HttpServerOperations.TrailerHeaders(false).add(headerName, HEADER_VALUE))
				.withMessage(String.format(ERROR_MESSAGE_1, headerName));
	}

	@ParameterizedTest
	@ValueSource(strings = {PSEUDO_HEADER_NAME_1, PSEUDO_HEADER_NAME_2, PSEUDO_HEADER_NAME_3})
	void testPseudoHeaderInTrailerHeaderNames(String headerName) {
		assertThatExceptionOfType(IllegalArgumentException.class)
				.isThrownBy(() -> new HttpServerOperations.TrailerHeaders(true).add(headerName, HEADER_VALUE))
				.withMessage(String.format(ERROR_MESSAGE_2, headerName));
	}
	@Test
	void testTrailerHeaders() {
		HttpHeaders headers = new HttpServerOperations.TrailerHeaders(false);
		assertThat(headers.isEmpty()).isTrue();
		headers.add(HEADER_NAME_1, HEADER_VALUE);
		headers.add(HEADER_NAME_2, HEADER_VALUE);
		assertThat(headers.isEmpty()).isFalse();
		assertThat(headers.size()).isEqualTo(2);
		assertThat(headers.get(HEADER_NAME_1)).isEqualTo(HEADER_VALUE);
		assertThat(headers.get(HEADER_NAME_2)).isEqualTo(HEADER_VALUE);
	}

	static Set<String> disallowedTrailerHeaderNames() {
		return HttpServerOperations.TrailerHeaders.DISALLOWED_TRAILER_HEADER_NAMES;
	}
}
