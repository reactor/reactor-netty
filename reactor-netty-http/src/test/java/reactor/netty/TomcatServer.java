/*
 * Copyright (c) 2019-2023 VMware, Inc. or its affiliates, All Rights Reserved.
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

import org.apache.catalina.Context;
import org.apache.catalina.Wrapper;
import org.apache.catalina.startup.Tomcat;
import org.apache.coyote.AbstractProtocol;
import org.apache.coyote.ProtocolHandler;
import org.apache.coyote.http11.AbstractHttp11Protocol;
import org.apache.tomcat.util.security.Escape;

import javax.servlet.MultipartConfigElement;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.Part;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintWriter;
import java.util.Collection;

/**
 * Prepares Tomcat server.
 *
 * @author Violeta Georgieva
 */
public class TomcatServer {
	static final String TOMCAT_BASE_DIR = "./build/tomcat";
	public static final String TOO_LARGE = "Request payload too large";
	public static final int PAYLOAD_MAX = 4096;

	final Tomcat tomcat;

	boolean started;

	public TomcatServer() {
		this(0);
	}

	public TomcatServer(int port) {
		this.tomcat = new Tomcat();
		this.tomcat.setPort(port);
		File baseDir = new File(TOMCAT_BASE_DIR);
		this.tomcat.setBaseDir(baseDir.getAbsolutePath());
	}

	public int getMaxSwallowSize() {
		ProtocolHandler protoHandler = tomcat.getConnector().getProtocolHandler();
		if (!(protoHandler instanceof AbstractProtocol<?>)) {
			throw new IllegalStateException("Connection protocol handler is not an instance of AbstractProtocol: " + protoHandler.getClass().getName());
		}
		AbstractHttp11Protocol<?> protocol = (AbstractHttp11Protocol<?>) protoHandler;
		return protocol.getMaxSwallowSize();
	}

	public void setMaxSwallowSize(int bytes) {
		ProtocolHandler protoHandler = tomcat.getConnector().getProtocolHandler();
		if (!(protoHandler instanceof AbstractProtocol<?>)) {
			throw new IllegalStateException("Connection protocol handler is not an instance of AbstractProtocol: " + protoHandler.getClass().getName());
		}
		AbstractHttp11Protocol<?> protocol = (AbstractHttp11Protocol<?>) protoHandler;
		protocol.setMaxSwallowSize(bytes);
	}

	public int port() {
		if (this.started) {
			return this.tomcat.getConnector().getLocalPort();
		}
		else {
			throw new IllegalStateException("Tomcat is not started.");
		}
	}

	public void start() throws Exception {
		if (!this.started) {
			this.tomcat.start();
			this.started = true;
		}
	}

	public void stop() throws Exception {
		if (this.started) {
			this.tomcat.stop();
			this.tomcat.destroy();
			this.started = false;
		}
	}

	public void createDefaultContext() {
		Context ctx = this.tomcat.addContext("", null);

		addServlet(ctx, new DefaultServlet(), "/");
		addServlet(ctx, new StatusServlet(), "/status/*");
		addServlet(ctx, new MultipartServlet(), "/multipart")
				.setMultipartConfigElement(new MultipartConfigElement(""));
		addServlet(ctx, new PayloadSizeServlet(), "/payload-size");
	}

	public void createContext(HttpServlet servlet, String mapping) {
		Context ctx = this.tomcat.addContext("", null);

		addServlet(ctx, servlet, mapping);
	}

	private Wrapper addServlet(Context ctx, HttpServlet servlet, String mapping) {
		String servletName = servlet.getClass().getName();
		Wrapper wrapper = Tomcat.addServlet(ctx, servletName, servlet);
		ctx.addServletMappingDecoded(mapping, servletName);
		return wrapper;
	}

	static final class DefaultServlet extends HttpServlet {

		@Override
		@SuppressWarnings("JdkObsolete")
		protected void service(HttpServletRequest req, HttpServletResponse resp) throws IOException {
			String contentLength = req.getHeader("Content-Length");
			if ("GET".equals(req.getMethod()) && contentLength != null
					&& !contentLength.isEmpty() && Integer.parseInt(contentLength) > 0) {
				resp.sendError(400);
			}
			else {
				PrintWriter writer = resp.getWriter();

				StringBuffer url = req.getRequestURL();

				String query = req.getQueryString();
				if (query != null && !query.isEmpty()) {
					// StringBuilder cannot be used as req.getRequestURL() returns StringBuffer
					url.append('?').append(query);
				}

				writer.print(url);
				writer.flush();
			}
		}
	}

	static final class MultipartServlet extends HttpServlet {

		@Override
		protected void service(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
			PrintWriter writer = resp.getWriter();

			StringBuilder builder = new StringBuilder();

			if ("application/x-www-form-urlencoded".equals(req.getHeader("content-type"))) {
				builder.append(req.getReader().readLine());
			}
			else {
				Collection<Part> parts = req.getParts();
				parts.forEach(p -> builder.append(p.getName()).append(' '));
			}

			writer.print(builder);
			writer.flush();
		}
	}

	static final class StatusServlet extends HttpServlet {

		@Override
		protected void service(HttpServletRequest req, HttpServletResponse resp) throws IOException {
			String path = req.getPathInfo();
			if (path != null) {
				PrintWriter writer = resp.getWriter();

				if (path.startsWith("/")) {
					path = path.substring(1);
				}
				resp.setStatus(Integer.parseInt(path));

				// Use Tomcat's HTML escaping method, to avoid cross-site scripting Github security alert.
				String sanitizedPath = Escape.htmlElementContent(path);

				writer.print(sanitizedPath);
				writer.flush();
			}
		}
	}

	static final class PayloadSizeServlet extends HttpServlet {

		@Override
		protected void service(HttpServletRequest req, HttpServletResponse resp) throws IOException {
			InputStream in = req.getInputStream();
			int count = 0;
			byte[] buf = new byte[4096];
			int n;

			while ((n = in.read(buf, 0, buf.length)) != -1) {
				count += n;
				if (count >= PAYLOAD_MAX) {
					// By default, Tomcat is configured with maxSwallowSize=2 MB (see https://tomcat.apache.org/tomcat-9.0-doc/config/http.html)
					// This means that once the 400 bad request is sent, the client will still be able to continue writing (if it is currently writing)
					// up to 2 MB. So, it is very likely that the client will be blocked, and it will then be able to consume the 400 bad request and
					// close itself the connection.
					sendResponse(resp, TOO_LARGE, HttpServletResponse.SC_BAD_REQUEST);
					return;
				}
			}

			sendResponse(resp, String.valueOf(count), HttpServletResponse.SC_OK);
		}

		private void sendResponse(HttpServletResponse resp, String message, int status) throws IOException {
			resp.setStatus(status);
			resp.setHeader("Content-Length", String.valueOf(message.length()));
			resp.setHeader("Content-Type", "text/plain");
			PrintWriter out = resp.getWriter();
			out.print(message);
			out.flush();
		}
	}
}
