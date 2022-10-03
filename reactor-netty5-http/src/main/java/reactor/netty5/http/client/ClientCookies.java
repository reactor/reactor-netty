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
package reactor.netty5.http.client;

import io.netty5.handler.codec.http.headers.HttpHeaders;
import io.netty5.handler.codec.http.headers.HttpSetCookie;
import reactor.netty5.http.Cookies;
import reactor.util.Logger;
import reactor.util.Loggers;

import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Holder for Set-Cookie headers found in response headers
 *
 * @author Violeta Georgieva
 * @since 2.0.0
 */
public final class ClientCookies extends Cookies<HttpSetCookie> {
	static final Logger log = Loggers.getLogger(Cookies.class);

	/**
	 * Return a new cookies holder from client response headers.
	 *
	 * @param headers client response headers
	 * @return a new cookies holder from client response headers
	 */
	public static ClientCookies newClientResponseHolder(HttpHeaders headers) {
		return new ClientCookies(headers);
	}

	final HttpHeaders   nettyHeaders;

	Map<CharSequence, Set<HttpSetCookie>> cachedCookies;

	ClientCookies(HttpHeaders nettyHeaders) {
		this.cachedCookies = Collections.emptyMap();
		this.nettyHeaders = Objects.requireNonNull(nettyHeaders, "nettyHeaders");
	}

	@Override
	public Map<CharSequence, Set<HttpSetCookie>> getCachedCookies() {
		if (!markReadingCookies()) {
			for (;;) {
				if (hasReadCookies()) {
					return cachedCookies;
				}
			}
		}

		Map<CharSequence, Set<HttpSetCookie>> cookies = new HashMap<>();
		Iterator<HttpSetCookie> setCookieItr = nettyHeaders.getSetCookiesIterator();
		int setCookieIndex = -1;
		while (setCookieItr.hasNext()) {
			try {
				setCookieIndex++;
				HttpSetCookie setCookie = setCookieItr.next();
				Set<HttpSetCookie> existingCookiesOfName = cookies.computeIfAbsent(setCookie.name(), k -> new HashSet<>());
				existingCookiesOfName.add(setCookie);
			}
			catch (IllegalArgumentException err) {
				// Ignore invalid syntax or whatever decoding error for the current Set-Cookie header.
				// Since we don't know which Set-Cookie header is not parsed, we log the Set-Cookie header index number.
				if (log.isDebugEnabled()) {
					log.debug("Ignoring invalid Set-Cookie header (header index #{}) : {}", setCookieIndex, err.toString());
				}
			}
		}

		cachedCookies = Collections.unmodifiableMap(cookies);
		markReadCookies();
		return cachedCookies;
	}
}
