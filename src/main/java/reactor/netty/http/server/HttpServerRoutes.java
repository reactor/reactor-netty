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

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Objects;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.Predicate;
import javax.annotation.Nullable;

import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpHeaderValues;
import io.netty.handler.codec.http.HttpMethod;
import org.reactivestreams.Publisher;
import reactor.netty.ByteBufFlux;
import reactor.netty.http.websocket.WebsocketInbound;
import reactor.netty.http.websocket.WebsocketOutbound;

/**
 * Server routes are unique and only the first matching in order of declaration will be
 * invoked.
 *
 * @author Stephane Maldini
 */
public interface HttpServerRoutes extends
                                  BiFunction<HttpServerRequest, HttpServerResponse, Publisher<Void>> {

	/**
	 * Returns a new default routing registry {@link HttpServerRoutes}
	 *
	 * @return a new default routing registry {@link HttpServerRoutes}
	 */
	static HttpServerRoutes newRoutes() {
		return new DefaultHttpServerRoutes();
	}

	/**
	 * Listens for HTTP DELETE on the passed path to be used as a routing condition.
	 * Incoming connections will query the internal registry to invoke the matching
	 * handler.
	 * <p>Additional regex matching is available, e.g. "/test/{param}".
	 * Params are resolved using {@link HttpServerRequest#param(CharSequence)}</p>
	 *
	 * @param path The DELETE path used by clients.
	 * @param handler an I/O handler to invoke for the given condition
	 *
	 * @return this {@link HttpServerRoutes}
	 */
	default HttpServerRoutes delete(String path,
			BiFunction<? super HttpServerRequest, ? super HttpServerResponse, ? extends Publisher<Void>> handler) {
		return route(HttpPredicate.delete(path), handler);
	}

	/**
	 * Listens for HTTP GET on the passed path to be used as a routing condition. The
	 * content of the provided  {@link Path directory} will be served.
	 * <p>Additional regex matching is available, e.g. "/test/{param}". Params are resolved
	 * using {@link HttpServerRequest#param(CharSequence)}</p>
	 *
	 * @param uri The GET path used by clients
	 * @param directory the root prefix to serve from the file system, e.g.
	 * "/Users/me/resources"
	 *
	 * @return this {@link HttpServerRoutes}
	 */
	default HttpServerRoutes directory(String uri, Path directory) {
		return directory(uri, directory, null);
	}

	/**
	 * Listens for HTTP GET on the passed path to be used as a routing condition.The
	 * content of the provided {@link Path directory} will be served.
	 * <p>Additional regex matching is available, e.g. "/test/{param}". Params are resolved
	 * using {@link HttpServerRequest#param(CharSequence)}</p>
	 *
	 * @param uri The GET path used by clients
	 * @param directory the root prefix to serve from the file system, e.g.
	 * "/Users/me/resources"
	 * @param interceptor a pre response processor
	 *
	 * @return this {@link HttpServerRoutes}
	 */
	HttpServerRoutes directory(String uri, Path directory,
			@Nullable Function<HttpServerResponse, HttpServerResponse> interceptor);

	/**
	 * Listens for HTTP GET on the passed path to be used as a routing condition. The
	 * provided {@link java.io.File} will be served.
	 * <p>Additional regex matching is available, e.g. "/test/{param}". Params are resolved
	 * using {@link HttpServerRequest#param(CharSequence)}</p>
	 *
	 * @param uri The GET path used by clients
	 * @param path the resource Path to serve
	 *
	 * @return this {@link HttpServerRoutes}
	 */
	default HttpServerRoutes file(String uri, Path path) {
		return file(HttpPredicate.get(uri), path, null);
	}

	/**
	 * Listens for HTTP GET on the passed path to be used as a routing condition. The file
	 * at provided path will be served.
	 * <p>Additional regex matching is available, e.g. "/test/{param}". Params are resolved
	 * using {@link HttpServerRequest#param(CharSequence)}</p>
	 *
	 * @param uri The GET path used by clients
	 * @param path the resource path to serve
	 *
	 * @return this {@link HttpServerRoutes}
	 */
	default HttpServerRoutes file(String uri, String path) {
		return file(HttpPredicate.get(uri), Paths.get(path), null);
	}

	/**
	 * Listens for HTTP GET on the passed path to be used as a routing condition. The
	 * file on the provided {@link Path} is served.
	 * <p>Additional regex matching is available e.g.
	 * "/test/{param}". Params are resolved using {@link HttpServerRequest#param(CharSequence)}</p>
	 *
	 * @param uri The {@link HttpPredicate} to use to trigger the route, pattern matching
	 * and capture are supported
	 * @param path the Path to the file to serve
	 * @param interceptor a channel pre-intercepting handler e.g. for content type header
	 *
	 * @return this {@link HttpServerRoutes}
	 */
	default HttpServerRoutes file(Predicate<HttpServerRequest> uri, Path path,
			@Nullable Function<HttpServerResponse, HttpServerResponse> interceptor) {
		Objects.requireNonNull(path, "path");
		return route(uri, (req, resp) -> {
			if (!Files.isReadable(path)) {
				return resp.send(ByteBufFlux.fromPath(path));
			}
			if (interceptor != null) {
				return interceptor.apply(resp)
				                  .sendFile(path);
			}
			return resp.sendFile(path);
		});
	}

	/**
	 * Listens for HTTP GET on the passed path to be used as a routing condition. Incoming
	 * connections will query the internal registry to invoke the matching handler.
	 * <p>Additional regex matching is available e.g.
	 * "/test/{param}". Params are resolved using {@link HttpServerRequest#param(CharSequence)}</p>
	 *
	 * @param path The GET path used by clients
	 * @param handler an I/O handler to invoke for the given condition
	 *
	 * @return this {@link HttpServerRoutes}
	 */
	default HttpServerRoutes get(String path,
			BiFunction<? super HttpServerRequest, ? super HttpServerResponse, ? extends Publisher<Void>> handler) {
		return route(HttpPredicate.get(path), handler);
	}

	/**
	 * Listens for HTTP HEAD on the passed path to be used as a routing condition. Incoming
	 * connections will query the internal registry to invoke the matching handler.
	 * <p>Additional regex matching is available e.g.
	 * "/test/{param}". Params are resolved using {@link HttpServerRequest#param(CharSequence)}</p>
	 *
	 * @param path The HEAD path used by clients
	 * @param handler an I/O handler to invoke for the given condition
	 *
	 * @return this {@link HttpServerRoutes}
	 */
	default HttpServerRoutes head(String path,
			BiFunction<? super HttpServerRequest, ? super HttpServerResponse, ? extends Publisher<Void>> handler) {
		return route(HttpPredicate.head(path), handler);
	}

	/**
	 * This route will be invoked when GET "/path" or "/path/" like uri are requested.
	 *
	 * @param handler an I/O handler to invoke on index/root request
	 *
	 * @return this {@link HttpServerRoutes}
	 */
	default HttpServerRoutes index(final BiFunction<? super HttpServerRequest, ? super HttpServerResponse, ? extends Publisher<Void>> handler) {
		return route(INDEX_PREDICATE, handler);
	}

	/**
	 * Listens for HTTP OPTIONS on the passed path to be used as a routing condition. Incoming
	 * connections will query the internal registry to invoke the matching handler.
	 * <p>Additional regex matching is available e.g.
	 * "/test/{param}". Params are resolved using {@link HttpServerRequest#param(CharSequence)}</p>
	 *
	 * @param path The OPTIONS path used by clients
	 * @param handler an I/O handler to invoke for the given condition
	 *
	 * @return this {@link HttpServerRoutes}
	 */
	default HttpServerRoutes options(String path,
			BiFunction<? super HttpServerRequest, ? super HttpServerResponse, ? extends Publisher<Void>> handler) {
		return route(HttpPredicate.options(path), handler);
	}

	/**
	 * Listens for HTTP POST on the passed path to be used as a routing condition. Incoming
	 * connections will query the internal registry to invoke the matching handler.
	 * <p>Additional regex matching is available e.g.
	 * "/test/{param}". Params are resolved using {@link HttpServerRequest#param(CharSequence)}</p>
	 *
	 * @param path The POST path used by clients
	 * @param handler an I/O handler to invoke for the given condition
	 *
	 * @return this {@link HttpServerRoutes}
	 */
	default HttpServerRoutes post(String path,
			BiFunction<? super HttpServerRequest, ? super HttpServerResponse, ? extends Publisher<Void>> handler) {
		return route(HttpPredicate.post(path), handler);
	}

	/**
	 * Listens for HTTP PUT on the passed path to be used as a routing condition. Incoming
	 * connections will query the internal registry to invoke the matching handler.
	 * <p>Additional regex matching is available e.g.
	 * "/test/{param}". Params are resolved using {@link HttpServerRequest#param(CharSequence)}</p>
	 *
	 * @param path The PUT path used by clients
	 * @param handler an I/O handler to invoke for the given condition
	 *
	 * @return this {@link HttpServerRoutes}
	 */
	default HttpServerRoutes put(String path,
			BiFunction<? super HttpServerRequest, ? super HttpServerResponse, ? extends Publisher<Void>> handler) {
		return route(HttpPredicate.put(path), handler);
	}

	/**
	 * A generic route predicate that if matched invoke the passed I/O handler.
	 *
	 * @param condition a predicate given each inbound request
	 * @param handler the I/O handler to invoke on match
	 *
	 * @return this {@link HttpServerRoutes}
	 */
	HttpServerRoutes route(Predicate<? super HttpServerRequest> condition,
			BiFunction<? super HttpServerRequest, ? super HttpServerResponse, ? extends Publisher<Void>> handler);

	/**
	 * Listens for websocket on the passed path to be used as a routing condition. Incoming
	 * connections will query the internal registry to invoke the matching handler.
	 * <p>Additional regex matching is available e.g. "/test/{param}".
	 * Params are resolved using {@link HttpServerRequest#param(CharSequence)}
	 * They are NOT accessible in the I/O handler provided as parameter.</p>
	 *
	 * @param path The websocket path used by clients
	 * @param handler an I/O handler to invoke for the given condition
	 *
	 * @return this {@link HttpServerRoutes}
	 */
	default HttpServerRoutes ws(String path,
			BiFunction<? super WebsocketInbound, ? super WebsocketOutbound, ? extends Publisher<Void>> handler) {
		return ws(path, handler, WebsocketServerSpec.builder().build());
	}

	/**
	 * Listens for websocket on the passed path to be used as a routing condition. Incoming
	 * connections will query the internal registry to invoke the matching handler.
	 * <p>Additional regex matching is available e.g. "/test/{param}".
	 * Params are resolved using {@link HttpServerRequest#param(CharSequence)}
	 * They are NOT accessible in the handler provided as parameter.</p>
	 *
	 * @param path The websocket path used by clients
	 * @param handler an I/O handler to invoke for the given condition
	 * @param configurer {@link WebsocketServerSpec} for websocket configuration
	 * @return this {@link HttpServerRoutes}
	 * @since 0.9.5
	 */
	default HttpServerRoutes ws(String path,
			BiFunction<? super WebsocketInbound, ? super WebsocketOutbound,? extends Publisher<Void>> handler,
			WebsocketServerSpec configurer) {
		return ws(HttpPredicate.get(path), handler, configurer);
	}

	/**
	 * Listens for websocket with the given route predicate to invoke the matching handler.
	 *
	 * @param condition a predicate given each inbound request
	 * @param handler an I/O handler to invoke for the given condition
	 * @param websocketServerSpec {@link WebsocketServerSpec} for websocket configuration
	 * @return this {@link HttpServerRoutes}
	 * @since 0.9.5
	 */
	default HttpServerRoutes ws(Predicate<? super HttpServerRequest> condition,
			BiFunction<? super WebsocketInbound, ? super WebsocketOutbound, ? extends Publisher<Void>> handler,
			WebsocketServerSpec websocketServerSpec) {
		return route(condition, (req, resp) -> {
			if (req.requestHeaders()
			       .containsValue(HttpHeaderNames.CONNECTION, HttpHeaderValues.UPGRADE, true)) {
				HttpServerOperations ops = (HttpServerOperations) req;
				return ops.withWebsocketSupport(req.uri(), websocketServerSpec, handler);
			}
			return resp.sendNotFound();
		});
	}

	Predicate<HttpServerRequest> INDEX_PREDICATE = req -> {
		URI uri = URI.create(req.uri());
		return Objects.equals(req.method(), HttpMethod.GET) &&
		       (uri.getPath()
		           .endsWith("/") || uri.getPath()
		                                .indexOf(".",
		                                         uri.getPath()
		                                            .lastIndexOf("/")) == -1);
	};

}
