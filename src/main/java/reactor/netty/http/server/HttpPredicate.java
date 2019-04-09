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

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
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
	 * This is adapted from
	 * https://github.com/spring-projects/spring-framework/blob/v5.1.6.RELEASE/spring-web/src/main/java/org/springframework/web/util/UriTemplate.java
	 *
	 * Represents a URI template. A URI template is a URI-like String that contains variables
	 * enclosed by braces ({@code {}}) which can be expanded to produce an actual URI.
	 *
	 * <p>See {@link #match(String)} for example usages.
	 *
	 * <p>This class is designed to be thread-safe and reusable, allowing for any number
	 * of expand or match calls.
	 *
	 * @author Arjen Poutsma
	 * @author Juergen Hoeller
	 * @author Rossen Stoyanchev
	 * @since 3.0
	 */
	@SuppressWarnings("serial")
	static final class UriPathTemplate implements Serializable {

		private final String uriTemplate;

		private final List<String> variableNames;

		private final Pattern matchPattern;


		/**
		 * Construct a new {@code UriPathTemplate} with the given URI String.
		 * @param uriTemplate the URI template string
		 */
		UriPathTemplate(String uriTemplate) {
			this.uriTemplate = Objects.requireNonNull(uriTemplate, "'uriTemplate' must not be null");

			TemplateInfo info = TemplateInfo.parse(filterQueryParams(uriTemplate));
			this.variableNames = Collections.unmodifiableList(info.variableNames);
			this.matchPattern = info.pattern;
		}


		/**
		 * Indicate whether the given URI matches this template.
		 * @param uri the URI to match to
		 * @return {@code true} if it matches; {@code false} otherwise
		 */
		boolean matches(@Nullable String uri) {
			if (uri == null) {
				return false;
			}
			uri = filterQueryParams(uri);
			Matcher matcher = this.matchPattern.matcher(uri);
			return matcher.matches();
		}

		/**
		 * Match the given URI to a map of variable values. Keys in the returned map are variable names,
		 * values are variable values, as occurred in the given URI.
		 * <p>Example:
		 * <pre class="code">
		 * UriPathTemplate template = new UriPathTemplate("https://example.com/hotels/{hotel}/bookings/{booking}");
		 * System.out.println(template.match("https://example.com/hotels/1/bookings/42"));
		 * </pre>
		 * will print: <blockquote>{@code {hotel=1, booking=42}}</blockquote>
		 * @param uri the URI to match to
		 * @return a map of variable values
		 */
		Map<String, String> match(String uri) {
			Objects.requireNonNull(uri, "'uri' must not be null");
			uri = filterQueryParams(uri);
			Map<String, String> result = new LinkedHashMap<>(this.variableNames.size());
			Matcher matcher = this.matchPattern.matcher(uri);
			if (matcher.find()) {
				for (int i = 1; i <= matcher.groupCount(); i++) {
					String name = this.variableNames.get(i - 1);
					String value = matcher.group(i);
					result.put(name, value);
				}
			}
			return result;
		}

		@Override
		public String toString() {
			return this.uriTemplate;
		}


		static String filterQueryParams(String uri) {
			int hasQuery = uri.lastIndexOf("?");
			if (hasQuery != -1) {
				return uri.substring(0, hasQuery);
			}
			else {
				return uri;
			}
		}

	}

	/**
	 * Helper to extract variable names and regex for matching to actual URLs.
	 */
	static final class TemplateInfo {

		private final List<String> variableNames;

		private final Pattern pattern;

		private TemplateInfo(List<String> vars, Pattern pattern) {
			this.variableNames = vars;
			this.pattern = pattern;
		}

		static TemplateInfo parse(String uriTemplate) {
			int level = 0;
			List<String> variableNames = new ArrayList<>();
			StringBuilder pattern = new StringBuilder();
			StringBuilder builder = new StringBuilder();
			for (int i = 0 ; i < uriTemplate.length(); i++) {
				char c = uriTemplate.charAt(i);
				if (c == '{') {
					level++;
					if (level == 1) {
						// start of URI variable
						pattern.append((builder.length() > 0 ? Pattern.quote(builder.toString()) : ""));
						builder = new StringBuilder();
						continue;
					}
				}
				else if (c == '}') {
					level--;
					if (level == 0) {
						// end of URI variable
						String variable = builder.toString();
						int idx = variable.indexOf(':');
						if (idx == -1) {
							pattern.append("([^/]*)");
							variableNames.add(variable);
						}
						else {
							if (idx + 1 == variable.length()) {
								throw new IllegalArgumentException(
										"No custom regular expression specified after ':' in \"" + variable + "\"");
							}
							String regex = variable.substring(idx + 1);
							pattern.append('(');
							pattern.append(regex);
							pattern.append(')');
							variableNames.add(variable.substring(0, idx));
						}
						builder = new StringBuilder();
						continue;
					}
				}
				builder.append(c);
			}
			if (builder.length() > 0) {
				pattern.append(Pattern.quote(builder.toString()));
			}
			return new TemplateInfo(variableNames, Pattern.compile(pattern.toString()));
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
