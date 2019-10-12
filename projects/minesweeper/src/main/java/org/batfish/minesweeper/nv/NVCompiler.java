package org.batfish.minesweeper.nv;

import static org.batfish.minesweeper.Graph.getFirstOspfProcess;

import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import org.batfish.common.plugin.IBatfish;
import org.batfish.datamodel.Configuration;
import org.batfish.datamodel.Interface;
import org.batfish.datamodel.Prefix;
import org.batfish.datamodel.ospf.OspfArea;
import org.batfish.datamodel.ospf.OspfProcess;
import org.batfish.datamodel.routing_policy.RoutingPolicy;
import org.batfish.datamodel.routing_policy.statement.Statement;
import org.batfish.minesweeper.Graph;
import org.batfish.minesweeper.GraphEdge;
import org.batfish.minesweeper.Protocol;
import org.batfish.minesweeper.utils.Tuple;

class InitialAttribute {
  private Boolean _conn;
  private Boolean _static;
  private Optional<Long> _areaId;
  private Boolean _bgp;

  public InitialAttribute(Boolean conn, Boolean stat, Optional<Long> areaId, Boolean bgp) {
    _conn = conn;
    _static = stat;
    _areaId = areaId;
    _bgp = bgp;
  }

  public String compileAttr(boolean singlePrefix) {
    StringBuilder sb = new StringBuilder();
    String c = this._conn ? "Some 0u8" : "None";
    String s = this._static ? "Some 1u8" : "None";
    String o = "None";
    if (this._areaId.isPresent()) {
      o = "Some {ospfAd=110u8; weight=0u16; areaType=ospfIntraArea; areaId=" + _areaId.get() + ";}";
    }
    String b = _bgp ? "Some {bgpAd=20u8; lp=100; aslen=0; med=80; comms={};}" : "None";
    // String b = _bgp ? "Some (20, 100, 0, 80)" : "None";
    sb.append("    let c = ")
        .append(c)
        .append(" in\n")
        .append("    let s = ")
        .append(s)
        .append(" in\n")
        .append("    let o = ")
        .append(o)
        .append(" in\n")
        .append("    let b = ")
        .append(b)
        .append(" in\n")
        .append("    let fib = best c s o b in\n");
    if (singlePrefix) {
      sb.append("      {connected=c; static=s; ospf=o; bgp=b; selected=fib;}\n");
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

  private Graph _graph;

  public NVCompiler(IBatfish batfish) {
    _graph = new Graph(batfish);
  }

  private String optionType(String typ) {
    return "option["+typ+"]";
  }

  private String dictType(String keyTyp, String valTyp) {
    return "dict["+keyTyp+", "+ valTyp+"]";
  }

  private void bgpTrans(boolean singlePrefix, StringBuilder sb, Map<GraphEdge, String> edgeMap) {

    /*
    sb.append(" let transferBgp edge x =\n")
        .append("  let (prefix, prefixLen) = d in\n")
        .append("  let b = match x.selected with\n")
        .append("           | None -> None\n")
        .append("           | Some 0 -> Some {bgpAd = 0; lp = 100; aslen = 0; med = 80; comms = {}}\n")
        .append("           | Some 1 -> Some {bgpAd = 1; lp = 100; aslen = 0; med = 80; comms = {}}\n")
        .append("           | Some 2 -> Some {bgpAd = 110; lp = 100; aslen = 0; med = 80; comms = {}}\n")
        .append("           | Some 3 -> x.bgp\n")
        .append("  in\n")
        .append("  match b with\n")
        .append("  | None -> None\n")
        .append("  | Some b -> (\n")
        .append("   match edge with\n"); */

    if (!singlePrefix) {
      sb.append(" let transferBgpImpPol policy x =\n")
          .append("  match x.bgp with\n")
          .append("  | None -> {x with bgp=None}\n")
          .append("  | Some b ->\n")
          .append("    {x with bgp=policy x.selected b}\n\n");
    }

    sb.append(" let transferBgpPol policy x =\n")
        .append("  let b = match x.selected with\n")
        .append("          | None -> None\n")
        .append(
            "          | Some 0u2 -> Some {bgpAd = 0u8; lp = 100; aslen = 0; med = 80; comms = {}}\n")
        .append(
            "          | Some 1u2 -> Some {bgpAd = 1u8; lp = 100; aslen = 0; med = 80; comms = {}}\n")
        .append(
            "          | Some 2u2 -> Some {bgpAd = 110u8; lp = 100; aslen = 0; med = 80; comms = {}}\n")
        .append("          | Some 3u2 -> x.bgp\n")
        .append("  in\n")
        .append("  match b with\n");
    if (!singlePrefix) {
          sb.append("  | None -> {x with bgp=None}\n")
          .append("  | Some b ->\n")
          .append("    {x with bgp=policy x.selected b}\n\n");
    }
    else {
      sb.append("  | None -> None\n")
          .append("  | Some b ->\n")
          .append("    policy x.selected b\n\n");
    }

    sb.append(" let transferBgp e x0 =\n").append("  match e with\n");

    Set<String> bgpSet = new HashSet<>();
    for (Entry<String, Configuration> entry : _graph.getConfigurations().entrySet()) {
      String router = entry.getKey();
      Configuration config = entry.getValue();
      for (GraphEdge edge : _graph.getEdgeMap().get(router)) {
        if (edge.getPeer() != null) {
          Interface iface = edge.getStart();
          //_graph.isInterfaceActive(Protocol.BGP, iface) the guard below used to be this.
          System.out.println("Encoding BGP edge: " + edge);
          if (_graph.isEdgeUsed(config, Protocol.BGP, edge) && !bgpSet.contains(edgeMap.get(edge))) {
            bgpSet.add(edgeMap.get(edge));
            RoutingPolicy policy = _graph.findExportRoutingPolicy(router, Protocol.BGP, edge);
            List<Statement> statements;
            if ((policy != null) && (policy.getStatements() != null)) {
              statements = policy.getStatements();
            } else {
              statements = Collections.emptyList();
            }

            sb.append("   | ").append(edgeMap.get(edge)).append(" ->\n");

            // Build the decision tree for the export policy
            TransferFunctionBuilder exportTransBuilder = new TransferFunctionBuilder(config,
                statements,
                edge,
                true);
            DecisionTree<Boolean> exportTree = exportTransBuilder.compute();

            // Normalize the tree if we want to export for simulation.
            if (!singlePrefix) {
              exportTransBuilder.normalize(exportTree);
            }
            // Apply some basic optimizations
            exportTree.optimize();

            String impPolicy = "x0"; // holds result of export
            /* Build NV string that corresponds to export tree */
            TreeCompiler exportTreeCompiler = new TreeCompiler(exportTree, null, config);
            if (!singlePrefix) {
              List<Tuple<String, String>> expPolicies = exportTreeCompiler.toNvStrings();
              int numberOfPolicies = expPolicies.size();
              // If there is only one policy there is no branching on prefixes just map the policy.
              if (numberOfPolicies == 1) {
                sb.append("     let x0 = map (transferBgpPol (fun prot b -> \n")
                    .append("           " + expPolicies.get(0).getSecond() + ")) x0\n")
                    .append("     in\n");
              } else {
                for (int idx = 0; idx < numberOfPolicies; idx++) {
                  sb.append("     let x" + (idx + 1) + " =\n")
                      .append("     mapIf (fun p -> let (prefix, prefixLen) = p in\n"
                          + "                   " + expPolicies.get(idx).getFirst() + ")\n")
                      .append("         (transferBgpPol (fun prot b ->\n")
                      .append("               " + expPolicies.get(idx).getSecond() + ")) x" + idx
                          + "\n")
                      .append("     in\n");
                }
                impPolicy = "x" + numberOfPolicies;
              }
            } else {
              impPolicy = "b";
              String expPolicy = exportTreeCompiler.toNvString();
              sb.append("     let (prefix, prefixLen) = d in\n")
                  .append("     let b = (transferBgpPol (fun prot b -> \n")
                  .append("           " + expPolicy + ")) x0\n")
                  .append("     in\n");
            }
            // Do import policy
            List<Statement> importStatements;

            // DecisionTree<Boolean> policyTree = exportTree;
            GraphEdge invEdge = _graph.getOtherEnd().get(edge);
            //TreeCompiler treeCompiler = null;

            if (invEdge != null) {
              String otherRouter = invEdge.getRouter();
              RoutingPolicy importPolicy = _graph.findImportRoutingPolicy(otherRouter,
                  Protocol.BGP,
                  invEdge);
              Configuration invConfig = _graph.getConfigurations().get(otherRouter);
              if ((importPolicy != null) && (importPolicy.getStatements() != null)) {
                importStatements = importPolicy.getStatements();
                TransferFunctionBuilder importTransBuilder = new TransferFunctionBuilder(invConfig,
                    importStatements,
                    invEdge,
                    false);
                DecisionTree<Boolean> importTree = importTransBuilder.compute();
                if (!singlePrefix) {
                  importTransBuilder.normalize(importTree);
                }
                importTree.optimize();

                TreeCompiler importTreeCompiler = new TreeCompiler(importTree, invConfig, null);
                if (!singlePrefix) {
                  List<Tuple<String, String>> impPolicies = importTreeCompiler.toNvStrings();
                  int numberOfPolicies = impPolicies.size();
                  // If there is only one policy there is no branching on prefixes just map the policy.
                  if (numberOfPolicies == 1) {
                    sb.append("     map (transferBgpImpPol (fun prot b -> \n")
                        .append("           " + impPolicies.get(0).getSecond() + ")) " + impPolicy + "\n");
                  } else {
                    for (int idx = 0; idx < numberOfPolicies; idx++) {
                      sb.append("     let x" + (idx + 1) + " =\n")
                          .append("     mapIf (fun p -> let (prefix, prefixLen) = p in\n"
                              + "                   " + impPolicies.get(idx).getFirst() + ")\n")
                          .append("         (transferBgpImpPol (fun prot b ->\n");
                      if (idx == 0) {
                        sb.append("               " + impPolicies.get(idx).getSecond() + ")) " + impPolicy
                            + "\n");
                      } else {
                        sb.append(
                            "               " + impPolicies.get(idx).getSecond() + ")) x" + idx + "\n");
                      }
                      sb.append("     in\n");
                    }
                    sb.append("     x" + numberOfPolicies + "\n\n");
                  }
                } else {
                  impPolicy = importTreeCompiler.toNvString();
                  sb.append("         (match b with\n")
                      .append("         | None -> None\n")
                      .append("         | Some b ->\n")
                      .append("           " + impPolicy + ")\n\n");
                }
                //policyTree = importTransBuilder.compute(exportTree);
                //treeCompiler = new TreeCompiler(policyTree, invConfig, config);
              }
              else { sb.append("      " + impPolicy + "\n\n"); }
            }
            else { sb.append("      " + impPolicy + "\n\n"); }
          }
        }
      }
    }
  }


           /* if (treeCompiler == null) {
              treeCompiler = new TreeCompiler(policyTree, null, config);
            }

            String computedPolicy = treeCompiler.toNvString();
*/
           /* if (!computedPolicy.equals("None")) {
              sb.append("   | ").append(edgeMap.get(edge)).append(" -> ");
              sb.append("\n    ");
              sb.append(computedPolicy);
              sb.append("\n");
            } */
           /* if (!expPolicy.equals("None")) {
              sb.append("   | ").append(edgeMap.get(edge)).append(" -> ");
              sb.append("\n    let b = \n" + expPolicy + "\n    in\n");
              if (impPolicy.equals("b")) {
                sb.append("      b\n");
              } else {
                sb.append("    (match b with\n")
                    .append("     | None -> None\n")
                    .append("     | Some b -> \n")
                    .append("      " + impPolicy + ")\n");
              }
            }*/

  private void ospfTrans(boolean singlePrefix, StringBuilder sb, Map<GraphEdge, String> edgeMap) {
    sb.append(" let transferOspf edge o =\n")
        .append("   match o with\n")
        .append("   | None -> None\n")
        .append("   | Some o -> (\n")
        .append("   match edge with\n");

    Set<String> ospfSet = new HashSet<>();

    for (GraphEdge edge : _graph.getAllEdges()) {
      if (edge.getPeer() != null) {
        Interface iface = edge.getStart();
        Integer cost = iface.getOspfCost() == null ? 1 : iface.getOspfCost();
        Long areaId = iface.getOspfAreaName();
        //        if (!_graph.isInterfaceActive(Protocol.OSPF, iface) || edge.getPeer() == null) {
        //        sb.append("None\n");
        // }
        if (_graph.isInterfaceActive(Protocol.OSPF, iface) && !ospfSet.contains(edgeMap.get(edge))) {
          ospfSet.add(edgeMap.get(edge));
          sb.append("   | ").append(edgeMap.get(edge)).append(" -> ");
          sb.append("Some {ospfAd = o.ad; weight = o.weight + ")
              .append(cost)
              .append("; areaType = if !(o.areaId = ")
              .append(areaId)
              .append(") then ospfInterArea else o.areaType; areaId = ")
              .append(areaId)
              .append(";}\n");
        }
      }
    }
    sb.append("   | _ -> None\n)\n\n");
  }


    public String compile(boolean singlePrefix) {
    StringBuilder sb = new StringBuilder();
    String ospfType = "{ospfAd: int8; weight: int16; areaType:int2; areaId: int;}"; // (ad, cost, area-type, area-id)
    String connType = "int8"; // ad
    String staticType = "int8"; // ad
    String bgpType = "{bgpAd: int8; lp: int; aslen: int; med:int; comms:set[int];}"; // (ad, lp, cost, med, comms)
    // String bgpType = "option[(int,int,int,int)]"; // (ad, lp, cost, med, comms)
    String bestType = "int2"; // proto
    sb.append("type ospfType = " + ospfType + "\n")
        .append("type bgpType = " + bgpType + "\n")
        .append("type rib = {\n")
        .append("    connected:")
        .append(optionType(connType))
        .append(";\n")
        .append("    static:")
        .append(optionType(staticType))
        .append(";\n")
        .append("    ospf:")
        .append(optionType("ospfType"))
        .append(";\n")
        .append("    bgp:")
        .append(optionType("bgpType"))
        .append(";\n")
        .append("    selected:")
        .append(optionType(bestType))
        .append("; }\n");

    // Either a single attribute or a map of attributes from prefix to route.
    sb = sb.append("type attribute = " +
        (singlePrefix ? "rib" : dictType("(int,int5)", "rib") )+ "\n\n");

    // symbolic destination variable. For now we only use one for single prefix networks.
    // We should make it so that symbolic destinations are used to represent external messages too.
    if (singlePrefix) {
      sb.append("symbolic d : (int, int5)\n\n");
    }


    // assign each node to a unique number starting from 0
    Map<String, Integer> nodeAssignment = new HashMap<>();
    int i = 0;
    for (String router : _graph.getRouters()) {
      nodeAssignment.put(router, i);
      i++;
    }

    Map<GraphEdge, String> edgeMap = new HashMap<>();
    Set<String> edgeSet = new HashSet<>();

    // Create the edge map of the network. We are currently ignoring hanging edges.
    // We should think how to represent those in the NV network. Perhaps, as an extra
    // (or two, we used to say we need two but I don't remember why) node connected to all nodes
    // with hanging edges.
    sb.append("let edges = {\n");
    for (GraphEdge edge : _graph.getAllEdges()) {
      Integer node1 = nodeAssignment.get(edge.getRouter());
      Integer node2 = nodeAssignment.get(edge.getPeer());
      String edgeString = "(" + node1 + "~" + node2 + ")";
      if (node2 != null) {
        edgeMap.put(edge, edgeString);
        if (!edgeSet.contains(edgeString)) {
          edgeSet.add(edgeString);

          sb.append("  ").append(node1).append("-").append(node2).append("; (*" + edge.toString() + "*)\n");
        }
      }
    }
    sb.append("}\n\n");

    sb.append("let nodes = ").append(i).append("\n\n");

    sb.append("let ospfIntraArea = 0u2\n")
        .append("let ospfInterArea = 1u2\n")
        .append("let ospfE1 = 2u2\n")
        .append("let ospfE2 = 3u2\n\n");

    sb.append("let protoConn = 0u8\n")
        .append("let protoStatic = 1u8\n")
        .append("let protoOspf = 2u8\n")
        .append("let protoBgp = 3u8\n\n");

    sb.append("let isProtocol fib x =\n")
        .append("  match fib with\n")
        .append("  | None -> false\n")
        .append("  | Some y -> x = y\n\n");

    sb.append("let min x y = if x <u8 y then x else y\n\n");
    sb.append("let max x y = if x <u8 y then y else x\n\n");

    sb.append("let pickOption f x y =\n")
        .append("  match (x,y) with\n")
        .append("  | (None, _) -> y")
        .append("  | (_, None) -> x\n")
        .append("  | (Some a, Some b) -> Some (f a b)\n\n");

    sb.append("let betterOspf o1 o2 =\n")
        .append("  if o1.areaType >u2 o2.areaType then o1\n")
        .append("  else if o2.areaType >u2 o1.areaType then o2\n")
        .append("  else if o1.weight <=u16 o2.weight then o1 else o2\n\n");

    sb.append("let betterBgp b1 b2 =\n")
        .append("  if b1.lp > b2.lp then b1\n")
        .append("  else if b2.lp > b1.lp then b2\n")
        .append("  else if b1.aslen < b2.aslen then b1\n")
        .append("  else if b2.aslen < b1.aslen then b2\n")
        .append("  else if b1.med >= b2.med then b1 else b2\n\n");

    sb.append("let betterEqOption o1 o2 =\n")
        .append("  match (o1,o2) with\n")
        .append("  | (_, None) -> true\n")
        .append("  | (None, _) -> false\n")
        .append("  | (Some a, Some b) -> a <=u8 b\n\n");

    sb.append("let best c s o b =\n")
        .append("  match (c,s,o,b) with\n")
        .append("  | (None,None,None,None) -> None\n")
        .append("  | _ -> \n")
        .append("      let o = match o with | None -> None | Some o -> Some o.ospfAd in\n")
        .append("      let b = match b with | None -> None | Some b -> Some b.bgpAd in\n")
        .append("      let (x,p1) = if betterEqOption c s then (c,0u2) else (s,1u2) in\n")
        .append("      let (y,p2) = if betterEqOption o b then (o,2u2) else (b,3u2) in\n")
        .append("      Some (if betterEqOption x y then p1 else p2)\n\n");

    sb.append("let mergeValues x y =\n")
        .append("  let c = pickOption min x.connected y.connected in\n")
        .append("  let s = pickOption min x.static y.static in\n")
        .append("  let o = pickOption betterOspf x.ospf y.ospf in\n")
        .append("  let b = pickOption betterBgp x.bgp y.bgp in\n")
        .append("  { connected = c;\n"
            +   "    static = s;\n"
            +   "    ospf = o;\n"
            +   "    bgp = b;\n"
            +   "    selected = best c s o b}\n\n");

    /* Merge one attribute or combine over map */
    if (singlePrefix) {
      sb.append("let merge node x y = mergeValues x y\n\n");
    } else {
      sb.append("let merge node x y = combine mergeValues x y\n\n");
    }

    sb.append("let init node =\n");

    /* init depends on whether we do a single route or not */
    if (singlePrefix) {
      sb.append("  match node with\n");
    } else {
      sb.append("  let d = createDict ({connected=None; static=None; ospf=None; bgp=None; selected=None;}) in\n")
          .append("  match node with\n");
    }

    for (Entry<String, Configuration> entry : _graph.getConfigurations().entrySet()) {
      String router = entry.getKey();
      Configuration conf = entry.getValue();
      Integer nodeId = nodeAssignment.get(router);
      sb.append("  | ").append(nodeId).append("n -> \n");
      Set<Prefix> connPrefixes = Graph.getOriginatedNetworks(conf, Protocol.CONNECTED);
      Set<Prefix> staticPrefixes = Graph.getOriginatedNetworks(conf, Protocol.STATIC);
      Set<Prefix> ospfPrefixes = Graph.getOriginatedNetworks(conf, Protocol.OSPF);
      Set<Prefix> bgpPrefixes = Graph.getOriginatedNetworks(conf, Protocol.BGP);

      connPrefixes = connPrefixes == null ? new HashSet<>() : connPrefixes;
      staticPrefixes = staticPrefixes == null ? new HashSet<>() : staticPrefixes;
      ospfPrefixes = ospfPrefixes == null ? new HashSet<>() : ospfPrefixes;
      bgpPrefixes = bgpPrefixes == null ? new HashSet<>() : bgpPrefixes;

      Set<Prefix> allPrefixes = new HashSet<>();
      allPrefixes.addAll(connPrefixes);
      allPrefixes.addAll(staticPrefixes);
      allPrefixes.addAll(ospfPrefixes);
      allPrefixes.addAll(bgpPrefixes);

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

      for (Prefix prefix : allPrefixes) {
        Boolean c = connPrefixes.contains(prefix);
        Boolean s = staticPrefixes.contains(prefix);
        Optional<Long> o = Optional.empty();
        if (ospfPrefixes.contains(prefix)) {
          o = Optional.of(ospfAreaIds.get(prefix));
        }
        Boolean b = bgpPrefixes.contains(prefix);

        InitialAttribute a = new InitialAttribute(c, s, o, b);
        Set<Prefix> prefixS = new HashSet<Prefix>();
        prefixS.add(prefix);
        if (attributePrefixMap.containsKey(a)) {
          prefixS.addAll(attributePrefixMap.get(a));
          attributePrefixMap.replace(a, prefixS);
        } else {
          attributePrefixMap.put(a, prefixS);
        }
      }

      /* This induces a large repetition of code but it's ok for now */
      if (singlePrefix) {
        for (Entry<InitialAttribute, Set<Prefix>> attrpre : attributePrefixMap.entrySet()) {
          String initAttr = attrpre.getKey().compileAttr(singlePrefix);
          Boolean first = true;
          sb.append("if ");
          for (Prefix pre : attrpre.getValue()) {
            if (!first) {
              sb.append(" || ");
            }
            sb.append("(d = (")
                .append(pre.getStartIp().asLong())
                .append(", ")
                .append(pre.getPrefixLength())
                .append("u5))");
            first = false;
          }
          sb.append(" then\n").append(initAttr).append("     else ");
        }
        sb.append("{connected=None; static=None; ospf=None; bgp=None; selected=None;}\n");
      } else {
        for (Entry<InitialAttribute, Set<Prefix>> attrpre : attributePrefixMap.entrySet()) {
          String initAttr = attrpre.getKey().compileAttr(singlePrefix);
          sb.append(initAttr);
          for (Prefix pre : attrpre.getValue()) {
            sb.append("    let d = d[(")
                .append(pre.getStartIp().asLong())
                .append(", ")
                .append(pre.getPrefixLength())
                .append("u5) := route] in\n");
          }
        }
        sb.append("      d\n");
      }
    }
    if (singlePrefix) {
      sb.append("  | _ -> {connected=None; static=None; ospf=None; bgp=None; selected=None;}\n\n");
    }
    else {
      sb.append("  | _ -> d\n\n");
    }
    ospfTrans(singlePrefix,sb,edgeMap);
    bgpTrans(singlePrefix,sb,edgeMap);

    if (singlePrefix) {
      sb.append("let trans edge x = \n")
          .append("  let o = transferOspf edge x.ospf in\n")
          .append("  let b = transferBgp edge x in\n")
          .append("  {connected=None; static=None; ospf=o; bgp=b; selected=None}\n\n");
    }
    else {
      sb.append("let trans edge x = \n")
          .append("  let x = transferBgp edge x in\n")
          .append("  let x = map (fun x -> {x with ospf=transferOspf edge x.ospf; connected=None; static=None}) x in\n")
          .append("  x\n\n");
    }

    /* Print node assignments for usability reasons */
    sb.append("(*\n" + nodeAssignment.toString() + "*)");

    return sb.toString();
  }
}
