/*
 * Copyright (c) 2020-2021 VMware, Inc. or its affiliates, All Rights Reserved.
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
package reactor.netty.examples.documentation.http.client.metrics.custom;

import reactor.netty.http.client.HttpClient;
import reactor.netty.http.client.HttpClientMetricsRecorder;

import java.net.SocketAddress;
import java.time.Duration;

public class Application {

	public static void main(String[] args) {
		HttpClient client =
				HttpClient.create()
				          .metrics(true, CustomHttpClientMetricsRecorder::new); //<1>

		client.get()
		      .uri("https://httpbin.org/stream/2")
		      .response()
		      .block();
	}

	private static class CustomHttpClientMetricsRecorder implements HttpClientMetricsRecorder {

		@Override
		public void recordDataReceived(SocketAddress remoteAddress, String uri, long bytes) {
		}

		@Override
		public void recordDataSent(SocketAddress remoteAddress, String uri, long bytes) {
		}

		@Override
		public void incrementErrorsCount(SocketAddress remoteAddress, String uri) {
		}

		@Override
		public void recordDataReceivedTime(SocketAddress remoteAddress, String uri, String method, String status, Duration time) {
		}

		@Override
		public void recordDataSentTime(SocketAddress remoteAddress, String uri, String method, Duration time) {
		}

		@Override
		public void recordResponseTime(SocketAddress remoteAddress, String uri, String method, String status, Duration time) {
		}

		@Override
		public void recordDataReceived(SocketAddress socketAddress, long l) {
		}

		@Override
		public void recordDataSent(SocketAddress socketAddress, long l) {
		}

		@Override
		public void incrementErrorsCount(SocketAddress socketAddress) {
		}

		@Override
		public void recordTlsHandshakeTime(SocketAddress socketAddress, Duration duration, String s) {
		}

		@Override
		public void recordConnectTime(SocketAddress socketAddress, Duration duration, String s) {
		}

		@Override
		public void recordResolveAddressTime(SocketAddress socketAddress, Duration duration, String s) {
		}
	}
}