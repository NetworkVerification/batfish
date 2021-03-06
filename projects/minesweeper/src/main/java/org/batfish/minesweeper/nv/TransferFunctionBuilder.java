package org.batfish.minesweeper.nv;


import static org.batfish.minesweeper.CommunityVarCollector.collectCommunityVars;
import static org.batfish.minesweeper.nv.NVLang.communityVarToNvValue;
import static org.batfish.minesweeper.nv.NVLang.mkInt;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Set;
import javax.annotation.Nullable;
import org.batfish.common.BatfishException;
import org.batfish.datamodel.Configuration;
import org.batfish.datamodel.RoutingProtocol;
import org.batfish.datamodel.routing_policy.RoutingPolicy;
import org.batfish.datamodel.routing_policy.expr.AsPathListExpr;
import org.batfish.datamodel.routing_policy.expr.BooleanExpr;
import org.batfish.datamodel.routing_policy.expr.BooleanExprs;
import org.batfish.datamodel.routing_policy.expr.CallExpr;
import org.batfish.datamodel.routing_policy.expr.Conjunction;
import org.batfish.datamodel.routing_policy.expr.ConjunctionChain;
import org.batfish.datamodel.routing_policy.expr.DecrementLocalPreference;
import org.batfish.datamodel.routing_policy.expr.DecrementMetric;
import org.batfish.datamodel.routing_policy.expr.Disjunction;
import org.batfish.datamodel.routing_policy.expr.FirstMatchChain;
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
import org.batfish.minesweeper.CommunityVar;
import org.batfish.minesweeper.GraphEdge;
import org.batfish.minesweeper.Protocol;
import org.batfish.minesweeper.TransferParam;
import org.batfish.minesweeper.TransferParam.CallContext;
import org.batfish.minesweeper.utils.Tuple;

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
          head = exprToTree(be, pCur, tleaf, fleaf);
        }
        else {
          head = continuationExpr(head, pCur, tleaf, fleaf, be, true);
        }
      }
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
          head = continuationExpr(head, pCur, tleaf, fleaf, be, false);
        }
      }
      return head;
    }

   /* if (expr instanceof ConjunctionChain) {
      pCur.debug("ConjunctionChain");
      ConjunctionChain d = (ConjunctionChain) expr;
      List<BooleanExpr> conjuncts = new ArrayList<>(d.getSubroutines());
      if (pCur.getDefaultPolicy() != null) {
        BooleanExpr be = new CallExpr(pCur.getDefaultPolicy().getDefaultPolicy());
        conjuncts.add(be);
      }
      if (conjuncts.isEmpty()) {
        return new DecisionTree<>(tleaf);
      } else {
 //       TransferResult<String, String> result = new TransferResult<>();
        DecisionTree<Boolean> head = exprToTree(conjuncts.get(conjuncts.size() -1), pCur, tleaf, fleaf);
        for (int i = conjuncts.size() - 2; i >= 0; i--) {
          BooleanExpr conjunct = conjuncts.get(i);
          TransferParam<Environment> param =
              pCur.setDefaultPolicy(null).setChainContext(TransferParam.ChainContext.CONJUNCTION);
          DecisionTree<Boolean> r = continuationExpr(head, param, tleaf, fleaf, conjunct, true);

          acc = mkIf(r.getFallthroughValue(), acc, r.getReturnValue());
        }
        acc = acc + ")";
        pCur.debug("ConjunctionChain Result: " + acc);
        return result.setReturnValue(acc);
      }
    }*/
/*
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

    if (expr instanceof FirstMatchChain) {
      pCur.debug("FirstMatchChain UNIMPLEMENTED");
      return (new DecisionTree<>(fleaf));
    }

    if (expr instanceof Not) {
      pCur.debug("mkNot");
      // Swap the true and false leafs.
      Not n = (Not) expr;
      return exprToTree(n.getExpr(), pCur, fleaf, tleaf);
    }

    if (expr instanceof MatchProtocol) {
      MatchProtocol mp = (MatchProtocol) expr;
      Iterator<RoutingProtocol> protos = mp.getProtocols().iterator();

      if (!protos.hasNext()) {
        pCur.debug("MatchProtocol: false");
        return (new DecisionTree<>(fleaf));
      }

      pCur.debug("MatchProtocol ");
      return trivial(expr, pCur, tleaf, fleaf);
    }

    if (expr instanceof MatchPrefixSet) {
      pCur.debug("MatchPrefixSet");
      if (expr == null) {
        pCur.debug("expr is empty");
      }
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

        p.debug("guard uncompiled: " + i.getGuard().toString());
        // Create new false/true leafs to capture nesting of ifs.
        Node<Boolean> tleafFresh = new Node<>(true, p, _isExport);
        Node<Boolean> fleafFresh = new Node<>(false, p, _isExport);
        DecisionTree<Boolean> guard =
            exprToTree(i.getGuard(), p, tleafFresh, fleafFresh);

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
        LongExpr ie = slp.getLocalPreference();
        String newValue = applyLongExprModification(p.getData().get_lp(), ie);
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
    if (e instanceof IncrementLocalPreference) {
      IncrementLocalPreference z = (IncrementLocalPreference) e;
      return "(" + x + " + " + z.getAddend() + ")";
    }
    if (e instanceof DecrementLocalPreference) {
      DecrementLocalPreference z = (DecrementLocalPreference) e;
      return "(" + x + " - " + z.getSubtrahend() + ")";
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
      return computeReturn(nodeP, true);
    }
    else {
      TransferParam<Environment> p = nodeP.getEnv();
      Statement stmt = statements.remove(0);

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
        LongExpr ie = slp.getLocalPreference();
        String newValue = applyLongExprModification(p.getData().get_lp(), ie);
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

  private void applyMetricUpdate(DecisionTree<Boolean> t) {
    if (_isExport) {
      for (Node<Boolean> leaf : t.getLeafs()) {
        Environment env = leaf.getEnv().getData();
        env.set_cost(env.get_cost() + " + 1");
        // Local-Preference is not exported, so set it to default value if it's an export.
        env.set_lp("100");
      }
    }
  }

  //Kick off the whole computation
  public DecisionTree<Boolean> compute() {
    Environment env = new Environment();
    TransferParam<Environment> p = new TransferParam<>(env, false);
    Node<Boolean> nodeP = new Node<>(true, p, _isExport);
    /*System.out.println("Printing statements for " + _graphEdge.toString());
    printStatements(_statements); */
    DecisionTree<Boolean> t = compute(_statements, nodeP);
    applyMetricUpdate(t);
    return t;
  }

  // Extend an existing decision tree (t), under the environment pCur, with another decision tree
  // computed from the BooleanExpr be. tleaf,fleaf are the true/false leafs of t and lr denotes which
  // leaf should the extension happen at (for instance lr = true means that true leaf is extended).
  private DecisionTree<Boolean> continuationExpr(DecisionTree<Boolean> t, TransferParam<Environment> pCur,
      Node<Boolean> tleaf, Node<Boolean> fleaf,
      BooleanExpr be, boolean lr) {

    List<Tuple<DecisionTree<Boolean>, Node<Boolean>>> todo = new ArrayList<>();

    // For every leaf in the decision tree
    for (Node<Boolean> leaf : t.getLeafs()) {
      // That matches the value of lr
      if (leaf.getData() == lr) {
        TransferParam<Environment> newEnv = leaf.getEnv();
        Node<Boolean> newTleaf = tleaf;
        Node<Boolean> newFleaf = fleaf;
        // If the environment has changed then mutate it.
        if (!newEnv.equals(pCur)) {
           newTleaf = new Node<>(true, newEnv, _isExport);
           newFleaf = new Node<>(false, newEnv, _isExport);
           newEnv = newEnv.deepCopy();
        }

        DecisionTree<Boolean> res = exprToTree(be, newEnv, newTleaf, newFleaf);
        // keep track of which leafs should be updated.
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

}
