/*
 * Copyright (c) 2023 VMware, Inc. or its affiliates, All Rights Reserved.
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
package reactor.netty.http;




import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;

public class QueryStringDecoderTests {


	@Test
	public void testBasicUrls() {
		Map<String, List<String>> parameters = QueryStringDecoder.decodeParams("http://localhost/path");
		Assertions.assertThat(parameters.size()).isEqualTo(0);
	}

	@Test
	public void testBasic() {
		Map<String, List<String>> parameters = QueryStringDecoder.decodeParams("/foo");
		Assertions.assertThat(parameters.size()).isEqualTo(0);

		parameters = QueryStringDecoder.decodeParams("/foo%20bar");
		Assertions.assertThat(parameters.size()).isEqualTo(0);

		parameters = QueryStringDecoder.decodeParams("/foo?a=b=c");
		Assertions.assertThat(parameters.size()).isEqualTo(1);
		Assertions.assertThat(parameters.get("a").size()).isEqualTo(1);
		Assertions.assertThat(parameters.get("a").get(0)).isEqualTo("b=c");

		parameters = QueryStringDecoder.decodeParams("/foo?a=1&a=2");
		Assertions.assertThat(parameters.size()).isEqualTo(1);
		Assertions.assertThat(parameters.get("a").size()).isEqualTo(2);
		Assertions.assertThat(parameters.get("a").get(0)).isEqualTo("1");
		Assertions.assertThat(parameters.get("a").get(1)).isEqualTo("2");

		parameters = QueryStringDecoder.decodeParams("/foo%20bar?a=1&a=2");
		Assertions.assertThat(parameters.size()).isEqualTo(1);
		Assertions.assertThat(parameters.get("a").size()).isEqualTo(2);
		Assertions.assertThat(parameters.get("a").get(0)).isEqualTo("1");
		Assertions.assertThat(parameters.get("a").get(1)).isEqualTo("2");

		parameters = QueryStringDecoder.decodeParams("/foo?a=&a=2");
		Assertions.assertThat(parameters.size()).isEqualTo(1);
		Assertions.assertThat(parameters.get("a").size()).isEqualTo(2);
		Assertions.assertThat(parameters.get("a").get(0)).isEqualTo("");
		Assertions.assertThat(parameters.get("a").get(1)).isEqualTo("2");

		parameters = QueryStringDecoder.decodeParams("/foo?a=1&a=");
		Assertions.assertThat(parameters.size()).isEqualTo(1);
		Assertions.assertThat(parameters.get("a").size()).isEqualTo(2);
		Assertions.assertThat(parameters.get("a").get(0)).isEqualTo("1");
		Assertions.assertThat(parameters.get("a").get(1)).isEqualTo("");

		parameters = QueryStringDecoder.decodeParams("/foo?a=1&a=&a=");
		Assertions.assertThat(parameters.size()).isEqualTo(1);
		Assertions.assertThat(parameters.get("a").size()).isEqualTo(3);
		Assertions.assertThat(parameters.get("a").get(0)).isEqualTo("1");
		Assertions.assertThat(parameters.get("a").get(1)).isEqualTo("");
		Assertions.assertThat(parameters.get("a").get(2)).isEqualTo("");

		parameters = QueryStringDecoder.decodeParams("/foo?a=1=&a==2");
		Assertions.assertThat(parameters.size()).isEqualTo(1);
		Assertions.assertThat(parameters.get("a").size()).isEqualTo(2);
		Assertions.assertThat(parameters.get("a").get(0)).isEqualTo("1=");
		Assertions.assertThat(parameters.get("a").get(1)).isEqualTo("=2");

		parameters = QueryStringDecoder.decodeParams("/foo?abc=1%2023&abc=124%20");
		Assertions.assertThat(parameters.size()).isEqualTo(1);
		Assertions.assertThat(parameters.get("abc").size()).isEqualTo(2);
		Assertions.assertThat(parameters.get("abc").get(0)).isEqualTo("1 23");
		Assertions.assertThat(parameters.get("abc").get(1)).isEqualTo("124 ");

		parameters = QueryStringDecoder.decodeParams("/foo?abc=%7E");
		Assertions.assertThat(parameters.get("abc").get(0)).isEqualTo("~");

		parameters = QueryStringDecoder.decodeParams("/foo?%23abc=%7E");
		Assertions.assertThat(parameters.get("#abc").get(0)).isEqualTo("~");

	}


	@Test
	public void testSemicolon() {
		Map<String, List<String>> parameters = QueryStringDecoder.decodeParams("/foo?a=1;2", false);
		Assertions.assertThat(parameters.size()).isEqualTo(2);
		Assertions.assertThat(parameters.get("a").size()).isEqualTo(1);
		Assertions.assertThat(parameters.get("a").get(0)).isEqualTo("1");
		Assertions.assertThat(parameters.get("2").size()).isEqualTo(1);
		Assertions.assertThat(parameters.get("2").get(0)).isEqualTo("");

		parameters = QueryStringDecoder.decodeParams("/foo?a=1;2");
		Assertions.assertThat(parameters.size()).isEqualTo(1);
		Assertions.assertThat(parameters.get("a").size()).isEqualTo(1);
		Assertions.assertThat(parameters.get("a").get(0)).isEqualTo("1;2");

		parameters = QueryStringDecoder.decodeParams("/foo?abc=1;abc=2", false);
		Assertions.assertThat(parameters.size()).isEqualTo(1);
		Assertions.assertThat(parameters.get("abc").size()).isEqualTo(2);
		Assertions.assertThat(parameters.get("abc").get(1)).isEqualTo("2");

		parameters = QueryStringDecoder.decodeParams("/foo", false);
		Assertions.assertThat(parameters.size()).isEqualTo(0);

		parameters = QueryStringDecoder.decodeParams("/foo%20bar", false);
		Assertions.assertThat(parameters.size()).isEqualTo(0);

		parameters = QueryStringDecoder.decodeParams("/foo?a=b=c", false);
		Assertions.assertThat(parameters.size()).isEqualTo(1);
		Assertions.assertThat(parameters.get("a").size()).isEqualTo(1);
		Assertions.assertThat(parameters.get("a").get(0)).isEqualTo("b=c");

		parameters = QueryStringDecoder.decodeParams("/foo?a=1;a=2", false);
		Assertions.assertThat(parameters.size()).isEqualTo(1);
		Assertions.assertThat(parameters.get("a").size()).isEqualTo(2);
		Assertions.assertThat(parameters.get("a").get(0)).isEqualTo("1");
		Assertions.assertThat(parameters.get("a").get(1)).isEqualTo("2");

		parameters = QueryStringDecoder.decodeParams("/foo%20bar?a=1;a=2", false);
		Assertions.assertThat(parameters.size()).isEqualTo(1);
		Assertions.assertThat(parameters.get("a").size()).isEqualTo(2);
		Assertions.assertThat(parameters.get("a").get(0)).isEqualTo("1");
		Assertions.assertThat(parameters.get("a").get(1)).isEqualTo("2");

		parameters = QueryStringDecoder.decodeParams("/foo?a=;a=2", false);
		Assertions.assertThat(parameters.size()).isEqualTo(1);
		Assertions.assertThat(parameters.get("a").size()).isEqualTo(2);
		Assertions.assertThat(parameters.get("a").get(0)).isEqualTo("");
		Assertions.assertThat(parameters.get("a").get(1)).isEqualTo("2");

		parameters = QueryStringDecoder.decodeParams("/foo?a=1;a=", false);
		Assertions.assertThat(parameters.size()).isEqualTo(1);
		Assertions.assertThat(parameters.get("a").size()).isEqualTo(2);
		Assertions.assertThat(parameters.get("a").get(0)).isEqualTo("1");
		Assertions.assertThat(parameters.get("a").get(1)).isEqualTo("");

		parameters = QueryStringDecoder.decodeParams("/foo?a=1;a=;a=", false);
		Assertions.assertThat(parameters.size()).isEqualTo(1);
		Assertions.assertThat(parameters.get("a").size()).isEqualTo(3);
		Assertions.assertThat(parameters.get("a").get(0)).isEqualTo("1");
		Assertions.assertThat(parameters.get("a").get(1)).isEqualTo("");
		Assertions.assertThat(parameters.get("a").get(2)).isEqualTo("");

		parameters = QueryStringDecoder.decodeParams("/foo?a=1=;a==2", false);
		Assertions.assertThat(parameters.size()).isEqualTo(1);
		Assertions.assertThat(parameters.get("a").size()).isEqualTo(2);
		Assertions.assertThat(parameters.get("a").get(0)).isEqualTo("1=");
		Assertions.assertThat(parameters.get("a").get(1)).isEqualTo("=2");

		parameters = QueryStringDecoder.decodeParams("/foo?abc=1%2023;abc=124%20", false);
		Assertions.assertThat(parameters.size()).isEqualTo(1);
		Assertions.assertThat(parameters.get("abc").size()).isEqualTo(2);
		Assertions.assertThat(parameters.get("abc").get(0)).isEqualTo("1 23");
		Assertions.assertThat(parameters.get("abc").get(1)).isEqualTo("124 ");

		parameters = QueryStringDecoder.decodeParams("/foo?abc=%7E", false);
		Assertions.assertThat(parameters.get("abc").get(0)).isEqualTo("~");
	}

	@Test
	public void testFragment() {
		Map<String, List<String>> parameters = QueryStringDecoder.decodeParams("/foo?a=1&b=2#anchor");
		Assertions.assertThat(parameters.size()).isEqualTo(2);
		Assertions.assertThat(parameters.get("a").size()).isEqualTo(1);
		Assertions.assertThat(parameters.get("a").get(0)).isEqualTo("1");
		Assertions.assertThat(parameters.get("b").size()).isEqualTo(1);
		Assertions.assertThat(parameters.get("b").get(0)).isEqualTo("2");

		parameters = QueryStringDecoder.decodeParams("/foo?a=1;b=2#anchor", false);
		Assertions.assertThat(parameters.size()).isEqualTo(2);
		Assertions.assertThat(parameters.get("a").size()).isEqualTo(1);
		Assertions.assertThat(parameters.get("a").get(0)).isEqualTo("1");
		Assertions.assertThat(parameters.get("b").size()).isEqualTo(1);
		Assertions.assertThat(parameters.get("b").get(0)).isEqualTo("2");

		parameters = QueryStringDecoder.decodeParams("/foo/#anchor?a=1;b=2#anchor");
		Assertions.assertThat(parameters.size()).isEqualTo(0);
	}

	@Test
	public void testHashDos() {
		StringBuilder buf = new StringBuilder();
		buf.append('?');
		for (int i = 0; i < 65536; i++) {
			buf.append('k');
			buf.append(i);
			buf.append("=v");
			buf.append(i);
			buf.append('&');
		}
		Assertions.assertThat(QueryStringDecoder.decodeParams(buf.toString()).size()).isEqualTo(1024);
	}

	@Test
	public void testNullUri() {
		Assertions.assertThatExceptionOfType(NullPointerException.class)
				.isThrownBy(() -> QueryStringDecoder.decodeParams(null));
	}

	@Test
	public void testNullCharset() {
		Assertions.assertThatExceptionOfType(NullPointerException.class)
				.isThrownBy(() -> QueryStringDecoder.decodeParams("/foo", null, 1024, true));
	}

	@Test
	public void testNegativeMaxNumberOfParameters() {
		Assertions.assertThatExceptionOfType(IllegalArgumentException.class)
				.isThrownBy(() -> QueryStringDecoder.decodeParams("/foo", StandardCharsets.UTF_8, 0, true));
	}

	@Test
	public void testUnterminatedEncodedValue() {
		Assertions.assertThatExceptionOfType(IllegalArgumentException.class)
				.isThrownBy(() -> QueryStringDecoder.decodeParams("/foo?id=1%"));
	}

	@Test
	public void testUnterminatedEncodedName() {
		Assertions.assertThatExceptionOfType(IllegalArgumentException.class)
				.isThrownBy(() -> QueryStringDecoder.decodeParams("/foo?%id=1"));
	}
}
