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

import java.util.function.Predicate;

import io.netty.handler.codec.http.HttpMethod;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for {@link HttpPredicate}.
 */
class HttpPredicateTest {

	/**
	 * {@link HttpPredicate#prefix(String)}'s own Javadoc describes the semantics as
	 * {@code [prefix]/**} (a segment-bounded wildcard), which is also what
	 * {@link DefaultHttpServerRoutes#directory} relies on to serve files under a mapped
	 * path. The underlying {@code HttpPrefixPredicate} matched with a plain
	 * {@link String#startsWith(String)} instead, with no check for a following
	 * {@code '/'} or exact-match boundary, so a route registered for prefix
	 * {@code "/files"} also incorrectly matched an unrelated sibling path such as
	 * {@code "/filesystem-status"} that merely shares the same characters.
	 */
	@Test
	void prefixPredicateRespectsSegmentBoundary() {
		Predicate<HttpServerRequest> predicate = HttpPredicate.prefix("/files");

		assertThat(predicate.test(requestFor("/files"))).as("exact match").isTrue();
		assertThat(predicate.test(requestFor("/files/report.txt"))).as("true descendant").isTrue();
		assertThat(predicate.test(requestFor("/files?download=1"))).as("exact match with query string").isTrue();

		assertThat(predicate.test(requestFor("/filesystem-status")))
				.as("unrelated sibling path must not match a rule for '/files'")
				.isFalse();
		assertThat(predicate.test(requestFor("/files-admin")))
				.as("unrelated sibling path must not match a rule for '/files'")
				.isFalse();
	}

	private static HttpServerRequest requestFor(String uri) {
		HttpServerRequest request = Mockito.mock(HttpServerRequest.class);
		Mockito.when(request.uri()).thenReturn(uri);
		Mockito.when(request.method()).thenReturn(HttpMethod.GET);
		return request;
	}
}
