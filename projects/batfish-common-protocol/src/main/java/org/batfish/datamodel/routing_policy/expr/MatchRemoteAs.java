package org.batfish.datamodel.routing_policy.expr;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.Objects;
import org.batfish.datamodel.routing_policy.Environment;
import org.batfish.datamodel.routing_policy.Result;

/** A {@link BooleanExpr} that matches the remote AS of a BGP route. */
public final class MatchRemoteAs extends BooleanExpr {
  /** */
  private static final long serialVersionUID = 1L;

  private static final String PROP_EXPR = "expr";

  private AsExpr _expr;

  @JsonCreator
  private MatchRemoteAs() {}

  public MatchRemoteAs(AsExpr expr) {
    _expr = expr;
  }

  @Override
  public boolean equals(Object obj) {
    if (this == obj) {
      return true;
    }
    if (!(obj instanceof MatchRemoteAs)) {
      return false;
    }
    MatchRemoteAs other = (MatchRemoteAs) obj;
    return Objects.equals(_expr, other._expr);
  }

  @Override
  public Result evaluate(Environment environment) {
    throw new UnsupportedOperationException(getClass().getSimpleName());
  }

  @JsonProperty(PROP_EXPR)
  public AsExpr getExpr() {
    return _expr;
  }

  @Override
  public int hashCode() {
    final int prime = 31;
    int result = 1;
    result = prime * result + ((_expr == null) ? 0 : _expr.hashCode());
    return result;
  }

  @JsonProperty(PROP_EXPR)
  public void setExpr(AsExpr expr) {
    _expr = expr;
  }

  @Override
  public String toString() {
    return toStringHelper().add(PROP_EXPR, _expr).toString();
  }
}
