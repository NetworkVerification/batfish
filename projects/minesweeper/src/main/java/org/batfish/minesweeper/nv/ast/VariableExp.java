package org.batfish.minesweeper.nv.ast;

import com.microsoft.z3.Context;
import com.microsoft.z3.Expr;
import java.util.Objects;

public class VariableExp extends Exp {
  public String name;

  public VariableExp (String n) {
    this.name=n;
  }

  // TODO: OK major cheat here, I am only adding variables as int sort because I know this
  // is how they are used for the generated programs (prefixes). If you ever need to generalize
  // this you need to keep track of sorts/types.
  @Override public Expr toSmt(Context ctx) {
    return ctx.mkIntConst(name);
  }

  @Override public String toString() {
    return name;
  }

  @Override public boolean equals(Object obj)
  {
    if (this == obj) return true;
    if (obj == null) return false;
    if (this.getClass() != obj.getClass()) return false;
    VariableExp that = (VariableExp) obj;
    return (this.name.equals(that.name));
  }

  @Override public int hashCode() {
    return Objects.hash(name);
  }
}
