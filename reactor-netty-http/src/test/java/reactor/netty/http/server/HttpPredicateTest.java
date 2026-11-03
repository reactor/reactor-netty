/*
 * Copyright (c) 2026 VMware, Inc. or its affiliates, All Rights Reserved.
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

import io.netty.handler.codec.http.HttpMethod;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.Mockito;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for {@link HttpPredicate}.
 */
class HttpPredicateTest {

	/**
	 * {@link HttpPredicate#prefix(String)} matches {@code [prefix]/**}: the prefix itself, or the prefix followed
	 * by a segment boundary (a {@code '/'}, also percent-encoded, or the query string), but not an unrelated
	 * sibling such as {@code /filesystem-status} for {@code /files}. A prefix that already ends with {@code '/'}
	 * needs no further boundary.
	 */
	@ParameterizedTest(name = "prefix(\"{0}\") on \"{1}\" is {2}")
	@CsvSource({
			"/files,  /files,                 true",
			"/files,  /files/report.txt,      true",
			"/files,  /files?download=1,      true",
			"/files,  /files%2Freport.txt,    true",
			"/files,  /files%2freport.txt,    true",
			"/files,  /filesystem-status,     false",
			"/files,  /files-admin,           false",
			"/files/, /files/,                true",
			"/files/, /files/report.txt,      true",
			"/files/, /files,                 false",
			"/files/, /filesystem-status,     false",
			"/,       /,                      true",
			"/,       /index.html,            true",
			"/,       /files/report.txt,      true"
	})
	void prefixPredicateRespectsSegmentBoundary(String prefix, String uri, boolean expected) {
		assertThat(HttpPredicate.prefix(prefix).test(requestFor(uri))).isEqualTo(expected);
	}

	private static HttpServerRequest requestFor(String uri) {
		HttpServerRequest request = Mockito.mock(HttpServerRequest.class);
		Mockito.when(request.uri()).thenReturn(uri);
		Mockito.when(request.method()).thenReturn(HttpMethod.GET);
		return request;
	}
}
