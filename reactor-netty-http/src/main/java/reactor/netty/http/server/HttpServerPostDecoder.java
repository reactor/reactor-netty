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
package reactor.netty.http.server;

import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.multipart.HttpPostRequestDecoder;
import io.netty.handler.codec.http.multipart.InterfaceHttpData;
import io.netty.handler.codec.http.multipart.InterfaceHttpPostRequestDecoder;
import java.util.ArrayList;
import java.util.List;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;

/**
 * This decoder is able to decode a post body.
 * <p>
 * If you disable {@link HttpServerPostDecoder#automaticCleanup}, you <strong>HAVE TO</strong> call {@link #dispose()}
 * after completion to release all resources.
 */
public class HttpServerPostDecoder implements Disposable {

	private final InterfaceHttpPostRequestDecoder decoder;
	private final HttpServerRequest request;

	private boolean automaticCleanup;
	private boolean disposed;

	HttpServerPostDecoder(final HttpRequest nettyRequest, HttpServerRequest request) {
		this.decoder = new HttpPostRequestDecoder(nettyRequest);
		this.request = request;

		this.automaticCleanup = false;
		this.disposed = false;
	}

	@Override
	public boolean isDisposed() {
		return this.disposed;
	}

	/**
	 * Returns if {@link HttpServerPostDecoder#dispose()} should be called automatically.
	 *
	 * @return if {@link HttpServerPostDecoder#dispose()} should be called automatically.
	 */
	public boolean isAutomaticCleanup() {
		return this.automaticCleanup;
	}

	/**
	 * Defines if {@link HttpServerPostDecoder#dispose()} should be called automatically.
	 */
	public HttpServerPostDecoder automaticCleanup(final boolean automaticCleanup) {
		this.automaticCleanup = automaticCleanup;
		return this;
	}

	/**
	 * True if this request is a Multipart request
	 *
	 * @return True if this request is a Multipart request
	 */
	public boolean isMultipart() {
		return this.decoder.isMultipart();
	}

	/**
	 * Set the amount of bytes after which read bytes in the buffer should be discarded. Setting this lower gives lower
	 * memory usage but with the overhead of more memory copies. Use {@code 0} to disable it.
	 */
	public HttpServerPostDecoder discardThreshold(final int discardThreshold) {
		this.decoder.setDiscardThreshold(discardThreshold);
		return this;
	}

	/**
	 * Return the threshold in bytes after which read data in the buffer should be discarded.
	 *
	 * @return the threshold in bytes after which read data in the buffer should be discarded.
	 */
	public int discardThreshold() {
		return this.decoder.getDiscardThreshold();
	}

	/**
	 * Return a {@link Flux} containing all {@link InterfaceHttpData}'s from the body.
	 *
	 * @return a {@link Flux} containing all {@link InterfaceHttpData}'s from the body.
	 */
	public Flux<InterfaceHttpData> getBodyHttpData() {
		return this.request.receiveContent()
				.flatMapIterable(content -> {
					this.decoder.offer(content);

					final List<InterfaceHttpData> data = new ArrayList<>();
					while (this.decoder.hasNext()) {
						data.add(this.decoder.next());
					}

					return data;
				})
				.doFinally(signalType -> {
					if (this.automaticCleanup) {
						this.dispose();
					}
				});
	}

	/**
	 * Return a {@link Flux} containing all {@link InterfaceHttpData}'s witch are named with the given name from the
	 * body.
	 *
	 * @return a {@link Flux} containing all {@link InterfaceHttpData}'s witch are named with the given name from the
	 * body.
	 */
	public Flux<InterfaceHttpData> getBodyHttpData(final String name) {
		return this.request.receiveContent()
				.flatMapIterable(content -> {
					this.decoder.offer(content);

					final List<InterfaceHttpData> data = new ArrayList<>();
					while (this.decoder.hasNext()) {
						final InterfaceHttpData next = this.decoder.next();
						if (next.getName().equals(name)) {
							data.add(next);
						}
					}

					return data;
				})
				.doFinally(signalType -> {
					if (this.automaticCleanup) {
						this.dispose();
					}
				});
	}

	/**
	 * Remove the given {@link InterfaceHttpData} from the list of {@link InterfaceHttpData}'s to clean
	 */
	public HttpServerPostDecoder removeHttpDataFromClean(final InterfaceHttpData data) {
		this.decoder.removeHttpDataFromClean(data);
		return this;
	}

	@Override
	public void dispose() {
		if (this.disposed) {
			return;
		}

		this.decoder.destroy();
		this.disposed = true;
	}
}
