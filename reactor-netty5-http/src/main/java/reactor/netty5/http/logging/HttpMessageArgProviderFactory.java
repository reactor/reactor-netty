/*
 * Copyright (c) 2022 VMware, Inc. or its affiliates, All Rights Reserved.
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
package reactor.netty5.http.logging;

import io.netty5.handler.codec.http.FullHttpRequest;
import io.netty5.handler.codec.http.FullHttpResponse;
import io.netty5.handler.codec.http.HttpContent;
import io.netty5.handler.codec.http.HttpRequest;
import io.netty5.handler.codec.http.HttpResponse;
import io.netty5.handler.codec.http.LastHttpContent;

/**
 * Factory for creating {@link HttpContentArgProvider} based on the provided HTTP object.
 *
 * @author Violeta Georgieva
 * @since 1.0.24
 */
public final class HttpMessageArgProviderFactory {

	/**
	 * Creates {@link HttpContentArgProvider} based on the provided HTTP object.
	 *
	 * @param httpObject the HTTP object
	 * @return a new {@link HttpContentArgProvider}
	 */
	public static HttpMessageArgProvider create(Object httpObject) {
		if (httpObject instanceof FullHttpRequest fullHttpRequest) {
			return new FullHttpRequestArgProvider(fullHttpRequest);
		}
		else if (httpObject instanceof HttpRequest httpRequest) {
			return new HttpRequestArgProvider(httpRequest);
		}
		else if (httpObject instanceof FullHttpResponse fullHttpResponse) {
			return new FullHttpResponseArgProvider(fullHttpResponse);
		}
		else if (httpObject instanceof HttpResponse httpResponse) {
			return new HttpResponseArgProvider(httpResponse);
		}
		else if (httpObject instanceof LastHttpContent<?> lastHttpContent) {
			return new LastHttpContentArgProvider(lastHttpContent);
		}
		else if (httpObject instanceof HttpContent<?> httpContent) {
			return new HttpContentArgProvider(httpContent);
		}
		else {
			throw new IllegalArgumentException("Unknown object: " + httpObject);
		}
	}

	private HttpMessageArgProviderFactory() {
	}
}
