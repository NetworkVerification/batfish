package org.batfish.minesweeper.nv.ast;

import com.microsoft.z3.Context;
import com.microsoft.z3.Expr;
import java.util.Objects;

class IntegerExp extends Exp {
  public long value;
  public int sz;

  public IntegerExp (long n, int sz) {
    this.value = n;
    this.sz = sz;
  }

  public IntegerExp (long n) {
    this.value = n;
    this.sz = 32;
  }

  @Override public Expr toSmt(Context ctx) {
    return ctx.mkInt(value);
  }

  @Override public String toString() {
    if (sz == 32) {
      return value + "";
    }
    else {
      return value + "u" + sz;
    }
  }

  @Override public boolean equals(Object obj)
  {
    if (this == obj) return true;
    if (obj == null) return false;
    if (this.getClass() != obj.getClass()) return false;
    IntegerExp that = (IntegerExp) obj;
    return (this.value == that.value) && (this.sz == that.sz);
  }

  @Override public int hashCode() {
    return Objects.hash(value, sz);
  }
}
