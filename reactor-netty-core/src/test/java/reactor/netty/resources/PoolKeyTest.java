/*
 * Copyright (c) 2023 VMware, Inc. or its affiliates, All Rights Reserved.
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
import reactor.netty.resources.PooledConnectionProvider.PoolKey;
import reactor.netty.transport.AddressUtils;

import java.util.LinkedHashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class PoolKeyTest {

	@Test
	void test() {
		Set<PoolKey> set = new LinkedHashSet<>();
		PoolKey key1 = new PoolKey(AddressUtils.createUnresolved("wikipedia.org", 80), 0);
		assertThat(set.add(key1)).isTrue();
		PoolKey key2 = new PoolKey(AddressUtils.createUnresolved("en.wikipedia.org", 80), 0);
		assertThat(set.add(key2)).isTrue();
		PoolKey key3 = new PoolKey(AddressUtils.createUnresolved("wikipedia.ORG", 80), 0);
		assertThat(set.add(key3)).isFalse();
		PoolKey key4 = new PoolKey(AddressUtils.createUnresolved("en.wikipedia.ORG", 80), 0);
		assertThat(set.add(key4)).isFalse();
		PoolKey key5 = new PoolKey(AddressUtils.createResolved("wikipedia.org", 80), 0);
		assertThat(set.add(key5)).isTrue();
		PoolKey key6 = new PoolKey(AddressUtils.createResolved("en.wikipedia.org", 80), 0);
		assertThat(set.add(key6)).isTrue();
		PoolKey key7 = new PoolKey(AddressUtils.createResolved("wikipedia.ORG", 80), 0);
		assertThat(set.add(key7)).isFalse();
		PoolKey key8 = new PoolKey(AddressUtils.createResolved("en.wikipedia.ORG", 80), 0);
		assertThat(set.add(key8)).isFalse();
		PoolKey key9 = new PoolKey(AddressUtils.createUnresolved("127.0.0.1", 80), 0);
		assertThat(set.add(key9)).isTrue();
		PoolKey key10 = new PoolKey(AddressUtils.createUnresolved("[::1]", 80), 0);
		assertThat(set.add(key10)).isTrue();
		PoolKey key11 = new PoolKey(AddressUtils.createUnresolved("127.0.0.1", 80), 0);
		assertThat(set.add(key11)).isFalse();
		PoolKey key12 = new PoolKey(AddressUtils.createUnresolved("[::1]", 80), 0);
		assertThat(set.add(key12)).isFalse();
		PoolKey key13 = new PoolKey(AddressUtils.createUnresolved("127.0.0.1", 443), 0);
		assertThat(set.add(key13)).isTrue();
		PoolKey key14 = new PoolKey(AddressUtils.createUnresolved("[::1]", 443), 0);
		assertThat(set.add(key14)).isTrue();
		assertThat(set.size()).isEqualTo(8);
	}

}
