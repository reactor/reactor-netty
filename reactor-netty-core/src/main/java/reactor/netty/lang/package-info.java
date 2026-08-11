/*
 * Copyright (c) 2026 VMware, Inc. or its affiliates, All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

/**
 * Common annotations with language-level semantics: nullability as well as JDK API indications.
 * These annotations sit at the lowest level of Reactor Netty's package dependency arrangement, even
 * lower than {@code reactor.netty.internal.util}, with no Reactor Netty-specific concepts implied.
 *
 * <p>Used descriptively within the codebase. Can be validated by build-time tools
 * (for example, FindBugs or Animal Sniffer), alternative JVM languages (for example, Kotlin), as well as IDEs
 * (for example, IntelliJ IDEA or Eclipse with corresponding project setup).
 */
@NullMarked
package reactor.netty.lang;

import org.jspecify.annotations.NullMarked;
