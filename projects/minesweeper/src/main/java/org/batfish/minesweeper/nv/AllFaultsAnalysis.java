/***********************/
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
import org.batfish.datamodel.Prefix;
import org.batfish.datamodel.pojo.Link;
import org.batfish.minesweeper.GraphEdge;
import org.batfish.minesweeper.utils.Tuple;

public class AllFaultsAnalysis {

  /* Maps router names to node integer identifiers */
  private Map<String, Integer> _nodes;

  /* Maps GraphEdge to NV edges */
  private Map<GraphEdge, String> _edgeMap;

  private ArrayList<LinkedList<Integer>> _adj;

  // Filename for the underlying network to be included.
  private String _filename;

  // Number of nodes
  private Integer _vnum;

  private Set<Prefix> _originated;

  public AllFaultsAnalysis(String _filename, Map<String, Integer> _nodes, Map<GraphEdge, String> _edgeMap, ArrayList<LinkedList<Integer>> adj, int vnum, Set<Prefix> originated) {
    this._nodes = _nodes;
    this._edgeMap = _edgeMap;
    this._filename = _filename;
    this._adj = adj;
    this._vnum = vnum;
    this._originated = originated;
  }

  /* adapted from:https://www.geeksforgeeks.org/breadth-first-search-or-bfs-for-a-graph/ */
  private void createNodeSymbolicsBFS(StringBuilder sb, int s)
  {
    // Mark all the vertices as not visited(By default
    // set as false)
    boolean visited[] = new boolean[_vnum];

    // Create a queue for BFS
    LinkedList<Integer> queue = new LinkedList<>();

    // Mark the current node as visited and enqueue it
    visited[s]=true;
    queue.add(s);

    while (queue.size() != 0)
    {
      // Dequeue a vertex from queue and print it
      s = queue.poll();
      sb.append("symbolic " + (symbolicName(s) +" : bool = | true -> 0.05p | false -> 0.95p\n"));

      // Get all adjacent vertices of the dequeued vertex s
      // If a adjacent has not been visited, then mark it
      // visited and enqueue it
      Iterator<Integer> i = _adj.get(s).listIterator();
      while (i.hasNext())
      {
        int n = i.next();
        if (!visited[n])
        {
          visited[n] = true;
          queue.add(n);
        }
      }
    }
  }

  /* For fat trees only, creates nodes in the order: edge routers, core routers, aggregation routers */
  private void createNodeSymbolicsFatTree(StringBuilder sb)
  {
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
    for (int i : tor)
    {
      sb.append("symbolic " + (symbolicName(i)) + ": bool = | true -> 0.0001p | false -> 0.9999p \n");
    }
    for (int i : core)
    {
      sb.append("symbolic " + (symbolicName(i)) + ": bool = | true -> 0.0001p | false -> 0.9999p\n");
    }
    for (int i : agg)
    {
      sb.append("symbolic " + (symbolicName(i)) + ": bool = | true -> 0.0001p | false -> 0.9999p\n");
    }
  }

  private String symbolicName(int i) {
    return "b" + i;
  }

  private String createNodesSymbolics () {
    int sz = _nodes.size();
    StringBuilder sb = new StringBuilder();
    for (int i = 0; i < sz; i++) {
      sb.append("symbolic " + (symbolicName(i)) + ": bool = | true -> 0.0001p | false -> 0.9999p\n");
    }
    return sb.toString();
  }

  private String createNodesMatch () {
    StringBuilder sb = new StringBuilder();
    int sz = _nodes.size();
    for (int i = 0; i < sz; i++) {
      sb.append("  | " + i + "n -> " + symbolicName(i) + "\n");
    }
    return sb.toString();
  }

/* All node failures */
  public Tuple<String, Map<Prefix, String>> compileAllFaults(boolean singlePrefix) {
    StringBuilder sb = new StringBuilder();
    sb.append("include \"../" + _filename + "_control.nv" + "\"\n\n");

    //sb.append(createNodesSymbolics());
    createNodeSymbolicsBFS(sb, 0);
    //createNodeSymbolicsFatTree(sb);
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

      sbpre.append("include \"" + _filename + "_nodeFaults.nv" + "\"\n\n")
          .append("let d = (" + pre.toString() + ")\n\n")
          .append("let nodeFaults = solution(initNodeFaults d, transNodeFaults d, mergeNodeFaults)\n\n");

      generateReachabilityAssertion(sbpre, "nodeFaults");
      nodeFaultsPerPrefix.put(pre, sbpre.toString());
    }

    return new Tuple<>(sb.toString(), nodeFaultsPerPrefix);
  }

  private String symbolicLinkName(int i, int j) {
    if (i < j)
      return "b" + i + "_" + j;
    else return "b" + j + "_" + i;
  }

  /* Creates symbolic variables denoting whether a link is failed or not.
     We assume bidirectional failures.
   */
  private String createLinkSymbolics () {
    int sz = _adj.size();
    StringBuilder sb = new StringBuilder();
    Set<Tuple<Integer,Integer>> done = new HashSet<>();
    for (int i = 0; i < sz; i++)
    {
      LinkedList<Integer> js = _adj.get(i);
      for (Integer j : js) {
        // If we haven't inserted the reverse link
        if (!done.contains(new Tuple<Integer, Integer>(j, i))) {
          sb.append("symbolic " + (symbolicLinkName(i, j)) + ": bool = | true -> 0.001p | false -> 0.999p\n");
          done.add(new Tuple<Integer, Integer>(i, j));
        }
      }
    }
    return sb.toString();
  }

  /* adapted from:https://www.geeksforgeeks.org/breadth-first-search-or-bfs-for-a-graph/ */
  private void createLinkSymbolicsBFS(StringBuilder sb, int s)
  {
    // Mark all the vertices as not visited(By default
    // set as false)
    boolean visited[] = new boolean[_vnum];

    // Used to implemented bidirectional failures
    Set<Tuple<Integer,Integer>> done = new HashSet<>();

    // Create a queue for BFS
    LinkedList<Integer> queue = new LinkedList<>();

    // Mark the current node as visited and enqueue it
    visited[s]=true;
    queue.add(s);

    while (queue.size() != 0)
    {
      // Dequeue a vertex from queue
      s = queue.poll();

      // Get all adjacent vertices of the dequeued vertex s
      // If a adjacent has not been visited, then mark it
      // visited and enqueue it
      Iterator<Integer> i = _adj.get(s).listIterator();
      while (i.hasNext())
      {
        int n = i.next();
        if (!done.contains(new Tuple<Integer, Integer>(n, s))) {
          sb.append("symbolic " + (symbolicLinkName(s, n)) + ": bool = | true -> 0.001p | false -> 0.999p\n");
          done.add(new Tuple<Integer, Integer>(s, n));
        }
        if (!visited[n])
        {
          visited[n] = true;
          queue.add(n);
        }
      }
    }
  }
  
  private String createLinksMatch () {
    int sz = _adj.size();
    StringBuilder sb = new StringBuilder();
    for (int i = 0; i < sz; i++)
    {
      LinkedList<Integer> js = _adj.get(i);
      for (Integer j : js) {
        sb.append("  | " + i + "~" + j + " -> " + symbolicLinkName(i,j) + "\n");
      }
    }
    return sb.toString();
  }

  private void generateReachabilityAssertion (StringBuilder sb, String solution) {
    sb.append("let reachable u =\n"
        + "  match " + solution + "[u].selected with | None -> false | _ -> true\n");

    sb.append("\n");
    for (Entry<String, Integer> u : _nodes.entrySet()) {
      sb.append("assert(\"reachability_").append(u.getKey()).append("\",").append("reachable " + u.getValue() + "n, 0.99p)\n");
    }
  }

  /* Code for all link failures analysis */
  public Tuple<String, Map<Prefix, String>>  compileAllLinkFaults(boolean singlePrefix) {
    StringBuilder sb = new StringBuilder();
    sb.append("include \"../" + _filename + "_control.nv" + "\"\n\n");

    //sb.append(createLinkSymbolics());
    createLinkSymbolicsBFS(sb, 0);
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

      sbpre.append("include \"" + _filename + "_linkFaults.nv" + "\"\n\n")
          .append("let d = (" + pre.toString() + ")\n\n")
          .append("let linkFaults = solution(initLinkFaults d, transLinkFaults d, mergeLinkFaults)\n\n");

      generateReachabilityAssertion(sbpre, "linkFaults");
      faultsPerPrefix.put(pre, sbpre.toString());
    }

    return new Tuple<>(sb.toString(), faultsPerPrefix);
  }

  private void createBoundedLinkSymbolics(StringBuilder sb, int bound) {
    for (int i = 0; i < bound; i++) {
      sb.append("symbolic f" + i + " : tedge\n");
    }
  }

  private String failureCondition(int bound) {
    StringBuilder sb = new StringBuilder();
    sb.append("(f" + 0 + " = e)");
    for (int i = 1; i < bound; i++) {
      sb.append("|| (f" + i + " = e)");
    }
    return sb.toString();
  }

  public Tuple<String, Map<Prefix, String>>  compiledBoundedLinkFaults(boolean singlePrefix, int bound) {
    StringBuilder sb = new StringBuilder();
    sb.append("include \"../" + _filename + "_control.nv" + "\"\n\n");

    //sb.append(createLinkSymbolics());
    createBoundedLinkSymbolics(sb, bound);
    sb.append("\n");

    sb.append("let mergeLinkFaults u (x : [M]attribute) (y : [M]attribute) =\n")
        .append("  merge u x y\n\n");

    String cond = failureCondition(bound);
    sb.append("let transLinkFaults d e (x : [M]attribute) =\n")
        .append("  if " + cond + " then\n")
        .append("    {connected=None; static=None; ospf=None; bgp=None; selected=None;}\n")
        .append("  else trans d e x\n\n");

    sb.append("let initLinkFaults d u = init d u \n\n");

    Map<Prefix, String> faultsPerPrefix = new HashMap<>();

    for (Prefix pre : _originated) {
      StringBuilder sbpre = new StringBuilder();

      sbpre.append("include \"" + _filename + "_" + bound + "_linkFaults.nv" + "\"\n\n")
          .append("let d = (" + pre.toString() + ")\n\n")
          .append("let linkFaults = solution(initLinkFaults d, transLinkFaults d, mergeLinkFaults)\n\n");

      generateReachabilityAssertion(sbpre, "linkFaults");
      faultsPerPrefix.put(pre, sbpre.toString());
    }

    return new Tuple<>(sb.toString(), faultsPerPrefix);
  }

}
