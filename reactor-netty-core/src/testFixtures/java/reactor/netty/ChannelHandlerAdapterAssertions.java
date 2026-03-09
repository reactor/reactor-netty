/*
 * Copyright (c) 2026 VMware, Inc. or its affiliates, All Rights Reserved.
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

import io.netty.channel.ChannelHandlerAdapter;
import org.reflections.Reflections;
import org.reflections.util.ConfigurationBuilder;

import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.reflections.scanners.Scanners.SubTypes;

/**
 * Verifies that all {@link ChannelHandlerAdapter} subclasses in the given package
 * explicitly override {@code isSharable()} so that the default reflection-based
 * annotation check in {@link ChannelHandlerAdapter#isSharable()} is never used.
 */
public final class ChannelHandlerAdapterAssertions {

	public static void assertIsSharableOverridden(String packageName) {
		Reflections reflections = new Reflections(
				new ConfigurationBuilder()
						.forPackage(packageName)
						.filterInputsBy(s -> !s.contains("Test")));

		Set<Class<?>> classes =
				reflections.get(SubTypes.of(ChannelHandlerAdapter.class).asClass())
						.stream()
						.filter(aClass -> aClass.getName().startsWith(packageName))
						.collect(Collectors.toSet());

		Set<Class<?>> failures = classes.stream()
				.filter(clazz -> !declaresIsSharable(clazz))
				.collect(Collectors.toSet());

		assertThat(failures)
				.as("ChannelHandlerAdapter subclasses that do not override isSharable()")
				.isEmpty();
	}

	@SuppressWarnings("ReturnValueIgnored")
	static boolean declaresIsSharable(Class<?> clazz) {
		Class<?> current = clazz;
		try {
			while (current != null && current != ChannelHandlerAdapter.class && current != Object.class) {
				try {
					// Suppressing "ReturnValueIgnored" is deliberate
					current.getDeclaredMethod("isSharable");
					return true;
				}
				catch (NoSuchMethodException e) {
					current = current.getSuperclass();
				}
			}
		}
		catch (NoClassDefFoundError e) {
			// Class hierarchy references a type not on this module's classpath (e.g. QuicStreamChannel).
			// Skip — the owning module's test will cover it.
			return true;
		}
		return false;
	}

	private ChannelHandlerAdapterAssertions() {
	}
}
