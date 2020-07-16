package org.batfish.minesweeper.nv.ast;

import com.microsoft.z3.BoolExpr;
import com.microsoft.z3.Context;
import com.microsoft.z3.Expr;
import java.util.Objects;

// Boolean binary operations
class BinaryBoolExp extends Exp {
  public BinaryBoolOp operator;
  public Exp left;
  public Exp right;
  public BinaryBoolExp (BinaryBoolOp o, Exp l, Exp r) {
    operator=o;
    left=l;
    right=r;
  }

  @Override public Expr toSmt(Context ctx) {
    switch (operator) {
    case AND: return ctx.mkAnd((BoolExpr) left.toSmt(ctx), (BoolExpr) right.toSmt(ctx));
    case OR: return ctx.mkOr((BoolExpr) left.toSmt(ctx), (BoolExpr) right.toSmt(ctx));
    case EQ: return ctx.mkEq(left.toSmt(ctx), right.toSmt(ctx));
    default: throw new IllegalArgumentException("Unimplemented SMT operation");
    }
  }

  @Override public String toString() {
    return "("+ left.toString() + " " + operator.toString() + " " + right.toString() + ")";
  }

  @Override public boolean equals(Object obj)
  {
    if (this == obj) return true;
    if (obj == null) return false;
    if (this.getClass() != obj.getClass()) return false;
    BinaryBoolExp that = (BinaryBoolExp) obj;
    return (this.operator.equals(that.operator)) && (this.left.equals(that.left)) && (this.right.equals(that.right));
  }

  @Override public int hashCode() {
    return Objects.hash(operator, left, right);
  }
}
