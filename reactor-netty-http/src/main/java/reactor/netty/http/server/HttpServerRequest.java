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
package reactor.netty.http.server;

import java.net.InetSocketAddress;
import java.time.ZonedDateTime;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Function;

import io.netty.handler.codec.http.HttpContent;
import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.multipart.Attribute;
import io.netty.handler.codec.http.multipart.FileUpload;
import io.netty.handler.codec.http.multipart.HttpData;
import reactor.core.publisher.Flux;
import reactor.netty.Connection;
import reactor.netty.NettyInbound;
import reactor.util.annotation.Nullable;

/**
 * An Http Reactive Channel with several accessors related to HTTP flow: headers, params,
 * URI, method, websocket...
 *
 * @author Stephane Maldini
 * @since 0.5
 */
public interface HttpServerRequest extends NettyInbound, HttpServerInfos {

	@Override
	HttpServerRequest withConnection(Consumer<? super Connection> withConnection);

	/**
	 * URI parameter captured via {@code {}} e.g. {@code /test/{param}}.
	 *
	 * @param key parameter name e.g. {@code "param"} in URI {@code /test/{param}}
	 * @return the parameter captured value
	 */
	@Nullable
	String param(CharSequence key);

	/**
	 * Returns all URI parameters captured via {@code {}} e.g. {@code /test/{param1}/{param2}} as key/value map.
	 *
	 * @return the parameters captured key/value map
	 */
	@Nullable
	Map<String, String> params();

	/**
	 * Specifies a params resolver.
	 *
	 * @param paramsResolver a params resolver
	 *
	 * @return this {@link HttpServerRequest}
	 */
	HttpServerRequest paramsResolver(Function<? super String, Map<String, String>> paramsResolver);

	/**
	 * Returns a {@link Flux} of {@link HttpContent} containing received chunks.
	 *
	 * @return a {@link Flux} of {@link HttpContent} containing received chunks
	 */
	default Flux<HttpContent> receiveContent() {
		return receiveObject().ofType(HttpContent.class);
	}

	/**
	 * Returns true if the request has {@code Content-Type} with value {@code application/x-www-form-urlencoded}.
	 *
	 * @return true if the request has {@code Content-Type} with value {@code application/x-www-form-urlencoded},
	 * false - otherwise
	 * @since 1.0.11
	 */
	boolean isFormUrlencoded();

	/**
	 * Returns true if the request has {@code Content-Type} with value {@code multipart/form-data}.
	 *
	 * @return true if the request has {@code Content-Type} with value {@code multipart/form-data},
	 * false - otherwise
	 * @since 1.0.11
	 */
	boolean isMultipart();

	/**
	 * When the request is {@code POST} and have {@code Content-Type} with value
	 * {@code application/x-www-form-urlencoded} or {@code multipart/form-data},
	 * returns a {@link Flux} of {@link HttpData} containing received {@link Attribute}/{@link FileUpload}.
	 * When the request is not {@code POST} or does not have {@code Content-Type}
	 * with value {@code application/x-www-form-urlencoded} or {@code multipart/form-data},
	 * a {@link Flux#error(Throwable)} will be returned.
	 * <p>Uses HTTP form decoder configuration specified on server level or the default one if nothing is configured.
	 * <p>{@link HttpData#retain()} disables auto memory release on each {@link HttpData} published,
	 * retaining in order to prevent premature recycling when {@link HttpData} is accumulated downstream.
	 *
	 * @return a {@link Flux} of {@link HttpData} containing received {@link Attribute}/{@link FileUpload}
	 * @since 1.0.11
	 */
	Flux<HttpData> receiveForm();

	/**
	 * When the request is {@code POST} and have {@code Content-Type} with value
	 * {@code application/x-www-form-urlencoded} or {@code multipart/form-data},
	 * returns a {@link Flux} of {@link HttpData} containing received {@link Attribute}/{@link FileUpload}.
	 * When the request is not {@code POST} or does not have {@code Content-Type}
	 * with value {@code application/x-www-form-urlencoded} or {@code multipart/form-data},
	 * a {@link Flux#error(Throwable)} will be returned.
	 * <p>{@link HttpData#retain()} disables auto memory release on each {@link HttpData} published,
	 * retaining in order to prevent premature recycling when {@link HttpData} is accumulated downstream.
	 *
	 * @param formDecoderBuilder {@link HttpServerFormDecoderProvider.Builder} for HTTP form decoder configuration
	 * @return a {@link Flux} of {@link HttpData} containing received {@link Attribute}/{@link FileUpload}
	 * @since 1.0.11
	 */
	Flux<HttpData> receiveForm(Consumer<HttpServerFormDecoderProvider.Builder> formDecoderBuilder);

	@Nullable
	@Override
	InetSocketAddress hostAddress();

	@Nullable
	@Override
	InetSocketAddress remoteAddress();

	/**
	 * Returns inbound {@link HttpHeaders}.
	 *
	 * @return inbound {@link HttpHeaders}
	 */
	HttpHeaders requestHeaders();

	/**
	 * Returns the inbound protocol and version.
	 *
	 * @return the inbound protocol and version
	 * @since 1.0.28
	 */
	String protocol();

	/**
	 * Returns the time when the request was received.
	 *
	 * @return the time when the request was received
	 * @since 1.0.28
	 */
	ZonedDateTime timestamp();
}
