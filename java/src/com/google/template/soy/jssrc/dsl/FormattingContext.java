/*
 * Copyright 2016 Google Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.template.soy.jssrc.dsl;

import com.google.common.base.Preconditions;
import com.google.common.base.Strings;
import com.google.errorprone.annotations.CanIgnoreReturnValue;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;
import javax.annotation.Nullable;

/**
 * Helper class to keep track of state during a single call to {@link CodeChunk#getCode()},
 * including the initial statements that have already been formatted and the current indentation
 * level.
 */
class FormattingContext implements AutoCloseable {
  private static final int MAX_LINE_LENGTH = 80;
  private final StringBuilder buf;
  private final int initialSize;

  private Scope curScope = new Scope(/* parent= */ null, /* emitClosingBrace= */ false);
  private String curIndent;
  private boolean nextAppendShouldStartNewLine = false;
  private boolean applyTsxLineBreaks = false;
  private final ArrayDeque<InterpolationKind> interpolationKindStack;

  public enum InterpolationKind {
    TSX,
    TTL,
  }

  FormattingContext() {
    this(/* startingIndent= */ 0);
  }

  /** @param startingIndent The number of columns to consider the "baseline" indentation level. */
  FormattingContext(int startingIndent) {
    curIndent = Strings.repeat(" ", startingIndent);
    buf = new StringBuilder(curIndent);
    initialSize = curIndent.length();
    interpolationKindStack = new ArrayDeque<>();
    interpolationKindStack.push(InterpolationKind.TSX);
  }

  /**
   * Returns a buffering context that will not insert any line breaks or indents automatically. The
   * contents of the buffer will be appended to the main context as a single string on close.
   */
  FormattingContext buffer() {
    FormattingContext parent = this;
    return new FormattingContext() {
      @Override
      public void close() {
        String buffer = this.toString();
        parent.append(buffer);
      }
    };
  }

  /**
   * If {@code applyTsxLineBreaks} is enabled, breaks lines to keep line length under 80 chars, and
   * does things like split lines between curly/angle braces, to try to make gencode more readable.
   * TODO: Either figure out a more long-term / sophisticated line breaking strategy, or if we want
   * to just run the gencode through a formatter.
   */
  void enableTsxLineBreaks() {
    applyTsxLineBreaks = true;
  }

  void pushInterpolationKind(InterpolationKind interpolationKind) {
    interpolationKindStack.push(interpolationKind);
  }

  void popInterpolationKind() {
    interpolationKindStack.pop();
  }

  InterpolationKind getCurrentInterpolationKind() {
    return interpolationKindStack.peek();
  }

  String getInterpolationOpenString() {
    return getCurrentInterpolationKind() == InterpolationKind.TSX ? "{" : "${";
  }

  @CanIgnoreReturnValue
  FormattingContext append(String stuff) {
    maybeBreakLineInsideTsxElement(stuff);
    maybeIndent(/* nextCharIsSpace= */ !stuff.isEmpty() && stuff.charAt(0) == ' ');
    buf.append(stuff);
    return this;
  }

  @CanIgnoreReturnValue
  FormattingContext appendForeignCode(String stuff) {
    stuff = stuff.replaceAll("\n", "\n" + curIndent);
    append(stuff);
    return this;
  }

  @CanIgnoreReturnValue
  FormattingContext append(char c) {
    maybeBreakLineInsideTsxElement(Character.toString(c));
    maybeIndent(c == ' ');
    buf.append(c);
    return this;
  }

  void appendBlankLine() {
    endLine();
    append("");
    endLine();
  }

  /** Writes the jsdoc for the {@code jsDoc} to the buffer. */
  @CanIgnoreReturnValue
  FormattingContext append(JsDoc jsDoc) {
    jsDoc.doFormatJsDoc(this);
    return this;
  }

  /**
   * Writes the initial statements for the {@code chunk} to the buffer. This is the only allowed
   * direct caller of {@link CodeChunk#doFormatInitialStatements}.
   */
  @CanIgnoreReturnValue
  FormattingContext appendInitialStatements(CodeChunk chunk) {
    if (shouldFormat(chunk)) {
      chunk.doFormatInitialStatements(this);
    }
    return this;
  }

  /** Writes the output expression for the {@code value} to the buffer. */
  @CanIgnoreReturnValue
  FormattingContext appendOutputExpression(Expression value) {
    value.doFormatOutputExpr(this);
    return this;
  }

  /** Writes all code for the {@code chunk} to the buffer. */
  @CanIgnoreReturnValue
  FormattingContext appendAll(CodeChunk chunk) {
    appendInitialStatements(chunk);
    if (chunk instanceof Expression) {
      appendOutputExpression((Expression) chunk);
      append(";");
      endLine();
    }
    return this;
  }

  private boolean shouldFormat(CodeChunk chunk) {
    boolean shouldFormat = !curScope.alreadyFormatted(chunk);
    if (shouldFormat) {
      curScope.formatted.add(chunk);
    }
    return shouldFormat;
  }

  @CanIgnoreReturnValue
  FormattingContext enterBlock() {
    maybeIndent(/* nextCharIsSpace= */ false);
    buf.append('{');
    increaseIndent();
    endLine();
    curScope = new Scope(curScope, /* emitClosingBrace= */ true);
    return this;
  }

  /**
   * For use only by {@link Switch#doFormatInitialStatements}. It's not an error for bodies of case
   * clauses to be brace-delimited, but it is slightly less readable, so omit them.
   */
  @CanIgnoreReturnValue
  FormattingContext enterCaseBody() {
    maybeIndent(false);
    increaseIndent();
    endLine();
    curScope = new Scope(curScope, /* emitClosingBrace= */ false);
    return this;
  }

  @CanIgnoreReturnValue
  FormattingContext endLine() {
    // To prevent spurious trailing whitespace, don't actually write the newline
    // until the next call to append().
    nextAppendShouldStartNewLine = true;
    return this;
  }

  char getLastChar() {
    return buf.length() == 0 ? '\0' : buf.charAt(buf.length() - 1);
  }

  private void maybeBreakLineInsideTsxElement(String nextAppendContent) {
    if (!applyTsxLineBreaks || buf.length() == 0) {
      return;
    }
    if (isRightAngleOrCurlyBracket(getLastChar())
        && isLeftAngleOrCurlyBracket(
            nextAppendContent.isEmpty() ? ' ' : nextAppendContent.charAt(0))) {
      endLine();
      return;
    }

    if (!fitsOnCurrentLine(nextAppendContent)) {
      endLine();
      return;
    }
  }

  /**
   * Return whether a code string can be appended to the current line without going over
   * MAX_LINE_LENGTH.
   */
  private boolean fitsOnCurrentLine(String stuff) {
    int lastNewLine = buf.lastIndexOf("\n");
    int currentLineLength =
        lastNewLine < 0
            ? buf.length()
            : lastNewLine == buf.length() - 1 ? 0 : buf.length() - lastNewLine;
    return currentLineLength + stuff.length() < MAX_LINE_LENGTH;
  }

  /**
   * If this is the first call to {@link #append} since the last {@link #endLine}, writes the
   * newline and leading indentation.
   *
   * @param nextCharIsSpace Whether the first charater of the appended content will be a space char.
   */
  private void maybeIndent(boolean nextCharIsSpace) {
    boolean prevCharIsSpace = buf.length() > 0 && (buf.charAt(buf.length() - 1) == ' ');
    // TSX safeguard: it's never safe to break a line when there's a space character at the join
    // location.
    if (applyTsxLineBreaks && (nextCharIsSpace || prevCharIsSpace)) {
      nextAppendShouldStartNewLine = false;
    }

    if (nextAppendShouldStartNewLine) {
      buf.append('\n').append(curIndent);
      nextAppendShouldStartNewLine = false;
    }
  }

  /** Increases the indent by the given number of times, where each indent is two spaces. */
  @CanIgnoreReturnValue
  FormattingContext increaseIndent(int numIndents) {
    for (int i = 0; i < numIndents; i++) {
      curIndent += "  ";
    }
    return this;
  }

  /** Increases the indent once (two spaces). */
  FormattingContext increaseIndent() {
    return increaseIndent(1);
  }

  /** Decreases the indent by the given number of times, where each indent is two spaces. */
  @CanIgnoreReturnValue
  FormattingContext decreaseIndent(int numIndents) {
    for (int i = 0; i < numIndents; i++) {
      Preconditions.checkState(!curIndent.isEmpty());
      curIndent = curIndent.substring(2);
    }
    return this;
  }

  /** Decreases the indent once (two spaces). */
  FormattingContext decreaseIndent() {
    return decreaseIndent(1);
  }

  @Override
  public String toString() {
    return isEmpty() ? "" : buf.toString();
  }

  boolean isEmpty() {
    return buf.length() == initialSize;
  }

  @Override
  public void close() {
    boolean emitClosingBrace = curScope.emitClosingBrace;
    curScope = Preconditions.checkNotNull(curScope.parent);
    decreaseIndent();
    endLine();
    if (emitClosingBrace) {
      append('}');
    }
  }

  /**
   * Returns a FormattingContext representing the concatenation of this FormattingContext with
   * {@code other}. For use only by {@link CodeChunk#getCode(int, OutputContext)}.
   */
  FormattingContext concat(FormattingContext other) {
    if (isEmpty()) {
      return other;
    } else if (other.isEmpty()) {
      return this;
    } else {
      curIndent = ""; // don't serialize trailing whitespace in front of the next FormattingContext.
      return append(other.toString());
    }
  }

  /**
   * {@link FormattingContext} needs to keep track of the conditional nesting structure
   * in order to avoid, for example, formatting the initial statements of a code chunk
   * in one branch and referencing the chunk in another. The scopes form a simple tree,
   * built and torn down by {@link #enterBlock()} and {@link #close()} respectively.
   * {@link FormattingContext#curScope} points to the current tip of the tree.
   */
  private static final class Scope {
    final Set<CodeChunk> formatted = Collections.newSetFromMap(new IdentityHashMap<>());
    @Nullable
    final Scope parent;
    final boolean emitClosingBrace;

    Scope(@Nullable Scope parent, boolean emitClosingBrace) {
      this.parent = parent;
      this.emitClosingBrace = emitClosingBrace;
    }

    boolean alreadyFormatted(CodeChunk chunk) {
      return formatted.contains(chunk) || (parent != null && parent.alreadyFormatted(chunk));
    }
  }

  private static boolean isLeftAngleOrCurlyBracket(char c) {
    return c == '<' || c == '{';
  }

  private static boolean isRightAngleOrCurlyBracket(char c) {
    return c == '>' || c == '}';
  }
}
