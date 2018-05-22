/*
 * Copyright (c) 2011-2018 Pivotal Software Inc, All Rights Reserved.
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

package reactor.ipc.netty.http.client;

import java.util.function.BiFunction;
import java.util.function.Function;
import javax.annotation.Nullable;

import io.netty.bootstrap.Bootstrap;
import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.util.AttributeKey;
import org.reactivestreams.Publisher;
import reactor.core.publisher.Mono;
import reactor.ipc.netty.NettyOutbound;

/**
 * @author Stephane Maldini
 */
final class HttpClientConfiguration {

	static final HttpClientConfiguration DEFAULT = new HttpClientConfiguration();

	static final AttributeKey<HttpClientConfiguration> CONF_KEY =
			AttributeKey.newInstance("httpClientConf");

	boolean      acceptGzip            = false;
	boolean      followRedirect        = false;
	Boolean      chunkedTransfer       = null;
	Mono<String> deferredUri           = null;
	String       uri                   = null;
	String       baseUrl               = null;
	HttpHeaders  headers               = null;
	HttpMethod   method                = HttpMethod.GET;
	String       websocketSubprotocols = null;

	BiFunction<? super HttpClientRequest, ? super NettyOutbound, ? extends Publisher<Void>>
			body;

	HttpClientConfiguration() {
	}

	HttpClientConfiguration(HttpClientConfiguration from, String uri) {
		this.uri = uri;
		this.acceptGzip = from.acceptGzip;
		this.followRedirect = from.followRedirect;
		this.chunkedTransfer = from.chunkedTransfer;
		this.baseUrl = from.baseUrl;
		this.headers = from.headers;
		this.method = from.method;
		this.websocketSubprotocols = from.websocketSubprotocols;
		this.body = from.body;
	}

	static HttpClientConfiguration getAndClean(Bootstrap b) {
		HttpClientConfiguration hcc = (HttpClientConfiguration) b.config()
		                                                         .attrs()
		                                                         .get(CONF_KEY);
		b.attr(CONF_KEY, null);
		if (hcc == null) {
			hcc = DEFAULT;
		}

		return hcc;
	}

	@SuppressWarnings("unchecked")
	static HttpClientConfiguration getOrCreate(Bootstrap b) {

		HttpClientConfiguration hcc = (HttpClientConfiguration) b.config()
		                                                         .attrs()
		                                                         .get(CONF_KEY);

		if (hcc == null) {
			hcc = new HttpClientConfiguration();
			b.attr(CONF_KEY, hcc);
		}

		return hcc;
	}

	static final Function<Bootstrap, Bootstrap> MAP_COMPRESS = b -> {
		getOrCreate(b).acceptGzip = true;
		return b;
	};


	static final Function<Bootstrap, Bootstrap> MAP_NO_COMPRESS = b -> {
		getOrCreate(b).acceptGzip = false;
		return b;
	};

	static final Function<Bootstrap, Bootstrap> MAP_CHUNKED = b -> {
		getOrCreate(b).chunkedTransfer = true;
		return b;
	};


	static final Function<Bootstrap, Bootstrap> MAP_NO_CHUNKED = b -> {
		getOrCreate(b).chunkedTransfer = false;
		return b;
	};


	static final Function<Bootstrap, Bootstrap> MAP_REDIRECT = b -> {
		getOrCreate(b).followRedirect = true;
		return b;
	};

	static final Function<Bootstrap, Bootstrap> MAP_NO_REDIRECT = b -> {
		getOrCreate(b).followRedirect = false;
		return b;
	};

	static Bootstrap uri(Bootstrap b, String uri) {
		getOrCreate(b).uri = uri;
		return b;
	}

	static Bootstrap baseUrl(Bootstrap b, String baseUrl) {
		getOrCreate(b).baseUrl = baseUrl;
		return b;
	}

	static Bootstrap deferredUri(Bootstrap b, Mono<String> uri) {
		getOrCreate(b).deferredUri = uri;
		return b;
	}

	static Bootstrap headers(Bootstrap b, HttpHeaders headers) {
		getOrCreate(b).headers = headers;
		return b;
	}

	@Nullable
	static HttpHeaders headers(Bootstrap b) {
		HttpClientConfiguration hcc = (HttpClientConfiguration) b.config()
		                                                         .attrs()
		                                                         .get(CONF_KEY);

		if (hcc == null) {
			return null;
		}
		return hcc.headers;
	}

	static Bootstrap method(Bootstrap b, HttpMethod method) {
		getOrCreate(b).method = method;
		return b;
	}

	static Bootstrap body(Bootstrap b,
			BiFunction<? super HttpClientRequest, ? super NettyOutbound, ? extends Publisher<Void>> body) {
		getOrCreate(b).body = body;
		return b;
	}

	static Bootstrap websocketSubprotocols(Bootstrap b, String websocketSubprotocols) {
		getOrCreate(b).websocketSubprotocols = websocketSubprotocols;
		return b;
	}
}
