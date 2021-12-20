/*
 * Copyright (c) 2021 VMware, Inc. or its affiliates, All Rights Reserved.
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

import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.cookie.Cookie;
import io.netty.handler.codec.http.cookie.ServerCookieDecoder;
import reactor.netty.http.Cookies;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * {@link Cookies} holder from server request headers.
 *
 * @author Violeta Georgieva
 * @since 1.0.8
 */
public final class ServerCookies extends Cookies {

	/**
	 * Return a new cookies holder from server request headers.
	 *
	 * @param headers server request headers
	 * @return a new cookies holder from server request headers
	 */
	public static ServerCookies newServerRequestHolder(HttpHeaders headers, ServerCookieDecoder decoder) {
		return new ServerCookies(headers, HttpHeaderNames.COOKIE, false, decoder);
	}

	final ServerCookieDecoder serverCookieDecoder;

	Map<CharSequence, List<Cookie>> allCachedCookies;

	ServerCookies(HttpHeaders nettyHeaders, CharSequence cookiesHeaderName, boolean isClientChannel,
			ServerCookieDecoder decoder) {
		super(nettyHeaders, cookiesHeaderName, isClientChannel, decoder);
		this.serverCookieDecoder = decoder;
		allCachedCookies = Collections.emptyMap();
	}

	@Override
	public Map<CharSequence, Set<Cookie>> getCachedCookies() {
		getAllCachedCookies();
		return cachedCookies;
	}

	/**
	 * Wait for the cookies to become available, cache them and subsequently return the cached map of cookies.
	 * As opposed to {@link #getCachedCookies()}, this returns all cookies, even if they have the same name.
	 *
	 * @return the cached map of cookies
	 */
	public Map<CharSequence, List<Cookie>> getAllCachedCookies() {
		if (!markReadingCookies()) {
			for (;;) {
				if (hasReadCookies()) {
					return allCachedCookies;
				}
			}
		}

		List<String> allCookieHeaders = allCookieHeaders();
		Map<String, Set<Cookie>> cookies = new HashMap<>();
		Map<String, List<Cookie>> allCookies = new HashMap<>();
		for (String aCookieHeader : allCookieHeaders) {
			List<Cookie> decode = serverCookieDecoder.decodeAll(aCookieHeader);
			for (Cookie cookie : decode) {
				Set<Cookie> existingCookiesOfNameSet = cookies.computeIfAbsent(cookie.name(), k -> new HashSet<>());
				existingCookiesOfNameSet.add(cookie);
				List<Cookie> existingCookiesOfNameList = allCookies.computeIfAbsent(cookie.name(), k -> new ArrayList<>());
				existingCookiesOfNameList.add(cookie);
			}
		}
		cachedCookies = Collections.unmodifiableMap(cookies);
		allCachedCookies = Collections.unmodifiableMap(allCookies);
		markReadCookies();
		return allCachedCookies;
	}
}
