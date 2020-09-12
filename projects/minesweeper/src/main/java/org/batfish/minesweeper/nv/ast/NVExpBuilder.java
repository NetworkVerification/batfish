package org.batfish.minesweeper.nv.ast;



/* Helper functions used to build NV expressions. It's recommended to use these functions instead of
   the constructors directly, as they implement various optimizations too.
 */

public final class NVExpBuilder {

  public static boolean isTrue(Exp e) {
    return ((e instanceof BoolExp)) && ((BoolExp) e).getValue();
  }

  public static boolean isFalse(Exp e) {
    return ((e instanceof BoolExp)) && !((BoolExp) e).getValue();
  }

  public static Exp mkBool(Boolean b) {
    return (new BoolExp(b));
  }

  public static Exp mkNot(Exp x) {
    if (x == null) {
      return null;
    }
    if (isTrue(x)) {
      return (new BoolExp(false));
    }
    if (isFalse(x)) {
      return (new BoolExp(true));
    }
    return (new NotExp(x));
  }

  public static Exp mkAnd(Exp l, Exp r) {
    if (isTrue(l)) {
      return r;
    }
    if (isTrue(r)) {
      return l;
    }
    if (isFalse(r) || isFalse(l)) {
      return new BoolExp(false);
    }
    if (r.equals(l)) {
      return r;
    }
    return new BinaryBoolExp(BinaryBoolOp.AND, l, r);
  }

  public static Exp mkOr(Exp l, Exp r) {
    if (isFalse(l)) {
      return r;
    }
    if (isFalse(r)) {
      return l;
    }
    if (isTrue(r) || isTrue(l)) {
      return new BoolExp(true);
    }
    if (r.equals(l)) {
      return r;
    }
    return new BinaryBoolExp(BinaryBoolOp.OR, l, r);
  }

  public static Exp mkIf(Exp g, Exp t, Exp f) {
    if (isTrue(g)) {
      return t;
    }
    if (isFalse(g)) {
      return f;
    }
    if (isTrue(t) && isFalse(f)) {
      return g;
    }
    if (isFalse(t) && isTrue(f)) {
      return mkNot(g);
    }
    if (t.equals(f)) {
      return t;
    }
    if (g.equals(t) || isTrue(t)) {
      return mkOr(g, f);
    }
    if (g.equals(f)) {
      return mkAnd(g, t);
    }
    /* Because this case seems to happen often we special-case it.
       if g then
         if g then
           e1
         else
           e2
        else e3 ~>
        if g then e1
        else e3
     */
    if (t instanceof IfThenElseExp) {
      IfThenElseExp texpr = (IfThenElseExp) t;
      if (texpr.getGuard().equals(g)) {
        return mkIf(g, texpr.getTrueBranch(), f);
      }
    }
    return new IfThenElseExp(g, t, f);
  }

  public static Exp mkInt(int i) {
    int mask = ((int) ((Math.pow(2, 32)) - 1));
    return new IntegerExp(i & mask, 32);
  }

  public static Exp mkInt(long i, int sz) {
    long mask = ((long) ((Math.pow(2L, sz)) -1L));
    return new IntegerExp(i & mask, sz);
  }

  public static Exp mkPrefixMatch(Exp lenVar, Exp ipVar, int lower, int upper, long ip, int len) {
    return new PrefixMatchExp(lenVar, ipVar, lower, upper, ip, len);
  }

  public static Exp mkGe(Exp l, Exp r) {
    return new ArithExp(BinaryArithOp.GE, l, r,32 );
  }

  public static Exp mkGe(Exp l, Exp r, int sz) {
    return new ArithExp(BinaryArithOp.GE, l, r, sz );
  }

  public static Exp mkLe(Exp l, Exp r) {
    return new ArithExp(BinaryArithOp.LE, l, r,32 );
  }

  public static Exp mkLe(Exp l, Exp r, int sz) {
    return new ArithExp(BinaryArithOp.LE, l, r, sz );
  }

  public static Exp mkLt(Exp l, Exp r) {
    return new ArithExp(BinaryArithOp.LT, l, r,32 );
  }

  public static Exp mkLt(Exp l, Exp r, int sz) {
    return new ArithExp(BinaryArithOp.LT, l, r, sz );
  }

  public static Exp mkEq(Exp l, Exp r) {
    return new BinaryBoolExp(BinaryBoolOp.EQ, l, r);
  }

}
