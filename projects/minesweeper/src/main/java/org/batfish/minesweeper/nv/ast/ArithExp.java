package org.batfish.minesweeper.nv.ast;

import com.microsoft.z3.ArithExpr;
import com.microsoft.z3.Context;
import com.microsoft.z3.Expr;
import java.util.Objects;

// Arithmetic binary operations
class ArithExp extends Exp {
  public BinaryArithOp operator;
  public Exp left;
  public Exp right;
  public int sz;
  public ArithExp (BinaryArithOp o, Exp l, Exp r, int sz) {
    operator=o;
    left=l;
    right=r;
    this.sz = sz;
  }

  @Override public Expr toSmt(Context ctx) {
    switch (operator) {
    case PLUS: return ctx.mkAdd((ArithExpr) left.toSmt(ctx), (ArithExpr) right.toSmt(ctx));
    case GE: return ctx.mkGe((ArithExpr) left.toSmt(ctx), (ArithExpr) right.toSmt(ctx));
    case LE: return ctx.mkLe((ArithExpr) left.toSmt(ctx), (ArithExpr) right.toSmt(ctx));
    case LT: return ctx.mkLt((ArithExpr) left.toSmt(ctx), (ArithExpr) right.toSmt(ctx));
    default: throw new IllegalArgumentException("Unimplemented SMT operation");
    }
  }

  @Override public String toString() {
    String szStr = sz == 32 ? "" : "u" + sz;
    return "("+ left.toString() + " " + operator.toString() + szStr + " " + right.toString()+")";
  }

  @Override public boolean equals(Object obj)
  {
    if (this == obj) return true;
    if (obj == null) return false;
    if (this.getClass() != obj.getClass()) return false;
    ArithExp that = (ArithExp) obj;
    return (this.operator.equals(that.operator)) && (this.left.equals(that.left))
        && (this.right.equals(that.right)) && (this.sz == that.sz);
  }

  @Override public int hashCode() {
    return Objects.hash(operator, left, right);
  }
}
