/*
 * Copyright (c) 2021 VMware, Inc. or its affiliates, All Rights Reserved.
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
package reactor.netty.resources;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.time.Duration;
import java.util.Collections;
import java.util.Map;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;

class ConnectionProviderTest {

	static final String TEST_STRING = "";
	static final Supplier<ConnectionProvider.MeterRegistrar> TEST_SUPPLIER = () -> (a, b, c, d) -> {};

	@Test
	void testBuilderCopyConstructor() throws IllegalAccessException {
		ConnectionProvider.Builder original = ConnectionProvider.builder("testBuilderCopyConstructor");
		init(original, original.getClass().getDeclaredFields());
		init(original, original.getClass().getSuperclass().getDeclaredFields());
		ConnectionProvider.Builder copy = new ConnectionProvider.Builder(original);
		assertThat(copy).usingRecursiveComparison().isEqualTo(original);
	}

	static void init(ConnectionProvider.Builder builder, Field[] fields) throws IllegalAccessException {
		for (Field field : fields) {
			int modifier = field.getModifiers();
			if (!(Modifier.isStatic(modifier) || Modifier.isVolatile(modifier))) {
				modifyField(builder, field);
			}
		}
	}

	static void modifyField(ConnectionProvider.Builder builder, Field field) throws IllegalAccessException {
		field.setAccessible(true);
		Class<?> clazz = field.getType();
		if (String.class == clazz) {
			field.set(builder, TEST_STRING);
		}
		else if (Duration.class == clazz) {
			field.set(builder, Duration.ZERO);
		}
		else if (Map.class == clazz) {
			field.set(builder, Collections.EMPTY_MAP);
		}
		else if (Supplier.class == clazz) {
			field.set(builder, TEST_SUPPLIER);
		}
		else if (boolean.class == clazz) {
			field.setBoolean(builder, true);
		}
		else if (int.class == clazz) {
			field.setInt(builder, 1);
		}
		else {
			throw new IllegalArgumentException("Unknown field type " + clazz);
		}
	}
}
