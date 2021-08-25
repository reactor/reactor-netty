/*
 * Copyright (c) 2021 VMware, Inc. or its affiliates, All Rights Reserved.
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
package reactor.netty.http.server;

import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.multipart.Attribute;
import io.netty.handler.codec.http.multipart.DefaultHttpDataFactory;
import io.netty.handler.codec.http.multipart.FileUpload;
import io.netty.handler.codec.http.multipart.HttpData;
import io.netty.handler.codec.http.multipart.HttpDataFactory;
import io.netty.handler.codec.http.multipart.HttpPostMultipartRequestDecoder;
import io.netty.handler.codec.http.multipart.HttpPostStandardRequestDecoder;
import io.netty.handler.codec.http.multipart.InterfaceHttpData;
import io.netty.handler.codec.http.multipart.InterfaceHttpPostRequestDecoder;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;
import reactor.util.annotation.Nullable;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * A configuration builder to fine tune the HTTP form decoder.
 *
 * @author Violeta Georgieva
 * @since 1.0.11
 */
public final class HttpServerFormDecoderProvider {

	public interface Builder {

		/**
		 * Sets the directory where to store disk {@link Attribute}/{@link FileUpload}. Default to system temp directory.
		 *
		 * @param baseDirectory directory where to store disk {@link Attribute}/{@link FileUpload}
		 * @return {@code this}
		 */
		Builder baseDirectory(Path baseDirectory);

		/**
		 * Set the {@link Charset} for {@link Attribute}/{@link FileUpload}. Default to {@link StandardCharsets#UTF_8}.
		 *
		 * @param charset the charset for {@link Attribute}/{@link FileUpload}
		 * @return {@code this}
		 */
		Builder charset(Charset charset);

		/**
		 * Specifies whether the temporary files should be deleted with the JVM. Default to {@code true}.
		 *
		 * @param deleteOnExit if true the temporary files should be deleted with the JVM, false - otherwise
		 * @return {@code this}
		 */
		Builder deleteOnExit(boolean deleteOnExit);

		/**
		 * Sets the maximum in-memory size per {@link Attribute}/{@link FileUpload} i.e. the data is written
		 * on disk if the size is greater than {@code maxInMemorySize}, else it is in memory.
		 * Default to {@link DefaultHttpDataFactory#MINSIZE}.
		 * <p>Note:
		 * <ul>
		 *     <li>If set to {@code -1} the entire contents is stored in memory</li>
		 *     <li>If set to {@code 0} the entire contents is stored on disk</li>
		 * </ul>
		 *
		 * @param maxInMemorySize the maximum in-memory size
		 * @return {@code this}
		 */
		Builder maxInMemorySize(long maxInMemorySize);

		/**
		 * Set the maximum size per {@link Attribute}/{@link FileUpload}. When the limit is reached, an exception is raised.
		 * Default to {@link DefaultHttpDataFactory#MAXSIZE} - unlimited.
		 * <p>Note: If set to {@code -1} this means no limitation.
		 *
		 * @param maxSize the maximum in-memory size
		 * @return {@code this}
		 */
		Builder maxSize(long maxSize);

		/**
		 * Sets the scheduler to be used for offloading disk operations in the decoding phase.
		 * Default to {@link Schedulers#boundedElastic()}
		 *
		 * @param scheduler the scheduler to be used for offloading disk operations in the decoding phase
		 * @return {@code this}
		 */
		Builder scheduler(Scheduler scheduler);

		/**
		 * When set to {@code true}, the data is streamed directly from the parsed input buffer stream,
		 * which means it is not stored either in memory or file.
		 * When {@code false}, parts are backed by in-memory and/or file storage. Default to {@code false}.
		 * <p><strong>NOTE</strong> that with streaming enabled, the provided {@link Attribute}/{@link FileUpload}
		 * might not be in a complete state i.e. {@link HttpData#isCompleted()} has to be checked.
		 * <p>Also note that enabling this property effectively ignores
		 * {@link #maxInMemorySize(long)},
		 * {@link #baseDirectory(Path)}, and
		 * {@link #scheduler(Scheduler)}.
		 */
		Builder streaming(boolean enable);
	}

	final Path baseDirectory;
	final Charset charset;
	final boolean deleteOnExit;
	final long maxInMemorySize;
	final long maxSize;
	final Scheduler scheduler;
	final boolean streaming;

	HttpServerFormDecoderProvider(Build build) {
		this.baseDirectory = build.baseDirectory;
		this.charset = build.charset;
		this.deleteOnExit = build.deleteOnExit;
		this.maxInMemorySize = !build.streaming ? build.maxInMemorySize : -1;
		this.maxSize = build.maxSize;
		this.scheduler = build.scheduler;
		this.streaming = build.streaming;
	}

	/**
	 * Returns the configured directory where to store disk {@link Attribute}/{@link FileUpload}.
	 *
	 * @return the configured directory where to store disk {@link Attribute}/{@link FileUpload}
	 */
	@Nullable
	public Path baseDirectory() {
		return baseDirectory;
	}

	/**
	 * Returns the configured charset for {@link Attribute}/{@link FileUpload}.
	 *
	 * @return the configured charset for {@link Attribute}/{@link FileUpload}
	 */
	public Charset charset() {
		return charset;
	}

	/**
	 * Returns whether the temporary files should be deleted with the JVM.
	 *
	 * @return whether the temporary files should be deleted with the JVM
	 */
	public boolean deleteOnExit() {
		return deleteOnExit;
	}

	/**
	 * Returns the configured maximum in-memory size per {@link Attribute}/{@link FileUpload}.
	 *
	 * @return the configured maximum in-memory size per {@link Attribute}/{@link FileUpload}
	 */
	public long maxInMemorySize() {
		return maxInMemorySize;
	}

	/**
	 * Returns the configured maximum size per {@link Attribute}/{@link FileUpload}.
	 *
	 * @return the configured maximum size per {@link Attribute}/{@link FileUpload}
	 */
	public long maxSize() {
		return maxSize;
	}

	/**
	 * Returns the configured scheduler to be used for offloading disk operations in the decoding phase.
	 *
	 * @return the configured scheduler to be used for offloading disk operations in the decoding phase
	 */
	public Scheduler scheduler() {
		return scheduler;
	}

	/**
	 * Returns whether the streaming mode is enabled.
	 *
	 * @return whether the streaming mode is enabled
	 */
	public boolean streaming() {
		return streaming;
	}

	@Override
	public boolean equals(Object o) {
		if (this == o) {
			return true;
		}
		if (!(o instanceof HttpServerFormDecoderProvider)) {
			return false;
		}
		HttpServerFormDecoderProvider that = (HttpServerFormDecoderProvider) o;
		return deleteOnExit == that.deleteOnExit &&
				maxInMemorySize == that.maxInMemorySize &&
				maxSize == that.maxSize &&
				streaming == that.streaming &&
				Objects.equals(baseDirectory, that.baseDirectory) &&
				charset.equals(that.charset) &&
				scheduler.equals(that.scheduler);
	}

	@Override
	public int hashCode() {
		return Objects.hash(baseDirectory, charset, deleteOnExit, maxInMemorySize, maxSize, scheduler, streaming);
	}

	ReactorNettyHttpPostRequestDecoder newHttpPostRequestDecoder(HttpRequest request, boolean isMultipart) {
		DefaultHttpDataFactory factory = maxInMemorySize > 0 ?
				new DefaultHttpDataFactory(maxInMemorySize, charset) :
				new DefaultHttpDataFactory(maxInMemorySize == 0, charset);
		factory.setMaxLimit(maxSize);
		factory.setDeleteOnExit(deleteOnExit);
		if (baseDirectory != null) {
			factory.setBaseDir(baseDirectory.toFile().getAbsolutePath());
		}
		return isMultipart ?
				new ReactorNettyHttpPostMultipartRequestDecoder(factory, request) :
				new ReactorNettyHttpPostStandardRequestDecoder(factory, request);
	}

	static final HttpServerFormDecoderProvider DEFAULT_FORM_DECODER_SPEC = new HttpServerFormDecoderProvider.Build().build();

	interface ReactorNettyHttpPostRequestDecoder extends InterfaceHttpPostRequestDecoder {

		void cleanCurrentHttpData(boolean onlyCompleted);

		List<HttpData> currentCompletedHttpData();

		List<HttpData> currentHttpData();
	}

	static final class Build implements Builder {

		static final Charset DEFAULT_CHARSET = StandardCharsets.UTF_8;
		static final boolean DEFAULT_DELETE_ON_EXIT = true;
		static final long DEFAULT_MAX_IN_MEMORY_SIZE = DefaultHttpDataFactory.MINSIZE;
		static final long DEFAULT_MAX_SIZE = DefaultHttpDataFactory.MAXSIZE;
		static final Scheduler DEFAULT_SCHEDULER = Schedulers.boundedElastic();
		static final boolean DEFAULT_STREAMING = false;

		Path baseDirectory;
		Charset charset = DEFAULT_CHARSET;
		boolean deleteOnExit = DEFAULT_DELETE_ON_EXIT;
		long maxInMemorySize = DEFAULT_MAX_IN_MEMORY_SIZE;
		long maxSize = DEFAULT_MAX_SIZE;
		Scheduler scheduler = DEFAULT_SCHEDULER;
		boolean streaming = DEFAULT_STREAMING;

		@Override
		public Builder baseDirectory(Path baseDirectory) {
			this.baseDirectory = Objects.requireNonNull(baseDirectory, "baseDirectory");
			return this;
		}

		@Override
		public Builder charset(Charset charset) {
			this.charset = Objects.requireNonNull(charset, "charset");
			return this;
		}

		@Override
		public Builder deleteOnExit(boolean deleteOnExit) {
			this.deleteOnExit = deleteOnExit;
			return this;
		}

		@Override
		public Builder maxInMemorySize(long maxInMemorySize) {
			if (maxInMemorySize < -1) {
				throw new IllegalArgumentException("Maximum in-memory size must be greater or equal to -1");
			}
			this.maxInMemorySize = maxInMemorySize;
			return this;
		}

		@Override
		public Builder maxSize(long maxSize) {
			if (maxSize < -1) {
				throw new IllegalArgumentException("Maximum size must be be greater or equal to -1");
			}
			this.maxSize = maxSize;
			return this;
		}

		@Override
		public Builder scheduler(Scheduler scheduler) {
			this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
			return this;
		}

		@Override
		public Builder streaming(boolean enable) {
			this.streaming = enable;
			return this;
		}

		HttpServerFormDecoderProvider build() {
			return new HttpServerFormDecoderProvider(this);
		}
	}

	static final class ReactorNettyHttpPostMultipartRequestDecoder extends HttpPostMultipartRequestDecoder
			implements ReactorNettyHttpPostRequestDecoder {

		/**
		 * Current {@link HttpData} from the body (only the completed {@link HttpData}).
		 */
		final List<HttpData> currentCompletedHttpData = new ArrayList<>();

		ReactorNettyHttpPostMultipartRequestDecoder(HttpDataFactory factory, HttpRequest request) {
			super(factory, request);
		}

		@Override
		protected void addHttpData(InterfaceHttpData data) {
			if (data instanceof HttpData) {
				currentCompletedHttpData.add((HttpData) data);
			}
		}

		@Override
		public void cleanCurrentHttpData(boolean cleanAll) {
			for (HttpData data : currentCompletedHttpData) {
				removeHttpDataFromClean(data);
				data.release();
			}
			currentCompletedHttpData.clear();

			if (cleanAll) {
				InterfaceHttpData partial = currentPartialHttpData();
				if (partial instanceof HttpData) {
					((HttpData) partial).delete();
				}
			}
		}

		@Override
		public List<HttpData> currentCompletedHttpData() {
			return currentCompletedHttpData;
		}

		@Override
		public List<HttpData> currentHttpData() {
			InterfaceHttpData partial = currentPartialHttpData();
			if (partial instanceof HttpData) {
				List<HttpData> all = new ArrayList<>(currentCompletedHttpData.size() + 1);
				all.addAll(currentCompletedHttpData);
				all.add((HttpData) partial);
				return all;
			}
			return currentCompletedHttpData;
		}
	}

	static final class ReactorNettyHttpPostStandardRequestDecoder extends HttpPostStandardRequestDecoder
			implements ReactorNettyHttpPostRequestDecoder {

		/**
		 * Current {@link HttpData} from the body (only the completed {@link HttpData}).
		 */
		final List<HttpData> currentCompletedHttpData = new ArrayList<>();

		ReactorNettyHttpPostStandardRequestDecoder(HttpDataFactory factory, HttpRequest request) {
			super(factory, request);
		}

		@Override
		protected void addHttpData(InterfaceHttpData data) {
			if (data instanceof HttpData) {
				currentCompletedHttpData.add((HttpData) data);
			}
		}

		@Override
		public void cleanCurrentHttpData(boolean onlyCompleted) {
			for (HttpData data : currentCompletedHttpData) {
				removeHttpDataFromClean(data);
				data.release();
			}
			currentCompletedHttpData.clear();

			if (!onlyCompleted) {
				InterfaceHttpData partial = currentPartialHttpData();
				if (partial != null) {
					currentPartialHttpData().release();
				}
			}
		}

		@Override
		public List<HttpData> currentCompletedHttpData() {
			return currentCompletedHttpData;
		}

		@Override
		public List<HttpData> currentHttpData() {
			InterfaceHttpData partial = currentPartialHttpData();
			if (partial instanceof HttpData) {
				List<HttpData> all = new ArrayList<>(currentCompletedHttpData.size() + 1);
				all.addAll(currentCompletedHttpData);
				all.add((HttpData) partial);
				return all;
			}
			return currentCompletedHttpData;
		}
	}
}
