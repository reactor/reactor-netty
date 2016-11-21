/*
 * Copyright (c) 2011-2016 Pivotal Software Inc, All Rights Reserved.
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

package reactor.ipc.netty.http.server;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.Predicate;

import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpHeaderValues;
import org.reactivestreams.Publisher;
import reactor.core.Exceptions;
import reactor.core.publisher.Mono;
import reactor.ipc.netty.http.HttpInbound;
import reactor.ipc.netty.http.HttpOutbound;

/**
 * @author Stephane Maldini
 */
public interface HttpServerRoutes extends
                           BiFunction<HttpServerRequest, HttpServerResponse, Iterable<? extends BiFunction<? super HttpServerRequest, ? super HttpServerResponse, ? extends Publisher<Void>>>> {

	/**
	 * @return a new default routing registry {@link HttpServerRoutes}
	 */
	static HttpServerRoutes newRoutes() {
		return new DefaultHttpServerRoutes();
	}

	/**
	 * Listen for HTTP DELETE on the passed path to be used as a routing condition.
	 * Incoming connections will query the internal registry to invoke the matching
	 * handlers. <p> Additional regex matching is available when reactor-bus is on the
	 * classpath. e.g. "/test/{param}". Params are resolved using {@link
	 * HttpServerRequest#param(CharSequence)}
	 *
	 * @param path The {@link HttpPredicate} to resolve against this path, pattern
	 * matching and capture are supported
	 * @param handler an handler to invoke for the given condition
	 *
	 * @return this {@link HttpServerRoutes}
	 */

	default HttpServerRoutes delete(String path,
			final BiFunction<? super HttpServerRequest, ? super HttpServerResponse, ? extends Publisher<Void>> handler) {
		return route(HttpPredicate.delete(path), handler);
	}

	/**
	 * Listen for HTTP GET on the passed path to be used as a routing condition. Incoming
	 * connections will query the internal registry to invoke the matching handlers. <p>
	 * Additional regex matching is available when reactor-bus is on the classpath. e.g.
	 * "/test/{param}". Params are resolved using {@link HttpServerRequest#param(CharSequence)}
	 *
	 * @param path The {@link HttpPredicate} to resolve against this
	 * path, pattern matching and capture are supported
	 * @param directory the File to serve
	 *
	 * @return this {@link HttpServerRoutes}
	 */
	default HttpServerRoutes directory(String path, final File directory) {
		return directory(path, directory.getAbsolutePath());
	}

	/**
	 * Listen for HTTP GET on the passed path to be used as a routing condition. Incoming
	 * connections will query the internal registry to invoke the matching handlers. <p>
	 * Additional regex matching is available when reactor-bus is on the classpath. e.g.
	 * "/test/{param}". Params are resolved using {@link HttpServerRequest#param(CharSequence)}
	 *
	 * @param path The {@link HttpPredicate} to resolve against this
	 * path, pattern matching and capture are supported
	 * @param directory the Path to the file to serve
	 *
	 * @return this {@link HttpServerRoutes}
	 */
	default HttpServerRoutes directory(final String path, final String directory) {
		return directory(path, directory, null);
	}

	/**
	 * Listen for HTTP GET on the passed path to be used as a routing condition. Incoming
	 * connections will query the internal registry to invoke the matching handlers. <p>
	 * Additional regex matching is available when reactor-bus is on the classpath. e.g.
	 * "/test/{param}". Params are resolved using {@link HttpServerRequest#param(CharSequence)}
	 *
	 * @param path The {@link HttpPredicate} to resolve against this
	 * path, pattern matching and capture are supported
	 * @param directory the Path to the file to serve
	 *
	 * @return this {@link HttpServerRoutes}
	 */
	default HttpServerRoutes directory(final String path,
			final String directory,
			final Function<HttpServerResponse, HttpServerResponse> interceptor) {
		return route(HttpPredicate.prefix(path), (req, resp) -> {
			String strippedPrefix = req.uri()
			                           .replaceFirst(path, "");
			int paramIndex = strippedPrefix.lastIndexOf("?");
			if (paramIndex != -1) {
				strippedPrefix = strippedPrefix.substring(0, paramIndex);
			}

			Path p = Paths.get(directory + strippedPrefix);
			if (Files.isReadable(p)) {

				if (interceptor != null) {
					return interceptor.apply(resp)
					                  .sendFile(p.toFile());
				}
				return resp.sendFile(p.toFile());
			}
			else {
				return Mono.error(new Exception("File not found or is not readable: "+p));
			}
		});
	}

	/**
	 * Listen for HTTP GET on the passed path to be used as a routing condition. Incoming
	 * connections will query the internal registry to invoke the matching handlers. <p>
	 * Additional regex matching is available when reactor-bus is on the classpath. e.g.
	 * "/test/{param}". Params are resolved using {@link HttpServerRequest#param(CharSequence)}
	 *
	 * @param path The {@link HttpPredicate} to resolve against this
	 * path, pattern matching and capture are supported
	 * @param file the File to serve
	 *
	 * @return this {@link HttpServerRoutes}
	 */
	default HttpServerRoutes file(String path, final File file) {
		return file(HttpPredicate.get(path), file.getAbsolutePath(), null);
	}

	/**
	 * Listen for HTTP GET on the passed path to be used as a routing condition. Incoming
	 * connections will query the internal registry to invoke the matching handlers. <p>
	 * Additional regex matching is available when reactor-bus is on the classpath. e.g.
	 * "/test/{param}". Params are resolved using {@link HttpServerRequest#param(CharSequence)}
	 *
	 * @param path The {@link HttpPredicate} to resolve against this path, pattern
	 * matching and capture are supported
	 * @param filepath the Path to the file to serve
	 *
	 * @return this {@link HttpServerRoutes}
	 */
	default HttpServerRoutes file(String path, final String filepath) {
		return file(HttpPredicate.get(path), filepath, null);
	}

	/**
	 * Listen for HTTP GET on the passed path to be used as a routing condition. Incoming
	 * connections will query the internal registry to invoke the matching handlers. <p>
	 * Additional regex matching is available when reactor-bus is on the classpath. e.g.
	 * "/test/{param}". Params are resolved using {@link HttpServerRequest#param(CharSequence)}
	 *
	 * @param path The {@link HttpPredicate} to resolve against this
	 * path, pattern matching and capture are supported
	 * @param filepath the Path to the file to serve
	 * @param interceptor a channel pre-intercepting handler e.g. for content type header
	 *
	 * @return this {@link HttpServerRoutes}
	 */
	default HttpServerRoutes file(Predicate<HttpServerRequest> path,
			final String filepath,
			final Function<HttpServerResponse, HttpServerResponse> interceptor) {
		final File file = new File(filepath);
		return route(path, (req, resp) -> {
			if (interceptor != null) {
				return interceptor.apply(resp)
				                  .sendFile(file);
			}
			return resp.sendFile(file);
		});
	}

	/**
	 * Listen for HTTP GET on the passed path to be used as a routing condition. Incoming
	 * connections will query the internal registry to invoke the matching handlers. <p>
	 * Additional regex matching is available when reactor-bus is on the classpath. e.g.
	 * "/test/{param}". Params are resolved using {@link HttpServerRequest#param(CharSequence)}
	 *
	 * @param path The {@link HttpPredicate} to resolve against this
	 * path, pattern matching and capture are supported
	 * @param handler an handler to invoke for the given condition
	 *
	 * @return this {@link HttpServerRoutes}
	 */
	default HttpServerRoutes get(String path,
			final BiFunction<? super HttpServerRequest, ? super HttpServerResponse, ? extends Publisher<Void>> handler) {
		return route(HttpPredicate.get(path), handler);
	}

	/**
	 * Listen for HTTP POST on the passed path to be used as a routing condition. Incoming
	 * connections will query the internal registry to invoke the matching handlers. <p>
	 * Additional regex matching is available when reactor-bus is on the classpath. e.g.
	 * "/test/{param}". Params are resolved using {@link HttpServerRequest#param(CharSequence)}
	 *
	 * @param path The {@link HttpPredicate} to resolve against this
	 * path, pattern matching and capture are supported
	 * @param handler an handler to invoke for the given condition
	 *
	 * @return this {@link HttpServerRoutes}
	 */
	default HttpServerRoutes post(String path,
			final BiFunction<? super HttpServerRequest, ? super HttpServerResponse, ? extends Publisher<Void>> handler) {
		return route(HttpPredicate.post(path), handler);
	}

	/**
	 * Listen for HTTP PUT on the passed path to be used as a routing condition. Incoming
	 * connections will query the internal registry to invoke the matching handlers. <p>
	 * Additional regex matching is available when reactor-bus is on the classpath. e.g.
	 * "/test/{param}". Params are resolved using {@link HttpServerRequest#param(CharSequence)}
	 *
	 * @param path The {@link HttpPredicate} to resolve against this
	 * path, pattern matching and capture are supported
	 * @param handler an handler to invoke for the given condition
	 *
	 * @return this {@link HttpServerRoutes}
	 */
	default HttpServerRoutes put(String path,
			final BiFunction<? super HttpServerRequest, ? super HttpServerResponse, ? extends Publisher<Void>> handler) {
		return route(HttpPredicate.put(path), handler);
	}

	/**
	 * @param condition
	 * @param handler
	 *
	 * @return this {@link HttpServerRoutes}
	 */
	HttpServerRoutes route(Predicate<? super HttpServerRequest> condition,
			BiFunction<? super HttpServerRequest, ? super HttpServerResponse, ? extends Publisher<Void>> handler);

	/**
	 * Listen for WebSocket on the passed path to be used as a routing condition. Incoming
	 * connections will query the internal registry to invoke the matching handlers. <p>
	 * Additional regex matching is available when reactor-bus is on the classpath. e.g.
	 * "/test/{param}". Params are resolved using {@link HttpServerRequest#param(CharSequence)}
	 *
	 * @param path The {@link HttpPredicate} to resolve against this
	 * path, pattern matching and capture are supported
	 * @param handler an handler to invoke for the given condition
	 *
	 * @return this {@link HttpServerRoutes}
	 */
	default HttpServerRoutes ws(String path,
			final BiFunction<? super HttpServerRequest, ? super HttpServerResponse, ? extends Publisher<Void>> handler) {
		return ws(path, handler, null);
	}

	/**
	 * Listen for WebSocket on the passed path to be used as a routing condition. Incoming
	 * connections will query the internal registry to invoke the matching handlers. <p>
	 * Additional regex matching is available when reactor-bus is on the classpath. e.g.
	 * "/test/{param}". Params are resolved using {@link HttpServerRequest#param(CharSequence)}
	 *
	 * @param path The {@link HttpPredicate} to resolve against this
	 * path, pattern matching and capture are supported
	 * @param handler an handler to invoke for the given condition
	 * @param protocols
	 *
	 * @return a new handler
	 */
	@SuppressWarnings("unchecked")
	default HttpServerRoutes ws(String path,
			final BiFunction<? super HttpServerRequest, ? super HttpServerResponse, ? extends Publisher<Void>> handler,
			final String protocols) {
		Predicate<HttpServerRequest> condition = HttpPredicate.get(path);

		return route(condition, (req, resp) -> {
			String connection = req.requestHeaders()
			                       .get(HttpHeaderNames.CONNECTION);
			HttpServerOperations ops = (HttpServerOperations) req;
			if (connection != null && connection.equals(HttpHeaderValues.UPGRADE.toString())) {
				return ops.withWebsocketSupport(req.uri(), protocols, false,
						(BiFunction<HttpInbound, HttpOutbound, Publisher<Void>>)handler);
			}
			return handler.apply(req, resp);
		});
	}

}
