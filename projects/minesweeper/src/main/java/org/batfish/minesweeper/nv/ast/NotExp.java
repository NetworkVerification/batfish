package org.batfish.minesweeper.nv.ast;

import com.microsoft.z3.BoolExpr;
import com.microsoft.z3.Context;
import com.microsoft.z3.Expr;
import java.util.Objects;

class NotExp extends Exp {
  public Exp operand;
  public NotExp (Exp e) {
    operand = e;
  }

  @Override public boolean equals(Object obj)
  {
    if (this == obj) return true;
    if (obj == null) return false;
    if (this.getClass() != obj.getClass()) return false;
    NotExp that = (NotExp) obj;
    return (this.operand.equals(that.operand));
  }

  @Override public Expr toSmt(Context ctx) {
    return ctx.mkNot((BoolExpr) operand.toSmt(ctx));
  }

  @Override public String toString() {
    String op = operand.toString();
    if (op.startsWith("(")) {
      return "(!" + op + ")";
    }
    else return "(!(" + op + "))";
  }

  @Override public int hashCode() {
    return Objects.hash(operand);
  }
}
