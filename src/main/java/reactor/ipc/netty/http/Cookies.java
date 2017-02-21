/*
 * Copyright (c) 2011-2016 Pivotal Software Inc, All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package reactor.ipc.netty.http;

import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicIntegerFieldUpdater;

import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.cookie.ClientCookieDecoder;
import io.netty.handler.codec.http.cookie.Cookie;
import io.netty.handler.codec.http.cookie.ServerCookieDecoder;

/**
 * Store cookies for the http channel.
 * @since 0.6
 */
public final class Cookies {

	/**
	 *
	 * Return a new cookies holder from client response headers
	 * @param headers client response headers
	 * @return a new cookies holder from client response headers
	 */
	public static Cookies newClientResponseHolder(HttpHeaders headers) {
		return new Cookies(headers, HttpHeaderNames.SET_COOKIE, true);
	}

	/**
	 * Return a new cookies holder from server request headers
	 * @param headers server request headers
	 * @return a new cookies holder from server request headers
	 */
	public static Cookies newServerRequestHolder(HttpHeaders headers) {
		return new Cookies(headers, HttpHeaderNames.COOKIE, false);
	}


	final static int NOT_READ = 0;
	final static int READING  = 1;

	final static int READ     = 2;
	final HttpHeaders  nettyHeaders;
	final CharSequence cookiesHeaderName;

	final boolean      isClientChannel;

	Map<CharSequence, Set<Cookie>> cachedCookies;
	volatile     int                                state = 0;

	static final AtomicIntegerFieldUpdater<Cookies> STATE =
			AtomicIntegerFieldUpdater.newUpdater(Cookies.class, "state");

	private Cookies(HttpHeaders nettyHeaders, CharSequence cookiesHeaderName, boolean isClientChannel) {
		this.nettyHeaders = nettyHeaders;
		this.cookiesHeaderName = cookiesHeaderName;
		this.isClientChannel = isClientChannel;
		cachedCookies = Collections.emptyMap();
	}

	/**
	 * Wait for the cookies to become available, cache them and subsequently return the
	 * cached map of cookies.
	 */
	public Map<CharSequence, Set<Cookie>> getCachedCookies() {
		if (!STATE.compareAndSet(this, NOT_READ, READING)) {
			for (; ; ) {
				if (state == READ) {
					return cachedCookies;
				}
			}
		}

		List<String> allCookieHeaders = nettyHeaders.getAll(cookiesHeaderName);
		Map<String, Set<Cookie>> cookies = new HashMap<>();
		for (String aCookieHeader : allCookieHeaders) {
			Set<Cookie> decode;
			if (isClientChannel) {
				final Cookie c = ClientCookieDecoder.STRICT.decode(aCookieHeader);
				Set<Cookie> existingCookiesOfName = cookies.get(c.name());
				if (null == existingCookiesOfName) {
					existingCookiesOfName = new HashSet<>();
					cookies.put(c.name(), existingCookiesOfName);
				}
				existingCookiesOfName.add(c);
			}
			else {
				decode = ServerCookieDecoder.STRICT.decode(aCookieHeader);
				for (Cookie cookie : decode) {
					Set<Cookie> existingCookiesOfName = cookies.get(cookie.name());
					if (null == existingCookiesOfName) {
						existingCookiesOfName = new HashSet<>();
						cookies.put(cookie.name(), existingCookiesOfName);
					}
					existingCookiesOfName.add(cookie);
				}
			}
		}
		cachedCookies = Collections.unmodifiableMap(cookies);
		state = READ;
		return cachedCookies;
	}
}
