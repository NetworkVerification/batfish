package org.batfish.minesweeper.nv.ast;

import com.microsoft.z3.Context;
import com.microsoft.z3.Expr;
import java.util.Objects;

public class BoolExp extends Exp {

  public boolean value;

  public BoolExp(boolean v) {
    value = v;
  }

  public boolean getValue() {
    return value;
  }

  @Override public Expr toSmt(Context ctx) {
    return ctx.mkBool(value);
  }

  @Override public boolean equals(Object obj)
  {
    if (this == obj) return true;
    if (obj == null) return false;
    if (this.getClass() != obj.getClass()) return false;
    BoolExp that = (BoolExp) obj;
    return (this.value == that.value);
  }

  @Override public int hashCode() {
    return Objects.hash(value);
  }
}
