package reactor.ipc.netty.channel.data;

import java.io.RandomAccessFile;

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
	 * @param file the file being sent
	 * @return the file, as a {@link ChunkedInput}
	 */
	ChunkedInput<T> chunkFile(RandomAccessFile file);

	/**
	 * Once the file has been written, allows to clean the pipeline
	 * (see {@link #preparePipeline(NettyContext)}) and do other operations.
	 *
	 * @param context the context from which to obtain the channel and pipeline
	 */
	void cleanupPipeline(NettyContext context);
}
