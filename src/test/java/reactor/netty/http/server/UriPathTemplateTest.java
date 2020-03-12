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

import java.util.Arrays;

import org.junit.Test;
import reactor.netty.http.server.HttpPredicate.UriPathTemplate;

import static org.hamcrest.CoreMatchers.allOf;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.hasEntry;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class UriPathTemplateTest {

    @Test
    public void patternShouldMatchPathWithOnlyLetters() {
        UriPathTemplate uriPathTemplate = new UriPathTemplate("/test/{order}");
        // works as expected
        assertThat(uriPathTemplate.match("/test/1").get("order"), is("1"));
    }

    @Test
    public void patternShouldMatchPathWithDots() {
        UriPathTemplate uriPathTemplate = new UriPathTemplate("/test/{order}");
        // does not match, the dot in the segment parameter breaks matching
        // expected: a map containing {"order": "2.0"}, found: empty map
        assertThat(uriPathTemplate.match("/test/2.0").get("order"), is("2.0"));
    }

    @Test
    public void staticPatternShouldMatchPathWithQueryParams() {
        UriPathTemplate uriPathTemplate = new UriPathTemplate("/test/3");
        // does not match, the query parameter breaks matching
        // expected: true, found: false
        assertTrue(uriPathTemplate.matches("/test/3?q=reactor"));
    }

    @Test
    public void parameterizedPatternShouldMatchPathWithQueryParams() {
        UriPathTemplate uriPathTemplate = new UriPathTemplate("/test/{order}");
        // does not match, the query parameter breaks matching
        // expected: a map containing {"order": "3"}, found: a map containing {"order": "3?q=reactor"}
        assertEquals("3",
                uriPathTemplate.match("/test/3?q=reactor")
                               .get("order"));
    }

    @Test
    public void staticPathShouldBeMatched() {
        UriPathTemplate template = new UriPathTemplate("/comments");
        assertThat(template.matches("/comments"), is(true));
        assertThat(template.match("/comments").entrySet(), empty());
    }
    @Test
    public void staticPathWithDotShouldBeMatched() {
        UriPathTemplate template = new UriPathTemplate("/1.0/comments");
        assertThat(template.matches("/1.0/comments"), is(true));
        assertThat(template.match("/1.0/comments").entrySet(), empty());
    }

    @Test
    public void parametrizedPathShouldBeMatched() {
        UriPathTemplate template = new UriPathTemplate("/comments/{id}");
        assertThat(template.matches("/comments/1"), is(true));
        assertThat(template.match("/comments/1"), hasEntry("id", "1"));
    }

    @Test
    public void parametrizedPathWithStaticSuffixShouldBeMatched() {
        UriPathTemplate template = new UriPathTemplate("/comments/{id}/author");
        assertThat(template.matches("/comments/1/author"), is(true));
        assertThat(template.match("/comments/1/author"), hasEntry("id", "1"));
    }

    @Test
    public void parametrizedPathWithMultipleParametersShouldBeMatched() {
        UriPathTemplate template = new UriPathTemplate("/{collection}/{id}");
        assertThat(template.matches("/comments/1"), is(true));
        assertThat(template.match("/comments/1"), allOf(Arrays.asList(hasEntry("id", "1"), hasEntry("collection", "comments"))));
    }

    @Test
    public void pathWithDotShouldBeMatched() {
        UriPathTemplate template = new UriPathTemplate("/tags/{tag}");
        assertThat(template.matches("/tags/v1.0.0"), is(true));
        assertThat(template.match("/tags/v1.0.0"), hasEntry("tag", "v1.0.0"));
    }

    @Test
    public void pathVariableShouldNotMatchTrailingSegments() {
        UriPathTemplate template = new UriPathTemplate("/tags/{tag}/commits");
        assertThat(template.matches("/tags/v1.0.0"), is(false));
        assertThat(template.match("/tags/v1.0.0").entrySet(), empty());
    }

}