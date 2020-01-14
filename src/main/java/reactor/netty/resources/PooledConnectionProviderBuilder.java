/*
 * Copyright (c) 2011-Present Pivotal Software Inc, All Rights Reserved.
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

package reactor.netty.resources;

/**
 * {@link PooledConnectionProvider} builder
 * @author Dmitrii Borin
 */
final public class PooledConnectionProviderBuilder {
    private String name;
    private PooledConnectionProvider.PoolFactory poolFactory;
    private long acquireTimeout;
    private int maxConnections;

    private PooledConnectionProviderBuilder() {
    }

    public static PooledConnectionProviderBuilder newInstance() {
        return new PooledConnectionProviderBuilder();
    }

    public PooledConnectionProvider build() {
        return new PooledConnectionProvider(name, poolFactory,
                maxConnections == PooledConnectionProvider.MAX_CONNECTIONS_ELASTIC ? 0 : acquireTimeout, maxConnections);
    }

    public PooledConnectionProviderBuilder name(String name) {
        this.name = name;
        return this;
    }

    public PooledConnectionProviderBuilder poolFactory(PooledConnectionProvider.PoolFactory poolFactory) {
        this.poolFactory = poolFactory;
        return this;
    }

    public PooledConnectionProviderBuilder acquireTimeout(long acquireTimeout) {
        this.acquireTimeout = acquireTimeout;
        return this;
    }

    public PooledConnectionProviderBuilder maxConnections(int maxConnections) {
        this.maxConnections = maxConnections;
        return this;
    }
}
