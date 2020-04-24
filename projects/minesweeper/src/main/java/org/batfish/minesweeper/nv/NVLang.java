package org.batfish.minesweeper.nv;

import org.batfish.minesweeper.CommunityVar;

public final class NVLang {

  private NVLang() {}

  public static String mkIf(String guard, String trueBranch, String falseBranch) {
    if (guard.equals("true")) {
      return trueBranch;
    }
    if (guard.equals("false")) {
      return falseBranch;
    }
    if (trueBranch.equals("true")
        && (falseBranch.equals("false") || falseBranch.equals("(false)"))) {
      return guard;
    }
    if (trueBranch.equals("false") && falseBranch.equals("true")) {
      return mkNot(guard);
    }
    if (trueBranch.equals(falseBranch)) {
      return trueBranch;
    }
    if (guard.equals(trueBranch)) {
      return mkIf(guard, "true", falseBranch);
    }
    if (guard.equals(falseBranch)) {
      return mkIf(guard, trueBranch, "false");
    }
    if (trueBranch.equals("true")) {
      return mkOr(guard, falseBranch);
    }
    return "(if " + guard + " then\n" + trueBranch + "\nelse\n" + falseBranch + ")";
  }

  public static String mkAnd(String x, String y) {
    if (x.equals("true")) {
      return y;
    }
    if (y.equals("true")) {
      return x;
    }
    if (x.equals("false") || y.equals("false") || y.equals("(false)")) {
      return "false";
    }
    return "(" + x + " && " + y + ")";
  }

  public static String mkAnd(String[] xs) {
    String acc = "true";
    Boolean nonempty = false;
    for (String x : xs) {
      if (x != null) {
        acc = mkAnd(acc, x);
        nonempty = true;
      }
    }
    if (nonempty) {
      return acc;
    }
    else return null;
  }

  public static String mkOr(String x, String y) {
    if (x.equals("false") || x.equals("(false)")) {
      return y;
    }
    if (y.equals("false") || y.equals("(false)")) {
      return x;
    }
    if (x.equals("true") || y.equals("true")) {
      return "true";
    }
    return "(" + x + " || " + y + ")";
  }

  public static String mkOr(String[] xs) {
    String acc = "false";
    Boolean nonempty = false;
    for (String x : xs) {
      if (x != null) {
        acc = mkOr(acc, x);
        nonempty = true;
      }
    }
    if (nonempty) {
    return acc;
    }
    else return null;
  }

  public static String mkNot(String x) {
    if (x == null) {
      return null;
    }
    if (x.equals("true")) {
      return "false";
    }
    if (x.equals("false")) {
      return "true";
    }
    return "!(" + x + ")";
  }

  public static String mkBool(boolean b) {
    return "" + b;
  }

  public static String mkInt(int i) {
    int mask = ((int) ((Math.pow(2, 32)) -1));
    return "" + (i & mask);
  }

  public static String mkInt(long i, int sz) {
    long mask = ((long) ((Math.pow(2L, sz)) -1L));
    return "" + (i & mask) + "u" + sz;
  }

  public static String mkGe(String x, String y) {
    return "(" + x + " >= " + y + ")";
  }

  public static String mkGe(String x, String y, int sz) {
    return "(" + x + " >=u" + sz + " " + y + ")";
  }

  public static String mkGt(String x, String y) {
    return "(" + x + " > " + y + ")";
  }

  public static String mkLe(String x, String y) {
    return "(" + x + " <= " + y + ")";
  }

  public static String mkLe(String x, String y, int sz) {
    return "(" + x + " <=u" + sz + " " + y + ")";
  }

  public static String mkLt(String x, String y) {
    return "(" + x + " < " + y + ")";
  }

  public static String mkEq(String x, String y) {
    if (x.equals(y)) {
      return "true";
    }
    else {
      return "(" + x + " = " + y + ")";
    }
  }

  public static String mkBitAnd(String x, String y) {
    if (x.equals("0") || x.equals("0u32") || y.equals("0") || y.equals("0u32")) {
      return "0";
    }
    else {
      return "(" + x + " & " + y + ")";
    }
  }

  public static String mkFilter(String pred, String x) {
    return "(filter " + pred + " " + x + ")";
  }


  public static long communityVarToNvValue(CommunityVar cvar) {
    Long l = cvar.getLiteralValue().asBigInt().longValue();
    if (l == null) {
      return 0;
    }
    return l;
  }

  public static String indent(String x, int n) {
    String tabSize = "  ";
    StringBuilder sb = new StringBuilder();
    for (int i = 0; i < n; i++) {
      sb.append(tabSize);
    }
    return sb.append(x).toString();
  }

}
