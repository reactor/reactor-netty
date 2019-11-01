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

import io.netty.handler.ssl.SslContext;
import io.netty.util.DomainNameMapping;
import io.netty.util.Mapping;

import java.net.IDN;
import java.util.Locale;
import java.util.Map;

/**
 * Maps a domain name to its associated value object.
 * <p>
 * Wildcard is supported as hostname, so you can use {@code *.demo.com} to match both {@code a.demo.com}
 * and {@code b.demo.com}, but neither {@code demo.com}, nor {@code a.b.demo.com}.
 * </p>
 * <br/>
 * (netty's {@link DomainNameMapping} can use {@code *.demo.com} to match {@code demo.com} and {@code a.b.demo.com},
 * it is not suitable for sni, this is why this current class is needed.)
 *
 * @author aftersss
 */
class SslDomainNameMapping implements Mapping<String, SslContext> {

    private DomainNameMapping<SslContext> delegate;
    private SslContext defaultSslContext;

    public SslDomainNameMapping(DomainNameMapping<SslContext> delegate, SslContext defaultSslContext) {
        this.delegate = delegate;
        this.defaultSslContext = defaultSslContext;
    }

    @Override
    public SslContext map(String hostname) {
        if (hostname != null && delegate != null) {
            hostname = normalizeHostname(hostname);
            Map<String, SslContext> map = delegate.asMap();
            if (map == null) {
                return defaultSslContext;
            }
            for (Map.Entry<String, SslContext> entry : map.entrySet()) {
                if (matches(entry.getKey(), hostname)) {
                    return entry.getValue();
                }
            }
        }

        return defaultSslContext;
    }

    /**
     * Simple function to match <a href="http://en.wikipedia.org/wiki/Wildcard_DNS_record">DNS wildcard</a>.
     */
    static boolean matches(String template, String hostName) {
        if (template.startsWith("*.")) {
            //只匹配同一级
            String hostNameReplaced = "*" + hostName.substring(hostName.indexOf('.'));
            return template.equals(hostNameReplaced);
        }

        return template.equals(hostName);
    }

    /**
     * copy from {@link DomainNameMapping#normalizeHostname(String)}
     *
     * IDNA ASCII conversion and case normalization
     */
    static String normalizeHostname(String hostname) {
        if (needsNormalization(hostname)) {
            hostname = IDN.toASCII(hostname, IDN.ALLOW_UNASSIGNED);
        }
        return hostname.toLowerCase(Locale.US);
    }

    private static boolean needsNormalization(String hostname) {
        final int length = hostname.length();
        for (int i = 0; i < length; i++) {
            int c = hostname.charAt(i);
            if (c > 0x7F) {
                return true;
            }
        }
        return false;
    }
}
