/*
 * Copyright (c) 2011-Present VMware, Inc. or its affiliates, All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package reactor.netty.http.server.logging;

import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.cookie.Cookie;
import reactor.netty.ReactorNetty;
import reactor.util.annotation.Nullable;

import java.net.SocketAddress;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

/**
 * @author limaoning
 */
abstract class AbstractAccessLogArgProvider<SELF extends AbstractAccessLogArgProvider<SELF>>
		implements AccessLogArgProvider, Supplier<SELF> {

	static final DateTimeFormatter DATE_TIME_FORMATTER =
			DateTimeFormatter.ofPattern("dd/MMM/yyyy:HH:mm:ss Z");
	static final String MISSING = "-";

	final SocketAddress remoteAddress;
	final String user = MISSING;
	String zonedDateTime;
	ZonedDateTime accessDateTime;
	CharSequence method;
	CharSequence uri;
	String protocol;
	boolean chunked;
	long contentLength = -1;
	long startTime;
	Map<CharSequence, Set<Cookie>> cookies;

	AbstractAccessLogArgProvider(@Nullable SocketAddress remoteAddress) {
		this.remoteAddress = remoteAddress;
	}

	@Override
	@Nullable
	@Deprecated
	public String zonedDateTime() {
		return zonedDateTime;
	}

	@Override
	@Nullable
	public ZonedDateTime accessDateTime() {
		return accessDateTime;
	}

	@Override
	@Nullable
	public SocketAddress remoteAddress() {
		return remoteAddress;
	}

	@Override
	@Nullable
	public CharSequence method() {
		return method;
	}

	@Override
	@Nullable
	public CharSequence uri() {
		return uri;
	}

	@Override
	@Nullable
	public String protocol() {
		return protocol;
	}

	@Override
	@Nullable
	public String user() {
		return user;
	}

	@Override
	public long contentLength() {
		return contentLength;
	}

	@Override
	public long duration() {
		return System.currentTimeMillis() - startTime;
	}

	@Override
	@Nullable
	public Map<CharSequence, Set<Cookie>> cookies() {
		return cookies;
	}

	/**
	 * Initialize some fields (e.g. ZonedDateTime startTime).
	 * Should be called when a new request is received.
	 */
	void onRequest() {
		this.accessDateTime = ZonedDateTime.now(ReactorNetty.ZONE_ID_SYSTEM);
		this.zonedDateTime = accessDateTime.format(DATE_TIME_FORMATTER);
		this.startTime = System.currentTimeMillis();
	}

	/**
	 * Remove non-final fields for reuse.
	 */
	void clear() {
		this.accessDateTime = null;
		this.zonedDateTime = null;
		this.method = null;
		this.uri = null;
		this.protocol = null;
		this.chunked = false;
		this.contentLength = -1;
		this.startTime = 0;
		this.cookies = null;
	}

	SELF cookies(Map<CharSequence, Set<Cookie>> cookies) {
		this.cookies = cookies;
		return get();
	}

	SELF chunked(boolean chunked) {
		this.chunked = chunked;
		return get();
	}

	SELF increaseContentLength(long contentLength) {
		if (chunked && contentLength >= 0 && !HttpMethod.HEAD.asciiName().contentEqualsIgnoreCase(method)) {
			if (this.contentLength == -1) {
				this.contentLength = 0;
			}
			this.contentLength += contentLength;
		}
		return get();
	}

}
