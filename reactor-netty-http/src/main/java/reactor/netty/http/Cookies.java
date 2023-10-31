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
package reactor.netty.http;

import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicIntegerFieldUpdater;

import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.cookie.ClientCookieDecoder;
import io.netty.handler.codec.http.cookie.Cookie;
import io.netty.handler.codec.http.cookie.CookieDecoder;
import io.netty.handler.codec.http.cookie.ServerCookieDecoder;

/**
 * Store cookies for the http channel.
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
	public static Cookies newClientResponseHolder(HttpHeaders headers, ClientCookieDecoder decoder) {
		return new Cookies(headers, HttpHeaderNames.SET_COOKIE, true, decoder);
	}

	/**
	 * Return a new cookies holder from server request headers.
	 *
	 * @param headers server request headers
	 * @return a new cookies holder from server request headers
	 * @deprecated as of 1.0.8.
	 * Prefer {@link reactor.netty.http.server.ServerCookies#newServerRequestHolder(HttpHeaders, ServerCookieDecoder)}.
	 * This method will be removed in version 1.2.0.
	 */
	@Deprecated
	public static Cookies newServerRequestHolder(HttpHeaders headers, ServerCookieDecoder decoder) {
		return new Cookies(headers, HttpHeaderNames.COOKIE, false, decoder);
	}


	static final int NOT_READ = 0;
	static final int READING  = 1;
	static final int READ     = 2;

	final HttpHeaders   nettyHeaders;
	final CharSequence  cookiesHeaderName;
	final boolean       isClientChannel;
	final CookieDecoder decoder;

	protected Map<CharSequence, Set<Cookie>> cachedCookies;

	volatile     int                                state;
	static final AtomicIntegerFieldUpdater<Cookies> STATE =
			AtomicIntegerFieldUpdater.newUpdater(Cookies.class, "state");

	protected Cookies(HttpHeaders nettyHeaders, CharSequence cookiesHeaderName, boolean isClientChannel,
			CookieDecoder decoder) {
		this.nettyHeaders = Objects.requireNonNull(nettyHeaders, "nettyHeaders");
		this.cookiesHeaderName = cookiesHeaderName;
		this.isClientChannel = isClientChannel;
		this.decoder = Objects.requireNonNull(decoder, "decoder");
		cachedCookies = Collections.emptyMap();
	}

	/**
	 * Wait for the cookies to become available, cache them and subsequently return the cached map of cookies.
	 *
	 * @return the cached map of cookies
	 */
	public Map<CharSequence, Set<Cookie>> getCachedCookies() {
		if (!markReadingCookies()) {
			for (;;) {
				if (hasReadCookies()) {
					return cachedCookies;
				}
			}
		}

		List<String> allCookieHeaders = allCookieHeaders();
		Map<String, Set<Cookie>> cookies = new HashMap<>();
		for (String aCookieHeader : allCookieHeaders) {
			Set<Cookie> decode;
			if (isClientChannel) {
				final Cookie c = ((ClientCookieDecoder) decoder).decode(aCookieHeader);
				if (c == null) {
					continue;
				}
				Set<Cookie> existingCookiesOfName = cookies.computeIfAbsent(c.name(), k -> new HashSet<>());
				existingCookiesOfName.add(c);
			}
			else {
				decode = ((ServerCookieDecoder) decoder).decode(aCookieHeader);
				for (Cookie cookie : decode) {
					Set<Cookie> existingCookiesOfName = cookies.computeIfAbsent(cookie.name(), k -> new HashSet<>());
					existingCookiesOfName.add(cookie);
				}
			}
		}
		cachedCookies = Collections.unmodifiableMap(cookies);
		markReadCookies();
		return cachedCookies;
	}

	protected List<String> allCookieHeaders() {
		return nettyHeaders.getAll(cookiesHeaderName);
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
