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

import java.util.function.BiPredicate;
import java.util.function.Function;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.handler.codec.http.cookie.ServerCookieDecoder;
import io.netty.handler.codec.http.cookie.ServerCookieEncoder;
import io.netty.util.AttributeKey;
import reactor.netty.http.HttpProtocol;

/**
 * @author Stephane Maldini
 */
final class HttpServerConfiguration {

	static final HttpServerConfiguration DEFAULT = new HttpServerConfiguration();

	static final AttributeKey<HttpServerConfiguration> CONF_KEY =
			AttributeKey.newInstance("httpServerConf");

	BiPredicate<HttpServerRequest, HttpServerResponse> compressPredicate  = null;

	int                    minCompressionSize = -1;
	boolean                forwarded          = false;
	HttpRequestDecoderSpec decoder            = new HttpRequestDecoderSpec();
	ServerCookieEncoder    cookieEncoder      = ServerCookieEncoder.STRICT;
	ServerCookieDecoder    cookieDecoder      = ServerCookieDecoder.STRICT;
	int                    protocols          = h11;


	static HttpServerConfiguration getAndClean(ServerBootstrap b) {
		HttpServerConfiguration hcc = (HttpServerConfiguration) b.config()
		                                                         .attrs()
		                                                         .get(CONF_KEY);
		if (hcc == null) {
			return DEFAULT;
		}

		b.attr(CONF_KEY, null);
		return hcc;
	}

	@SuppressWarnings("unchecked")
	static HttpServerConfiguration getOrCreate(ServerBootstrap b) {

		HttpServerConfiguration hcc = (HttpServerConfiguration) b.config()
		                                                         .attrs()
		                                                         .get(CONF_KEY);

		if (hcc == null) {
			hcc = new HttpServerConfiguration();
			b.attr(CONF_KEY, hcc);
		}

		return hcc;
	}

	static final Function<ServerBootstrap, ServerBootstrap> MAP_COMPRESS = b -> {
		getOrCreate(b).minCompressionSize = 0;
		return b;
	};

	static final Function<ServerBootstrap, ServerBootstrap> MAP_NO_COMPRESS = b -> {
		HttpServerConfiguration c = getOrCreate(b);
		c.minCompressionSize = -1;
		c.compressPredicate = null;
		return b;
	};

	static final Function<ServerBootstrap, ServerBootstrap> MAP_FORWARDED = b -> {
		getOrCreate(b).forwarded = true;
		return b;
	};

	static final Function<ServerBootstrap, ServerBootstrap> MAP_NO_FORWARDED = b -> {
		getOrCreate(b).forwarded = false;
		return b;
	};

	static ServerBootstrap compressSize(ServerBootstrap b, int minCompressionSize) {
		getOrCreate(b).minCompressionSize = minCompressionSize;
		return b;
	}

	static ServerBootstrap protocols(ServerBootstrap b, HttpProtocol... protocols) {
		int _protocols = 0;

		for (HttpProtocol p : protocols) {
			if (p == HttpProtocol.HTTP11) {
				_protocols |= h11;
			}
			else if (p == HttpProtocol.H2) {
				_protocols |= h2;
			}
			else if (p == HttpProtocol.H2C) {
				_protocols |= h2c;
			}
		}

		getOrCreate(b).protocols = _protocols;
		return b;
	}

	static ServerBootstrap compressPredicate(ServerBootstrap b,
			BiPredicate<HttpServerRequest, HttpServerResponse> compressPredicate) {
		getOrCreate(b).compressPredicate = compressPredicate;
		return b;
	}

	static ServerBootstrap decoder(ServerBootstrap b,
			HttpRequestDecoderSpec decoder) {
		getOrCreate(b).decoder = decoder;
		return b;
	}

	static ServerBootstrap cookieCodec(ServerBootstrap b,
			ServerCookieEncoder encoder, ServerCookieDecoder decoder) {
		HttpServerConfiguration conf = getOrCreate(b);
		conf.cookieEncoder = encoder;
		conf.cookieDecoder = decoder;
		return b;
	}

	static final int h11      = 0b100;
	static final int h2       = 0b010;
	static final int h2c      = 0b001;
	static final int h11orH2c = h11 | h2c;
	static final int h11orH2  = h11 | h2;
}
