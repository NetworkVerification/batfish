package org.batfish.minesweeper.nv.ast;

import com.microsoft.z3.BoolExpr;
import com.microsoft.z3.Context;
import com.microsoft.z3.Expr;
import java.util.Objects;

public class IfThenElseExp extends Exp {

  public Exp guard;
  public Exp trueBranch;
  public Exp falseBranch;


  public IfThenElseExp(Exp g, Exp t, Exp f) {
    guard = g;
    trueBranch = t;
    falseBranch = f;
  }

  @Override public Expr toSmt(Context ctx) {
    return ctx.mkITE((BoolExpr) guard.toSmt(ctx), trueBranch.toSmt(ctx), falseBranch.toSmt(ctx));
  }

  @Override public String toString() {
    return "if " + guard.toString() + " then\n" + trueBranch.toString() + "\n else\n" + falseBranch.toString();
  }

  @Override public boolean equals(Object obj)
  {
    if (this == obj) return true;
    if (obj == null) return false;
    if (this.getClass() != obj.getClass()) return false;
    IfThenElseExp that = (IfThenElseExp) obj;
    return (this.guard.equals(that.guard)) && (this.trueBranch.equals(that.trueBranch))
        && (this.falseBranch.equals(that.falseBranch));
  }

  @Override public int hashCode() {
    return Objects.hash(guard, trueBranch, falseBranch);
  }

  public Exp getGuard() {
    return guard;
  }

  public Exp getTrueBranch() {
    return trueBranch;
  }

  public Exp getFalseBranch() {
    return falseBranch;
  }


}
