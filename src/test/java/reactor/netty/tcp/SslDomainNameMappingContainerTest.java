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

import java.security.cert.CertificateException;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Consumer;
import javax.net.ssl.SSLException;

import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import io.netty.handler.ssl.util.SelfSignedCertificate;
import org.junit.Assert;
import org.junit.Test;

/**
 * @author aftersss
 */
public class SslDomainNameMappingContainerTest {

	@Test
	public void testSetDefaultSslProviderBuilder() throws CertificateException, SSLException {
		SelfSignedCertificate cert = new SelfSignedCertificate("default");
		SslContextBuilder sslContextBuilder =
				SslContextBuilder.forServer(cert.certificate(), cert.privateKey());
		SslContext defaultSslContext = sslContextBuilder.build();

		SslDomainNameMappingContainer container = new SslDomainNameMappingContainer(sslContextSpec -> sslContextSpec.sslContext(defaultSslContext));
		Assert.assertTrue(container.getDomainNameMapping().map("demo.com") == defaultSslContext);

		SelfSignedCertificate demoCert = new SelfSignedCertificate("demo.com");
		SslContextBuilder demoSslContextBuilder =
				SslContextBuilder.forServer(demoCert.certificate(), demoCert.privateKey());
		SslContext demoSslContext = demoSslContextBuilder.build();

		container.setDefaultSslProviderBuilder(sslContextSpec -> sslContextSpec.sslContext(demoSslContext));
		Assert.assertTrue(container.getDomainNameMapping().map("demo.com") == demoSslContext);
	}

	@Test
	public void testAdd() throws CertificateException, SSLException {
		SelfSignedCertificate cert = new SelfSignedCertificate("default");
		SslContextBuilder sslContextBuilder =
				SslContextBuilder.forServer(cert.certificate(), cert.privateKey());
		SslContext defaultSslContext = sslContextBuilder.build();

		SslDomainNameMappingContainer container = new SslDomainNameMappingContainer(sslContextSpec -> sslContextSpec.sslContext(defaultSslContext));
		Assert.assertTrue(container.getDomainNameMapping().map("demo.com") == defaultSslContext);

		SelfSignedCertificate demoCert = new SelfSignedCertificate("demo.com");
		SslContextBuilder demoSslContextBuilder =
				SslContextBuilder.forServer(demoCert.certificate(), demoCert.privateKey());
		SslContext demoSslContext = demoSslContextBuilder.build();

		container.add("demo.com", sslContextSpec -> sslContextSpec.sslContext(demoSslContext));
		Assert.assertTrue(container.getDomainNameMapping().map("demo.com") == demoSslContext);
		Assert.assertTrue(container.getDomainNameMapping().map("demo1.com") == defaultSslContext);

		demoCert = new SelfSignedCertificate("demo.com");
		demoSslContextBuilder =
				SslContextBuilder.forServer(demoCert.certificate(), demoCert.privateKey());
		SslContext demoSslContext1 = demoSslContextBuilder.build();

		container.add("demo.com", sslContextSpec -> sslContextSpec.sslContext(demoSslContext1));
		Assert.assertTrue(container.getDomainNameMapping().map("demo.com") == demoSslContext1);

		container.remove("demo.com");
		Assert.assertTrue(container.getDomainNameMapping().map("demo.com") == defaultSslContext);

		demoCert = new SelfSignedCertificate("demo.com");
		demoSslContextBuilder =
				SslContextBuilder.forServer(demoCert.certificate(), demoCert.privateKey());
		SslContext demoSslContext2 = demoSslContextBuilder.build();
		Map<String, Consumer<? super SslProvider.SslContextSpec>> map = new HashMap<>();
		map.put("demo.com", sslContextSpec -> sslContextSpec.sslContext(demoSslContext2));
		container.addAll(map);
		Assert.assertTrue(container.getDomainNameMapping().map("demo.com") == demoSslContext2);

		map.clear();
		map.put("demo1.com", sslContextSpec -> sslContextSpec.sslContext(demoSslContext2));
		container.clearAndAddAll(map);
		Assert.assertTrue(container.getDomainNameMapping().map("demo.com") == defaultSslContext);
		Assert.assertTrue(container.getDomainNameMapping().map("demo1.com") == demoSslContext2);
	}
}
