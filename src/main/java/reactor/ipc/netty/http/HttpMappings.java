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
package reactor.ipc.netty.http;

import java.util.Iterator;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;
import java.util.function.Predicate;

import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpVersion;
import org.reactivestreams.Publisher;

/**
 * @author Stephane Maldini
 */
public abstract class HttpMappings
		implements Function<HttpChannel, Iterable<? extends Function<? super HttpChannel, ? extends Publisher<Void>>>> {

	static final boolean hasBus;

	static {
		boolean bus;
		try{
			RegistryHttpMappings.class.getClassLoader().loadClass("reactor.bus" +
					".registry.Registry");
			bus = true;
		}
		catch(ClassNotFoundException cfe){
			bus = false;
		}
		hasBus = bus;
	}

	/**
	 * An alias for {@link HttpMappings#http}.
	 * <p>
	 * Creates a {@link Predicate} based on a URI template filtering .
	 * <p>
	 * This will listen for DELETE Method.
	 *
	 * @param uri The string to compile into a URI template and use for matching
	 * @return The new {@link Predicate}.
	 * @see reactor.bus.selector.UriPathTemplate
	 * @see Predicate
	 */
	public static Predicate<HttpChannel> delete(String uri) {
		return http(uri, null, HttpMethod.DELETE);
	}

	/**
	 * An alias for {@link HttpMappings#http}.
	 * <p>
	 * Creates a {@link Predicate} based on a URI template filtering .
	 * <p>
	 * This will listen for GET Method.
	 *
	 * @param uri The string to compile into a URI template and use for matching
	 * @return The new {@link Predicate}.
	 * @see reactor.bus.selector.UriPathTemplate
	 * @see Predicate
	 */
	public static Predicate<HttpChannel> get(String uri) {
		return http(uri, null, HttpMethod.GET);
	}

	/**
	 * Creates a {@link Predicate} based on a URI template.
	 * This will listen for all Methods.
	 *
	 * @param uri The string to compile into a URI template and use for matching
	 * @return The new {@link HttpPredicate}.
	 * @see reactor.bus.selector.UriPathTemplate
	 * @see Predicate
	 */
	public static Predicate<HttpChannel> http(String uri, HttpVersion protocol, HttpMethod method) {
		if (null == uri) {
			return null;
		}

		if(hasBus && !FORCE_SIMPLE_MAPPINGS) {
			return new RegistryHttpMappings.HttpSelector(uri, protocol, method);
		}
		else{
			return new HttpPredicate(uri, protocol, method);
		}
	}

	/**
	 *
	 * @return
	 */
	public static HttpMappings newMappings() {
		if(hasBus && !FORCE_SIMPLE_MAPPINGS){
			return new RegistryHttpMappings();
		}
		else{
			return new SimpleHttpMappings();
		}
	}

	/**
	 * An alias for {@link HttpMappings#http}.
	 * <p>
	 * Creates a {@link Predicate} based on a URI template filtering .
	 * <p>
	 * This will listen for POST Method.
	 *
	 * @param uri The string to compile into a URI template and use for matching
	 * @return The new {@link Predicate}.
	 * @see reactor.bus.selector.UriPathTemplate
	 * @see Predicate
	 */
	public static Predicate<HttpChannel> post(String uri) {
		return http(uri, null, HttpMethod.POST);
	}

	/**
	 * An alias for {@link HttpMappings#get} prefix ([prefix]/**), useful for file system mapping.
	 * <p>
	 * Creates a {@link Predicate} based on a URI template filtering .
	 * <p>
	 * This will listen for WebSocket Method.
	 *
	 * @return The new {@link Predicate}.
	 * @see reactor.bus.selector.UriPathTemplate
	 * @see Predicate
	 */
	public static Predicate<HttpChannel> prefix(String prefix) {
		return prefix(prefix, HttpMethod.GET);
	}

	/**
	 * An alias for {@link HttpMappings#get} prefix (/**), useful for file system mapping.
	 * <p>
	 * Creates a {@link Predicate} based on a URI template filtering .
	 * <p>
	 * This will listen for WebSocket Method.
	 *
	 * @return The new {@link Predicate}.
	 * @see reactor.bus.selector.UriPathTemplate
	 * @see Predicate
	 */
	public static Predicate<HttpChannel> prefix(String prefix, HttpMethod method) {
		Objects.requireNonNull(prefix, "Prefix must be provided");

		String target = prefix.startsWith("/") ? prefix : "/".concat(prefix);
		//target = target.endsWith("/") ? target :  prefix.concat("/");
		if(hasBus && !FORCE_SIMPLE_MAPPINGS) {
			return new RegistryHttpMappings.HttpSelector(target+"**", null, method);
		}
		else{
			return new HttpPrefixPredicate(target, method);
		}
	}

	/**
	 * An alias for {@link HttpMappings#http}.
	 * <p>
	 * Creates a {@link Predicate} based on a URI template filtering .
	 * <p>
	 * This will listen for PUT Method.
	 *
	 * @param uri The string to compile into a URI template and use for matching
	 * @return The new {@link Predicate}.
	 * @see reactor.bus.selector.UriPathTemplate
	 * @see Predicate
	 */
	public static Predicate<HttpChannel> put(String uri) {
		return http(uri, null, HttpMethod.PUT);
	}

	/**
	 *
	 * @param condition
	 * @param handler
	 * @return
	 */
	public abstract HttpMappings add(Predicate<? super HttpChannel> condition,
			Function<? super HttpChannel, ? extends Publisher<Void>> handler);

	/**
	 */
	static final class HttpHandlerMapping
			implements Function<HttpChannel, Publisher<Void>>, Predicate<HttpChannel> {

		private final Predicate<? super HttpChannel>                condition;
		private final Function<? super HttpChannel, ? extends Publisher<Void>>   handler;
		private final Function<? super String, Map<String, Object>> resolver;

		HttpHandlerMapping(Predicate<? super HttpChannel> condition,
				Function<? super HttpChannel, ? extends Publisher<Void>> handler,
				Function<? super String, Map<String, Object>>         resolver) {
			this.condition = condition;
			this.handler = handler;
			this.resolver = resolver;
		}

		@Override
		public Publisher<Void> apply(HttpChannel channel) {
			return handler.apply(channel.paramsResolver(resolver));
		}

		Function<? super HttpChannel, ? extends Publisher<Void>> getHandler() {
			return handler;
		}

		@Override
		public boolean test(HttpChannel o) {
			return condition.test(o);
		}
	}

	final static class SimpleHttpMappings extends HttpMappings {

		private final CopyOnWriteArrayList<HttpHandlerMapping> handlers =
				new CopyOnWriteArrayList<>();

		@Override
		public HttpMappings add(Predicate<? super HttpChannel> condition,
				Function<? super HttpChannel, ? extends Publisher<Void>> handler) {

			handlers.add(new HttpHandlerMapping(condition, handler, null));
			return this;
		}

		@Override
		public Iterable<? extends Function<? super HttpChannel, ? extends Publisher<Void>>> apply(final HttpChannel channel) {

			return (Iterable<Function<? super HttpChannel, ? extends Publisher<Void>>>) () -> {
				final Iterator<HttpHandlerMapping> iterator = handlers.iterator();
				return new Iterator<Function<? super HttpChannel, ? extends Publisher<Void>>>() {

					HttpHandlerMapping cached;

					@Override
					public boolean hasNext() {
						HttpHandlerMapping cursor;
						while (iterator.hasNext()) {
							cursor = iterator.next();
							if (cursor.test(channel)) {
								cached = cursor;
								return true;
							}
						}
						return false;
					}

					@Override
					public Function<? super HttpChannel, ? extends Publisher<Void>> next() {
						HttpHandlerMapping cursor = cached;
						if (cursor != null) {
							cached = null;
							return cursor;
						}
						hasNext();
						return cached;
					}
				};
			};
		}
	}

	/**
	 * A Predicate to match against ServerRequest
	 *
	 * @author Stephane Maldini
	 */
	public static class HttpPredicate implements Predicate<HttpChannel> {

		final protected HttpVersion protocol;
		final protected HttpMethod  method;
		final protected String      uri;

		@SuppressWarnings("unused")
		public HttpPredicate(String uri) {
			this(uri, null, null);
		}

		public HttpPredicate(String uri, HttpVersion protocol, HttpMethod method) {
			this.protocol = protocol;
			this.uri = uri;
			this.method = method;
		}

		@Override
		public final boolean test(HttpChannel key) {
			return (protocol == null || protocol.equals(key.version()))
					&& (method == null || method.equals(key.method()))
					&& (uri == null || uri.equals(key.uri()));
		}
	}

	final static class HttpPrefixPredicate implements Predicate<HttpChannel> {

		final protected HttpMethod method;
		final protected String     prefix;

		public HttpPrefixPredicate(String prefix, HttpMethod method) {
			this.prefix = prefix;
			this.method = method;
		}

		@Override
		public boolean test(HttpChannel key) {
			return (method == null || method.equals(key.method())) && key.uri()
			                                                             .startsWith(prefix);
		}
	}
	private static final boolean FORCE_SIMPLE_MAPPINGS =
			Boolean.parseBoolean(System.getProperty("reactor.net.forceSimpleMappings", "false"));
}
