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

package reactor.netty;

import reactor.blockhound.BlockHound;
import reactor.blockhound.integration.BlockHoundIntegration;

import java.util.concurrent.ScheduledThreadPoolExecutor;

public class NettyTestBlockHoundIntegration implements BlockHoundIntegration {
    @Override
    public void applyTo(BlockHound.Builder builder) {
        // Calls blocking SecureRandom.next
        builder.allowBlockingCallsInside("java.nio.file.Files", "createTempFile");

        // System.out.println
        builder.allowBlockingCallsInside("java.io.PrintStream", "println");

        builder.allowBlockingCallsInside("java.util.concurrent.ConcurrentHashMap", "initTable");

        // TODO remove once BlockHound adds Thread#run by default
        builder.disallowBlockingCallsInside("java.lang.Thread", "run");
        builder.allowBlockingCallsInside(ScheduledThreadPoolExecutor.class.getName() + "$DelayedWorkQueue", "take");
    }
}
