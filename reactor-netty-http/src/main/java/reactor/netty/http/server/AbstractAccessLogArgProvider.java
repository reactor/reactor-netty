/*
 * Copyright (c) 2011-Present VMware, Inc. or its affiliates, All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *       https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package reactor.netty.http.server;

import io.netty.channel.socket.SocketChannel;

import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;

/**
 * @author limaoning
 */
abstract class AbstractAccessLogArgProvider implements AccessLogArgProvider {

	static final DateTimeFormatter DATE_TIME_FORMATTER =
			DateTimeFormatter.ofPattern("dd/MMM/yyyy:HH:mm:ss Z");
	static final String MISSING = "-";

	final String zonedDateTime;
	String user = MISSING;
	SocketChannel channel;
	long contentLength;
	long startTime = System.currentTimeMillis();

	AbstractAccessLogArgProvider() {
		this.zonedDateTime = ZonedDateTime.now(ZoneId.systemDefault()).format(DATE_TIME_FORMATTER);
	}

	@Override
	public String zonedDateTime() {
		return zonedDateTime;
	}

	@Override
	public String address() {
		return channel.remoteAddress().getHostString();
	}

	@Override
	public int port() {
		return channel.remoteAddress().getPort();
	}

	@Override
	public String user() {
		return user;
	}

	abstract void increaseContentLength(long contentLength);

	@Override
	public long contentLength() {
		return contentLength;
	}

	@Override
	public long duration() {
		return System.currentTimeMillis() - startTime;
	}

}
