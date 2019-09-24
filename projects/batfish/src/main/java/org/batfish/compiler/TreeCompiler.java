package org.batfish.compiler;

import static org.batfish.compiler.NVFunctions.communityVarToNvValue;
import static org.batfish.compiler.NVFunctions.indent;
import static org.batfish.compiler.NVFunctions.mkAnd;
import static org.batfish.compiler.NVFunctions.mkBool;
import static org.batfish.compiler.NVFunctions.mkEq;
import static org.batfish.compiler.NVFunctions.mkGe;
import static org.batfish.compiler.NVFunctions.mkIf;
import static org.batfish.compiler.NVFunctions.mkInt;
import static org.batfish.compiler.NVFunctions.mkLe;
import static org.batfish.compiler.NVFunctions.mkLt;
import static org.batfish.compiler.NVFunctions.mkOr;
import static org.batfish.symbolic.CommunityVarCollector.collectCommunityVars;
import static org.batfish.symbolic.bdd.CommunityVarConverter.toCommunityVar;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import org.batfish.common.BatfishException;
import org.batfish.datamodel.CommunityList;
import org.batfish.datamodel.CommunityListLine;
import org.batfish.datamodel.Configuration;
import org.batfish.datamodel.LineAction;
import org.batfish.datamodel.Prefix;
import org.batfish.datamodel.PrefixRange;
import org.batfish.datamodel.RouteFilterLine;
import org.batfish.datamodel.RouteFilterList;
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
import org.batfish.symbolic.CommunityVar;
import org.batfish.symbolic.Protocol;

public class TreeCompiler {

  private DecisionTree<Boolean> _tree;
  private Configuration _conf;

  public TreeCompiler(DecisionTree<Boolean> _tree, Configuration _conf) {
    this._tree = _tree;
    this._conf = _conf;
  }

  public DecisionTree<Boolean> getTree() {
    return _tree;
  }

  public void setTree(DecisionTree<Boolean> tree) {
    this._tree = tree;
  }


  private String firstBitsEqual(String x, long y, int n) {
    assert (n >= 0 && n <= 32);
    if (n == 0) {
      return "true";
    }
    String lower = "" + y;
    String upper = "" + y + (int) Math.pow(2, 32 - n);
    return mkAnd(mkGe(x, lower), mkLt(x, upper));
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
      String equalLen = mkEq(prefixLen, mkInt(lower));
      return mkAnd(equalLen, lowerBitsMatch);
    } else {
      String lengthLowerBound = mkGe(prefixLen, mkInt(lower));
      String lengthUpperBound = mkLe(prefixLen, mkInt(upper));
      return mkAnd(lengthLowerBound, mkAnd(lengthUpperBound, lowerBitsMatch));
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
      String action = mkBool(line.getAction() == LineAction.PERMIT);
      acc = mkIf(matches, action, acc);
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
        acc = mkOr(acc, isRelevantFor(other, range));
      }

      return ("(" + acc + ")");

    } else if (e instanceof NamedPrefixSet) {
      NamedPrefixSet x = (NamedPrefixSet) e;
      String name = x.getName();
      RouteFilterList fl = conf.getRouteFilterLists().get(name);
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
      String c = other.get_communities() + "[" + communityVarToNvValue(cvar) + "]";
      acc = mkIf(c, mkBool(action), acc);
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
        String c = other.get_communities() + "[" + comm.getValue() + "]";
        acc = mkAnd(acc, c);
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

  private String computeReturn(TransferParam<Environment> p, int i) {
    return (p.getData().get_valid() ?
        indent("(Some {bgpAd= "
            + p.getData().get_ad()
            + "; "
            + "lp= "
            + p.getData().get_lp()
            + "; "
            + "aslen= "
            + p.getData().get_cost()
            + " + 1"
            + "; "
            + "med= "
            + p.getData().get_med()
            + ";"
            + "comms= "
            + p.getData().get_communities()
            + ";})", i) : indent("None", i));
  }

  private void debug(String msg) {
    System.out.println(msg);
  }

  private String compute(BooleanExpr expr, Environment env) {
    if (expr instanceof MatchProtocol) {
      MatchProtocol mp = (MatchProtocol) expr;
      Protocol proto = Protocol.fromRoutingProtocol(mp.getProtocol());
      if (proto == null) {
        debug("MatchProtocol(" + mp.getProtocol().protocolName() + "): false");
        return "false";
      }

      int x;
      if (proto.isConnected()) {
        x = 0;
      } else if (proto.isStatic()) {
        x = 1;
      } else if (proto.isOspf()) {
        x = 2;
      } else if (proto.isBgp()) {
        x = 3;
      } else {
        throw new BatfishException("invalid protocol: " + proto.name());
      }
      String protoMatch = "(isProtocol " + env.get_protocol() + " " + x + ")";
      debug("MatchProtocol(" + mp.getProtocol().protocolName() + "): " + protoMatch);
      return protoMatch;
    }

    if (expr instanceof MatchPrefixSet) {
      debug("MatchPrefixSet");
      MatchPrefixSet m = (MatchPrefixSet) expr;
      // For BGP, may change prefix length
      return matchPrefixSet(_conf, m.getPrefixSet(), env);

    } else if (expr instanceof MatchCommunitySet) {
      debug("MatchCommunitySet");
      MatchCommunitySet mcs = (MatchCommunitySet) expr;
      return matchCommunitySet(_conf, mcs.getExpr(), env);

    }
    String msg =
        String.format("Unimplemented feature %s", expr.toString());

    throw new BatfishException(msg);
  }

  private String treeToNV(Node<Boolean> p, int i) {
    if (p.isLeaf()) {
      return computeReturn(p.getEnv(), i);
    }
    else {
      // If it's not a leaf then it's a branching point.
      String guard = compute(p.getExpr(), p.getEnv().getData());
      return mkIf(guard, treeToNV(p.getRight(),i+1), treeToNV(p.getLeft(), i+1));
    }
  }

  public String toNvString () {
    /* Traverse the tree from root and produce an NV program */
    Node<Boolean> head = _tree.getRoot();
    return treeToNV(head, 0);

  }
}
