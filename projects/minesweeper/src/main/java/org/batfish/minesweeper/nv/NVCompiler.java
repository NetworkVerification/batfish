package org.batfish.minesweeper.nv;

import static org.batfish.minesweeper.Graph.getFirstOspfProcess;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Objects;
import java.util.Optional;
import java.util.Scanner;
import java.util.Set;
import java.util.SortedMap;
import java.util.SortedSet;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Pattern;
import org.batfish.common.plugin.IBatfish;
import org.batfish.datamodel.BgpActivePeerConfig;
import org.batfish.datamodel.BgpProcess;
import org.batfish.datamodel.Configuration;
import org.batfish.datamodel.Interface;
import org.batfish.datamodel.Ip;
import org.batfish.datamodel.Prefix;
import org.batfish.datamodel.ospf.OspfArea;
import org.batfish.datamodel.ospf.OspfProcess;
import org.batfish.datamodel.routing_policy.RoutingPolicy;
import org.batfish.datamodel.routing_policy.statement.Statement;
import org.batfish.minesweeper.Graph;
import org.batfish.minesweeper.GraphEdge;
import org.batfish.minesweeper.Protocol;
import org.batfish.minesweeper.abstraction.PrefixTrie;
import org.batfish.minesweeper.collections.Table2;
import org.batfish.minesweeper.utils.Triple;
import org.batfish.minesweeper.utils.Tuple;

/* Assumptions.
 * 1. So far we do not model iBGP, hence our BGP routes do not include IGP/EGP modifier.
 * 2. For multipath we always assume that multipath-as-relax is enabled for BGP. Also the number of
 *    equivalent paths is always ignored
 * 3. We do not model AS PATH string; we do model AS PATH as a set that is useful to detect loops.
 *    Note however, that this AS PATH will only correspond to one route when multipath is enabled
 *    hence loops may still occur (this is how BGP works in practice too I think).
 * */

class InitialAttribute {
  private final Boolean _conn;
  private final Boolean _static;
  private final Optional<Long> _areaId;
  private final Boolean _bgp;
  private final CompilerOptions _flags;

  public InitialAttribute(
      Boolean conn, Boolean stat, Optional<Long> areaId, Boolean bgp, CompilerOptions flags) {
    _conn = conn;
    _static = stat;
    _areaId = areaId;
    _bgp = bgp;
    _flags = flags;
  }

  public String compileAttr(int node, boolean singlePrefix) {
    StringBuilder sb = new StringBuilder();
    Attributes attrs = new Attributes(_flags);
    String c = this._conn ? "Some 0u8" : "None";
    String s = this._static ? "Some 1u8" : "None";
    String o = "None";
    if (this._areaId.isPresent()) {
      o =
          attrs.buildOspfAttribute(
              "110u8",
              "0u16",
              "ospfIntraArea",
              _areaId.get().toString(),
              _flags.doMultiPath() ? "{}" : "None",
              node + "n");
    }
    String b =
        _bgp
            ? attrs.buildBgpAttribute(
                "100",
                "20u8",
                "0",
                "80",
                "{}",
                _flags.doMultiPath() ? "{}" : "None",
                node + "n",
                "{}",
                "false",
                "0u16")
            : "None";

    if (!c.equals("None")) {
      sb.append("      let c = ").append(c).append(" in\n");
    }
    if (!s.equals("None")) {
      sb.append("      let s = ").append(s).append(" in\n");
    }
    if (!o.equals("None")) {
      sb.append("      let o = ").append(o).append(" in\n");
    }
    if (!b.equals("None")) {
      sb.append("      let b = ").append(b).append(" in\n");
    }
    sb.append("      let fib = best ");
    if (c.equals("None")) {
      sb.append("None ");
    } else {
      sb.append("c ");
    }

    if (s.equals("None")) {
      sb.append("None ");
    } else {
      sb.append("s ");
    }

    if (o.equals("None")) {
      sb.append("None ");
    } else {
      sb.append("o ");
    }

    if (b.equals("None")) {
      sb.append("None ");
    } else {
      sb.append("b ");
    }
    sb.append("in\n");

    //        sb.append("    let fib = best c s o b in\n");
    if (singlePrefix) {
      sb.append("      {connected= ");
      if (c.equals("None")) {
        sb.append("None");
      } else {
        sb.append("c");
      }

      sb.append("; static = ");

      if (s.equals("None")) {
        sb.append("None");
      } else {
        sb.append("s");
      }

      sb.append("; ospf = ");

      if (o.equals("None")) {
        sb.append("None");
      } else {
        sb.append("o");
      }

      sb.append("; bgp = ");

      if (b.equals("None")) {
        sb.append("None");
      } else {
        sb.append("b");
      }
      sb.append("; selected=fib;}\n");
    } else {
      sb.append("    let route = {connected=c; static=s; ospf=o; bgp=b; selected=fib;} in\n");
    }

    return sb.toString();
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (o == null || getClass() != o.getClass()) {
      return false;
    }
    InitialAttribute that = (InitialAttribute) o;
    return Objects.equals(_conn, that._conn)
        && Objects.equals(_static, that._static)
        && Objects.equals(_areaId, that._areaId)
        && Objects.equals(_bgp, that._bgp);
  }

  @Override
  public int hashCode() {

    return Objects.hash(_conn, _static, _areaId, _bgp);
  }
}

public class NVCompiler {

  private final Graph _graph;
  private final Map<GraphEdge, String> _edgeMap;
  private final Map<String, GraphEdge> _reverseEdgeMap;
  private final Map<String, Integer> _nodeAssignment;
  private ArrayList<LinkedList<Integer>> _adj; // Adjacency list for nv edges;

  private final SortedSet<Prefix> _originated; // Originated BGP and OSPF prefixes
  private final Map<Integer, Set<Prefix>>
      _perNodePrefixes; // associated a set of prefixes to every node.

  /* Link Capacities from the GraphEdge name to capacity.*/
  private final Map<String, Double> _linkCapacities;

  /* Link Failure Probabilities */
  private final Map<String, Double> _linkFailureProbability;

  /* Node Failure Probabilities */
  private final Map<String, Double> _nodeFailureProbability;

  /* Set of links groupped by the failure of probability */
  private final SortedMap<Double, Set<String>> _edgeGroups;

  /* Set of nodes groupped by the failure of probability */
  private final SortedMap<Double, Set<String>> _nodeGroups;

  /* Mapping edge to edge id */
  private final Map<GraphEdge, Integer> _edgeIds;

  /* Mapping edge_id to edge */
  private final SortedMap<Integer, GraphEdge> _idToEdge;

  /* Set of loopbacks used for iBGP */
  private final Set<Ip> _ibgpLoopbacks;

  // Filename of the main network file.
  private final String _filename;

  private final String _topologyModel;

  private final CompilerOptions _flags;
  private final Attributes _attrs;

  public NVCompiler(
      IBatfish batfish, String filename, String topologyModel, CompilerOptions flags) {
    _graph = new Graph(batfish);
    _flags = flags;
    _attrs = new Attributes(flags);
    _edgeMap = new HashMap<>();
    _reverseEdgeMap = new HashMap<>();
    _nodeAssignment = new HashMap<>();
    _originated = new TreeSet<>();
    // Originated prefixes belonging to hosts
    SortedSet<Prefix> _hosts = new TreeSet<>();
    _perNodePrefixes = new HashMap<>();
    _linkCapacities = new HashMap<>();
    _linkFailureProbability = new HashMap<>();
    _nodeFailureProbability = new HashMap<>();
    _edgeIds = new HashMap<>();
    _idToEdge = new TreeMap<>();
    _edgeGroups = new TreeMap<>();
    _nodeGroups = new TreeMap<>();
    _filename = filename;
    _topologyModel = topologyModel;
    _ibgpLoopbacks = new HashSet<>();
  }

  private String optionType(String typ) {
    return "option[" + typ + "]";
  }

  private String dictType(String keyTyp, String valTyp) {
    return "dict[" + keyTyp + ", " + valTyp + "]";
  }

  /* Groups policies that are equivalent and generates a single function for them instead of
   *  emitting the same policy multiple times. (*/
  private Tuple<Map<GraphEdge, String>, Map<GraphEdge, String>> computeEquivalentPolicies(
      StringBuilder sb) {

    Set<String> bgpSet = new HashSet<>();

    // Maps policy expression (as an NV string) to a unique name.
    Map<String, String> compiledExportPolicies = new HashMap<>();
    Map<String, String> compiledImportPolicies = new HashMap<>();

    // Maps the policy applied over this GraphEdge to the unique name of the policy.
    Map<GraphEdge, String> edgeToImportPolicy = new HashMap<>();
    Map<GraphEdge, String> edgeToExportPolicy = new HashMap<>();

    /*Iterate over each router */
    for (Entry<String, Configuration> entry : _graph.getConfigurations().entrySet()) {
      String router = entry.getKey();
      Configuration config = entry.getValue();

      /*
       //XXX: Ignoring aggregation for now.
       //Get aggregate routes for this router.
            List<GeneratedRoute> aggregates = agg.getAggregates().get(router);
            Set<Prefix> suppresed = agg.getSuppressedAggregates().get(router);
            Map<GeneratedRoute, Set<Prefix>> contributing = agg.getContributing();
      */

      for (GraphEdge edge : _graph.getEdgeMap().get(router)) {
        if ((edge.getPeer() != null) && (_edgeMap.containsKey(edge))) {
          if (_graph.isEdgeUsed(config, Protocol.BGP, edge)
              && !bgpSet.contains(_edgeMap.get(edge))) {
            bgpSet.add(_edgeMap.get(edge));

            StringBuilder exportString = new StringBuilder();

            // Compiling the export policy for the given edge.
            RoutingPolicy policy = _graph.findExportRoutingPolicy(router, Protocol.BGP, edge);
            List<Statement> statements;
            if ((policy != null) && (policy.getStatements() != null)) {
              statements = policy.getStatements();
            } else {
              statements = Collections.emptyList();
            }

            /* Emit the NV code to handle redistribution into BGP */

            exportString
                .append("      match b with\n")
                .append("      | None -> None\n")
                .append("      | Some b ->\n");

            if (edge.isAbstract()) {
              exportString.append("      let igpVU = igpVU.ospf in\n");
            }

            /* If this is an iBGP connection then only send the route if it was not learned by
            another iBGP neighbor.
            */
            if (edge.isAbstract()) {
              exportString.append("        if b.ibgp || (igpUV.selected = None) then None else\n");
            }

            /* If AS Path is tracked then check for AS loops */
            if (_flags.doASPath()) {
              exportString.append("        if (let (u~v) = e in b.bgpAS[v]) then None else\n");
            }

            /* Set the nexthop appropriately */
            if (_flags.doNextHop()) {
              bgpNextHop(exportString, edge.isAbstract());
            }
            else {
              // Set ibgp = false/true; we do that in bgpNextHop but we also need to do in case
              // nexthop is not tracked.
              if (edge.isAbstract()) {
                sb.append("        let b = {b with ibgp = true} in\n");
                  }
               else {
                  sb.append("        let b = {b with ibgp = false} in\n");
                }
            }

            // Build the decision tree for the export policy
            TransferFunctionBuilder exportTransBuilder =
                new TransferFunctionBuilder(config, statements, edge, true);
            DecisionTree<Boolean> exportTree = exportTransBuilder.compute();

            // Apply some basic optimizations - NOTE: I think there is some bug in this code so
            // disabling it for now.
            // exportTree.optimize();

            /* Build NV string that corresponds to export tree */
            TreeCompiler exportTreeCompiler = new TreeCompiler(exportTree, null, config, _flags);

            String expPolicy = exportTreeCompiler.toNvString();
            exportString.append(expPolicy);

            /* Route Aggregation - Disabled I think it only works with multiple prefixes right now. */
            // Add aggregation explicitly.
            /* TODO: this assumes that the default route components are set and no route-maps are applied over aggregates.
              The former should be easy to do, but i can't figure it out through batfish millions of classes.
            */

            //            for (GeneratedRoute aggregate : aggregates) {
            //              Prefix p = aggregate.getNetwork();
            //              exportString.append("   let x = mapIf (fun p -> p = ")
            //                  .append(p).append(") (fun v -> \n")
            //                  .append("      match v.bgp with\n)")
            //                  .append("      | None -> \n")
            //                  .append("        if (");
            //
            //              boolean notFirst = false;
            //              for (Prefix contributor : contributing.get(aggregate)) {
            //                if (notFirst) {
            //                  exportString.append(" || ");
            //                }
            //                notFirst = true;
            //                exportString.append("(x[")
            //                    .append(contributor)
            //                    .append("].selected = 3u2)");
            //              }
            //              exportString.append(") then ")
            //                  .append("{v with bgp= ")
            //
            // .append(_attrs.buildBgpAttribute("100","20u8","0","80","{}",_flags.doMultiPath() ?
            // "{}" : "None", "getSourceNode e", "{}"))
            //                  .append(" else None\n")
            //                  .append("      | Some _ -> v) x in\n");
            //
            //            }
            //
            //            // TODO: suppress routes.

            // Add to string.
            String expPolicyName = compiledExportPolicies.get(exportString.toString());
            if (expPolicyName == null) {
              /* If this policy has not been encountered before then create an NV function for it */
              expPolicyName = "bgpExportPol" + compiledExportPolicies.size();
              if (edge.isAbstract())
                sb.append("let " + expPolicyName + " e prefix prefixLen prot b igpUV igpVU =\n");
              else
                sb.append("let " + expPolicyName + " e prefix prefixLen prot b =\n");
              sb.append(exportString);
              sb.append("\n\n");
              compiledExportPolicies.put(exportString.toString(), expPolicyName);
            }
            edgeToExportPolicy.put(edge, expPolicyName);

            /* Process the import policy */
            List<Statement> importStatements;
            GraphEdge invEdge = _graph.getOtherEnd().get(edge);

            String impPolicy = "b";
            if (invEdge != null) {
              String otherRouter = invEdge.getRouter();
              RoutingPolicy importPolicy =
                  _graph.findImportRoutingPolicy(otherRouter, Protocol.BGP, invEdge);
              Configuration invConfig = _graph.getConfigurations().get(otherRouter);
              if ((importPolicy != null) && (importPolicy.getStatements() != null)) {

                importStatements = importPolicy.getStatements();
                // if (!compiledImportPolicies.containsKey(importStatements)) {
                TransferFunctionBuilder importTransBuilder =
                    new TransferFunctionBuilder(invConfig, importStatements, invEdge, false);

                DecisionTree<Boolean> importTree = importTransBuilder.compute();

                // importTree.optimize();

                TreeCompiler importTreeCompiler =
                    new TreeCompiler(importTree, invConfig, null, _flags);
                impPolicy = importTreeCompiler.toNvString();
              }
            }
            StringBuilder importString = new StringBuilder();

            /* Add AS PATH setting */
            if (_flags.doASPath()) {
              importString
                  .append("  match b with\n")
                  .append("  | None -> None\n")
                  .append("  | Some b ->\n");
              importString
                  .append("    let (u~v) = e in\n")
                  .append("    let b = {b with bgpAS = b.bgpAS[u := true]} in\n");
              importString.append("       ");
              if (impPolicy.equals("b")) importString.append("Some b\n");
              else importString.append(impPolicy).append("))\n");
            }

            if (importString.toString().equals("")) importString.append(impPolicy);

            /* Map the import policy to a policy name */
            String impPolicyName = compiledImportPolicies.get(importString.toString());
            if (impPolicyName == null) {
              impPolicyName = "bgpImportPol" + compiledImportPolicies.size();

              // Only consider the import policy if it is non-empty or if we need to add AS path
              // tracking.
              //              if (!importString.equals("b")) {
              sb.append("let ")
                  .append(impPolicyName)
                  .append(" e prefix prefixLen b =\n")
                  .append(importString);
              sb.append("\n\n");
              compiledImportPolicies.put(importString.toString(), impPolicyName);
              //              }
            }
            //            if (!importString.equals("b"))
            edgeToImportPolicy.put(edge, impPolicyName);
          }
        }
      }
    }
    Tuple<Map<GraphEdge, String>, Map<GraphEdge, String>> ret =
        new Tuple<>(edgeToExportPolicy, edgeToImportPolicy);
    return ret;
  }

  private Map<Protocol, String> redistributeIntoBgp(
      String x, Table2<String, Protocol, Set<Protocol>> redistributionTable, String router) {
    Set<Protocol> protocols = redistributionTable.get(router, Protocol.BGP);
    Map<Protocol, String> redistributedRoutes = new HashMap<>();

    if (protocols != null && protocols.contains(Protocol.CONNECTED)) {
      // Default bgp attribute for connected
      redistributedRoutes.put(
          Protocol.CONNECTED,
          _attrs.buildBgpAttribute(
              "100",
              "20u8",
              "0",
              "80",
              "{}",
              _flags.doMultiPath() ? "{}" : "None",
              "getSourceNode e",
              "{}",
              "false",
              "0u16"));
    } else {
      redistributedRoutes.put(Protocol.CONNECTED, "None");
    }

    // TODO: Nexthop and origin might need to be different.
    if (protocols.contains(Protocol.STATIC)) {
      redistributedRoutes.put(
          Protocol.STATIC,
          _attrs.buildBgpAttribute(
              "100",
              "20u8",
              "0",
              "80",
              "{}",
              _flags.doMultiPath() ? "{}" : "None",
              "getSourceNode e",
              "{}",
              "false",
              "0u16"));
    } else {
      redistributedRoutes.put(Protocol.STATIC, "None");
    }

    // For ospf
    // TODO: I think the med here needs to be set to the OSPF weight, but we can't cast it yet.
    // TODO: Do we need to check whether we redistribute E1 and E2? We probably need to.
    if (protocols.contains(Protocol.OSPF)) {
      String bospf =
          "(match "
              + x
              + ".ospf with\n"
              + " | None -> None\n"
              + " | Some o -> "
              + _attrs.buildBgpAttribute(
                  "100",
                  "20u8",
                  "0",
                  "80",
                  "{}",
                  "o.ospfNextHop",
                  "o.ospfOrigin",
                  "{}",
                  "false",
                  "0u16")
              + ")";
      redistributedRoutes.put(Protocol.OSPF, bospf);
    } else {
      redistributedRoutes.put(Protocol.OSPF, "None");
    }
    return redistributedRoutes;
  }

  /* Set BGP multiPath and nextHop in an attribute of the transfer function */
  private void bgpNextHop(StringBuilder sb, boolean isIBGP) {
    if (isIBGP) {
      // if it's an iBGP link then set the nexthop as the one used by the IGP.
      if (_flags.doMultiPath()) {
        sb.append(
            "        let b = {b with ibgp = true; bgpNextHop = match igpVU with | None -> {} | Some o -> o.ospfNextHop} in\n");
      } else {
        sb.append(
            "        let b = {b with ibgp = true; bgpNextHop = match igpVU with | None -> None | Some o -> o.ospfNextHop} in\n");
      }
    } else {
      if (_flags.doMultiPath()) {
        sb.append(
            "        let b = {b with ibgp = false; bgpNextHop = match flipEdge e with | None -> {} | Some fe -> {fe}} in\n");
      } else {
        sb.append("        let b = {b with ibgp = false; bgpNextHop = flipEdge e} in\n");
      }
    }
  }

  /* Computes the set of loopback addresses for iBGP */
  private void computeIBGPLoopbacks() {
    for (Entry<GraphEdge, BgpActivePeerConfig> e : _graph.getIbgpNeighbors().entrySet()) {
      _ibgpLoopbacks.add(e.getValue().getLocalIp());
    }
  }

  // This is assuming that there are multiple prefixes to reach the iBGP loopbacks.
//  private String graphEdgetoIBGPLoopback() {
//    PrefixTrie preTrie = new PrefixTrie(_originated);
//    StringBuilder sbU = new StringBuilder();
//    StringBuilder sbV = new StringBuilder();
//    sbU.append("@noinline @verbatim\n").append("let loopbackU e =\n").append("  match e with\n");
//    sbV.append("@noinline @verbatim\n").append("let loopbackV e =\n").append("  match e with\n");
//    for (Entry<GraphEdge, BgpActivePeerConfig> e : _graph.getIbgpNeighbors().entrySet()) {
//      String nvEdge = _edgeMap.get(e.getKey());
//      List<Prefix> matchingPrefixes = preTrie.getLongestPrefixMatches(e.getValue().getLocalIp());
//      sbU.append("| ").append(nvEdge).append(" -> ");
//      Iterator<Prefix> it = matchingPrefixes.iterator();
//      StringBuilder sbParen = new StringBuilder();
//      while (it.hasNext()) {
//        Prefix pre = it.next();
//        sbU.append("  match ").append(FaultAnalysis.ribName(pre)).append("[" + _nodeAssignment.get(e.getKey().getPeer()) + "].selected with\n")
//            .append("  | Some _ -> ").append(FaultAnalysis.ribName(pre)).append("[" + _nodeAssignment.get(e.getKey().getPeer()) + "]").append("\n");
//
//        if (it.hasNext()) {
//          sbU.append("  | None -> (\n");
//          sbV.append("  | None -> (\n");
//          sbParen.append(")");
//        }
//        else
//          sbU.append("  | _ -> emptyRoute\n");
//          sbV.append("  | _ -> emptyRoute\n");
//      }
//      sbU.append(sbParen);
//      sbV.append(sbParen);
//      sbU.append("\n\n");
//      sbV.append("\n\n");
//      }
//  }

  private Tuple<String, String> graphEdgetoIBGPLoopback() {
    StringBuilder sbU = new StringBuilder();
    StringBuilder sbV = new StringBuilder();
    if (_flags.getLinkFaultsBound() == 0 ) {
      sbU.append("symbolic x : bool (*just a hack to trick the type system*)\n\n")
          .append("let emptyRoute = if (x = x) then {connected=None; static=None; ospf=None; bgp=None; selected=None;} else {connected=None; static=None; ospf=None; bgp=None; selected=None;}\n\n");
    }
    else {
      sbU.append("let emptyRoute = if (hasFailures = hasFailures) then {connected=None; static=None; ospf=None; bgp=None; selected=None;} else {connected=None; static=None; ospf=None; bgp=None; selected=None;}\n\n");
    }

    sbU.append("@noinline @verbatim\n").append("let loopbackU e =\n").append("  match e with\n");
    sbV.append("@noinline @verbatim\n").append("let loopbackV e =\n").append("  match e with\n");
    for (Entry<GraphEdge, BgpActivePeerConfig> e : _graph.getIbgpNeighbors().entrySet()) {
      String nvEdge = _edgeMap.get(e.getKey());
      sbU.append("  | ").append(nvEdge).append(" -> ");
      sbV.append("  | ").append(nvEdge).append(" -> ");
      Iterator<Prefix> i = _originated.iterator();
      Prefix preLocal = null;
      Prefix prePeer = null;
      while (i.hasNext() && (preLocal == null || prePeer == null)) {
        Prefix pre = i.next();
        if (pre.containsIp(e.getValue().getLocalIp()))
          preLocal = pre;
        if (pre.containsIp(e.getValue().getPeerAddress()))
          prePeer = pre;
      }
      sbU.append(FaultAnalysis.ribName(preLocal)).append("[" + _nodeAssignment.get(e.getKey().getPeer()) + "n]").append("\n");
      sbV.append(FaultAnalysis.ribName(prePeer)).append("[" + _nodeAssignment.get(e.getKey().getRouter()) + "n]").append("\n");
      }

      sbU.append(" | _ -> emptyRoute\n\n");
      sbV.append(" | _ -> emptyRoute\n\n");
      return new Tuple<>(sbU.toString(), sbV.toString());
    }


  /***** BGP Transfer Function for Single Prefix ****/
  private void bgpTrans(
      StringBuilder sbNetwork, Table2<String, Protocol, Set<Protocol>> redistributionTable) {

    // Get the origin of the route if model requires it.
    /*if (_flags.doOrigin()) {
      sb.append("  match e with | u~v ->");
    }*/

    boolean headerCodeEmitted = false;

    Set<String> bgpSet = new HashSet<>();

    Tuple<Map<GraphEdge, String>, Map<GraphEdge, String>> policies = computeEquivalentPolicies(sbNetwork);

    StringBuilder sbIBGP = new StringBuilder();
    StringBuilder sbEBGP = new StringBuilder();

    sbEBGP.append("\nlet transferEBGP d e x0 =\n");

//    if (!_ibgpLoopbacks.isEmpty()) {
      // if iBGP is used also pass the IGP routes for links between u and v as arguments to BGP's
      // transfer function.
      sbIBGP.append("\nlet transferIBGP d e x0 igpUV igpVU =\n");

    for (Entry<String, Configuration> entry : _graph.getConfigurations().entrySet()) {
      String router = entry.getKey();
      Configuration config = entry.getValue();

      // Compute redistribution of other protocols into BGP for this router
      // See this:
      // https://www.juniper.net/documentation/en_US/junose15.1/information-products/topic-collections/swconfig-bgp-mpls/index.html?id-24039.html
      // By default you only redistribute the best route into BGP, so we redistribute based on the
      // selected protocol.
      Map<Protocol, String> redistAttrs = redistributeIntoBgp("x0", redistributionTable, router);

      for (GraphEdge edge : _graph.getEdgeMap().get(router)) {
        if ((edge.getPeer() != null) && (_edgeMap.containsKey(edge))) {
          if (_graph.isEdgeUsed(config, Protocol.BGP, edge)
              && !bgpSet.contains(_edgeMap.get(edge))) {
            if (!headerCodeEmitted) {
              sbEBGP.append("  let (prefix, prefixLen) = d in\n")
                  .append("  let prot = x0.selected in\n")
                  .append(" match e with\n");
              sbIBGP.append("  let (prefix, prefixLen) = d in\n")
                  .append("  let prot = x0.selected in\n")
                  .append(" match e with\n");
              headerCodeEmitted = true;
            }

            // Get export policy name
            String expPolicy = policies.getFirst().get(edge);

            // Get import policy name
            GraphEdge invEdge = _graph.getOtherEnd().get(edge);
            String impPolicy = policies.getSecond().get(invEdge);
            StringBuilder sb = edge.isAbstract() ? sbIBGP : sbEBGP;
            sb.append("   | ").append(_edgeMap.get(edge));
            sb.append(" -> ");

            /* Adding redistribution into BGP */
            // NOTE: we have to handle redistribution here because it is a per router setting
            // not a per edge policy setting; the function computeEquivalentPolicies computes
            // equivalent edge policies only (e.g., route-maps but not redistribution).
            String connected =
                redistAttrs.get(Protocol.CONNECTED).equals("None")
                    ? ""
                    : "     | Some 0u2 -> " + redistAttrs.get(Protocol.CONNECTED) + "\n";
            String stat =
                redistAttrs.get(Protocol.STATIC).equals("None")
                    ? ""
                    : "     | Some 1u2 -> " + redistAttrs.get(Protocol.STATIC) + "\n";
            String ospf =
                redistAttrs.get(Protocol.OSPF).equals("None")
                    ? ""
                    : "     | Some 2u2 -> " + redistAttrs.get(Protocol.OSPF) + "\n";

            String routeName = "x0.bgp";
            // Don't bother emitting a match if it would be trivial
            if (!(connected.equals("") && stat.equals("") && ospf.equals(""))) {
              sb.append("   \n(* Handling redistribution into BGP *)\n")
                  .append("   let b = \n")
                  .append("     match x0.selected with\n")
                  .append(connected)
                  .append(stat)
                  .append(ospf)
                  .append("     | _ -> x0.bgp\n")
                  .append("   in\n");
              routeName = "b";
            }

            if (impPolicy == null) {
              if (edge.isAbstract())
                sb.append("    " + expPolicy).append(" e prefix prefixLen prot " + routeName + "igpUV igpVU \n");
              else
                sb.append("    " + expPolicy).append(" e prefix prefixLen prot " + routeName + "\n");
            } else {
              sb.append(impPolicy)
                  .append(" e prefix prefixLen (")
                  .append(expPolicy);
                  if (edge.isAbstract())
                    sb.append(" e prefix prefixLen prot " + routeName + " igpUV igpVU)\n");
                  else
                   sb.append(" e prefix prefixLen prot " + routeName + ")\n");
            }
          }
        }
      }
    }
    if (!headerCodeEmitted) {
      sbEBGP.append(" None");
      sbIBGP.append(" None");
    }
    sbEBGP.append("\n\n");
    sbIBGP.append("\n\n");

    sbNetwork.append(sbEBGP);
    // only append iBGP if it is actually used.
    if (!_ibgpLoopbacks.isEmpty())
      sbNetwork.append(sbIBGP);
  }

  private void ospfTrans(boolean singlePrefix, StringBuilder sb) {
    sb.append(" let transferOspf edge o =\n")
        .append("   match o with\n")
        .append("   | None -> None\n")
        .append("   | Some o -> (\n")
        .append("     match edge with\n");

    Set<String> ospfSet = new HashSet<>();

    for (GraphEdge edge : _graph.getAllRealEdges()) {
      System.out.println(edge);
      if (edge.getPeer() != null) {
        // NOTE: this is a hack to get back the OSPF edge because we only
        // keep track of the iBGP edge/interface when both exist.
        // We convert this OSPF edge to an NV edge directly instead of looking it up in edgeMap,
        // because edgeMap will contain the virtual edge instead.
        // Instead we should keep track of separate _edgeMaps for each protocol probably.

        Optional<String> nvEdge = graphEdgeToNVEdge(edge);
        System.out.println("= " + nvEdge.get());
        Interface iface = edge.getStart();
        Integer cost = iface.getOspfCost() == null ? 1 : iface.getOspfCost();
        Long areaId = iface.getOspfAreaName();
        //        if (!_graph.isInterfaceActive(Protocol.OSPF, iface) || edge.getPeer() == null) {
        //        sb.append("None\n");
        // }
        System.out.println("ospf active:" + _graph.isInterfaceActive(Protocol.OSPF, iface));
        if (_graph.isInterfaceActive(Protocol.OSPF, iface)
            && !ospfSet.contains(nvEdge.get())) {
          String nhop =
              _flags.doMultiPath()
                  ? "match flipEdge edge with | None -> {} | Some fe -> {fe}"
                  : "flipEdge edge";
          String o =
              _attrs.buildOspfAttribute(
                  "o.ospfAd",
                  "o.weight +u16 " + cost + "u16",
                  "if !(o.areaId = " + areaId + ") then ospfInterArea else o.areaType",
                  areaId.toString(),
                  nhop,
                  "o.ospfOrigin");
          ospfSet.add(nvEdge.get());
          sb.append("     | ").append(nvEdge.get()).append(" -> ");
          sb.append(o).append("\n");
        }
      }
    }
    sb.append("     | _ -> None)\n\n");
  }

  // Type declarations
  private void printAttributeType(boolean singlePrefix, StringBuilder sb) {
    String connType = "int8"; // ad
    String staticType = "int8"; // ad
    String bestType = "int2"; // proto
    sb.append("type prefix = (int, int6) (* IP prefix; tuple of (address, length) *)\n")
        .append("type ospfType = ")
        .append(_attrs.buildOspfType())
        .append("\n")
        .append("type bgpType = ")
        .append(_attrs.buildBgpType())
        .append("\n")
        .append("type rib = {\n")
        .append("    connected:")
        .append(optionType(connType))
        .append("; (* Just track administrative distance *)\n")
        .append("    static:")
        .append(optionType(staticType))
        .append("; (* Just track administrative distance *)\n")
        .append("    ospf:")
        .append(optionType("ospfType"))
        .append(";\n")
        .append("    bgp:")
        .append(optionType("bgpType"))
        .append(";\n")
        .append("    selected:")
        .append(optionType(bestType))
        .append("; (* Which protocol has the best route *) }\n");

    // Either a single attribute or a map of attributes from prefix to route.
    sb.append("type attribute = ")
        .append(singlePrefix ? "rib" : dictType("prefix", "rib"))
        .append("\n\n");

    // Definitions for "best" field
    sb.append("(* Definitions for the \"best\" field *)\n")
        .append("let p_CONNECTED = 0u2\n")
        .append("let p_STATIC = 1u2\n")
        .append("let p_OSPF = 2u2\n")
        .append("let p_BGP = 3u2\n\n");
  }

  /********** Merge Function ****************/
  private void merge(boolean singlePrefix, StringBuilder sb) {
    sb.append("let min x y = x <u8 y\n\n");

    sb.append("(* Compute the better of x and y according to f *)\n")
        .append("(* Return a boolean (true for x, false for y) for efficiency reasons *)\n")
        .append("let pickOption f x y =\n")
        .append("  match (x,y) with\n")
        .append("  | (None, _) -> false")
        .append("  | (_, None) -> true\n")
        .append("  | (Some a, Some b) -> f a b\n\n");

    sb.append("let pickMinOption = pickOption min\n\n");

    if ((_flags.doMultiPath()) && (_flags.doNextHop())) {
      sb.append(
          "let union (s1 : [C]dict[[C]tedge, [C]bool]) (s2 : [C]dict[[C]tedge, [C]bool]) = combine (fun x y -> x || y) s1 s2\n\n");
    }

    sb.append(
            "(* OSPF Route ranking: first compare areas, then weights. \n  Multipath is applied by default if enabled during translation.*)\n")
        .append("let betterOspf o1 o2 =\n")
        .append("  if o1.areaType <u2 o2.areaType then o1\n")
        .append("  else if o2.areaType <u2 o1.areaType then o2\n")
        .append("  else if o1.weight <u16 o2.weight then o1\n")
        .append("  else if o2.weight <u16 o1.weight then o2\n");
    if (_flags.doMultiPath()) {
      sb.append("  else {o1 with ospfNextHop = union o1.ospfNextHop o2.ospfNextHop}\n\n");
    } else {
      sb.append("  else {o1 with ospfMultiPath = o1.ospfMultiPath +. o2.ospfMultiPath}\n\n");
    }

    sb.append("let mergeOption f x y =\n")
        .append("  match (x,y) with\n")
        .append("  | (None, _) -> y")
        .append("  | (_, None) -> x\n")
        .append("  | (Some a, Some b) -> Some (f a b)\n\n");

    sb.append(
            "(* BGP Route ranking: first compare local pref, then path length, then MED. \n"
                + "       If multipath is disabled then tie-break is arbitrary (normally, the router id should be used) *)\n")
        .append("let betterBgp multiPath b1 b2 =\n")
        .append("  if b1.lp > b2.lp then b1\n")
        .append("  else if b2.lp > b1.lp then b2\n")
        .append("  else if b1.aslen < b2.aslen then b1\n")
        .append("  else if b2.aslen < b1.aslen then b2\n")
        .append("  else if b1.med < b2.med then b1\n")
        .append("  else if b1.med > b2.med then b2\n")
        .append("  else if (b1.ibgp = false) && b2.ibgp then b1\n")
        .append("  else if b1.ibgp && (b2.ibgp = false) then b2\n")
        .append("  else if b1.igpMetric <u16 b2.igpMetric then b1\n")
        .append("  else if b2.igpMetric <u16 b1.igpMetric then b2\n");

    if (_flags.doMultiPath()) {
      sb.append(
              "  else if multiPath then {b1 with bgpNextHop = union b1.bgpNextHop b2.bgpNextHop}\n")
          .append("  else b1\n\n");
    } else {
      sb.append("  else b1\n\n");
    }

    sb.append(
            "(* Determine which of the four protocols has the best route by comparing their ADs *)\n")
        .append("let best c s o b =\n")
        .append("  match (c,s,o,b) with\n")
        .append("  (* If no protocol has a route, then we have no route at all *)\n")
        .append("  | (None,None,None,None) -> None\n")
        .append("  | _ -> \n")
        .append("      (* Otherwise, get administrative distances for osfp and bgp... *)\n")
        .append("      let o = match o with | None -> None | Some o -> Some o.ospfAd in\n")
        .append("      let b = match b with | None -> None | Some b -> Some b.bgpAd in\n")
        .append("      (* ...and figure out which of the protocols has the lowest AD *)\n")
        .append(
            "      let (x,p1) = if pickMinOption c s then (c,p_CONNECTED) else (s,p_STATIC) in\n")
        .append("      let (y,p2) = if pickMinOption o b then (o,p_OSPF) else (b,p_BGP) in\n")
        .append("      Some (if pickMinOption x y then p1 else p2)\n\n");

    sb.append(
            "(* Compute the best route for each protocol individually, then select the best one *)\n")
        .append("let mergeValues bgpMultiPathEnabled x y =\n")
        .append(
            "  let c = if (pickMinOption x.connected y.connected) then x.connected else y.connected in\n")
        .append("  let s = if (pickMinOption x.static y.static) then x.static else y.static in\n")
        .append("  let o = mergeOption betterOspf x.ospf y.ospf in\n")
        .append("  let b = mergeOption (betterBgp bgpMultiPathEnabled) x.bgp y.bgp in\n")
        .append(
            "  { connected = c;\n"
                + "    static = s;\n"
                + "    ospf = o;\n"
                + "    bgp = b;\n"
                + "    selected = best c s o b}\n\n");

    /* Merge one attribute or combine over map */

    // If multipath is enabled then do a match depending on whether a router has enabled multiPath
    // or not.
    // TODO: we should separate the boolean for multi path depending on OSPF or BGP multipath.
    // TODO: for now I don't see OSPF configurations including multipath options in batfish, so I
    // will keep it on as default.
    if (_flags.doMultiPath()) {
      if (singlePrefix) {
        sb.append("let merge node x y =\n");

        StringBuilder sbMultiPath = new StringBuilder();
        // Do all nodes use BGP multipath or single path?
        boolean allMultiPath = true;
        boolean allSinglePath = true;

        for (Entry<String, Configuration> entry : _graph.getConfigurations().entrySet()) {
          String router = entry.getKey();
          Integer u = _nodeAssignment.get(router);
          Configuration config = entry.getValue();
          BgpProcess bgpProc = config.getDefaultVrf().getBgpProcess();

          if (bgpProc == null) {
            allMultiPath = false;
            continue;
          }

          if (bgpProc.getMultipathEbgp()) {
            allSinglePath = false;
            sbMultiPath.append("  | ").append(u).append("n -> mergeValues true x y\n");
          } else {
            allMultiPath = false;
            sbMultiPath.append("  | ").append(u).append("n -> mergeValues false x y\n");
          }
        }
        if (allMultiPath) {
          sb.append("  mergeValues true x y\n");
        } else if (allSinglePath) {
          sb.append("  mergeValues false x y\n");
        } else {
          sb.append("  match node with\n").append(sbMultiPath);
        }

      } else {
        sb.append("let merge node x y = combine mergeValues x y\n");
      }
    } else {
      if (singlePrefix) {
        sb.append("let merge node x y = mergeValues false x y\n");

      } else {
        sb.append("let merge node x y = combine mergeValues x y\n");
      }
    }
    sb.append("\n");
  }

  /* Writes a csv file associating nodes to the prefixes they announce. */
  private void writeOriginatedPrefixes(String file) {

    File directory = new File(file);
    if (!directory.exists()) {
      directory.mkdir();
    }
    File csvFile = new File(file + "/" + file + "Prefixes.csv");

    try {
      FileWriter csvWriter = new FileWriter(csvFile);

      for (Entry<Integer, Set<Prefix>> e : _perNodePrefixes.entrySet()) {
        if (!e.getValue().isEmpty()) {
          for (Prefix p : e.getValue()) {
            csvWriter.append(e.getKey().toString());
            csvWriter.append(",").append(p.toString()).append("\n");
          }
        }
      }

      csvWriter.flush();
      csvWriter.close();
    } catch (IOException ioException) {
      ioException.printStackTrace();
    }
  }

  /* Writes a csv file with default failure probabilities and link capacity info if it does not exist */
  private void writeFailureProbabilities(String file, boolean nodes) {

    if (nodes) {
      File directory = new File(file);
      if (!directory.exists()) {
        directory.mkdir();
      }
    }
    File csvFile = new File(file + "/" + file + "Failure.csv");
    // append for the links.
    try {
      FileWriter csvWriter = new FileWriter(csvFile, !nodes);

      if (!nodes) {
        // First we process all edges that have a physical manifestation (i.e., non-IBGP edges)
        for (GraphEdge e : _graph.getAllRealEdges()) {
          Optional<String> edgeStr = graphEdgeToNVEdge(e);
          if (edgeStr.isPresent()) {
            String probability = "0.005";
            csvWriter
                .append("link,")
                .append(edgeStr.get())
                .append(",")
                .append(probability)
                .append(",")
                .append("40000");
            csvWriter.append("\n");
            _linkFailureProbability.put(edgeStr.get(), 0.005);
            _linkCapacities.put(edgeStr.get(), 40000.0);
            // Add to reverse edge map at this point if the file is created.
            _reverseEdgeMap.put(edgeStr.get(), e);
          }
        }
        // Then we process all iBGP edges.
        for (GraphEdge e : _graph.getIbgpNeighbors().keySet()) {
          Optional<String> edgeStr = graphEdgeToNVEdge(e);
          // In the iBGP case we only add a virtual edge to the CSV if there is no physical link.
          // The probability of a purely virtual edge to fail is 0.0.
          if (edgeStr.isPresent()) {
            if (!_linkFailureProbability.containsKey(edgeStr.get())) {
              String probability = "0.0";
              csvWriter
                  .append("link,")
                  .append(edgeStr.get())
                  .append(",")
                  .append(probability)
                  .append(",")
                  .append("40000");
              csvWriter.append("\n");
              _linkFailureProbability.put(edgeStr.get(), 0.005);
              _linkCapacities.put(edgeStr.get(), 40000.0);
              // Add to reverse edge map at this point if the file is created.
              _reverseEdgeMap.put(edgeStr.get(), e);
            } else {
              // If an edge has both a physical and virtual manifestation we keep
              // track of the virtual edge for the purpose of generating policy.
              // The link can still fail (so non-zero probability) but it will not fail for
              // the purpose of iBGP.
              // We assume that a link cannot be used for both eBGP and iBGP.
              _reverseEdgeMap.put(edgeStr.get(), e);
            }
          }
        }
      } else {
        for (String e : _graph.getRouters()) {
          csvWriter.append("node,").append(e).append(",").append("0.01");
          csvWriter.append("\n");
          _nodeFailureProbability.put(e, 0.01);
        }
      }

      csvWriter.flush();
      csvWriter.close();
    } catch (IOException ioException) {
      ioException.printStackTrace();
    }
  }

  /* Parse the failure probability of each link/device. If the file does not exist/create it.
   *  Expected format is type_of_failure, name, probability */
  private void parseFailureProbabilities(File csvFile, boolean node) {
    try (Scanner scanner = new Scanner(csvFile)) {

      // CSV file delimiter
      Pattern DELIMITER = Pattern.compile("[\\r\\n;]+|,");

      // set comma as delimiter
      scanner.useDelimiter(DELIMITER);

      while (scanner.hasNext()) {
        String type = scanner.next();
        String name = scanner.next();
        String prob = scanner.next();

        if (type.equals("link") && !node) {
          String capacity = scanner.next();
          _linkFailureProbability.put(name, Double.valueOf(prob));
          _linkCapacities.put(name, Double.valueOf(capacity));
        } else if (type.equals("node") && node) {
          _nodeFailureProbability.put(name, Double.valueOf(prob));
        }
      }
      // In case the file is parsed, and this is parsing for links
      // then we need to populate the reverse edge map.
      populateReverseEdgeMap();
    } catch (IOException ex) {
      ex.printStackTrace();
    }
  }

  private Optional<String> graphEdgeToNVEdge(GraphEdge ge) {
    Integer node1 = _nodeAssignment.get(ge.getRouter());
    Integer node2 = _nodeAssignment.get(ge.getPeer());
    if (node2 == null) return Optional.empty();

    String edgeString = "(" + node1 + "~" + node2 + ")";
    return Optional.of(edgeString);
  }

  private void populateReverseEdgeMap() {
    for (GraphEdge ge : _graph.getAllRealEdges()) {
      Optional<String> ges = graphEdgeToNVEdge(ge);
      ges.ifPresent(s -> _reverseEdgeMap.put(s, ge));
    }
  }

  /* Compiles the topology of the network.
   The ids assigned to nodes/edges are based on the probability to fail.
  */
  private String compileTopology() {

    // Parse failure probabilities for nodes if there are any, otherwise generate them.
    // We first do the nodes because we need the node assignments.
    File csvFile = new File(_topologyModel);
    if (_topologyModel.equals("") || !csvFile.isFile()) {
      System.out.println(
          "\nNo failure model provided or it was not found - generating a default failure model.");
      writeFailureProbabilities(_filename, true);
    } else {
      parseFailureProbabilities(csvFile, true);
    }

    // Group nodes by their probability
    for (Entry<String, Double> e : _nodeFailureProbability.entrySet()) {
      Set<String> group = _nodeGroups.computeIfAbsent(e.getValue(), k -> new TreeSet<>());
      group.add(e.getKey());
    }

    // assign each node to a unique number starting from 0.
    // Numbers are assigned in increasing order per failure probability group.
    int i = 0;
    for (Set<String> group : _nodeGroups.values()) {
      for (String router : group) {
        _nodeAssignment.put(router, i);
        i++;
      }
    }

    // Now parse/write link probabilities
    if (_topologyModel.equals("") || !csvFile.isFile()) {
      writeFailureProbabilities(_filename, false);
    } else {
      parseFailureProbabilities(csvFile, false);
    }

    // Group links by their probability.

    for (Entry<String, Double> e : _linkFailureProbability.entrySet()) {
      // For every string, probability entry, then add the string to that probability group.
      Set<String> group = _edgeGroups.computeIfAbsent(e.getValue(), k -> new TreeSet<>());
      group.add(e.getKey());
    }

    StringBuilder sb = new StringBuilder();

    sb.append("nodes = (").append(i).append(", {\n");

    for (Entry<String, Integer> e : _nodeAssignment.entrySet()) {
      sb.append("  ").append(e.getValue()).append("n:\"").append(e.getKey()).append("\";\n");
    }

    sb.append(" })\n\n");

    Set<String> edgeSet = new HashSet<>();

    // Initialize adjacency list

    _adj = new ArrayList<>();

    for (int j = 0; j < i; ++j) _adj.add(j, new LinkedList<>());

    // Create the edge map of the network. We are currently ignoring hanging edges.
    // We should think how to represent those in the NV network. Perhaps, as an extra
    // (or two, we used to say we need two but I don't remember why) node connected to all nodes
    // with hanging edges.

    int edgeId = 0;
    sb.append("edges = {\n");
    for (Set<String> group : _edgeGroups.values()) {
      for (String strEdge : group) {
        GraphEdge edge = _reverseEdgeMap.get(strEdge);
        Integer node1 = _nodeAssignment.get(edge.getRouter());
        Integer node2 = _nodeAssignment.get(edge.getPeer());
        _edgeMap.put(edge, strEdge);
        _edgeIds.put(edge, edgeId);
        _idToEdge.put(edgeId, edge);
        if (!edgeSet.contains(strEdge)) {
          edgeSet.add(strEdge);
          _adj.get(node1).add(node2);
          sb.append("  (")
              .append(node1)
              .append("-")
              .append(node2)
              .append(",")
              .append(edgeId)
              .append(")")
              .append("; (*")
              .append(edge.asRouters())
              .append("*)\n");
          edgeId++;
        }
      }
    }

    sb.append("}\n\n");

    /* Emit info about the topology */
    File topologyCsv = new File(_filename + "/" + _filename + "Topology.csv");
    // append for the links.
    try {
      FileWriter csvWriter = new FileWriter(topologyCsv);

      for (Entry<GraphEdge, String> e : _edgeMap.entrySet()) {
        edgeId = _edgeIds.get(e.getKey());
        csvWriter
            .append(e.getKey().asRouters())
            .append(",")
            .append(e.getValue())
            .append(",")
            .append(String.format("%d", edgeId));
        csvWriter.append("\n");
      }

      csvWriter.flush();
      csvWriter.close();
    } catch (IOException e) {
      e.printStackTrace();
    }

    return sb.toString();
  }

  /***** Control Plane ********/

  /* Computes the init function and the prefixes announced in the network */
  private String computeInitFunction(StringBuilder sb) {
    sb.append("let init d node =\n");

    /* init depends on whether we do a single route or not */
    sb.append("  match node with\n");

    // All prefixes originated
    Set<Prefix> allPrefixes = new HashSet<>();

    for (Entry<String, Configuration> entry : _graph.getConfigurations().entrySet()) {
      String router = entry.getKey();
      Configuration conf = entry.getValue();
      Integer nodeId = _nodeAssignment.get(router);
      sb.append("  | ").append(nodeId).append("n -> \n");
      Set<Prefix> connPrefixes = Graph.getOriginatedNetworks(conf, Protocol.CONNECTED);
      Set<Prefix> staticPrefixes = Graph.getOriginatedNetworks(conf, Protocol.STATIC);
      Set<Prefix> ospfPrefixes = Graph.getOriginatedNetworks(conf, Protocol.OSPF);
      Set<Prefix> bgpPrefixes = Graph.getOriginatedNetworks(conf, Protocol.BGP);

      /*NOTE: Just keeping track of the BGP and OSPF routes for now
        Not sure all our configs have connected hosts to them, but it can be easily fixed if needed.
      */
      Set<Prefix> nodePrefixes = new HashSet<>();
      nodePrefixes.addAll(ospfPrefixes);
      nodePrefixes.addAll(bgpPrefixes);
      System.out.println("node:" + nodeId + "prefix" + ospfPrefixes.toString());
      _perNodePrefixes.put(nodeId, nodePrefixes);

      // Prefixes originated by just one router.
      Set<Prefix> routerPrefixes = new HashSet<>();

      routerPrefixes.addAll(connPrefixes);
      routerPrefixes.addAll(staticPrefixes);
      routerPrefixes.addAll(ospfPrefixes);
      routerPrefixes.addAll(bgpPrefixes);

      // BGP/OSPF prefixes originated by any node
      _originated.addAll(bgpPrefixes);
      _originated.addAll(ospfPrefixes);

      allPrefixes.addAll(routerPrefixes);

      Map<Prefix, Long> ospfAreaIds = new HashMap<>();
      OspfProcess ospf = getFirstOspfProcess(conf.getDefaultVrf());
      if (ospf != null) {
        for (Entry<Long, OspfArea> e : ospf.getAreas().entrySet()) {
          Long areaId = e.getKey();
          OspfArea area = e.getValue();
          for (String ifaceName : area.getInterfaces()) {
            Interface iface = conf.getAllInterfaces().get(ifaceName);
            if (iface.getActive() && iface.getOspfEnabled()) {
              ospfAreaIds.put(iface.getConcreteAddress().getPrefix(), areaId);
            }
          }
        }
      }

      // Building init function
      Map<InitialAttribute, Set<Prefix>> attributePrefixMap = new HashMap<>();

      // Only compute an attribute if at least one protocol announces a route.
      for (Prefix prefix : routerPrefixes) {
        Boolean c = connPrefixes.contains(prefix);
        Boolean s = staticPrefixes.contains(prefix);
        Optional<Long> o = Optional.empty();
        if (ospfPrefixes.contains(prefix)) {
          o = Optional.of(ospfAreaIds.get(prefix));
        }
        Boolean b = bgpPrefixes.contains(prefix);

        InitialAttribute a = new InitialAttribute(c, s, o, b, _flags);
        Set<Prefix> prefixS = new HashSet<>();
        prefixS.add(prefix);
        if (attributePrefixMap.containsKey(a)) {
          prefixS.addAll(attributePrefixMap.get(a));
          attributePrefixMap.replace(a, prefixS);
        } else {
          attributePrefixMap.put(a, prefixS);
        }
      }

      /* This induces a large repetition of code but it's ok for now */
      for (Entry<InitialAttribute, Set<Prefix>> attrpre : attributePrefixMap.entrySet()) {
        String initAttr = attrpre.getKey().compileAttr(nodeId, true);
        Boolean first = true;
        sb.append("    if ");
        for (Prefix pre : attrpre.getValue()) {
          if (!first) {
            sb.append(" || ");
          }
          sb.append("d = (").append(pre).append(")");
          first = false;
        }
        sb.append(" then\n").append(initAttr).append("    else\n");
      }
      sb.append("  {connected=None; static=None; ospf=None; bgp=None; selected=None;}\n");
    }

    sb.append("  | _ -> {connected=None; static=None; ospf=None; bgp=None; selected=None;}\n\n");
    return sb.toString();
  }

  public String compileControlPlane(
      String init,
      String topology,
      Triple<Tuple<String, String>, Map<Prefix, String>, Map<Prefix, String>> linkFailures) {
    StringBuilder sb = new StringBuilder();

    printAttributeType(true, sb);

    sb.append("(** Useful helper definitions **)\n\n");
    sb.append("let ospfIntraArea = 0u2\n")
        .append("let ospfInterArea = 1u2\n")
        .append("let ospfE1 = 2u2\n")
        .append("let ospfE2 = 3u2\n\n");

    sb.append("(* Check if the selected protocol is x *)\n");
    sb.append("let isProtocol fib x =\n")
        .append("  match fib with\n")
        .append("  | None -> false\n")
        .append("  | Some y -> x = y\n\n");

    sb.append("let flipEdge e = \n").append("  match e with").append("  | a~b -> toEdge b a\n\n");

    sb.append("let getSourceNode e = \n").append("  match e with").append("  | a~b -> a\n\n");

    // Print out merge function
    merge(true, sb);

    // Add init function
    sb.append(init);

    // Redistribution into BGP
    Redistribution redistributionTable = new Redistribution(_graph);

    // Route Aggregation - We don't do aggregation for now.
    //    Aggregation agg = new Aggregation(_graph, allPrefixes);

    // OSPF transfer function
    ospfTrans(true, sb);

    /* Are there any iBGP neighbors? */
    boolean hasIBGP = !_ibgpLoopbacks.isEmpty();

    /* If there are iBGP neighbors, then we first need to compute the routes
    of the loopbacks. */
    if (hasIBGP) {
      sb.append("let trans d edge x = \n")
          .append(
              "  {connected=None; static=None; ospf=transferOspf edge x.ospf; bgp=None; selected=None}\n\n");
      sb.append(linkFailures.getFirst().getFirst());
    }

    if (hasIBGP) {
      // Emit solution declarations for loopbacks
      for (Entry<Prefix, String> e : linkFailures.getSecond().entrySet()) {
        sb.append(e.getValue());
      }
    }

    // BGP transfer function
    bgpTrans(sb, redistributionTable.getRedistributedProtocols());

    // All other prefixes (non-iBGP loopbacks)
    if (!_ibgpLoopbacks.isEmpty()) {
      Tuple<String, String> loopbacks = graphEdgetoIBGPLoopback();
      sb.append(loopbacks.getFirst());
      sb.append(loopbacks.getSecond());
      sb.append(linkFailures.getFirst().getSecond());
    }
    else {
      sb.append("let trans d edge x = \n")
          .append("  let o = transferOspf edge x.ospf in\n")
          .append("  let b = transferEBGP d edge x in\n")
          .append("  {connected=None; static=None; ospf=o; bgp=b; selected=None}\n\n");

      sb.append(linkFailures.getFirst().getFirst());
}
    for (Entry<Prefix, String> e : linkFailures.getThird().entrySet()) {
      sb.append(e.getValue());
    }

    sb.append(topology);
    /* Print node assignments for usability reasons */
    sb.append("(*\n").append(_nodeAssignment.toString()).append("*)");

    return sb.toString();
  }

  /* Putting everything together */
  public CompilerResult compile(boolean singlePrefix) {

    // Generate the network's topology - this populates various maps such as edgeMap and
    // nodeAssignment.
    String topology = compileTopology();

    // Generate the init function and populate some info such as edge ids, prefixes announced, etc.
    String initFunc = computeInitFunction(new StringBuilder());

    /* Export the prefixes announced by each node in CSV form.*/
    writeOriginatedPrefixes(_filename);

    // Find the loopback addresses for iBGP (must happen before FaultAnalysis)
    computeIBGPLoopbacks();

    /* Fault analysis is done before the control plane model creation to accommodate for iBGP. */
    FaultAnalysis faults =
        new FaultAnalysis(
            _filename,
            _nodeAssignment,
            _edgeMap,
            _adj,
            _nodeAssignment.size(),
            _originated,
            _ibgpLoopbacks,
            _flags.getSymbolicOrder(),
            _linkFailureProbability,
            _nodeFailureProbability,
            _edgeGroups);

    //    if (_flags.doBoundedLinkFaults()) {
    // This code also generates solutions now so we execute it no matter what.
    Triple<Tuple<String, String>, Map<Prefix, String>, Map<Prefix, String>> boundedLinkFaults =
        faults.compileBoundedLinkFaults(
            _flags.conditionalProbabilityModel(),
            _flags.getLinkFaultsBound(),
            !_flags.doDataplane());
    //    }

    String controlplane = compileControlPlane(initFunc, topology, boundedLinkFaults);
    String dataplane = "";


    Tuple<String, Map<Prefix, String>> allFaults = null;
    Tuple<String, Map<Prefix, String>> allLinkFaults = null;

    /* For all possible failures analysis */
    if (_flags.doNodeFaults()) {
      allFaults = faults.compileAllFaults(singlePrefix);
      allLinkFaults = faults.compileAllLinkFaults(singlePrefix);
    }

    if (_flags.doDataplane()) {
      Dataplane data =
          new Dataplane(
              _nodeAssignment,
              _edgeMap,
              _reverseEdgeMap,
              _idToEdge,
              _originated,
              _perNodePrefixes,
              _linkCapacities,
              faults,
              _flags);
      dataplane = data.generateDataplane();
    }

    return new CompilerResult(controlplane, dataplane, allFaults, allLinkFaults, boundedLinkFaults);
  }
}
