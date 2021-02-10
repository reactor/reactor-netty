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

package reactor.netty.http.server;

import org.junit.jupiter.api.Test;
import reactor.netty.http.server.HttpPredicate.UriPathTemplate;

import static org.assertj.core.api.Assertions.assertThat;

class UriPathTemplateTest {

    @Test
    void patternShouldMatchPathWithOnlyLetters() {
        UriPathTemplate uriPathTemplate = new UriPathTemplate("/test/{order}");
        // works as expected
        assertThat(uriPathTemplate.match("/test/1").get("order")).isEqualTo("1");
    }

    @Test
    void patternShouldMatchPathWithDots() {
        UriPathTemplate uriPathTemplate = new UriPathTemplate("/test/{order}");
        // does not match, the dot in the segment parameter breaks matching
        // expected: a map containing {"order": "2.0"}, found: empty map
        assertThat(uriPathTemplate.match("/test/2.0").get("order")).isEqualTo("2.0");
    }

    @Test
    void staticPatternShouldMatchPathWithQueryParams() {
        UriPathTemplate uriPathTemplate = new UriPathTemplate("/test/3");
        // does not match, the query parameter breaks matching
        // expected: true, found: false
        assertThat(uriPathTemplate.matches("/test/3?q=reactor")).isTrue();
    }

    @Test
    void parameterizedPatternShouldMatchPathWithQueryParams() {
        UriPathTemplate uriPathTemplate = new UriPathTemplate("/test/{order}");
        // does not match, the query parameter breaks matching
        // expected: a map containing {"order": "3"}, found: a map containing {"order": "3?q=reactor"}
        assertThat(uriPathTemplate.match("/test/3?q=reactor")
                               .get("order")).isEqualTo("3");
    }

    @Test
    void staticPathShouldBeMatched() {
        UriPathTemplate template = new UriPathTemplate("/comments");
        assertThat(template.matches("/comments")).isTrue();
        assertThat(template.match("/comments").entrySet()).isEmpty();
    }
    @Test
    void staticPathWithDotShouldBeMatched() {
        UriPathTemplate template = new UriPathTemplate("/1.0/comments");
        assertThat(template.matches("/1.0/comments")).isTrue();
        assertThat(template.match("/1.0/comments").entrySet()).isEmpty();
    }

    @Test
    void parametrizedPathShouldBeMatched() {
        UriPathTemplate template = new UriPathTemplate("/comments/{id}");
        assertThat(template.matches("/comments/1")).isTrue();
        assertThat(template.match("/comments/1")).hasEntrySatisfying("id", s -> assertThat(s).isEqualTo("1"));
    }

    @Test
    void parametrizedPathWithStaticSuffixShouldBeMatched() {
        UriPathTemplate template = new UriPathTemplate("/comments/{id}/author");
        assertThat(template.matches("/comments/1/author")).isTrue();
        assertThat(template.match("/comments/1/author")).hasEntrySatisfying("id", s -> assertThat(s).isEqualTo("1"));
    }

    @Test
    void parametrizedPathWithMultipleParametersShouldBeMatched() {
        UriPathTemplate template = new UriPathTemplate("/{collection}/{id}");
        assertThat(template.matches("/comments/1")).isTrue();
        assertThat(template.match("/comments/1")).hasEntrySatisfying("id", s -> assertThat(s).isEqualTo("1"));
        assertThat(template.match("/comments/1")).hasEntrySatisfying("collection", s -> assertThat(s).isEqualTo("comments"));
    }

    @Test
    void pathWithDotShouldBeMatched() {
        UriPathTemplate template = new UriPathTemplate("/tags/{tag}");
        assertThat(template.matches("/tags/v1.0.0")).isTrue();
        assertThat(template.match("/tags/v1.0.0")).hasEntrySatisfying("tag", s -> assertThat(s).isEqualTo("v1.0.0"));
    }

    @Test
    void pathVariableShouldNotMatchTrailingSegments() {
        UriPathTemplate template = new UriPathTemplate("/tags/{tag}/commits");
        assertThat(template.matches("/tags/v1.0.0")).isFalse();
        assertThat(template.match("/tags/v1.0.0").entrySet()).isEmpty();
    }

}