package org.batfish.minesweeper.nv;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import javax.annotation.Nullable;
import org.batfish.datamodel.routing_policy.expr.BooleanExpr;
import org.batfish.datamodel.routing_policy.expr.MatchPrefixSet;
import org.batfish.minesweeper.utils.Tuple;

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
        if (targetLeaf.getParents() != null) {
          parents.addAll(targetLeaf.getParents());
        }
        int sz = parents.size();
        /*System.out.print("targetLeaf: " + targetLeaf + " has parents: ");
        for (int i=0; i < sz; i++) {
          System.out.print(parents.get(i) + ",");
        }
        System.out.println("");*/
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

  private void optimizeAux(Node<T> t) {
    if (t.getLeft() != null && t.getLeft().getExpr() != null &&
        t.getLeft().getExpr().equals(t.getExpr())) {
      t.setChild(t.getLeft().getLeft(), false);
    }
    if (t.getRight() != null && t.getRight().getExpr() != null && t.getRight().getExpr().equals(t.getExpr())) {
      t.setChild(t.getRight().getRight(), true);
    }
    if (t.getLeft() != null) {
      optimizeAux(t.getLeft());
    }
    if (t.getRight() != null) {
      optimizeAux(t.getRight());
    }
  }

  public void optimize() {
    optimizeAux(_root);
    removeDuplicatesAux(_root, new HashSet<>(), new HashSet<>());
  }



  /* Traverses the tree and if a BooleanExpr condition is seen twice then it removes the second occurrence
     simplifying the tree structure and the resulting condition.
     trueSet keeps track of the nodes (expressions) that are true in this part of the tree
     and falseSet does the same for false expressions.
     NOTE: There is a "problem" with this function. Batfish expressions that may end up being the same
     in NV, are not the same as Batfish expressions (e.g., NamedPrefixSet vs PrefixSet). In this sense,
     it's not optimal.
   */
  private void removeDuplicatesAux(Node<T> t, Set<BooleanExpr> trueSet, Set<BooleanExpr> falseSet) {

    if (t == null) {
      return ;
    }

    // If the condition of t has already been chosen as true
    if (t.getExpr() != null && trueSet.contains(t.getExpr())) {
      // Replace t with it's right child.

      // Keep a copy
      Node<T> tright = t.getRight();

      // Always copy parents list for safety...
      List<Tuple<Node<T>, Boolean>> parents = new ArrayList<>();
      if (t.getParents() != null) {
        parents.addAll(t.getParents());
      }
      int sz = parents.size();
      for (int i = 0; i < sz; i++) {
        Tuple<Node<T>, Boolean> ps = parents.get(i);
        ps.getFirst().setChild(t.getRight(),ps.getSecond());
      }

      // Call recursively.
      removeDuplicatesAux(tright, trueSet, falseSet);
      removeDuplicatesAux(t.getLeft(), trueSet, falseSet);
    }

    // Respectively for false
    if (t.getExpr() != null && falseSet.contains(t.getExpr())) {
      // Replace t with it's left child.

      // Keep a copy
      Node<T> tleft = t.getLeft();

      // Always copy parents list for safety...
      List<Tuple<Node<T>, Boolean>> parents = new ArrayList<>();
      if (t.getParents() != null) {
        parents.addAll(t.getParents());
      }
      int sz = parents.size();
      for (int i = 0; i < sz; i++) {
        Tuple<Node<T>, Boolean> ps = parents.get(i);
        ps.getFirst().setChild(t.getLeft(),ps.getSecond());
      }

      // Call recursively
      removeDuplicatesAux(t.getRight(), trueSet, falseSet);
      removeDuplicatesAux(t.getLeft(), trueSet, falseSet);
    }

    //Otherwise do not replace anything and call recursively
    // But first update true and false sets.
    Set<BooleanExpr> newTrueSet= new HashSet<BooleanExpr>();
    newTrueSet.add(t.getExpr());
    newTrueSet.addAll(trueSet);
    removeDuplicatesAux(t.getRight(), newTrueSet, falseSet);

    Set<BooleanExpr> newFalseSet= new HashSet<BooleanExpr>();
    newFalseSet.add(t.getExpr());
    newFalseSet.addAll(falseSet);
    removeDuplicatesAux(t.getLeft(), trueSet, newFalseSet);
  }

  private void printTreeParentsAux (Node<T> head, int i) {
    if (_leafs.contains(head)) {
      System.out.println("Leaf: " + head.toString() + " parents:" + head.getParents().toString());
    } else
    {
      if (_prefixNodes.contains(head)) {
        System.out.println("Prefix Node: " + head.toString() + " parents:" +
            (head.getParents() != null ? head.getParents().toString() : "[]")  + i);
      }
      else {
        System.out.println("Node: " + head.toString() + " parents:" + (head.getParents() != null
            ? head.getParents().toString()
            : "[]") + i);
      }
      System.out.println("Left " + i + ":");
      printTreeParentsAux(head.getLeft(),i+1);
      System.out.println("Right " + i + ":");
      printTreeParentsAux(head.getRight(), i+1);
    }
  }
  public void printTreeParents () {
    printTreeParentsAux(this._root, 0);
  }


}


