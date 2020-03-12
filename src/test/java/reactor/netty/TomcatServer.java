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
package reactor.netty;

import org.apache.catalina.Context;
import org.apache.catalina.Wrapper;
import org.apache.catalina.startup.Tomcat;

import javax.servlet.MultipartConfigElement;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.Part;
import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.Collection;

/**
 * @author Violeta Georgieva
 */
public class TomcatServer {
	static final String TOMCAT_BASE_DIR = "./build/tomcat";

	final Tomcat tomcat;

	boolean started = false;

	public TomcatServer() {
		this(0);
	}

	public TomcatServer(int port) {
		this.tomcat = new Tomcat();
		this.tomcat.setPort(port);
		File baseDir = new File(TOMCAT_BASE_DIR);
		this.tomcat.setBaseDir(baseDir.getAbsolutePath());
	}

	public int port() {
		if (this.started) {
			return this.tomcat.getConnector().getLocalPort();
		} else {
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

			Collection<Part> parts = req.getParts();
			parts.forEach(p -> builder.append(p.getName()).append(' '));

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

				writer.print(path);
				writer.flush();
			}
		}
	}
}
