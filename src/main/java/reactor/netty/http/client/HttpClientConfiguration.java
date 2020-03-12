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

package reactor.netty.http.client;

import java.util.function.BiFunction;
import java.util.function.BiPredicate;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.regex.Pattern;
import javax.annotation.Nullable;

import io.netty.bootstrap.Bootstrap;
import io.netty.handler.codec.http.DefaultHttpHeaders;
import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpUtil;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.codec.http.cookie.ClientCookieDecoder;
import io.netty.handler.codec.http.cookie.ClientCookieEncoder;
import io.netty.util.AttributeKey;
import org.reactivestreams.Publisher;
import reactor.core.publisher.Mono;
import reactor.netty.NettyOutbound;
import reactor.netty.http.HttpProtocol;

/**
 * @author Stephane Maldini
 */
final class HttpClientConfiguration {

	static final HttpClientConfiguration DEFAULT = new HttpClientConfiguration();

	static final AttributeKey<HttpClientConfiguration> CONF_KEY =
			AttributeKey.newInstance("httpClientConf");

	boolean                       acceptGzip                     = false;
	String                        uri                            = null;
	String                        baseUrl                        = null;
	HttpHeaders                   headers                        = null;
	HttpMethod                    method                         = HttpMethod.GET;
	String                        websocketSubprotocols          = null;
	int                           websocketMaxFramePayloadLength = 65536;
	boolean                       websocketProxyPing             = false;
	int                           protocols                      = h11;
	HttpResponseDecoderSpec       decoder                        = new HttpResponseDecoderSpec();

	ClientCookieEncoder cookieEncoder = ClientCookieEncoder.STRICT;
	ClientCookieDecoder cookieDecoder = ClientCookieDecoder.STRICT;

	BiPredicate<HttpClientRequest, HttpClientResponse> followRedirectPredicate = null;
	Consumer<HttpClientRequest> redirectRequestConsumer = null;

	Function<Mono<HttpClientConfiguration>, Mono<HttpClientConfiguration>> deferredConf                   = null;

	BiFunction<? super HttpClientRequest, ? super NettyOutbound, ? extends Publisher<Void>>
			body;

	HttpClientConfiguration() {
	}

	HttpClientConfiguration(HttpClientConfiguration from) {
		this.uri = from.uri;
		this.acceptGzip = from.acceptGzip;
		this.cookieEncoder = from.cookieEncoder;
		this.cookieDecoder = from.cookieDecoder;
		this.decoder = from.decoder;
		this.followRedirectPredicate = from.followRedirectPredicate;
		this.redirectRequestConsumer = from.redirectRequestConsumer;
		this.baseUrl = from.baseUrl;
		this.headers = from.headers;
		this.method = from.method;
		this.websocketSubprotocols = from.websocketSubprotocols;
		this.websocketMaxFramePayloadLength = from.websocketMaxFramePayloadLength;
		this.websocketProxyPing = from.websocketProxyPing;
		this.body = from.body;
		this.protocols = from.protocols;
		this.deferredConf = from.deferredConf;
	}

	static HttpClientConfiguration getAndClean(Bootstrap b) {
		HttpClientConfiguration hcc = (HttpClientConfiguration) b.config()
		                                                         .attrs()
		                                                         .get(CONF_KEY);
		if (hcc == null) {
			return DEFAULT;
		}

		b.attr(CONF_KEY, null);
		return hcc;
	}

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

	static HttpClientConfiguration get(Bootstrap b) {

		HttpClientConfiguration hcc = (HttpClientConfiguration) b.config()
		                                                         .attrs()
		                                                         .get(CONF_KEY);

		if (hcc == null) {
			return DEFAULT;
		}

		return hcc;
	}

	static final Function<Bootstrap, Bootstrap> MAP_KEEPALIVE = b -> {
		HttpClientConfiguration c = getOrCreate(b);
		if ( c.headers == null ) {
			//default is keep alive no need to change
			return b;
		}

		HttpUtil.setKeepAlive(c.headers, HttpVersion.HTTP_1_1, true);

		return b;
	};

	static final Function<Bootstrap, Bootstrap> MAP_NO_KEEPALIVE = b -> {
		HttpClientConfiguration c = getOrCreate(b);
		if ( c.headers == null ) {
			c.headers = new DefaultHttpHeaders();
		}
		HttpUtil.setKeepAlive(c.headers, HttpVersion.HTTP_1_1, false);
		return b;
	};

	static final Function<Bootstrap, Bootstrap> MAP_COMPRESS = b -> {
		getOrCreate(b).acceptGzip = true;
		return b;
	};


	static final Function<Bootstrap, Bootstrap> MAP_NO_COMPRESS = b -> {
		getOrCreate(b).acceptGzip = false;
		return b;
	};


	static final Pattern FOLLOW_REDIRECT_CODES = Pattern.compile("30[1278]");
	static final BiPredicate<HttpClientRequest, HttpClientResponse> FOLLOW_REDIRECT_PREDICATE =
			(req, res) -> FOLLOW_REDIRECT_CODES.matcher(res.status()
			                                               .codeAsText())
			                                   .matches();

	static final Function<Bootstrap, Bootstrap> MAP_NO_REDIRECT = b -> {
		HttpClientConfiguration conf = getOrCreate(b);
		conf.followRedirectPredicate = null;
		conf.redirectRequestConsumer = null;
		return b;
	};

	static Bootstrap uri(Bootstrap b, String uri) {
		getOrCreate(b).uri = uri;
		return b;
	}

	HttpClientConfiguration uri(String uri) {
		this.uri = uri;
		return this;
	}

	static Bootstrap baseUrl(Bootstrap b, String baseUrl) {
		getOrCreate(b).baseUrl = baseUrl;
		return b;
	}

	static Bootstrap deferredConf(Bootstrap b, Function<HttpClientConfiguration, Mono<HttpClientConfiguration>> deferrer) {
		HttpClientConfiguration c = getOrCreate(b);
		if (c.deferredConf != null){
			c.deferredConf = c.deferredConf.andThen(deferredConf -> deferredConf.flatMap(deferrer));
		}
		else {
			c.deferredConf = deferredConf -> deferredConf.flatMap(deferrer);
		}
		return b;
	}

	static Bootstrap headers(Bootstrap b, HttpHeaders headers) {
		getOrCreate(b).headers = headers;
		return b;
	}

	HttpClientConfiguration headers(HttpHeaders headers) {
		this.headers = headers;
		return this;
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

	static Bootstrap protocols(Bootstrap b, HttpProtocol... protocols) {
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

	static Bootstrap websocketSubprotocols(Bootstrap b, String websocketSubprotocols) {
		getOrCreate(b).websocketSubprotocols = websocketSubprotocols;
		return b;
	}

	static Bootstrap websocketMaxFramePayloadLength(Bootstrap b, int websocketMaxFramePayloadLength) {
		getOrCreate(b).websocketMaxFramePayloadLength = websocketMaxFramePayloadLength;
		return b;
	}

	static Bootstrap websocketProxyPing(Bootstrap b, boolean websocketProxyPing) {
		getOrCreate(b).websocketProxyPing = websocketProxyPing;
		return b;
	}

	static Bootstrap cookieCodec(Bootstrap b, ClientCookieEncoder encoder, ClientCookieDecoder decoder) {
		HttpClientConfiguration conf = getOrCreate(b);
		conf.cookieEncoder = encoder;
		conf.cookieDecoder = decoder;
		return b;
	}

	static Bootstrap followRedirectPredicate(Bootstrap b, BiPredicate<HttpClientRequest, HttpClientResponse> predicate,
			@Nullable Consumer<HttpClientRequest> redirectRequestConsumer) {
		HttpClientConfiguration conf = getOrCreate(b);
		conf.followRedirectPredicate = predicate;
		conf.redirectRequestConsumer = redirectRequestConsumer;
		return b;
	}

	static Bootstrap decoder(Bootstrap b, HttpResponseDecoderSpec decoder) {
		getOrCreate(b).decoder = decoder;
		return b;
	}

	static final int h11      = 0b100;
	static final int h2       = 0b010;
	static final int h2c      = 0b001;
	static final int h11orH2c = h11 | h2c;
}
