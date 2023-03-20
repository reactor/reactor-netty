/*
 * Copyright (c) 2021-2023 VMware, Inc. or its affiliates, All Rights Reserved.
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
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;
import reactor.util.annotation.Nullable;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
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
		 * Sets the directory where to store disk {@link Attribute}/{@link FileUpload}.
		 * Default to generated temp directory.
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
		 * @param maxSize the maximum size allowed for an individual attribute/fileUpload
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
	final long maxInMemorySize;
	final long maxSize;
	final Scheduler scheduler;
	final boolean streaming;

	private volatile Mono<Path> defaultTempDirectory = createDefaultTempDirectory();

	HttpServerFormDecoderProvider(Build build) {
		this.baseDirectory = build.baseDirectory;
		this.charset = build.charset;
		this.maxInMemorySize = !build.streaming ? build.maxInMemorySize : -1;
		this.maxSize = build.maxSize;
		this.scheduler = build.scheduler;
		this.streaming = build.streaming;
	}

	/**
	 * Returns the configured directory where to store disk {@link Attribute}/{@link FileUpload}.
	 *
	 * @return the configured directory where to store disk {@link Attribute}/{@link FileUpload}
	 * @see Builder#baseDirectory(Path)
	 */
	@Nullable
	public Path baseDirectory() {
		return baseDirectory;
	}

	/**
	 * Returns the configured charset for {@link Attribute}/{@link FileUpload}.
	 *
	 * @return the configured charset for {@link Attribute}/{@link FileUpload}
	 * @see Builder#charset(Charset)
	 */
	public Charset charset() {
		return charset;
	}

	/**
	 * Returns the configured maximum size after which an {@link Attribute}/{@link FileUpload} starts being stored on disk rather than in memory.
	 *
	 * @return the configured maximum size after which an {@link Attribute}/{@link FileUpload} starts being stored on disk rather than in memory
	 * @see Builder#maxInMemorySize(long)
	 */
	public long maxInMemorySize() {
		return maxInMemorySize;
	}

	/**
	 * Returns the configured maximum allowed size of individual {@link Attribute}/{@link FileUpload}.
	 *
	 * @return the configured maximum allowed size of individual {@link Attribute}/{@link FileUpload}
	 * @see Builder#maxSize(long)
	 */
	public long maxSize() {
		return maxSize;
	}

	/**
	 * Returns the configured scheduler to be used for offloading disk operations in the decoding phase.
	 *
	 * @return the configured scheduler to be used for offloading disk operations in the decoding phase
	 * @see Builder#scheduler(Scheduler)
	 */
	public Scheduler scheduler() {
		return scheduler;
	}

	/**
	 * Returns whether the streaming mode is enabled.
	 *
	 * @return whether the streaming mode is enabled
	 * @see Builder#streaming(boolean)
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
		return maxInMemorySize == that.maxInMemorySize &&
				maxSize == that.maxSize &&
				streaming == that.streaming &&
				Objects.equals(baseDirectory, that.baseDirectory) &&
				charset.equals(that.charset) &&
				scheduler.equals(that.scheduler);
	}

	@Override
	public int hashCode() {
		int result = 1;
		result = 31 * result + Objects.hashCode(baseDirectory);
		result = 31 * result + Objects.hashCode(charset);
		result = 31 * result + Long.hashCode(maxInMemorySize);
		result = 31 * result + Long.hashCode(maxSize);
		result = 31 * result + Objects.hashCode(scheduler);
		result = 31 * result + Boolean.hashCode(streaming);
		return result;
	}

	Mono<Path> createDefaultTempDirectory() {
		return Mono.fromCallable(() -> Files.createTempDirectory(DEFAULT_TEMP_DIRECTORY_PREFIX))
		           .cache();
	}

	Mono<Path> defaultTempDirectory() {
		return defaultTempDirectory
				.flatMap(dir -> {
					if (!Files.exists(dir)) {
						Mono<Path> newDirectory = createDefaultTempDirectory();
						defaultTempDirectory = newDirectory;
						return newDirectory;
					}
					else {
						return Mono.just(dir);
					}
				})
				.subscribeOn(scheduler);
	}

	Mono<ReactorNettyHttpPostRequestDecoder> newHttpPostRequestDecoder(HttpRequest request, boolean isMultipart) {
		if (maxInMemorySize > -1) {
			Mono<Path> directoryMono;
			if (baseDirectory == null) {
				directoryMono = defaultTempDirectory();
			}
			else {
				directoryMono = Mono.just(baseDirectory);
			}
			return directoryMono.map(directory -> createNewHttpPostRequestDecoder(request, isMultipart, directory));
		}
		else {
			return Mono.just(createNewHttpPostRequestDecoder(request, isMultipart, null));
		}
	}

	ReactorNettyHttpPostRequestDecoder createNewHttpPostRequestDecoder(HttpRequest request, boolean isMultipart,
			@Nullable Path baseDirectory) {
		DefaultHttpDataFactory factory = maxInMemorySize > 0 ?
				new DefaultHttpDataFactory(maxInMemorySize, charset) :
				new DefaultHttpDataFactory(maxInMemorySize == 0, charset);
		factory.setMaxLimit(maxSize);
		if (baseDirectory != null) {
			factory.setBaseDir(baseDirectory.toFile().getAbsolutePath());
		}
		return isMultipart ?
				new ReactorNettyHttpPostMultipartRequestDecoder(factory, request) :
				new ReactorNettyHttpPostStandardRequestDecoder(factory, request);
	}

	static final HttpServerFormDecoderProvider DEFAULT_FORM_DECODER_SPEC = new HttpServerFormDecoderProvider.Build().build();

	static final String DEFAULT_TEMP_DIRECTORY_PREFIX = "RN_form_";

	interface ReactorNettyHttpPostRequestDecoder extends InterfaceHttpPostRequestDecoder {

		void cleanCurrentHttpData(boolean onlyCompleted);

		List<HttpData> currentHttpData(boolean onlyCompleted);
	}

	static final class Build implements Builder {

		static final Charset DEFAULT_CHARSET = StandardCharsets.UTF_8;
		static final long DEFAULT_MAX_IN_MEMORY_SIZE = DefaultHttpDataFactory.MINSIZE;
		static final long DEFAULT_MAX_SIZE = DefaultHttpDataFactory.MAXSIZE;
		static final Scheduler DEFAULT_SCHEDULER = Schedulers.boundedElastic();
		static final boolean DEFAULT_STREAMING = false;

		Path baseDirectory;
		Charset charset = DEFAULT_CHARSET;
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
		public void cleanCurrentHttpData(boolean onlyCompleted) {
			for (HttpData data : currentCompletedHttpData) {
				removeHttpDataFromClean(data);
				data.release();
			}
			currentCompletedHttpData.clear();

			if (!onlyCompleted) {
				InterfaceHttpData partial = currentPartialHttpData();
				if (partial instanceof HttpData) {
					((HttpData) partial).delete();
				}
			}
		}

		@Override
		public List<HttpData> currentHttpData(boolean onlyCompleted) {
			if (!onlyCompleted) {
				InterfaceHttpData partial = currentPartialHttpData();
				if (partial instanceof HttpData) {
					currentCompletedHttpData.add(((HttpData) partial).retainedDuplicate());
				}
			}

			return currentCompletedHttpData;
		}

		@Override
		public void destroy() {
			super.destroy();
			InterfaceHttpData partial = currentPartialHttpData();
			if (partial != null) {
				partial.release();
			}
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
				if (partial instanceof HttpData) {
					((HttpData) partial).delete();
				}
			}
		}

		@Override
		public List<HttpData> currentHttpData(boolean onlyCompleted) {
			if (!onlyCompleted) {
				InterfaceHttpData partial = currentPartialHttpData();
				if (partial instanceof HttpData) {
					currentCompletedHttpData.add(((HttpData) partial).retainedDuplicate());
				}
			}

			return currentCompletedHttpData;
		}

		@Override
		public void destroy() {
			super.destroy();
			InterfaceHttpData partial = currentPartialHttpData();
			if (partial != null) {
				partial.release();
			}
		}
	}
}
