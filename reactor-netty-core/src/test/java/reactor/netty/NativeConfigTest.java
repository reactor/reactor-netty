/*
 * Copyright (c) 2022 VMware, Inc. or its affiliates, All Rights Reserved.
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
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.reflections.scanners.Scanners.SubTypes;

class NativeConfigTest {

	@Test
	void testChannelHandler() throws Exception {
		Set<Config> classes = findAllClassesUsingReflection("reactor.netty", ChannelHandler.class);

		try (InputStream is = getClass()
				.getResourceAsStream("/META-INF/native-image/io.projectreactor.netty/reactor-netty-core/reflect-config.json")) {

			ObjectMapper mapper = new ObjectMapper();
			Set<Config> classesFromFile = new HashSet<>(Arrays.asList(mapper.readValue(is, Config[].class)));

			assertThat(classesFromFile).isEqualTo(classes);
		}
	}

	Set<Config> findAllClassesUsingReflection(String packageName, Class<?> subtype) {
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

		private Config() {
		}

		private Config(String name) {
			this(new Condition(name), name, true);
		}

		private Config(Condition condition, String name, boolean queryAllPublicMethods) {
			this.condition = condition;
			this.name = name;
			this.queryAllPublicMethods = queryAllPublicMethods;
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
					queryAllPublicMethods == config.queryAllPublicMethods;
		}

		@Override
		public int hashCode() {
			return Objects.hash(name);
		}

		@Override
		public String toString() {
			return "Config {" +
					"condition=" + condition +
					", name='" + name + '\'' +
					", queryAllPublicMethods=" + queryAllPublicMethods +
					'}';
		}
	}
}
