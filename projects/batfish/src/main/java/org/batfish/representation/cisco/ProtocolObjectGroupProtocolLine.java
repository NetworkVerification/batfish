package org.batfish.representation.cisco;

import static java.util.Objects.requireNonNull;

import com.google.common.collect.ImmutableList;
import java.io.Serializable;
import java.util.List;
import javax.annotation.Nonnull;
import org.batfish.datamodel.HeaderSpace;
import org.batfish.datamodel.IpProtocol;
import org.batfish.datamodel.Protocol;
import org.batfish.datamodel.SubRange;
import org.batfish.datamodel.acl.AclLineMatchExpr;
import org.batfish.datamodel.acl.MatchHeaderSpace;

public class ProtocolObjectGroupProtocolLine implements ProtocolObjectGroupLine {

  /** */
  private static final long serialVersionUID = 1L;

  private final IpProtocol _protocol;

  public ProtocolObjectGroupProtocolLine(@Nonnull IpProtocol protocol) {
    _protocol = protocol;
  }

  public IpProtocol getProtocol() {
    return _protocol;
  }

  public AclLineMatchExpr toAclLineMatchExpr() {
    return new MatchHeaderSpace(
        HeaderSpace.builder()
            .setIpProtocols(ImmutableList.of(_protocol))
            .build());
  }
}