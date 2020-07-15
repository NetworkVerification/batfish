package org.batfish.minesweeper.nv;

import static org.batfish.minesweeper.CommunityVarCollector.collectCommunityVars;
import static org.batfish.minesweeper.bdd.CommunityVarConverter.toCommunityVar;
import static org.batfish.minesweeper.nv.NVLang.mkInt;
import static org.batfish.minesweeper.nv.NVLang.mkOr;

import com.google.common.collect.Iterables;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import org.batfish.common.BatfishException;
import org.batfish.datamodel.CommunityList;
import org.batfish.datamodel.CommunityListLine;
import org.batfish.datamodel.Configuration;
import org.batfish.datamodel.LineAction;
import org.batfish.datamodel.Ip;
import org.batfish.datamodel.Prefix;
import org.batfish.datamodel.PrefixRange;
import org.batfish.datamodel.RouteFilterLine;
import org.batfish.datamodel.RouteFilterList;
import org.batfish.datamodel.RoutingProtocol;
import org.batfish.datamodel.SubRange;
import org.batfish.datamodel.routing_policy.expr.BooleanExpr;
import org.batfish.datamodel.routing_policy.expr.CommunitySetExpr;
import org.batfish.datamodel.routing_policy.expr.ExplicitPrefixSet;
import org.batfish.datamodel.routing_policy.expr.MatchCommunitySet;
import org.batfish.datamodel.routing_policy.expr.MatchPrefixSet;
import org.batfish.datamodel.routing_policy.expr.MatchProtocol;
import org.batfish.datamodel.routing_policy.expr.NamedCommunitySet;
import org.batfish.datamodel.routing_policy.expr.NamedPrefixSet;
import org.batfish.datamodel.routing_policy.expr.PrefixSetExpr;
import org.batfish.minesweeper.CommunityVar;
import org.batfish.minesweeper.Protocol;
import org.batfish.minesweeper.TransferParam;
import org.batfish.minesweeper.utils.Tuple;

public class TreeCompiler {

  private DecisionTree<Boolean> _tree;
  private Configuration _impConf;
  private Configuration _expConf;
  private CompilerOptions _flags;

  public TreeCompiler(DecisionTree<Boolean> _tree, Configuration _impConf, Configuration _expConf, CompilerOptions flags) {
    this._tree = _tree;
    this._impConf = _impConf;
    this._expConf = _expConf;
    this._flags = flags;
  }

  private String firstBitsEqual(String x, long y, int n) {
    assert (n >= 0 && n <= 32);
    if (n == 0) {
      return "true";
    }
    String lower = "" + Ip.create(y);
    String upper = "" + Ip.create(y + (int) Math.pow(2, 32 - n));
    return NVLang.mkAnd(NVLang.mkGe(x, lower), NVLang.mkLt(x, upper));
  }

  /*
   * Check if a prefix range match is applicable for the packet destination
   * Ip address, given the prefix length variable.
   */
  private String isRelevantFor(Environment env, PrefixRange range) {
    String prefixLen = env.get_prefixLength();
    Prefix p = range.getPrefix();
    SubRange r = range.getLengthRange();
    long pfx = p.getStartIp().asLong();
    int len = p.getPrefixLength();
    int lower = r.getStart();
    int upper = r.getEnd();
    // well formed prefix
    assert (p.getPrefixLength() <= lower && lower <= upper);
    String lowerBitsMatch = firstBitsEqual(env.get_prefixValue(), pfx, len);
    if (lower == upper) {
      String equalLen = NVLang.mkEq(prefixLen, NVLang.mkInt(lower,6));
      return NVLang.mkAnd(equalLen, lowerBitsMatch);
    } else {
      String lengthLowerBound = NVLang.mkGe(prefixLen, NVLang.mkInt(lower,6), 6);
      String lengthUpperBound = NVLang.mkLe(prefixLen, NVLang.mkInt(upper,6), 6);
      return NVLang.mkAnd(lengthLowerBound, NVLang.mkAnd(lengthUpperBound, lowerBitsMatch));
    }
  }

  /*
   * Converts a route filter list to a boolean expression.
   */
  private String matchFilterList(RouteFilterList x, Environment other) {
    String acc = "(false";

    List<RouteFilterLine> lines = new ArrayList<>(x.getLines());
    Collections.reverse(lines);

    for (RouteFilterLine line : lines) {
      if (!line.getIpWildcard().isPrefix()) {
        throw new BatfishException("non-prefix IpWildcards are unsupported");
      }
      Prefix p = line.getIpWildcard().toPrefix();
      SubRange r = line.getLengthRange();
      PrefixRange range = new PrefixRange(p, r);
      String matches = isRelevantFor(other, range);
      String action = NVLang.mkBool(line.getAction() == LineAction.PERMIT);
      acc = NVLang.mkIf(matches, action, acc);
    }
    return acc + ")";
  }

  /*
   * Converts a prefix set to NV expression.
   */
  private String matchPrefixSet(
      Configuration conf, PrefixSetExpr e, Environment other) {


    if (e instanceof ExplicitPrefixSet) {
      ExplicitPrefixSet x = (ExplicitPrefixSet) e;

      Set<PrefixRange> ranges = x.getPrefixSpace().getPrefixRanges();
      if (ranges.isEmpty()) {
        return "true";
      }

      // Compute if the other best route is relevant for this match statement
      String acc = "false";
      for (PrefixRange range : ranges) {
        if (acc.equals("false")) {
          acc = isRelevantFor(other, range);
        }
        else {
          acc = mkOr(acc, isRelevantFor(other, range));
        }
      }

      System.out.println("PrefixRange: " + acc);

      return ("(" + acc + ")");

    } else if (e instanceof NamedPrefixSet) {
      NamedPrefixSet x = (NamedPrefixSet) e;
      String name = x.getName();
      RouteFilterList fl = conf.getRouteFilterLists().get(name);
      System.out.println("NamedPrefixSet: " + matchFilterList(fl,other));
      return matchFilterList(fl, other);

    } else {
      throw new BatfishException("TODO: match prefix set: " + e);
    }
  }

  /*
   * Converts a community list to a boolean expression.
   */
  private String matchCommunityList(CommunityList cl, Environment other) {
    List<CommunityListLine> lines = new ArrayList<>(cl.getLines());
    Collections.reverse(lines);
    String acc = "false";
    for (CommunityListLine line : lines) {
      boolean action = (line.getAction() == LineAction.PERMIT);
      CommunityVar cvar = toCommunityVar(line.getMatchCondition());
      String c = other.get_communities() + "[" + NVLang.communityVarToNvValue(cvar) + "]";
      acc = NVLang.mkIf(c, NVLang.mkBool(action), acc);
    }
    return acc;
  }

  /*
   * Converts a community set to a boolean expression
   */
  private String matchCommunitySet(Configuration conf, CommunitySetExpr e, Environment other) {
    if (e instanceof CommunityList) {
      Set<CommunityVar> comms = collectCommunityVars(conf, e);
      String acc = "(true";
      for (CommunityVar comm : comms) {
        String c = other.get_communities() + "[" + comm.getLiteralValue() + "]";
        acc = NVLang.mkAnd(acc, c);
      }
      return acc + ")";
    }

    if (e instanceof NamedCommunitySet) {
      NamedCommunitySet x = (NamedCommunitySet) e;
      CommunityList cl = conf.getCommunityLists().get(x.getName());
      if (cl == null) {
        System.out.println("Looked up: " + x.getName() + ", from: " + conf.getHostname());
      }
      return matchCommunityList(cl, other);
    }

    throw new BatfishException("TODO: match community set");
  }

  private String computeReturn(TransferParam<Environment> p) {
    Environment data = p.getData();
    if (!data.get_valid()) return "None";
    // Compute updates for each field separately
    String ad = (data.get_ad().equals("b.bgpAd") ? "" :
                "bgpAd= " + data.get_ad() + "; ");
    String lp = (data.get_lp().equals("b.lp") ? "" :
                "lp= " + data.get_lp() + "; ");
    String aslen = (data.get_cost().equals("b.aslen") ? "" :
                "aslen= " + data.get_cost() + "; ");
    String med = (data.get_med().equals("b.med") ? "" :
                "med= " + data.get_med() + "; ");
    String comms = (data.get_communities().equals("b.comms") ? "" :
                "comms= " + data.get_communities() + "; ");
    String bgpOrigin =(!_flags.doOrigin() || data.get_bgpOrigin().equals("b.bgpOrigin") ? "" :
                "bgpOrigin= " + data.get_bgpOrigin() + "; ");
    String bgpNextHop =(!_flags.doNextHop() || data.get_bgpNextHop().equals("b.bgpNextHop") ? "" :
                "bgpNextHop= " + data.get_bgpNextHop() + "; ");

    // If no field is updated, just return the original value
    if (ad.equals("") && lp.equals("") && aslen.equals("") && med.equals("") &&
        comms.equals("") && bgpOrigin.equals("") && bgpNextHop.equals(""))
        return "(Some b)";

    StringBuilder sb = new StringBuilder();
    // If every field is updated, we don't need the "with" syntax
    if (!ad.equals("") && !lp.equals("") && !aslen.equals("") && !med.equals("") &&
        !comms.equals("") && (!bgpOrigin.equals("") || !_flags.doOrigin()) &&
        (!bgpNextHop.equals("") || !_flags.doNextHop())) {
      sb.append("(Some { ");
    } else {
      sb.append("(Some {b with ");
    }
    sb.append(ad).append(lp).append(aslen).append(med).append(comms).append(bgpOrigin).append(bgpNextHop);
    sb.append("})");
    return sb.toString();
  }

  private void debug(String msg) {
    System.out.println(msg);
  }

  private String compute(BooleanExpr expr, Environment env, boolean isExport) {
    Configuration conf = isExport ? _expConf : _impConf;
    if (expr instanceof MatchProtocol) {
      MatchProtocol mp = (MatchProtocol) expr;
      Set<RoutingProtocol> rps = mp.getProtocols();

      // Hack: We need to do a disjunction of MatchProtocol with multiple arguments.
      // Only does first protocol in the set.
      RoutingProtocol rp = Iterables.getFirst(rps, null);
      Protocol proto = rp == null ? null : Protocol.fromRoutingProtocol(rp);
      if (proto == null) {
        debug("MatchProtocol(" + rp.protocolName() + "): false");
        return "false";
      }

      String prot;
      if (proto.isConnected()) {
        prot = "p_CONNECTED";
      } else if (proto.isStatic()) {
        prot = "p_STATIC";
      } else if (proto.isOspf()) {
        prot = "p_OSPF";
      } else if (proto.isBgp()) {
        prot = "p_BGP";
      } else {
        throw new BatfishException("invalid protocol: " + proto.name());
      }
      String protoMatch = "(isProtocol " + env.get_protocol() + " " + prot + ")";
      debug("MatchProtocol(" + proto.name() + "): " + protoMatch);
      return protoMatch;
    }

    if (expr instanceof MatchPrefixSet) {
      debug("MatchPrefixSet");
      MatchPrefixSet m = (MatchPrefixSet) expr;
      // For BGP, may change prefix length
      return matchPrefixSet(conf, m.getPrefixSet(), env);

    } else if (expr instanceof MatchCommunitySet) {
      debug("MatchCommunitySet");
      MatchCommunitySet mcs = (MatchCommunitySet) expr;
      return matchCommunitySet(conf, mcs.getExpr(), env);

    }
    String msg =
        String.format("Unimplemented feature %s", expr.toString());

    throw new BatfishException(msg);
  }

  private String treeToNV(Node<Boolean> p, int i) {
    if (p.getLeft() == null && p.getRight() == null) {
      //System.out.println("Leaf: " + computeReturn(p.getEnv()));
      return computeReturn(p.getEnv());
    }
    else {
      // If it's not a leaf then it's a branching point.
      String guard;
      String l;
      String r;
      // Specialize the and case
      if ((p.getRight().getLeft() != null) && (p.getRight().getLeft() == p.getLeft())) {
        Node<Boolean> pr = p.getRight();
        guard = NVLang.mkAnd(
            compute(p.getExpr(), p.getEnv().getData(), p.getExport()),
            compute(pr.getExpr(), pr.getEnv().getData(), pr.getExport()));
        l = treeToNV(p.getLeft(), i + 1);
        r = treeToNV(pr.getRight(), i + 1);
      }
      // specialize the or case
      else if ((p.getLeft().getRight() != null) && (p.getLeft().getRight() == p.getRight())){
        Node<Boolean> pl = p.getLeft();
        guard = mkOr(
            compute(p.getExpr(), p.getEnv().getData(), p.getExport()),
            compute(pl.getExpr(), pl.getEnv().getData(), pl.getExport()));
        l = treeToNV(pl.getLeft(), i + 1);
        r = treeToNV(p.getRight(), i + 1);
      }
      else {
        guard = compute(p.getExpr(), p.getEnv().getData(), p.getExport());
        //System.out.println("Left child: ");
        l = treeToNV(p.getLeft(), i + 1);
        //System.out.println("Right child: ");
        r = treeToNV(p.getRight(), i + 1);
      }
      return NVLang.mkIf(guard, r, l);
    }
  }

  // TODO: implement AND-OR optimizations.
  private List<Tuple<String, Node<Boolean>>> doChild(String guard, Node<Boolean> p) {
    if (p.getExpr() != null && p.getExpr() instanceof MatchPrefixSet) {
      List<Tuple<String, Node<Boolean>>> lst = treeToList(p);
      int sz = lst.size();
      for (int idx = 0; idx < sz; idx++) {
        Tuple<String,Node<Boolean>> elt = lst.get(idx);
        lst.set(idx, new Tuple<>(NVLang.mkAnd(guard, elt.getFirst()), elt.getSecond()));
      }
      return lst;
    }
    else {
      List<Tuple<String, Node<Boolean>>> res = new ArrayList<>();
      res.add(new Tuple<>(guard, p));
      return res;
    }
  }
  /* This function is used to compile the prefix-related nodes. It returns a list of
      string (the compiled prefix conditions) and trees/nodes that are the value-related nodes that
      apply to these prefixes and are to be compiled.
   */
  private List<Tuple<String, Node<Boolean>>> treeToList(Node<Boolean> p) {
    String guard = compute(p.getExpr(), p.getEnv().getData(), p.getExport());
    List<Tuple<String, Node<Boolean>>> lstTrue = doChild(guard, p.getRight());
    List<Tuple<String, Node<Boolean>>> lstFalse = doChild(NVLang.mkNot(guard), p.getLeft());
    lstTrue.addAll(lstFalse);
    return lstTrue;
  }

  /* Use for BDD-based simulator */
  public List<Tuple<String, String>> toNvStrings () {
    /* Traverse the tree from root and produce an NV program */
    Node<Boolean> head = _tree.getRoot();
    List<Tuple<String, String>> results = new ArrayList<>();
    if (head.getExpr() != null && head.getExpr() instanceof MatchPrefixSet) {
      List<Tuple<String, Node<Boolean>>> funs = treeToList(head);
      int sz = funs.size();
      for (int i = 0; i < sz; i++) {
        Tuple<String, Node<Boolean>> fn = funs.get(i);
        results.add(i, new Tuple<>(fn.getFirst(),treeToNV(fn.getSecond(), 2)));
      }
      return results;
    }
    else {
      results.add(new Tuple<>("", treeToNV(head, 2)));
      return results;
    }
  }

  public String toNvString () {
    /* Traverse the tree from root and produce an NV program */
    //System.out.println("Traversing tree");
    Node<Boolean> head = _tree.getRoot();
    return treeToNV(head, 2);

  }
}
