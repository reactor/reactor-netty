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
package reactor.netty.http;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class Http11WebSocketServerMetricsHandlerTests extends WebSocketServerMetricsHandlerTests {

	@Test
	void testServerWebSocketHandshakeTimeMicrometer() {
		doTestServerWebSocketHandshakeTimeMicrometer(httpServer, httpClient);
	}

	@Test
	void testServerWebSocketHandshakeFailureMicrometer() {
		doTestServerWebSocketHandshakeFailureMicrometer(httpServer, httpClient);
	}

	@ParameterizedTest
	@ValueSource(booleans = {true, false})
	void testServerWebSocketSynchronousHandshakeFailureMicrometer(boolean onEventLoop) {
		doTestServerWebSocketSynchronousHandshakeFailureMicrometer(httpServer, httpClient, onEventLoop);
	}

	@ParameterizedTest
	@ValueSource(booleans = {true, false})
	void testServerWebSocketSynchronousHandshakeFailureContextAwareRecorder(boolean onEventLoop) {
		doTestServerWebSocketSynchronousHandshakeFailureContextAwareRecorder(httpServer, httpClient, onEventLoop);
	}

	@Test
	void testServerWebSocketFailureBeforeHandshakeStart() {
		doTestServerWebSocketFailureBeforeHandshakeStart(httpServer, httpClient);
	}

	@ParameterizedTest
	@CsvSource({"false,false", "true,false", "true,true"})
	void testServerWebSocketContextAwareRecorderUpgradeNotSubscribed(boolean failHandshakeRecording, boolean failConnectionDurationRecording)
			throws Exception {
		doTestServerWebSocketContextAwareRecorderUpgradeNotSubscribed(httpServer, httpClient,
				failHandshakeRecording, failConnectionDurationRecording);
	}

	@Test
	void testServerWebSocketUriTagValueFunctionMicrometer() {
		doTestServerWebSocketUriTagValueFunctionMicrometer(httpServer, httpClient);
	}

	@Test
	void testServerWebSocketConnectionDurationMicrometer() {
		doTestServerWebSocketConnectionDurationMicrometer(httpServer, httpClient);
	}

	@Test
	void testServerWebSocketDataMetricsMicrometer() {
		doTestServerWebSocketDataMetricsMicrometer(httpServer, httpClient);
	}

	@Test
	void testServerWebSocketMultipleMessagesSentMicrometer() {
		doTestServerWebSocketMultipleMessagesSentMicrometer(httpServer, httpClient);
	}

	@Test
	void testServerWebSocketMultipleMessagesReceivedMicrometer() {
		doTestServerWebSocketMultipleMessagesReceivedMicrometer(httpServer, httpClient);
	}

	@Test
	void testServerWebSocketControlFramesExcludedFromDataMetricsMicrometer() {
		doTestServerWebSocketControlFramesExcludedFromDataMetricsMicrometer(httpServer, httpClient);
	}

	@Test
	void testServerWebSocketFragmentedDataReceivedMicrometer() {
		doTestServerWebSocketFragmentedDataReceivedMicrometer(httpServer, httpClient);
	}

	@Test
	void testServerWebSocketErrorsOnApplicationErrorMicrometer() {
		doTestServerWebSocketErrorsOnApplicationErrorMicrometer(httpServer, httpClient);
	}

	@Test
	void testServerWebSocketCustomRecorder() {
		doTestServerWebSocketCustomRecorder(httpServer, httpClient);
	}

	@Test
	void testServerWebSocketHttpRecorder() {
		doTestServerWebSocketHttpRecorder(httpServer, httpClient);
	}

	@Test
	void testServerWebSocketContextAwareRecorder() {
		doTestServerWebSocketContextAwareRecorder(httpServer, httpClient);
	}

	@Test
	void testServerWebSocketContextAwareRecorderAfterHandshake() {
		doTestServerWebSocketContextAwareRecorderAfterHandshake(httpServer, httpClient);
	}

	@Test
	void testServerWebSocketContextAwareRecorderAfterHandshakeOffEventLoop() throws Exception {
		doTestServerWebSocketContextAwareRecorderAfterHandshakeOffEventLoop(httpServer, httpClient);
	}
}
