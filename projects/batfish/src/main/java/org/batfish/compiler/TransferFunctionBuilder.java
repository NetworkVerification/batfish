package org.batfish.compiler;

import static org.batfish.symbolic.CommunityVarCollector.collectCommunityVars;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Set;
import javax.annotation.Nullable;
import org.batfish.common.BatfishException;
import org.batfish.compiler.TransferParam.CallContext;
import static org.batfish.compiler.NVFunctions.*;
import org.batfish.datamodel.Configuration;
import org.batfish.datamodel.routing_policy.RoutingPolicy;
import org.batfish.datamodel.routing_policy.expr.AsPathListExpr;
import org.batfish.datamodel.routing_policy.expr.BooleanExpr;
import org.batfish.datamodel.routing_policy.expr.BooleanExprs;
import org.batfish.datamodel.routing_policy.expr.CallExpr;
import org.batfish.datamodel.routing_policy.expr.Conjunction;
import org.batfish.datamodel.routing_policy.expr.DecrementLocalPreference;
import org.batfish.datamodel.routing_policy.expr.DecrementMetric;
import org.batfish.datamodel.routing_policy.expr.Disjunction;
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
import org.batfish.datamodel.routing_policy.expr.Not;
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
import org.batfish.symbolic.utils.Tuple;

class TransferFunctionBuilder {

  private Configuration _conf;

  private List<Statement> _statements;

  private GraphEdge _graphEdge;

  private boolean _isExport;

  TransferFunctionBuilder(
      Configuration conf, List<Statement> statements, GraphEdge ge, boolean isExport) {
    _conf = conf;
    _statements = statements;
    _graphEdge = ge;
    _isExport = isExport;
  }

  private DecisionTree<Boolean> trivial(BooleanExpr expr, TransferParam<Environment> env,
      Node<Boolean> t1, Node<Boolean> t2) {

    Node<Boolean> p = new Node<>(expr, env, _isExport);
    Set<Node<Boolean>> leaves = new HashSet<>();
    leaves.add(t1);
    leaves.add(t2);
    DecisionTree<Boolean> t = new DecisionTree<>(p,leaves);
    p.setChild(t1,true);
    p.setChild(t2, false);
    return t;
  }

  /*
   * Convert a Batfish AST boolean expression to a decision tree with boolean leafs.
   */
  @Nullable
  private DecisionTree<Boolean> exprToTree(BooleanExpr expr,
      TransferParam<Environment> pCur, Node<Boolean> tleaf, Node<Boolean> fleaf) {
    // TODO: right now everything is IPV4
    if (expr instanceof MatchIpv4) {
      pCur.debug("MatchIpv4");
      return new DecisionTree<>(tleaf);
    }
    if (expr instanceof MatchIpv6) {
      pCur.debug("MatchIpv6");
      return new DecisionTree<>(fleaf);
    }

    if (expr instanceof Conjunction) {
      pCur.debug("Conjunction");
      Conjunction c = (Conjunction) expr;
      DecisionTree<Boolean> head = null;
      for (BooleanExpr be : c.getConjuncts()) {
        if (head == null) {
          System.out.println("\nFirst conjunct " + be.toString());
          head = exprToTree(be, pCur, tleaf, fleaf);
        }
        else {
          System.out.println("Additional conjunct: ");
          head.printTree();
          System.out.println("\nConjuncting " + be.toString());
          System.out.println("TLEAF before: " + tleaf + "," + tleaf.getEnv().getData().get_communities());
          head = continuationExpr(head, pCur, tleaf, fleaf, be, true);
          System.out.println("Head after conjunction: ");
          head.printTree();
        }
      }
      System.out.println("END conjunction: ");
      return head;
    }

    if (expr instanceof Disjunction) {
      pCur.debug("Disjunction");
      Disjunction d = (Disjunction) expr;
      DecisionTree<Boolean> head = null;
      for (BooleanExpr be : d.getDisjuncts()) {
        if (head == null) {
          head = exprToTree(be, pCur, tleaf, fleaf);
        }
        else {
          //DecisionTree<Boolean> t = exprToTree(be, pCur, tleaf, fleaf);
          System.out.println("Head at disjunction: ");
          head.printTree();
          head = continuationExpr(head, pCur, tleaf, fleaf, be, false);
          System.out.println("Head after disjunction: ");
          head.printTree();
        }
      }
      return head;
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
      // Swap the true and false leafs.
      Not n = (Not) expr;
      return exprToTree(n.getExpr(), pCur, fleaf, tleaf);
    }

    if (expr instanceof MatchProtocol) {
      MatchProtocol mp = (MatchProtocol) expr;
      Protocol proto = Protocol.fromRoutingProtocol(mp.getProtocol());
      if (proto == null) {
        pCur.debug("MatchProtocol(" + mp.getProtocol().protocolName() + "): false");
        return (new DecisionTree<>(fleaf));
      }

      pCur.debug("MatchProtocol(" + mp.getProtocol().protocolName() + "): ");
      return trivial(expr, pCur, tleaf, fleaf);
      //String protoMatch = "(isProtocol " + pCur.getData().get_protocol() + " " + x + ")";

    }

    if (expr instanceof MatchPrefixSet) {
      pCur.debug("MatchPrefixSet");
      return trivial(expr, pCur, tleaf, fleaf);
    } else if (expr instanceof MatchPrefix6Set) {
      return (new DecisionTree<>(fleaf));

    }
    else if (expr instanceof CallExpr) {
      pCur.debug("CallExpr");
      CallExpr c = (CallExpr) expr;
      String name = c.getCalledPolicyName();
      RoutingPolicy pol = _conf.getRoutingPolicies().get(name);

      // When mutating the environment, we need to make a copy,
      // because we don't want to mutate it for the previous expressions.
      TransferParam<Environment> pNew = pCur.deepCopy();
      pNew = pNew.setCallContext(TransferParam.CallContext.EXPR_CALL);
      pNew.debug("CallExpr " + name + "(stmts): ");

      Node<Boolean> tleafFresh = new Node<>(tleaf, pNew);
      Node<Boolean> fleafFresh = new Node<>(fleaf, pNew);

      return (statementToBool(pol.getStatements(), pNew, tleafFresh, fleafFresh));
      //pCur.debug("CallExpr " + name + " (return): " + r);
      //      pCur.debug("CallExpr (fallthrough): " + r.getFallthroughValue());

    } else if (expr instanceof WithEnvironmentExpr) {
      //pCur.debug("WithEnvironmentExpr");
      // TODO: this is not correct
      WithEnvironmentExpr we = (WithEnvironmentExpr) expr;
      // TODO: postStatements() and preStatements()
      return exprToTree(we.getExpr(), pCur, tleaf, fleaf);

    } else if (expr instanceof MatchCommunitySet) {
      //pCur.debug("MatchCommunitySet");
      return trivial(expr, pCur, tleaf, fleaf);

    } else if (expr instanceof BooleanExprs.StaticBooleanExpr) {
      BooleanExprs.StaticBooleanExpr b = (BooleanExprs.StaticBooleanExpr) expr;
      //TODO: CallContext
      switch (b.getType()) {
      case CallExprContext:
        pCur.debug("CallExprContext");
        if (pCur.getCallContext() == CallContext.EXPR_CALL) {
          return (new DecisionTree<>(tleaf));
        }
        else {
          return (new DecisionTree<>(fleaf));
        }
      case CallStatementContext:
        pCur.debug("CallStmtContext");
        if (pCur.getCallContext() == CallContext.STMT_CALL) {
          return (new DecisionTree<>(tleaf));
        }
        else {
          return (new DecisionTree<>(fleaf));
        }
      case True:
        return new DecisionTree<>(tleaf);
      case False:
        return new DecisionTree<>(fleaf);
      default:
        throw new BatfishException(
            "Unhandled " + BooleanExprs.class.getCanonicalName() + ": " + b.getType());
      }
    } else if (expr instanceof MatchAsPath) {
      //pCur.debug("MatchAsPath");
      System.out.println("Warning: use of unimplemented feature MatchAsPath");
      return new DecisionTree<>(fleaf);
    }

    String s = (_isExport ? "export" : "import");
    String msg =
        String.format(
            "Unimplemented feature %s for %s transfer function on interface %s",
            expr.toString(), s, _graphEdge.toString());

    throw new BatfishException(msg);
  }

  /* The invariant here is that p is the environment in tleaf and fleaf. */
  private DecisionTree<Boolean> statementToBool(List<Statement> stmts,
      TransferParam<Environment> p,
      Node<Boolean> tleaf,
      Node<Boolean> fleaf) {

    List<Statement> statements = new ArrayList<>(stmts);

    if (statements.isEmpty()) {
      return null;
    }
    else {
      Statement stmt = statements.remove(0);
      System.out.println("Executing " + stmt.toString() + "\n");

      if (stmt instanceof StaticStatement) {
        StaticStatement ss = (StaticStatement) stmt;

        switch (ss.getType()) {
        case ExitAccept:
          p.debug("ExitAccept");

          return new DecisionTree<>(tleaf);
        // TODO: implement proper unsuppression of routes covered by aggregates
        case Unsuppress:
        case ReturnTrue:
          p.debug("ReturnTrue");
          return new DecisionTree<>(tleaf);

        case ExitReject:
          p.debug("ExitReject");
          return new DecisionTree<>(fleaf);

        // TODO: implement proper suppression of routes covered by aggregates
        case Suppress:
        case ReturnFalse:
          p.debug("ReturnFalse");
          return new DecisionTree<>(fleaf);

        case SetDefaultActionAccept:
          p.debug("SetDefaulActionAccept");
          p = p.setDefaultAccept(true);
          // No need for a deepcopy here, because setDefaultAccept makes a copy (not of the env though).

          tleaf.setEnv(p);
          fleaf.setEnv(p);

          break;

        case SetDefaultActionReject:
          p.debug("SetDefaultActionReject");
          p.setDefaultAccept(false);

          tleaf.setEnv(p);
          fleaf.setEnv(p);

          break;

        case SetLocalDefaultActionAccept:
          p.debug("SetLocalDefaultActionAccept");
          p.setDefaultAcceptLocal(true);

          tleaf.setEnv(p);
          fleaf.setEnv(p);
          break;

        case SetLocalDefaultActionReject:
          p.debug("SetLocalDefaultActionReject");
          p = p.setDefaultAcceptLocal(false);

          tleaf.setEnv(p);
          fleaf.setEnv(p);
          break;

        case ReturnLocalDefaultAction:
          p.debug("ReturnLocalDefaultAction");
          if (p.getDefaultAcceptLocal()) {
            return new DecisionTree<>(tleaf);
          } else {
            return new DecisionTree<>(fleaf);
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

        // Create new false/true leafs to capture nesting of ifs.
        Node<Boolean> tleafFresh = new Node<>(true, p, _isExport);
        Node<Boolean> fleafFresh = new Node<>(false, p, _isExport);
        DecisionTree<Boolean> guard =
            exprToTree(i.getGuard(), p, tleafFresh, fleafFresh);

        p.debug("guard uncompiled: " + i.getGuard().toString());
        guard.printTree();
        List<Statement> statementsFall;

        statementsFall = new ArrayList<>(i.getFalseStatements());
        statementsFall.addAll(i.getFalseStatements().size(), statements);
        List<Tuple<DecisionTree<Boolean>, Node<Boolean>>> todo = new ArrayList<>();
        for (Node<Boolean> leaf : guard.getLeafs()) {
          Boolean branch = leaf.getData();
          //Make fresh environment for this branch
          TransferParam<Environment> newEnv = leaf.getEnv();
          Environment newData = newEnv.getData().deepCopy();
          newEnv = newEnv.indent().setData(newData);
          Node<Boolean> newTleaf = new Node<>(true, newEnv, _isExport);
          Node<Boolean> newFleaf = new Node<>(false, newEnv, _isExport);
          List<Statement> nextStatements = branch ? i.getTrueStatements() : statementsFall;
          DecisionTree<Boolean> exprTree =
              statementToBool(nextStatements, newEnv, newTleaf, newFleaf);

          // Gather the trees+leafs to merge, do it outside the loop not to mess up things.
          todo.add(new Tuple<>(exprTree, leaf));
        }

        // Merge the trees at the leaf points.
        for (Tuple<DecisionTree<Boolean>, Node<Boolean>> t : todo) {
          guard.mergeAtLeaf(t.getFirst(), t.getSecond());
        }

        System.out.println("Final computed boolIf: ");
        guard.printTree();

        return guard;

      } else if (stmt instanceof SetDefaultPolicy) {
        p.debug("SetDefaultPolicy");
        p = p.setDefaultPolicy((SetDefaultPolicy) stmt);

        tleaf.setEnv(p);
        fleaf.setEnv(p);

      } else if (stmt instanceof SetMetric) {
        p.debug("SetMetric");

        SetMetric sm = (SetMetric) stmt;
        LongExpr ie = sm.getMetric();
        String newValue = applyLongExprModification(p.getData().get_cost(), ie);
        p.getData().set_cost(newValue);
      }
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
        newValue = commExpr + newValue;
        p.getData().set_communities(newValue);

      }
      else if (stmt instanceof DeleteCommunity) {
        p.debug("DeleteCommunity");

        DeleteCommunity ac = (DeleteCommunity) stmt;
        Set<CommunityVar> comms = collectCommunityVars(_conf, ac.getExpr());

        // set[x := true][y := true]
        String commExpr = p.getData().get_communities();
        String newValue = "";
        for (CommunityVar cvar : comms) {
          newValue = newValue + "[" + communityVarToNvValue(cvar) + ":= false]";
        }
        p.getData().set_communities(commExpr + newValue);
      } else if (stmt instanceof RetainCommunity) {
        p.debug("RetainCommunity");
        // no op

      }
      else if (stmt instanceof SetLocalPreference) {
        p.debug("SetLocalPreference");

        SetLocalPreference slp = (SetLocalPreference) stmt;
        IntExpr ie = slp.getLocalPreference();
        String newValue = applyIntExprModification(p.getData().get_lp(), ie);
        p.debug("Value after modification: " + newValue);
        p.getData().set_lp(newValue);

      } else if (stmt instanceof PrependAsPath) {
        p.debug("PrependAsPath");

        PrependAsPath pap = (PrependAsPath) stmt;
        Integer prependCost = prependLength(pap.getExpr());
        String newValue = p.getData().get_cost() + " + " + prependCost;
        p.getData().set_cost(newValue);

      } else if (stmt instanceof SetOrigin) {
        p.debug("SetOrigin");
        System.out.println("Warning: use of unimplemented feature SetOrigin");

      } else if (stmt instanceof SetNextHop) {
        p.debug("SetNextHop");
        System.out.println("Warning: use of unimplemented feature SetNextHop");

      } else {

        String s = (_isExport ? "export" : "import");
        String msg =
            String.format(
                "Unimplemented feature %s for %s transfer function on interface %s",
                stmt.toString(), s, _graphEdge.toString());

        throw new BatfishException(msg);
      }
      return statementToBool(statements,p, tleaf, fleaf);
    }
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

  private DecisionTree<Boolean> computeReturn(Node<Boolean> p, boolean accept) {
    if (accept) {
      p.getEnv().getData().set_valid(true);
    }
    else {
      p.getEnv().getData().set_valid(false);
    }
    return (new DecisionTree<>(p));
  }

  /*
   * Convert a list of statements into an NV expression for the transfer function.
   */
  private DecisionTree<Boolean> compute(
      List<Statement> stmts,
      Node<Boolean> nodeP) {

    List<Statement> statements = new ArrayList<>(stmts);
    //System.out.println("List of statements");
    //statements.forEach(stmt -> System.out.println(stmt));
    if (statements.isEmpty()) {
      return null;
    }
    else {
      TransferParam<Environment> p = nodeP.getEnv();
      Statement stmt = statements.remove(0);
      System.out.println("Executing " + stmt.toString() + "\n");

      if (stmt instanceof StaticStatement) {
        StaticStatement ss = (StaticStatement) stmt;

        switch (ss.getType()) {
        case ExitAccept:
          p.debug("ExitAccept");
          return computeReturn(nodeP, true);

        // TODO: implement proper unsuppression of routes covered by aggregates
        case Unsuppress:
        case ReturnTrue:
          p.debug("ReturnTrue");
          return computeReturn(nodeP, true);

        case ExitReject:
          p.debug("ExitReject");
          return computeReturn(nodeP, false);

        // TODO: implement proper suppression of routes covered by aggregates
        case Suppress:
        case ReturnFalse:
          p.debug("ReturnFalse");
          return computeReturn(nodeP, false);

        case SetDefaultActionAccept:
          p.debug("SetDefaulActionAccept");
          p = p.setDefaultAccept(true);
          nodeP.setEnv(p);

          break;

        case SetDefaultActionReject:
          p.debug("SetDefaultActionReject");
          p = p.setDefaultAccept(false);
          nodeP.setEnv(p);

          break;

        case SetLocalDefaultActionAccept:
          p.debug("SetLocalDefaultActionAccept");
          p = p.setDefaultAcceptLocal(true);
          nodeP.setEnv(p);

          break;

        case SetLocalDefaultActionReject:
          p.debug("SetLocalDefaultActionReject");
          p = p.setDefaultAcceptLocal(false);
          nodeP.setEnv(p);

          break;

        case ReturnLocalDefaultAction:
          p.debug("ReturnLocalDefaultAction");
          // TODO: need to set local default action in an environment
          if (p.getDefaultAcceptLocal()) {
            return computeReturn(nodeP, true);
          } else {
            return computeReturn(nodeP, false);
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

        // Create false/true leafs.
        Node<Boolean> tleafFresh = new Node<>(true, p, _isExport);
        Node<Boolean> fleafFresh = new Node<>(false, p, _isExport);

        p.debug("Now computing the guard: ");
        DecisionTree<Boolean> guard =
            exprToTree(i.getGuard(), p, tleafFresh, fleafFresh);

        List<Statement> statementsFall;

        // The else branch is merged with the leftovers statements.
        // TODO: this may not be always right..
        statementsFall = new ArrayList<>(i.getFalseStatements());
        statementsFall.addAll(i.getFalseStatements().size(), statements);

        // keep the trees to merge in a separate list to do it after they have been computed
        // to avoid changing the leafs during the loop over the leafs.
        List<Tuple<DecisionTree<Boolean>, Node<Boolean>>> todo = new ArrayList<>();

        System.out.println("Printing the if-guard at stateful if");
        guard.printTree();
        for (Node<Boolean> leaf : guard.getLeafs()) {
          Boolean branch = leaf.getData();
          //Make fresh environment for this branch
          TransferParam<Environment> newEnv = leaf.getEnv();
          Environment newData = newEnv.getData().deepCopy();
          newEnv = newEnv.indent().setData(newData);
          Node<Boolean> newNodeP = new Node<>(true, newEnv, _isExport); //boolean value doesn't matter
          List<Statement> nextStatements = branch ? i.getTrueStatements() : statementsFall;
          DecisionTree<Boolean> stmtTree = compute(nextStatements, newNodeP);

          // Gather the trees+leafs to merge, do it outside the loop not to mess up things.
          todo.add(new Tuple<>(stmtTree, leaf));
        }

        // Merge the trees at the leaf points.
        for (Tuple<DecisionTree<Boolean>, Node<Boolean>> t : todo) {
          guard.mergeAtLeaf(t.getFirst(), t.getSecond());
        }
        return guard;

      } else if (stmt instanceof SetDefaultPolicy) {
        p.debug("SetDefaultPolicy");
        p = p.setDefaultPolicy((SetDefaultPolicy) stmt);

        nodeP.setEnv(p);

      } else if (stmt instanceof SetMetric) {
        p.debug("SetMetric");

        SetMetric sm = (SetMetric) stmt;
        LongExpr ie = sm.getMetric();
        String newValue = applyLongExprModification(p.getData().get_cost(), ie);
        p.getData().set_cost(newValue);
      }
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
        newValue = commExpr + newValue;
        p.getData().set_communities(newValue);

      }
      else if (stmt instanceof DeleteCommunity) {
        p.debug("DeleteCommunity");
        DeleteCommunity ac = (DeleteCommunity) stmt;
        Set<CommunityVar> comms = collectCommunityVars(_conf, ac.getExpr());

        // set[x := true][y := true]
        String commExpr = p.getData().get_communities();
        String newValue = "";
        for (CommunityVar cvar : comms) {
          newValue = newValue + "[" + communityVarToNvValue(cvar) + ":= false]";
        }
        p.getData().set_communities(commExpr + newValue);
      } else if (stmt instanceof RetainCommunity) {
        p.debug("RetainCommunity");
        // no op

      }
      else if (stmt instanceof SetLocalPreference) {
        p.debug("SetLocalPreference");
        SetLocalPreference slp = (SetLocalPreference) stmt;
        IntExpr ie = slp.getLocalPreference();
        String newValue = applyIntExprModification(p.getData().get_lp(), ie);
        p.debug("Value after modification: " + newValue);
        p.getData().set_lp(newValue);
        //curResult = curResult.addChangedVariable("LOCAL-PREF", newValue);

      } else if (stmt instanceof PrependAsPath) {
        p.debug("PrependAsPath");
        PrependAsPath pap = (PrependAsPath) stmt;
        Integer prependCost = prependLength(pap.getExpr());
        String newValue = p.getData().get_cost() + " + " + prependCost;
        p.getData().set_cost(newValue);

      } else if (stmt instanceof SetOrigin) {
        p.debug("SetOrigin");
        System.out.println("Warning: use of unimplemented feature SetOrigin");

      } else if (stmt instanceof SetNextHop) {
        p.debug("SetNextHop");
        System.out.println("Warning: use of unimplemented feature SetNextHop");

      } else {

        String s = (_isExport ? "export" : "import");
        String msg =
            String.format(
                "Unimplemented feature %s for %s transfer function on interface %s",
                stmt.toString(), s, _graphEdge.toString());

        throw new BatfishException(msg);
      }
      return compute(statements,nodeP);
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

  public DecisionTree<Boolean> compute() {
    Environment env = new Environment();
    TransferParam<Environment> p = new TransferParam<>(env, true);
    Node<Boolean> nodeP = new Node<>(true, p, _isExport);
    return compute(_statements, nodeP);
  }

  private DecisionTree<Boolean> continuationExpr(DecisionTree<Boolean> t, TransferParam<Environment> pCur,
      Node<Boolean> tleaf, Node<Boolean> fleaf,
      BooleanExpr be, boolean lr) {
    List<Tuple<DecisionTree<Boolean>, Node<Boolean>>> todo = new ArrayList<>();

    for (Node<Boolean> leaf : t.getLeafs()) {
      if (leaf.getData() == lr) {
        TransferParam<Environment> newEnv = leaf.getEnv();
        Node<Boolean> newTleaf = tleaf;
        Node<Boolean> newFleaf = fleaf;
        if (!newEnv.equals(pCur)) {
           newTleaf = new Node<>(true, newEnv, _isExport);
           newFleaf = new Node<>(false, newEnv, _isExport);
          newEnv = newEnv.deepCopy();
        }

        DecisionTree<Boolean> res = exprToTree(be, newEnv, newTleaf, newFleaf);
        todo.add(new Tuple<>(res, leaf));
      }
    }

    // Merge the trees at the leaf points.
    for (Tuple<DecisionTree<Boolean>, Node<Boolean>> elt : todo) {
      t.mergeAtLeaf(elt.getFirst(), elt.getSecond());
    }
    return t;
  }

  /* Applies it on top of an existing tree (on its leafs) */
  public DecisionTree<Boolean> compute(DecisionTree<Boolean> t) {
    List<Tuple<DecisionTree<Boolean>, Node<Boolean>>> todo = new ArrayList<>();
    for (Node<Boolean> leaf : t.getLeafs()) {
      TransferParam<Environment> newEnv = leaf.getEnv();
      Environment newData = newEnv.getData().deepCopy();
      newEnv = newEnv.indent().setData(newData);

      Node<Boolean> newLeaf = new Node<>(true, newEnv, _isExport);
      DecisionTree<Boolean> res = compute(_statements, newLeaf);
      todo.add(new Tuple<>(res, leaf));
    }

    // Merge the trees at the leaf points.
    for (Tuple<DecisionTree<Boolean>, Node<Boolean>> elt : todo) {
      t.mergeAtLeaf(elt.getFirst(), elt.getSecond());
    }
    return t;
  }

  private void swap(Node<Boolean> pre, DecisionTree<Boolean> t) {
    if (pre.getParents().size() > 1)
    {
      throw new BatfishException("Should have a single parent");
    }
    Tuple<Node<Boolean>, Boolean> parent = pre.getParents().get(0);
    Node<Boolean> v1 = parent.getFirst();
    Boolean lr = parent.getSecond();

    // Keep pointers to children of pre and v.
    Node<Boolean> pl = pre.getLeft();
    Node<Boolean> pr = pre.getRight();
    Node<Boolean> vl = v1.getLeft();
    Node<Boolean> vr = v1.getRight();

    //Update the parents of pre to those of v if any.
    if (v1.getParents() != null) {
      List<Tuple<Node<Boolean>, Boolean>> vparents = new LinkedList<>();
      vparents.addAll(v1.getParents());
      for (Tuple<Node<Boolean>, Boolean> vparent : vparents) {
        vparent.getFirst().setChild(pre, vparent.getSecond());
      }
    }
    else {
      pre.setParents(null);
      t.setRoot(pre);
    }

    // Make a copy of v1, v1 will be the left child of pre, v2 the right.
    Node<Boolean> v2 = new Node<>(v1.getExpr(), v1.getEnv(), v1.getExport());
    v1.setParents(null);
    v2.setParents(null);
    v1.setLeft(null);
    v1.setRight(null);
    v2.setLeft(null);
    v2.setRight(null);
    pre.setChild(v1, false);
    pre.setChild(v2, true);

    v1.setChild(lr ? vl : pl, false);
    v1.setChild(lr ? pl : vr, true);
    v2.setChild(lr ? vl : pr, false);
    v2.setChild(lr ? pr : vr, true);
  }

  @Nullable
  private Node<Boolean> findCandidate(DecisionTree<Boolean> t) {
    Set<Node<Boolean>> preNodes = new HashSet<>();
    preNodes.addAll(t.getPrefixNodes());
    Iterator<Node<Boolean>> iter = preNodes.iterator();
    while (iter.hasNext()) {
      Node<Boolean> pre = iter.next();
      List<Tuple<Node<Boolean>, Boolean>> parents = pre.getParents();
      int sz = (parents != null) ? parents.size() : 0; // if it's zero it's root so that's ok.
      if (sz == 1 && !t.getPrefixNodes().contains(parents.get(0))) {
        return pre;
      }
      else if (sz > 1) {
        // For every parent
        boolean hasV = false;
        for (Tuple<Node<Boolean>, Boolean> parent : parents) {
          // Check that it is also a prefix node
          if (!preNodes.contains(parent.getFirst())) {
            hasV = true;
            break;
          }
        }

        if (hasV) {
          for (int i = 1; i < sz; i++) {
            // Make a copy of it
            Node<Boolean> preCopy = new Node<>(pre.getExpr(), pre.getEnv(), pre.getExport());
            preCopy.setParents(null);
            Tuple<Node<Boolean>, Boolean> parent = parents.get(i);
            parent.getFirst().setChild(preCopy, parent.getSecond());

            //add the children of pre as children of precopy.
            preCopy.setChild(pre.getLeft(), false);
            preCopy.setChild(pre.getRight(), true);

            // Add this node to the list of prefix nodes and to all nodes.
            t.getPrefixNodes().add(preCopy);
            t.getAllNodes().add(preCopy);
          }
          return pre;
        }
      }
    }
    return null;
  }

  public void normalize(DecisionTree<Boolean> t) {
    Node<Boolean> pre = findCandidate(t);
    while (pre != null) {
      swap(pre, t);
      pre = findCandidate(t);
    }
  }
}
