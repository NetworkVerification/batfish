package org.batfish.compiler;

import java.util.LinkedList;
import java.util.List;
import java.util.function.Predicate;
import org.batfish.datamodel.routing_policy.expr.BooleanExpr;
import org.batfish.symbolic.utils.Tuple;

public class Node<T> {
  private Node _left;
  private Node _right;
  private T _data;
  private BooleanExpr _expr;
  //Parent nodes of this node, false represents left, true right.
  //Invariant: we don't keep track of parents for leaves.
  private List<Tuple<Node, Boolean>> _parents;
  private boolean _leaf; // This language has no sum types so I'll do it the ugly way...

  /* Constructor for leafs */
  Node(T value) {
    this._data = value;
    this._left = null;
    this._right = null;
    this._parents = null;
    this._expr = null;
    this._leaf = true;
  }

  Node(BooleanExpr expr) {
    this._left = null;
    this._right = null;
    this._data = null;
    this._expr = expr;
    this._parents = null;
    this._leaf = false;
  }

  /* Sets the left or right child (depending on lr) of head to c. This method needs
     to:
     1. Remove this node from the parent nodes of the node at lr, if any.
     2. Set c to the lr child of this node.
     3. Set this node in the parents list of c.
   */
  public void setChild(Node c, boolean lr) {
    // First see if there is already a node in this position.
    Node head = this;
    Node current = lr ? head._right : head._left;

    // if there is, then remove head from its parents
    if (current != null) {
      Predicate<Tuple<Node,Boolean>> isCurrent = p-> p.getFirst() == current;
      if (!current._parents.removeIf(isCurrent)) {
        System.out.println("Failed to remove parent");
      }
    }

    // add c to lr position of head
    if (lr) {
      head._right = c;
    } else {
      head._left = c;
    }

    // add head to c parents.
    Tuple<Node, Boolean> parent = new Tuple<>(head, lr);
    if (c._parents == null) {
      c._parents = new LinkedList();
      c._parents.add(parent);
    }
    else {
      c._parents.add(parent);
    }
  }

  public Node getLeft() {
    return _left;
  }

  public void setLeft(Node left) {
    this._left = left;
  }

  public Node getRight() {
    return _right;
  }

  public void setRight(Node right) {
    this._right = right;
  }

  public T getData() {
    return _data;
  }

  public void setData(T data) {
    this._data = data;
  }

  public BooleanExpr getExpr() {
    return _expr;
  }

  public void setExpr(BooleanExpr expr) {
    this._expr = expr;
  }

  public List<Tuple<Node, Boolean>> getParents() {
    return _parents;
  }

  public void setParents(List<Tuple<Node, Boolean>> parents) {
    this._parents = parents;
  }

  public boolean isLeaf() {
    return _leaf;
  }

  public void setLeaf(boolean leaf) {
    this._leaf = leaf;
  }
}
