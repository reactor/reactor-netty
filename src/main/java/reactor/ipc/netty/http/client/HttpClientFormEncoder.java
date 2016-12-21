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
import java.io.IOException;
import java.io.InputStream;
import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.ListIterator;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.DecoderResult;
import io.netty.handler.codec.http.DefaultFullHttpRequest;
import io.netty.handler.codec.http.DefaultHttpContent;
import io.netty.handler.codec.http.EmptyHttpHeaders;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.HttpContent;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpHeaderValues;
import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpUtil;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.codec.http.LastHttpContent;
import io.netty.handler.codec.http.multipart.Attribute;
import io.netty.handler.codec.http.multipart.DiskAttribute;
import io.netty.handler.codec.http.multipart.DiskFileUpload;
import io.netty.handler.codec.http.multipart.FileUpload;
import io.netty.handler.codec.http.multipart.HttpData;
import io.netty.handler.codec.http.multipart.HttpDataFactory;
import io.netty.handler.codec.http.multipart.HttpPostRequestEncoder.EncoderMode;
import io.netty.handler.codec.http.multipart.HttpPostRequestEncoder.ErrorDataEncoderException;
import io.netty.handler.codec.http.multipart.InterfaceHttpData;
import io.netty.handler.codec.http.multipart.MemoryFileUpload;
import io.netty.handler.stream.ChunkedInput;
import io.netty.util.AbstractReferenceCounted;
import io.netty.util.internal.ThreadLocalRandom;
import reactor.core.Exceptions;
import reactor.core.publisher.DirectProcessor;

import static io.netty.buffer.Unpooled.wrappedBuffer;

/**
 * Modified {@link io.netty.handler.codec.http.multipart.HttpPostRequestEncoder} for
 * optional filename and builder support
 * <p>
 * This encoder will help to encode Request for a FORM as POST.
 */
final class HttpClientFormEncoder
		implements ChunkedInput<HttpContent>, Runnable, HttpClientRequest.Form {

	/**
	 * Factory used to create InterfaceHttpData
	 */
	final HttpDataFactory         factory;
	/**
	 * Request to encode
	 */
	final HttpRequest             request;
	/**
	 * Default charset to use
	 */
	final Charset                 charset;
	/**
	 * InterfaceHttpData for Body (without encoding)
	 */
	final List<InterfaceHttpData> bodyListDatas;
	/**
	 * The final Multipart List of InterfaceHttpData including encoding
	 */
	final List<InterfaceHttpData> multipartHttpDatas;
	/**
	 * Does this request is a Multipart request
	 */
	final boolean                 isMultipart;
	/**
	 * Form mode
	 */
	final EncoderMode             encoderMode;
	/**
	 * Progress flux
	 */
	final DirectProcessor<Long>   progressFlux;
	/**
	 * clean files on terminate
	 */
	boolean                         cleanOnTerminate;
	/**
	 * Produce a new encoder (dataFactory changes...)
	 */
	boolean                         needNewEncoder;
	/**
	 * Chunked false by default
	 */
	boolean                         isChunked;
	/**
	 * If multipart, this is the boundary for the flobal multipart
	 */
	String                          multipartDataBoundary;
	/**
	 * If multipart, there could be internal multiparts (mixed) to the global multipart.
	 * Only one level is allowed.
	 */
	String                          multipartMixedBoundary;
	/**
	 * To check if the header has been finalized
	 */
	boolean                         headerFinalized;
	/**
	 * Does the last non empty chunk already encoded so that next chunk will be empty
	 * (last chunk)
	 */
	boolean                         isLastChunk;
	/**
	 * Last chunk already sent
	 */
	boolean                         isLastChunkSent;
	/**
	 * The current FileUpload that is currently in encode process
	 */
	FileUpload                      currentFileUpload;
	/**
	 * While adding a FileUpload, is the multipart currently in Mixed Mode
	 */
	boolean                         duringMixedMode;
	/**
	 * Global Body size
	 */
	long                            globalBodySize;
	/**
	 * Global Transfer progress
	 */
	long                            globalProgress;
	/**
	 * Iterator to be used when encoding will be called chunk after chunk
	 */
	ListIterator<InterfaceHttpData> iterator;
	/**
	 * The ByteBuf currently used by the encoder
	 */
	ByteBuf                         currentBuffer;
	/**
	 * The current InterfaceHttpData to encode (used if more chunks are available)
	 */
	InterfaceHttpData               currentData;
	/**
	 * If not multipart, does the currentBuffer stands for the Key or for the Value
	 */
	boolean isKey = true;

	Charset newCharset;
	boolean newMultipart;
	EncoderMode newMode;

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
		if (factory == null) {
			throw new NullPointerException("factory");
		}
		if (request == null) {
			throw new NullPointerException("request");
		}
		if (charset == null) {
			throw new NullPointerException("charset");
		}
		HttpMethod method = request.method();
		if (!(method.equals(HttpMethod.POST) || method.equals(HttpMethod.PUT) || method.equals(
				HttpMethod.PATCH) || method.equals(HttpMethod.OPTIONS))) {
			throw new ErrorDataEncoderException("Cannot create a Encoder if not a POST");
		}
		this.request = request;
		this.charset = charset;
		this.newCharset = charset;
		this.factory = factory;
		this.progressFlux = DirectProcessor.create();
		this.cleanOnTerminate = true;
		this.newMode = encoderMode;
		this.newMultipart = multipart;

		// Fill default values
		bodyListDatas = new ArrayList<>();
		// default mode
		isLastChunk = false;
		isLastChunkSent = false;
		isMultipart = multipart;
		multipartHttpDatas = new ArrayList<>();
		this.encoderMode = encoderMode;
		if (isMultipart) {
			initDataMultipart();
		}
	}

	@Override
	public void close() throws Exception {
		// NO since the user can want to reuse (broadcast for instance)
		// cleanFiles();
	}

	@Override
	public boolean isEndOfInput() throws Exception {
		return isLastChunkSent;
	}

	@Override
	public long length() {
		return isMultipart ? globalBodySize : globalBodySize - 1;
	}

	@Override
	public long progress() {
		return globalProgress;
	}

	@Deprecated
	@Override
	public HttpContent readChunk(ChannelHandlerContext ctx) throws Exception {
		return readChunk(ctx.alloc());
	}

	/**
	 * Returns the next available HttpChunk. The caller is responsible to test if this
	 * chunk is the last one (isLast()), in order to stop calling this getMethod.
	 *
	 * @return the next available HttpChunk
	 *
	 * @throws ErrorDataEncoderException if the encoding is in error
	 */
	@Override
	public HttpContent readChunk(ByteBufAllocator allocator) throws Exception {
		if (isLastChunkSent) {
			progressFlux.onComplete();
			return null;
		}
		else {
			HttpContent nextChunk = nextChunk();
			globalProgress += nextChunk.content()
			                           .readableBytes();
			progressFlux.onNext(progress());
			if (isLastChunkSent) {
				progressFlux.onComplete();
			}
			return nextChunk;
		}
	}

	/**
	 * Add a simple attribute in the body as Name=Value
	 *
	 * @param name name of the parameter
	 * @param value the value of the parameter
	 *
	 * @throws NullPointerException      for name
	 * @throws ErrorDataEncoderException if the encoding is in error or if the finalize
	 *                                   were already done
	 */
	void addBodyAttribute(String name, String value) throws ErrorDataEncoderException {
		if (name == null) {
			throw new NullPointerException("name");
		}
		String svalue = value;
		if (value == null) {
			svalue = "";
		}
		Attribute data = factory.createAttribute(request, name, svalue);
		addBodyHttpData(data);
	}

	/**
	 * Add a file as a FileUpload
	 *
	 * @param name the name of the parameter
	 * @param file the file to be uploaded (if not Multipart mode, only the filename will
	 * be included)
	 * @param contentType the associated contentType for the File
	 * @param isText True if this file should be transmitted in Text format (else binary)
	 *
	 * @throws NullPointerException      for name and file
	 * @throws ErrorDataEncoderException if the encoding is in error or if the finalize
	 *                                   were already done
	 */
	void addBodyFileUpload(String name, File file, String contentType, boolean isText)
			throws ErrorDataEncoderException {
		if (name == null) {
			throw new NullPointerException("name");
		}
		if (file == null) {
			throw new NullPointerException("file");
		}
		String scontentType = contentType;
		String contentTransferEncoding = null;
		if (contentType == null) {
			if (isText) {
				scontentType = DEFAULT_TEXT_CONTENT_TYPE;
			}
			else {
				scontentType = DEFAULT_BINARY_CONTENT_TYPE;
			}
		}
		if (!isText) {
			contentTransferEncoding = DEFAULT_TRANSFER_ENCODING;
		}
		FileUpload fileUpload = factory.createFileUpload(request,
				name,
				file.getName(),
				scontentType,
				contentTransferEncoding,
				null,
				file.length());
		try {
			fileUpload.setContent(file);
		}
		catch (IOException e) {
			throw new ErrorDataEncoderException(e);
		}
		addBodyHttpData(fileUpload);
	}

	/**
	 * Add a series of Files associated with one File parameter
	 *
	 * @param name the name of the parameter
	 * @param file the array of files
	 * @param contentType the array of content Types associated with each file
	 * @param isText the array of isText attribute (False meaning binary mode) for each
	 * file
	 *
	 * @throws NullPointerException      also throws if array have different sizes
	 * @throws ErrorDataEncoderException if the encoding is in error or if the finalize
	 *                                   were already done
	 */
	void addBodyFileUploads(String name,
			File[] file,
			String[] contentType,
			boolean[] isText) throws ErrorDataEncoderException {
		if (file.length != contentType.length && file.length != isText.length) {
			throw new NullPointerException("Different array length");
		}
		for (int i = 0; i < file.length; i++) {
			addBodyFileUpload(name, file[i], contentType[i], isText[i]);
		}
	}

	/**
	 * Add the InterfaceHttpData to the Body list
	 *
	 * @throws NullPointerException      for data
	 * @throws ErrorDataEncoderException if the encoding is in error or if the finalize
	 *                                   were already done
	 */
	void addBodyHttpData(InterfaceHttpData data) throws ErrorDataEncoderException {
		if (headerFinalized) {
			throw new ErrorDataEncoderException("Cannot add value once finalized");
		}
		if (data == null) {
			throw new NullPointerException("data");
		}
		bodyListDatas.add(data);
		if (!isMultipart) {
			if (data instanceof Attribute) {
				Attribute attribute = (Attribute) data;
				try {
					// name=value& with encoded name and attribute
					String key = encodeAttribute(attribute.getName(), charset);
					String value = encodeAttribute(attribute.getValue(), charset);
					Attribute newattribute = factory.createAttribute(request, key, value);
					multipartHttpDatas.add(newattribute);
					globalBodySize += newattribute.getName()
					                              .length() + 1 + newattribute.length() + 1;
				}
				catch (IOException e) {
					throw new ErrorDataEncoderException(e);
				}
			}
			else if (data instanceof FileUpload) {
				// since not Multipart, only name=filename => Attribute
				FileUpload fileUpload = (FileUpload) data;
				// name=filename& with encoded name and filename
				String key = encodeAttribute(fileUpload.getName(), charset);
				String value = encodeAttribute(fileUpload.getFilename(), charset);
				Attribute newattribute = factory.createAttribute(request, key, value);
				multipartHttpDatas.add(newattribute);
				globalBodySize += newattribute.getName()
				                              .length() + 1 + newattribute.length() + 1;
			}
			return;
		}
		/*
		 * Logic:
         * if not Attribute:
         *      add Data to body list
         *      if (duringMixedMode)
         *          add endmixedmultipart delimiter
         *          currentFileUpload = null
         *          duringMixedMode = false;
         *      add multipart delimiter, multipart body header and Data to multipart list
         *      reset currentFileUpload, duringMixedMode
         * if FileUpload: take care of multiple file for one field => mixed mode
         *      if (duringMixeMode)
         *          if (currentFileUpload.name == data.name)
         *              add mixedmultipart delimiter, mixedmultipart body header and Data to multipart list
         *          else
         *              add endmixedmultipart delimiter, multipart body header and Data to multipart list
         *              currentFileUpload = data
         *              duringMixedMode = false;
         *      else
         *          if (currentFileUpload.name == data.name)
         *              change multipart body header of previous file into multipart list to
         *                      mixedmultipart start, mixedmultipart body header
         *              add mixedmultipart delimiter, mixedmultipart body header and Data to multipart list
         *              duringMixedMode = true
         *          else
         *              add multipart delimiter, multipart body header and Data to multipart list
         *              currentFileUpload = data
         *              duringMixedMode = false;
         * Do not add last delimiter! Could be:
         * if duringmixedmode: endmixedmultipart + endmultipart
         * else only endmultipart
         */
		if (data instanceof Attribute) {
			if (duringMixedMode) {
				InternalAttribute internal = new InternalAttribute(charset);
				internal.addValue("\r\n--" + multipartMixedBoundary + "--");
				multipartHttpDatas.add(internal);
				multipartMixedBoundary = null;
				currentFileUpload = null;
				duringMixedMode = false;
			}
			InternalAttribute internal = new InternalAttribute(charset);
			if (!multipartHttpDatas.isEmpty()) {
				// previously a data field so CRLF
				internal.addValue("\r\n");
			}
			internal.addValue("--" + multipartDataBoundary + "\r\n");
			// content-disposition: form-data; name="field1"
			Attribute attribute = (Attribute) data;
			internal.addValue(HttpHeaderNames.CONTENT_DISPOSITION + ": " + HttpHeaderValues.FORM_DATA + "; " + HttpHeaderValues.NAME + "=\"" + attribute.getName() + "\"\r\n");
			// Add Content-Length: xxx
			internal.addValue(HttpHeaderNames.CONTENT_LENGTH + ": " + attribute.length() + "\r\n");
			Charset localcharset = attribute.getCharset();
			if (localcharset != null) {
				// Content-Type: text/plain; charset=charset
				internal.addValue(HttpHeaderNames.CONTENT_TYPE + ": " + DEFAULT_TEXT_CONTENT_TYPE + "; " + HttpHeaderValues.CHARSET + '=' + localcharset.name() + "\r\n");
			}
			// CRLF between body header and data
			internal.addValue("\r\n");
			multipartHttpDatas.add(internal);
			multipartHttpDatas.add(data);
			globalBodySize += attribute.length() + internal.size();
		}
		else if (data instanceof FileUpload) {
			FileUpload fileUpload = (FileUpload) data;
			InternalAttribute internal = new InternalAttribute(charset);
			if (!multipartHttpDatas.isEmpty()) {
				// previously a data field so CRLF
				internal.addValue("\r\n");
			}
			boolean localMixed;
			if (duringMixedMode) {
				if (currentFileUpload != null && currentFileUpload.getName()
				                                                  .equals(fileUpload.getName())) {
					// continue a mixed mode

					localMixed = true;
				}
				else {
					// end a mixed mode

					// add endmixedmultipart delimiter, multipart body header
					// and
					// Data to multipart list
					internal.addValue("--" + multipartMixedBoundary + "--");
					multipartHttpDatas.add(internal);
					multipartMixedBoundary = null;
					// start a new one (could be replaced if mixed start again
					// from here
					internal = new InternalAttribute(charset);
					internal.addValue("\r\n");
					localMixed = false;
					// new currentFileUpload and no more in Mixed mode
					currentFileUpload = fileUpload;
					duringMixedMode = false;
				}
			}
			else {
				if (encoderMode != EncoderMode.HTML5 && currentFileUpload != null && currentFileUpload.getName()
				                                                                                      .equals(fileUpload.getName())) {
					// create a new mixed mode (from previous file)

					// change multipart body header of previous file into
					// multipart list to
					// mixedmultipart start, mixedmultipart body header

					// change Internal (size()-2 position in multipartHttpDatas)
					// from (line starting with *)
					// --AaB03x
					// * Content-Disposition: form-data; name="files";
					// filename="file1.txt"
					// Content-Type: text/plain
					// to (lines starting with *)
					// --AaB03x
					// * Content-Disposition: form-data; name="files"
					// * Content-Type: multipart/mixed; boundary=BbC04y
					// *
					// * --BbC04y
					// * Content-Disposition: attachment; filename="file1.txt"
					// Content-Type: text/plain
					initMixedMultipart();
					InternalAttribute pastAttribute =
							(InternalAttribute) multipartHttpDatas.get(multipartHttpDatas.size() - 2);
					// remove past size
					globalBodySize -= pastAttribute.size();
					StringBuilder replacement =
							new StringBuilder(139 + multipartDataBoundary.length() + multipartMixedBoundary.length() * 2 + fileUpload.getFilename()
							                                                                                                         .length() + fileUpload.getName()
							                                                                                                                               .length())

									.append("--")
									.append(multipartDataBoundary)
									.append("\r\n")

									.append(HttpHeaderNames.CONTENT_DISPOSITION)
									.append(": ")
									.append(HttpHeaderValues.FORM_DATA)
									.append("; ")
									.append(HttpHeaderValues.NAME)
									.append("=\"")
									.append(fileUpload.getName())
									.append("\"\r\n")

									.append(HttpHeaderNames.CONTENT_TYPE)
									.append(": ")
									.append(HttpHeaderValues.MULTIPART_MIXED)
									.append("; ")
									.append(HttpHeaderValues.BOUNDARY)
									.append('=')
									.append(multipartMixedBoundary)
									.append("\r\n\r\n")

									.append("--")
									.append(multipartMixedBoundary)
									.append("\r\n")

									.append(HttpHeaderNames.CONTENT_DISPOSITION)
									.append(": ")
									.append(HttpHeaderValues.ATTACHMENT)
									.append("; ");

					if(!fileUpload.getFilename().isEmpty()) {
									replacement.append(HttpHeaderValues.FILENAME).append("=\"")
									                                  .append(fileUpload.getFilename());
					}

					replacement.append("\"\r\n");

					pastAttribute.setValue(replacement.toString(), 1);
					pastAttribute.setValue("", 2);

					// update past size
					globalBodySize += pastAttribute.size();

					// now continue
					// add mixedmultipart delimiter, mixedmultipart body header
					// and
					// Data to multipart list
					localMixed = true;
					duringMixedMode = true;
				}
				else {
					// a simple new multipart
					// add multipart delimiter, multipart body header and Data
					// to multipart list
					localMixed = false;
					currentFileUpload = fileUpload;
					duringMixedMode = false;
				}
			}

			if (localMixed) {
				// add mixedmultipart delimiter, mixedmultipart body header and
				// Data to multipart list
				internal.addValue("--" + multipartMixedBoundary + "\r\n");
				// Content-Disposition: attachment; filename="file1.txt"
				if(!fileUpload.getFilename().isEmpty()) {
					internal.addValue(HttpHeaderNames.CONTENT_DISPOSITION + ": " + HttpHeaderValues.ATTACHMENT + "; " + HttpHeaderValues.FILENAME + "=\"" + fileUpload.getFilename() + "\"\r\n");
				}
				else{
					internal.addValue(HttpHeaderNames.CONTENT_DISPOSITION + ": " + HttpHeaderValues.ATTACHMENT + ";\r\n");
				}
			}
			else {
				internal.addValue("--" + multipartDataBoundary + "\r\n");
				// Content-Disposition: form-data; name="files";
				// filename="file1.txt"
				if(!fileUpload.getFilename().isEmpty()) {
					internal.addValue(HttpHeaderNames.CONTENT_DISPOSITION + ": " + HttpHeaderValues.FORM_DATA + "; " + HttpHeaderValues.NAME + "=\"" + fileUpload.getName() + "\"; " + HttpHeaderValues.FILENAME + "=\"" + fileUpload.getFilename() + "\"\r\n");
				}
				else{
					internal.addValue(HttpHeaderNames.CONTENT_DISPOSITION + ": " + HttpHeaderValues.FORM_DATA + "; " + HttpHeaderValues.NAME + "=\"" + fileUpload.getName() + "\";\r\n");
				}
			}
			// Add Content-Length: xxx
			internal.addValue(HttpHeaderNames.CONTENT_LENGTH + ": " + fileUpload.length() + "\r\n");
			// Content-Type: image/gif
			// Content-Type: text/plain; charset=ISO-8859-1
			// Content-Transfer-Encoding: binary
			internal.addValue(HttpHeaderNames.CONTENT_TYPE + ": " + fileUpload.getContentType());
			String contentTransferEncoding = fileUpload.getContentTransferEncoding();
			if (contentTransferEncoding != null && contentTransferEncoding.equals(
					DEFAULT_TRANSFER_ENCODING)) {
				internal.addValue("\r\n" + HttpHeaderNames.CONTENT_TRANSFER_ENCODING + ": " + DEFAULT_BINARY_CONTENT_TYPE + "\r\n\r\n");
			}
			else if (fileUpload.getCharset() != null) {
				internal.addValue("; " + HttpHeaderValues.CHARSET + '=' + fileUpload.getCharset()
				                                                                    .name() + "\r\n\r\n");
			}
			else {
				internal.addValue("\r\n\r\n");
			}
			multipartHttpDatas.add(internal);
			multipartHttpDatas.add(data);
			globalBodySize += fileUpload.length() + internal.size();
		}
	}

	/**
	 * Clean all HttpDatas (on Disk) for the current request.
	 */
	void cleanFiles() {
		factory.cleanRequestHttpData(request);
	}

	/**
	 * Encode one attribute
	 *
	 * @return the encoded attribute
	 *
	 * @throws ErrorDataEncoderException if the encoding is in error
	 */
	String encodeAttribute(String s, Charset charset) throws ErrorDataEncoderException {
		if (s == null) {
			return "";
		}
		try {
			String encoded = URLEncoder.encode(s, charset.name());
			if (encoderMode == EncoderMode.RFC3986) {
				for (Map.Entry<Pattern, String> entry : percentEncodings.entrySet()) {
					String replacement = entry.getValue();
					encoded = entry.getKey()
					               .matcher(encoded)
					               .replaceAll(replacement);
				}
			}
			return encoded;
		}
		catch (UnsupportedEncodingException e) {
			throw new ErrorDataEncoderException(charset.name(), e);
		}
	}

	/**
	 * From the current context (currentBuffer and currentData), returns the next
	 * HttpChunk (if possible) trying to get sizeleft bytes more into the currentBuffer.
	 * This is the Multipart version.
	 *
	 * @param sizeleft the number of bytes to try to get from currentData
	 *
	 * @return the next HttpChunk or null if not enough bytes were found
	 *
	 * @throws ErrorDataEncoderException if the encoding is in error
	 */
	HttpContent encodeNextChunkMultipart(int sizeleft) throws ErrorDataEncoderException {
		if (currentData == null) {
			return null;
		}
		ByteBuf buffer;
		if (currentData instanceof InternalAttribute) {
			buffer = ((InternalAttribute) currentData).toByteBuf();
			currentData = null;
		}
		else {
			if (currentData instanceof Attribute) {
				try {
					buffer = ((Attribute) currentData).getChunk(sizeleft);
				}
				catch (IOException e) {
					throw new ErrorDataEncoderException(e);
				}
			}
			else {
				try {
					buffer = ((HttpData) currentData).getChunk(sizeleft);
				}
				catch (IOException e) {
					throw new ErrorDataEncoderException(e);
				}
			}
			if (buffer.capacity() == 0) {
				// end for current InterfaceHttpData, need more data
				currentData = null;
				return null;
			}
		}
		if (currentBuffer == null) {
			currentBuffer = buffer;
		}
		else {
			currentBuffer = wrappedBuffer(currentBuffer, buffer);
		}
		if (currentBuffer.readableBytes() < chunkSize) {
			currentData = null;
			return null;
		}
		buffer = fillByteBuf();
		return new DefaultHttpContent(buffer);
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
		this.newCharset = Objects.requireNonNull(charset, "charset");
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
	public HttpClientRequest.Form file(String name, InputStream inputStream) {
		file(name, inputStream, null);
		return this;
	}

	@Override
	public HttpClientRequest.Form file(String name,
			String filename,
			File file,
			String contentType) {
		Objects.requireNonNull(name, "name");
		Objects.requireNonNull(file, "file");
		Objects.requireNonNull(filename, "filename");
		String scontentType = contentType;
		if (contentType == null) {
			scontentType = DEFAULT_BINARY_CONTENT_TYPE;
		}
		FileUpload fileUpload = factory.createFileUpload(request,
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
	public HttpClientRequest.Form file(String name,
			String filename,
			InputStream stream,
			String contentType) {
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
					charset,
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
		this.newMode = Objects.requireNonNull(mode, "mode");
		this.needNewEncoder = true;
		return this;
	}

	@Override
	public HttpClientRequest.Form multipart(boolean isMultipart) {
		this.newMultipart = isMultipart;
		this.needNewEncoder = this.isMultipart != isMultipart;
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
	public HttpClientRequest.Form textFile(String name, InputStream stream) {
		textFile(name, stream, null);
		return this;
	}

	@Override
	public HttpClientRequest.Form textFile(String name,
			InputStream stream,
			String contentType) {
		Objects.requireNonNull(name, "name");
		Objects.requireNonNull(stream, "stream");
		try {
			String scontentType = contentType;

			if (contentType == null) {
				scontentType = DEFAULT_TEXT_CONTENT_TYPE;
			}

			MemoryFileUpload fileUpload =
					new MemoryFileUpload(name, "", scontentType, null, charset, -1);
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

	final HttpClientFormEncoder applyChanges(HttpRequest request)
			throws ErrorDataEncoderException {
		if (!needNewEncoder) {
			return this;
		}

		HttpClientFormEncoder encoder = new HttpClientFormEncoder(factory,
				request,
				newMultipart,
				newCharset,
				newMode);

		encoder.setBodyHttpDatas(getBodyListAttributes());

		return encoder;
	}

	/**
	 * From the current context (currentBuffer and currentData), returns the next
	 * HttpChunk (if possible) trying to get sizeleft bytes more into the currentBuffer.
	 * This is the UrlEncoded version.
	 *
	 * @param sizeleft the number of bytes to try to get from currentData
	 *
	 * @return the next HttpChunk or null if not enough bytes were found
	 *
	 * @throws ErrorDataEncoderException if the encoding is in error
	 */
	HttpContent encodeNextChunkUrlEncoded(int sizeleft) throws ErrorDataEncoderException {
		if (currentData == null) {
			return null;
		}
		int size = sizeleft;
		ByteBuf buffer;

		// Set name=
		if (isKey) {
			String key = currentData.getName();
			buffer = wrappedBuffer(key.getBytes());
			isKey = false;
			if (currentBuffer == null) {
				currentBuffer = wrappedBuffer(buffer, wrappedBuffer("=".getBytes()));
				// continue
				size -= buffer.readableBytes() + 1;
			}
			else {
				currentBuffer = wrappedBuffer(currentBuffer,
						buffer,
						wrappedBuffer("=".getBytes()));
				// continue
				size -= buffer.readableBytes() + 1;
			}
			if (currentBuffer.readableBytes() >= chunkSize) {
				buffer = fillByteBuf();
				return new DefaultHttpContent(buffer);
			}
		}

		// Put value into buffer
		try {
			buffer = ((HttpData) currentData).getChunk(size);
		}
		catch (IOException e) {
			throw new ErrorDataEncoderException(e);
		}

		// Figure out delimiter
		ByteBuf delimiter = null;
		if (buffer.readableBytes() < size) {
			isKey = true;
			delimiter = iterator.hasNext() ? wrappedBuffer("&".getBytes()) : null;
		}

		// End for current InterfaceHttpData, need potentially more data
		if (buffer.capacity() == 0) {
			currentData = null;
			if (currentBuffer == null) {
				currentBuffer = delimiter;
			}
			else {
				if (delimiter != null) {
					currentBuffer = wrappedBuffer(currentBuffer, delimiter);
				}
				else {
					currentBuffer = wrappedBuffer(currentBuffer);
				}
			}
			if (currentBuffer.readableBytes() >= chunkSize) {
				buffer = fillByteBuf();
				return new DefaultHttpContent(buffer);
			}
			return null;
		}

		// Put it all together: name=value&
		if (currentBuffer == null) {
			if (delimiter != null) {
				currentBuffer = wrappedBuffer(buffer, delimiter);
			}
			else {
				currentBuffer = buffer;
			}
		}
		else {
			if (delimiter != null) {
				currentBuffer = wrappedBuffer(currentBuffer, buffer, delimiter);
			}
			else {
				currentBuffer = wrappedBuffer(currentBuffer, buffer);
			}
		}

		// end for current InterfaceHttpData, need more data
		if (currentBuffer.readableBytes() < chunkSize) {
			currentData = null;
			isKey = true;
			return null;
		}

		buffer = fillByteBuf();
		return new DefaultHttpContent(buffer);
	}

	/**
	 * @return the next ByteBuf to send as a HttpChunk and modifying currentBuffer
	 * accordingly
	 */
	ByteBuf fillByteBuf() {
		int length = currentBuffer.readableBytes();
		if (length > chunkSize) {
			ByteBuf slice = currentBuffer.slice(currentBuffer.readerIndex(), chunkSize);
			currentBuffer.skipBytes(chunkSize);
			return slice;
		}
		else {
			// to continue
			ByteBuf slice = currentBuffer;
			currentBuffer = null;
			return slice;
		}
	}

	/**
	 * Finalize the request by preparing the Header in the request and returns the request
	 * ready to be sent.<br> Once finalized, no data must be added.<br> If the request
	 * does not need chunk (isChunked() == false), this request is the only object to send
	 * to the remote server.
	 *
	 * @return the request object (chunked or not according to size of body)
	 *
	 * @throws ErrorDataEncoderException if the encoding is in error or if the finalize
	 *                                   were already done
	 */
	HttpRequest finalizeRequest() throws ErrorDataEncoderException {
		// Finalize the multipartHttpDatas
		if (!headerFinalized) {
			if (isMultipart) {
				InternalAttribute internal = new InternalAttribute(charset);
				if (duringMixedMode) {
					internal.addValue("\r\n--" + multipartMixedBoundary + "--");
				}
				internal.addValue("\r\n--" + multipartDataBoundary + "--\r\n");
				multipartHttpDatas.add(internal);
				multipartMixedBoundary = null;
				currentFileUpload = null;
				duringMixedMode = false;
				globalBodySize += internal.size();
			}
			headerFinalized = true;
		}
		else {
			throw new ErrorDataEncoderException("Header already encoded");
		}

		HttpHeaders headers = request.headers();
		List<String> contentTypes = headers.getAll(HttpHeaderNames.CONTENT_TYPE);
		List<String> transferEncoding = headers.getAll(HttpHeaderNames.TRANSFER_ENCODING);
		if (contentTypes != null) {
			headers.remove(HttpHeaderNames.CONTENT_TYPE);
			for (String contentType : contentTypes) {
				// "multipart/form-data; boundary=--89421926422648"
				String lowercased = contentType.toLowerCase();
				if (lowercased.startsWith(HttpHeaderValues.MULTIPART_FORM_DATA.toString()) || lowercased.startsWith(
						HttpHeaderValues.APPLICATION_X_WWW_FORM_URLENCODED.toString())) {
					// ignore
				}
				else {
					headers.add(HttpHeaderNames.CONTENT_TYPE, contentType);
				}
			}
		}
		if (isMultipart) {
			String value =
					HttpHeaderValues.MULTIPART_FORM_DATA + "; " + HttpHeaderValues.BOUNDARY + '=' + multipartDataBoundary;
			headers.add(HttpHeaderNames.CONTENT_TYPE, value);
		}
		else {
			// Not multipart
			headers.add(HttpHeaderNames.CONTENT_TYPE,
					HttpHeaderValues.APPLICATION_X_WWW_FORM_URLENCODED);
		}
		// Now consider size for chunk or not
		long realSize = globalBodySize;
		if (isMultipart) {
			iterator = multipartHttpDatas.listIterator();
		}
		else {
			realSize -= 1; // last '&' removed
			iterator = multipartHttpDatas.listIterator();
		}
		headers.set(HttpHeaderNames.CONTENT_LENGTH, String.valueOf(realSize));
		if (realSize > chunkSize || isMultipart) {
			isChunked = true;
			if (transferEncoding != null) {
				headers.remove(HttpHeaderNames.TRANSFER_ENCODING);
				for (CharSequence v : transferEncoding) {
					if (HttpHeaderValues.CHUNKED.contentEqualsIgnoreCase(v)) {
						// ignore
					}
					else {
						headers.add(HttpHeaderNames.TRANSFER_ENCODING, v);
					}
				}
			}
			HttpUtil.setTransferEncodingChunked(request, true);

			// wrap to hide the possible content
			return new WrappedHttpRequest(request);
		}
		else {
			// get the only one body and set it to the request
			HttpContent chunk = nextChunk();
			if (request instanceof FullHttpRequest) {
				FullHttpRequest fullRequest = (FullHttpRequest) request;
				ByteBuf chunkContent = chunk.content();
				if (fullRequest.content() != chunkContent) {
					fullRequest.content()
					           .clear()
					           .writeBytes(chunkContent);
					chunkContent.release();
				}
				return fullRequest;
			}
			else {
				return new WrappedFullHttpRequest(request, chunk);
			}
		}
	}

	/**
	 * This getMethod returns a List of all InterfaceHttpData from body part.<br>
	 *
	 * @return the list of InterfaceHttpData from Body part
	 */
	List<InterfaceHttpData> getBodyListAttributes() {
		return bodyListDatas;
	}

	/**
	 * Init the delimiter for Global Part (Data).
	 */
	void initDataMultipart() {
		multipartDataBoundary = getNewMultipartDelimiter();
	}

	/**
	 * Init the delimiter for Mixed Part (Mixed).
	 */
	void initMixedMultipart() {
		multipartMixedBoundary = getNewMultipartDelimiter();
	}

	/**
	 * @return True if the request is by Chunk
	 */
	boolean isChunked() {
		return isChunked;
	}

	/**
	 * True if this request is a Multipart request
	 *
	 * @return True if this request is a Multipart request
	 */
	boolean isMultipart() {
		return isMultipart;
	}

	/**
	 * Returns the next available HttpChunk. The caller is responsible to test if this
	 * chunk is the last one (isLast()), in order to stop calling this getMethod.
	 *
	 * @return the next available HttpChunk
	 *
	 * @throws ErrorDataEncoderException if the encoding is in error
	 */
	HttpContent nextChunk() throws ErrorDataEncoderException {
		if (isLastChunk) {
			isLastChunkSent = true;
			return LastHttpContent.EMPTY_LAST_CONTENT;
		}
		ByteBuf buffer;
		int size = chunkSize;
		// first test if previous buffer is not empty
		if (currentBuffer != null) {
			size -= currentBuffer.readableBytes();
		}
		if (size <= 0) {
			// NextChunk from buffer
			buffer = fillByteBuf();
			return new DefaultHttpContent(buffer);
		}
		// size > 0
		if (currentData != null) {
			// continue to read data
			if (isMultipart) {
				HttpContent chunk = encodeNextChunkMultipart(size);
				if (chunk != null) {
					return chunk;
				}
			}
			else {
				HttpContent chunk = encodeNextChunkUrlEncoded(size);
				if (chunk != null) {
					// NextChunk Url from currentData
					return chunk;
				}
			}
			size = chunkSize - currentBuffer.readableBytes();
		}
		if (!iterator.hasNext()) {
			isLastChunk = true;
			// NextChunk as last non empty from buffer
			buffer = currentBuffer;
			currentBuffer = null;
			return new DefaultHttpContent(buffer);
		}
		while (size > 0 && iterator.hasNext()) {
			currentData = iterator.next();
			HttpContent chunk;
			if (isMultipart) {
				chunk = encodeNextChunkMultipart(size);
			}
			else {
				chunk = encodeNextChunkUrlEncoded(size);
			}
			if (chunk == null) {
				// not enough
				size = chunkSize - currentBuffer.readableBytes();
				continue;
			}
			// NextChunk from data
			return chunk;
		}
		// end since no more data
		isLastChunk = true;
		if (currentBuffer == null) {
			isLastChunkSent = true;
			// LastChunk with no more data
			return LastHttpContent.EMPTY_LAST_CONTENT;
		}
		// Previous LastChunk with no more data
		buffer = currentBuffer;
		currentBuffer = null;
		return new DefaultHttpContent(buffer);
	}

	/**
	 * Set the Body HttpDatas list
	 *
	 * @throws NullPointerException      for datas
	 * @throws ErrorDataEncoderException if the encoding is in error or if the finalize
	 *                                   were already done
	 */
	void setBodyHttpDatas(List<InterfaceHttpData> datas)
			throws ErrorDataEncoderException {
		if (datas == null) {
			throw new NullPointerException("datas");
		}
		globalBodySize = 0;
		bodyListDatas.clear();
		currentFileUpload = null;
		duringMixedMode = false;
		multipartHttpDatas.clear();
		for (InterfaceHttpData data : datas) {
			addBodyHttpData(data);
		}
	}

	static class WrappedHttpRequest implements HttpRequest {

		final HttpRequest request;

		public WrappedHttpRequest(HttpRequest request) {
			this.request = request;
		}

		@Override
		public DecoderResult decoderResult() {
			return request.decoderResult();
		}

		@Override
		@Deprecated
		public DecoderResult getDecoderResult() {
			return request.getDecoderResult();
		}

		@Override
		public void setDecoderResult(DecoderResult result) {
			request.setDecoderResult(result);
		}

		@Override
		public HttpMethod getMethod() {
			return request.method();
		}

		@Override
		public HttpVersion getProtocolVersion() {
			return request.protocolVersion();
		}

		@Override
		public String getUri() {
			return request.uri();
		}

		@Override
		public HttpHeaders headers() {
			return request.headers();
		}

		@Override
		public HttpMethod method() {
			return request.method();
		}

		@Override
		public HttpVersion protocolVersion() {
			return request.protocolVersion();
		}

		@Override
		public HttpRequest setMethod(HttpMethod method) {
			request.setMethod(method);
			return this;
		}

		@Override
		public HttpRequest setProtocolVersion(HttpVersion version) {
			request.setProtocolVersion(version);
			return this;
		}

		@Override
		public HttpRequest setUri(String uri) {
			request.setUri(uri);
			return this;
		}

		@Override
		public String uri() {
			return request.uri();
		}
	}

	static final class WrappedFullHttpRequest extends WrappedHttpRequest
			implements FullHttpRequest {

		final HttpContent content;

		WrappedFullHttpRequest(HttpRequest request, HttpContent content) {
			super(request);
			this.content = content;
		}

		@Override
		public ByteBuf content() {
			return content.content();
		}

		@Override
		public FullHttpRequest copy() {
			return replace(content().copy());
		}

		@Override
		public FullHttpRequest duplicate() {
			return replace(content().duplicate());
		}

		@Override
		public int refCnt() {
			return content.refCnt();
		}

		@Override
		public boolean release() {
			return content.release();
		}

		@Override
		public boolean release(int decrement) {
			return content.release(decrement);
		}

		@Override
		public FullHttpRequest replace(ByteBuf content) {
			DefaultFullHttpRequest duplicate =
					new DefaultFullHttpRequest(protocolVersion(),
							method(),
							uri(),
							content);
			duplicate.headers()
			         .set(headers());
			duplicate.trailingHeaders()
			         .set(trailingHeaders());
			return duplicate;
		}

		@Override
		public FullHttpRequest retain(int increment) {
			content.retain(increment);
			return this;
		}

		@Override
		public FullHttpRequest retain() {
			content.retain();
			return this;
		}

		@Override
		public FullHttpRequest retainedDuplicate() {
			return replace(content().retainedDuplicate());
		}

		@Override
		public FullHttpRequest setMethod(HttpMethod method) {
			super.setMethod(method);
			return this;
		}

		@Override
		public FullHttpRequest setProtocolVersion(HttpVersion version) {
			super.setProtocolVersion(version);
			return this;
		}

		@Override
		public FullHttpRequest setUri(String uri) {
			super.setUri(uri);
			return this;
		}

		@Override
		public FullHttpRequest touch() {
			content.touch();
			return this;
		}

		@Override
		public FullHttpRequest touch(Object hint) {
			content.touch(hint);
			return this;
		}

		@Override
		public HttpHeaders trailingHeaders() {
			if (content instanceof LastHttpContent) {
				return ((LastHttpContent) content).trailingHeaders();
			}
			else {
				return EmptyHttpHeaders.INSTANCE;
			}
		}
	}

	/**
	 * This Attribute is only for Encoder use to insert special command between object if
	 * needed (like Multipart Mixed mode)
	 */
	static final class InternalAttribute extends AbstractReferenceCounted
			implements InterfaceHttpData {

		final List<ByteBuf> value = new ArrayList<>();
		final Charset charset;
		int size;

		InternalAttribute(Charset charset) {
			this.charset = charset;
		}

		@Override
		public int compareTo(InterfaceHttpData o) {
			if (!(o instanceof InternalAttribute)) {
				throw new ClassCastException("Cannot compare " + getHttpDataType() + " with " + o.getHttpDataType());
			}
			return compareTo((InternalAttribute) o);
		}

		@Override
		public boolean equals(Object o) {
			if (!(o instanceof InternalAttribute)) {
				return false;
			}
			InternalAttribute attribute = (InternalAttribute) o;
			return getName().equalsIgnoreCase(attribute.getName());
		}

		@Override
		public HttpDataType getHttpDataType() {
			return HttpDataType.InternalAttribute;
		}

		@Override
		public String getName() {
			return "InternalAttribute";
		}

		@Override
		public int hashCode() {
			return getName().hashCode();
		}

		@Override
		public InterfaceHttpData retain() {
			for (ByteBuf buf : value) {
				buf.retain();
			}
			return this;
		}

		@Override
		public InterfaceHttpData retain(int increment) {
			for (ByteBuf buf : value) {
				buf.retain(increment);
			}
			return this;
		}

		@Override
		public String toString() {
			StringBuilder result = new StringBuilder();
			for (ByteBuf elt : value) {
				result.append(elt.toString(charset));
			}
			return result.toString();
		}

		@Override
		public InterfaceHttpData touch() {
			for (ByteBuf buf : value) {
				buf.touch();
			}
			return this;
		}

		@Override
		public InterfaceHttpData touch(Object hint) {
			for (ByteBuf buf : value) {
				buf.touch(hint);
			}
			return this;
		}

		@Override
		protected void deallocate() {
			// Do nothing
		}

		void addValue(String value) {
			if (value == null) {
				throw new NullPointerException("value");
			}
			ByteBuf buf = Unpooled.copiedBuffer(value, charset);
			this.value.add(buf);
			size += buf.readableBytes();
		}

		void addValue(String value, int rank) {
			if (value == null) {
				throw new NullPointerException("value");
			}
			ByteBuf buf = Unpooled.copiedBuffer(value, charset);
			this.value.add(rank, buf);
			size += buf.readableBytes();
		}

		int compareTo(InternalAttribute o) {
			return getName().compareToIgnoreCase(o.getName());
		}

		void setValue(String value, int rank) {
			if (value == null) {
				throw new NullPointerException("value");
			}
			ByteBuf buf = Unpooled.copiedBuffer(value, charset);
			ByteBuf old = this.value.set(rank, buf);
			if (old != null) {
				size -= old.readableBytes();
				old.release();
			}
			size += buf.readableBytes();
		}

		int size() {
			return size;
		}

		ByteBuf toByteBuf() {
			return Unpooled.compositeBuffer()
			               .addComponents(value)
			               .writerIndex(size())
			               .readerIndex(0);
		}
	}

	static final Map<Pattern, String> percentEncodings            = new HashMap<>();
	static final String               DEFAULT_BINARY_CONTENT_TYPE =
			"application/octet-stream";
	static final String               DEFAULT_TRANSFER_ENCODING   = "binary";
	static final String               DEFAULT_TEXT_CONTENT_TYPE   = "text/plain";
	static final int                  chunkSize                   = 8096;

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

	/**
	 * @return a newly generated Delimiter (either for DATA or MIXED)
	 */
	static String getNewMultipartDelimiter() {
		// construct a generated delimiter
		return Long.toHexString(ThreadLocalRandom.current()
		                                         .nextLong())
		           .toLowerCase();
	}
}
