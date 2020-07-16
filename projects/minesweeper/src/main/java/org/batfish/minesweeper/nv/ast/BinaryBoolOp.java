package org.batfish.minesweeper.nv.ast;


public enum BinaryBoolOp {
  AND, OR, EQ;

  @Override public String toString() {
    switch (this) {
    case EQ: return "=";
    case AND: return "&&";
    case OR: return "||";
    }
    return "unknown operator";
  }
}
