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
package reactor.netty;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import org.assertj.core.api.ListAssert;
import org.junit.Test;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.*;

/**
 * This test must be executed with Gradle because it requires a shadow JAR.
 * For example it can be run with {@code ./gradlew jarFileTest --tests *JarFileShadingTest}
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
	public void testManifestContent() throws IOException {
		ZipFile jar = new ZipFile(jarFilePath.toString());
		ZipEntry manifest = jar
				.stream()
				.filter(ze -> ze.getName().equals("META-INF/MANIFEST.MF"))
				.findFirst()
				.get();

		String version = jarFilePath.getFileName().toString()
				.replace("reactor-netty-", "")
				.replace("-original.jar", "")
				.replace(".jar", "");
		//for the case where there is a customVersion. OSGI only want 4 components to the version,
		//so BND would simply remove the 4th dot between customVersion and RELEASE/BUILD-SNAPSHOT.
		String osgiVersion = Arrays.stream(version.split("\\.", 4))
		                           .map(comp -> comp.replace(".", ""))
		                           .collect(Collectors.joining("."));

		try (InputStream inputStream = jar.getInputStream(manifest);
		     BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream, UTF_8))) {
			String lines = reader.lines()
			                     //we eliminate the intermediate details of import-package & co
			                     //since we won't assert them -> so that assertion error is more readable
			                     .filter(s -> !s.startsWith(" "))
			                     .collect(Collectors.joining("\n"));
			assertThat(lines)
					.as("base content")
					.contains(
							"Implementation-Title: reactor-netty",
							"Implementation-Version: " + version,
							"Automatic-Module-Name: reactor.netty"
					);
			assertThat(lines)
					.as("OSGI content")
					.contains(
							"Bundle-Name: reactor-netty",
							"Bundle-SymbolicName: io.projectreactor.netty.reactor-netty",
							"Import-Package: ", //only assert the section is there
							"Require-Capability:",
							"Export-Package:", //only assert the section is there
							"Bundle-Version: " + osgiVersion
					);
		}
		catch (IOException ioe) {
			fail("Couldn't inspect the manifest", ioe);
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