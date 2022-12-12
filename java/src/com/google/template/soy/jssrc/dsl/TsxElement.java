/*
 * Copyright 2022 Google Inc.
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

import static com.google.common.base.Preconditions.checkState;

import com.google.auto.value.AutoValue;
import com.google.common.collect.ImmutableList;
import com.google.errorprone.annotations.Immutable;
import com.google.template.soy.jssrc.restricted.JsExpr;
import java.util.function.Consumer;

/** Represents a tsx elemenet, e.g.: "<div>body</div>". */
@AutoValue
@Immutable
public abstract class TsxElement extends Expression {

  abstract HtmlTag openTag();

  abstract HtmlTag closeTag();

  abstract ImmutableList<Statement> body();

  public static Expression create(
      HtmlTag openTag, HtmlTag closeTag, ImmutableList<Statement> body) {
    checkState(openTag.tagName().equals(closeTag.tagName()));
    checkState(openTag.isOpen());
    checkState(closeTag.isClose());
    return new AutoValue_TsxElement(
        /* initialStatements= */ ImmutableList.of(), openTag, closeTag, body);
  }

  @Override
  public boolean isCheap() {
    return true;
  }

  @Override
  void doFormatInitialStatements(FormattingContext ctx) {}

  @Override
  void doFormatOutputExpr(FormattingContext ctx) {
    ctx.appendAll(openTag());
    for (Statement s : body()) {
      ctx.appendAll(s);
    }
    ctx.appendAll(closeTag());
  }

  @Override
  public JsExpr singleExprOrName() {
    FormattingContext ctx = new FormattingContext();
    ctx.appendOutputExpression(this);
    return new JsExpr(ctx.toString(), Integer.MAX_VALUE);
  }

  @Override
  public void collectRequires(Consumer<GoogRequire> collector) {
    openTag().collectRequires(collector);
    for (Statement s : body()) {
      s.collectRequires(collector);
    }
    closeTag().collectRequires(collector);
  }
}
