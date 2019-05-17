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

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectWriter;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder;
import com.github.tomakehurst.wiremock.common.FileSource;
import com.github.tomakehurst.wiremock.extension.Parameters;
import com.github.tomakehurst.wiremock.extension.ResponseDefinitionTransformer;
import com.github.tomakehurst.wiremock.http.HttpHeader;
import com.github.tomakehurst.wiremock.http.Request;
import com.github.tomakehurst.wiremock.http.ResponseDefinition;
import org.junit.rules.ExternalResource;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;

/**
 * An utility class that sets up a "echo-testing" server, a bit like https://httpbin.org.
 * The HTTP server replies to all requests on the `/anything` endpoint with a representation
 * of the request it received, and on the `/status/{xxx}` endpoint with a response of the
 * xxx status. It is started/stopped as a JUnit {@link org.junit.ClassRule}.
 *
 * @author Simon Baslé
 */
public class HttpEchoTestingServer extends ExternalResource {

	static final Pattern STATUS_ENDPOINT_PATTERN = Pattern.compile("/status/([0-9][0-9][0-9])");

	private int port;
	private WireMockServer server;

	@Override
	protected void before() throws Throwable {
		start();
	}

	@Override
	protected void after() {
		stop();
	}

	public void start() {
		this.port = SocketUtils.findAvailableTcpPort();
		server = createWireMockServer(port);
		server.start();
	}

	public void stop() {
		server.stop();
	}

	/**
	 * @return the port on which the http echo-testing server runs. Host is always localhost.
	 */
	public int getPort() {
		return this.port;
	}

	/**
	 * @return the base HTTP url on which the http echo-testing server has its endpoints.
	 */
	public String getBaseUrl() {
		return "http://localhost:" + port + "/";
	}

	private static class HttpEchoTransformer extends ResponseDefinitionTransformer {

		private final ObjectWriter writer;

		public HttpEchoTransformer(ObjectMapper mapper) {
			this.writer = mapper.writerWithDefaultPrettyPrinter();
		}

		@Override
		public ResponseDefinition transform(Request request, ResponseDefinition responseDefinition, FileSource files, Parameters parameters) {
			int status = 200;
			boolean parseStatus = parameters.getBoolean("parseStatus", Boolean.FALSE);
			boolean headersOnly = parameters.getBoolean("headersOnly", Boolean.FALSE);

			Map<String, Object> requestMap = new LinkedHashMap<>();
			Map<String, Object> headers = new LinkedHashMap<>();
			for (HttpHeader h : request.getHeaders()
			                           .all()) {
				headers.put(h.key(), h.isSingleValued() ? h.firstValue() : h.values());
			}

			if (headersOnly) {
				requestMap.put("headers", headers);
			}
			else {
				requestMap.put("method", request.getMethod().getName());
				requestMap.put("uri", request.getAbsoluteUrl());

				String path = request.getUrl();
				int queryParamsStart = request.getUrl().indexOf('?');
				if (queryParamsStart >=0) {
					path = request.getUrl().substring(0, queryParamsStart);
					String queryParams = request.getUrl().substring(queryParamsStart + 1);
					String[] paramsArray = queryParams.split("&");

					Map<String, List<String>> params = new LinkedHashMap<>();
					for (String entry : paramsArray) {
						String[] param = entry.split("=");
						params.put(param[0], Collections.singletonList(param[1]));
					}
					requestMap.put("path", path);
					requestMap.put("queryParams", params);
				}
				else {
					requestMap.put("path", request.getUrl());
				}

				if (parseStatus) {
					Matcher matcher = STATUS_ENDPOINT_PATTERN.matcher(path);
					if (matcher.matches()) {
						String statusString = matcher.group(1);
						status = Integer.parseInt(statusString);
					}
				}

				requestMap.put("headers", headers);
				requestMap.put("body", request.getBodyAsString());
			}

			String requestAsString;
			try {
				requestAsString = writer.writeValueAsString(requestMap);
			}
			catch (JsonProcessingException e) {
				e.printStackTrace();
				requestAsString = request.toString();
			}
			return new ResponseDefinitionBuilder()
					.withStatus(status)
					.withBody(requestAsString)
					.build();
		}

		@Override
		public String getName() {
			return "httpEcho";
		}

		@Override
		public boolean applyGlobally() {
			return false;
		}
	}

	private static WireMockServer createWireMockServer(int port) {
		WireMockServer wireMockServer = new WireMockServer(options()
				.extensions(new HttpEchoTransformer(new ObjectMapper()))
				.disableRequestJournal()
				.port(port));

		wireMockServer.stubFor(any(urlPathMatching(STATUS_ENDPOINT_PATTERN.pattern()))
				.willReturn(aResponse()
				.withTransformer("httpEcho", "parseStatus", true)));

		wireMockServer.stubFor(any(urlPathEqualTo("/anything"))
				.willReturn(aResponse()
				.withTransformers("httpEcho")));

		wireMockServer.stubFor(any(urlPathEqualTo("/headers"))
				.willReturn(aResponse()
				.withTransformer("httpEcho", "headersOnly", true)));

		return wireMockServer;
	}

}
