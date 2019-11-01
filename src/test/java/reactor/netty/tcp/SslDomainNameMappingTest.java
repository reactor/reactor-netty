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

import javax.net.ssl.SSLException;

import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import io.netty.handler.ssl.util.SelfSignedCertificate;
import io.netty.util.DomainNameMappingBuilder;
import org.junit.Assert;
import org.junit.Test;

/**
 * @author aftersss
 */
public class SslDomainNameMappingTest {

	@Test
	public void testMap() throws CertificateException, SSLException {
		SelfSignedCertificate cert = new SelfSignedCertificate("default");
		SslContextBuilder sslContextBuilder =
				SslContextBuilder.forServer(cert.certificate(), cert.privateKey());
		SslContext defaultSslContext = sslContextBuilder.build();

		SelfSignedCertificate demoCert = new SelfSignedCertificate("demo.com");
		SslContextBuilder demoSslContextBuilder =
				SslContextBuilder.forServer(demoCert.certificate(), demoCert.privateKey());
		SslContext demoSslContext = demoSslContextBuilder.build();

		DomainNameMappingBuilder<SslContext> builder = new DomainNameMappingBuilder<>(defaultSslContext);
		builder.add("demo.com", demoSslContext);

		SslDomainNameMapping mapping = new SslDomainNameMapping(builder.build(), defaultSslContext);
		Assert.assertTrue(mapping.map("demo.com") == demoSslContext);
		Assert.assertTrue(mapping.map("a.demo.com") == defaultSslContext);

		builder = new DomainNameMappingBuilder<>(defaultSslContext);
		builder.add("*.demo.com", demoSslContext);
		mapping = new SslDomainNameMapping(builder.build(), defaultSslContext);
		Assert.assertTrue(mapping.map("a.demo.com") == demoSslContext);
		Assert.assertTrue(mapping.map("a.b.demo.com") == defaultSslContext);
		Assert.assertTrue(mapping.map("demo.com") == defaultSslContext);
		Assert.assertTrue(mapping.map("demo1.com") == defaultSslContext);
	}
}
