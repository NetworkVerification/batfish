/**********************/
/* All-faults analysis */
/************************/

package org.batfish.minesweeper.nv;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.SortedMap;
import org.batfish.datamodel.Ip;
import org.batfish.datamodel.Prefix;
import org.batfish.minesweeper.GraphEdge;
import org.batfish.minesweeper.utils.Triple;
import org.batfish.minesweeper.utils.Tuple;

public class FaultAnalysis {

  /* Maps router names to node integer identifiers */
  private final Map<String, Integer> _nodes;

  /* Maps GraphEdge (as String) to NV edges */
  private final Map<GraphEdge, String> _edgeMap;

  private final ArrayList<LinkedList<Integer>> _adj;

  // Filename for the underlying network to be included.
  private final String _filename;

  // Number of nodes
  private final Integer _vnum;

  private final Set<Prefix> _originated;

  private final Set<Ip> _ibgpLoopbacks;

  private final String _order;

  /* Link Failure Probabilities - TODO: These are unused, if we only need edgeGroups then remove.*/
  private final Map<String, Double> _linkFailureProbability;

  /* Node Failure Probabilities */
  private final Map<String, Double> _nodeFailureProbability;

  /* Set of links groupped by the failure of probability */
  public final SortedMap<Double, Set<String>> _edgeGroups;

  public FaultAnalysis(
      String _filename,
      Map<String, Integer> _nodes,
      Map<GraphEdge, String> _edgeMap,
      ArrayList<LinkedList<Integer>> adj,
      int vnum,
      Set<Prefix> originated,
      Set<Ip> ibgpLoopbacks,
      String order,
      Map<String, Double> linkFailureProbability,
      Map<String, Double> nodeFailureProbability,
      SortedMap<Double, Set<String>> edgeGroups) {
    this._nodes = _nodes;
    this._edgeMap = _edgeMap;
    this._filename = _filename;
    this._adj = adj;
    this._vnum = vnum;
    this._originated = originated;
    this._ibgpLoopbacks = ibgpLoopbacks;
    this._order = order;
    this._linkFailureProbability = linkFailureProbability;
    this._nodeFailureProbability = nodeFailureProbability;
    this._edgeGroups = edgeGroups;
  }

  public String get_filename() {
    return _filename;
  }

  public Set<Prefix> get_originated() {
    return _originated;
  }

  /* adapted from:https://www.geeksforgeeks.org/breadth-first-search-or-bfs-for-a-graph/ */
  private void createNodeSymbolicsBFS(StringBuilder sb, int s) {
    // Mark all the vertices as not visited(By default
    // set as false)
    boolean[] visited = new boolean[_vnum];

    // Create a queue for BFS
    LinkedList<Integer> queue = new LinkedList<>();

    // Mark the current node as visited and enqueue it
    visited[s] = true;
    queue.add(s);

    while (queue.size() != 0) {
      // Dequeue a vertex from queue and print it
      s = queue.poll();
      sb.append("symbolic ")
          .append(symbolicName(s))
          .append(" : bool = | true -> 0.05p | false -> 0.95p\n");

      // Get all adjacent vertices of the dequeued vertex s
      // If a adjacent has not been visited, then mark it
      // visited and enqueue it
      Iterator<Integer> i = _adj.get(s).listIterator();
      while (i.hasNext()) {
        int n = i.next();
        if (!visited[n]) {
          visited[n] = true;
          queue.add(n);
        }
      }
    }
  }

  /* For fat trees only, creates nodes in the order: edge routers, core routers, aggregation routers */
  private void createNodeSymbolicsFatTree(StringBuilder sb) {
    HashSet<Integer> tor = new HashSet<>();
    HashSet<Integer> core = new HashSet<>();
    HashSet<Integer> agg = new HashSet<>();
    for (Entry<String, Integer> router : _nodes.entrySet()) {
      if (router.getKey().matches("aggregation.*")) {
        agg.add(router.getValue());
      }
      if (router.getKey().matches("edge.*")) {
        tor.add(router.getValue());
      }
      if (router.getKey().matches("core.*")) {
        core.add(router.getValue());
      }
    }
    for (int i : tor) {
      sb.append("symbolic ")
          .append(symbolicName(i))
          .append(": bool = | true -> 0.0001p | false -> 0.9999p \n");
    }
    for (int i : core) {
      sb.append("symbolic ")
          .append(symbolicName(i))
          .append(": bool = | true -> 0.0001p | false -> 0.9999p\n");
    }
    for (int i : agg) {
      sb.append("symbolic ")
          .append(symbolicName(i))
          .append(": bool = | true -> 0.0001p | false -> 0.9999p\n");
    }
  }

  private String symbolicName(int i) {
    return "b" + i;
  }

  private String createNodesSymbolics() {
    int sz = _nodes.size();
    StringBuilder sb = new StringBuilder();
    for (int i = 0; i < sz; i++) {
      sb.append("symbolic ")
          .append(symbolicName(i))
          .append(": bool = | true -> 0.0001p | false -> 0.9999p\n");
    }
    return sb.toString();
  }

  private String createNodesMatch() {
    StringBuilder sb = new StringBuilder();
    int sz = _nodes.size();
    for (int i = 0; i < sz; i++) {
      sb.append("  | ").append(i).append("n -> ").append(symbolicName(i)).append("\n");
    }
    return sb.toString();
  }

  /* All node failures */
  public Tuple<String, Map<Prefix, String>> compileAllFaults(boolean singlePrefix) {
    StringBuilder sb = new StringBuilder();
    sb.append("include \"../").append(_filename).append("_control.nv").append("\"\n\n");

    if (_order.equals("default")) sb.append(createNodesSymbolics());
    else if (_order.equals("fat")) {
      createNodeSymbolicsFatTree(sb);
    } else createNodeSymbolicsBFS(sb, 0);

    sb.append("\n");

    sb.append("let isFailed u = \n")
        .append("  match u with\n")
        .append(createNodesMatch())
        .append("\n");

    sb.append("let mergeNodeFaults u (x : [M]attribute) (y : [M]attribute) =\n")
        .append("  merge u x y\n\n");

    sb.append("let transNodeFaults d e (x : [M]attribute) =\n")
        .append("  if (match e with | a~b -> isFailed b) then\n")
        .append("    {connected=None; static=None; ospf=None; bgp=None; selected=None;}\n")
        .append("  else trans d e x\n\n");

    sb.append("let initNodeFaults d u = \n")
        .append("  if isFailed u then\n")
        .append("    {connected=None; static=None; ospf=None; bgp=None; selected=None;}\n")
        .append("  else init d u\n\n");

    Map<Prefix, String> nodeFaultsPerPrefix = new HashMap<>();

    for (Prefix pre : _originated) {
      StringBuilder sbpre = new StringBuilder();

      sbpre
          .append("include \"")
          .append(_filename)
          .append("_nodeFaults_")
          .append(_order)
          .append(".nv")
          .append("\"\n\n")
          .append("let d = (")
          .append(pre.toString())
          .append(")\n\n")
          .append(
              "solution nodeFaults = (initNodeFaults d, transNodeFaults d, mergeNodeFaults)\n\n");

      generateReachabilityAssertion(sbpre, "nodeFaults");
      nodeFaultsPerPrefix.put(pre, sbpre.toString());
    }

    return new Tuple<>(sb.toString(), nodeFaultsPerPrefix);
  }

  private String symbolicLinkName(int i, int j) {
    if (i < j) return "b" + i + "_" + j;
    else return "b" + j + "_" + i;
  }

  /* Creates symbolic variables denoting whether a link is failed or not.
    We assume bidirectional failures.
  */
  private String createLinkSymbolics() {
    int sz = _adj.size();
    StringBuilder sb = new StringBuilder();
    Set<Tuple<Integer, Integer>> done = new HashSet<>();
    for (int i = 0; i < sz; i++) {
      LinkedList<Integer> js = _adj.get(i);
      for (Integer j : js) {
        // If we haven't inserted the reverse link
        if (!done.contains(new Tuple<Integer, Integer>(j, i))) {
          sb.append("symbolic ")
              .append(symbolicLinkName(i, j))
              .append(": bool = | true -> 0.001p | false -> 0.999p\n");
          done.add(new Tuple<Integer, Integer>(i, j));
        }
      }
    }
    return sb.toString();
  }

  /* adapted from:https://www.geeksforgeeks.org/breadth-first-search-or-bfs-for-a-graph/ */
  private void createLinkSymbolicsBFS(StringBuilder sb, int s) {
    // Mark all the vertices as not visited(By default
    // set as false)
    boolean visited[] = new boolean[_vnum];

    // Used to implemented bidirectional failures
    Set<Tuple<Integer, Integer>> done = new HashSet<>();

    // Create a queue for BFS
    LinkedList<Integer> queue = new LinkedList<>();

    // Mark the current node as visited and enqueue it
    visited[s] = true;
    queue.add(s);

    while (queue.size() != 0) {
      // Dequeue a vertex from queue
      s = queue.poll();

      // Get all adjacent vertices of the dequeued vertex s
      // If a adjacent has not been visited, then mark it
      // visited and enqueue it
      Iterator<Integer> i = _adj.get(s).listIterator();
      while (i.hasNext()) {
        int n = i.next();
        if (!done.contains(new Tuple<>(n, s))) {
          sb.append("symbolic ")
              .append(symbolicLinkName(s, n))
              .append(": bool = | true -> 0.001p | false -> 0.999p\n");
          done.add(new Tuple<>(s, n));
        }
        if (!visited[n]) {
          visited[n] = true;
          queue.add(n);
        }
      }
    }
  }

  private String createLinksMatch() {
    int sz = _adj.size();
    StringBuilder sb = new StringBuilder();
    for (int i = 0; i < sz; i++) {
      LinkedList<Integer> js = _adj.get(i);
      for (Integer j : js) {
        sb.append("  | ")
            .append(i)
            .append("~")
            .append(j)
            .append(" -> ")
            .append(symbolicLinkName(i, j))
            .append("\n");
      }
    }
    return sb.toString();
  }

  private void generateReachabilityAssertion(StringBuilder sb, String solution) {
    sb.append("let reachable u =\n" + "  match ")
        .append(solution)
        .append("[u].selected with | None -> false | _ -> true\n");

    sb.append("\n");
    for (Entry<String, Integer> u : _nodes.entrySet()) {
      sb.append("assert(\"reachability_")
          .append(u.getKey())
          .append("\",")
          .append("reachable ")
          .append(u.getValue())
          .append("n)\n");
    }
  }

  /* Code for all link failures analysis */
  public Tuple<String, Map<Prefix, String>> compileAllLinkFaults(boolean singlePrefix) {
    StringBuilder sb = new StringBuilder();
    sb.append("include \"../").append(_filename).append("_control.nv").append("\"\n\n");

    if (_order.equals("default")) sb.append(createLinkSymbolics());
    else if (_order.equals("bfs")) createLinkSymbolicsBFS(sb, 0);

    sb.append("\n");

    sb.append("let isFailed e = \n")
        .append("  match e with\n")
        .append(createLinksMatch())
        .append("\n");

    sb.append("let mergeLinkFaults u (x : [M]attribute) (y : [M]attribute) =\n")
        .append("  merge u x y\n\n");

    sb.append("let transLinkFaults d e (x : [M]attribute) =\n")
        .append("  if isFailed e then\n")
        .append("    {connected=None; static=None; ospf=None; bgp=None; selected=None;}\n")
        .append("  else trans d e x\n\n");

    sb.append("let initLinkFaults d u = init d u \n\n");

    Map<Prefix, String> faultsPerPrefix = new HashMap<>();

    for (Prefix pre : _originated) {
      StringBuilder sbpre = new StringBuilder();

      sbpre
          .append("include \"")
          .append(_filename)
          .append("_linkFaults_")
          .append(_order)
          .append(".nv")
          .append("\"\n\n")
          .append("let d = (")
          .append(pre.toString())
          .append(")\n\n")
          .append(
              "solution linkFaults = (initLinkFaults d, transLinkFaults d, mergeLinkFaults)\n\n");

      generateReachabilityAssertion(sbpre, "linkFaults");
      faultsPerPrefix.put(pre, sbpre.toString());
    }

    return new Tuple<>(sb.toString(), faultsPerPrefix);
  }

  public String boundedSymbolicName(int i) {
    return "f" + i;
  }

  public static double log2(int n) {
    return (Math.log(n) / Math.log(2));
  }

  private int numberOfBits(int n) {
    return (int) Math.floor(log2(n)) + 1;
  }

  public static double binomCoeff(int n, int r) {
    if (r > n || r < 0) {
      return 0;
    }
    if (r == 0 || r == n) {
      return 1;
    }

    double value = 1;

    for (int i = 0; i < r; i++) {
      value = value * (n - i) / (r - i);
    }

    return value;
  }

  //  private void createBoundedLinkSymbolics(StringBuilder sb, int bound) {
  //    int numberOfEdges = _edgeMap.size();
  //    double nofailureProb = 0.9995;
  //    int bits = numberOfBits(bound);
  //    sb.append("symbolic failures : int").append(bits).append(" =\n");
  //    for (int i = 0; i <= bound; i++) {
  //      double probabilityOfI = binomCoeff(numberOfEdges, i) * Math.pow(nofailureProb,
  // _edgeMap.size()-i) * Math.pow(1-nofailureProb,i);
  //      sb.append("  | [")
  //          .append(i)
  //          .append("u")
  //          .append(bits)
  //          .append(", ")
  //          .append(i)
  //          .append("u")
  //          .append(bits)
  //          .append("] -> ")
  //          .append(String.format("%.16f", probabilityOfI))
  //          .append("p\n");
  //    }
  //    sb.append("  | _ -> 0.0p\n\n");
  //    for (int i = 0; i < bound; i++) {
  //      sb.append("symbolic ").append(boundedSymbolicName(i)).append(" : tedge\n");
  //    }
  //  }

  private double listProbability(
      ArrayList<Integer> xs,
      double probabilityOfNoFailure,
      ArrayList<Tuple<Double, Set<String>>> groups) {
    // Index to count
    Map<Integer, Integer> ys = new HashMap<>();
    for (Integer x : xs) {
      Integer freq = ys.get(x);
      if (freq == null) ys.put(x, 1);
      else ys.put(x, freq + 1);
    }

    double prob = probabilityOfNoFailure;
    for (Entry<Integer, Integer> y : ys.entrySet()) {
      Tuple<Double, Set<String>> group = groups.get(y.getKey());
      // Computing binom(|group|, cardinality) * p(i) * (1-p(i))^(-cardinality) * ... *
      // nofailureProb.
      //      System.out.println("Coeff: " + binomCoeff(group.getSecond().size(), y.getValue()));
      prob =
          prob
              * binomCoeff(group.getSecond().size(), y.getValue())
              * (Math.pow(group.getFirst(), y.getValue()))
              * (Math.pow(1 - group.getFirst(), -y.getValue()));
    }
    return prob;
  }

  // Creating symbolics for bounded link failures using joint distributions
  private void createBoundedLinkSymbolics(StringBuilder sb, boolean conditional, int bound) {
    int numberOfEdges = _edgeMap.size();
    int numberOfGroups = _edgeGroups.size();

    // Compute the probability that no failure occurs
    double probabilityOfNoFailure = 1.0;
    for (Entry<Double, Set<String>> e : _edgeGroups.entrySet()) {
      probabilityOfNoFailure =
          probabilityOfNoFailure * Math.pow(1 - e.getKey(), e.getValue().size());
    }

    /* Compute the probability that at most [bound] failures occur. */

    /*  This will be P(At-Most-k) = P(0-failures) + P(1-failure) + .. P(k-failures)
     * It is rather tricky to compute this when links have different failure probabilities.
     * The idea we use is the following:
     * 1. Make a matrix where the ith row contains the valid failure combinations for (i+1) failures.
     * 2. These combinations are expressed as lists containing the index of the group belonging to this combination.
     * 3. Since these are lists multiple elements are allowed. For instance, [0,0] means the first group has 2 failed links.
     * 4. Computing the probability for a single list [i,j,i] we use the following rules:
     *     * binom(|i|, 2) * p(i) * (1-p(i))^(-2) * |j| * (1-p(j))^-1 * nofailureProb.
     *  */

    ArrayList<ArrayList<ArrayList<Integer>>> matrix = new ArrayList<>();
    ArrayList<ArrayList<Integer>> baseRow = new ArrayList<>();

    // populate base row (i.e. for 1 failure)
    int numGroups = _edgeGroups.size();

    for (int i = 0; i < numGroups; i++) {
      ArrayList<Integer> elt = new ArrayList<>();
      elt.add(i);
      baseRow.add(elt);
    }

    matrix.add(baseRow);

    // Note that matrix[i] corresponds to probability i+1 failures occur.
    for (int i = 1; i < bound; i++) {
      ArrayList<ArrayList<Integer>> currentRow = new ArrayList<>();
      ArrayList<ArrayList<Integer>> rowOne = matrix.get(0);
      ArrayList<ArrayList<Integer>> rowPrev = matrix.get(i - 1);

      for (int j = 0; j < rowOne.size(); j++) {
        for (int k = j; k < rowPrev.size(); k++) {
          ArrayList<Integer> kElt = rowPrev.get(k);
          // if the first elt of base row is less or equal than the first elt of the previous row.
          if (rowOne.get(j).get(0) <= kElt.get(0)) {
            ArrayList<Integer> curElt = new ArrayList<>(kElt);
            curElt.add(0, rowOne.get(j).get(0));
            // curElt = [b[j]; prev]
            currentRow.add(0, curElt);
          }
        }
      }
      matrix.add(currentRow);
    }

    // Represent _edgeGroups as an arraylist.

    ArrayList<Tuple<Double, Set<String>>> groups = new ArrayList<>();

    for (Entry<Double, Set<String>> e : _edgeGroups.entrySet()) {
      Tuple<Double, Set<String>> t = new Tuple<>(e.getKey(), e.getValue());
      groups.add(t);
    }

    // Holds the probability that at most bound failures occur.
    double probabilityAtMostKFailed = probabilityOfNoFailure;

    // Iterate each line of the matrix
    for (ArrayList<ArrayList<Integer>> row : matrix) {
      for (ArrayList<Integer> xs : row) {
        probabilityAtMostKFailed += listProbability(xs, probabilityOfNoFailure, groups);
      }
    }

    /* For the no failure case we need to divide by edges^m because of our semantics in
    ProbNV (it will be multiplied by the same number) */
    double probabilityOfNoFailureNormalized =
        probabilityOfNoFailure / Math.pow(numberOfEdges, bound);

    /* Emit failureProb function that describes the probability of each link failure individually.
     For every group this will be (p * (1-p)^-1).
    */

    sb.append("let failureProb (f : [S]tedge) =\n");
    int groupId = 0;
    Iterator<Entry<Double, Set<String>>> iter = _edgeGroups.entrySet().iterator();
    for (int i = 0; i <= numberOfGroups - 1; i++) {
      Entry<Double, Set<String>> e = iter.next();
      Double probabilityOfFailure = e.getKey();
      Double probabilityForGroup = probabilityOfFailure * (1.0 / (1 - probabilityOfFailure));
      if (i == numberOfGroups - 1) {
        sb.append(String.format("  %.16f\n\n", probabilityForGroup));
      } else {
        groupId = groupId + e.getValue().size();
        sb.append("  if (f <e ")
            .append(groupId)
            .append("e")
            .append(") then\n")
            .append(String.format("%.16f\n", probabilityForGroup))
            .append("  else\n");
      }
    }

    // Emit the symbolics definition and distribution.

    StringBuilder sbSymbolics = new StringBuilder();
    StringBuilder sbTypes = new StringBuilder();

    sbSymbolics.append("(hasFailures, ");
    sbTypes.append("(bool, ");
    for (int i = 1; i <= bound; i++) {
      sbSymbolics.append(boundedSymbolicName(i));
      sbTypes.append("tedge");
      if (i < bound) {
        sbSymbolics.append(", ");
        sbTypes.append(", ");
      }
    }
    sbSymbolics.append(")");
    sbTypes.append(")");

    sb.append("symbolic ").append(sbSymbolics).append(" : ").append(sbTypes).append(" =\n");
    sb.append("  if (hasFailures = false) then\n")
        .append(
            String.format(
                "    %.16f\n",
                conditional
                    ? probabilityOfNoFailureNormalized / probabilityAtMostKFailed
                    : probabilityOfNoFailureNormalized));
    sb.append("  else\n");

    if (bound == 1) {
      sb.append("      ");
      sb.append("(failureProb ").append(boundedSymbolicName(1)).append(")");
      sb.append(" *. ");
      // Multiply by probability of no failure.
      sb.append(
          String.format(
              "%.16f\n\n",
              conditional
                  ? probabilityOfNoFailure / probabilityAtMostKFailed
                  : probabilityOfNoFailure));
    } else {
      for (int i = bound; i > 0; i--) {
        sb.append("    if ");
        // Generate less-than constraints
        for (int j = 1; j < i; j++) {
          sb.append("(")
              .append(boundedSymbolicName(j))
              .append(" <e ")
              .append(boundedSymbolicName(j + 1))
              .append(")");

          if (j + 1 < i) sb.append(" && ");
        }

        // In this case both less-than and eq constraints will be generated so we need an extra &&.
        if (i > 1 && i < bound) {
          sb.append(" && ");
        }

        // Generating equality constraints
        for (int j = i; j < bound; j++) {
          sb.append("(")
              .append(boundedSymbolicName(j))
              .append(" = ")
              .append(boundedSymbolicName(j + 1))
              .append(")");
          if (j + 1 < bound) sb.append(" && ");
        }

        // Generate probability for this case

        sb.append(" then\n");
        for (int j = 1; j <= i; j++) {
          if (j == 1) sb.append("      ");
          sb.append("(failureProb ").append(boundedSymbolicName(j)).append(")");
          sb.append(" *. ");
        }

        // Multiply by probability of no failure.
        sb.append(
            String.format(
                "%.16f\n",
                conditional
                    ? probabilityOfNoFailure / probabilityAtMostKFailed
                    : probabilityOfNoFailure));

        sb.append("    else\n");
      }
      sb.append("      0.0");
      sb.append("\n");
    }
  }

  //  public String failureCondition(int bound) {
  //    StringBuilder sb = new StringBuilder();
  //    sb.append("(failures >u").append(numberOfBits(bound)).append(" 0u")
  //        .append(numberOfBits(bound))
  //        .append(") && ((")
  //        .append(boundedSymbolicName(0)).append(" = e)");
  //    for (int i = 1; i < bound; i++) {
  //      sb.append("|| (").append(boundedSymbolicName(i)).append(" = e)");
  //    }
  //    sb.append(")");
  //    return sb.toString();
  //  }

  public String failureCondition(int bound) {
    StringBuilder sb = new StringBuilder();
    sb.append("hasFailures").append(" && ((").append(boundedSymbolicName(1)).append(" = e)");
    for (int i = 2; i <= bound; i++) {
      sb.append(" || (").append(boundedSymbolicName(i)).append(" = e)");
    }
    sb.append(")");
    return sb.toString();
  }

  public String generateOrderingConstraints(int total) {
    StringBuilder ord = new StringBuilder();

    for (int j = 0; j < total - 1; j++) {
      ord.append("let ltf")
          .append(j)
          .append("_")
          .append(j + 1)
          .append(" = ")
          .append(boundedSymbolicName(j))
          .append(" <e ")
          .append(boundedSymbolicName(j + 1))
          .append("\n");
      ord.append("let eqf")
          .append(j)
          .append("_")
          .append(j + 1)
          .append(" = ")
          .append(boundedSymbolicName(j))
          .append(" = ")
          .append(boundedSymbolicName(j + 1))
          .append("\n");
    }

    for (int i = 1; i <= total; i++) {
      ord.append("let ord").append(i).append(" = ");
      for (int j = 0; j < i - 1; j++) {
        ord.append("ltf").append(j).append("_").append(j + 1);
        if (j + 1 < i - 1) // add an and if there is another iteration
        ord.append(" && ");
      }

      if ((i - 1 > 0)
          && (i - 1
              < total - 1)) // add an && if there was an iteration and there will be another one
      ord.append(" && ");
      for (int j = i - 1; j < total - 1; j++) {
        ord.append("eqf").append(j).append("_").append(j + 1);
        if (j + 1 < total - 1) ord.append(" && ");
      }
      ord.append("\n");
    }
    int bits = numberOfBits(total);
    ord.append("let ord = (failures = 0u").append(bits).append(") || ");
    for (int i = 1; i <= total; i++) {
      ord.append(" ((failures = ")
          .append(i)
          .append("u")
          .append(bits)
          .append(") && ord")
          .append(i)
          .append(")");
      if (i < total) ord.append(" || ");
    }
    ord.append("\n\n");
    return ord.toString();
  }

  private void generateBoundedReachabilityAssertion(StringBuilder sb, String solution, int bound) {
    sb.append("let reachable u =\n" + "  match ")
        .append(solution)
        .append("[u].selected with | None -> false | _ -> true\n");

    sb.append("\n");

    sb.append(generateOrderingConstraints(bound));

    for (Entry<String, Integer> u : _nodes.entrySet()) {
      sb.append("assert(\"reachability_")
          .append(u.getKey())
          .append("\",")
          .append("reachable ")
          .append(u.getValue())
          .append("n | ord)\n");
    }
  }

  public static String ribName(Prefix pre) {
    return "rib_" + pre.toString().replace('.', '_').replace('/', '_');
  }

  /* Computes the ProbNV code that models failures.
  The first component is general definitions such as the symbolics,
  the second component is the solution declarations for iBGP loopbacks
  and the third component is the solution declarations for all other destinations.*/
  public Triple<Tuple<String, String>, Map<Prefix, String>, Map<Prefix, String>>
      compileBoundedLinkFaults(boolean conditional, int bound, boolean partitioned) {
    /* Hold the first component */
    StringBuilder sb = new StringBuilder();
    // We are including everything in one file (symbolic and solution declarations) due to iBGP
    // which requires some prefixes to be resolved first and then used by other prefixes.
    //    sb.append("include \"../").append(_filename).append("_control.nv").append("\"\n\n");

    StringBuilder sbIBGP = new StringBuilder();
    if (bound > 0) {
      createBoundedLinkSymbolics(sb, conditional, bound);
      sb.append("\n");
    }
    sb.append("let mergeLinkFaults u (x : [M]attribute) (y : [M]attribute) =\n")
        .append("  merge u x y\n\n");

    if (bound > 0) {
      String cond = failureCondition(bound);
      sb.append("let transLinkFaults d e (x : [M]attribute) =\n")
          .append("  if ")
          .append(cond)
          .append(" then\n")
          .append("    {connected=None; static=None; ospf=None; bgp=None; selected=None;}\n")
          .append("  else trans d e x\n\n");

      sbIBGP
          .append("let transLinkFaultsIBGP d e x = \n")
          .append(
              "  {connected=None; static=None; ospf=if "
                  + cond
                  + " then None else transferOspf e x.ospf; bgp=transferIBGP d e x (loopbackV e) (loopbackU e); selected=None;}\n\n");

      sbIBGP
          .append("let transLinkFaultsEBGP d e x = \n")
          .append("  if ")
          .append(cond)
          .append(" then\n")
          .append("    {connected=None; static=None; ospf=None; bgp=None; selected=None;}\n")
          .append(
              "  else {connected=None; static=None; ospf=transferOspf e x.ospf; bgp=transferEBGP d e x; selected=None}\n\n");

    } else {
      sb.append("let transLinkFaults d e (x : [M]attribute) = trans d e x\n\n");
      sbIBGP
          .append("let transLinkFaultsIBGP d e x = \n")
          .append(
              "  {connected=None; static=None; ospf=transferOspf e x.ospf; bgp=transferIBGP d e x (loopbackV e) (loopbackU e); selected=None;}\n\n");

      sbIBGP
          .append("let transLinkFaultsEBGP d e x = \n")
          .append(
              "  {connected=None; static=None; ospf=transferOspf e x.ospf; bgp=transferEBGP d e x; selected=None}\n\n");
    }
    sbIBGP.append("let transLinkFaults d e x =\n").append("  match e with\n");
    for (Entry<GraphEdge, String> e : _edgeMap.entrySet()) {
      if (e.getKey().isAbstract())
        sbIBGP.append("  | " + e.getValue() + " -> transLinkFaultsIBGP d e x\n");
    }
    sbIBGP.append("  | _ -> transLinkFaultsEBGP d e x\n\n");

    sb.append("let initLinkFaults d u = init d u \n\n");

    //    let transLinkFaults d e x  =
    //        match e with
    //  | 1~0 -> transLinkFaultsIBGP d e x
    //  | 0~1 -> transLinkFaultsIBGP d e x
    //  | _ -> transLinkFaultsEBGP d e x

    Map<Prefix, String> faultsPerPrefix = new HashMap<>();
    Map<Prefix, String> faultsPerPrefixIBGP = new HashMap<>();

    for (Prefix pre : _originated) {
      StringBuilder sbpre = new StringBuilder();

      // Only include the file in partitioned mode, otherwise everything will be in the same file.
      //      if (partitioned)
      //        sbpre.append("include \"")
      //            .append(_filename)
      //            .append("_")
      //            .append(bound)
      //            .append("_linkFaults.nv")
      //            .append("\"\n\n");

      String solutionName = ribName(pre);
      sbpre
          .append("solution ")
          .append(solutionName)
          .append(" = (initLinkFaults (")
          .append(pre)
          .append("), transLinkFaults (")
          .append(pre)
          .append("), mergeLinkFaults)\n\n");

      // Only generate the assertions if we are in partitioned mode
      if (partitioned) {
        // TODO: this case will not work with iBGP.
        generateBoundedReachabilityAssertion(sbpre, solutionName, bound);
        faultsPerPrefix.put(pre, sbpre.toString());
      } else {
        // is this an iBGP prefix?
        if (_ibgpLoopbacks.stream().anyMatch(ip -> pre.containsIp(ip))) {
          faultsPerPrefixIBGP.put(pre, sbpre.toString());
        } else {
          faultsPerPrefix.put(pre, sbpre.toString());
        }
        //        sb.append(sbpre);
      }
    }
    return new Triple<>(
        new Tuple<>(sb.toString(), sbIBGP.toString()), faultsPerPrefixIBGP, faultsPerPrefix);
  }
}
