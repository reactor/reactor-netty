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
package reactor.netty.lang;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Specifies that the return value of the annotated method can be ignored.
 *
 * <p>This annotation overrides an enclosing {@link CheckReturnValue} annotation:
 * when a type is annotated with {@link CheckReturnValue}, a method within that type
 * can be annotated with this annotation to indicate that its return value is only
 * <i>additional</i> information and callers are free to discard it. Static analysis
 * tools such as Error Prone and IntelliJ IDEA apply the nearest annotation:
 * a method-level annotation wins over a type-level one.
 *
 * <p>Inspired by {@code com.google.errorprone.annotations.CanIgnoreReturnValue} and
 * {@code org.assertj.core.annotations.CanIgnoreReturnValue}, this variant has been
 * introduced in the {@code reactor.netty.lang} package to avoid requiring an extra
 * dependency, while still following similar semantics.
 *
 * @author Filip Hrisafov
 * @since 1.4.0
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD, ElementType.CONSTRUCTOR})
public @interface CanIgnoreReturnValue {
}
