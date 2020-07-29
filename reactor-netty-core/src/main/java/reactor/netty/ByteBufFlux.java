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

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.Charset;
import java.nio.file.Path;
import java.util.Objects;
import java.util.function.Function;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import io.netty.buffer.ByteBufHolder;
import io.netty.buffer.CompositeByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.util.IllegalReferenceCountException;
import org.reactivestreams.Publisher;
import reactor.core.CoreSubscriber;
import reactor.core.Fuseable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.FluxOperator;
import reactor.core.publisher.Mono;
import reactor.util.Logger;
import reactor.util.Loggers;

/**
 * A decorating {@link Flux} {@link NettyInbound} with various {@link ByteBuf} related
 * operations.
 *
 * @author Stephane Maldini
 */
public class ByteBufFlux extends FluxOperator<ByteBuf, ByteBuf> {

	/**
	 * Decorate as {@link ByteBufFlux}
	 *
	 * @param source publisher to decorate
	 *
	 * @return a {@link ByteBufFlux}
	 */
	public static ByteBufFlux fromInbound(Publisher<?> source) {
		return fromInbound(source, ByteBufAllocator.DEFAULT);
	}

	/**
	 * Decorate as {@link ByteBufFlux}
	 *
	 * @param source publisher to decorate
	 * @param allocator the channel {@link ByteBufAllocator}
	 *
	 * @return a {@link ByteBufFlux}
	 */
	public static ByteBufFlux fromInbound(Publisher<?> source, ByteBufAllocator allocator) {
		Objects.requireNonNull(allocator, "allocator");
		return maybeFuse(Flux.from(ReactorNetty.publisherOrScalarMap(source, bytebufExtractor)), allocator);
	}


	/**
	 * Decorate as {@link ByteBufFlux}
	 *
	 * @param source publisher to decorate
	 *
	 * @return a {@link ByteBufFlux}
	 */
	public static ByteBufFlux fromString(Publisher<? extends String> source) {
		return fromString(source, Charset.defaultCharset(), ByteBufAllocator.DEFAULT);
	}

	public static ByteBufFlux fromString(Publisher<? extends String> source, Charset charset, ByteBufAllocator allocator) {
		Objects.requireNonNull(allocator, "allocator");
		return maybeFuse(
				Flux.from(ReactorNetty.publisherOrScalarMap(
						source, s -> {
							ByteBuf buffer = allocator.buffer();
							buffer.writeCharSequence(s, charset);
							return buffer;
						})), allocator);
	}

	/**
	 * Open a {@link java.nio.channels.FileChannel} from a path and stream
	 * {@link ByteBuf} chunks with a default maximum size of 500K into
	 * the returned {@link ByteBufFlux}
	 *
	 * @param path the path to the resource to stream
	 *
	 * @return a {@link ByteBufFlux}
	 */
	public static ByteBufFlux fromPath(Path path) {
		return fromPath(path, MAX_CHUNK_SIZE);
	}

	/**
	 * Open a {@link java.nio.channels.FileChannel} from a path and stream
	 * {@link ByteBuf} chunks with a given maximum size into the returned {@link ByteBufFlux}
	 *
	 * @param path the path to the resource to stream
	 * @param maxChunkSize the maximum per-item ByteBuf size
	 *
	 * @return a {@link ByteBufFlux}
	 */
	public static ByteBufFlux fromPath(Path path, int maxChunkSize) {
		return fromPath(path, maxChunkSize, ByteBufAllocator.DEFAULT);
	}

	/**
	 * Open a {@link java.nio.channels.FileChannel} from a path and stream
	 * {@link ByteBuf} chunks with a default maximum size of 500K into the returned
	 * {@link ByteBufFlux}, using the provided {@link ByteBufAllocator}.
	 *
	 * @param path the path to the resource to stream
	 * @param allocator the channel {@link ByteBufAllocator}
	 *
	 * @return a {@link ByteBufFlux}
	 */
	public static ByteBufFlux fromPath(Path path, ByteBufAllocator allocator) {
		return fromPath(path, MAX_CHUNK_SIZE, allocator);
	}

	/**
	 * Open a {@link java.nio.channels.FileChannel} from a path and stream
	 * {@link ByteBuf} chunks with a given maximum size into the returned
	 * {@link ByteBufFlux}, using the provided {@link ByteBufAllocator}.
	 *
	 * @param path the path to the resource to stream
	 * @param maxChunkSize the maximum per-item ByteBuf size
	 * @param allocator the channel {@link ByteBufAllocator}
	 *
	 * @return a {@link ByteBufFlux}
	 */
	public static ByteBufFlux fromPath(Path path,
			int maxChunkSize,
			ByteBufAllocator allocator) {
		Objects.requireNonNull(path, "path");
		Objects.requireNonNull(allocator, "allocator");
		if (maxChunkSize < 1) {
			throw new IllegalArgumentException("chunk size must be strictly positive, " + "was: " + maxChunkSize);
		}
		return maybeFuse(
				Flux.generate(() -> FileChannel.open(path),
				              (fc, sink) -> {
				                  ByteBuf buf = allocator.buffer();
				                  try {
				                      if (buf.writeBytes(fc, maxChunkSize) < 0) {
				                          buf.release();
				                          sink.complete();
				                      }
				                      else {
				                          sink.next(buf);
				                      }
				                  }
				                  catch (IOException e) {
				                      buf.release();
				                      sink.error(e);
				                  }
				                  return fc;
				              },
				              ReactorNetty.fileCloser),
				allocator);
	}

	/**
	 * Convert to a {@link ByteBuffer} inbound {@link Flux}
	 *
	 * @return a {@link ByteBuffer} inbound {@link Flux}
	 */
	public final Flux<ByteBuffer> asByteBuffer() {
		return handle((bb, sink) -> {
			try {
				sink.next(bb.nioBuffer());
			}
			catch (IllegalReferenceCountException e) {
				sink.complete();
			}
		});
	}

	/**
	 * Convert to a {@literal byte[]} inbound {@link Flux}
	 *
	 * @return a {@literal byte[]} inbound {@link Flux}
	 */
	public final Flux<byte[]> asByteArray() {
		return handle((bb, sink) -> {
			try {
				byte[] bytes = new byte[bb.readableBytes()];
				bb.readBytes(bytes);
				sink.next(bytes);
			}
			catch (IllegalReferenceCountException e) {
				sink.complete();
			}
		});
	}

	/**
	 * Convert to a {@link InputStream} inbound {@link Flux}
	 *
	 * @return a {@link InputStream} inbound {@link Flux}
	 */
	public final Flux<InputStream> asInputStream() {
		return handle((bb, sink) -> {
			try {
				sink.next(new ByteBufMono.ReleasingInputStream(bb));
			}
			catch (IllegalReferenceCountException e) {
				sink.complete();
			}
		});
	}

	/**
	 * Convert to a {@link String} inbound {@link Flux} using the default {@link Charset}.
	 *
	 * @return a {@link String} inbound {@link Flux}
	 */
	public final Flux<String> asString() {
		return asString(Charset.defaultCharset());
	}

	/**
	 * Convert to a {@link String} inbound {@link Flux} using the provided {@link Charset}.
	 *
	 * @param charset the decoding charset
	 *
	 * @return a {@link String} inbound {@link Flux}
	 */
	public final Flux<String> asString(Charset charset) {
		return handle((bb, sink) -> {
			try {
				sink.next(bb.readCharSequence(bb.readableBytes(), charset).toString());
			}
			catch (IllegalReferenceCountException e) {
				sink.complete();
			}
		});
	}

	/**
	 * Aggregate subsequent byte buffers into a single buffer.
	 *
	 * @return {@link ByteBufMono} of aggregated {@link ByteBuf}
	 */
	public final ByteBufMono aggregate() {
		return Mono.defer(() -> {
		               CompositeByteBuf output = alloc.compositeBuffer();
		               return doOnNext(ByteBuf::retain)
		                       .collectList()
		                       .doOnDiscard(ByteBuf.class, ByteBufFlux::safeRelease)
		                       .handle((list, sink) -> {
		                           if (!list.isEmpty()) {
		                               try {
		                                   output.addComponents(true, list);
		                               }
		                               catch(IllegalReferenceCountException e) {
		                                   if (log.isDebugEnabled()) {
		                                       log.debug("", e);
		                                   }
		                               }
		                           }
		                           if (output.isReadable()) {
		                               sink.next(output);
		                           }
		                           else {
		                               sink.complete();
		                           }
		                       })
		                       .doFinally(signalType -> safeRelease(output));
		               })
		           .as(ByteBufMono::maybeFuse);
	}

	/**
	 * Allow multiple consumers downstream of the flux while also disabling auto memory
	 * release on each buffer published (retaining in order to prevent premature recycling).
	 *
	 * @return {@link ByteBufMono} of retained {@link ByteBuf}
	 */
	public final ByteBufMono multicast() {
		throw new UnsupportedOperationException("Not yet implemented");
	}

	/**
	 * Disable auto memory release on each buffer published, retaining in order to prevent
	 * premature recycling when buffers are accumulated downstream (async).
	 *
	 * @return {@link ByteBufFlux} of retained {@link ByteBuf}
	 */
	public final ByteBufFlux retain() {
		return maybeFuse(doOnNext(ByteBuf::retain), alloc);
	}

	final ByteBufAllocator alloc;

	ByteBufFlux(Flux<ByteBuf> source, ByteBufAllocator allocator) {
		super(source);
		this.alloc = allocator;
	}

	static final class ByteBufFluxFuseable extends ByteBufFlux implements Fuseable {

		ByteBufFluxFuseable(Flux<ByteBuf> source, ByteBufAllocator allocator) {
			super(source, allocator);
		}
	}

	@Override
	public void subscribe(CoreSubscriber<? super ByteBuf> s) {
		source.subscribe(s);
	}

	static ByteBufFlux maybeFuse(Flux<ByteBuf> source, ByteBufAllocator allocator) {
		if (source instanceof Fuseable) {
			return new ByteBufFluxFuseable(source, allocator);
		}
		return new ByteBufFlux(source, allocator);
	}

	/**
	 * A channel object to {@link ByteBuf} transformer
	 */
	final static Function<Object, ByteBuf> bytebufExtractor = o -> {
		if (o instanceof ByteBuf) {
			return (ByteBuf) o;
		}
		if (o instanceof ByteBufHolder) {
			return ((ByteBufHolder) o).content();
		}
		if (o instanceof byte[]) {
			return Unpooled.wrappedBuffer((byte[])o);
		}
		throw new IllegalArgumentException("Object " + o + " of type " + o.getClass() + " " + "cannot be converted to ByteBuf");
	};

	final static int MAX_CHUNK_SIZE = 1024 * 512; //500k

	final static Logger log = Loggers.getLogger(ByteBufFlux.class);

	static void safeRelease(ByteBuf byteBuf) {
		if (byteBuf.refCnt() > 0) {
			try {
				byteBuf.release();
			}
			catch (IllegalReferenceCountException e) {
				if (log.isDebugEnabled()) {
					log.debug("", e);
				}
			}
		}
	}
}
