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
import org.batfish.minesweeper.collections.Table2;
import org.batfish.minesweeper.utils.Tuple;

class InitialAttribute {
  private Boolean _conn;
  private Boolean _static;
  private Optional<Long> _areaId;
  private Boolean _bgp;
  private CompilerOptions _flags;

  public InitialAttribute(Boolean conn, Boolean stat, Optional<Long> areaId, Boolean bgp, CompilerOptions flags) {
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
      o = attrs.buildOspfAttribute("110u8", "0u16", "ospfIntraArea",  _areaId.get().toString(), "None", node + "n");
    }
    String b = _bgp ?
        attrs.buildBgpAttribute("100","20u8","0","80","{}","None", node + "n") : "None";
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
  private Map<GraphEdge, String> _edgeMap;
  private Map<String, Integer> _nodeAssignment;

  // Filename of the main network file, i.e. most likely the one describing the control plane.
  private String _filename;

  private CompilerOptions _flags;
  private Attributes _attrs;

  public NVCompiler(IBatfish batfish, String filename, CompilerOptions flags) {
    _graph = new Graph(batfish);
    _flags = flags;
    _attrs = new Attributes(flags);
    _edgeMap = new HashMap<>();
    _nodeAssignment = new HashMap<>();
    _filename = filename;
  }

  private String optionType(String typ) {
    return "option["+typ+"]";
  }

  private String dictType(String keyTyp, String valTyp) {
    return "dict["+keyTyp+", "+ valTyp+"]";
  }

  // Fixing this only for simulator right now.
  private Tuple<Map<GraphEdge, String>,Map<GraphEdge, String>> computeEquivalentPolicies(StringBuilder sb) {
    Set<String> bgpSet = new HashSet<>();
    Map<String, String> compiledExportPolicies = new HashMap<>();
    Map<String, String> compiledImportPolicies = new HashMap<>();

    Map<GraphEdge, String> edgeToImportPolicy = new HashMap<>();
    Map<GraphEdge, String> edgeToExportPolicy = new HashMap<>();

    for (Entry<String, Configuration> entry : _graph.getConfigurations().entrySet()) {
      String router = entry.getKey();
      Configuration config = entry.getValue();
      for (GraphEdge edge : _graph.getEdgeMap().get(router)) {
        if (edge.getPeer() != null) {
          if (_graph.isEdgeUsed(config, Protocol.BGP, edge) && !bgpSet.contains(_edgeMap.get(edge))) {
            bgpSet.add(_edgeMap.get(edge));

            // Doing export policy
            RoutingPolicy policy = _graph.findExportRoutingPolicy(router, Protocol.BGP, edge);
            List<Statement> statements;
            if ((policy != null) && (policy.getStatements() != null)) {
              statements = policy.getStatements();
            } else {
              statements = Collections.emptyList();
            }

            // Have we already compiled this policy?
       //     if (!compiledExportPolicies.containsKey(statements)) {
              // Build the decision tree for the export policy
              TransferFunctionBuilder exportTransBuilder =
                  new TransferFunctionBuilder(config, statements, edge, true);
              DecisionTree<Boolean> exportTree = exportTransBuilder.compute();

              // Normalize the tree if we want to export for simulation.
              exportTransBuilder.normalize(exportTree);
              // Apply some basic optimizations
              exportTree.optimize();

              /* Build NV string that corresponds to export tree */
              TreeCompiler exportTreeCompiler = new TreeCompiler(exportTree, null, config, _flags);
              List<Tuple<String, String>> expPolicies = exportTreeCompiler.toNvStrings();

              StringBuilder exportString = new StringBuilder();
              int numberOfPolicies = expPolicies.size();

             // String expPolicyName = "bgpExportPol" + compiledExportPolicies.size();
              //exportString.append("let " + expPolicyName + " =\n");
              // If there is only one policy there is no branching on prefixes just map the policy.
              if (numberOfPolicies == 1) {
                exportString
                    .append("     map (transferBgpExpPol e (fun prot b -> \n")
                    .append("           " + expPolicies.get(0).getSecond() + ")) x\n\n");
              } else {
                for (int idx = 0; idx < numberOfPolicies; idx++) {
                  exportString
                      .append("     let x =\n")
                      .append(
                          "     mapIf (fun p -> let (prefix, prefixLen) = p in\n"
                              + "                   "
                              + expPolicies.get(idx).getFirst()
                              + ")\n")
                      .append("         (transferBgpExpPol e (fun prot b ->\n")
                      .append(
                          "               "
                              + expPolicies.get(idx).getSecond()
                              + ")) x\n")
                      .append("    in\n");
                }
                exportString.append("     x\n\n");
              }

              // Add to string.
              String expPolicyName = compiledExportPolicies.get(exportString.toString());
              if (expPolicyName == null) {
                expPolicyName = "bgpExportPol" + compiledExportPolicies.size();
                sb.append("let " + expPolicyName + " e x =\n")
                    .append(exportString.toString());
                compiledExportPolicies.put(exportString.toString(), expPolicyName);
              }
              edgeToExportPolicy.put(edge, expPolicyName);


            // Do import policy
            List<Statement> importStatements;
            GraphEdge invEdge = _graph.getOtherEnd().get(edge);

            if (invEdge != null) {
              String otherRouter = invEdge.getRouter();
              RoutingPolicy importPolicy = _graph.findImportRoutingPolicy(otherRouter,
                  Protocol.BGP,
                  invEdge);
              Configuration invConfig = _graph.getConfigurations().get(otherRouter);
              if ((importPolicy != null) && (importPolicy.getStatements() != null)) {

                importStatements = importPolicy.getStatements();
                //if (!compiledImportPolicies.containsKey(importStatements)) {
                  TransferFunctionBuilder importTransBuilder = new TransferFunctionBuilder(invConfig,
                    importStatements,
                    invEdge,
                    false);

                  DecisionTree<Boolean> importTree = importTransBuilder.compute();

                  // Should only do this for Simulator, but leaving out smt for now.
                  importTransBuilder.normalize(importTree);
                  importTree.optimize();

                  TreeCompiler importTreeCompiler = new TreeCompiler(importTree, invConfig, null, _flags);
                  List<Tuple<String, String>> impPolicies = importTreeCompiler.toNvStrings();

                  StringBuilder importString = new StringBuilder();

                  numberOfPolicies = impPolicies.size();
                  // If there is only one policy there is no branching on prefixes just map the policy.
                  if (numberOfPolicies == 1) {
                    importString.append("     map (transferBgpImpPol (fun prot b -> \n")
                        .append("           " + impPolicies.get(0).getSecond() + ")) \n");
                  } else {
                    for (int idx = 0; idx < numberOfPolicies; idx++) {
                      importString.append("     let x =\n")
                          .append("     mapIf (fun p -> let (prefix, prefixLen) = p in\n"
                              + "                   " + impPolicies.get(idx).getFirst() + ")\n")
                          .append("         (transferBgpImpPol (fun prot b ->\n")
                          .append("               " + impPolicies.get(idx).getSecond() + ")) x \n");
                      }
                      importString.append("     in\n");
                    }
                    importString.append("     x\n\n");

                String impPolicyName = compiledImportPolicies.get(importString.toString());
                if (impPolicyName == null) {
                  impPolicyName = "bgpImportPol" + compiledImportPolicies.size();
                  sb.append("let " + impPolicyName + " x =\n")
                      .append(importString.toString());
                  compiledImportPolicies.put(importString.toString(), impPolicyName);
                }
                edgeToImportPolicy.put(edge, impPolicyName);
                  }
                }
              }
            }
          }
        }
    Tuple<Map<GraphEdge, String>, Map<GraphEdge, String>> ret = new Tuple<>(edgeToExportPolicy, edgeToImportPolicy);
      return ret;
    }


    private Map<Protocol, String> redistributeIntoBgp(String x,
        Table2<String, Protocol, Set<Protocol>> redistributionTable, String router) {
      Set<Protocol> protocols = redistributionTable.get(router, Protocol.BGP);
      Map<Protocol, String> redistributedRoutes = new HashMap<>();

      if (protocols.contains(Protocol.CONNECTED)) {
        // Default bgp attribute for connected
        redistributedRoutes.put(Protocol.CONNECTED, _attrs.buildBgpAttribute("100","20u8","0","80",
            "{}","None", "getSourceNode e"));
      }
      else { redistributedRoutes.put(Protocol.CONNECTED, "None"); }

      //TODO: Nexthop and origin might need to be different.
      if (protocols.contains(Protocol.STATIC)) {
        redistributedRoutes.put(Protocol.STATIC, _attrs.buildBgpAttribute("100","20u8","0","80",
            "{}","None", "getSourceNode e"));
      }
      else { redistributedRoutes.put(Protocol.STATIC, "None"); }

      // For ospf
      //TODO: I think the med here needs to be set to the OSPF weight, but we can't cast it yet.
      //TODO: Do we need to check whether we redistribute E1 and E2? We probably need to.
      if (protocols.contains(Protocol.OSPF)) {
        String bospf = "(match " + x + ".ospf with\n"
            + " | None -> None\n" + " | Some o -> "
            + _attrs.buildBgpAttribute("100",
            "20u8",
            "0",
            "80",
            "{}",
            "o.ospfNextHop",
            "o.ospfOrigin") +")";
        redistributedRoutes.put(Protocol.OSPF, bospf);
      }
      else {redistributedRoutes.put(Protocol.OSPF, "None");}
      return redistributedRoutes;
    }

  // Just to make my life easier making it separately now, but do it for single prefix too.
  private void bgpTransAllPrefixes(StringBuilder sb, Map<GraphEdge, String> edgeMap,
      Table2<String, Protocol, Set<Protocol>> redistributionTable) {
    sb.append("(* Bgp import policy *)\n")
        .append("let transferBgpImpPol policy x =\n")
        .append("  match x.bgp with\n")
        .append("  | None -> {x with bgp=None}\n")
        .append("  | Some b ->\n")
        .append("    {x with bgp=policy x.selected b}\n\n");

    sb.append("(* Bgp export policy *)\n")
        .append("let transferBgpExpPol e policy b x =\n")
        .append("  match b with\n")
        .append("  | None -> {x with bgp=None}\n")
        .append("  | Some b ->\n");

    if (_flags.doNextHop()) {
      sb.append("    let b = {b with bgpNextHop = flipEdge e} in\n");
    }

    sb.append("    {x with bgp=policy x.selected b}\n\n");

    Tuple<Map<GraphEdge, String>, Map<GraphEdge, String>> policies = computeEquivalentPolicies(sb);

    sb.append(" let transferBgp e x =");

    StringBuilder sbBgp = new StringBuilder();
    Set<String> bgpSet = new HashSet<>();
    for (Entry<String, Configuration> entry : _graph.getConfigurations().entrySet()) {
      String router = entry.getKey();
      Configuration config = entry.getValue();
      // Compute redistribution of other protocols into BGP for this router
      Map<Protocol, String> redistAttrs = redistributeIntoBgp("x", redistributionTable, router);
      for (GraphEdge edge : _graph.getEdgeMap().get(router)) {
        if (edge.getPeer() != null) {
          if (_graph.isEdgeUsed(config, Protocol.BGP, edge)
              && !bgpSet.contains(_edgeMap.get(edge))) {
            bgpSet.add(_edgeMap.get(edge));
            String expPolicy = policies.getFirst().get(edge);
            sbBgp.append("   | ")
                .append(_edgeMap.get(edge))
                .append(" ->\n")
                .append("     (*handling redistribution into BGP*)\n")
                .append("     let b = \n")
                .append("       match x.selected with\n")
                .append("       | None -> None\n")
                .append("       | Some 0u2 -> " + redistAttrs.get(Protocol.CONNECTED) + "\n")
                .append("       | Some 1u2 -> " + redistAttrs.get(Protocol.STATIC)+ "\n")
                .append("       | Some 2u2 -> " + redistAttrs.get(Protocol.OSPF) + "\n")
                .append("       | Some 3u2 -> x.bgp \n")
                .append("     in\n")
                .append("     let x = " + expPolicy + " e x in\n");

            // Do import policy now
            GraphEdge invEdge = _graph.getOtherEnd().get(edge);
            String impPolicy = policies.getSecond().get(invEdge);
            if (impPolicy == null) {
              sbBgp.append("     x\n");
            } else {
              sbBgp.append("    " + impPolicy + " x\n");
            }
          }
        }
      }
    }

    if (sbBgp.length() == 0 ) {
      sb.append(" map (fun v -> {v with bgp = None}) x\n");
    }
    else {
      sb.append("\n  match e with\n");
      sb.append(sbBgp);
    }
  }

  /***** BGP Transfer Function for Single Prefix ****/
  private void bgpTrans(boolean singlePrefix, StringBuilder sb, Map<GraphEdge, String> edgeMap,
      Table2<String, Protocol, Set<Protocol>> redistributionTable) {

    sb.append(" let transferBgp e x0 =\n");

    // Get the origin of the route if model requires it.
    /*if (_flags.doOrigin()) {
      sb.append("  match e with | u~v ->");
    }*/

      sb.append("  let (prefix, prefixLen) = d in\n")
        .append(" match e with\n");


    Set<String> bgpSet = new HashSet<>();
    for (Entry<String, Configuration> entry : _graph.getConfigurations().entrySet()) {
      String router = entry.getKey();
      Configuration config = entry.getValue();
      // Compute redistribution of other protocols into BGP for this router
      Map<Protocol, String> redistAttrs = redistributeIntoBgp("x0", redistributionTable, router);
      for (GraphEdge edge : _graph.getEdgeMap().get(router)) {
        if (edge.getPeer() != null) {
          Interface iface = edge.getStart();
          if (_graph.isEdgeUsed(config, Protocol.BGP, edge) && !bgpSet.contains(_edgeMap.get(edge))) {
            bgpSet.add(_edgeMap.get(edge));
            RoutingPolicy policy = _graph.findExportRoutingPolicy(router, Protocol.BGP, edge);
            List<Statement> statements;
            if ((policy != null) && (policy.getStatements() != null)) {
              statements = policy.getStatements();
            } else {
              statements = Collections.emptyList();
            }

            sb.append("   | ").append(edgeMap.get(edge)).append(" ->\n")
                .append("     (*handling redistribution into BGP*)\n")
                .append("     let b = \n")
                .append("       match x0.selected with\n")
                .append("       | None -> None\n")
                .append("       | Some 0u2 -> " + redistAttrs.get(Protocol.CONNECTED) + "\n")
                .append("       | Some 1u2 -> " + redistAttrs.get(Protocol.STATIC)+ "\n")
                .append("       | Some 2u2 -> " + redistAttrs.get(Protocol.OSPF) + "\n")
                .append("       | Some 3u2 -> x0.bgp \n")
                .append("     in\n")
                .append("     (match b with\n")
                .append("      | None -> None\n")
                .append("      | Some b ->\n");
            if (_flags.doNextHop()) {
              sb.append("        let b = {b with bgpNextHop = flipEdge e} in\n");
            }

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
            /* Build NV string that corresponds to export tree */
            TreeCompiler exportTreeCompiler = new TreeCompiler(exportTree, null, config, _flags);

            String impPolicy = "b";
            String expPolicy = exportTreeCompiler.toNvString();
            sb.append("     let b = \n")
              .append("           " + expPolicy + "\n")
                .append("     in\n");

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
                importTree.optimize();

                TreeCompiler importTreeCompiler = new TreeCompiler(importTree, invConfig, null, _flags);
                impPolicy = importTreeCompiler.toNvString();
                sb.append("         (match b with\n")
                    .append("         | None -> None\n")
                    .append("         | Some b ->\n")
                    .append("           " + impPolicy + ")\n");
                //policyTree = importTransBuilder.compute(exportTree);
                //treeCompiler = new TreeCompiler(policyTree, invConfig, config);
              }
              else { sb.append("      " + impPolicy + ")\n"); }
            }
            else { sb.append("      " + impPolicy + ")\n"); }
          }
        }
      }
    }
    if (singlePrefix) {
      sb.append(")\n\n");
    }
  }


  private void ospfTrans(boolean singlePrefix, StringBuilder sb) {
    sb.append(" let transferOspf edge o =\n")
        .append("   match o with\n")
        .append("   | None -> None\n")
        .append("   | Some o -> (\n")
        .append("     match edge with\n");

    Set<String> ospfSet = new HashSet<>();

    for (GraphEdge edge : _graph.getAllEdges()) {
      if (edge.getPeer() != null) {
        Interface iface = edge.getStart();
        Integer cost = iface.getOspfCost() == null ? 1 : iface.getOspfCost();
        Long areaId = iface.getOspfAreaName();
        //        if (!_graph.isInterfaceActive(Protocol.OSPF, iface) || edge.getPeer() == null) {
        //        sb.append("None\n");
        // }
        if (_graph.isInterfaceActive(Protocol.OSPF, iface) && !ospfSet.contains(_edgeMap.get(edge))) {
          String o = _attrs.buildOspfAttribute("o.ad","o.weight +u16 " + cost +"u16",
              "if !(o.areaId = " + areaId + ") then ospfInterArea else o.areaType",
              areaId.toString(), "flipEdge edge", "o.ospfOrigin");
          ospfSet.add(_edgeMap.get(edge));
          sb.append("     | ").append(_edgeMap.get(edge)).append(" -> ");
          sb.append(o + "\n");
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
        .append("type ospfType = " + _attrs.buildOspfType() + "\n")
        .append("type bgpType = " + _attrs.buildBgpType() + "\n")
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
    sb.append("type attribute = " +
        (singlePrefix ? "rib" : dictType("prefix", "rib") )+ "\n\n");

    // Definitions for "best" field
    sb.append("(* Definitions for the \"best\" field *)\n")
        .append("let p_CONNECTED = 0u2\n")
        .append("let p_STATIC = 1u2\n")
        .append("let p_OSPF = 2u2\n")
        .append("let p_BGP = 3u2\n\n");

  }

  /********** Merge Function ****************/
  private void merge(boolean singlePrefix, StringBuilder sb)
  {
    sb.append("let min x y = x <u8 y\n\n");

    sb.append("(* Compute the better of x and y according to f *)\n")
        .append("(* Return a boolean (true for x, false for y) for efficiency reasons *)\n")
        .append("let pickOption f x y =\n")
        .append("  match (x,y) with\n")
        .append("  | (None, _) -> false")
        .append("  | (_, None) -> true\n")
        .append("  | (Some a, Some b) -> f a b\n\n");

    sb.append("let pickMinOption = pickOption min\n\n");

    sb.append("(* OSPF Route ranking: first compare areas, then weights *)\n")
        .append("let betterOspf o1 o2 =\n")
        .append("  if o1.areaType <u2 o2.areaType then true\n")
        .append("  else if o2.areaType <u2 o1.areaType then false\n")
        .append("  else if o1.weight <=u16 o2.weight then true else false\n\n");

    sb.append("(* BGP Route ranking: first compare local pref, then path length, then MED *)\n")
        .append("let betterBgp b1 b2 =\n")
        .append("  if b1.lp > b2.lp then true\n")
        .append("  else if b2.lp > b1.lp then false\n")
        .append("  else if b1.aslen < b2.aslen then true\n")
        .append("  else if b2.aslen < b1.aslen then false\n")
        .append("  else if b1.med <= b2.med then true else false\n\n");

    sb.append("(* Determine which of the four protocols has the best route by comparing their ADs *)\n")
        .append("let best c s o b =\n")
        .append("  match (c,s,o,b) with\n")
        .append("  (* If no protocol has a route, then we have no route at all *)\n")
        .append("  | (None,None,None,None) -> None\n")
        .append("  | _ -> \n")
        .append("      (* Otherwise, get administrative distances for osfp and bgp... *)\n")
        .append("      let o = match o with | None -> None | Some o -> Some o.ospfAd in\n")
        .append("      let b = match b with | None -> None | Some b -> Some b.bgpAd in\n")
        .append("      (* ...and figure out which of the protocols has the lowest AD *)\n")
        .append("      let (x,p1) = if pickMinOption c s then (c,p_CONNECTED) else (s,p_STATIC) in\n")
        .append("      let (y,p2) = if pickMinOption o b then (o,p_OSPF) else (b,p_BGP) in\n")
        .append("      Some (if pickMinOption x y then p1 else p2)\n\n");

    sb.append("(* Compute the best route for each protocol individually, then select the best one *)\n")
        .append("let mergeValues x y =\n")
        .append("  let c = if (pickMinOption x.connected y.connected) then x.connected else y.connected in\n")
        .append("  let s = if (pickMinOption x.static y.static) then x.static else y.static in\n")
        .append("  let o = if (pickOption betterOspf x.ospf y.ospf) then x.ospf else y.ospf in\n")
        .append("  let b = if (pickOption betterBgp x.bgp y.bgp) then x.bgp else y.bgp in\n")
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

  }

  private String compileTopology() {
    StringBuilder sb = new StringBuilder();

    // assign each node to a unique number starting from 0
    int i = 0;
    for (String router : _graph.getRouters()) {
      _nodeAssignment.put(router, i);
      i++;
    }

    sb.append("let nodes = ").append(i).append("\n\n");

    Set<String> edgeSet = new HashSet<>();

    // Create the edge map of the network. We are currently ignoring hanging edges.
    // We should think how to represent those in the NV network. Perhaps, as an extra
    // (or two, we used to say we need two but I don't remember why) node connected to all nodes
    // with hanging edges.
    sb.append("let edges = {\n");
    for (GraphEdge edge : _graph.getAllEdges()) {
      Integer node1 = _nodeAssignment.get(edge.getRouter());
      Integer node2 = _nodeAssignment.get(edge.getPeer());
      String edgeString = "(" + node1 + "~" + node2 + ")";
      if (node2 != null) {
        _edgeMap.put(edge, edgeString);
        if (!edgeSet.contains(edgeString)) {
          edgeSet.add(edgeString);
          sb.append("  ").append(node1).append("-").append(node2).append("; (*" + edge.toString() + "*)\n");
        }
      }
    }
    sb.append("}\n\n");

    return sb.toString();
  }

  /***** Control Plane ********/
  public String compileControlPlane(boolean singlePrefix) {
    StringBuilder sb = new StringBuilder();

    printAttributeType(singlePrefix, sb);

    String topology = compileTopology();

    // symbolic destination variable. For now we only use one for single prefix networks.
    // We should make it so that symbolic destinations are used to represent external messages too.
    if (singlePrefix) {
      sb.append("symbolic d : prefix\n\n");
    }

    sb.append("(** Useful helper definitions **)\n");
    sb.append("let ospfIntraArea = 0u2\n")
        .append("let ospfInterArea = 1u2\n")
        .append("let ospfE1 = 2u2\n")
        .append("let ospfE2 = 3u2\n\n");

    sb.append("(* Check if the selected protocol is x *)\n");
    if (singlePrefix) {
      sb.append("let isProtocol fib x = fib = x\n");
    }
    else {
      sb.append("let isProtocol fib x =\n")
        .append("  match fib with\n")
        .append("  | None -> false\n")
        .append("  | Some y -> x = y\n\n");
    }

    sb.append("let flipEdge e = \n")
        .append("  match e with")
        .append("  | a~b -> toEdge b a\n\n");


    sb.append("let getSourceNode e = \n")
        .append("  match e with")
        .append("  | a~b -> a\n\n");

    // Print out merge function
    merge(singlePrefix, sb);

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
      Integer nodeId = _nodeAssignment.get(router);
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

        InitialAttribute a = new InitialAttribute(c, s, o, b, _flags);
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
          String initAttr = attrpre.getKey().compileAttr(nodeId, singlePrefix);
          Boolean first = true;
          sb.append("if ");
          for (Prefix pre : attrpre.getValue()) {
            if (!first) {
              sb.append(" || ");
            }
            sb.append("(d = ").append(pre);
            first = false;
          }
          sb.append(" then\n").append(initAttr).append("     else ");
        }
        sb.append("{connected=None; static=None; ospf=None; bgp=None; selected=None;}\n");
      } else {
        for (Entry<InitialAttribute, Set<Prefix>> attrpre : attributePrefixMap.entrySet()) {
          String initAttr = attrpre.getKey().compileAttr(nodeId, singlePrefix);
          sb.append(initAttr);
          for (Prefix pre : attrpre.getValue()) {
            sb.append("    let d = d[")
              .append(pre)
              .append(" := route] in\n");
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


    Redistribution redistributionTable = new Redistribution(_graph);

    // OSPF transfer function
    ospfTrans(singlePrefix,sb);

    // BGP transfer function
    if (singlePrefix) {
      bgpTrans(singlePrefix, sb, _edgeMap, redistributionTable.getRedistributedProtocols());
    }
    else {
      bgpTransAllPrefixes(sb, _edgeMap, redistributionTable.getRedistributedProtocols());
    }

    if (singlePrefix) {
      sb.append("let trans edge x = \n")
          .append("  let o = transferOspf edge x.ospf in\n")
          .append("  let b = transferBgp edge x in\n")
          .append("  {connected=None; static=None; ospf=o; bgp=b; selected=None}\n\n");
    }
    else {
      sb.append("\nlet trans edge x = \n")
          .append("  let x = transferBgp edge x in\n")
          .append("  let x = map (fun x -> {x with ospf=transferOspf edge x.ospf; connected=None; static=None}) x in\n")
          .append("  x\n\n");
    }

    sb.append(topology);
    /* Print node assignments for usability reasons */
    sb.append("(*\n" + _nodeAssignment.toString() + "*)");

    return sb.toString();
  }


  /* Putting everything together */
  public CompilerResult compile(boolean singlePrefix){
    String controlplane = compileControlPlane(singlePrefix);
    String dataplane = "";
    if (_flags.doDataplane()) {
      Dataplane data = new Dataplane(_nodeAssignment, _edgeMap);
      dataplane = data.generateDataplane();
    }
    String allFaults = "";
    if (_flags.doNodeFaults()) {
      AllFaultsAnalysis faults = new AllFaultsAnalysis(_filename, _nodeAssignment, _edgeMap);
      allFaults = faults.compileAllFaults(true, singlePrefix);

    }
    return new CompilerResult(controlplane, dataplane, allFaults);
  }

}
