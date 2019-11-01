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
package reactor.netty.tcp;

import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.ssl.SniHandler;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslHandler;
import io.netty.util.ReferenceCountUtil;
import reactor.netty.NettyPipeline;

/**
 * @author aftersss
 */
public class AutoSniHandler extends SniHandler {
	private SslDomainNameMappingContainer sslDomainNameMappingContainer;

	public AutoSniHandler(SslDomainNameMappingContainer sslDomainNameMappingContainer) {
		super(sslDomainNameMappingContainer.getDomainNameMapping());

		this.sslDomainNameMappingContainer = sslDomainNameMappingContainer;
	}

	@Override
	protected void replaceHandler(ChannelHandlerContext ctx, String hostname, SslContext sslContext) throws Exception {
		SslHandler sslHandler = null;
		try {
			sslHandler = newSslHandler(sslContext, ctx.alloc());

			sslDomainNameMappingContainer.getDefaultSslProvider().configure(sslHandler);

			ctx.pipeline().replace(this, NettyPipeline.SslHandler, sslHandler);
			sslHandler = null;
		} finally {
			// Since the SslHandler was not inserted into the pipeline the ownership of the SSLEngine was not
			// transferred to the SslHandler.
			// See https://github.com/netty/netty/issues/5678
			if (sslHandler != null) {
				ReferenceCountUtil.safeRelease(sslHandler.engine());
			}
		}
	}
}
