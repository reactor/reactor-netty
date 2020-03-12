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
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;
import javax.annotation.Nullable;

import io.netty.buffer.ByteBufAllocator;
import io.netty.handler.codec.http.HttpContent;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.multipart.DiskAttribute;
import io.netty.handler.codec.http.multipart.DiskFileUpload;
import io.netty.handler.codec.http.multipart.FileUpload;
import io.netty.handler.codec.http.multipart.HttpDataFactory;
import io.netty.handler.codec.http.multipart.HttpPostRequestEncoder;
import io.netty.handler.codec.http.multipart.MemoryFileUpload;
import io.netty.handler.stream.ChunkedInput;
import reactor.core.Exceptions;
import reactor.core.publisher.DirectProcessor;

/**
 * Modified {@link io.netty.handler.codec.http.multipart.HttpPostRequestEncoder} for
 * optional filename and builder support
 * <p>
 * This encoder will help to encode Request for a FORM as POST.
 */
final class HttpClientFormEncoder extends HttpPostRequestEncoder
		implements ChunkedInput<HttpContent>, Runnable, HttpClientForm {

	final DirectProcessor<Long> progressFlux;
	final HttpRequest request;

	boolean         needNewEncoder;
	HttpDataFactory newFactory;
	boolean         cleanOnTerminate;
	Charset         newCharset;
	boolean         newMultipart;
	EncoderMode     newMode;

	/**
	 * @param factory the factory used to create InterfaceHttpData
	 * @param request the request to encode
	 * @param multipart True if the FORM is a ENCTYPE="multipart/form-data"
	 * @param charset the charset to use as default
	 * @param encoderMode the mode for the encoder to use. See {@link EncoderMode} for the
	 * details.
	 *
	 * @throws NullPointerException      for request or charset or factory
	 * @throws ErrorDataEncoderException if the request is not a POST
	 */
	HttpClientFormEncoder(HttpDataFactory factory,
			HttpRequest request,
			boolean multipart,
			Charset charset,
			EncoderMode encoderMode) throws ErrorDataEncoderException {
		super(factory, request, multipart, charset, encoderMode);
		this.newCharset = charset;
		this.request = request;
		this.cleanOnTerminate = true;
		this.progressFlux = DirectProcessor.create();
		this.newMode = encoderMode;
		this.newFactory = factory;
		this.newMultipart = multipart;
	}

	@Override
	public HttpContent readChunk(ByteBufAllocator allocator) throws Exception {
		HttpContent c = super.readChunk(allocator);
		if (c == null) {
			progressFlux.onComplete();
		}
		else {
			progressFlux.onNext(progress());
			if (isEndOfInput()) {
				progressFlux.onComplete();
			}
		}
		return c;
	}

	@Override
	public HttpClientForm attr(String name, String value) {
		try {
			addBodyAttribute(name, value);
		}
		catch (ErrorDataEncoderException e) {
			throw Exceptions.propagate(e);
		}
		return this;
	}

	@Override
	public HttpClientForm charset(Charset charset) {
		this.newCharset = Objects.requireNonNull(charset, "charset");
		this.needNewEncoder = true;
		return this;
	}

	@Override
	public HttpClientForm cleanOnTerminate(boolean clean) {
		this.cleanOnTerminate = clean;
		return this;
	}

	@Override
	public HttpClientForm factory(HttpDataFactory factory) {
		if(!getBodyListAttributes().isEmpty()){
			throw new IllegalStateException("Cannot set a new HttpDataFactory after " +
					"starting appending Parts, call factory(f) at the earliest occasion" +
					" offered");
		}
		this.newFactory = Objects.requireNonNull(factory, "factory");
		this.needNewEncoder = true;
		return applyChanges(request);
	}

	@Override
	public HttpClientForm file(String name, File file) {
		file(name, file, null);
		return this;
	}

	@Override
	public HttpClientForm file(String name, InputStream inputStream) {
		file(name, inputStream, null);
		return this;
	}

	@Override
	public HttpClientForm file(String name,
			String filename,
			File file,
			@Nullable String contentType) {
		Objects.requireNonNull(name, "name");
		Objects.requireNonNull(file, "file");
		Objects.requireNonNull(filename, "filename");
		String scontentType = contentType;
		if (contentType == null) {
			scontentType = DEFAULT_BINARY_CONTENT_TYPE;
		}
		FileUpload fileUpload = newFactory.createFileUpload(request,
				name,
				filename,
				scontentType,
				DEFAULT_TRANSFER_ENCODING,
				null,
				file.length());
		try {
			fileUpload.setContent(file);
			addBodyHttpData(fileUpload);
		}
		catch (ErrorDataEncoderException e) {
			throw Exceptions.propagate(e);
		}
		catch (IOException e) {
			throw Exceptions.propagate(new ErrorDataEncoderException(e));
		}
		return this;
	}

	@Override
	public HttpClientForm file(String name,
			String filename,
			InputStream stream,
			@Nullable String contentType) {
		Objects.requireNonNull(name, "name");
		Objects.requireNonNull(stream, "stream");
		try {
			String scontentType = contentType;
			if (contentType == null) {
				scontentType = DEFAULT_BINARY_CONTENT_TYPE;
			}
			MemoryFileUpload fileUpload = new MemoryFileUpload(name,
					filename,
					scontentType,
					DEFAULT_TRANSFER_ENCODING,
					newCharset,
					-1);
			fileUpload.setMaxSize(-1);
			fileUpload.setContent(stream);
			addBodyHttpData(fileUpload);
		}
		catch (ErrorDataEncoderException e) {
			throw Exceptions.propagate(e);
		}
		catch (IOException e) {
			throw Exceptions.propagate(new ErrorDataEncoderException(e));
		}
		return this;
	}

	@Override
	public HttpClientForm files(String name,
			File[] files,
			String[] contentTypes) {
		for (int i = 0; i < files.length; i++) {
			file(name, files[i], contentTypes[i]);
		}
		return this;
	}

	@Override
	public HttpClientForm files(String name,
			File[] files,
			String[] contentTypes,
			boolean[] textFiles) {
		try {
			addBodyFileUploads(name, files, contentTypes, textFiles);
		}
		catch (ErrorDataEncoderException e) {
			throw Exceptions.propagate(e);
		}
		return this;
	}

	@Override
	public HttpClientForm encoding(EncoderMode mode) {
		this.newMode = Objects.requireNonNull(mode, "mode");
		this.needNewEncoder = true;
		return this;
	}

	@Override
	public HttpClientForm multipart(boolean isMultipart) {
		this.needNewEncoder = isChunked() != isMultipart;
		this.newMultipart = isMultipart;
		return this;
	}

	@Override
	public HttpClientForm textFile(String name, File file) {
		textFile(name, file, null);
		return this;
	}

	@Override
	public HttpClientForm textFile(String name, File file, @Nullable String contentType) {
		try {
			addBodyFileUpload(name, file, contentType, true);
		}
		catch (ErrorDataEncoderException e) {
			throw Exceptions.propagate(e);
		}
		return this;
	}

	@Override
	public HttpClientForm textFile(String name, InputStream stream) {
		textFile(name, stream, null);
		return this;
	}

	@Override
	public HttpClientForm textFile(String name,
			InputStream stream,
			@Nullable String contentType) {
		Objects.requireNonNull(name, "name");
		Objects.requireNonNull(stream, "stream");
		try {
			String scontentType = contentType;

			if (contentType == null) {
				scontentType = DEFAULT_TEXT_CONTENT_TYPE;
			}

			MemoryFileUpload fileUpload =
					new MemoryFileUpload(name, "", scontentType, null, newCharset, -1);
			fileUpload.setMaxSize(-1);
			fileUpload.setContent(stream);
			addBodyHttpData(fileUpload);
		}
		catch (ErrorDataEncoderException e) {
			throw Exceptions.propagate(e);
		}
		catch (IOException e) {
			throw Exceptions.propagate(new ErrorDataEncoderException(e));
		}
		return this;
	}

	@Override
	public void run() {
		cleanFiles();
	}

	final HttpClientFormEncoder applyChanges(HttpRequest request) {
		if (!needNewEncoder) {
			return this;
		}

		try {
			HttpClientFormEncoder encoder = new HttpClientFormEncoder(newFactory,
					request,
					newMultipart,
					newCharset,
					newMode);

			encoder.setBodyHttpDatas(getBodyListAttributes());

			return encoder;
		}
		catch(ErrorDataEncoderException ee){
			throw Exceptions.propagate(ee);
		}
	}

	static final Map<Pattern, String> percentEncodings            = new HashMap<>();
	static final String               DEFAULT_BINARY_CONTENT_TYPE =
			"application/octet-stream";
	static final String               DEFAULT_TRANSFER_ENCODING   = "binary";
	static final String               DEFAULT_TEXT_CONTENT_TYPE   = "text/plain";

	static {
		DiskFileUpload.deleteOnExitTemporaryFile = true; // should delete file
		// on exit (in normal
		// exit)
		DiskFileUpload.baseDirectory = null; // system temp directory
		DiskAttribute.deleteOnExitTemporaryFile = true; // should delete file on
		// exit (in normal exit)
		DiskAttribute.baseDirectory = null; // system temp directory

		percentEncodings.put(Pattern.compile("\\*"), "%2A");
		percentEncodings.put(Pattern.compile("\\+"), "%20");
		percentEncodings.put(Pattern.compile("%7E"), "~");
	}
}
