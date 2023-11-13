/*
 * Copyright (c) 2011-2023 VMware, Inc. or its affiliates, All Rights Reserved.
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
package reactor.netty.http.client;

import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.cookie.Cookie;

import java.time.Duration;

/**
 * An Http Reactive client metadata contract for outgoing requests. It inherits several
 * accessor related to HTTP flow : headers, params, URI, method, websocket...
 *
 * @author Stephane Maldini
 * @author Simon Baslé
 */
public interface HttpClientRequest extends HttpClientInfos {

	/**
	 * Add an outbound cookie.
	 *
	 * @return this outbound
	 */
	HttpClientRequest addCookie(Cookie cookie);

	/**
	 * Add an outbound http header, appending the value if the header is already set.
	 *
	 * @param name header name
	 * @param value header value
	 *
	 * @return this outbound
	 */
	HttpClientRequest addHeader(CharSequence name, CharSequence value);

	/**
	 * Set an outbound header, replacing any pre-existing value.
	 *
	 * @param name headers key
	 * @param value header value
	 *
	 * @return this outbound
	 */
	HttpClientRequest header(CharSequence name, CharSequence value);

	/**
	 * Set outbound headers from the passed headers. It will however ignore {@code
	 * HOST} header key. Any pre-existing value for the passed headers will be replaced.
	 *
	 * @param headers a netty headers map
	 *
	 * @return this outbound
	 */
	HttpClientRequest headers(HttpHeaders headers);

	/**
	 * Return true if redirected will be followed.
	 *
	 * @return true if redirected will be followed
	 */
	boolean isFollowRedirect();

	/**
	 * Specifies the maximum duration allowed between each network-level read operation while reading a given response
	 * (resolution: ms). In other words, {@link io.netty.handler.timeout.ReadTimeoutHandler} is added to the channel
	 * pipeline after sending the request and is removed when the response is fully received.
	 * If the {@code maxReadOperationInterval} is {@code null}, any previous setting will be removed and no
	 * {@code maxReadOperationInterval} will be applied.
	 * If the {@code maxReadOperationInterval} is less than {@code 1ms}, then {@code 1ms} will be the
	 * {@code maxReadOperationInterval}.
	 * The {@code maxReadOperationInterval} setting on {@link HttpClientRequest} level overrides any
	 * {@code maxReadOperationInterval} setting on {@link HttpClient} level.
	 *
	 * @param maxReadOperationInterval the maximum duration allowed between each network-level read operations
	 *                                 (resolution: ms).
	 * @return this outbound
	 * @since 0.9.11
	 * @see io.netty.handler.timeout.ReadTimeoutHandler
	 */
	HttpClientRequest responseTimeout(Duration maxReadOperationInterval);
}
