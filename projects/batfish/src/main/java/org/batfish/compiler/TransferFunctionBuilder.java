package org.batfish.compiler;

import static org.batfish.symbolic.CommunityVarCollector.collectCommunityVars;
import static org.batfish.symbolic.bdd.CommunityVarConverter.toCommunityVar;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.ListIterator;
import java.util.Set;
import javax.annotation.Nullable;
import org.batfish.common.BatfishException;
import org.batfish.common.Pair;
import org.batfish.datamodel.CommunityList;
import org.batfish.datamodel.CommunityListLine;
import org.batfish.datamodel.Configuration;
import org.batfish.datamodel.Interface;
import org.batfish.datamodel.LineAction;
import org.batfish.datamodel.Prefix;
import org.batfish.datamodel.PrefixRange;
import org.batfish.datamodel.RouteFilterLine;
import org.batfish.datamodel.RouteFilterList;
import org.batfish.datamodel.SubRange;
import org.batfish.datamodel.routing_policy.RoutingPolicy;
import org.batfish.datamodel.routing_policy.expr.AsPathListExpr;
import org.batfish.datamodel.routing_policy.expr.BooleanExpr;
import org.batfish.datamodel.routing_policy.expr.BooleanExprs;
import org.batfish.datamodel.routing_policy.expr.CallExpr;
import org.batfish.datamodel.routing_policy.expr.CommunitySetExpr;
import org.batfish.datamodel.routing_policy.expr.Conjunction;
import org.batfish.datamodel.routing_policy.expr.ConjunctionChain;
import org.batfish.datamodel.routing_policy.expr.DecrementLocalPreference;
import org.batfish.datamodel.routing_policy.expr.DecrementMetric;
import org.batfish.datamodel.routing_policy.expr.Disjunction;
import org.batfish.datamodel.routing_policy.expr.DisjunctionChain;
import org.batfish.datamodel.routing_policy.expr.ExplicitPrefixSet;
import org.batfish.datamodel.routing_policy.expr.IncrementLocalPreference;
import org.batfish.datamodel.routing_policy.expr.IncrementMetric;
import org.batfish.datamodel.routing_policy.expr.IntExpr;
import org.batfish.datamodel.routing_policy.expr.LiteralAsList;
import org.batfish.datamodel.routing_policy.expr.LiteralInt;
import org.batfish.datamodel.routing_policy.expr.LiteralLong;
import org.batfish.datamodel.routing_policy.expr.LongExpr;
import org.batfish.datamodel.routing_policy.expr.MatchAsPath;
import org.batfish.datamodel.routing_policy.expr.MatchCommunitySet;
import org.batfish.datamodel.routing_policy.expr.MatchIpv4;
import org.batfish.datamodel.routing_policy.expr.MatchIpv6;
import org.batfish.datamodel.routing_policy.expr.MatchPrefix6Set;
import org.batfish.datamodel.routing_policy.expr.MatchPrefixSet;
import org.batfish.datamodel.routing_policy.expr.MatchProtocol;
import org.batfish.datamodel.routing_policy.expr.MultipliedAs;
import org.batfish.datamodel.routing_policy.expr.NamedCommunitySet;
import org.batfish.datamodel.routing_policy.expr.NamedPrefixSet;
import org.batfish.datamodel.routing_policy.expr.Not;
import org.batfish.datamodel.routing_policy.expr.PrefixSetExpr;
import org.batfish.datamodel.routing_policy.expr.WithEnvironmentExpr;
import org.batfish.datamodel.routing_policy.statement.AddCommunity;
import org.batfish.datamodel.routing_policy.statement.DeleteCommunity;
import org.batfish.datamodel.routing_policy.statement.If;
import org.batfish.datamodel.routing_policy.statement.PrependAsPath;
import org.batfish.datamodel.routing_policy.statement.RetainCommunity;
import org.batfish.datamodel.routing_policy.statement.SetCommunity;
import org.batfish.datamodel.routing_policy.statement.SetDefaultPolicy;
import org.batfish.datamodel.routing_policy.statement.SetLocalPreference;
import org.batfish.datamodel.routing_policy.statement.SetMetric;
import org.batfish.datamodel.routing_policy.statement.SetNextHop;
import org.batfish.datamodel.routing_policy.statement.SetOrigin;
import org.batfish.datamodel.routing_policy.statement.Statement;
import org.batfish.datamodel.routing_policy.statement.Statements.StaticStatement;
import org.batfish.symbolic.CommunityVar;
import org.batfish.symbolic.GraphEdge;
import org.batfish.symbolic.Protocol;
import org.batfish.symbolic.collections.PList;

class TransferFunctionBuilder {

  private static int id = 0;

  private Configuration _conf;

  private List<Statement> _statements;

  private Interface _iface;

  private GraphEdge _graphEdge;

  private boolean _isExport;

  TransferFunctionBuilder(
      Configuration conf, List<Statement> statements, GraphEdge ge, boolean isExport) {
    _conf = conf;
    _statements = statements;
    _graphEdge = ge;
    _iface = ge.getStart();
    _isExport = isExport;
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
  String isRelevantFor(Environment env, PrefixRange range) {
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
   * Converts a prefix set to a boolean expression.
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

  private long communityVarToNvValue(CommunityVar cvar) {
    Long l = cvar.asLong();
    if (l == null) {
      return 0;
    }
    return l;
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

  /*
   * Wrap a simple boolean expression return value in a transfer function result
   */
  private TransferResult<String, String> fromExpr(String b) {
    return new TransferResult<String, String>().setReturnAssignedValue("true").setReturnValue(b);
  }

  private TransferResult<String, String> initialResult() {
    return new TransferResult<String, String>()
        .setReturnValue("false")
        .setFallthroughValue("false")
        .setReturnAssignedValue("false");
  }

  private DecisionTree<Environment>mapEnv(DecisionTree<Boolean> t, DecisionTree<Environment> tEnv, DecisionTree<Environment> fEnv) {
    

  }

  /*
   * Convert a Batfish AST boolean expression to a symbolic Z3 boolean expression
   * by performing inlining of stateful side effects.
   */
  @Nullable
  private DecisionTree<Boolean> compute(BooleanExpr expr, Node<Boolean> tleaf, Node<Boolean> fleaf) {
    // TODO: right now everything is IPV4
    if (expr instanceof MatchIpv4) {
      //pCur.debug("MatchIpv4");
      Set<Node<Boolean>> s = new HashSet<>();
      s.add(tleaf);
      return new DecisionTree<>(tleaf,s);
    }
    if (expr instanceof MatchIpv6) {
      //pCur.debug("MatchIpv6");
      Set<Node<Boolean>> s = new HashSet<>();
      s.add(fleaf);
      return new DecisionTree<>(fleaf,s);
    }

    if (expr instanceof Conjunction) {
      //pCur.debug("Conjunction");
      //TODO: implement some optimizations such as putting a prefix-expression as root.
      Conjunction c = (Conjunction) expr;
      DecisionTree<Boolean> head = null;
      for (BooleanExpr be : c.getConjuncts()) {
        if (head == null) {
          head = compute(be, tleaf, fleaf);
        }
        else {
          DecisionTree<Boolean> t = compute(be, tleaf, fleaf);
          head.mergeTrees(t, true);
        }
      }
      return head;
    }

    if (expr instanceof Disjunction) {
      //pCur.debug("Disjunction");
      Disjunction d = (Disjunction) expr;
      Node<Boolean> head = null;
      Node<Boolean> ptr = head;
      for (BooleanExpr be : d.getDisjuncts()) {
        Node<Boolean> r = compute(be, tleaf, fleaf);
        acc = acc.equals("(") ? acc + r : mkOr(acc, r);
      }
      pCur.debug("has changed variable");
      return (acc + ")");
    }
/*
    if (expr instanceof ConjunctionChain) {
      pCur.debug("ConjunctionChain");
      ConjunctionChain d = (ConjunctionChain) expr;
      List<BooleanExpr> conjuncts = new ArrayList<>(d.getSubroutines());
      if (pCur.getDefaultPolicy() != null) {
        BooleanExpr be = new CallExpr(pCur.getDefaultPolicy().getDefaultPolicy());
        conjuncts.add(be);
      }
      if (conjuncts.isEmpty()) {
        return fromExpr("true");
      } else {
        TransferResult<String, String> result = new TransferResult<>();
        String acc = "(false";
        for (int i = conjuncts.size() - 1; i >= 0; i--) {
          BooleanExpr conjunct = conjuncts.get(i);
          TransferParam<Environment> param =
              pCur.setDefaultPolicy(null).setChainContext(TransferParam.ChainContext.CONJUNCTION);
          TransferResult<String, String> r = compute(conjunct, param);
          result = result.addChangedVariables(r);
          acc = mkIf(r.getFallthroughValue(), acc, r.getReturnValue());
        }
        acc = acc + ")";
        pCur.debug("ConjunctionChain Result: " + acc);
        return result.setReturnValue(acc);
      }
    }

    if (expr instanceof DisjunctionChain) {
      pCur.debug("DisjunctionChain");
      DisjunctionChain d = (DisjunctionChain) expr;
      List<BooleanExpr> disjuncts = new ArrayList<>(d.getSubroutines());
      if (pCur.getDefaultPolicy() != null) {
        BooleanExpr be = new CallExpr(pCur.getDefaultPolicy().getDefaultPolicy());
        disjuncts.add(be);
      }
      if (disjuncts.isEmpty()) {
        return fromExpr("true");
      } else {
        TransferResult<String, String> result = new TransferResult<>();
        String acc = "(false";
        for (int i = disjuncts.size() - 1; i >= 0; i--) {
          BooleanExpr disjunct = disjuncts.get(i);
          TransferParam<Environment> param =
              pCur.setDefaultPolicy(null).setChainContext(TransferParam.ChainContext.CONJUNCTION);
          TransferResult<String, String> r = compute(disjunct, param);
          result.addChangedVariables(r);
          acc = mkIf(r.getFallthroughValue(), acc, r.getReturnValue());
        }
        acc = acc + ")";
        pCur.debug("DisjunctionChain Result: " + acc);
        return result.setReturnValue(acc);
      }
    } */

    if (expr instanceof Not) {
      pCur.debug("mkNot");
      Not n = (Not) expr;
      String result = compute(n.getExpr(), pCur);
      return mkNot(result);
    }

    if (expr instanceof MatchProtocol) {
      MatchProtocol mp = (MatchProtocol) expr;
      Protocol proto = Protocol.fromRoutingProtocol(mp.getProtocol());
      if (proto == null) {
        pCur.debug("MatchProtocol(" + mp.getProtocol().protocolName() + "): false");
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
      String protoMatch = "(isProtocol " + pCur.getData().get_protocol() + " " + x + ")";
      pCur.debug("MatchProtocol(" + mp.getProtocol().protocolName() + "): " + protoMatch);
      return protoMatch;
    }

    if (expr instanceof MatchPrefixSet) {
      pCur.debug("MatchPrefixSet");
      MatchPrefixSet m = (MatchPrefixSet) expr;
      // For BGP, may change prefix length
      return matchPrefixSet(_conf, m.getPrefixSet(), pCur.getData());

      // TODO: implement me
    } else if (expr instanceof MatchPrefix6Set) {
      pCur.debug("MatchPrefix6Set");
      return "false";

    }
    else if (expr instanceof CallExpr) {
      pCur.debug("CallExpr");
      // TODO: the call can modify certain fields, need to keep track of these variables
      CallExpr c = (CallExpr) expr;
      String name = c.getCalledPolicyName();
      RoutingPolicy pol = _conf.getRoutingPolicies().get(name);
      pCur = pCur.setCallContext(TransferParam.CallContext.EXPR_CALL);
      pCur.debug("CallExpr " + name + "(stmts): ");
      printStatements(pol.getStatements());
      String r = compute(pol.getStatements(), pCur.indent().enterScope(name), true);
      pCur.debug("CallExpr " + name + " (return): " + r);
//      pCur.debug("CallExpr (fallthrough): " + r.getFallthroughValue());
      return r;

    } else if (expr instanceof WithEnvironmentExpr) {
      pCur.debug("WithEnvironmentExpr");
      // TODO: this is not correct
      WithEnvironmentExpr we = (WithEnvironmentExpr) expr;
      // TODO: postStatements() and preStatements()
      return compute(we.getExpr(), pCur);

    } else if (expr instanceof MatchCommunitySet) {
      pCur.debug("MatchCommunitySet");
      MatchCommunitySet mcs = (MatchCommunitySet) expr;
      return matchCommunitySet(_conf, mcs.getExpr(), pCur.getData());

    } else if (expr instanceof BooleanExprs.StaticBooleanExpr) {
      BooleanExprs.StaticBooleanExpr b = (BooleanExprs.StaticBooleanExpr) expr;
      switch (b.getType()) {
        case CallExprContext:
          pCur.debug("CallExprContext");
          return mkBool(pCur.getCallContext() == TransferParam.CallContext.EXPR_CALL);
        case CallStatementContext:
          pCur.debug("CallStmtContext");
          return mkBool(pCur.getCallContext() == TransferParam.CallContext.STMT_CALL);
        case True:
          pCur.debug("True");
          return "true";
        case False:
          pCur.debug("False");
          return "false";
        default:
          throw new BatfishException(
              "Unhandled " + BooleanExprs.class.getCanonicalName() + ": " + b.getType());
      }
    } else if (expr instanceof MatchAsPath) {
      pCur.debug("MatchAsPath");
      System.out.println("Warning: use of unimplemented feature MatchAsPath");
      return "false";
    }

    String s = (_isExport ? "export" : "import");
    String msg =
        String.format(
            "Unimplemented feature %s for %s transfer function on interface %s",
            expr.toString(), s, _graphEdge.toString());

    throw new BatfishException(msg);
  }

  /*
   * Apply the effect of modifying a long value (e.g., to set the metric)
   */
  private String applyLongExprModification(String x, LongExpr e) {
    if (e instanceof LiteralLong) {
      LiteralLong z = (LiteralLong) e;
      return "" + z.getValue();
    }
    if (e instanceof DecrementMetric) {
      DecrementMetric z = (DecrementMetric) e;
      return "(" + x + " - " + z.getSubtrahend() + ")";
    }
    if (e instanceof IncrementMetric) {
      IncrementMetric z = (IncrementMetric) e;
      return "(" + x + z.getAddend() + ")";
    }
    throw new BatfishException("int expr transfer function: " + e);
  }

  /*
   * Compute how many times to prepend to a path from the AST
   */
  private int prependLength(AsPathListExpr expr) {
    if (expr instanceof MultipliedAs) {
      MultipliedAs x = (MultipliedAs) expr;
      IntExpr e = x.getNumber();
      LiteralInt i = (LiteralInt) e;
      return i.getValue();
    }
    if (expr instanceof LiteralAsList) {
      LiteralAsList x = (LiteralAsList) expr;
      return x.getList().size();
    }
    throw new BatfishException("Error[prependLength]: unreachable");
  }


  private void updateSingleValue(TransferParam<Environment> p, String variableName, String expr) {
    switch (variableName) {
      case "METRIC":
        p.getData().set_cost(expr);
        break;
      case "ADMIN-DIST":
        p.getData().set_ad(expr);
        break;
      case "LOCAL-PREF":
        p.getData().set_lp(expr);
        break;
      case "RETURN":
        break;
      case "COMMUNITIES":
        p.getData().set_communities(expr);
        break;
      default:
        throw new BatfishException("bad");
    }
  }

  private String mkIf(String guard, String trueBranch, String falseBranch) {
    if (guard.equals("true")) {
      return trueBranch;
    }
    if (guard.equals("false")) {
      return falseBranch;
    }
    if (trueBranch.equals("true")
        && (falseBranch.equals("false") || falseBranch.equals("(false)"))) {
      return guard;
    }
    if (trueBranch.equals("false") && falseBranch.equals("true")) {
      return mkNot(guard);
    }
    if (trueBranch.equals(falseBranch)) {
      return trueBranch;
    }
    if (guard.equals(trueBranch)) {
      return mkIf(guard, "true", falseBranch);
    }
    if (guard.equals(falseBranch)) {
      return mkIf(guard, trueBranch, "false");
    }
    if (trueBranch.equals("true")) {
      return mkOr(guard, falseBranch);
    }
    return "(if " + guard + " then\n" + trueBranch + "\nelse\n" + falseBranch + ")";
  }

  private String mkAnd(String x, String y) {
    if (x.equals("true")) {
      return y;
    }
    if (y.equals("true")) {
      return x;
    }
    if (x.equals("false") || y.equals("false") || y.equals("(false)")) {
      return "false";
    }
    return "(" + x + " && " + y + ")";
  }

  private String mkOr(String x, String y) {
    if (x.equals("false")) {
      return y;
    }
    if (y.equals("false")) {
      return x;
    }
    if (x.equals("true") || y.equals("true")) {
      return "true";
    }
    return "(" + x + " || " + y + ")";
  }

  private String mkNot(String x) {
    if (x.equals("true")) {
      return "false";
    }
    if (x.equals("false")) {
      return "true";
    }
    return "(!" + x + ")";
  }

  private String mkBool(boolean b) {
    return "" + b;
  }

  private String mkInt(int i) {
    return "" + i;
  }

  private String mkGe(String x, String y) {
    return "(" + x + " >= " + y + ")";
  }

  private String mkGt(String x, String y) {
    return "(" + x + " > " + y + ")";
  }

  private String mkLe(String x, String y) {
    return "(" + x + " <= " + y + ")";
  }

  private String mkLt(String x, String y) {
    return "(" + x + " < " + y + ")";
  }

  private String mkEq(String x, String y) {
    return "(" + x + " = " + y + ")";
  }

  /*
   * Apply the effect of modifying an integer value (e.g., to set the local pref)
   */
  private String applyIntExprModification(String x, IntExpr e) {
    if (e instanceof LiteralInt) {
      LiteralInt z = (LiteralInt) e;
      return mkInt(z.getValue());
    }
    if (e instanceof IncrementLocalPreference) {
      IncrementLocalPreference z = (IncrementLocalPreference) e;
      return "(" + x + " + " + mkInt(z.getAddend()) + ")";
    }
    if (e instanceof DecrementLocalPreference) {
      DecrementLocalPreference z = (DecrementLocalPreference) e;
      return "(" + x + mkInt(z.getSubtrahend()) + ")";
    }
    throw new BatfishException("TODO: int expr transfer function: " + e);
  }


  private String computeReturn(TransferParam<Environment> p, boolean accept) {
    return (accept ?
        "(Some {bgpAd= "
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
            + ";})" : "None");
  }

  /*
   * Convert a list of statements into an NV expression for the transfer function.
   */
  private String compute(
      List<Statement> stmts,
      TransferParam<Environment> p,
      Boolean boolType) {

    List<Statement> statements = new ArrayList<>(stmts);
    System.out.println("List of statements");
    statements.forEach(stmt -> System.out.println(stmt));
    String result = "";
    if (statements.isEmpty()) {
      return result;
    }
    else {
      Statement stmt = statements.remove(0);
      System.out.println("Executing " + stmt.toString() + " comms: " + p.getData().get_communities() + "\n");

      boolean doesReturn = false;

      if (stmt instanceof StaticStatement) {
        StaticStatement ss = (StaticStatement) stmt;

        switch (ss.getType()) {
          case ExitAccept:
            doesReturn = true;
            p.debug("ExitAccept");

            return boolType ? "true" : computeReturn(p, true);
           // curResult = returnValue(curResult, true);

        // TODO: implement proper unsuppression of routes covered by aggregates
          case Unsuppress:
          case ReturnTrue:
            doesReturn = true;
            p.debug("ReturnTrue");
            return boolType ? "true" : computeReturn(p, true);
           // curResult = returnValue(curResult, true);

          case ExitReject:
            doesReturn = true;
            p.debug("ExitReject");
            return boolType ? "false" : computeReturn(p, false);
           // curResult = returnValue(curResult, false);

            // TODO: implement proper suppression of routes covered by aggregates
          case Suppress:
          case ReturnFalse:
            doesReturn = true;
            p.debug("ReturnFalse");
            return boolType ? "false" : computeReturn(p, false);
           // curResult = returnValue(curResult, false);

          case SetDefaultActionAccept:
            p.debug("SetDefaulActionAccept");
            p = p.setDefaultAccept(true);
            break;

          case SetDefaultActionReject:
            p.debug("SetDefaultActionReject");
            p = p.setDefaultAccept(false);
            break;

          case SetLocalDefaultActionAccept:
            p.debug("SetLocalDefaultActionAccept");
            p = p.setDefaultAcceptLocal(true);
            break;

          case SetLocalDefaultActionReject:
            p.debug("SetLocalDefaultActionReject");
            p = p.setDefaultAcceptLocal(false);
            break;

          case ReturnLocalDefaultAction:
            p.debug("ReturnLocalDefaultAction");
            // TODO: need to set local default action in an environment
            if (p.getDefaultAcceptLocal()) {
              return boolType ? "true" : computeReturn(p, true);
            } else {
              return boolType ? "false" : computeReturn(p, false);
            }

          case FallThrough:
            p.debug("Fallthrough");
        //    curResult = fallthrough(curResult);
            break;

          case Return:
            // TODO: assumming this happens at the end of the function, so it is ignored for now.
            p.debug("Return");
            break;

          case RemovePrivateAs:
            p.debug("RemovePrivateAs");
            System.out.println("Warning: use of unimplemented feature RemovePrivateAs");
            break;

          default:
            throw new BatfishException("TODO: computeTransferFunction: " + ss.getType());
        }

      } else if (stmt instanceof If) {
        p.debug("If");
        If i = (If) stmt;
        String guard = compute(i.getGuard(), p);
        String str = guard;

        p.debug("guard uncompiled: " + i.getGuard().toString());
        p.debug("guard: " + str);
        List<Statement> statementsFall;
        // If we know the branch ahead of time, then specialize
        switch (str) {
          case "true":
            p.debug("True Branch");
            return compute(i.getTrueStatements(), p.indent(), boolType);
          case "false":
            p.debug("False Branch");
            statementsFall = new ArrayList<>(i.getFalseStatements());
            statementsFall.addAll(i.getFalseStatements().size(),statements);
            return compute(statementsFall, p.indent(), boolType);
          default:
            p.debug("True Branch");
            // clear changed variables before proceeding
            Environment env;
            TransferParam<Environment> p1 = p.indent().setData(p.getData().deepCopy());
            TransferParam<Environment> p2 = p.indent().setData(p.getData().deepCopy());

            String trueBranch = compute(i.getTrueStatements(), p1, boolType);
            p.debug("False Branch");
            statementsFall = new ArrayList<>(i.getFalseStatements());
            statementsFall.addAll(i.getFalseStatements().size(),statements);
            statementsFall.forEach(st -> System.out.println(st.toString()));

            String falseBranch = compute(statementsFall, p2, boolType);

            System.out.println("True branch: " + trueBranch);
            System.out.println("False branch: " + falseBranch);

            result = mkIf(guard, trueBranch, falseBranch);
            p.debug("IF RESULT:" + result);
            return result;
        }

      } else if (stmt instanceof SetDefaultPolicy) {
        p.debug("SetDefaultPolicy");
        p = p.setDefaultPolicy((SetDefaultPolicy) stmt);
      } /* else if (stmt instanceof SetMetric) {
        curP.debug("SetMetric");

        SetMetric sm = (SetMetric) stmt;
        LongExpr ie = sm.getMetric();
        String newValue = applyLongExprModification(curP.getData().get_cost(), ie);
        newValue = mkIf(curResult.getReturnAssignedValue(), curP.getData().get_cost(), newValue);
        curP.getData().set_cost(newValue);
        curResult = curResult.addChangedVariable("METRIC", newValue);

      } */
      else if (stmt instanceof AddCommunity) {
        p.debug("AddCommunity");
        AddCommunity ac = (AddCommunity) stmt;
        Set<CommunityVar> comms = collectCommunityVars(_conf, ac.getExpr());

        // set[x := true][y := true]
        String commExpr = p.getData().get_communities();
        String newValue = "";
        for (CommunityVar cvar : comms) {
          newValue = newValue + "[" + communityVarToNvValue(cvar) + ":= true]";
        }
        newValue = commExpr + newValue;
        p.getData().set_communities(newValue);
        //curResult = curResult.addChangedVariable("COMMUNITIES", newValue);

      } else if (stmt instanceof SetCommunity) {
        p.debug("SetCommunity");
        SetCommunity sc = (SetCommunity) stmt;
        Set<CommunityVar> comms = collectCommunityVars(_conf, sc.getExpr());

        // set[x := true][y := true]
        String commExpr = p.getData().get_communities();
        String newValue = "";
        for (CommunityVar cvar : comms) {
          newValue = newValue + "[" + communityVarToNvValue(cvar) + ":= true]";
        }
//        System.out.println("RESULT:" + curResult.getReturnAssignedValue());
        newValue = commExpr + newValue;
        p.getData().set_communities(newValue);
       // curResult = curResult.addChangedVariable("COMMUNITIES", newValue);

      }
      /* else if (stmt instanceof DeleteCommunity) {
        curP.debug("DeleteCommunity");
        DeleteCommunity ac = (DeleteCommunity) stmt;
        Set<CommunityVar> comms = collectCommunityVars(_conf, ac.getExpr());

        // set[x := true][y := true]
        String commExpr = curP.getData().get_communities();
        String newValue = "";
        for (CommunityVar cvar : comms) {
          newValue = newValue + "[" + communityVarToNvValue(cvar) + ":= false]";
        }
        newValue = mkIf(curResult.getReturnAssignedValue(), commExpr, commExpr + newValue);
        curP.getData().set_communities(newValue);
        curResult = curResult.addChangedVariable("COMMUNITIES", newValue);

      } else if (stmt instanceof RetainCommunity) {
        curP.debug("RetainCommunity");
        // no op

      } */
      else if (stmt instanceof SetLocalPreference) {
        p.debug("SetLocalPreference");
        SetLocalPreference slp = (SetLocalPreference) stmt;
        IntExpr ie = slp.getLocalPreference();
        String newValue = applyIntExprModification(p.getData().get_lp(), ie);
        p.debug("Value after modification: " + newValue);
        p.getData().set_lp(newValue);
        //curResult = curResult.addChangedVariable("LOCAL-PREF", newValue);

      } /*else if (stmt instanceof PrependAsPath) {
        curP.debug("PrependAsPath");
        PrependAsPath pap = (PrependAsPath) stmt;
        Integer prependCost = prependLength(pap.getExpr());
        String newValue = curP.getData().get_cost() + " + " + prependCost;
        newValue = mkIf(curResult.getReturnAssignedValue(), curP.getData().get_cost(), newValue);
        curP.getData().set_cost(newValue);
        curResult = curResult.addChangedVariable("METRIC", newValue);

      } else if (stmt instanceof SetOrigin) {
        curP.debug("SetOrigin");
        System.out.println("Warning: use of unimplemented feature SetOrigin");

      } else if (stmt instanceof SetNextHop) {
        curP.debug("SetNextHop");
        System.out.println("Warning: use of unimplemented feature SetNextHop");

      } */ else {

        String s = (_isExport ? "export" : "import");
        String msg =
            String.format(
                "Unimplemented feature %s for %s transfer function on interface %s",
                stmt.toString(), s, _graphEdge.toString());

        throw new BatfishException(msg);
      }
      return compute(statements,p,boolType);
    }
    }


    private void printStatements (List<Statement> stmts) {
      stmts.forEach(stmt -> {System.out.println("Command\n-------------------");System.out.println(stmt);
      if (stmt instanceof If) {
        BooleanExpr g = ((If) stmt).getGuard();
        System.out.println("guard"); System.out.println(g);
        System.out.println("True Branch:");printStatements(((If) stmt).getTrueStatements());
      System.out.println("False Branch");printStatements(((If) stmt).getFalseStatements());} });
    }

  public String compute() {
    Environment env = new Environment();
    TransferParam<Environment> p = new TransferParam<>(env, true);

    String result = compute(_statements, p, false);
    return result;
  }
}
