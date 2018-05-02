package org.batfish.representation.cisco.nx;

import java.io.Serializable;
import javax.annotation.Nullable;

/**
 * Represents the BGP configuration for a single address family in a single BGP neighbor.
 *
 * <p>Configuration commands entered at the CLI {@code config-router-neighbor-af} or {@code
 * config-router-vrf-neighbor-af} levels.
 */
public class CiscoNxBgpVrfNeighborAddressFamilyConfiguration implements Serializable {
  private static final long serialVersionUID = 1L;

  public CiscoNxBgpVrfNeighborAddressFamilyConfiguration() {}

  @Nullable
  public Boolean getAllowAsIn() {
    return _allowAsIn;
  }

  public void setAllowAsIn(@Nullable Boolean allowAsIn) {
    _allowAsIn = allowAsIn;
  }

  @Nullable
  public Boolean getAsOverride() {
    return _asOverride;
  }

  public void setAsOverride(@Nullable Boolean asOverride) {
    _asOverride = asOverride;
  }

  public void setNextHopSelf(@Nullable Boolean nextHopSelf) {
    _nextHopSelf = nextHopSelf;
  }

  @Nullable
  public Boolean getNextHopSelf() {
    return _nextHopSelf;
  }

  public void setNextHopThirdParty(@Nullable Boolean nextHopThirdParty) {
    _nextHopThirdParty = nextHopThirdParty;
  }

  @Nullable
  public Boolean getNextHopThirdParty() {
    return _nextHopThirdParty;
  }

  public void setRouteReflectorClient(@Nullable Boolean routeReflectorClient) {
    _routeReflectorClient = routeReflectorClient;
  }

  @Nullable
  public Boolean getRouteReflectorClient() {
    return _routeReflectorClient;
  }

  public void setSendCommunityExtended(@Nullable Boolean sendCommunityExtended) {
    _sendCommunityExtended = sendCommunityExtended;
  }

  @Nullable
  public Boolean getSendCommunityExtended() {
    return _sendCommunityExtended;
  }

  public void setSendCommunityStandard(@Nullable Boolean sendCommunityStandard) {
    _sendCommunityStandard = sendCommunityStandard;
  }

  @Nullable
  public Boolean getSendCommunityStandard() {
    return _sendCommunityStandard;
  }

  public void setSuppressInactive(@Nullable Boolean suppressInactive) {
    _suppressInactive = suppressInactive;
  }

  @Nullable
  public Boolean getSuppressInactive() {
    return _suppressInactive;
  }

  private @Nullable Boolean _allowAsIn;
  private @Nullable Boolean _asOverride;
  private @Nullable Boolean _nextHopSelf;
  private @Nullable Boolean _nextHopThirdParty;
  private @Nullable Boolean _routeReflectorClient;
  private @Nullable Boolean _sendCommunityExtended;
  private @Nullable Boolean _sendCommunityStandard;
  private @Nullable Boolean _suppressInactive;
}
