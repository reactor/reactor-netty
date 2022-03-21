/*
 * Copyright (c) 2021-2022 VMware, Inc. or its affiliates, All Rights Reserved.
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
import org.mockito.Mockito;
import reactor.core.publisher.MonoSink;
import reactor.netty.ChannelBindException;
import reactor.netty.Connection;
import reactor.util.context.Context;

import java.io.IOException;
import java.net.InetSocketAddress;

class NewConnectionProviderTest {


	@Test
	@SuppressWarnings("unchecked")
	void testDisposableConnectBindException() {
		MonoSink<Connection> sink = Mockito.mock(MonoSink.class);
		Mockito.when(sink.contextView()).thenReturn(Context.empty());

		NewConnectionProvider.DisposableConnect connect = new NewConnectionProvider.DisposableConnect(
				sink,
				() -> new InetSocketAddress("test1", 6956)
		);

		connect.onError(new UnsupportedOperationException());
		Mockito.verify(sink).error(Mockito.argThat(a -> a instanceof UnsupportedOperationException));

		connect = new NewConnectionProvider.DisposableConnect(sink, () -> new InetSocketAddress("test2", 4956));
		// Not possible to mock io.netty.channel.unix.Errors.NativeIoException or create a new instance because of Jni
		// error:
		// java.lang.UnsatisfiedLinkError: 'int io.netty.channel.unix.ErrorsStaticallyReferencedJniMethods.errnoENOENT()'
		connect.onError(new IOException("bind(..) failed: Address already in use"));
		Mockito.verify(sink).error(
				Mockito.argThat(
						a -> a instanceof ChannelBindException &&
								((ChannelBindException) a).localHost().equals("test2") &&
								((ChannelBindException) a).localPort() == 4956
				)
		);

		// Issue-1668
		connect = new NewConnectionProvider.DisposableConnect(sink, () -> new InetSocketAddress("test3", 7956));
		connect.onError(new IOException("bind(..) failed: Die Adresse wird bereits verwendet"));
		Mockito.verify(sink).error(
				Mockito.argThat(a ->
						a instanceof ChannelBindException &&
								((ChannelBindException) a).localHost().equals("test3") &&
								((ChannelBindException) a).localPort() == 7956
				)
		);
	}


}
