package org.batfish.compiler;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;
import org.batfish.datamodel.routing_policy.expr.BooleanExpr;
import org.batfish.symbolic.utils.Tuple;

public class Node<T> {
  private Node<T> _left;
  private Node<T> _right;
  private T _data;
  private BooleanExpr _expr;
  private TransferParam<Environment> _env;
  //Parent nodes of this node, false represents left, true right.
  //Invariant: we don't keep track of parents for leaves.
  private List<Tuple<Node<T>, Boolean>> _parents;
  private boolean _export; // Is this an export or import node.

  /* Constructor for leafs */
  Node(T value, TransferParam<Environment> env, boolean export) {
    this._data = value;
    this._left = null;
    this._right = null;
    this._env = env;
    this._parents = null;
    this._expr = null;
    this._export = export;
  }

  Node(BooleanExpr expr, TransferParam<Environment> env, boolean export) {
    this._left = null;
    this._right = null;
    this._data = null;
    this._env = env;
    this._expr = expr;
    this._parents = null;
    this._export = export;
  }

  Node(Node<T> p, TransferParam<Environment> env) {
    this._left = p._left;
    this._right = p._right;
    this._data = p._data;
    this._env = env;
    this._expr = p._expr;
    this._parents = p._parents;
    this._export = p._export;
  }


  /* Sets the left or right child (depending on lr) of head to c. This method needs
     to:
     1. Remove this node from the parent nodes of the node at lr, if any.
     2. Set c to the lr child of this node.
     3. Set this node in the parents list of c.
   */
  public void setChild(Node<T> c, boolean lr) {
    // First see if there is already a node in this position.
    Node<T> head = this;
    Node<T> current = lr ? head._right : head._left;
    System.out.println("Current is:" + current);
    // if there is, then remove head from its parents
    if (current != null) {
      Predicate<Tuple<Node<T>,Boolean>> isCurrent = p-> p.getFirst() == head;
      System.out.println("Parents to consider for removal: ");
      current._parents.forEach(p -> System.out.println(p));
      if (!current._parents.removeIf(isCurrent)) {
        System.out.println("Failed to remove parent");
      }
    }

    // add c to lr position of head, but as an optimization
    // if c.expr=head.expr then add the lr child of c instead.
    boolean opt = (c.getExpr() != null) && (c.getExpr().equals(head.getExpr())) && (c.getEnv().equals(head.getEnv()));
    if (opt) {
      c = lr ? c.getRight() : c.getLeft();
    }

    if (lr) {
      head._right = c;
    } else {
      head._left = c;
    }

    // add head to c parents.
    Tuple<Node<T>, Boolean> parent = new Tuple<>(head, lr);
    if (c._parents == null) {
      c._parents = new ArrayList<>();
      c._parents.add(parent);
    }
    else {
      c._parents.add(parent);
    }
  }

  public Node<T> getLeft() {
    return _left;
  }

  public void setLeft(Node<T> left) {
    this._left = left;
  }

  public Node<T> getRight() {
    return _right;
  }

  public void setRight(Node<T> right) {
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

  public List<Tuple<Node<T>, Boolean>> getParents() {
    return _parents;
  }

  public void setParents(List<Tuple<Node<T>, Boolean>> parents) {
    this._parents = parents;
  }

  public boolean getExport() {
    return _export;
  }

  public void setExport(boolean export) {
    this._export = export;
  }

  public TransferParam<Environment> getEnv() {
    return _env;
  }

  public void setEnv(TransferParam<Environment> env) {
    this._env = env;
  }

  @Override public boolean equals(Object obj) {
    return (this == obj) || ((obj instanceof Node) &&
            (this._left == ((Node) obj)._left) &&
            (this._right == ((Node) obj)._right) &&
            (this._data.equals(((Node) obj)._data)) &&
            (this._env.equals(((Node) obj)._env)) &&
            (this._expr.equals(((Node) obj)._expr)) &&
            (this._parents.equals(((Node) obj)._parents)) &&
            (this._export == ((Node) obj)._export));
  }

  @Override public int hashCode() {
    return super.hashCode();
  }

 /* public Node<T> deepCopy () {
    Node<T> fresh = new Node<>(this._expr, this._env, this._export);

  } */

}
