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
package reactor.netty;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.BindException;
import java.net.InetSocketAddress;

import static org.assertj.core.api.Assertions.assertThat;

class ChannelBindExceptionTest {

	@Test
	void testFailBindExceptionCause() {

		UnsupportedOperationException cause = new UnsupportedOperationException();
		ChannelBindException ex = ChannelBindException.fail(new InetSocketAddress("test", 4956), cause);
		assertThat(ex.getCause()).isEqualTo(cause);
		assertThat(ex.localHost()).isEqualTo("test");
		assertThat(ex.localPort()).isEqualTo(4956);

		ex = ChannelBindException.fail(new InetSocketAddress("test", 4956), new BindException("Address already in use"));
		assertThat(ex.getCause()).isEqualTo(null);
		assertThat(ex.localHost()).isEqualTo("test");
		assertThat(ex.localPort()).isEqualTo(4956);

		// Not possible to mock io.netty.channel.unix.Errors.NativeIoException or create a new instance because of Jni errors
		// java.lang.UnsatisfiedLinkError: 'int io.netty.channel.unix.ErrorsStaticallyReferencedJniMethods.errnoENOENT()'
		ex = ChannelBindException.fail(new InetSocketAddress("test", 4956), new IOException("bind(..) failed: Address already in use"));
		assertThat(ex.getCause()).isEqualTo(null);
		assertThat(ex.localHost()).isEqualTo("test");
		assertThat(ex.localPort()).isEqualTo(4956);

		// Issue-1668
		ex = ChannelBindException.fail(new InetSocketAddress("test", 4956), new IOException("bind(..) failed: Die Adresse wird bereits verwendet"));
		assertThat(ex.getCause()).isEqualTo(null);
		assertThat(ex.localHost()).isEqualTo("test");
		assertThat(ex.localPort()).isEqualTo(4956);


	}


}
