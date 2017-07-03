package reactor.ipc.netty.tcp;

import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.concurrent.TimeoutException;

import reactor.core.publisher.Mono;
import reactor.ipc.netty.NettyContext;
import reactor.util.Logger;
import reactor.util.Loggers;

/**
 * Wrap a {@link NettyContext} obtained from a {@link Mono} and offer methods to manage
 * its lifecycle in a blocking fashion.
 *
 * @author Simon Baslé
 */
public class BlockingNettyContext {

	private static final Logger LOG = Loggers.getLogger(BlockingNettyContext.class);

	private final NettyContext context;
	private final String description;

	private Duration lifecycleTimeout;

	public BlockingNettyContext(Mono<? extends NettyContext> contextAsync,
			String description) {
		this(contextAsync, description, Duration.ofSeconds(3));
	}

	public BlockingNettyContext(Mono<? extends NettyContext> contextAsync,
			String description, Duration lifecycleTimeout) {
		this.description = description;
		this.lifecycleTimeout = lifecycleTimeout;
		this.context = contextAsync
				.timeout(lifecycleTimeout, Mono.error(new TimeoutException(description + " couldn't be started within " + lifecycleTimeout.toMillis() + "ms")))
				.doOnNext(ctx -> LOG.info("Started {} on {}", description, ctx.address()))
				.block();

		context.onClose().subscribe(null,
				e -> LOG.error("Stopped {} on {} with an error {}", description, context.address(), e),
				() -> LOG.info("Stopped {} on {}", description, context.address()));
	}

	/**
	 * Change the lifecycle timeout applied to the {@link #shutdown()} operation (as this can
	 * only be called AFTER the {@link NettyContext} has been "started").
	 *
	 * @param timeout the new timeout to apply on shutdown.
	 */
	public void setLifecycleTimeout(Duration timeout) {
		this.lifecycleTimeout = timeout;
	}

	/**
	 * Get the {@link NettyContext} wrapped by this facade.
	 * @return the original NettyContext.
	 */
	public NettyContext getContext() {
		return context;
	}

	/**
	 * Return this server's port.
	 * @return The port the server is bound to.
	 */
	public int getPort() {
		return context.address().getPort();
	}

	/**
	 * Return the server's host String. That is, the hostname or in case the server was bound
	 * to a literal IP adress, the IP string representation (rather than performing a reverse-DNS
	 * lookup).
	 *
	 * @return the host string, without reverse DNS lookup
	 * @see NettyContext#address()
	 * @see InetSocketAddress#getHostString()
	 */
	public String getHost() {
		return context.address().getHostString();
	}

	/**
	 * Shut down the {@link NettyContext} and wait for its termination, up to the
	 * {@link #setLifecycleTimeout(Duration) lifecycle timeout}.
	 */
	public void shutdown() {
		if (context.isDisposed()) {
			return;
		}
		context.dispose();
		context.onClose()
		       .timeout(lifecycleTimeout, Mono.error(new TimeoutException(description + " couldn't be stopped within " + lifecycleTimeout.toMillis() + "ms")))
		       .block();
	}
}
