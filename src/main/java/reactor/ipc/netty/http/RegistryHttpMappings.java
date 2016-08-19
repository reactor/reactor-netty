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

import java.util.Map;
import java.util.function.Function;
import java.util.function.Predicate;

import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpVersion;
import org.reactivestreams.Publisher;
import reactor.bus.registry.Registries;
import reactor.bus.registry.Registry;
import reactor.bus.selector.Selector;
import reactor.bus.selector.Selectors;
import reactor.bus.selector.UriPathSelector;

/**
 * @author Stephane Maldini
 */
final class RegistryHttpMappings extends HttpMappings {

	static {
		try{
			RegistryHttpMappings.class.getClassLoader().loadClass("reactor.bus" +
					".registry.Registry");
		}
		catch(ClassNotFoundException cfe){
			throw new IllegalStateException("io.projectreactor.addons:reactor-bus dependency is missing from the classpath.");
		}
	}

	private final Registry<HttpChannel, HttpHandlerMapping> routedWriters =
			Registries.create();

	@Override
	public Iterable<? extends Function<? super HttpChannel, ? extends Publisher<Void>>> apply(
			HttpChannel channel) {
		return routedWriters.selectValues(channel);
	}

	@Override
	@SuppressWarnings("unchecked")
	public HttpMappings add(Predicate<? super HttpChannel> condition,
			Function<? super HttpChannel, ? extends Publisher<Void>> handler) {

		Selector<HttpChannel> selector;

		if(Selector.class.isAssignableFrom(condition.getClass())) {
			selector = (Selector<HttpChannel>) condition;
		}
		else{
			selector = Selectors.predicate(condition);
		}

		routedWriters.register(selector, new HttpHandlerMapping(condition, handler, selector.getHeaderResolver()));

		return this;
	}

	public final static class HttpSelector
			extends HttpPredicate
			implements Selector<HttpChannel> {

		final UriPathSelector uriPathSelector;

		public HttpSelector(String uri, HttpVersion protocol, HttpMethod method) {
			super(null, protocol, method);
			this.uriPathSelector = uri != null && !uri.isEmpty() ? new UriPathSelector(uri) : null;
		}


		@Override
		public Object getObject() {
			return uriPathSelector != null ? uriPathSelector.getObject() : null;
		}

		@Override
		@SuppressWarnings("unchecked")
		public Function<Object, Map<String, Object>> getHeaderResolver() {
			return uriPathSelector != null ?
					uriPathSelector.getHeaderResolver() :
					null;
		}

		@Override
		public boolean matches(HttpChannel key) {
			return test(key)
					&& (uriPathSelector == null || uriPathSelector.matches(key.uri()));
		}
	}
}
