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

import com.google.auto.value.AutoValue;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.errorprone.annotations.Immutable;
import com.google.template.soy.jssrc.restricted.JsExpr;
import java.util.function.Consumer;

/**
 * Holds {@link JsExpr expressions}. These chunks can always be represented as single expressions
 * (namely, the {@link #value} from which they are built).
 */
@AutoValue
@Immutable
abstract class Leaf extends Expression {
  static Leaf create(String text, boolean isCheap, Iterable<GoogRequire> require) {
    return create(
        new JsExpr(text, Integer.MAX_VALUE),
        isCheap,
        ImmutableSet.copyOf(require),
        /* initialExpressionIsObjectLiteral=*/ false);
  }

  static Leaf create(String text, boolean isCheap) {
    return create(text, isCheap, ImmutableSet.<GoogRequire>of());
  }

  static Leaf create(JsExpr value, boolean isCheap, Iterable<GoogRequire> requires) {
    return create(value, isCheap, requires, /* initialExpressionIsObjectLiteral=*/ true);
  }

  private static Leaf create(
      JsExpr value,
      boolean isCheap,
      Iterable<GoogRequire> requires,
      boolean initialExpressionIsObjectLiteral) {
    return new AutoValue_Leaf(
        /* initialStatements= */ ImmutableList.of(),
        value,
        ImmutableSet.copyOf(requires),
        isCheap,
        initialExpressionIsObjectLiteral);
  }

  abstract JsExpr value();

  abstract ImmutableSet<GoogRequire> requires();

  @Override
  public abstract boolean isCheap();

  @Override
  void doFormatInitialStatements(FormattingContext ctx) {
    // nothing to do
  }

  @Override
  void doFormatOutputExpr(FormattingContext ctx) {
    ctx.appendForeignCode(value().getText());
  }

  @Override
  public JsExpr singleExprOrName() {
    return value();
  }

  @Override
  public void collectRequires(Consumer<GoogRequire> collector) {
    for (GoogRequire require : requires()) {
      collector.accept(require);
    }
  }

  @Override
  abstract boolean initialExpressionIsObjectLiteral();
}
