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

import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicIntegerFieldUpdater;

import io.netty5.handler.codec.http.headers.HttpCookiePair;

/**
 * Base holder for Set-Cookie/Cookie headers found in response headers/request headers
 *
 * @since 0.6
 */
public abstract class Cookies<T extends HttpCookiePair> {

	final static int NOT_READ = 0;
	final static int READING  = 1;
	final static int READ     = 2;

	volatile     int                                state;
	@SuppressWarnings("rawtypes")
	static final AtomicIntegerFieldUpdater<Cookies> STATE =
			AtomicIntegerFieldUpdater.newUpdater(Cookies.class, "state");

	/**
	 * Wait for the cookies to become available, cache them and subsequently return the cached map of cookies.
	 *
	 * @return the cached map of cookies
	 */
	public abstract Map<CharSequence, Set<T>> getCachedCookies();

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
