package org.batfish.minesweeper.nv.ast;

import com.microsoft.z3.Context;
import com.microsoft.z3.Expr;
import org.batfish.datamodel.Ip;

public class IpExp extends Exp {
  public Ip value;

  public IpExp (Ip n) {
    this.value = n;
  }

  @Override public Expr toSmt(Context ctx) {
    return ctx.mkInt(value.asLong());
  }

  @Override public String toString() {
    return value.toString();
  }

  @Override public boolean equals(Object obj)
  {
    if (this == obj) return true;
    if (obj == null) return false;
    if (this.getClass() != obj.getClass()) return false;
    IpExp that = (IpExp) obj;
    return (this.value.equals(that.value));
  }

  @Override public int hashCode() {
    return value.hashCode();
  }
}

