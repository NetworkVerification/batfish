package org.batfish.compiler;

import java.lang.reflect.Array;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;
import javax.annotation.Nullable;
import org.batfish.datamodel.routing_policy.expr.MatchPrefixSet;
import org.batfish.symbolic.utils.Tuple;

public class DecisionTree<T> {

  private Node<T> _root;

  private Set<Node<T>> _leafs;
  private Set<Node<T>> _allNodes;
  private Set<Node<T>> _prefixNodes;

  public DecisionTree(Node<T> _root, Set<Node<T>> _leafs) {
    this._root = _root;
    this._leafs = _leafs;
    this._prefixNodes = new HashSet<>();
    this._allNodes = new HashSet<>();
    if (_root.getExpr() != null && (_root.getExpr() instanceof MatchPrefixSet)) {
      _prefixNodes.add(_root);
    }
    _allNodes.add(_root);
    _allNodes.addAll(_leafs);
  }

  public DecisionTree(Node<T> _root) {
    this._root = _root;
    this._prefixNodes = new HashSet<>();
    this._leafs = new HashSet<>();
    this._allNodes = new HashSet<>();
    this._leafs.add(_root);
    this._allNodes.add(_root);
  }

  public void mergeAtLeaf(@Nullable DecisionTree<T> t, Node<T> targetLeaf) {
    if (t != null) {
      if (_leafs.contains(targetLeaf)) {
        Node<T> root = t._root;
        List<Tuple<Node<T>, Boolean>> parents = new ArrayList<>();
        parents.addAll(targetLeaf.getParents());
        int sz = parents.size();
        System.out.print("targetLeaf: " + targetLeaf + " has parents: ");
        for (int i=0; i < sz; i++) {
          System.out.print(parents.get(i) + ",");
        }
        System.out.println("");
        for (int i=0; i < sz; i++) {
          Tuple<Node<T>, Boolean> parent = parents.get(i);
          //System.out.println("Checking parent: " + parent.getFirst());
          if (_allNodes.contains(parent.getFirst())) {
            /*System.out.println("Setting parent: " + parent.getFirst());
            System.out.println("To: ");
            t.printTree();
            System.out.println("---------");*/
            parent.getFirst().setChild(root, parent.getSecond());
          }
        }
        // Remove targetLeaf from the list of leafs.
        _leafs.remove(targetLeaf);
        _allNodes.remove(targetLeaf);
        // Add te leafs of t to the list of leafs.
        _leafs.addAll(t.getLeafs());
        _allNodes.addAll(t._allNodes);
        _prefixNodes.addAll(t._prefixNodes);
      }
    }
  }


  public Set<Node<T>> getPrefixNodes() {
    return _prefixNodes;
  }

  public void setPrefixNodes(Set<Node<T>> prefixNodes) {
    this._prefixNodes = prefixNodes;
  }

  public Node<T> getRoot() {
    return _root;
  }

  public void setRoot(Node<T> root) {
    this._root = root;
  }

  public Set<Node<T>> getLeafs() {
    return _leafs;
  }

  public void setLeafs(Set<Node<T>> leafs) {
    this._leafs = leafs;
  }

  public Set<Node<T>> getAllNodes() {
    return _allNodes;
  }

  public void setAllNodes(Set<Node<T>> allNodes) {
    this._allNodes = allNodes;
  }

  private void printTreeAux (Node<T> head, int i) {
    if (_leafs.contains(head)) {
      System.out.println("Leaf: " + head.getData() + "," + head + " @ " + i + " with " + head.getEnv().getData().toString() + "," + head.getEnv().getData().get_communities());
    } else
    {
      System.out.println("Expr, Node: " + head.getExpr() + "," + head + " " + i);
      System.out.println("Left " + i + ":");
      printTreeAux(head.getLeft(),i+1);
      System.out.println("Right " + i + ":");
      printTreeAux(head.getRight(), i+1);
    }
  }
  public void printTree() {
    System.out.println("Nodes: ");
    _allNodes.forEach(n -> System.out.println(n + ", "));
    System.out.println("-----");
    printTreeAux(this._root, 0);

  }

}


