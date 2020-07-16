package org.batfish.minesweeper.nv.ast;


public enum BinaryArithOp {
 PLUS, GE, LE, LT;

  @Override public String toString() {
    switch (this) {
    case PLUS: return "+";
    case GE: return ">=";
    case LE: return "<=";
    case LT: return "<";
    }
    return "unknown operator";
  }
}
