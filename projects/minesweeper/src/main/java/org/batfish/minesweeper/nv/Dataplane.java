package org.batfish.minesweeper.nv;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Optional;
import java.util.Random;
import java.util.Set;
import java.util.SortedSet;
import org.batfish.datamodel.Interface;
import org.batfish.datamodel.Ip;
import org.batfish.datamodel.IpAccessList;
import org.batfish.datamodel.IpProtocol;
import org.batfish.datamodel.Prefix;
import org.batfish.minesweeper.GraphEdge;
import org.batfish.minesweeper.abstraction.PrefixTrie;


/* A model of the dataplane with quantities where there */

public class Dataplane {

  private Map<String, Integer> _nodes;

  /* Maps GraphEdge to NV edges */
  private Map<GraphEdge, String> _edgeMap;

  private SortedSet<Prefix> _originated; // Originated BGP and OSPF prefixes

  private Map<Integer, Set<Prefix>> _perNodePrefixes;

  private PrefixTrie _preTrie;

  private final FaultAnalysis _fa;

  private final CompilerOptions _flags;

  public Dataplane(Map<String, Integer> _nodes, Map<GraphEdge, String> edgeMap, SortedSet<Prefix> originated, Map<Integer, Set<Prefix>> perNodePrefixes, FaultAnalysis fa, CompilerOptions flags) {
    this._nodes = _nodes;
    this._edgeMap = edgeMap;
    this._originated = originated;
    this._perNodePrefixes = perNodePrefixes;
    this._fa = fa;
    this._preTrie = new PrefixTrie(originated);
    this._flags = flags;
  }

  /** Type of messages exchanged by the dataplane **/
  private String getAttributeType() {
    return "type flow = {srcIp: int; dstIp: int; srcPort:int16; dstPort:int16; protocol: int8; flowSize:float }";
  }
  //
  //  private void generateLPM(StringBuilder sb) {
  //
  //    // Helper function matching a route's forwarding edge and an edge
  //    sb.append("let getFwd route =\n")
  //        .append("  match route.selected with\n")
  //        .append("  | None -> None\n")
  //        .append("  | Some 0 -> Some None\n")
  //        .append("  | Some 1 -> Some None\n")
  //        .append("  | Some 2 -> (match route.ospf with\n")
  //        .append("               | None -> None\n")
  //        .append("               | Some o -> Some o.ospfNextHop)\n")
  //        .append("  | Some 3 -> (match route.bgp with\n")
  //        .append("               | None -> None\n")
  //        .append("               | Some b -> Some b.bgpNextHop)\n");
  //
  //
  //    sb.append("\nlet lpm rib edge p =\n")
  //        .append("  let dst = p.dstIp in\n");
  //
  //    for (int i = 32; i >= 0; i--) {
  //      sb.append("  (match getFwd (rib[(" + NVLang.mkBitAnd("dst", (Math.round(Math.pow(2,i)) - 1) + "") + "," + i +"u6)]) with\n")
  //        .append("  | Some None -> false\n")
  //        .append("  | Some (Some nexthop) -> edge = nexthop\n")
  //        .append("  | None -> ");
  //    }
  //    sb.append("false");
  //    for (int i = 0; i <= 32; i++) {
  //      sb.append(")");
  //    }
  //    sb.append("\n\n");
  //  }


  private void computeACL(StringBuilder sb, boolean out) {

    if (out)
    {sb.append("let aclOut edge fs = \n")
        .append("  match edge with\n");
}
    else {
      sb.append("let aclIn edge fs = \n")
          .append("  match edge with\n");
    }

    // ACL to edge map
    Map<String, Set<String>> equivAcls = new HashMap<>();

    for (Entry<GraphEdge, String> edge : _edgeMap.entrySet()) {
      Interface i = edge.getKey().getStart();

      IpAccessList filter;
      if (out) {
        filter = i.getOutgoingFilter();
      }
      else
      {
        filter = i.getIncomingFilter();
      }
      Optional<String> outAcl = Optional.empty();
      if (filter != null) {
        outAcl = computeACL(filter);
      }

      // Encoding the ACLs
      String filterOut = outAcl.orElse("true");

      Set<String> edges = equivAcls.get(filterOut);
      if (edges == null) {
        edges = new HashSet<>();
        edges.add(edge.getValue());
        equivAcls.put(filterOut, edges);
      }
      else {
        edges.add(edge.getValue());
      }
    }

    for (Entry<String, Set<String>> e : equivAcls.entrySet()) {
      if (!(e.getKey().equals("true")))
      {


        for (String edge : e.getValue()) {
          sb.append("  | ").append(edge).append(" ->\n    ")
              .append(e.getKey()).append("\n");
        }
      }
    }

    // Do default case
    sb.append("  | _ -> true\n\n");
  }


  private String nextHopMatch(boolean multipath, String nhops) {

    if (multipath) {
        return "                   if nhop[e] && (aclOut e fs) then Some (Some (split fs " + nhops + ")) else None))\n";
    }
    else
      return "                   if (e = nhop) && (aclOut e fs) then Some (Some fs) else None))\n";
  }



  private void fwdOut(StringBuilder sb, int bound, boolean multipath) {

    computeACL(sb, true);


    if (multipath) {
      sb.append("let split fs npaths = {fs with flowSize = fs.flowSize /. npaths}\n\n");
    }

    sb.append("let fwdOut (r : [C]rib) e (fs : [C]option[flow])  = \n")
        //        .append("  if ")
        //        .append(_fa.failureCondition(bound))
        //        .append(" then\n")
        //        .append("    None\n")
        //        .append("  else\n")
        .append("  match fs with\n")
        .append("  | None -> None\n")
        .append("  | Some fs -> \n")
        .append("     (match r.selected with\n")
        .append("       | None -> None\n")
        .append("       | Some 0u2 -> Some None\n")
        .append("       | Some 1u2 -> Some None\n")
        .append("       | Some 2u2 -> (match r.ospf with\n")
        .append("                 | None -> None (*can't happen *) \n")
        .append("                 | Some o -> (\n");
      if (_flags.doMultiPath()) {
        sb.append("                   if o.ospfNextHop = {} then Some None else\n")
            .append("                 if o.ospfNextHop[e] && (aclOut e fs) then Some (Some (split fs (size o.ospfNextHop true))) else None))\n");
      }
      else {
        sb.append("                   match o.ospfNextHop with\n")
            .append("                   | None -> Some None\n")
            .append("                   | Some nhop ->\n")
            .append("                      if (e = nhop) && (aclOut e fs) then Some (Some fs) else None))\n");
      }
        sb.append("        | Some 3u2 -> (match r.bgp with\n")
        .append("                 | None -> None (*can't happen *)\n")
        .append("                 | Some b -> (\n");
    if (_flags.doMultiPath()) {
      sb.append("                   if b.bgpNextHop = {} then Some None else\n")
          .append("                 if b.bgpNextHop[e] && (aclOut e fs) then Some (Some (split fs (size b.bgpNextHop true))) else None))\n");
    }
    else {
      sb.append("                   match b.bgpNextHop with\n")
          .append("                   | None -> Some None\n")
          .append("                   | Some nhop ->\n")
          .append("                      if (e = nhop) && (aclOut e fs) then Some (Some fs) else None))\n");
    }
    sb.append("      )\n\n");

  }

  private void fwdIn(StringBuilder sb) {
    computeACL(sb, false);
    sb.append("let fwdIn e fs = if aclIn e fs then fs else None\n\n");
  }

  private void historyE(StringBuilder sb) {

    sb.append("let hinitE e = 0.0\n\n");

    sb.append("let logE e fs edgeHistory =\n")
        .append("  match fs with\n")
        .append("  | None -> edgeHistory\n")
        .append("  | Some f -> f.flowSize +. edgeHistory\n\n");
  }

  /* Could trace set of packets or unit */
  private void historyV(StringBuilder sb) {

    sb.append("let hinitV u = 0.0\n\n");

    sb.append("let logV u fs nodeHistory = 0.0\n\n");
  }

  //  /** Transfer function for the dataplane **/
  //  private String transferData() {
  //    StringBuilder sb = new StringBuilder();
  //
  //    // Generate the LPM function
  //    generateLPM(sb);
  //
  //    sb.append("\nlet transferData rib edge ps = \n")
  //        .append("  match edge with\n");
  //
  //    for (Entry<GraphEdge, String> edge : _edgeMap.entrySet()) {
  //      Interface i = edge.getKey().getStart();
  //
  //      IpAccessList outbound = i.getOutgoingFilter();
  //      Optional<String> outAcl = Optional.empty();
  //      if (outbound != null) {
  //        outAcl = computeACL(outbound);
  //      }
  //
  //      IpAccessList inbound = i.getIncomingFilter();
  //      Optional<String> inAcl = Optional.empty();
  //      if (inbound != null) {
  //        inAcl = computeACL(inbound);
  //      }
  //
  //      // Encoding the ACLs
  //      String filterAcls = NVLang.mkAnd(outAcl.orElse("true"), inAcl.orElse("true"));
  //
  //      // Encoding the LPM semantics
  //      String filterLPM = "(lpm rib edge p)";
  //      String filter = NVLang.mkAnd(filterLPM, filterAcls);
  //
  //      sb.append("  | " + edge.getValue() + " ->\n")
  //          .append(NVLang.mkFilter("fun p -> " + NVLang.mkNot(filter), "ps"))
  //          .append("\n");
  //    }
  //
  //    return sb.toString();
  //  }


  /*
   * Convert an Access Control List (ACL) to an NV expression.
   * The default action in an ACL is to deny all traffic.
   */
  private Optional<String> computeACL(IpAccessList acl) {

    // Check if there is an ACL first
    if (acl == null) {
      return Optional.empty();
    }

    return Optional.of(new IpAccessListToNvExpr().toNvExpr(acl));
  }

  // Generate a random traffic matrix
  private List<Flow> randomTrafficMatrix() {
    List<Flow> flows = new ArrayList<>();
    Random rand = new Random();
    // Go through every node in the network
    for (Entry<String, Integer> node : _nodes.entrySet()) {

      // TODO: potentially add a set of host networks to send traffic to instead of using the prefixes.

      // and every prefix announced
      for (Prefix dstPre : _originated) {
        // If this prefix is not announced by this node
        if (!_perNodePrefixes.get(node.getValue()).contains(dstPre)) {
          // Randomly choose whether to send traffic to it.
          boolean sendsTraffic = rand.nextBoolean() && rand.nextBoolean();
          if (flows.size() < 10)
            sendsTraffic = sendsTraffic || rand.nextBoolean() || rand.nextBoolean();

          if (flows.size() > 10)
            sendsTraffic = sendsTraffic && rand.nextBoolean();

          if (flows.size() > 100)
            sendsTraffic = sendsTraffic && rand.nextBoolean();

          if (flows.size() > 500)
            sendsTraffic = sendsTraffic && rand.nextBoolean() && rand.nextBoolean();

          if (flows.size() > 600)
            sendsTraffic = sendsTraffic && rand.nextBoolean() && rand.nextBoolean() && rand.nextBoolean();

          if (sendsTraffic) {
            // If yes, check that the source node announces some prefix (TODO: alternatively, has some host connected)
            Set<Prefix> sources = _perNodePrefixes.get(node.getValue());
            if (sources.size() > 0) {
              int index = rand.nextInt(sources.size());
              // If yes, randomly choose ports and protocol
              Integer srcPort = rand.nextInt((int) Math.pow(2, 16));
              Integer dstPort = rand.nextInt((int) Math.pow(2, 16));
              IpProtocol protocol = IpProtocol.fromNumber(rand.nextBoolean() ? 6 : 17);
              Iterator<Prefix> iter = sources.iterator();
              for (int i = 0; i < index - 1; i++) {
                iter.next();
              }
              Ip srcIp = iter.hasNext() ? iter.next().getFirstHostIp() : Ip.ZERO;

              int flowSize = rand.nextInt(500);
              Flow f =
                  new Flow(
                      node.getValue(),
                      srcIp,
                      dstPre.getFirstHostIp(),
                      srcPort,
                      dstPort,
                      protocol,
                      flowSize);
              flows.add(f);
            }
            sendsTraffic = false;
          }
        }
      }
    }

    return flows;
  }

  /* Generates the initTC, fwdOut and call to forward for every traffic class */

  /* let fwdOutTci e fs =
   *   match fwdOut rib_0 e fs with
   *   | Some (Some f) -> Some f (* have a nexthop through this prefix *)
   *   | Some (None) -> None (* have no next-hop because we reached the destination actually *)
   *   | None -> match fwdOut rib_1 e fs with... (* no next-hop through the most specific prefix *)
   * */
  private void fwdInit(StringBuilder sb, List<Flow> flows) {
    int sz = flows.size();
    StringBuilder sbSols = new StringBuilder();
    for (int i = 0; i < sz; i++) {
      Flow f = flows.get(i);
      sb.append("let initTC")
          .append(i)
          .append(" u =\n")
          .append("  if (u = ")
          .append(f.get_source())
          .append("n) then\n")
          .append("    Some (")
          .append(f.compile())
          .append(")\n")
          .append("  else None\n\n");

      List<Prefix> matchingPrefixes = _preTrie.getLongestPrefixMatches(f.get_dstIp());
      sb.append("let fwdOutTc").append(i).append(" e (fs : [C]option[flow])  =\n");
      Iterator<Prefix> it = matchingPrefixes.iterator();
      StringBuilder sbParen = new StringBuilder();
      while (it.hasNext()) {
        Prefix pre = it.next();
        sb.append("  match fwdOut (").append(_fa.ribName(pre)).append("[let (u~v) = e in u])")
            .append(" e fs with\n")
            .append("  | Some (Some fs) -> Some fs\n")
            .append("  | Some None -> None\n");
        if (it.hasNext())
        {
          sb.append("  | None -> (\n");
          sbParen.append(")");
        }
        else
          sb.append("  | None -> None\n");

      }
      sb.append(sbParen);
      sb.append("\n\n");

      sbSols.append("forward (hVTc")
          .append(i)
          .append(",")
          .append("hETc")
          .append(i)
          .append(") = (initTC")
          .append(i)
          .append(", fwdOutTc")
          .append(i)
          .append(", fwdIn, hinitV, hinitE, logV, logE, None)\n");
    }

    sb.append(sbSols);
  }


  /* Utilizations are computed pairwise */
  private void capacityAssertions(StringBuilder sb, int nbFlows) {

    sb.append("\n");
    if (nbFlows > 0) {
      sb.append("@noinline let linkUtilization0 e = hETc0[e]");
      if (nbFlows > 1)
        sb.append(" +. hETc1[e]\n\n");
      else sb.append("\n\n");
    }
    else return;

    for (int i = 1; i < nbFlows-1; i++)
    {
      sb.append("@noinline let linkUtilization")
          .append(i)
          .append(" e = (linkUtilization")
          .append(i - 1)
          .append(" e) +. hETc")
          .append(i+1).append("[e]\n\n");
    }
    sb.append(_fa.generateOrderingConstraints(_flags.getLinkFaultsBound()));

    for (Entry<GraphEdge, String> e : _edgeMap.entrySet()) {
      sb.append("assert(\"Link(")
          .append(e.getKey())
          .append("\", linkUtilization")
          .append(nbFlows - 2)
          .append(" ")
          .append(e.getValue())
          .append(" <. 10000.0 | ord)\n");
    }
  }

//  private void capacityAssertions(StringBuilder sb, int nbFlows) {
//    sb.append("\nlet linkUtilization e =\n");
//
//    for (int i = 0; i < nbFlows-1; i++)
//    {
//      sb.append("hETc").append(i).append("[e] +. ");
//    }
//    sb.append("hETc").append(nbFlows-1).append("[e]\n\n");
//
//    sb.append(_fa.generateOrderingConstraints(_flags.getLinkFaultsBound()));
//
//    for (Entry<GraphEdge, String> e : _edgeMap.entrySet()) {
//      sb.append("assert(\"Link(").append(e.getKey())
//          .append("\", linkUtilization ")
//          .append(e.getValue())
//          .append(" <. 10000.0 | ord)\n");
//    }
//  }

  public String generateDataplane() {
    // Generate helper functions as above.
    StringBuilder sb = new StringBuilder();

    sb.append("include \"")
        .append("LinkFaults")
        .append(_flags.getLinkFaultsBound())
        .append("/")
        .append(_fa.get_filename())
        .append("_")
        .append(_flags.getLinkFaultsBound())
        .append("_linkFaults.nv\"\n\n");

    // Add flow type
    sb.append(getAttributeType()).append("\n\n");

    // Add fwdOut skeleton
    fwdOut(sb, _flags.getLinkFaultsBound(), _flags.doMultiPath());

    // Add fwdIn
    fwdIn(sb);

    // Add histories
    historyV(sb);
    historyE(sb);

    // Add per-traffic class init/fwdOut and calls to forwarding.
    List<Flow> flows = randomTrafficMatrix();
    fwdInit(sb, flows);

    capacityAssertions(sb, flows.size());

    return sb.toString();
  }

}
