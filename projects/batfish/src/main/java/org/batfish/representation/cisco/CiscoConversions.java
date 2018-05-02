package org.batfish.representation.cisco;

import java.util.stream.Stream;
import org.batfish.datamodel.Configuration;
import org.batfish.datamodel.LineAction;
import org.batfish.datamodel.Prefix;
import org.batfish.datamodel.RouteFilterLine;
import org.batfish.datamodel.RouteFilterList;
import org.batfish.datamodel.SubRange;
import org.batfish.datamodel.routing_policy.expr.DestinationNetwork;
import org.batfish.datamodel.routing_policy.expr.MatchPrefixSet;
import org.batfish.datamodel.routing_policy.expr.NamedPrefixSet;
import org.batfish.datamodel.routing_policy.statement.If;
import org.batfish.datamodel.routing_policy.statement.Statements;

public final class CiscoConversions {

  public static If suppressLongerNetworksForSummaryOnlyNetworks(
      Configuration c, String vrfName, Stream<Prefix> prefixesToSuppress) {
    // Create a RouteFilterList that matches any network longer than a prefix marked summary only.
    RouteFilterList matchLonger =
        new RouteFilterList("~MATCH_SUPPRESSED_SUMMARY_ONLY:" + vrfName + "~");
    prefixesToSuppress
        .map(
            p ->
                new RouteFilterLine(
                    LineAction.ACCEPT,
                    p,
                    new SubRange(p.getPrefixLength() + 1, Prefix.MAX_PREFIX_LENGTH)))
        .forEach(matchLonger::addLine);
    // Bookkeeping: record that we created this RouteFilterList to match longer networks.
    c.getRouteFilterLists().put(matchLonger.getName(), matchLonger);

    // The policy statement:
    If suppressLonger = new If();
    suppressLonger.setComment(
        "Suppress longer advertisements of summary-only aggregate-address networks");
    // If a network matches the above RouteFilterList...
    suppressLonger.setGuard(
        new MatchPrefixSet(new DestinationNetwork(), new NamedPrefixSet(matchLonger.getName())));
    // Then it should not be advertised.
    suppressLonger.getTrueStatements().add(Statements.ReturnFalse.toStaticStatement());

    return suppressLonger;
  }

  private CiscoConversions() {} // prevent instantiation of utility class
}
