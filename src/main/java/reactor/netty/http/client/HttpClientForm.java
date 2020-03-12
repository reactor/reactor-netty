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
package reactor.netty.http.client;

import java.io.File;
import java.io.InputStream;
import java.nio.charset.Charset;
import javax.annotation.Nullable;

import io.netty.handler.codec.http.multipart.HttpDataFactory;
import io.netty.handler.codec.http.multipart.HttpPostRequestEncoder;

/**
 * An HTTP Form builder
 */
public interface HttpClientForm {

	/**
	 * Add an HTTP Form attribute
	 *
	 * @param name Attribute name
	 * @param value Attribute value
	 *
	 * @return this builder
	 */
	HttpClientForm attr(String name, String value);

	/**
	 * Set the Form {@link Charset}
	 *
	 * @param charset form charset
	 *
	 * @return this builder
	 */
	HttpClientForm charset(Charset charset);

	/**
	 * Should file attributes be cleaned and eventually removed from disk.
	 * Default to false.
	 *
	 * @param clean true if cleaned on termination (successful or failed)
	 *
	 * @return this builder
	 */
	HttpClientForm cleanOnTerminate(boolean clean);

	/**
	 * Set Form encoding
	 *
	 * @param mode the encoding mode for this form encoding
	 * @return this builder
	 */
	HttpClientForm encoding(HttpPostRequestEncoder.EncoderMode mode);

	/**
	 * Set Upload factories (allows memory threshold configuration)
	 *
	 * @param factory the new {@link HttpDataFactory} to use
	 * @return this builder
	 */
	HttpClientForm factory(HttpDataFactory factory);

	/**
	 * Add an HTTP File Upload attribute
	 *
	 * @param name File name
	 * @param file File reference
	 *
	 * @return this builder
	 */
	HttpClientForm file(String name, File file);

	/**
	 * Add an HTTP File Upload attribute
	 *
	 * @param name File name
	 * @param stream File content as InputStream
	 *
	 * @return this builder
	 */
	HttpClientForm file(String name, InputStream stream);

	/**
	 * Add an HTTP File Upload attribute
	 *
	 * @param name File name
	 * @param file File reference
	 * @param contentType File mime-type
	 *
	 * @return this builder
	 */
	default HttpClientForm file(String name, File file, @Nullable String contentType) {
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
	HttpClientForm file(String name, String filename, File file, @Nullable String contentType);

	/**
	 * Add an HTTP File Upload attribute
	 *
	 * @param name File name
	 * @param stream File content as InputStream
	 * @param contentType File mime-type
	 *
	 * @return this builder
	 */
	default HttpClientForm file(String name, InputStream stream, @Nullable String contentType) {
		return file(name, "", stream, contentType);
	}

	/**
	 * Add an HTTP File Upload attribute
	 *
	 * @param name File name
	 * @param filename File name to override origin name
	 * @param stream File content as InputStream
	 * @param contentType File mime-type
	 *
	 * @return this builder
	 */
	HttpClientForm file(String name, String filename, InputStream stream, @Nullable String contentType);

	/**
	 * Add an HTTP File Upload attribute
	 *
	 * @param name File name
	 * @param files File references
	 * @param contentTypes File mime-types in the same order than file references
	 *
	 * @return this builder
	 */
	HttpClientForm files(String name, File[] files, String[] contentTypes);

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
	HttpClientForm files(String name, File[] files, String[] contentTypes, boolean[] textFiles);

	/**
	 * Define if this request will be encoded as Multipart
	 *
	 * @param multipart should this form be encoded as Multipart
	 *
	 * @return this builder
	 */
	HttpClientForm multipart(boolean multipart);

	/**
	 * Add an HTTP File Upload attribute for a text file.
	 *
	 * @param name Text file name
	 * @param file Text File reference
	 *
	 * @return this builder
	 */
	HttpClientForm textFile(String name, File file);

	/**
	 * Add an HTTP File Upload attribute for a text file.
	 *
	 * @param name Text file name
	 * @param stream Text file content as InputStream
	 *
	 * @return this builder
	 */
	HttpClientForm textFile(String name, InputStream stream);

	/**
	 * Add an HTTP File Upload attribute for a text file.
	 *
	 * @param name Text file name
	 * @param file Text File reference
	 * @param contentType Text file mime-type
	 *
	 * @return this builder
	 */
	HttpClientForm textFile(String name, File file, @Nullable String contentType);

	/**
	 * Add an HTTP File Upload attribute for a text file.
	 *
	 * @param name Text file name
	 * @param inputStream Text file content as InputStream
	 * @param contentType Text file mime-type
	 *
	 * @return this builder
	 */
	HttpClientForm textFile(String name, InputStream inputStream, @Nullable String contentType);
}
