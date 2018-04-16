package org.batfish.representation.cisco;

import com.google.common.collect.ImmutableSet;
import com.google.common.primitives.Ints;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import org.batfish.common.Warnings;
import org.batfish.datamodel.Configuration;
import org.batfish.datamodel.routing_policy.expr.BooleanExpr;
import org.batfish.datamodel.routing_policy.expr.Disjunction;
import org.batfish.datamodel.routing_policy.expr.ExplicitAs;
import org.batfish.datamodel.routing_policy.expr.MatchRemoteAs;

/**
 * A {@link RouteMapMatchLine} that matches if any of the specified AS numbers matches the remote AS
 * number.
 */
public class RouteMapMatchRemoteAsLine extends RouteMapMatchLine {

  private static final long serialVersionUID = 1L;

  private final Set<Integer> _asns;

  public RouteMapMatchRemoteAsLine(int... asns) {
    this(Ints.asList(asns));
  }

  public RouteMapMatchRemoteAsLine(Collection<Integer> asns) {
    _asns = ImmutableSet.copyOf(asns);
  }

  public Set<Integer> getAsns() {
    return _asns;
  }

  @Override
  public BooleanExpr toBooleanExpr(Configuration c, CiscoConfiguration cc, Warnings w) {
    Disjunction d = new Disjunction();
    List<BooleanExpr> disjuncts = d.getDisjuncts();
    for (int asn : _asns) {
      disjuncts.add(new MatchRemoteAs(new ExplicitAs(asn)));
    }
    return d.simplify();
  }
}
