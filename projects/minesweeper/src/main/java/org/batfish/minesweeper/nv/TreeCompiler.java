package org.batfish.minesweeper.nv;

import static org.batfish.minesweeper.CommunityVarCollector.collectCommunityVars;
import static org.batfish.minesweeper.bdd.CommunityVarConverter.toCommunityVar;
import static org.batfish.minesweeper.nv.ast.NVExpBuilder.isFalse;
import static org.batfish.minesweeper.nv.ast.NVExpBuilder.mkAnd;
import static org.batfish.minesweeper.nv.ast.NVExpBuilder.mkBool;
import static org.batfish.minesweeper.nv.ast.NVExpBuilder.mkIf;
import static org.batfish.minesweeper.nv.ast.NVExpBuilder.mkNot;
import static org.batfish.minesweeper.nv.ast.NVExpBuilder.mkOr;

import com.microsoft.z3.BoolExpr;
import com.microsoft.z3.Context;
import com.microsoft.z3.Solver;
import com.microsoft.z3.Status;
import com.microsoft.z3.Tactic;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
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

      return acc;

    } else if (e instanceof NamedPrefixSet) {
      NamedPrefixSet x = (NamedPrefixSet) e;
      String name = x.getName();
      RouteFilterList fl = conf.getRouteFilterLists().get(name);
//      System.out.println("NamedPrefixSet: " + matchFilterList(fl,other));
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

  /* Compute the route returned based on the current environment */
  private String computeReturn(TransferParam<Environment> p, boolean isExport) {
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
//    System.out.println(msg);
  }

  private Exp compute(BooleanExpr expr, Environment env, boolean isExport) {
    Configuration conf = isExport ? _expConf : _impConf;
    if (expr instanceof MatchProtocol) {
      MatchProtocol mp = (MatchProtocol) expr;
      Set<RoutingProtocol> rps = mp.getProtocols();

      // Hack: We need to do a disjunction of MatchProtocol with multiple arguments.
      Protocol proto = null;
      Iterator<RoutingProtocol> rp = rps.iterator();

      while (proto == null && rp.hasNext()) {
        proto = Protocol.fromRoutingProtocol(rp.next());
      }
//      RoutingProtocol rp = Iterables.getFirst(rps, null);
//      Protocol proto = rp == null ? null : Protocol.fromRoutingProtocol(rp);
      if (proto == null) {
        debug("MatchProtocol(" + rps + "): false");
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


  private Map<Exp,Exp> treeToNVExp(Node<Boolean> p) {
    /* If node p is a leaf then there are no prefix constraints on it (so the key of the map is set
       to a true expression), and the value of the map is a String with the current environment.
       NOTE: We use a string expression because we don't have classes to model the full NV language
     */

    Map<Exp, Exp> result = new HashMap<>();

    if (p.isLeaf()) {
      result.put(mkBool(true), new ExpAsString(computeReturn(p.getEnv(), p.getExport())));
    }
    /* If node p is a prefix node then compute the maps of the left and right child
       and add the appropriate condition to each of them.
     */
    else if (p.getExpr() != null && p.getExpr() instanceof MatchPrefixSet) {
      // Since this is a decision node both children must not be null - otherwise something is wrong
      assert (p.getLeft() != null && p.getRight() != null);
      Map<Exp, Exp> left = treeToNVExp(p.getLeft());
      Map<Exp, Exp> right = treeToNVExp(p.getRight());

      // Compute the condition for p
      Exp guard = compute(p.getExpr(), p.getEnv().getData(), p.getExport());

      for (Entry<Exp,Exp> l : left.entrySet()) {
        // For left sub-tree, the condition will be ~p /\ l.key
        result.put(mkAnd(mkNot(guard), l.getKey()), l.getValue());
      }

      for (Entry<Exp,Exp> r : right.entrySet()) {
        // For left sub-tree, the condition will be p /\ r.key
        result.put(mkAnd(guard, r.getKey()), r.getValue());
      }
    }
    /* Otherwise if p is another conditional node (not on the prefix) we compute the left and
       right cases and then combine them pointwise. For instance, if the condition is f
       and we get maps {cl1 -> vl1, ~cl1 -> vl2} and {cr1 -> vr1, ~cr1 -> vr2} we compute:
       {cr1 /\ cl1 -> if f then vr1 else vl1, cr1 /\ ~cl1 -> if f then vr1 else vl2,
        ~cr1 /\ cl1 -> if then vr2 else vl1, ~cr1 /\ ~cl1 -> if f then vr2 else vl2}
        TODO: I need to make a correctness argument for this.
     */
    else {
      // Since this is a decision node both children must not be null - otherwise something is wrong
      assert (p.getLeft() != null && p.getRight() != null);
      Map<Exp, Exp> left = treeToNVExp(p.getLeft());
      Map<Exp, Exp> right = treeToNVExp(p.getRight());

      Exp guard = compute(p.getExpr(), p.getEnv().getData(), p.getExport());

      // Combine piecewise
      for (Entry<Exp,Exp> r : right.entrySet()) {
        for (Entry<Exp,Exp> l : left.entrySet()) {
          result.put(mkAnd(r.getKey(),l.getKey()), mkIf(guard, r.getValue(), l.getValue()));
        }
      }
    }
    return result;
  }

  /* Compiles the policy to a list of string tuples, where the first string is the
    condition on the prefix and the second string is the value returned, i.e.
    per-prefix policy.
  */
  public List<Tuple<String, String>> toNvStrings () {
    /* Traverse the tree from root and produce an NV program */
    Node<Boolean> head = _tree.getRoot();

    Map<Exp,Exp> m = treeToNVExp(head);

    List<Tuple<String, String>> result = new LinkedList<>();
    for (Entry<Exp, Exp> e : m.entrySet()) {
      // If the entry is not refutable then add it to the list.
      if (!refuteViaSmt(e.getKey())) {
        result.add(new Tuple<>(e.getKey().toString(), e.getValue().toString()));
      }
    }
    return result;
  }


  /*** For Single Prefix ***/


  /* Traverse the tree and build NV expressions of the policy. Used when we don't need to separate
     between prefix and non-prefix expressions.
     The pathCondition variable is used as an optimization; it keeps track of the prefix conditions
     to reach this point in the execution and uses SMT to eliminate any path that may be impossible.
     The representation we get from Batfish often has paths that are implausible based on the given
     prefix conditions.
   */
  private Exp treeToNV(Node<Boolean> p, Exp pathCondition, int i) {
    if (p.getLeft() == null && p.getRight() == null) {
      //System.out.println("Leaf: " + computeReturn(p.getEnv()));
      return new ExpAsString(computeReturn(p.getEnv(), p.getExport()));
    }
    else {
      // If it's not a leaf then it's a branching point.
      Exp guard = compute(p.getExpr(), p.getEnv().getData(), p.getExport());
      Exp l;
      Exp r;

      Exp rightPc = pathCondition;
      Exp leftPc = pathCondition;

      // If it's a prefix expression then we will add it to the path condition
      if (p.getExpr() != null && p.getExpr() instanceof MatchPrefixSet) {
        // Modify the path conditions for the right and left paths.
        rightPc = mkAnd(guard, pathCondition);
        leftPc = mkAnd(mkNot(guard), pathCondition);

        // If the current PC and the current expression are impossible, then this expression cannot hold
        // so return the left sub-tree. Add the negation of the current expression to the PC.
        if (refuteViaSmt(rightPc)) {
          return treeToNV(p.getLeft(), leftPc, i+1);
        }

        // Likewise for the negation of the current expression.
        if (refuteViaSmt(leftPc)) {
          return treeToNV(p.getRight(), rightPc, i+1);
        }
      }


      // Otherwise, if it's not a prefix expression.

      // Specialize the and case
      if ((p.getRight().getLeft() != null) && (p.getRight().getLeft() == p.getLeft())) {
        Node<Boolean> pr = p.getRight();
        guard = mkAnd(
            guard,
            compute(pr.getExpr(), pr.getEnv().getData(), pr.getExport()));
        l = treeToNV(p.getLeft(), leftPc, i + 1);
        r = treeToNV(pr.getRight(), rightPc, i + 1);
      }
      // specialize the or case
      else if ((p.getLeft().getRight() != null) && (p.getLeft().getRight() == p.getRight())){
        Node<Boolean> pl = p.getLeft();
        guard = mkOr(
            guard,
            compute(pl.getExpr(), pl.getEnv().getData(), pl.getExport()));
        l = treeToNV(pl.getLeft(), leftPc, i + 1);
        r = treeToNV(p.getRight(), rightPc, i + 1);
      }
      else {
        l = treeToNV(p.getLeft(), leftPc,  i + 1);
        r = treeToNV(p.getRight(), rightPc,i + 1);
      }
      return mkIf(guard, r, l);
    }
  }

  /* Compiles the policy to a single string, i.e. we don't "separate" between conditionals on prefix.
   */
  public String toNvString () {
    /* Traverse the tree from root and produce an NV program */
    //System.out.println("Traversing tree");
    Node<Boolean> head = _tree.getRoot();
    return treeToNV(head, mkBool(true),2).toString();

  }

  /* Checks if the given expression is unsatisfiable using SMT.
     Returns true if so.
   */
  private boolean refuteViaSmt(Exp e) {
    _solver.reset();
    _solver.add((BoolExpr) e.toSmt(_ctx));
    Status s = _solver.check();
    return s == Status.UNSATISFIABLE;
  }

}


