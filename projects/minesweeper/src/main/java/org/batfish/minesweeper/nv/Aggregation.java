/* Implementing route aggregation (for BGP) */
package org.batfish.minesweeper.nv;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import org.batfish.common.BatfishException;
import org.batfish.datamodel.GeneratedRoute;
import org.batfish.datamodel.Prefix;
import org.batfish.datamodel.RouteFilterLine;
import org.batfish.minesweeper.Graph;

public class Aggregation {

  private Graph _graph;

  /* Set of all prefixes announced by the network.
  This could likely be reduced by a smarter analyses, to only include say BGP prefixes, but it has to account for things like redistribution.
  It currently does not include aggregate prefixes.
   */
  private Set<Prefix> _originatedPrefixes;


  private Map<String, Set<Prefix>> _suppressedAggregates; // Aggregates that will suppress contributing routes
  private Map<String, List<GeneratedRoute>> _aggregates; // Per router aggregates

  /* Prefixes originated by network configs that contribute to each aggregate route*/
  private Map<GeneratedRoute, Set<Prefix>> _contributing;

  private static final String AGGREGATION_SUPPRESS_NAME = "MATCH_SUPPRESSED_SUMMARY_ONLY";


  public Aggregation(Graph _graph, Set<Prefix> _originatedPrefixes) {
    this._graph = _graph;
    this._originatedPrefixes = _originatedPrefixes;

    computeAggregates();
    computeSuppressedAggregates();
    computeContributingRoutes();
  }

  public Map<String, Set<Prefix>> getSuppressedAggregates() {
    return _suppressedAggregates;
  }

  public Map<String, List<GeneratedRoute>> getAggregates() {
    return _aggregates;
  }

  public Map<GeneratedRoute, Set<Prefix>> getContributing() {
    return _contributing;
  }

  /*
   * Computes aggregates routes.
   */
  private void computeAggregates() {
    _graph.getConfigurations()
        .forEach(
            (router, conf) -> {
              List<GeneratedRoute> routes = new ArrayList<>();
              _aggregates.put(router, routes);
              routes.addAll(conf.getDefaultVrf().getGeneratedRoutes());
            });
}

  /*
   * Computes whether each aggregate will suppress more specific routes
   * or if it will advertise both.
   */
  private void computeSuppressedAggregates() {
        _graph
        .getConfigurations()
        .forEach(
            (router, conf) -> {
              Set<Prefix> prefixes = new HashSet<>();
              _suppressedAggregates.put(router, prefixes);
              conf.getRouteFilterLists()
                  .forEach(
                      (name, filter) -> {
                        if (name.contains(AGGREGATION_SUPPRESS_NAME)) {
                          for (RouteFilterLine line : filter.getLines()) {
                            if (!line.getIpWildcard().isPrefix()) {
                              throw new BatfishException("non-prefix IpWildcards are unsupported");
                            }
                            prefixes.add(line.getIpWildcard().toPrefix());
                          }
                        }
                      });
            });
  }

  private void computeContributingRoutes() {
    Map<GeneratedRoute, Set<Prefix>> acc = new HashMap<>();
    Set<GeneratedRoute> grs = new HashSet<>();

    // Get all GeneratedRoutes
    for (Entry<String, List<GeneratedRoute>> gr : _aggregates.entrySet()) {
      grs.addAll(gr.getValue());
    }

    // For each originated network

    for (Prefix prefix : _originatedPrefixes) {
      for (GeneratedRoute g : grs) {
        Prefix pgr = g.getNetwork();
        if (pgr.containsPrefix(prefix)) {
          Set<Prefix> contributingPrefixes = acc.computeIfAbsent(g, k -> new HashSet<>());
          // If not allocated, allocate.
          // Add prefix to contributing prefixes for pgr.
          contributingPrefixes.add(prefix);
        }
      }
    }
    _contributing = acc;
  }


  /*
   * Determines whether to model each aggregate route as
   * suppressing a more specific, or including the more specific, for a given router.
   */
  private Map<Prefix, Boolean> aggregateRoutes(String name) {
    Map<Prefix, Boolean> acc = new HashMap<>();
    //String name = _conf.getHostname();
    List<GeneratedRoute> aggregates = getAggregates().get(name);
    Set<Prefix> suppressed = getSuppressedAggregates().get(name);
    for (GeneratedRoute gr : aggregates) {
      Prefix p = gr.getNetwork();
      acc.put(p, suppressed.contains(p));
    }
    return acc;
  }

}
