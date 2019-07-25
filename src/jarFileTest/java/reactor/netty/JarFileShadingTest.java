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
package reactor.netty;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

import org.assertj.core.api.ListAssert;
import org.junit.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * This test must be executed with Gradle because it requires a shadow JAR
 */
public class JarFileShadingTest extends AbstractJarFileTest {

	@Test
	public void testPackages() throws Exception {
		try (Stream<Path> stream = Files.list(root)) {
			assertThatFileList(stream).containsOnly("reactor", "META-INF");
		}
		try(Stream<Path> stream = Files.list(root.resolve("reactor"))) {
			assertThatFileList(stream).containsOnly("netty");
		}
	}

	@Test
	public void testPackagesReactorPool() throws Exception {
		try (Stream<Path> stream = Files.list(root.resolve("reactor/netty/internal/shaded"))) {
			assertThatFileList(stream).containsOnly("reactor");
		}
		try (Stream<Path> stream = Files.list(root.resolve("reactor/netty/internal/shaded/reactor"))) {
			assertThatFileList(stream).containsOnly("pool");
		}
	}

	@Test
	public void testMetaInf() throws Exception {
		try (Stream<Path> stream = Files.list(root.resolve("META-INF"))) {
			assertThatFileList(stream).containsOnly("MANIFEST.MF");
		}
	}

	@SuppressWarnings("unchecked")
	private ListAssert<String> assertThatFileList(Stream<Path> path) {
		return (ListAssert) assertThat(path)
				.extracting(Path::getFileName)
				.extracting(Path::toString)
				.extracting(it -> it.endsWith("/") ? it.substring(0, it.length() - 1) : it);
	}

}