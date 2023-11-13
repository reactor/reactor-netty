/*
 * Copyright (c) 2021-2023 VMware, Inc. or its affiliates, All Rights Reserved.
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
package reactor.netty.http.server;

import io.netty.handler.codec.http.HttpHeaders;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

/**
 * This test class verifies {@link HttpServer} trailer headers handling.
 *
 * @author Violeta Georgieva
 */
class TrailerHeadersTests {

	static final String ERROR_MESSAGE = "Trailer header name [%s] not declared with [Trailer] header," +
			" or it is not a valid trailer header name";
	static final String COMMA = ",";
	static final String EMPTY = "";
	static final String HEADER_NAME_1 = "foo";
	static final String HEADER_NAME_2 = "bar";
	static final String HEADER_VALUE = "test";
	static final String SPACE = " ";

	@ParameterizedTest(name = "{displayName}({0})")
	@MethodSource("disallowedTrailerHeaderNames")
	void testDisallowedTrailerHeaderNames(String declaredHeaderName) {
		assertThatExceptionOfType(IllegalArgumentException.class)
				.isThrownBy(() -> new HttpServerOperations.TrailerHeaders(declaredHeaderName).add(declaredHeaderName, HEADER_VALUE))
				.withMessage(String.format(ERROR_MESSAGE, declaredHeaderName));
	}

	@ParameterizedTest(name = "{displayName}({index})")
	@ValueSource(strings = {
			HEADER_NAME_1,
			COMMA + HEADER_NAME_1,
			HEADER_NAME_1 + COMMA,
			HEADER_NAME_1 + SPACE,
			HEADER_NAME_1 + COMMA + SPACE
	})
	void testNameIncludedInTrailerHeader(String declaredHeaderNames) {
		HttpHeaders headers = new HttpServerOperations.TrailerHeaders(declaredHeaderNames);
		assertThat(headers.isEmpty()).isTrue();
		headers.add(HEADER_NAME_1, HEADER_VALUE);
		assertThat(headers.isEmpty()).isFalse();
		assertThat(headers.size()).isEqualTo(1);
		assertThat(headers.get(HEADER_NAME_1)).isEqualTo(HEADER_VALUE);
	}

	@Test
	void testNamesIncludedInTrailerHeader() {
		HttpHeaders headers = new HttpServerOperations.TrailerHeaders(HEADER_NAME_1 + ',' + HEADER_NAME_2);
		assertThat(headers.isEmpty()).isTrue();
		headers.add(HEADER_NAME_1, HEADER_VALUE);
		headers.add(HEADER_NAME_2, HEADER_VALUE);
		assertThat(headers.isEmpty()).isFalse();
		assertThat(headers.size()).isEqualTo(2);
		assertThat(headers.get(HEADER_NAME_1)).isEqualTo(HEADER_VALUE);
		assertThat(headers.get(HEADER_NAME_2)).isEqualTo(HEADER_VALUE);
	}

	@Test
	void testNameNotIncludedInTrailerHeader() {
		assertThatExceptionOfType(IllegalArgumentException.class)
				.isThrownBy(() -> new HttpServerOperations.TrailerHeaders(HEADER_NAME_1).add(HEADER_NAME_2, HEADER_VALUE))
				.withMessage(String.format(ERROR_MESSAGE, HEADER_NAME_2));
	}

	@ParameterizedTest(name = "{displayName}({index})")
	@ValueSource(strings = {COMMA, EMPTY, SPACE})
	void testNothingIsIncludedInTrailerHeader(String declaredHeaderNames) {
		assertThatExceptionOfType(IllegalArgumentException.class)
				.isThrownBy(() -> new HttpServerOperations.TrailerHeaders(declaredHeaderNames).add(EMPTY, HEADER_VALUE))
				.withMessage(String.format(ERROR_MESSAGE, EMPTY));
	}

	static Set<String> disallowedTrailerHeaderNames() {
		return HttpServerOperations.TrailerHeaders.DISALLOWED_TRAILER_HEADER_NAMES;
	}
}
