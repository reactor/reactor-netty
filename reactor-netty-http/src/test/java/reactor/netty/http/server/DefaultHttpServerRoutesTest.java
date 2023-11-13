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

import io.netty.handler.codec.http.HttpMethod;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.reactivestreams.Publisher;
import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;
import reactor.netty.NettyOutbound;
import reactor.test.StepVerifier;

import java.net.URISyntaxException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for {@link DefaultHttpServerRoutes}.
 *
 * @author Sascha Dais
 * @since 1.0.8
 */
class DefaultHttpServerRoutesTest {

	@Test
	void directoryRouteTest() throws URISyntaxException {

		HttpServerRequest request = Mockito.mock(HttpServerRequest.class);

		Mockito.when(request.paramsResolver(Mockito.any())).thenReturn(request);
		Mockito.when(request.uri()).thenReturn("/test");
		Mockito.when(request.method()).thenReturn(HttpMethod.GET);

		Subscription subscription = Mockito.mock(Subscription.class);

		NettyOutbound outbound = Mockito.mock(NettyOutbound.class);
		Mockito.doAnswer(invocation -> {
			Subscriber<Void> subscriber = invocation.getArgument(0);
			subscriber.onSubscribe(subscription);
			subscriber.onNext(null);
			subscriber.onComplete();
			return null;
		}).when(outbound).subscribe(Mockito.any());
		HttpServerResponse response = Mockito.mock(HttpServerResponse.class);

		Mockito.when(response.sendFile(Mockito.any())).thenReturn(outbound);

		Path resource = Paths.get(getClass().getResource("/public").toURI());

		DefaultHttpServerRoutes routes = new DefaultHttpServerRoutes();

		HttpServerRoutes route = routes.directory("/test", resource);
		Publisher<Void> publisher = route.apply(request, response);

		assertThat(publisher).isNotNull();

		StepVerifier.create(publisher)
				.expectNextMatches(p -> true)
				.expectComplete()
				.verify(Duration.ofMillis(200));

	}
}