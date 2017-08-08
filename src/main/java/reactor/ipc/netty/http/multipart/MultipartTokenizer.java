/*
 * Copyright (c) 2011-2017 Pivotal Software Inc, All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package reactor.ipc.netty.http.multipart;

import java.nio.charset.Charset;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import org.reactivestreams.Subscription;
import reactor.core.CoreSubscriber;
import reactor.core.publisher.Operators;

/**
 * @author Ben Hale
 */
final class MultipartTokenizer
		implements CoreSubscriber<ByteBuf>, Subscription {

	static final char[] CRLF = new char[]{'\r', '\n'};

	static final char[] DOUBLE_DASH = new char[]{'-', '-'};

	final char[] boundary;

	final CoreSubscriber<? super Token> actual;

	int bodyPosition;

	Subscription subscription;

	int boundaryPosition;

	ByteBuf byteBuf;

	volatile boolean cancelled = false;

	int crlfPosition;

	int delimiterPosition;

	boolean done = false;

	int doubleDashPosition;

	int position;

	Stage stage;

	MultipartTokenizer(String boundary, CoreSubscriber<? super Token> subscriber) {
		this.actual = subscriber;
		this.boundary = boundary.toCharArray();
		reset();
	}

	@Override
	public final void onSubscribe(Subscription s) {
		if (Operators.validate(subscription, s)) {
				subscription = s;
				actual.onSubscribe(s);
		}
	}

	@Override
	public final void cancel() {
		if (this.cancelled) {
			return;
		}

		this.cancelled = true;
		Subscription s = this.subscription;
		if (s != null) {
			this.subscription = null;
			s.cancel();
		}
	}

	@Override
	public void onComplete() {
		if (this.done) {
			return;
		}

		this.done = true;
		this.actual.onComplete();
	}

	@Override
	public void onError(Throwable throwable) {
		if (this.done) {
			Operators.onErrorDropped(throwable);
			return;
		}

		this.done = true;
		this.actual.onError(throwable);
	}

	@Override
	public void onNext(ByteBuf byteBuf) {
		if (this.done) {
			Operators.onNextDropped(byteBuf);
			return;
		}

		this.byteBuf =
				this.byteBuf != null ? Unpooled.wrappedBuffer(this.byteBuf, byteBuf) :
						byteBuf;

		while (this.position < this.byteBuf.readableBytes()) {
			if (this.cancelled) {
				break;
			}

			char c = getChar();

			switch (this.stage) {
				case BODY:
					body(c);
					break;
				case BOUNDARY:
					boundary(c);
					break;
				case END_CRLF:
					endCrLf(c);
					break;
				case END_DOUBLE_DASH:
					endDoubleDash(c);
					break;
				case START_CRLF:
					startCrLf(c);
					break;
				case START_DOUBLE_DASH:
					startDoubleDash(c);
					break;
				case TRAILING_CRLF:
					trailingCrLf(c);
					break;
			}
		}

		if (!this.cancelled && Stage.BODY == this.stage) {
			pushTrailingBodyToken();
			reset();
		}
	}

	@Override
	public void request(long n) {
		if(!Operators.validate(n)){
			return;
		}
		try {

			if (Integer.MAX_VALUE > n) {  // TODO: Support smaller request sizes
				actual.onError(Operators.onOperatorError(this, new
						IllegalArgumentException(
						"This operation only supports unbounded requests, was " + n), n));
				return;
			}
			Subscription s = this.subscription;
			if (s != null) {
				s.request(n);
			}
		} catch (Throwable throwable) {
			actual.onError(Operators.onOperatorError(this, throwable, n));
		}
	}

	void body(char c) {
		if (CRLF[0] == c) {
			this.delimiterPosition = this.position;
			this.stage = Stage.START_CRLF;
			this.crlfPosition = 1;
			this.position++;
		}
		else if (DOUBLE_DASH[0] == c) {
			this.delimiterPosition = this.position;
			this.stage = Stage.START_DOUBLE_DASH;
			this.doubleDashPosition = 1;
			this.position++;
		}
		else {
			this.position++;
		}
	}

	void boundary(char c) {
		if (this.boundaryPosition < this.boundary.length) {
			if (this.boundary[this.boundaryPosition] == c) {
				this.boundaryPosition++;
				this.position++;
			}
			else {
				this.stage = Stage.BODY;
			}
		}
		else {
			if (CRLF[0] == c) {
				this.stage = Stage.END_CRLF;
				this.crlfPosition = 1;
				this.position++;
			}
			else if (DOUBLE_DASH[0] == c) {
				this.stage = Stage.END_DOUBLE_DASH;
				this.doubleDashPosition = 1;
				this.position++;
			}
			else {
				this.stage = Stage.BODY;
			}
		}
	}

	void endCrLf(char c) {
		if (this.crlfPosition < CRLF.length) {
			if (CRLF[this.crlfPosition] == c) {
				this.crlfPosition++;
				this.position++;
			}
			else {
				this.stage = Stage.BODY;
			}
		}
		else {
			if (CRLF[0] == c) {
				this.stage = Stage.TRAILING_CRLF;
				this.crlfPosition = 1;
				this.position++;
			}
			else {
				pushBodyToken();
				pushDelimiterToken();
			}
		}
	}

	void endDoubleDash(char c) {
		if (this.doubleDashPosition < DOUBLE_DASH.length) {
			if (DOUBLE_DASH[this.doubleDashPosition] == c) {
				this.doubleDashPosition++;
				this.position++;
			}
			else {
				this.stage = Stage.BODY;
			}
		}
		else {
			pushBodyToken();
			pushCloseDelimiterToken();
		}
	}

	char getChar() {
		return (char) (this.byteBuf.getByte(this.position) & 0xFF);
	}

	void pushBodyToken() {
		pushToken(TokenKind.BODY, this.bodyPosition, this.delimiterPosition);
		this.bodyPosition = this.position;
	}

	void pushCloseDelimiterToken() {
		pushToken(TokenKind.CLOSE_DELIMITER, this.delimiterPosition, this.position);
		this.stage = Stage.BODY;
	}

	void pushDelimiterToken() {
		pushToken(TokenKind.DELIMITER, this.delimiterPosition, this.position);
		this.stage = Stage.BODY;
	}

	void pushToken(TokenKind kind, int start, int end) {
		if (!this.cancelled && (end - start > 0)) {
			this.actual.onNext(new Token(kind, this.byteBuf, start, end - start));
		}
	}

	void pushTrailingBodyToken() {
		pushToken(TokenKind.BODY, this.bodyPosition, this.position);
	}

	void reset() {
		this.bodyPosition = 0;
		this.byteBuf = null;
		this.position = 0;
		this.stage = Stage.BODY;
	}

	void startCrLf(char c) {
		if (this.crlfPosition < CRLF.length) {
			if (CRLF[this.crlfPosition] == c) {
				this.crlfPosition++;
				this.position++;
			}
			else {
				this.stage = Stage.BODY;
			}
		}
		else {
			if (DOUBLE_DASH[0] == c) {
				this.stage = Stage.START_DOUBLE_DASH;
				this.doubleDashPosition = 1;
				this.position++;
			}
			else {
				this.stage = Stage.BODY;
			}
		}
	}

	void startDoubleDash(char c) {
		if (this.doubleDashPosition < DOUBLE_DASH.length) {
			if (DOUBLE_DASH[this.doubleDashPosition] == c) {
				this.doubleDashPosition++;
				this.position++;
			}
			else {
				this.stage = Stage.BODY;
			}
		}
		else {
			if (this.boundary[0] == c) {
				this.stage = Stage.BOUNDARY;
				this.boundaryPosition = 1;
				this.position++;
			}
			else {
				this.stage = Stage.BODY;
			}
		}
	}

	void trailingCrLf(char c) {
		if (this.crlfPosition < CRLF.length) {
			if (CRLF[this.crlfPosition] == c) {
				this.crlfPosition++;
				this.position++;
			}
			else {
				this.stage = Stage.BODY;
			}
		}
		else {
			pushBodyToken();
			pushDelimiterToken();
		}
	}

	enum Stage {

		BODY,

		BOUNDARY,

		END_CRLF,

		END_DOUBLE_DASH,

		START_CRLF,

		START_DOUBLE_DASH,

		TRAILING_CRLF

	}

	static final class Token {

		final ByteBuf byteBuf;

		final TokenKind kind;

		final int length;

		final int offset;

		Token(TokenKind kind, ByteBuf byteBuf, int offset, int length) {
			this.byteBuf = byteBuf;
			this.kind = kind;
			this.length = length;
			this.offset = offset;
		}

		@Override
		public String toString() {
			return String.format("Token: %s, offset=%d, length=%d, '%s'",
					this.kind,
					this.offset,
					this.length,
					expandWhitespace(this.byteBuf.toString(this.offset,
							this.length,
							Charset.defaultCharset())));
		}

		ByteBuf getByteBuf() {
			return this.byteBuf.slice(this.offset, this.length);
		}

		TokenKind getKind() {
			return this.kind;
		}

		static String expandWhitespace(String s) {
			return s.replaceAll("\r", "\\\\r")
			        .replaceAll("\n", "\\\\n")
			        .replaceAll("\t", "\\\\t");
		}
	}

	enum TokenKind {

		BODY,

		CLOSE_DELIMITER,

		DELIMITER

	}
}