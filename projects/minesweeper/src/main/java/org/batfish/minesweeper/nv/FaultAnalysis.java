/***********************/
/* All-faults analysis */
/************************/

package org.batfish.minesweeper.nv;

import java.io.FileNotFoundException;
import java.io.FileWriter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Scanner;
import java.util.Set;
import java.io.File;
import org.batfish.datamodel.Prefix;
import org.batfish.minesweeper.GraphEdge;
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

  private Set<Prefix> _originated;

  private String _order;

  /* Link Failure Probabilities */
  private Map<String, Double>  _linkFailureProbability;

  /* Node Failure Probabilities */
  private Map<String, Double> _nodeFailureProbability;

  public FaultAnalysis(
      String _filename,
      Map<String, Integer> _nodes,
      Map<GraphEdge, String> _edgeMap,
      ArrayList<LinkedList<Integer>> adj,
      int vnum,
      Set<Prefix> originated,
      String order) {
    this._nodes = _nodes;
    this._edgeMap = _edgeMap;
    this._filename = _filename;
    this._adj = adj;
    this._vnum = vnum;
    this._originated = originated;
    this._order = order;
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

      sbpre.append("include \"")
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

      sbpre.append("include \"")
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

  public static double log2(int n)
  {
    return (Math.log(n) / Math.log(2));
  }

  private int numberOfBits(int n) {
    return (int)Math.floor(log2(n)) + 1;
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

  private void createBoundedLinkSymbolics(StringBuilder sb, int bound) {
    int numberOfEdges = _edgeMap.size();
    double nofailureProb = 0.9995;
    int bits = numberOfBits(bound);
    sb.append("symbolic failures : int").append(bits).append(" =\n");
    for (int i = 0; i <= bound; i++) {
      double probabilityOfI = binomCoeff(numberOfEdges, i) * Math.pow(nofailureProb, _edgeMap.size()-i) * Math.pow(1-nofailureProb,i);
      sb.append("  | [")
          .append(i)
          .append("u")
          .append(bits)
          .append(", ")
          .append(i)
          .append("u")
          .append(bits)
          .append("] -> ")
          .append(String.format("%.16f", probabilityOfI))
          .append("p\n");
    }
    sb.append("  | _ -> 0.0p\n\n");
    for (int i = 0; i < bound; i++) {
      sb.append("symbolic ").append(boundedSymbolicName(i)).append(" : tedge\n");
    }
  }

  public String failureCondition(int bound) {
    StringBuilder sb = new StringBuilder();
    sb.append("(failures >u").append(numberOfBits(bound)).append(" 0u")
        .append(numberOfBits(bound))
        .append(") && ((")
        .append(boundedSymbolicName(0)).append(" = e)");
    for (int i = 1; i < bound; i++) {
      sb.append("|| (").append(boundedSymbolicName(i)).append(" = e)");
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
      ord.append(" ((failures = ").append(i).append("u").append(bits).append(") && ord").append(i).append(")");
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

  public String ribName(Prefix pre) {
    return "rib_" + pre.toString().replace('.', '_').replace('/', '_');
  }

//  private String convertToCSV(String[] data) {
//    return Stream.of(data)
//        .map(this::escapeSpecialCharacters)
//        .collect(Collectors.joining(","));
//  }
//
  private void writeFailureProbabilities(File csvFile) throws IOException {

    FileWriter csvWriter = new FileWriter(csvFile);

    for (GraphEdge e : _edgeMap.keySet()) {
      csvWriter.append("link,").append(String.valueOf(e)).append(",").append("0.005");
      csvWriter.append("\n");
    }

    for (String e : _nodes.keySet()) {
      csvWriter.append("node,").append(String.valueOf(e)).append(",").append("0.01");
      csvWriter.append("\n");
    }

    csvWriter.flush();
    csvWriter.close();
  }

  /* Parse the failure probability of each link/device. If the file does not exist/create it.
  *  Expected format is type_of_failure, name, probability */
  private void parseFailureProbabilities(String file)  {
    File csvFile = new File(file);
    if (csvFile.isFile() && csvFile.canRead()) {
      try (Scanner scanner = new Scanner (csvFile)) {

        // CSV file delimiter
        String DELIMITER = ",";

        // set comma as delimiter
        scanner.useDelimiter(DELIMITER);

        String type = scanner.next();
        String name = scanner.next();
        String prob = scanner.next();

        if (type.equals("link"))  {
          _linkFailureProbability.put(name, new Double(prob));
        }
        else if (type.equals("node")) {
          _nodeFailureProbability.put(name, new Double(prob));
        }
      }
    catch (FileNotFoundException _) {
      System.out.println("File not found - generating a default failure model.");

      }
    }
  }

  public Tuple<String, Map<Prefix, String>> compiledBoundedLinkFaults(
      boolean singlePrefix, int bound, boolean partitioned) {
    StringBuilder sb = new StringBuilder();
    sb.append("include \"../").append(_filename).append("_control.nv").append("\"\n\n");

    // sb.append(createLinkSymbolics());
    createBoundedLinkSymbolics(sb, bound);
    sb.append("\n");

    sb.append("let mergeLinkFaults u (x : [M]attribute) (y : [M]attribute) =\n")
        .append("  merge u x y\n\n");

    String cond = failureCondition(bound);
    sb.append("let transLinkFaults d e (x : [M]attribute) =\n")
        .append("  if ")
        .append(cond)
        .append(" then\n")
        .append("    {connected=None; static=None; ospf=None; bgp=None; selected=None;}\n")
        .append("  else trans d e x\n\n");

    sb.append("let initLinkFaults d u = init d u \n\n");

    Map<Prefix, String> faultsPerPrefix = new HashMap<>();

    for (Prefix pre : _originated) {
      StringBuilder sbpre = new StringBuilder();

      // Only include the file in partitioned mode, otherwise everything will be in the same file.
      if (partitioned)
        sbpre.append("include \"")
            .append(_filename)
            .append("_")
            .append(bound)
            .append("_linkFaults.nv")
            .append("\"\n\n");

      String solutionName = ribName(pre);
      sbpre.append("solution ")
          .append(solutionName)
          .append(" = (initLinkFaults (").append(pre).append("), transLinkFaults (").append(pre).append("), mergeLinkFaults)\n\n");

      // Only generate the assertions if we are in partitioned mode
      if (partitioned) {
        generateBoundedReachabilityAssertion(sbpre, solutionName, bound);
        faultsPerPrefix.put(pre, sbpre.toString());
      }
      else {
        sb.append(sbpre);
      }
    }
    return new Tuple<>(sb.toString(), faultsPerPrefix);
  }
}
