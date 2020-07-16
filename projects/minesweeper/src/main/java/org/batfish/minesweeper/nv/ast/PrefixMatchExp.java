package org.batfish.minesweeper.nv.ast;

import com.microsoft.z3.Context;
import com.microsoft.z3.Expr;
import java.util.Objects;
import org.batfish.datamodel.Ip;

class PrefixMatchExp extends Exp {
  public Exp lenVar;   // The length variable
  public Exp ipVar;   // The address part of the prefix variable
  public int lower;  // Lower prefix-length
  public int upper; // Upper prefix-length
  public long ip;  // Address part of prefix
  public int len; // Length part of prefix

  public PrefixMatchExp(Exp lenVar, Exp ipVar, int lower, int upper, long ip, int len) {
    this.lenVar = lenVar;
    this.ipVar = ipVar;
    this.lower = lower;
    this.upper = upper;
    this.ip = ip;
    this.len = len;
  }

  @Override public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (!(o instanceof PrefixMatchExp)) {
      return false;
    }
    PrefixMatchExp that = (PrefixMatchExp) o;
    return lower == that.lower && upper == that.upper && ip == that.ip && lenVar.equals(that.lenVar)
        && ipVar.equals(that.ipVar);
  }

  @Override public int hashCode() {
    return Objects.hash(lenVar, ipVar, lower, upper, ip);
  }

  private Exp firstBitsEqual(Exp x, long y, int n) {
    assert (n >= 0 && n <= 32);
    if (n == 0) {
      return new BoolExp(true);
    }
    Exp lower = new IpExp(Ip.create(y));
    Exp upper = new IpExp(Ip.create(y + (int) Math.pow(2, 32 - n)));
    return NVExpBuilder.mkAnd(NVExpBuilder.mkGe(x, lower), NVExpBuilder.mkLt(x, upper));
  }

    /*
     * Check if a prefix range match is applicable for the packet destination
     * Ip address, given the prefix length variable.
     */
    private Exp prefixMatchDecompose() {
      Exp lowerBitsMatch = firstBitsEqual(this.ipVar, ip, len);
      if (lower == upper) {
        Exp equalLen = NVExpBuilder.mkEq(lenVar, NVExpBuilder.mkInt(lower,6));
        return NVExpBuilder.mkAnd(equalLen, lowerBitsMatch);
      } else {
        Exp lengthLowerBound = NVExpBuilder.mkGe(lenVar, NVExpBuilder.mkInt(lower,6), 6);
        Exp lengthUpperBound = NVExpBuilder.mkLe(lenVar, NVExpBuilder.mkInt(upper,6), 6);
        return NVExpBuilder.mkAnd(lengthLowerBound, NVExpBuilder.mkAnd(lengthUpperBound, lowerBitsMatch));
      }
    }

  @Override public Expr toSmt(Context ctx) {
      Exp e = prefixMatchDecompose();
      return e.toSmt(ctx);
  }


  @Override public String toString() {
    String isRel = prefixMatchDecompose().toString();
    if (isRel.startsWith("(")) {
      return isRel;
    }
    else return "(" + isRel + ")";
  }

}
