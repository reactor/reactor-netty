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
package reactor.netty5;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.netty5.channel.ChannelHandler;
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
	void testChannelInboundHandler() throws Exception {
		Set<Pojo> classes = findAllClassesUsingReflectionsLibrary("reactor.netty5", ChannelHandler.class);

		try (InputStream is = getClass()
				.getResourceAsStream("/META-INF/native-image/io.projectreactor.netty/reactor-netty5-core/reflect-config.json")) {

			ObjectMapper mapper = new ObjectMapper();
			Set<Pojo> classesFromFile = new HashSet<>(Arrays.asList(mapper.readValue(is, Pojo[].class)));

			assertThat(classesFromFile).isEqualTo(classes);
		}
	}

	Set<Pojo> findAllClassesUsingReflectionsLibrary(String packageName, Class<?> subtype) {
		Reflections reflections = new Reflections(
				new ConfigurationBuilder()
						.forPackage(packageName)
						.filterInputsBy(s -> !s.contains("Test")));
		return reflections.get(SubTypes.of(subtype).asClass())
				.stream()
				.filter(aClass -> aClass.getName().startsWith(packageName))
				.map(aClass -> new Pojo(aClass.getName()))
				.collect(Collectors.toSet());
	}

	public static final class Pojo {
		private String name;
		private boolean queryAllPublicMethods;

		private Pojo() {
		}

		private Pojo(String name) {
			this(name, true);
		}

		private Pojo(String name, boolean queryAllPublicMethods) {
			this.name = name;
			this.queryAllPublicMethods = queryAllPublicMethods;
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
			if (!(o instanceof Pojo)) {
				return false;
			}
			Pojo myObject = (Pojo) o;
			return name.equals(myObject.name);
		}

		@Override
		public int hashCode() {
			return Objects.hash(name);
		}

		@Override
		public String toString() {
			return "Pojo{" +
					"name='" + name + '\'' +
					", queryAllPublicMethods='" + queryAllPublicMethods + '\'' +
					'}';
		}
	}
}
