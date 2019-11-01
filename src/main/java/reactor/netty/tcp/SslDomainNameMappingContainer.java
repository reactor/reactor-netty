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

import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.Consumer;

import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import io.netty.handler.ssl.util.SelfSignedCertificate;
import io.netty.util.DomainNameMappingBuilder;
import io.netty.util.Mapping;

/**
 * Container for multiple {@link SslProvider}s, useful for sni or ssl,
 * you can optionally add multiple hostname-to-sslProviderBuilder mapping(with hostname optionally wildcard) so
 * the server will auto search for the suitable {@link SslProvider} for the given hostname.
 * If no suitable {@link SslProvider} is found for the hostname or the client hostname is not specified,
 * it will fallback to use the default {@link SslProvider}.
 *
 * @author aftersss
 */
public class SslDomainNameMappingContainer {

	private volatile Mapping<String, SslContext> domainNameMapping;

	private final ConcurrentMap<String, SslProvider> sslProviderMap = new ConcurrentHashMap<>();

	private volatile SslProvider.DefaultConfigurationType defaultConfigurationType;

	//The default SslProvider which will further generate {@link SslContext},
	//the generated {@link SslContext} will be used when there is no suitable {@link SslContext} is found for the hostname
	//or when the client hostname is not specified.
	private volatile SslProvider defaultSslProvider;

	/**
	 * Constructs a {@link SslDomainNameMappingContainer} with the default sslProviderBuilder which will further generate {@link SslContext},
	 * the generated {@link SslContext} will be used when there is no suitable {@link SslContext} is found for the hostname
	 * or when the client hostname is not specified.
	 *
	 * @param defaultSslProviderBuilder builder callback for further customization of {@link SslContext}.
	 *                           The builder will produce the {@link SslContext} to be passed to with a default value of
	 *                           {@code 10} seconds handshake timeout unless the environment property {@code
	 * 	                         reactor.netty.tcp.sslHandshakeTimeout} is set.
	 * 	                         If {@link SelfSignedCertificate} needs to be used, the sample below can be
	 * 	                         used. Note that {@link SelfSignedCertificate} should not be used in production.
	 * 	                         <pre>
	 * 	                         {@code
	 * 	                             SelfSignedCertificate cert = new SelfSignedCertificate();
	 * 	                             SslContextBuilder sslContextBuilder =
	 * 	                                     SslContextBuilder.forServer(cert.certificate(), cert.privateKey());
	 * 	                             secure(sslContextSpec -> sslContextSpec.sslContext(sslContextBuilder));
	 * 	                         }
	 * 	                         </pre>
	 */
	public SslDomainNameMappingContainer(Consumer<? super SslProvider.SslContextSpec> defaultSslProviderBuilder) {
		setDefaultSslProviderBuilder(defaultSslProviderBuilder);
	}

	/**
	 * Constructs a {@link SslDomainNameMappingContainer} with the default SslProvider which will further generate {@link SslContext},
	 * the generated {@link SslContext} will be used when there is no suitable {@link SslContext} is found for the hostname
	 * or when the client hostname is not specified.
	 *
	 * @param defaultSslProvider builder callback for further customization of {@link SslContext}.
	 *                           The builder will produce the {@link SslContext} to be passed to with a default value of
	 *                           {@code 10} seconds handshake timeout unless the environment property {@code
	 * 	                         reactor.netty.tcp.sslHandshakeTimeout} is set.
	 * 	                         If {@link SelfSignedCertificate} needs to be used, the sample below can be
	 * 	                         used. Note that {@link SelfSignedCertificate} should not be used in production.
	 * 	                         <pre>
	 * 	                         {@code
	 * 	                             SelfSignedCertificate cert = new SelfSignedCertificate();
	 * 	                             SslContextBuilder sslContextBuilder =
	 * 	                                     SslContextBuilder.forServer(cert.certificate(), cert.privateKey());
	 * 	                             secure(sslContextSpec -> sslContextSpec.sslContext(sslContextBuilder));
	 * 	                         }
	 * 	                         </pre>
	 */
	SslDomainNameMappingContainer(SslProvider defaultSslProvider) {
		this.defaultSslProvider = Objects.requireNonNull(defaultSslProvider, "defaultSslProvider");
		if(defaultConfigurationType != null) {
			this.defaultSslProvider
					= SslProvider.updateDefaultConfiguration(this.defaultSslProvider, defaultConfigurationType);
		}

		refreshDomainNameMapping();
	}

	/**
	 * Set the default sslProviderBuilder which will further generate {@link SslContext},
	 * the generated {@link SslContext} will be used when there is no suitable {@link SslContext} is found for the hostname
	 * or when the client hostname is not specified.
	 *
	 * @param defaultSslProviderBuilder builder callback for further customization of {@link SslContext}.
	 *                           The builder will produce the {@link SslContext} to be passed to with a default value of
	 *                           {@code 10} seconds handshake timeout unless the environment property {@code
	 * 	                         reactor.netty.tcp.sslHandshakeTimeout} is set.
	 * 	                         If {@link SelfSignedCertificate} needs to be used, the sample below can be
	 * 	                         used. Note that {@link SelfSignedCertificate} should not be used in production.
	 * 	                         <pre>
	 * 	                         {@code
	 * 	                             SelfSignedCertificate cert = new SelfSignedCertificate();
	 * 	                             SslContextBuilder sslContextBuilder =
	 * 	                                     SslContextBuilder.forServer(cert.certificate(), cert.privateKey());
	 * 	                             secure(sslContextSpec -> sslContextSpec.sslContext(sslContextBuilder));
	 * 	                         }
	 * 	                         </pre>
	 */
	public void setDefaultSslProviderBuilder(Consumer<? super SslProvider.SslContextSpec> defaultSslProviderBuilder) {
		Objects.requireNonNull(defaultSslProviderBuilder, "defaultSslProviderBuilder");
		reactor.netty.tcp.SslProvider.SslContextSpec builder = reactor.netty.tcp.SslProvider.builder();
		defaultSslProviderBuilder.accept(builder);
		this.defaultSslProvider = ((SslProvider.Builder) builder).build();
		if(defaultConfigurationType != null) {
			this.defaultSslProvider
					= SslProvider.updateDefaultConfiguration(defaultSslProvider, defaultConfigurationType);
		}

		refreshDomainNameMapping();
	}

	/**
	 * Update the Default configuration that will be applied to the provided
	 * {@link SslContextBuilder}
	 *
	 * @param type default configuration that will be applied to the provided {@link SslContextBuilder}
	 */
	public void updateAllSslProviderConfiguration(SslProvider.DefaultConfigurationType type) {
		if (this.defaultConfigurationType == type) {
			return;
		}
		this.defaultConfigurationType = type;
		if (defaultSslProvider.getDefaultConfigurationType() == null) {
			defaultSslProvider = SslProvider.updateDefaultConfiguration(defaultSslProvider, type);
		}

		for (Map.Entry<String, SslProvider> entry : sslProviderMap.entrySet()) {
			SslProvider sslProvider = entry.getValue();
			if (sslProvider.getDefaultConfigurationType() == null) {
				sslProvider = SslProvider.updateDefaultConfiguration(sslProvider, type);
				entry.setValue(sslProvider);
			}
		}

		refreshDomainNameMapping();
	}

	public SslProvider getDefaultSslProvider() {
		return defaultSslProvider;
	}

	/**
	 * Bind a hostname with a SslProviderBuilder, if the hostname is already exists, the value will be replaced.
	 * This method can be invoked after the server have been started.
	 *
	 * @param hostname the host name (optionally wildcard)
	 * @param sslProviderBuilder builder callback for further customization of {@link SslContext}.
	 *                           The builder will produce the {@link SslContext} to be passed to with a default value of
	 *                           {@code 10} seconds handshake timeout unless the environment property {@code
	 * 	                         reactor.netty.tcp.sslHandshakeTimeout} is set.
	 * 	                         If {@link SelfSignedCertificate} needs to be used, the sample below can be
	 * 	                         used. Note that {@link SelfSignedCertificate} should not be used in production.
	 * 	                         <pre>
	 * 	                         {@code
	 * 	                             SelfSignedCertificate cert = new SelfSignedCertificate();
	 * 	                             SslContextBuilder sslContextBuilder =
	 * 	                                     SslContextBuilder.forServer(cert.certificate(), cert.privateKey());
	 * 	                             secure(sslContextSpec -> sslContextSpec.sslContext(sslContextBuilder));
	 * 	                         }
	 * 	                         </pre>
	 */
	public void add(String hostname, Consumer<? super SslProvider.SslContextSpec> sslProviderBuilder){
		addInner(hostname, sslProviderBuilder);

		refreshDomainNameMapping();
	}

	private void addInner(String hostname, Consumer<? super SslProvider.SslContextSpec> sslProviderBuilder) {
		Objects.requireNonNull(sslProviderBuilder, "sslProviderBuilder");
		reactor.netty.tcp.SslProvider.SslContextSpec builder = reactor.netty.tcp.SslProvider.builder();
		sslProviderBuilder.accept(builder);
		SslProvider sslProvider = ((SslProvider.Builder) builder).build();
		if (defaultConfigurationType != null && sslProvider.getDefaultConfigurationType() == null) {
			sslProvider = SslProvider.updateDefaultConfiguration(sslProvider, defaultConfigurationType);
		}

		sslProviderMap.put(hostname, sslProvider);
	}

	/**
	 * Like {@link SslDomainNameMappingContainer#add(String, Consumer<? super SslProvider.SslContextSpec>)},
	 * this method can add multi hostname-to-sslProviderBuilder mappings at one time.
	 * This method can be invoked after the server have been started.
	 *
	 * @param hostname2sslProviderBuilderMap hostname-to-sslProviderBuilder mappings
	 */
	public void addAll(Map<String, Consumer<? super SslProvider.SslContextSpec>> hostname2sslProviderBuilderMap){
		Objects.requireNonNull(hostname2sslProviderBuilderMap, "hostname2sslProviderBuilderMap");
		for (Map.Entry<String, Consumer<? super SslProvider.SslContextSpec>> entry : hostname2sslProviderBuilderMap.entrySet()) {
			String hostname = entry.getKey();
			Consumer<? super SslProvider.SslContextSpec> sslProviderBuilder = entry.getValue();

			addInner(hostname, sslProviderBuilder);
		}

		refreshDomainNameMapping();
	}

	/**
	 * Like {@link SslDomainNameMappingContainer#add(String, Consumer<? super SslProvider.SslContextSpec>)},
	 * this method will clear all existing hostname-to-sslProviderBuilder mappings and then add multi hostname-to-sslProviderBuilder mappings at one time.
	 * This method can be invoked after the server have been started.
	 *
	 * @param hostname2sslProviderBuilderMap hostname-to-sslProviderBuilder mappings
	 */
	public void clearAndAddAll(Map<String, Consumer<? super SslProvider.SslContextSpec>> hostname2sslProviderBuilderMap) {
		sslProviderMap.clear();

		addAll(hostname2sslProviderBuilderMap);
	}

	/**
	 * Remove multi hostname-to-sslProviderBuilder at one time.
	 * This method can be invoked after the server have been started.
	 *
	 * @param hostnames host name (optionally wildcard)
	 */
	public void remove(String... hostnames) {
		for (String hostname : hostnames) {
			sslProviderMap.remove(hostname);
		}

		refreshDomainNameMapping();
	}

	private void refreshDomainNameMapping() {
		DomainNameMappingBuilder<SslContext> builder = new DomainNameMappingBuilder<>(defaultSslProvider.getSslContext());
		for (Map.Entry<String, SslProvider> entry : sslProviderMap.entrySet()) {
			builder.add(entry.getKey(), entry.getValue().getSslContext());
		}

		domainNameMapping = new SslDomainNameMapping(builder.build(), defaultSslProvider.getSslContext());
	}

	public Mapping<String, SslContext> getDomainNameMapping() {
		return domainNameMapping;
	}

}
