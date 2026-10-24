/*
 * Copyright (c) 2022-2026 VMware, Inc. or its affiliates, All Rights Reserved.
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
package reactor.netty;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.netty.channel.ChannelHandler;
import org.junit.jupiter.api.Test;
import org.reflections.Reflections;
import org.reflections.util.ConfigurationBuilder;

import java.io.InputStream;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.reflections.scanners.Scanners.SubTypes;

class NativeConfigTest {

	@Test
	void testChannelHandler() throws Exception {
		Set<Config> classes = findAllClassesUsingReflection("reactor.netty.http", ChannelHandler.class);

		try (InputStream is = getClass()
				.getResourceAsStream("/META-INF/native-image/io.projectreactor.netty/reactor-netty-http/reflect-config.json")) {

			ObjectMapper mapper = new ObjectMapper();
			Set<Config> classesFromFile = new HashSet<>(Arrays.asList(mapper.readValue(is, Config[].class)));
			classes.add(new Config(new Condition("reactor.netty.http.internal.Http3"),
					"io.netty.handler.codec.quic.Quic", false,
					Collections.singletonList(new MethodConfig("isAvailable", Collections.emptyList()))));

			assertThat(classesFromFile).isEqualTo(classes);
		}
	}

	static Set<Config> findAllClassesUsingReflection(String packageName, Class<?> subtype) {
		Reflections reflections = new Reflections(
				new ConfigurationBuilder()
						.forPackage(packageName)
						.filterInputsBy(s -> !s.contains("Test")));
		return reflections.get(SubTypes.of(subtype).asClass())
				.stream()
				.filter(aClass -> aClass.getName().startsWith(packageName))
				.map(aClass -> new Config(aClass.getName()))
				.collect(Collectors.toSet());
	}

	public static final class Condition {
		private String typeReachable;

		private Condition() {
		}

		private Condition(String typeReachable) {
			this.typeReachable = typeReachable;
		}

		public String getTypeReachable() {
			return typeReachable;
		}

		public void setTypeReachable(String typeReachable) {
			this.typeReachable = typeReachable;
		}

		@Override
		public boolean equals(Object o) {
			if (this == o) {
				return true;
			}
			if (!(o instanceof Condition)) {
				return false;
			}
			Condition condition = (Condition) o;
			return Objects.equals(typeReachable, condition.typeReachable);
		}

		@Override
		public int hashCode() {
			return Objects.hash(typeReachable);
		}

		@Override
		public String toString() {
			return "Condition {" +
					"typeReachable='" + typeReachable + '\'' +
					'}';
		}
	}

	public static final class Config {
		private Condition condition;
		private String name;
		private boolean queryAllPublicMethods;
		private List<MethodConfig> methods;

		private Config() {
		}

		private Config(String name) {
			this(new Condition(name), name, true);
		}

		private Config(Condition condition, String name, boolean queryAllPublicMethods) {
			this(condition, name, queryAllPublicMethods, null);
		}

		private Config(Condition condition, String name, boolean queryAllPublicMethods, List<MethodConfig> methods) {
			this.condition = condition;
			this.name = name;
			this.queryAllPublicMethods = queryAllPublicMethods;
			this.methods = methods;
		}

		public Condition getCondition() {
			return condition;
		}

		public void setCondition(Condition condition) {
			this.condition = condition;
		}

		public String getName() {
			return name;
		}

		public void setName(String name) {
			this.name = name;
		}

		public boolean isQueryAllPublicMethods() {
			return queryAllPublicMethods;
		}

		public void setQueryAllPublicMethods(boolean queryAllPublicMethods) {
			this.queryAllPublicMethods = queryAllPublicMethods;
		}

		public List<MethodConfig> getMethods() {
			return methods;
		}

		public void setMethods(List<MethodConfig> methods) {
			this.methods = methods;
		}

		@Override
		public boolean equals(Object o) {
			if (this == o) {
				return true;
			}
			if (!(o instanceof Config)) {
				return false;
			}
			Config config = (Config) o;
			return Objects.equals(condition, config.condition) &&
					Objects.equals(name, config.name) &&
					queryAllPublicMethods == config.queryAllPublicMethods &&
					Objects.equals(methods, config.methods);
		}

		@Override
		public int hashCode() {
			return Objects.hash(condition, name, queryAllPublicMethods, methods);
		}

		@Override
		public String toString() {
			return "Config {" +
					"condition=" + condition +
					", name='" + name + '\'' +
					", queryAllPublicMethods=" + queryAllPublicMethods +
					", methods=" + methods +
					'}';
		}
	}

	public static final class MethodConfig {
		private String name;
		private List<String> parameterTypes;

		private MethodConfig() {
		}

		private MethodConfig(String name, List<String> parameterTypes) {
			this.name = name;
			this.parameterTypes = parameterTypes;
		}

		public String getName() {
			return name;
		}

		public void setName(String name) {
			this.name = name;
		}

		public List<String> getParameterTypes() {
			return parameterTypes;
		}

		public void setParameterTypes(List<String> parameterTypes) {
			this.parameterTypes = parameterTypes;
		}

		@Override
		public boolean equals(Object o) {
			if (this == o) {
				return true;
			}
			if (!(o instanceof MethodConfig)) {
				return false;
			}
			MethodConfig that = (MethodConfig) o;
			return Objects.equals(name, that.name) &&
					Objects.equals(parameterTypes, that.parameterTypes);
		}

		@Override
		public int hashCode() {
			return Objects.hash(name, parameterTypes);
		}

		@Override
		public String toString() {
			return "MethodConfig {" +
					"name='" + name + '\'' +
					", parameterTypes=" + parameterTypes +
					'}';
		}
	}
}
