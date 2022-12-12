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

import com.google.common.collect.ImmutableList;
import com.google.template.soy.jssrc.restricted.JsExpr;
import java.util.function.Consumer;

/**
 * Represents a TS arrow function expression.
 *
 * <p>Example:
 *
 * <p><code>{@literal
 * (param: string): string => { ... }
 * }</code>
 */
public class TsArrowFunction extends Expression {

  private final ParamDecls params;
  private final Expression returnType;
  private final ImmutableList<Statement> bodyStmts;

  TsArrowFunction(ParamDecls params, Expression returnType, ImmutableList<Statement> bodyStmts) {
    this.params = params;
    this.returnType = returnType;
    this.bodyStmts = bodyStmts;
  }

  @Override
  void doFormatOutputExpr(FormattingContext ctx) {
    ctx.append(String.format("(%s): ", params.getCode()));
    ctx.appendOutputExpression(returnType);
    ctx.append(" => ");
    ctx.enterBlock();
    ctx.endLine();
    for (Statement stmt : bodyStmts) {
      ctx.appendAll(stmt);
      ctx.endLine();
    }
    ctx.close();
  }

  @Override
  public void collectRequires(Consumer<GoogRequire> collector) {
    for (Statement stmt : bodyStmts) {
      stmt.collectRequires(collector);
    }
  }

  @Override
  public ImmutableList<Statement> initialStatements() {
    return ImmutableList.of();
  }

  @Override
  void doFormatInitialStatements(FormattingContext ctx) {}

  @Override
  public JsExpr singleExprOrName() {
    return new JsExpr("$$SOY_INTERNAL_ERROR_EXPR", Integer.MAX_VALUE);
  }
}
