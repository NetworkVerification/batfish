package org.batfish.minesweeper.nv;

import com.google.common.collect.Lists;
import java.util.List;
import java.util.Set;
import javax.annotation.Nullable;
import org.batfish.common.BatfishException;
import org.batfish.datamodel.EmptyIpSpace;
import org.batfish.datamodel.HeaderSpace;
import org.batfish.datamodel.IpAccessList;
import org.batfish.datamodel.IpAccessListLine;
import org.batfish.datamodel.IpProtocol;
import org.batfish.datamodel.IpSpace;
import org.batfish.datamodel.LineAction;
import org.batfish.datamodel.SubRange;
import org.batfish.datamodel.TcpFlagsMatchConditions;
import org.batfish.datamodel.acl.AclLineMatchExpr;
import org.batfish.datamodel.acl.AndMatchExpr;
import org.batfish.datamodel.acl.FalseExpr;
import org.batfish.datamodel.acl.GenericAclLineMatchExprVisitor;
import org.batfish.datamodel.acl.MatchHeaderSpace;
import org.batfish.datamodel.acl.MatchSrcInterface;
import org.batfish.datamodel.acl.NotMatchExpr;
import org.batfish.datamodel.acl.OrMatchExpr;
import org.batfish.datamodel.acl.OriginatingFromDevice;
import org.batfish.datamodel.acl.PermittedByAcl;
import org.batfish.datamodel.acl.TrueExpr;

public class IpAccessListToNvExpr implements GenericAclLineMatchExprVisitor<String> {

  private final Packet _packet;

  public IpAccessListToNvExpr() {
    _packet = new Packet();
  }

  private String toNvExpr(AclLineMatchExpr matchExpr) {
    return matchExpr.accept(this);
  }

  public String toNvExpr(IpAccessList ipAccessList) {
    String expr = NVLang.mkBool(false);
    for (IpAccessListLine line : Lists.reverse(ipAccessList.getLines())) {
      String matchExpr = line.getMatchCondition().accept(this);
      String actionExpr =
          line.getAction() == LineAction.PERMIT ? NVLang.mkBool(true) : NVLang.mkBool(false);
      expr = NVLang.mkIf(matchExpr, actionExpr, expr);
    }
    return expr;
  }

  private @Nullable String toNvExpr(@Nullable IpSpace ipSpace, String var) {
    if (ipSpace == null) {
      return null;
    }
    return ipSpace.accept(new IpSpaceToNvExpr(var));
  }

  private @Nullable String toNvExpr(Set<IpProtocol> ipProtocols) {
    if (ipProtocols == null || ipProtocols.isEmpty()) {
      return null;
    }

    return NVLang.mkOr(
        ipProtocols.stream()
            .map(
                ipProtocol ->
                    NVLang.mkEq(_packet.getIpProtocol(), NVLang.mkInt(ipProtocol.number(), 8)))
            .toArray(String[]::new));
  }

  private @Nullable String toNvExpr(@Nullable Set<SubRange> ranges, Long var) {
    if (ranges == null || ranges.isEmpty()) {
      return null;
    }
    return NVLang.mkOr(
        ranges.stream().map(range -> toNvExpr(range, var)).toArray(String[]::new));
  }

  private String toNvExpr(SubRange range, Long var) {
    int start = range.getStart();
    int end = range.getEnd();
    String startExpr = NVLang.mkInt(start);
    String endExpr = NVLang.mkInt(end);
    return start == end
        ? NVLang.mkEq(NVLang.mkInt(var, 32), startExpr)
        : NVLang.mkAnd(NVLang.mkGe(NVLang.mkInt(var, 32), startExpr), NVLang.mkLe(NVLang.mkInt(var, 32), endExpr));
  }

  private String toNvExpr(List<TcpFlagsMatchConditions> tcpFlags) {
    if (tcpFlags == null || tcpFlags.isEmpty()) {
      return null;
    }

    return NVLang.mkOr(tcpFlags.stream().map(this::toNvExpr).toArray(String[]::new));
  }

  /** For TcpFlagsMatchConditions */
  private String toNvExpr(boolean useFlag, boolean flagValue, String flagExpr) {
    return useFlag ? flagValue ? flagExpr : NVLang.mkNot(flagExpr) : null;
  }

  private String toNvExpr(TcpFlagsMatchConditions tcpFlags) {
    if (!tcpFlags.anyUsed()) {
      return null;
    }

    return NVLang.mkBool(true);
    /*TODO: implement tcp flags */
    /*return _boolExprOps.and(
        toBoolExpr(tcpFlags.getUseAck(), tcpFlags.getTcpFlags().getAck(), _packet.getTcpAck()),
        toBoolExpr(tcpFlags.getUseCwr(), tcpFlags.getTcpFlags().getCwr(), _packet.getTcpCwr()),
        toBoolExpr(tcpFlags.getUseEce(), tcpFlags.getTcpFlags().getEce(), _packet.getTcpEce()),
        toBoolExpr(tcpFlags.getUseFin(), tcpFlags.getTcpFlags().getFin(), _packet.getTcpFin()),
        toBoolExpr(tcpFlags.getUsePsh(), tcpFlags.getTcpFlags().getPsh(), _packet.getTcpPsh()),
        toBoolExpr(tcpFlags.getUseRst(), tcpFlags.getTcpFlags().getRst(), _packet.getTcpRst()),
        toBoolExpr(tcpFlags.getUseSyn(), tcpFlags.getTcpFlags().getSyn(), _packet.getTcpSyn()),
        toBoolExpr(tcpFlags.getUseUrg(), tcpFlags.getTcpFlags().getUrg(), _packet.getTcpUrg()));*/
  }

  private static <T> void forbidHeaderSpaceField(String fieldName, Set<T> fieldValue) {
    if (fieldValue != null && !fieldValue.isEmpty()) {
      throw new BatfishException("unsupported HeaderSpace field " + fieldName);
    }
  }

  private static void forbidHeaderSpaceField(String fieldName, IpSpace fieldValue) {
    if (fieldValue != null && fieldValue != EmptyIpSpace.INSTANCE) {
      throw new BatfishException("unsupported HeaderSpace field " + fieldName);
    }
  }

  @Override
  public String visitAndMatchExpr(AndMatchExpr andMatchExpr) {
    return NVLang.mkAnd(
        andMatchExpr.getConjuncts().stream().map(this::toNvExpr).toArray(String[]::new));
  }

  @Override
  public String visitFalseExpr(FalseExpr falseExpr) {
    return NVLang.mkBool(false);
  }

  @Override
  public String visitMatchHeaderSpace(MatchHeaderSpace matchHeaderSpace) {
    HeaderSpace headerSpace = matchHeaderSpace.getHeaderspace();
    forbidHeaderSpaceField("dscps", headerSpace.getDscps());
    forbidHeaderSpaceField("ecns", headerSpace.getEcns());
    forbidHeaderSpaceField("fragmentOffsets", headerSpace.getFragmentOffsets());
    forbidHeaderSpaceField("notDscps", headerSpace.getNotDscps());
    forbidHeaderSpaceField("notEcns", headerSpace.getNotEcns());
    forbidHeaderSpaceField("notFragmentOffsets", headerSpace.getNotFragmentOffsets());
    forbidHeaderSpaceField("notIcmpCodes", headerSpace.getNotIcmpCodes());
    forbidHeaderSpaceField("notIcmpTypes", headerSpace.getNotIcmpTypes());
    forbidHeaderSpaceField("notIpProtocols", headerSpace.getNotIpProtocols());
    forbidHeaderSpaceField("srcOrDstIps", headerSpace.getSrcOrDstIps());
    forbidHeaderSpaceField("srcOrDstPorts", headerSpace.getSrcOrDstPorts());
    forbidHeaderSpaceField("srcOrDstProtocols", headerSpace.getSrcOrDstProtocols());
    forbidHeaderSpaceField("states", headerSpace.getStates());

//    System.out.println(toNvExpr(headerSpace.getDstIps(), _packet.getDstIp()));
//    System.out.println(toNvExpr(headerSpace.getSrcIps(), _packet.getSrcIp()));

    String expr =
        NVLang.mkAnd(new String[]
            {toNvExpr(headerSpace.getDstIps(), _packet.getDstIp()),
                toNvExpr(headerSpace.getSrcIps(), _packet.getSrcIp()),
                NVLang.mkNot(toNvExpr(headerSpace.getNotDstIps(), _packet.getDstIp())),
                NVLang.mkNot(toNvExpr(headerSpace.getNotSrcIps(), _packet.getSrcIp())),
                toNvExpr(headerSpace.getIpProtocols())});

    // TODO:Add support for more fields.
    /*toBoolExpr(headerSpace.getDstPorts(), _packet.getDstPort()),
            toBoolExpr(headerSpace.getSrcPorts(), _packet.getSrcPort()),

            not(toBoolExpr(headerSpace.getNotDstPorts(), _packet.getDstPort())),

            not(toBoolExpr(headerSpace.getSrcPorts(), _packet.getSrcPort())),
            toBoolExpr(headerSpace.getTcpFlags()),
            toBoolExpr(headerSpace.getIcmpCodes(), _packet.getIcmpCode()),
            toBoolExpr(headerSpace.getIcmpTypes(), _packet.getIcmpType()), */

    return headerSpace.getNegate() ? NVLang.mkNot(expr) : expr;
  }

  @Override
  public String visitMatchSrcInterface(MatchSrcInterface matchSrcInterface) {
    throw new BatfishException("MatchSrcInterface is not supported");
  }

  @Override
  public String visitNotMatchExpr(NotMatchExpr notMatchExpr) {
    return NVLang.mkNot(notMatchExpr.getOperand().accept(this));
  }

  @Override
  public String visitOriginatingFromDevice(OriginatingFromDevice originatingFromDevice) {
    throw new BatfishException("OriginatingFromDevice is not supported");
  }

  @Override
  public String visitOrMatchExpr(OrMatchExpr orMatchExpr) {
    return NVLang.mkOr(
        orMatchExpr.getDisjuncts().stream().map(this::toNvExpr).toArray(String[]::new));
  }

  @Override
  public String visitPermittedByAcl(PermittedByAcl permittedByAcl) {
    throw new BatfishException("TODO: support PermittedByAcl");
  }

  @Override
  public String visitTrueExpr(TrueExpr trueExpr) {
    return NVLang.mkBool(true);
  }
}
