/*
 * Copyright (c) 2011-Present VMware, Inc. or its affiliates, All Rights Reserved.
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

package reactor.netty.http.client;

import reactor.netty.http.HttpInfos;
import reactor.util.context.Context;

import javax.annotation.Nullable;

/**
 * @since 0.9.3
 */
public interface HttpClientInfos extends HttpInfos {

    /**
     * Return the current {@link Context} associated with the Mono/Flux exposed
     * via {@link HttpClient.ResponseReceiver#response()} or related terminating API.
     *
     * @return the current user {@link Context}
     */
    Context currentContext();

    /**
     * Return the previous redirections or empty array
     *
     * @return the previous redirections or empty array
     */
    String[] redirectedFrom();

    /**
     * Return the fully qualified URL of the requested resource. In case of redirects, return the URL the last
     * redirect led to.
     *
     * @return The URL of the retrieved resource. This method can return null in case there was an error before the
     * client could create the URL
     */
    @Nullable
    String resourceUrl();
}
