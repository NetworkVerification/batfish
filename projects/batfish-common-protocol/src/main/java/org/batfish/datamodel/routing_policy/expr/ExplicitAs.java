package org.batfish.datamodel.routing_policy.expr;

import com.fasterxml.jackson.annotation.JsonCreator;
import org.batfish.datamodel.routing_policy.Environment;

public class ExplicitAs extends AsExpr {

  /** */
  private static final long serialVersionUID = 1L;

  private int _as;

  @JsonCreator
  private ExplicitAs() {}

  public ExplicitAs(int as) {
    _as = as;
  }

  @Override
  public boolean equals(Object obj) {
    if (this == obj) {
      return true;
    }
    if (!(obj instanceof ExplicitAs)) {
      return false;
    }
    ExplicitAs other = (ExplicitAs) obj;
    return _as == other._as;
  }

  @Override
  public int evaluate(Environment environment) {
    return _as;
  }

  public int getAs() {
    return _as;
  }

  @Override
  public int hashCode() {
    final int prime = 31;
    int result = 1;
    result = prime * result + _as;
    return result;
  }

  public void setAs(int as) {
    _as = as;
  }
}
