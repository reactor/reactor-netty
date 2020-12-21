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
package reactor.netty.udp;

import org.junit.jupiter.api.Test;
import reactor.netty.resources.LoopResources;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * @author Violeta Georgieva
 */
class UdpResourcesTest {

	@Test
	void testIssue1227() {
		UdpResources.get();

		UdpResources old = UdpResources.udpResources.get();

		LoopResources loops = LoopResources.create("testIssue1227");
		UdpResources.set(loops);
		assertThat(old.isDisposed()).isTrue();

		UdpResources current = UdpResources.udpResources.get();
		UdpResources.shutdownLater()
		            .block();
		assertThat(current.isDisposed()).isTrue();
	}
}
