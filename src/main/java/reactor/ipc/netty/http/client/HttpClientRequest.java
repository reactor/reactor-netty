/*
 * Copyright (c) 2011-2016 Pivotal Software Inc, All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package reactor.ipc.netty.http.client;

import java.io.File;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.util.function.Consumer;

import io.netty.channel.ChannelHandler;
import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.cookie.Cookie;
import io.netty.handler.codec.http.multipart.HttpPostRequestEncoder;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.ipc.netty.NettyOutbound;
import reactor.ipc.netty.channel.ChannelOperations;
import reactor.ipc.netty.http.HttpOutbound;

/**
 * An Http Reactive client write contract for outgoing requests. It inherits several
 * accessor related to HTTP flow : headers, params, URI, method, websocket...
 *
 * @author Stephane Maldini
 */
public interface HttpClientRequest extends HttpOutbound {


	@Override
	HttpClientRequest addChannelHandler(ChannelHandler handler);

	@Override
	HttpClientRequest addChannelHandler(String name, ChannelHandler handler);

	@Override
	HttpClientRequest addCookie(Cookie cookie);

	@Override
	HttpClientRequest addHeader(CharSequence name, CharSequence value);

	@Override
	HttpClientRequest disableChunkedTransfer();

	@Override
	HttpClientRequest flushEach();

	/**
	 * Enable http status 302 auto-redirect support
	 *
	 * @return {@literal this}
	 */
	HttpClientRequest followRedirect();

	@Override
	HttpClientRequest header(CharSequence name, CharSequence value);

	/**
	 * Return true  if redirected will be followed
	 *
	 * @return true if redirected will be followed
	 */
	boolean isFollowRedirect();

	@Override
	HttpClientRequest keepAlive(boolean keepAlive);

	@Override
	HttpClientRequest onWriteIdle(long idleTimeout, Runnable onWriteIdle);

	/**
	 * Return the previous redirections or empty array
	 *
	 * @return the previous redirections or empty array
	 */
	String[] redirectedFrom();

	/**
	 * Return outbound headers to be sent
	 *
	 * @return outbound headers to be sent
	 */
	HttpHeaders requestHeaders();

	/**
	 * Prepare to send an HTTP Form excluding multipart
	 *
	 * @param formCallback called when form frames generator is created
	 *
	 * @return a {@link Flux} of latest in-flight or uploaded bytes,
	 */
	Flux<Long> sendForm(Consumer<Form> formCallback);

	/**
	 * Prepare to send an HTTP Form in multipart mode for file upload facilities
	 *
	 * @param formCallback called when form frames generator is created
	 *
	 * @return a {@link Flux} of latest in-flight or uploaded bytes,
	 */
	Flux<Long> sendMultipart(Consumer<Form> formCallback);

	/**
	 * Upgrade connection to Websocket with text plain payloads
	 *
	 * @return a {@link Mono} completing when upgrade is confirmed
	 */
	default Mono<Void> upgradeToTextWebsocket() {
		return upgradeToWebsocket(uri(), true, ChannelOperations.noopHandler());
	}

	/**
	 * Upgrade connection to Websocket
	 *
	 * @return a {@link Mono} completing when upgrade is confirmed
	 */
	default Mono<Void> upgradeToWebsocket() {
		return upgradeToWebsocket(uri(), false, ChannelOperations.noopHandler());
	}

	/**
	 * Add an HTTP Form builder
	 */
	interface Form {

		/**
		 * Add an HTTP Form attribute
		 *
		 * @param name Attribute name
		 * @param value Attribute value
		 *
		 * @return this builder
		 */
		Form attr(String name, String value);

		/**
		 * Set the Form {@link Charset}
		 *
		 * @param charset form charset
		 *
		 * @return this builder
		 */
		Form charset(Charset charset);


		/**
		 * Should file attributes be cleaned and eventually removed from disk.
		 * Default to false.
		 *
		 * @param clean true if cleaned on termination (successful or failed)
		 *
		 * @return this builder
		 */
		Form cleanOnTerminate(boolean clean);

		/**
		 * Set Form encoding
		 *
		 * @param mode the encoding mode for this form
		 * encoding
		 * @return this builder
		 */
		Form encoding(HttpPostRequestEncoder.EncoderMode mode);

		/**
		 * Add an HTTP File Upload attribute
		 *
		 * @param name File name
		 * @param file File reference
		 *
		 * @return this builder
		 */
		Form file(String name, File file);

		/**
		 * Add an HTTP File Upload attribute
		 *
		 * @param name File name
		 * @param stream InputStream reference
		 *
		 * @return this builder
		 */
		Form file(String name, InputStream stream);

		/**
		 * Add an HTTP File Upload attribute
		 *
		 * @param name File name
		 * @param file File reference
		 * @param contentType File mime-type
		 *
		 * @return this builder
		 */
		default Form file(String name, File file, String contentType){
			return file(name, file.getName(), file, contentType);
		}

		/**
		 * Add an HTTP File Upload attribute
		 *
		 * @param name File name
		 * @param filename File name to override origin name
		 * @param file File reference
		 * @param contentType File mime-type
		 *
		 * @return this builder
		 */
		Form file(String name, String filename, File file, String contentType);

		/**
		 * Add an HTTP File Upload attribute
		 *
		 * @param name File name
		 * @param stream File reference
		 * @param contentType File mime-type
		 *
		 * @return this builder
		 */
		default Form file(String name, InputStream stream, String contentType){
			return file(name, "", stream, contentType);
		}

		/**
		 * Add an HTTP File Upload attribute
		 *
		 * @param name File name
		 * @param filename File name to override origin name
		 * @param stream File reference
		 * @param contentType File mime-type
		 *
		 * @return this builder
		 */
		Form file(String name, String filename, InputStream stream, String contentType);

		/**
		 * Add an HTTP File Upload attribute
		 *
		 * @param name File name
		 * @param files File references
		 * @param contentTypes File mime-types in the same order than file references
		 *
		 * @return this builder
		 */
		Form files(String name, File[] files, String[] contentTypes);

		/**
		 * Add an HTTP File Upload attribute
		 *
		 * @param name File name
		 * @param files File references
		 * @param contentTypes File mime-type in the same order than file references
		 * @param textFiles Plain-Text transmission in the same order than file references
		 *
		 * @return this builder
		 */
		Form files(String name, File[] files, String[] contentTypes, boolean[] textFiles);

		/**
		 * Add an HTTP File Upload attribute
		 *
		 * @param name File name
		 * @param file File reference
		 *
		 * @return this builder
		 */
		Form textFile(String name, File file);

		/**
		 * Add an HTTP File Upload attribute
		 *
		 * @param name File name
		 * @param stream InputStream reference
		 *
		 * @return this builder
		 */
		Form textFile(String name, InputStream stream);

		/**
		 * Add an HTTP File Upload attribute
		 *
		 * @param name File name
		 * @param file File reference
		 * @param contentType File mime-type
		 *
		 * @return this builder
		 */
		Form textFile(String name, File file, String contentType);

		/**
		 * Add an HTTP File Upload attribute
		 *
		 * @param name File name
		 * @param inputStream File reference
		 * @param contentType File mime-type
		 *
		 * @return this builder
		 */
		Form textFile(String name, InputStream inputStream, String contentType);
	}
}
