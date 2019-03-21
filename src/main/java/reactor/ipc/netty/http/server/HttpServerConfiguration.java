/*
 * Copyright (c) 2011-2018 Pivotal Software Inc, All Rights Reserved.
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

package reactor.ipc.netty.http.server;

import java.util.function.BiPredicate;
import java.util.function.Function;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.util.AttributeKey;

/**
 * @author Stephane Maldini
 */
final class HttpServerConfiguration {

	static final HttpServerConfiguration DEFAULT = new HttpServerConfiguration();

	static final AttributeKey<HttpServerConfiguration> CONF_KEY =
			AttributeKey.newInstance("httpServerConf");

	int                                                minCompressionSize = -1;
	BiPredicate<HttpServerRequest, HttpServerResponse> compressPredicate  = null;
	boolean                                            forwarded          = false;
	HttpRequestDecoderConfiguration                    decoder            =
			new HttpRequestDecoderConfiguration();

	static HttpServerConfiguration getAndClean(ServerBootstrap b) {
		HttpServerConfiguration hcc = (HttpServerConfiguration) b.config()
		                                                         .attrs()
		                                                         .get(CONF_KEY);
		b.attr(CONF_KEY, null);
		if (hcc == null) {
			hcc = DEFAULT;
		}

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

	static ServerBootstrap compressPredicate(ServerBootstrap b,
			BiPredicate<HttpServerRequest, HttpServerResponse> compressPredicate) {
		getOrCreate(b).compressPredicate = compressPredicate;
		return b;
	}

	static ServerBootstrap decoder(ServerBootstrap b,
			HttpRequestDecoderConfiguration decoder) {
		getOrCreate(b).decoder = decoder;
		return b;
	}
}
