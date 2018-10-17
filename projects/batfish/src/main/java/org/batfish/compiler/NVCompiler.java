package org.batfish.compiler;

import java.util.HashMap;
import java.util.HashSet;
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
import org.batfish.symbolic.Graph;
import org.batfish.symbolic.GraphEdge;
import org.batfish.symbolic.Protocol;
import org.glassfish.grizzly.streams.StreamInput;

class InitialAttribute {
  private Boolean _conn;
  private Boolean _static;
  private Optional<Long> _areaId;
  private Boolean _bgp;

  public InitialAttribute(Boolean conn, Boolean stat, Optional<Long> areaId, Boolean bgp){
    _conn = conn;
    _static = stat;
    _areaId = areaId;
    _bgp = bgp;
  }

  public String compileAttr() {
    StringBuilder sb = new StringBuilder();
    String c = this._conn ? "Some 0" : "None";
    String s = this._static ? "Some 1" : "None";
    String o = "None";
    if (this._areaId.isPresent()) {
      o = "Some (110, 0, ospfIntraArea, " + _areaId.get() + ")";
    }
    String b = _bgp ? "Some (20, 100, 0, 80, {})" : "None";
    sb.append("       let c = ")
        .append(c)
        .append(" in\n")
        .append("       let s = ")
        .append(s)
        .append(" in\n")
        .append("       let o = ")
        .append(o)
        .append(" in\n")
        .append("       let b = ")
        .append(b)
        .append(" in\n");
    sb.append("       let fib = best c s o b in\n")
        .append("        (c,s,o,b,fib)\n");
    return sb.toString();
  }

  @Override public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (o == null || getClass() != o.getClass()) {
      return false;
    }
    InitialAttribute that = (InitialAttribute) o;
    return Objects.equals(_conn, that._conn) && Objects.equals(_static, that._static)
        && Objects.equals(_areaId, that._areaId) && Objects.equals(_bgp, that._bgp);
  }

  @Override public int hashCode() {

    return Objects.hash(_conn, _static, _areaId, _bgp);
  }
}

public class NVCompiler {

  private Graph _graph;

  public NVCompiler(IBatfish batfish) {
    _graph = new Graph(batfish);
  }

  public String compile() {
    StringBuilder sb = new StringBuilder();
    String ospfType = "option[(int, int, int, int)]"; // (ad, cost, area-type, area-id)
    String connType = "option[int]"; // ad
    String staticType = "option[int]"; // ad
    String bgpType = "option[(int,int,int,int,set[int])]"; // (ad, lp, cost, med, comms)
    String bestType = "option[int]"; // proto
    sb.append("type attribute = (")
        .append(connType)
        .append(",")
        .append(staticType)
        .append(",")
        .append(ospfType)
        .append(",")
        .append(bgpType)
        .append(",")
        .append(bestType)
        .append(")\n\n");

    // symbolic destination variable
    sb.append("symbolic d : (int, int)\n\n");

    // assign each node to a unique number starting from 0
    Map<String, Integer> nodeAssignment = new HashMap<>();
    int i = 0;
    for (String router : _graph.getRouters()) {
      nodeAssignment.put(router, i);
      i++;
    }

    Map<GraphEdge, String> edgeMap = new HashMap<>();

    sb.append("let edges = {\n");
    for (GraphEdge edge : _graph.getAllEdges()) {
      Integer node1 = nodeAssignment.get(edge.getRouter());
      Integer node2 = nodeAssignment.get(edge.getPeer());
      if (node2 != null) {
        edgeMap.put(edge, "(" + node1 + "," + node2 + ")");
        sb.append("  ").append(node1).append("-").append(node2).append(";\n");
      }
    }
    sb.append("}\n\n");

    sb.append("let nodes = ").append(i).append("\n\n");

    sb.append("let ospfIntraArea = 0\n")
        .append("let ospfInterArea = 1\n")
        .append("let ospfE1 = 2\n")
        .append("let ospfE2 = 3\n\n");

    sb.append("let protoConn = 0\n")
        .append("let protoStatic = 1\n")
        .append("let protoOspf = 2\n")
        .append("let protoBgp = 3\n\n");

    sb.append("let min x y = if x < y then x else y\n\n");
    sb.append("let max x y = if x < y then y else x\n\n");

    sb.append("let pickOption f x y =\n")
        .append("  match (x,y) with\n")
        .append("  | (None, None) -> None\n")
        .append("  | (None, Some _) -> y")
        .append("  | (Some _, None) -> x\n")
        .append("  | (Some a, Some b) -> Some (f a b)\n\n");

    sb.append("let betterOspf o1 o2 =\n")
        .append("  let (_,cost1,areaType1,_) = o1 in\n")
        .append("  let (_,cost2,areaType2,_) = o2 in\n")
        .append("  if areaType1 > areaType2 then o1\n")
        .append("  else if areaType2 > areaType1 then o2\n")
        .append("  else if cost1 <= cost2 then o1 else o2\n\n");

    sb.append("let betterBgp o1 o2 =\n")
        .append("  let (_,lp1,cost1,med1,_) = o1 in\n")
        .append("  let (_,lp2,cost2,med2,_) = o2 in\n")
        .append("  if lp1 > lp2 then o1\n")
        .append("  else if lp2 > lp1 then o2\n")
        .append("  else if cost1 < cost2 then o1\n")
        .append("  else if cost2 < cost1 then o2")
        .append("  else if med1 >= med2 then o1 else o2\n\n");

    sb.append("let betterEqOption o1 o2 =\n")
        .append("  match (o1,o2) with\n")
        .append("  | (None, None) -> true\n")
        .append("  | (Some _, None) -> true\n")
        .append("  | (None, Some _) -> false\n")
        .append("  | (Some a, Some b) -> a <= b\n\n");

    sb.append("let best c s o b =\n")
        .append("  match (c,s,o,b) with\n")
        .append("  | (None,None,None,None) -> None\n")
        .append("  | _ -> \n")
        .append("      let o = match o with | None -> None | Some (ad,_,_,_) -> Some ad in\n")
        .append("      let b = match b with | None -> None | Some (ad,_,_,_,_) -> Some ad in\n")
        .append("      let (x,p1) = if betterEqOption c s then (c,0) else (s,1) in\n")
        .append("      let (y,p2) = if betterEqOption o b then (o,2) else (b,3) in\n")
        .append("      Some (if betterEqOption x y then p1 else p2)\n\n");

    sb.append("let mergeValues x y =\n")
        .append("  let (cx,sx,ox,bx,_) = x in\n")
        .append("  let (cy,sy,oy,by,_) = y in\n")
        .append("  let c = pickOption min cx cy in\n")
        .append("  let s = pickOption min sx sy in\n")
        .append("  let o = pickOption betterOspf ox oy in\n")
        .append("  let b = pickOption betterBgp bx by in\n")
        .append("  (c,s,o,b, best c s o b)\n\n");

    sb.append("let merge node x y = mergeValues x y\n\n");

    sb.append("let init node =\n")
        .append("  match node with\n");

    for (Entry<String, Configuration> entry : _graph.getConfigurations().entrySet()) {
      String router = entry.getKey();
      Configuration conf = entry.getValue();
      Integer nodeId = nodeAssignment.get(router);
      sb.append("  | ").append(nodeId).append(" -> \n");
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
      OspfProcess ospf = conf.getDefaultVrf().getOspfProcess();
      if (ospf != null) {
        for (Entry<Long, OspfArea> e : ospf.getAreas().entrySet()) {
          Long areaId = e.getKey();
          OspfArea area = e.getValue();
          for (String ifaceName : area.getInterfaces()) {
            Interface iface = conf.getInterfaces().get(ifaceName);
            Prefix pfx = iface.getAddress().getPrefix();
            ospfAreaIds.put(pfx, areaId);
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

        InitialAttribute a = new InitialAttribute(c,s,o,b);
        Set<Prefix> prefixS = new HashSet<Prefix>();
        prefixS.add(prefix);
        if (attributePrefixMap.containsKey(a)) {
          prefixS.addAll(attributePrefixMap.get(a));
          attributePrefixMap.replace(a, prefixS);
        }
        else
        {
          attributePrefixMap.put(a, prefixS);
        }
      }

      sb.append("     ");
      for (Entry<InitialAttribute, Set<Prefix>> attrpre: attributePrefixMap.entrySet())
      {
       String initAttr = attrpre.getKey().compileAttr();
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
             .append("))");
         first = false;
       }
       sb.append(" then\n")
       .append(initAttr)
       .append("     else ");
      }
      sb.append("(None,None,None,None,None)\n");
    }
    sb.append("  | _ -> (None,None,None,None,None)\n\n");

    sb.append(" let transferOspf edge o =\n")
        .append("   match o with\n")
        .append("   | None -> None\n")
        .append("   | Some (ad,cost,areaType,areaId) -> (\n")
        .append("   match edge with\n");

    for (GraphEdge edge : _graph.getAllEdges()) {
      if (edge.getPeer() != null) {
        Interface iface = edge.getStart();
        Integer cost = iface.getOspfCost() == null ? 1 : iface.getOspfCost();
        Long areaId = iface.getOspfAreaName();
        sb.append("   | ").append(edgeMap.get(edge)).append(" -> ");
        if (!_graph.isInterfaceActive(Protocol.OSPF, iface) || edge.getPeer() == null) {
          sb.append("None\n");
        } else {
          sb.append("(ad, cost + ")
              .append(cost)
              .append(", if !(areaId = ")
              .append(areaId)
              .append(") then ospfInterArea else areaType, ")
              .append(areaId)
              .append("\n");
        }
      }
    }
    sb.append(")\n");

    sb.append(" let transferBgp edge b =\n")
        .append("   match b with\n")
        .append("   | None -> None\n")
        .append("   | Some (ad,lp,cost,med,comms) -> (\n")
        .append("   match edge with\n");
    for (GraphEdge edge : _graph.getAllEdges()) {
      if (edge.getPeer() != null) {
        Interface iface = edge.getStart();
        sb.append("   | ").append(edgeMap.get(edge)).append(" -> ");
        if (!_graph.isInterfaceActive(Protocol.BGP, iface) || edge.getPeer() == null) {
          sb.append("None\n");
        } else {
          sb.append("Some (ad, lp, cost + 1, med, comms)\n");
        }
      }
    }
    sb.append(")\n\n");

    sb.append("let transferRoute edge x = \n")
        .append("  let (c,s,o,b,fib) = x in\n")
        .append("  let o = transferOspf edge o in\n")
        .append("  let b = transferBgp edge b in\n")
        .append("  (None, None, o, b, fib)\n\n");

    sb.append("let trans edge x =\n").append("   transferRoute edge x\n\n");

    return sb.toString();
  }
}
