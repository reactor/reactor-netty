/*
 * Copyright (c) 2011-2019 Pivotal Software Inc, All Rights Reserved.
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

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.annotation.Nullable;

import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpVersion;

/**
 * A Predicate to match against ServerRequest
 *
 * @author Stephane Maldini
 */
final class HttpPredicate
		implements Predicate<HttpServerRequest>, Function<Object, Map<String, String>> {

	/**
	 * An alias for {@link HttpPredicate#http}.
	 * <p>
	 * Creates a {@link Predicate} based on a URI template filtering .
	 * <p>
	 * This will listen for DELETE Method.
	 *
	 * @param uri The string to compile into a URI template and use for matching
	 *
	 * @return The new {@link Predicate}.
	 *
	 * @see Predicate
	 */
	public static Predicate<HttpServerRequest> delete(String uri) {
		return http(uri, null, HttpMethod.DELETE);
	}

	/**
	 * An alias for {@link HttpPredicate#http}.
	 * <p>
	 * Creates a {@link Predicate} based on a URI template filtering .
	 * <p>
	 * This will listen for GET Method.
	 *
	 * @param uri The string to compile into a URI template and use for matching
	 *
	 * @return The new {@link Predicate}.
	 *
	 * @see Predicate
	 */
	public static Predicate<HttpServerRequest> get(String uri) {
		return http(uri, null, HttpMethod.GET);
	}

	/**
	 * An alias for {@link HttpPredicate#http}.
	 * <p>
	 * Creates a {@link Predicate} based on a URI template filtering .
	 * <p>
	 * This will listen for HEAD Method.
	 *
	 * @param uri The string to compile into a URI template and use for matching
	 *
	 * @return The new {@link Predicate}.
	 *
	 * @see Predicate
	 */
	public static Predicate<HttpServerRequest> head(String uri) {
		return http(uri, null, HttpMethod.HEAD);
	}

	/**
	 * Creates a {@link Predicate} based on a URI template.
	 * This will listen for all Methods.
	 *
	 * @param uri The string to compile into a URI template and use for matching
	 *
	 * @return The new {@link HttpPredicate}.
	 *
	 * @see Predicate
	 */
	public static Predicate<HttpServerRequest> http(String uri,
			@Nullable HttpVersion protocol,
			HttpMethod method) {
		if (null == uri) {
			return null;
		}

		return new HttpPredicate(uri, protocol, method);
	}

	/**
	 * An alias for {@link HttpPredicate#http}.
	 * <p>
	 * Creates a {@link Predicate} based on a URI template filtering .
	 * <p>
	 * This will listen for OPTIONS Method.
	 *
	 * @param uri The string to compile into a URI template and use for matching
	 *
	 * @return The new {@link Predicate}.
	 *
	 * @see Predicate
	 */
	public static Predicate<HttpServerRequest> options(String uri) {
		return http(uri, null, HttpMethod.OPTIONS);
	}

	/**
	 * An alias for {@link HttpPredicate#http}.
	 * <p>
	 * Creates a {@link Predicate} based on a URI template filtering .
	 * <p>
	 * This will listen for POST Method.
	 *
	 * @param uri The string to compile into a URI template and use for matching
	 *
	 * @return The new {@link Predicate}.
	 *
	 * @see Predicate
	 */
	public static Predicate<HttpServerRequest> post(String uri) {
		return http(uri, null, HttpMethod.POST);
	}

	/**
	 * An alias for {@link HttpPredicate#get} prefix ([prefix]/**), useful for file system
	 * mapping.
	 * <p>
	 * Creates a {@link Predicate} based on a URI template filtering .
	 * <p>
	 * This will listen for WebSocket Method.
	 *
	 * @return The new {@link Predicate}.
	 *
	 * @see Predicate
	 */
	public static Predicate<HttpServerRequest> prefix(String prefix) {
		return prefix(prefix, HttpMethod.GET);
	}

	/**
	 * An alias for {@link HttpPredicate#get} prefix (/**), useful for file system mapping.
	 * <p>
	 * Creates a {@link Predicate} based on a URI template filtering .
	 * <p>
	 * This will listen for WebSocket Method.
	 *
	 * @return The new {@link Predicate}.
	 *
	 *
	 * @see Predicate
	 */
	public static Predicate<HttpServerRequest> prefix(String prefix, HttpMethod method) {
		Objects.requireNonNull(prefix, "Prefix must be provided");

		String target = prefix.startsWith("/") ? prefix : "/".concat(prefix);
		//target = target.endsWith("/") ? target :  prefix.concat("/");
		return new HttpPrefixPredicate(target, method);
	}

	/**
	 * An alias for {@link HttpPredicate#http}.
	 * <p>
	 * Creates a {@link Predicate} based on a URI template filtering .
	 * <p>
	 * This will listen for PUT Method.
	 *
	 * @param uri The string to compile into a URI template and use for matching
	 *
	 * @return The new {@link Predicate}.
	 *
	 * @see Predicate
	 */
	public static Predicate<HttpServerRequest> put(String uri) {
		return http(uri, null, HttpMethod.PUT);
	}

	final HttpVersion     protocol;
	final HttpMethod      method;
	final String          uri;
	final UriPathTemplate template;

	@SuppressWarnings("unused")
	public HttpPredicate(String uri) {
		this(uri, null, null);
	}

	public HttpPredicate(String uri,
			@Nullable HttpVersion protocol,
			@Nullable HttpMethod method) {
		this.protocol = protocol;
		this.uri = uri;
		this.method = method;
		this.template = uri != null ? new UriPathTemplate(uri) : null;
	}

	@Override
	public Map<String, String> apply(Object key) {
		if (template == null) {
			return null;
		}
		Map<String, String> headers = template.match(key.toString());
		if (null != headers && !headers.isEmpty()) {
			return headers;
		}
		return null;
	}

	@Override
	public final boolean test(HttpServerRequest key) {
		return (protocol == null || protocol.equals(key.version())) && (method == null || method.equals(
				key.method())) && (template == null || template.matches(key.uri()));
	}

	/**
	 * Represents a URI template. A URI template is a URI-like String that contains
	 * variables enclosed by braces (<code>{</code>, <code>}</code>), which can be
	 * expanded to produce an actual URI.
	 *
	 * @author Arjen Poutsma
	 * @author Juergen Hoeller
	 * @author Jon Brisbin
	 * @see <a href="https://tools.ietf.org/html/rfc6570">RFC 6570: URI Templates</a>
	 */
	static final class UriPathTemplate {

		private static final Pattern FULL_SPLAT_PATTERN     =
				Pattern.compile("[\\*][\\*]");
		private static final String  FULL_SPLAT_REPLACEMENT = ".*";

		private static final Pattern NAME_SPLAT_PATTERN     =
				Pattern.compile("\\{([^/]+?)\\}[\\*][\\*]");
		// JDK 6 doesn't support named capture groups
		private static final String  NAME_SPLAT_REPLACEMENT = "(?<%NAME%>.*)";
		//private static final String  NAME_SPLAT_REPLACEMENT = "(.*)";

		private static final Pattern NAME_PATTERN     = Pattern.compile("\\{([^/]+?)\\}");
		// JDK 6 doesn't support named capture groups
		private static final String  NAME_REPLACEMENT = "(?<%NAME%>[^\\/]*)";
		//private static final String  NAME_REPLACEMENT = "([^\\/]*)";

		private final List<String>                         pathVariables =
				new ArrayList<>();
		private final HashMap<String, Matcher>             matchers      =
				new HashMap<>();
		private final HashMap<String, Map<String, String>> vars          =
				new HashMap<>();

		private final Pattern uriPattern;

		static String filterQueryParams(String uri) {
			int hasQuery = uri.lastIndexOf("?");
			if (hasQuery != -1) {
				return uri.substring(0, hasQuery);
			}
			else {
				return uri;
			}
		}

		/**
		 * Creates a new {@code UriPathTemplate} from the given {@code uriPattern}.
		 *
		 * @param uriPattern The pattern to be used by the template
		 */
		UriPathTemplate(String uriPattern) {
			String s = "^" + filterQueryParams(uriPattern);

			Matcher m = NAME_SPLAT_PATTERN.matcher(s);
			while (m.find()) {
				for (int i = 1; i <= m.groupCount(); i++) {
					String name = m.group(i);
					pathVariables.add(name);
					s = m.replaceFirst(NAME_SPLAT_REPLACEMENT.replaceAll("%NAME%", name));
					m.reset(s);
				}
			}

			m = NAME_PATTERN.matcher(s);
			while (m.find()) {
				for (int i = 1; i <= m.groupCount(); i++) {
					String name = m.group(i);
					pathVariables.add(name);
					s = m.replaceFirst(NAME_REPLACEMENT.replaceAll("%NAME%", name));
					m.reset(s);
				}
			}

			m = FULL_SPLAT_PATTERN.matcher(s);
			while (m.find()) {
				s = m.replaceAll(FULL_SPLAT_REPLACEMENT);
				m.reset(s);
			}

			this.uriPattern = Pattern.compile(s + "$");
		}

		/**
		 * Tests the given {@code uri} against this template, returning {@code true} if
		 * the uri matches the template, {@code false} otherwise.
		 *
		 * @param uri The uri to match
		 *
		 * @return {@code true} if there's a match, {@code false} otherwise
		 */
		public boolean matches(String uri) {
			return matcher(uri).matches();
		}

		/**
		 * Matches the template against the given {@code uri} returning a map of path
		 * parameters extracted from the uri, keyed by the names in the template. If the
		 * uri does not match, or there are no path parameters, an empty map is returned.
		 *
		 * @param uri The uri to match
		 *
		 * @return the path parameters from the uri. Never {@code null}.
		 */
		final Map<String, String> match(String uri) {
			Map<String, String> pathParameters = vars.get(uri);
			if (null != pathParameters) {
				return pathParameters;
			}

			pathParameters = new HashMap<>();
			Matcher m = matcher(uri);
			if (m.matches()) {
				int i = 1;
				for (String name : pathVariables) {
					String val = m.group(i++);
					pathParameters.put(name, val);
				}
			}
			synchronized (vars) {
				vars.put(uri, pathParameters);
			}

			return pathParameters;
		}

		private Matcher matcher(String uri) {
			uri = filterQueryParams(uri);
			Matcher m = matchers.get(uri);
			if (null == m) {
				m = uriPattern.matcher(uri);
				synchronized (matchers) {
					matchers.put(uri, m);
				}
			}
			return m;
		}

	}

	static final class HttpPrefixPredicate implements Predicate<HttpServerRequest> {

		final HttpMethod method;
		final String     prefix;

		public HttpPrefixPredicate(String prefix, HttpMethod method) {
			this.prefix = prefix;
			this.method = method;
		}

		@Override
		public boolean test(HttpServerRequest key) {
			return (method == null || method.equals(key.method())) && key.uri()
			                                                             .startsWith(
					                                                             prefix);
		}
	}
}
