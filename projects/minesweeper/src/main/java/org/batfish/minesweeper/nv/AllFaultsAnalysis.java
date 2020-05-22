/***********************/
/* All-faults analysis */
/************************/

package org.batfish.minesweeper.nv;

import java.util.Map;
import org.batfish.minesweeper.GraphEdge;

public class AllFaultsAnalysis {

  private Map<String, Integer> _nodes;

  /* Maps GraphEdge to NV edges */
  private Map<GraphEdge, String> _edgeMap;

  // Filename for the underlying network to be included.
  private String _filename;

  public AllFaultsAnalysis(String _filename, Map<String, Integer> _nodes, Map<GraphEdge, String> _edgeMap) {
    this._nodes = _nodes;
    this._edgeMap = _edgeMap;
    this._filename = _filename;
  }

  private String createNodesTuple () {
    int sz = _nodes.size();
    StringBuilder sb = new StringBuilder();
    sb.append("(");
    for (int i = 0; i < sz; i++) {
      sb.append("b" + i);
      if (i < sz - 1) {
        sb.append(",");
      }
    }
    sb.append(")");
    return sb.toString();
  }

  private String createNodesMatch () {
    StringBuilder sb = new StringBuilder();
    int sz = _nodes.size();
    for (int i = 0; i < sz; i++) {
      sb.append("       | " + i + "n -> b" + i + "\n");
    }
    return sb.toString();
  }


  public String compileAllFaults(boolean doNodes, boolean singlePrefix) {
    StringBuilder sb = new StringBuilder();
    sb.append("include \"" + _filename + "\"\n\n");

    sb.append("let applyNodeFaults u x = \n")
        .append("  mapIf (fun b ->\n")
        .append("    match b with\n")
        .append("    | ").append(createNodesTuple())
        .append("\n")
        .append("     ->\n")
        .append("       (match u with\n")
        .append(createNodesMatch())
        .append("       )) ");
    if (singlePrefix) {
      sb.append("(fun v -> {connected=None; static=None; ospf=None; bgp=None; selected=None;}) x\n\n");
    }
    else {
      sb.append("(fun v -> createDict ({connected=None; static=None; ospf=None; bgp=None; selected=None;}))x\n\n");
    }

    sb.append("let mergeNodeFaults u x y =\n")
        .append("  let x = applyNodeFaults u x in\n")
        .append("  let y = applyNodeFaults u y in\n")
        .append("  combine (fun x y -> merge u x y) x y\n\n");

    sb.append("let transNodeFaults e x = map (fun v -> trans e v) x\n\n");

    sb.append("let initNodeFaults u = \n")
        .append("  let d = createDict (init u) in\n")
        .append("  applyNodeFaults u d\n\n")
        .append("let nodeFaults = solution {init = initNodeFaults; trans = transNodeFaults; merge = mergeNodeFaults;}");

    return sb.toString();
  }
}
