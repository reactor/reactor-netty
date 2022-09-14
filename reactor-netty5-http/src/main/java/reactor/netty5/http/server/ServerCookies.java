/*
 * Copyright (c) 2021-2022 VMware, Inc. or its affiliates, All Rights Reserved.
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

import io.netty5.handler.codec.http.headers.HttpCookiePair;
import io.netty5.handler.codec.http.headers.HttpHeaders;
import reactor.netty5.http.Cookies;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Holder for Cookie headers found from request headers
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
	public static ServerCookies newServerRequestHolder(HttpHeaders headers) {
		return new ServerCookies(headers);
	}

	Map<CharSequence, List<HttpCookiePair>> allCachedCookies;

	ServerCookies(HttpHeaders nettyHeaders) {
		super(nettyHeaders);
		allCachedCookies = Collections.emptyMap();
	}

	@Override
	public Map<CharSequence, Set<HttpCookiePair>> getCachedCookies() {
		getAllCachedCookies();
		return cachedCookies;
	}

	/**
	 * Wait for the cookies to become available, cache them and subsequently return the cached map of cookies.
	 * As opposed to {@link #getCachedCookies()}, this returns all cookies, even if they have the same name.
	 *
	 * @return the cached map of cookies
	 */
	public Map<CharSequence, List<HttpCookiePair>> getAllCachedCookies() {
		if (!markReadingCookies()) {
			for (;;) {
				if (hasReadCookies()) {
					return allCachedCookies;
				}
			}
		}

		Map<java.lang.CharSequence, Set<HttpCookiePair>> cookies = new HashMap<>();
		Map<java.lang.CharSequence, List<HttpCookiePair>> allCookies = new HashMap<>();
		for (HttpCookiePair cookie : nettyHeaders.getCookies()) {
			Set<HttpCookiePair> existingCookiesOfName = cookies.computeIfAbsent(cookie.name(), k -> new HashSet<>());
			if (existingCookiesOfName.size() == 0) {
				// if the set is non empty, it means we have previously added the same Set-Cookie  with the same name.
				existingCookiesOfName.add(cookie);
			}
			List<HttpCookiePair> existingCookiesOfNameList = allCookies.computeIfAbsent(cookie.name(), k -> new ArrayList<>());
			existingCookiesOfNameList.add(cookie);
		}
		cachedCookies = Collections.unmodifiableMap(cookies);
		allCachedCookies = Collections.unmodifiableMap(allCookies);
		markReadCookies();
		return allCachedCookies;
	}
}
