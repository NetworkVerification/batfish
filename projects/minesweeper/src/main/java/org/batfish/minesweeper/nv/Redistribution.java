package org.batfish.minesweeper.nv;

import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map.Entry;
import java.util.Set;
import org.batfish.datamodel.Configuration;
import org.batfish.datamodel.routing_policy.RoutingPolicy;
import org.batfish.minesweeper.Graph;
import org.batfish.minesweeper.Protocol;
import org.batfish.minesweeper.collections.Table2;

public class Redistribution {

  private Graph _graph;


  /* For each router R and each protocol P, represents the set of protocols that redistribute into P*/
  private Table2<String, Protocol, Set<Protocol>> _redistributedProtocols;

  public Redistribution(Graph _graph) {
    this._graph = _graph;
    this._redistributedProtocols = new Table2<>();
    initRedistributedProtocols();
  }

  private void initRedistributedProtocols() {
    /* List of protocols modeled. For now we always model the 4 standard ones */
    List<Protocol> protos = new LinkedList<>();
    protos.add(Protocol.BGP);
    protos.add(Protocol.OSPF);
    protos.add(Protocol.CONNECTED);
    protos.add(Protocol.STATIC);


    for (Entry<String, Configuration> entry : _graph.getConfigurations().entrySet()) {
      String router = entry.getKey();
      Configuration conf = entry.getValue();
      for (Protocol proto : protos) {
        Set<Protocol> redistributed = new HashSet<>();
        redistributed.add(proto);
        _redistributedProtocols.put(router, proto, redistributed);
        RoutingPolicy pol = Graph.findCommonRoutingPolicy(conf, proto);
        if (pol != null) {
          // Find the set of protocols that may be redistributed into proto
          Set<Protocol> ps = _graph.findRedistributedProtocols(conf, pol, proto);
          redistributed.addAll(ps);
        }
      }
    }
  }

  public Table2<String, Protocol, Set<Protocol>> getRedistributedProtocols() {
    return _redistributedProtocols;
  }

}
