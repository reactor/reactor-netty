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
import java.nio.charset.Charset;
import java.util.Objects;

import io.netty.buffer.ByteBufAllocator;
import io.netty.handler.codec.http.HttpConstants;
import io.netty.handler.codec.http.HttpContent;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.multipart.DiskAttribute;
import io.netty.handler.codec.http.multipart.DiskFileUpload;
import io.netty.handler.codec.http.multipart.HttpDataFactory;
import io.netty.handler.codec.http.multipart.HttpPostRequestEncoder;
import reactor.core.Exceptions;
import reactor.core.publisher.DirectProcessor;

/**
 * @author Stephane Maldini
 */
final class HttpClientFormEncoder extends HttpPostRequestEncoder
		implements Runnable, HttpClientRequest.Form {

	static {
		DiskFileUpload.deleteOnExitTemporaryFile = true; // should delete file
		// on exit (in normal
		// exit)
		DiskFileUpload.baseDirectory = null; // system temp directory
		DiskAttribute.deleteOnExitTemporaryFile = true; // should delete file on
		// exit (in normal exit)
		DiskAttribute.baseDirectory = null; // system temp directory
	}

	final DirectProcessor<Long> progressFlux;

	boolean     cleanOnTerminate;
	Charset     charset;
	EncoderMode mode;

	boolean needNewEncoder;

	HttpClientFormEncoder(HttpDataFactory factory,
			HttpRequest request,
			boolean multipart,
			Charset charset,
			EncoderMode mode) throws ErrorDataEncoderException {
		super(factory, request, multipart);
		this.progressFlux = DirectProcessor.create();
		this.mode = mode;
		this.charset = charset;
		this.cleanOnTerminate = true;
	}

	@Override
	public HttpContent readChunk(ByteBufAllocator allocator) throws Exception {
		HttpContent c = super.readChunk(allocator);
		progressFlux.onNext(progress());
		if (isEndOfInput()) {
			progressFlux.onComplete();
		}
		return c;
	}

	@Override
	public HttpClientRequest.Form attr(String name, String value) {
		try {
			addBodyAttribute(name, value);
		}
		catch (ErrorDataEncoderException e) {
			throw Exceptions.propagate(e);
		}
		return this;
	}

	@Override
	public HttpClientRequest.Form charset(Charset charset) {
		this.charset = Objects.requireNonNull(charset, "charset");
		this.needNewEncoder = true;
		return this;
	}

	@Override
	public HttpClientRequest.Form cleanOnTerminate(boolean clean) {
		this.cleanOnTerminate = clean;
		return this;
	}

	@Override
	public HttpClientRequest.Form file(String name, File file) {
		file(name, file, null);
		return this;
	}

	@Override
	public HttpClientRequest.Form file(String name, File file, String contentType) {
		try {
			addBodyFileUpload(name, file, contentType, false);
		}
		catch (ErrorDataEncoderException e) {
			throw Exceptions.propagate(e);
		}
		return this;
	}

	@Override
	public HttpClientRequest.Form files(String name,
			File[] files,
			String[] contentTypes) {
		for (int i = 0; i < files.length; i++) {
			file(name, files[i], contentTypes[i]);
		}
		return this;
	}

	@Override
	public HttpClientRequest.Form files(String name,
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
	public HttpClientRequest.Form encoding(EncoderMode mode) {
		this.mode = Objects.requireNonNull(mode, "mode");
		this.needNewEncoder = true;
		return this;
	}

	@Override
	public HttpClientRequest.Form textFile(String name, File file) {
		textFile(name, file, null);
		return this;
	}

	@Override
	public HttpClientRequest.Form textFile(String name, File file, String contentType) {
		try {
			addBodyFileUpload(name, file, contentType, true);
		}
		catch (ErrorDataEncoderException e) {
			throw Exceptions.propagate(e);
		}
		return this;
	}

	@Override
	public void run() {
		cleanFiles();
	}

	final HttpClientFormEncoder applyChanges(HttpDataFactory factory, HttpRequest request)
			throws ErrorDataEncoderException {
		if (!needNewEncoder) {
			return this;
		}

		HttpClientFormEncoder encoder =
				new HttpClientFormEncoder(factory, request, isMultipart(), charset, mode);

		encoder.setBodyHttpDatas(getBodyListAttributes());

		return encoder;
	}
}
