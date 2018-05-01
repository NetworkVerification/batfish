package org.batfish.representation.cisco.nx;

import java.io.Serializable;
import java.util.HashMap;
import java.util.Map;
import java.util.SortedMap;
import java.util.TreeMap;
import javax.annotation.Nullable;

/**
 * Represents the BGP configuration for a single neighbor at the VRF level.
 *
 * <p>Configuration commands entered at the CLI {@code config-router-neighbor} or {@code
 * config-router-vrf-neighbor} levels.
 */
public class CiscoNxBgpVrfNeighborConfiguration implements Serializable {
  private static final long serialVersionUID = 1L;

  /**
   * Determines whether to remote private AS numbers from AS paths ({@link #ALL}) or replace them
   * with the local AS number ({@link #REPLACE_AS}).
   */
  public enum RemovePrivateAsMode {
    ALL,
    REPLACE_AS
  }

  public CiscoNxBgpVrfNeighborConfiguration() {
    _addressFamilies = new HashMap<>();
    _inheritPeerPolicies = new TreeMap<>();
  }

  public CiscoNxBgpVrfNeighborAddressFamilyConfiguration getOrCreateAddressFamily(String af) {
    return _addressFamilies.computeIfAbsent(
        af, a -> new CiscoNxBgpVrfNeighborAddressFamilyConfiguration());
  }

  @Nullable
  public CiscoNxBgpVrfNeighborAddressFamilyConfiguration getIpv4UnicastAddressFamily() {
    return _addressFamilies.get("ipv4-unicast");
  }

  @Nullable
  public CiscoNxBgpVrfNeighborAddressFamilyConfiguration getIpv6UnicastAddressFamily() {
    return _addressFamilies.get("ipv6-unicast");
  }

  @Nullable
  public String getDescription() {
    return _description;
  }

  public void setDescription(@Nullable String description) {
    _description = description;
  }

  @Nullable
  public Integer getEbgpMultihopTtl() {
    return _ebgpMultihopTtl;
  }

  public void setEbgpMultihopTtl(@Nullable Integer ttl) {
    _ebgpMultihopTtl = ttl;
  }

  @Nullable
  public CiscoNxBgpVrfNeighborConfiguration getInheritPeer() {
    return _inheritPeer;
  }

  public void setInheritPeer(@Nullable CiscoNxBgpVrfNeighborConfiguration parent) {
    _inheritPeer = parent;
  }

  public void setInheritPeerPolicy(
      int seq, @Nullable CiscoNxBgpVrfNeighborAddressFamilyConfiguration policy) {
    _inheritPeerPolicies.put(seq, policy);
  }

  @Nullable
  public CiscoNxBgpVrfNeighborConfiguration getInheritPeerSession() {
    return _inheritPeerSession;
  }

  public void setInheritPeerSession(@Nullable CiscoNxBgpVrfNeighborConfiguration parent) {
    _inheritPeerSession = parent;
  }

  @Nullable
  public Long getLocalAs() {
    return _localAs;
  }

  public void setLocalAs(@Nullable Long localAs) {
    _localAs = localAs;
  }

  @Nullable
  public Long getRemoteAs() {
    return _remoteAs;
  }

  public void setRemoteAs(@Nullable Long remoteAs) {
    _remoteAs = remoteAs;
  }

  @Nullable
  public String getRemoteAsRouteMap() {
    return _remoteAsRouteMap;
  }

  public void setRemoteAsRouteMap(@Nullable String remoteAsRouteMap) {
    _remoteAsRouteMap = remoteAsRouteMap;
  }

  @Nullable
  public RemovePrivateAsMode getRemovePrivateAs() {
    return _removePrivateAs;
  }

  public void setRemovePrivateAs(@Nullable RemovePrivateAsMode mode) {
    _removePrivateAs = mode;
  }

  @Nullable
  public Boolean getShutdown() {
    return _shutdown;
  }

  public void setShutdown(@Nullable Boolean shutdown) {
    this._shutdown = shutdown;
  }

  private final Map<String, CiscoNxBgpVrfNeighborAddressFamilyConfiguration> _addressFamilies;
  @Nullable private String _description;
  @Nullable private Integer _ebgpMultihopTtl;
  @Nullable private CiscoNxBgpVrfNeighborConfiguration _inheritPeer;
  private final SortedMap<Integer, CiscoNxBgpVrfNeighborAddressFamilyConfiguration>
      _inheritPeerPolicies;
  @Nullable private CiscoNxBgpVrfNeighborConfiguration _inheritPeerSession;
  @Nullable private Long _localAs;
  @Nullable private Long _remoteAs;
  @Nullable private String _remoteAsRouteMap;
  @Nullable private RemovePrivateAsMode _removePrivateAs;
  @Nullable private Boolean _shutdown;
}
