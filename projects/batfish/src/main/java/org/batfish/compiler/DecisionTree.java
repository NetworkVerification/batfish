package org.batfish.compiler;

import java.util.HashSet;
import java.util.LinkedList;
import java.util.function.Consumer;
import java.util.function.Predicate;
import javax.annotation.Nullable;
import org.batfish.datamodel.routing_policy.expr.BooleanExpr;
import java.util.Set;
import org.batfish.symbolic.utils.Tuple;
import java.util.stream.Stream;

public class DecisionTree<T> {

  private Node<T> _root;

  private Set<Node<T>> _leafs;

  public DecisionTree(Node _root, Set<Node<T>> _leafs) {
    this._root = _root;
    this._leafs = _leafs;
  }

  public DecisionTree(Node _root) {
    this._root = _root;
    this._leafs = new HashSet<>();
    this._leafs.add(_root);
  }

  public void mapLeafs(Consumer<Node<T>> f) {
    _leafs.forEach(f);
  }

  /* Assumes that the leaves of the tree t are a subset of the this tree (which is true for our use case),
     so that we don't have to worry about merging leaves */
  public void mergeTrees( @Nullable DecisionTree<T> t, T equalVal) {
    if (t != null)
    {
      for (Node<T> leaf : _leafs) {
        if (leaf.getData().equals(equalVal)) {
          Node<T> root = t._root;
          for (Tuple<Node, Boolean> parent : leaf.getParents()) {
            parent.getFirst().setChild(root, parent.getSecond());
          }
        }
      }
    }
  }

  public void mergeAtLeaf(@Nullable DecisionTree<T> t, Node<T> targetLeaf) {
    if (t != null) {
      if (_leafs.contains(targetLeaf)) {
        Node<T> root = t._root;
        for (Tuple<Node, Boolean> parent : targetLeaf.getParents()) {
          parent.getFirst().setChild(root, parent.getSecond());
        }
        _leafs.remove(targetLeaf);
      }
    }
  }

  public Node<T> getRoot() {
    return _root;
  }

  public void setRoot(Node root) {
    this._root = root;
  }

  public Set<Node<T>> getLeafs() {
    return _leafs;
  }

  public void setLeafs(Set<Node<T>> leafs) {
    this._leafs = leafs;
  }

}


