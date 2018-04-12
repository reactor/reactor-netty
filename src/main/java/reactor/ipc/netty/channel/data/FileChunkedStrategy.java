/*
 * Copyright (c) 2011-2018 Pivotal Software Inc, All Rights Reserved.
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

package reactor.ipc.netty.channel.data;

import java.io.RandomAccessFile;
import java.nio.channels.FileChannel;

import io.netty.buffer.ByteBufAllocator;
import io.netty.channel.Channel;
import io.netty.handler.stream.ChunkedInput;
import reactor.ipc.netty.NettyContext;

/**
 * A strategy applied by {@link reactor.ipc.netty.NettyOutbound} when sending a file
 * should be done using a {@link ChunkedInput} instead of zero-copy (typically when SSL
 * is used). This in turn allows to manipulate the pipeline and adapt the exact ChunkedInput
 * used to different situation, like TCP vs HTTP.
 *
 * @author Simon Baslé
 */
public interface FileChunkedStrategy<T> {

	/**
	 * Allow for preparing the pipeline (eg. by adding handlers dynamically) before sending
	 * the chunked file. The {@link NettyContext} is provided and can be used to get the
	 * {@link NettyContext#channel() channel()} then {@link Channel#pipeline() pipeline()}.
	 *
	 * @param context the context from which to obtain the channel and pipeline
	 */
	void preparePipeline(NettyContext context);

	/**
	 * Given the sent file as a {@link RandomAccessFile}, return a {@link ChunkedInput}
	 * "view" of the file, eg. as a {@link io.netty.handler.stream.ChunkedFile} or a
	 * {@link io.netty.handler.codec.http.HttpChunkedInput} around a ChunkedFile.
	 *
	 * @param fileChannel the {@link FileChannel} for the file being sent
	 * @param offset the offset of the file where the transfer begins
	 * @param length the number of bytes to transfer
	 * @param chunkSize the number of bytes to fetch on each
	 *                  {@link ChunkedInput#readChunk(ByteBufAllocator)} call
	 * @return the file, as a {@link ChunkedInput}
	 */
	ChunkedInput<T> chunkFile(FileChannel fileChannel, long offset, long length, int chunkSize);

	/**
	 * Once the file has been written, allows to clean the pipeline
	 * (see {@link #preparePipeline(NettyContext)}) and do other operations.
	 *
	 * @param context the context from which to obtain the channel and pipeline
	 */
	void cleanupPipeline(NettyContext context);
}
