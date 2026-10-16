/*
 * Copyright (c) 2011-2026 VMware, Inc. or its affiliates, All Rights Reserved.
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
package reactor.netty.http.client;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

import io.netty.buffer.ByteBufAllocator;
import io.netty.handler.codec.http.HttpContent;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.multipart.DiskAttribute;
import io.netty.handler.codec.http.multipart.DiskFileUpload;
import io.netty.handler.codec.http.multipart.FileUpload;
import io.netty.handler.codec.http.multipart.HttpDataFactory;
import io.netty.handler.codec.http.multipart.HttpPostRequestEncoder;
import io.netty.handler.codec.http.multipart.InterfaceHttpData;
import io.netty.handler.stream.ChunkedInput;
import org.jspecify.annotations.Nullable;
import reactor.core.Exceptions;
import reactor.core.publisher.Sinks;

/**
 * Modified {@link io.netty.handler.codec.http.multipart.HttpPostRequestEncoder} for
 * optional filename and builder support
 * <p>
 * This encoder will help to encode Request for a FORM as POST.
 */
final class HttpClientFormEncoder extends HttpPostRequestEncoder implements ChunkedInput<HttpContent>, HttpClientForm {

	final Sinks.Many<Long> progressSink;
	final HttpRequest request;

	boolean         needNewEncoder;
	boolean         needNewFactory;
	boolean         partsEncoded;
	HttpDataFactory newFactory;
	boolean         cleanOnTerminate;
	Charset         newCharset;
	boolean         newMultipart;
	EncoderMode     newMode;

	@Nullable HttpClientFormEncoder nextEncoder;

	/**
	 * Constructs a new {@link HttpClientFormEncoder}.
	 *
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
	HttpClientFormEncoder(HttpDataFactory factory, HttpRequest request, boolean multipart, Charset charset, EncoderMode encoderMode)
			throws ErrorDataEncoderException {
		super(factory, request, multipart, charset, encoderMode);
		this.newCharset = charset;
		this.request = request;
		this.cleanOnTerminate = true;
		this.progressSink = Sinks.many().unicast().onBackpressureError();
		this.newMode = encoderMode;
		this.newFactory = factory;
		this.newMultipart = multipart;
	}

	@Override
	public void addBodyHttpData(InterfaceHttpData data) throws ErrorDataEncoderException {
		if (!partsEncoded) {
			// Create parts with the configured factory, but wait for the final form settings
			// before encoding them. A temporary encoding can exceed the factory's size limit.
			getBodyListAttributes().add(Objects.requireNonNull(data, "data"));
		}
		else {
			super.addBodyHttpData(data);
		}
	}

	@Override
	public HttpRequest finalizeRequest() throws ErrorDataEncoderException {
		if (!partsEncoded) {
			List<InterfaceHttpData> parts = new ArrayList<>(getBodyListAttributes());
			partsEncoded = true;
			setBodyHttpDatas(parts);
		}
		return super.finalizeRequest();
	}

	@Override
	public HttpContent readChunk(ByteBufAllocator allocator) throws Exception {
		HttpContent c = super.readChunk(allocator);
		if (c == null) {
			progressSink.emitComplete(Sinks.EmitFailureHandler.FAIL_FAST);
		}
		else {
			progressSink.emitNext(progress(), Sinks.EmitFailureHandler.FAIL_FAST);
			if (isEndOfInput()) {
				progressSink.emitComplete(Sinks.EmitFailureHandler.FAIL_FAST);
			}
		}
		return c;
	}

	@Override
	public HttpClientForm attr(String name, String value) {
		HttpClientFormEncoder encoder = encoderForParts();
		if (encoder != this) {
			return encoder.attr(name, value);
		}
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
		Objects.requireNonNull(charset, "charset");
		HttpClientFormEncoder encoder = currentEncoder();
		if (encoder != this) {
			return encoder.charset(charset);
		}
		if (!charset.equals(newCharset)) {
			this.newCharset = charset;
			this.needNewEncoder = true;
		}
		return this;
	}

	@Override
	public HttpClientForm cleanOnTerminate(boolean clean) {
		HttpClientFormEncoder encoder = currentEncoder();
		if (encoder != this) {
			return encoder.cleanOnTerminate(clean);
		}
		this.cleanOnTerminate = clean;
		return this;
	}

	@Override
	public HttpClientForm factory(HttpDataFactory factory) {
		if (!getBodyListAttributes().isEmpty()) {
			throw new IllegalStateException("Cannot set a new HttpDataFactory after " +
					"starting appending Parts, call factory(f) at the earliest occasion" +
					" offered");
		}
		Objects.requireNonNull(factory, "factory");
		HttpClientFormEncoder encoder = currentEncoder();
		if (encoder != this) {
			return encoder.factory(factory);
		}
		if (factory != newFactory) {
			this.newFactory = factory;
			this.needNewEncoder = true;
			this.needNewFactory = true;
		}
		return this;
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
	public HttpClientForm file(String name, String filename, File file, @Nullable String contentType) {
		Objects.requireNonNull(name, "name");
		Objects.requireNonNull(file, "file");
		Objects.requireNonNull(filename, "filename");
		HttpClientFormEncoder encoder = encoderForParts();
		if (encoder != this) {
			return encoder.file(name, filename, file, contentType);
		}
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
	public HttpClientForm file(String name, String filename, InputStream stream, @Nullable String contentType) {
		Objects.requireNonNull(name, "name");
		Objects.requireNonNull(stream, "stream");
		HttpClientFormEncoder encoder = encoderForParts();
		if (encoder != this) {
			return encoder.file(name, filename, stream, contentType);
		}
		try {
			String scontentType = contentType;
			if (contentType == null) {
				scontentType = DEFAULT_BINARY_CONTENT_TYPE;
			}
			FileUpload fileUpload = newFactory.createFileUpload(request,
					name,
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
	public HttpClientForm files(String name, File[] files, String[] contentTypes) {
		for (int i = 0; i < files.length; i++) {
			file(name, files[i], contentTypes[i]);
		}
		return this;
	}

	@Override
	public HttpClientForm files(String name, File[] files, String[] contentTypes, boolean[] textFiles) {
		HttpClientFormEncoder encoder = encoderForParts();
		if (encoder != this) {
			return encoder.files(name, files, contentTypes, textFiles);
		}
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
		Objects.requireNonNull(mode, "mode");
		HttpClientFormEncoder encoder = currentEncoder();
		if (encoder != this) {
			return encoder.encoding(mode);
		}
		if (mode != newMode) {
			this.newMode = mode;
			this.needNewEncoder = true;
		}
		return this;
	}

	@Override
	public HttpClientForm multipart(boolean isMultipart) {
		HttpClientFormEncoder encoder = currentEncoder();
		if (encoder != this) {
			return encoder.multipart(isMultipart);
		}
		this.needNewEncoder |= isMultipart() != isMultipart;
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
		HttpClientFormEncoder encoder = encoderForParts();
		if (encoder != this) {
			return encoder.textFile(name, file, contentType);
		}
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
	public HttpClientForm textFile(String name, InputStream stream, @Nullable String contentType) {
		Objects.requireNonNull(name, "name");
		Objects.requireNonNull(stream, "stream");
		HttpClientFormEncoder encoder = encoderForParts();
		if (encoder != this) {
			return encoder.textFile(name, stream, contentType);
		}
		try {
			String scontentType = contentType;

			if (contentType == null) {
				scontentType = DEFAULT_TEXT_CONTENT_TYPE;
			}

			FileUpload fileUpload = newFactory.createFileUpload(request, name, "", scontentType, null, newCharset, -1);
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
	public void cleanFiles() {
		HttpClientFormEncoder encoder = currentEncoder();
		if (encoder != this) {
			encoder.cleanFiles();
		}
		else {
			super.cleanFiles();
		}
	}

	final HttpClientFormEncoder applyChanges(HttpRequest request) {
		HttpClientFormEncoder current = currentEncoder();
		if (!current.needNewEncoder) {
			return current;
		}

		try {
			HttpClientFormEncoder encoder = new HttpClientFormEncoder(current.newFactory, request, current.newMultipart, current.newCharset, current.newMode);

			encoder.cleanOnTerminate = current.cleanOnTerminate;
			encoder.setBodyHttpDatas(current.getBodyListAttributes());

			current.needNewEncoder = false;
			current.needNewFactory = false;
			current.nextEncoder = encoder;

			return encoder;
		}
		catch (ErrorDataEncoderException ee) {
			throw Exceptions.propagate(ee);
		}
	}

	/**
	 * Parts must be created by the configured factory, so a pending factory change is
	 * applied before adding a part. The other settings are applied to all parts by the
	 * final {@link #applyChanges(HttpRequest)}, so they do not trigger a rebuild here.
	 */
	private HttpClientFormEncoder encoderForParts() {
		HttpClientFormEncoder encoder = currentEncoder();
		return encoder.needNewFactory ? encoder.applyChanges(request) : encoder;
	}

	private HttpClientFormEncoder currentEncoder() {
		HttpClientFormEncoder encoder = this;
		while (encoder.nextEncoder != null) {
			encoder = encoder.nextEncoder;
		}
		return encoder;
	}

	static final Map<Pattern, String> percentEncodings            = new HashMap<>();
	static final String               DEFAULT_BINARY_CONTENT_TYPE = "application/octet-stream";
	static final String               DEFAULT_TRANSFER_ENCODING   = "binary";
	static final String               DEFAULT_TEXT_CONTENT_TYPE   = "text/plain";

	static {
		DiskFileUpload.deleteOnExitTemporaryFile = true; // should delete file on exit (in normal exit)
		DiskFileUpload.baseDirectory = null; // system temp directory
		DiskAttribute.deleteOnExitTemporaryFile = true; // should delete file on exit (in normal exit)
		DiskAttribute.baseDirectory = null; // system temp directory

		percentEncodings.put(Pattern.compile("\\*"), "%2A");
		percentEncodings.put(Pattern.compile("\\+"), "%20");
		percentEncodings.put(Pattern.compile("%7E"), "~");
	}
}
