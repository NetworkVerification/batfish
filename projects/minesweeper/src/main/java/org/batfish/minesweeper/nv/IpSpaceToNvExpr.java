package org.batfish.minesweeper.nv;

import com.google.common.collect.Lists;
import org.batfish.datamodel.AclIpSpace;
import org.batfish.datamodel.AclIpSpaceLine;
import org.batfish.datamodel.EmptyIpSpace;
import org.batfish.datamodel.IpIpSpace;
import org.batfish.datamodel.IpSpaceReference;
import org.batfish.datamodel.IpWildcard;
import org.batfish.datamodel.IpWildcardIpSpace;
import org.batfish.datamodel.IpWildcardSetIpSpace;
import org.batfish.datamodel.LineAction;
import org.batfish.datamodel.PrefixIpSpace;
import org.batfish.datamodel.UniverseIpSpace;
import org.batfish.datamodel.visitors.GenericIpSpaceVisitor;

public class IpSpaceToNvExpr implements GenericIpSpaceVisitor<String> {

  private final String _var;

  public IpSpaceToNvExpr(String var) {
    _var = var;
  }

  @Override
  public String castToGenericIpSpaceVisitorReturnType(Object o) {
    return (String) o;
  }

  @Override
  public String visitAclIpSpace(AclIpSpace aclIpSpace) {
    String expr = NVLang.mkBool(false);
    for (AclIpSpaceLine aclIpSpaceLine : Lists.reverse(aclIpSpace.getLines())) {
      String matchExpr = aclIpSpaceLine.getIpSpace().accept(this);
      String actionExpr =
          aclIpSpaceLine.getAction() == LineAction.PERMIT ? NVLang.mkBool(true) : NVLang.mkBool(false);
      expr = NVLang.mkIf(matchExpr, actionExpr, expr);
    }
    return expr;
  }

  @Override
  public String visitEmptyIpSpace(EmptyIpSpace emptyIpSpace) {
    return NVLang.mkBool(false);
  }

  @Override
  public String visitIpIpSpace(IpIpSpace ipIpSpace) {
    return NVLang.mkEq(_var, NVLang.mkInt(ipIpSpace.getIp().asLong(), 32));
  }

  @Override
  public String visitIpWildcardIpSpace(IpWildcardIpSpace ipWildcardIpSpace) {
    return toNvExpr(ipWildcardIpSpace.getIpWildcard());
  }

  private String toNvExpr(IpWildcard ipWildcard) {
//    System.out.println("ip: " + ipWildcard.getIp().asLong() );
//    String ip = NVLang.mkInt(ipWildcard.getIp().asLong(), 32);
//    System.out.println("wildcardmask: " + ipWildcard.getWildcardMask());
//    System.out.println("~wildcardmask: " + (~ipWildcard.getWildcardMask()));
    String mask = NVLang.mkInt(~ipWildcard.getWildcardMask(), 32);
    long filter = ipWildcard.getIp().asLong() & (~ipWildcard.getWildcardMask());
    return NVLang.mkEq(NVLang.mkBitAnd(_var, mask), NVLang.mkInt(filter, 32));
  }

  @Override
  public String visitIpWildcardSetIpSpace(IpWildcardSetIpSpace ipWildcardSetIpSpace) {
    String whitelistExpr =
        NVLang.mkOr(
            ipWildcardSetIpSpace.getWhitelist().stream()
                .map(this::toNvExpr)
                .toArray(String[]::new));

    String blacklistExpr =
      NVLang.mkOr(
            ipWildcardSetIpSpace.getBlacklist().stream()
                .map(this::toNvExpr)
                .toArray(String[]::new));

    return NVLang.mkAnd(whitelistExpr, NVLang.mkNot(blacklistExpr));
  }

  @Override
  public String visitPrefixIpSpace(PrefixIpSpace prefixIpSpace) {
    return toNvExpr(IpWildcard.create(prefixIpSpace.getPrefix()));
  }

  @Override
  public String visitUniverseIpSpace(UniverseIpSpace universeIpSpace) {
    return NVLang.mkBool(true);
  }

  @Override
  public String visitIpSpaceReference(IpSpaceReference ipSpaceReference) {
    throw new UnsupportedOperationException("no implementation for generated method");
  }
}

