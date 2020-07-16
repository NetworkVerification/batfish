package org.batfish.minesweeper.nv.ast;

import com.microsoft.z3.Context;
import com.microsoft.z3.Expr;
import java.util.Objects;

// Used for some expressions that would introduce a lot of overhead to this expression language,
// e.g. function calls. A proper expression language should ditch this class.
public class ExpAsString extends Exp {
  public String name;

  public ExpAsString (String n) {
    this.name=n;
  }

  @Override public Expr toSmt (Context ctx) {
    throw new IllegalArgumentException("Unimplemented SMT operation");
  }

  @Override public String toString() {
    return name;
  }

  @Override public boolean equals(Object obj)
  {
    if (this == obj) return true;
    if (obj == null) return false;
    if (this.getClass() != obj.getClass()) return false;
    ExpAsString that = (ExpAsString) obj;
    return (this.name.equals(that.name));
  }

  @Override public int hashCode() {
    return Objects.hash(name);
  }
}
