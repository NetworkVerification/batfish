package org.batfish.minesweeper.nv.ast;

import com.microsoft.z3.Context;
import com.microsoft.z3.Expr;

public abstract class Exp {
  public abstract Expr toSmt(Context ctx);
}

