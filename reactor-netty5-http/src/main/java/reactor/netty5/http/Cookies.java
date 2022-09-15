/*
 * Copyright (c) 2011-2022 VMware, Inc. or its affiliates, All Rights Reserved.
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
package reactor.netty5.http;

import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicIntegerFieldUpdater;

import io.netty5.handler.codec.http.headers.HttpCookiePair;
import io.netty5.handler.codec.http.headers.HttpHeaders;
import io.netty5.handler.codec.http.headers.HttpSetCookie;
import reactor.util.Logger;
import reactor.util.Loggers;

/**
 * Holder for Set-Cookie headers found from response headers
 *
 * @since 0.6
 */
public class Cookies {

	/**
	 * Return a new cookies holder from client response headers.
	 *
	 * @param headers client response headers
	 * @return a new cookies holder from client response headers
	 */
	public static Cookies newClientResponseHolder(HttpHeaders headers) {
		return new Cookies(headers);
	}

	final static int NOT_READ = 0;
	final static int READING  = 1;
	final static int READ     = 2;

	protected final HttpHeaders   nettyHeaders;

	protected Map<CharSequence, Set<HttpCookiePair>> cachedCookies;

	volatile     int                                state;
	static final AtomicIntegerFieldUpdater<Cookies> STATE =
			AtomicIntegerFieldUpdater.newUpdater(Cookies.class, "state");
	static final Logger log = Loggers.getLogger(Cookies.class);

	protected Cookies(HttpHeaders nettyHeaders) {
		this.nettyHeaders = Objects.requireNonNull(nettyHeaders, "nettyHeaders");
		cachedCookies = Collections.emptyMap();
	}

	/**
	 * Wait for the cookies to become available, cache them and subsequently return the cached map of cookies.
	 *
	 * @return the cached map of cookies
	 */
	public Map<CharSequence, Set<HttpCookiePair>> getCachedCookies() {
		if (!markReadingCookies()) {
			for (;;) {
				if (hasReadCookies()) {
					return cachedCookies;
				}
			}
		}

		Map<CharSequence, Set<HttpCookiePair>> cookies = new HashMap<>();
		Iterator<HttpSetCookie> setCookieItr = nettyHeaders.getSetCookiesIterator();
		int setCookieIndex = -1;
		while (setCookieItr.hasNext()) {
			try {
				setCookieIndex++;
				HttpSetCookie setCookie = setCookieItr.next();
				Set<HttpCookiePair> existingCookiesOfName = cookies.computeIfAbsent(setCookie.name(), k -> new HashSet<>());
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

	protected final boolean hasReadCookies() {
		return state == READ;
	}

	protected final boolean markReadCookies() {
		return STATE.compareAndSet(this, READING, READ);
	}

	protected final boolean markReadingCookies() {
		return STATE.compareAndSet(this, NOT_READ, READING);
	}
}
