/*
 * Copyright (c) 2011-2019 Pivotal Software Inc, All Rights Reserved.
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

package reactor.ipc.netty.channel.data;

import io.netty.handler.stream.ChunkedWriteHandler;
import reactor.ipc.netty.NettyContext;
import reactor.ipc.netty.NettyPipeline;

import java.nio.channels.FileChannel;

/**
 * A base abstract implementation of a {@link FileChunkedStrategy}. Only the
 * {@link #chunkFile(FileChannel, long, long, int)} method needs to be implemented, but child classes
 * can also override {@link #afterWrite(NettyContext)} to add custom cleanup.
 * The pipeline preparation and cleanup involves adding and removing the
 * {@link NettyPipeline#ChunkedWriter} handler if it was not already present. It will be
 * added before {@link NettyPipeline#ReactiveBridge} or last, if the bridge handler is not
 * present.
 *
 * @author Simon Baslé
 */
public abstract class AbstractFileChunkedStrategy<T> implements FileChunkedStrategy<T> {

	/**
	 * {@inheritDoc}
	 * <p>
	 * This adds a ChunkedWriter to the pipeline to extract chunks from the
	 * {@link io.netty.handler.stream.ChunkedInput} that the strategy produces. This step
	 * is skipped if the handler is already present, and the placement of the handler
	 * depends on the presence of the ReactiveBridge handler (see {@link NettyPipeline}).
	 *
	 * @param context the context from which to obtain the channel and pipeline
	 */
	@Override
	public final void preparePipeline(NettyContext context) {
		if (!hasChunkedWriter(context)) {
			boolean hasReactiveBridge = context.channel()
			                                   .pipeline()
			                                   .get(NettyPipeline.ReactiveBridge) != null;

			if (hasReactiveBridge) {
				context.channel()
				       .pipeline()
				       .addBefore(NettyPipeline.ReactiveBridge,
						       NettyPipeline.ChunkedWriter,
						       new ChunkedWriteHandler());
			}
			else {
				context.channel()
				       .pipeline()
				       .addLast(NettyPipeline.ChunkedWriter, new ChunkedWriteHandler());
			}
		}
	}

	private boolean hasChunkedWriter(NettyContext context) {
		return context.channel()
		              .pipeline()
		              .get(NettyPipeline.ChunkedWriter) != null;
	}

	/**
	 * {@inheritDoc}
	 * <p>
	 * This removes the ChunkedWriter handler if it was added by this strategy. It then
	 * calls the {@link #afterWrite(NettyContext)} method
	 *
	 * @param context the context from which to obtain the channel and pipeline
	 */
	@Override
	public final void cleanupPipeline(NettyContext context) {
		if (hasChunkedWriter(context)) {
			context.channel()
			       .pipeline()
			       .remove(NettyPipeline.ChunkedWriter);
		}
		afterWrite(context);
	}

	/**
	 * Additional cleanup to perform at the end of {@link #cleanupPipeline(NettyContext)}.
	 *
	 * @param context the {@link NettyContext}
	 */
	protected void afterWrite(NettyContext context) {
		//NO-OP
	}
}
