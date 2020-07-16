package org.batfish.minesweeper.nv;

import static org.batfish.minesweeper.CommunityVarCollector.collectCommunityVars;
import static org.batfish.minesweeper.bdd.CommunityVarConverter.toCommunityVar;
import static org.batfish.minesweeper.nv.ast.NVExpBuilder.isFalse;
import static org.batfish.minesweeper.nv.ast.NVExpBuilder.mkAnd;
import static org.batfish.minesweeper.nv.ast.NVExpBuilder.mkIf;
import static org.batfish.minesweeper.nv.ast.NVExpBuilder.mkNot;
import static org.batfish.minesweeper.nv.ast.NVExpBuilder.mkOr;

import com.google.common.collect.Iterables;
import com.microsoft.z3.BoolExpr;
import com.microsoft.z3.Context;
import com.microsoft.z3.Solver;
import com.microsoft.z3.Status;
import com.microsoft.z3.Tactic;
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
import org.batfish.minesweeper.nv.ast.BoolExp;
import org.batfish.minesweeper.nv.ast.Exp;
import org.batfish.minesweeper.nv.ast.ExpAsString;
import org.batfish.minesweeper.nv.ast.NVExpBuilder;
import org.batfish.minesweeper.nv.ast.VariableExp;
import org.batfish.minesweeper.utils.Tuple;

public class TreeCompiler {

  private DecisionTree<Boolean> _tree;
  private Configuration _impConf;
  private Configuration _expConf;
  private CompilerOptions _flags;
  private Context _ctx;
  private Solver _solver;


  public TreeCompiler(DecisionTree<Boolean> _tree, Configuration _impConf, Configuration _expConf, CompilerOptions flags) {
    this._tree = _tree;
    this._impConf = _impConf;
    this._expConf = _expConf;
    this._flags = flags;

    this._ctx = new Context();

    Tactic t1 = _ctx.mkTactic("simplify");
    Tactic t2 = _ctx.mkTactic("propagate-values");
    Tactic t3 = _ctx.mkTactic("solve-eqs");
    Tactic t4 = _ctx.mkTactic("smt");
    Tactic t = _ctx.then(t1, t2, t3, t4);
    _solver = _ctx.mkSolver(t);
  }

//  private String firstBitsEqual(String x, long y, int n) {
//    assert (n >= 0 && n <= 32);
//    if (n == 0) {
//      return "true";
//    }
//    String lower = "" + Ip.create(y);
//    String upper = "" + Ip.create(y + (int) Math.pow(2, 32 - n));
//    return NVExpBuilder.mkAnd(NVLang.mkGe(x, lower), NVLang.mkLt(x, upper));
//  }
//
//  /*
//   * Check if a prefix range match is applicable for the packet destination
//   * Ip address, given the prefix length variable.
//   */
//  private String isRelevantFor(Environment env, PrefixRange range) {
//    String prefixLen = env.get_prefixLength();
//    Prefix p = range.getPrefix();
//    SubRange r = range.getLengthRange();
//    long pfx = p.getStartIp().asLong();
//    int len = p.getPrefixLength();
//    int lower = r.getStart();
//    int upper = r.getEnd();
//    // well formed prefix
//    assert (p.getPrefixLength() <= lower && lower <= upper);
//    String lowerBitsMatch = firstBitsEqual(env.get_prefixValue(), pfx, len);
//    if (lower == upper) {
//      String equalLen = NVLang.mkEq(prefixLen, NVLang.mkInt(lower,6));
//      return NVLang.mkAnd(equalLen, lowerBitsMatch);
//    } else {
//      String lengthLowerBound = NVLang.mkGe(prefixLen, NVLang.mkInt(lower,6), 6);
//      String lengthUpperBound = NVLang.mkLe(prefixLen, NVLang.mkInt(upper,6), 6);
//      return NVLang.mkAnd(lengthLowerBound, NVLang.mkAnd(lengthUpperBound, lowerBitsMatch));
//    }
//  }

  /*
   * Check if a prefix range match is applicable for the packet destination
   * Ip address, given the prefix length variable.
   */
  private Exp isRelevantFor(Environment env, PrefixRange range) {
    Prefix p = range.getPrefix();
    SubRange r = range.getLengthRange();
    long pfx = p.getStartIp().asLong();
    int len = p.getPrefixLength();
    int lower = r.getStart();
    int upper = r.getEnd();
    // well formed prefix
    assert (p.getPrefixLength() <= lower && lower <= upper);
    return NVExpBuilder.mkPrefixMatch(new VariableExp(env.get_prefixLength()),
        new VariableExp(env.get_prefixValue()), lower, upper, pfx, len);
  }

  /*
   * Converts a route filter list to a boolean expression.
   */
  private Exp matchFilterList(RouteFilterList x, Environment other) {
    Exp acc = new BoolExp(false);

    List<RouteFilterLine> lines = new ArrayList<>(x.getLines());
    Collections.reverse(lines);

    for (RouteFilterLine line : lines) {
      if (!line.getIpWildcard().isPrefix()) {
        throw new BatfishException("non-prefix IpWildcards are unsupported");
      }
      Prefix p = line.getIpWildcard().toPrefix();
      SubRange r = line.getLengthRange();
      PrefixRange range = new PrefixRange(p, r);
      Exp matches = isRelevantFor(other, range);
      Exp action = new BoolExp(line.getAction() == LineAction.PERMIT);
      acc = mkIf(matches, action, acc);
    }
    return acc;
  }

  /*
   * Converts a prefix set to NV expression.
   */
  private Exp matchPrefixSet(Configuration conf, PrefixSetExpr e, Environment other) {


    if (e instanceof ExplicitPrefixSet) {
      ExplicitPrefixSet x = (ExplicitPrefixSet) e;

      Set<PrefixRange> ranges = x.getPrefixSpace().getPrefixRanges();
      if (ranges.isEmpty()) {
        return new BoolExp(true);
      }

      // Compute if the other best route is relevant for this match statement
      Exp acc = new BoolExp(false);
      for (PrefixRange range : ranges) {
        if (isFalse(acc)) {
          acc = isRelevantFor(other, range);
        }
        else {
          acc = mkOr(acc, isRelevantFor(other, range));
        }
      }

      System.out.println("PrefixRange: " + acc);

      return acc;

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
  private Exp matchCommunityList(CommunityList cl, Environment other) {
    List<CommunityListLine> lines = new ArrayList<>(cl.getLines());
    Collections.reverse(lines);
    Exp acc = new BoolExp(false);
    for (CommunityListLine line : lines) {
      boolean action = (line.getAction() == LineAction.PERMIT);
      CommunityVar cvar = toCommunityVar(line.getMatchCondition());
      //TODO: Being lazy here and not introducing a communities exp or smth. Instead using a variable.
      String c = other.get_communities() + "[" + NVLang.communityVarToNvValue(cvar) + "]";
      acc = mkIf(new VariableExp(c), new BoolExp(action), acc);
    }
    return acc;
  }

  /*
   * Converts a community set to a boolean expression
   */
  private Exp matchCommunitySet(Configuration conf, CommunitySetExpr e, Environment other) {
    if (e instanceof CommunityList) {
      Set<CommunityVar> comms = collectCommunityVars(conf, e);
      Exp acc = new BoolExp(true);
      for (CommunityVar comm : comms) {
        String c = other.get_communities() + "[" + comm.getLiteralValue() + "]";
        acc = mkAnd(acc, new ExpAsString(c));
      }
      return acc;
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

  private Exp compute(BooleanExpr expr, Environment env, boolean isExport) {
    Configuration conf = isExport ? _expConf : _impConf;
    if (expr instanceof MatchProtocol) {
      MatchProtocol mp = (MatchProtocol) expr;
      Set<RoutingProtocol> rps = mp.getProtocols();

      // Hack: We need to do a disjunction of MatchProtocol with multiple arguments.
      // Only does first protocol in the set.
      RoutingProtocol rp = Iterables.getFirst(rps, null);
      Protocol proto = rp == null ? null : Protocol.fromRoutingProtocol(rp);
      if (proto == null) {
        debug("MatchProtocol(" + (rp != null ? rp.protocolName() : null) + "): false");
        return new BoolExp(false);
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
      Exp protoMatch = new ExpAsString("(isProtocol " + env.get_protocol() + " " + prot + ")");
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
        String.format("Unimplemented feature %s", expr);

    throw new BatfishException(msg);
  }

  private Exp treeToNV(Node<Boolean> p, int i) {
    if (p.getLeft() == null && p.getRight() == null) {
      //System.out.println("Leaf: " + computeReturn(p.getEnv()));
      return new ExpAsString(computeReturn(p.getEnv()));
    }
    else {
      // If it's not a leaf then it's a branching point.
      Exp guard;
      Exp l;
      Exp r;
      // Specialize the and case
      if ((p.getRight().getLeft() != null) && (p.getRight().getLeft() == p.getLeft())) {
        Node<Boolean> pr = p.getRight();
        guard = mkAnd(
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
      return mkIf(guard, r, l);
    }
  }

  // TODO: implement AND-OR optimizations.
  private List<Tuple<Exp, Node<Boolean>>> doChild(Exp guard, Node<Boolean> p) {
    if (p.getExpr() != null && p.getExpr() instanceof MatchPrefixSet) {
      List<Tuple<Exp, Node<Boolean>>> lst = treeToList(p);
      int sz = lst.size();
      for (int idx = 0; idx < sz; idx++) {
        Tuple<Exp,Node<Boolean>> elt = lst.get(idx);
        lst.set(idx, new Tuple<>(mkAnd(guard, elt.getFirst()), elt.getSecond()));
      }
      return lst;
    }
    else {
      List<Tuple<Exp, Node<Boolean>>> res = new ArrayList<>();
      res.add(new Tuple<>(guard, p));
      return res;
    }
  }
  /* This function is used to compile the prefix-related nodes. It returns a list of
      string (the compiled prefix conditions) and trees/nodes that are the value-related nodes that
      apply to these prefixes and are to be compiled.
   */
  private List<Tuple<Exp, Node<Boolean>>> treeToList(Node<Boolean> p) {
    Exp guard = compute(p.getExpr(), p.getEnv().getData(), p.getExport());
    List<Tuple<Exp, Node<Boolean>>> lstTrue = doChild(guard, p.getRight());
    List<Tuple<Exp, Node<Boolean>>> lstFalse = doChild(mkNot(guard), p.getLeft());
    lstTrue.addAll(lstFalse);
    return lstTrue;
  }

  /* Use for BDD-based simulator */
  public List<Tuple<String, String>> toNvStrings () {
    /* Traverse the tree from root and produce an NV program */
    Node<Boolean> head = _tree.getRoot();
    List<Tuple<String, String>> results = new ArrayList<>();
    if (head.getExpr() != null && head.getExpr() instanceof MatchPrefixSet) {
      // The first element of this tuple is the conditions on the key of the map (i.e., the prefix)
      // TODO: This is what we want to optimize the Exp of funs.
      List<Tuple<Exp, Node<Boolean>>> funs = treeToList(head);
      int sz = funs.size();
      for (int i = 0; i < sz; i++) {
        Tuple<Exp, Node<Boolean>> fn = funs.get(i);
        if (!refuteViaSmt(fn.getFirst())) {
          results.add(new Tuple<>(fn.getFirst().toString(),treeToNV(fn.getSecond(), 2).toString()));
        }
//        results.add(i, new Tuple<>(fn.getFirst().toString(),treeToNV(fn.getSecond(), 2).toString()));
      }
      return results;
    }
    else {
      results.add(new Tuple<>("", treeToNV(head, 2).toString()));
      return results;
    }
  }

  public String toNvString () {
    /* Traverse the tree from root and produce an NV program */
    //System.out.println("Traversing tree");
    Node<Boolean> head = _tree.getRoot();
    return treeToNV(head, 2).toString();

  }


  private boolean refuteViaSmt(Exp e) {
    _solver.reset();
    _solver.add((BoolExpr) e.toSmt(_ctx));
    Status s = _solver.check();
    return s == Status.UNSATISFIABLE;
  }

}
