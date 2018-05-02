package org.batfish.representation.cisco;

import static com.google.common.base.MoreObjects.firstNonNull;

import com.google.common.collect.ImmutableMap;
import java.util.Collection;
import java.util.Comparator;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Optional;
import java.util.stream.Collectors;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.annotation.ParametersAreNonnullByDefault;
import org.batfish.common.Warnings;
import org.batfish.datamodel.BgpNeighbor;
import org.batfish.datamodel.Configuration;
import org.batfish.datamodel.Interface;
import org.batfish.datamodel.InterfaceAddress;
import org.batfish.datamodel.Ip;
import org.batfish.datamodel.Prefix;
import org.batfish.datamodel.Vrf;
import org.batfish.representation.cisco.nx.CiscoNxBgpGlobalConfiguration;
import org.batfish.representation.cisco.nx.CiscoNxBgpVrfAddressFamilyConfiguration;
import org.batfish.representation.cisco.nx.CiscoNxBgpVrfConfiguration;
import org.batfish.representation.cisco.nx.CiscoNxBgpVrfNeighborAddressFamilyConfiguration;
import org.batfish.representation.cisco.nx.CiscoNxBgpVrfNeighborConfiguration;

/**
 * A utility class for converting between Cisco NX-OS configurations and the Batfish
 * vendor-independent {@link org.batfish.datamodel}.
 */
@ParametersAreNonnullByDefault
final class CiscoNxConversions {
  /** Computes the router ID on Cisco NX-OS. */
  // See CiscoNxosTest#testRouterId for a test that is verifiable using GNS3.
  @Nonnull
  static Ip getNxBgpRouterId(
      CiscoNxBgpVrfConfiguration vrfConfig, org.batfish.datamodel.Vrf vrf, Warnings w) {
    // If Router ID is configured in the VRF-Specific BGP config, it always wins.
    if (vrfConfig.getRouterId() != null) {
      return vrfConfig.getRouterId();
    }

    String messageBase =
        String.format(
            "Router-id is not manually configured for BGP process in VRF %s", vrf.getName());

    // Otherwise, Router ID is defined based on the interfaces in the VRF that have IP addresses.
    // NX-OS does use shutdown interfaces to configure router-id.
    Map<String, Interface> interfaceMap =
        vrf.getInterfaces()
            .entrySet()
            .stream()
            .filter(e -> e.getValue().getAddress() != null)
            .collect(Collectors.toMap(Entry::getKey, Entry::getValue));
    if (interfaceMap.isEmpty()) {
      w.redFlag(
          String.format(
              "%s. Unable to infer default router-id as no interfaces have IP addresses",
              messageBase));
      // With no interfaces in the VRF that have IP addresses, show ip bgp vrf all reports 0.0.0.0
      // as the router ID. Of course, this is not really relevant as no routes will be exchanged.
      return Ip.ZERO;
    }

    // Next, NX-OS prefers the IP of Loopback0 if one exists.
    Interface loopback0 = interfaceMap.get("Loopback0");
    if (loopback0 != null) {
      w.redFlag(String.format("%s. Using the IP address of Loopback0", messageBase));
      return loopback0.getAddress().getIp();
    }

    // Next, NX-OS prefers "first" loopback interface. NX-OS is non-deterministic, but we will
    // enforce determinism by always choosing the smallest loopback IP.
    Collection<Interface> interfaces = interfaceMap.values();
    Optional<Ip> lowestLoopback =
        interfaces
            .stream()
            .filter(i -> i.getName().startsWith("Loopback"))
            .map(Interface::getAddress)
            .map(InterfaceAddress::getIp)
            .min(Comparator.naturalOrder());
    if (lowestLoopback.isPresent()) {
      w.redFlag(
          String.format(
              "%s. Making a non-deterministic choice from associated loopbacks", messageBase));
      return lowestLoopback.get();
    }

    // Finally, NX uses the first non-loopback interface defined in the vrf, assuming no loopback
    // addresses with IP address are present in the vrf. NX-OS is non-deterministic, by we will
    // enforce determinism by always choosing the smallest interface IP.
    Optional<Ip> lowestIp =
        interfaces
            .stream()
            .map(Interface::getAddress)
            .map(InterfaceAddress::getIp)
            .min(Comparator.naturalOrder());
    w.redFlag(
        String.format(
            "%s. Making a non-deterministic choice from associated interfaces", messageBase));
    assert lowestIp.isPresent(); // This cannot happen if interfaces is non-empty.
    return lowestIp.get();
  }

  private static boolean isActive(
      String name, CiscoNxBgpVrfNeighborConfiguration neighbor, Warnings w) {
    // Shutdown
    if (firstNonNull(neighbor.getShutdown(), Boolean.FALSE)) {
      return false;
    }

    // No active address family that we support.
    if (neighbor.getIpv4UnicastAddressFamily() == null
        && neighbor.getIpv6UnicastAddressFamily() == null) {
      w.redFlag("No supported address-family configured for " + name);
      return false;
    }

    // No remote AS set.
    if (neighbor.getRemoteAs() == null) {
      w.redFlag("No remote-as configured for " + name);
    }

    return true;
  }

  @Nonnull
  static Map<Ip, BgpNeighbor> getNeighbors(
      Configuration c,
      Vrf vrf,
      CiscoNxBgpGlobalConfiguration bgpConfig,
      CiscoNxBgpVrfConfiguration nxBgpVrf,
      Warnings warnings) {
    return nxBgpVrf
        .getNeighbors()
        .entrySet()
        .stream()
        .filter(e -> isActive(getTextDesc(e.getKey(), vrf), e.getValue(), warnings))
        .collect(
            ImmutableMap.toImmutableMap(
                Entry::getKey,
                e ->
                    CiscoNxConversions.toBgpNeighbor(
                        c,
                        vrf,
                        new Prefix(e.getKey(), Prefix.MAX_PREFIX_LENGTH),
                        bgpConfig,
                        nxBgpVrf,
                        e.getValue(),
                        false,
                        warnings)));
  }

  @Nonnull
  static Map<Prefix, BgpNeighbor> getPassiveNeighbors(
      Configuration c,
      Vrf vrf,
      CiscoNxBgpGlobalConfiguration bgpConfig,
      CiscoNxBgpVrfConfiguration nxBgpVrf,
      Warnings warnings) {
    return nxBgpVrf
        .getPassiveNeighbors()
        .entrySet()
        .stream()
        .filter(e -> isActive(getTextDesc(e.getKey(), vrf), e.getValue(), warnings))
        .collect(
            ImmutableMap.toImmutableMap(
                Entry::getKey,
                e ->
                    CiscoNxConversions.toBgpNeighbor(
                        c, vrf, e.getKey(), bgpConfig, nxBgpVrf, e.getValue(), true, warnings)));
  }

  @Nonnull
  private static BgpNeighbor toBgpNeighbor(
      Configuration c,
      org.batfish.datamodel.Vrf vrf,
      Prefix prefix,
      CiscoNxBgpGlobalConfiguration bgpConfig,
      CiscoNxBgpVrfConfiguration vrfConfig,
      CiscoNxBgpVrfNeighborConfiguration neighbor,
      boolean dynamic,
      Warnings warnings) {
    BgpNeighbor newNeighbor = new BgpNeighbor(prefix, c, dynamic);

    newNeighbor.setDescription(neighbor.getDescription());

    if (vrfConfig.getClusterId() != null) {
      newNeighbor.setClusterId(vrfConfig.getClusterId().asLong());
    }

    newNeighbor.setEbgpMultihop(firstNonNull(neighbor.getEbgpMultihopTtl(), 0) > 1);

    newNeighbor.setEnforceFirstAs(bgpConfig.getEnforceFirstAs());

    if (neighbor.getLocalAs() != null) {
      long asn = neighbor.getLocalAs();
      if (asn >= (1 << 16)) {
        warnings.redFlag(
            String.format(
                "4-byte AS numbers are not fully supported: vrf %s neighbor %s local-as %d",
                vrf.getName(), prefix, asn));
      }
      newNeighbor.setLocalAs((int) asn);
    } else if (vrfConfig.getLocalAs() != null) {
      long asn = vrfConfig.getLocalAs();
      if (asn >= (1 << 16)) {
        warnings.redFlag(
            String.format(
                "4-byte AS numbers are not fully supported: vrf %s neighbor %s local-as %d",
                vrf.getName(), prefix, asn));
      }
      newNeighbor.setLocalAs((int) asn);
    }

    if (neighbor.getRemoteAs() != null) {
      long asn = neighbor.getRemoteAs();
      if (asn >= (1 << 16)) {
        warnings.redFlag(
            String.format(
                "4-byte AS numbers are not fully supported: vrf %s neighbor %s remote-as %d",
                vrf.getName(), prefix, asn));
      }
      newNeighbor.setRemoteAs((int) asn);
    }

    newNeighbor.setVrf(vrf.getName());

    @Nullable
    CiscoNxBgpVrfNeighborAddressFamilyConfiguration naf4 = neighbor.getIpv4UnicastAddressFamily();
    @Nullable CiscoNxBgpVrfAddressFamilyConfiguration af4 = vrfConfig.getIpv4UnicastAddressFamily();
    if (naf4 != null) {
      newNeighbor.setAdvertiseInactive(
          !firstNonNull(
              naf4.getSuppressInactive(), af4 != null ? af4.getSuppressInactive() : Boolean.FALSE));
      newNeighbor.setAllowLocalAsIn(firstNonNull(naf4.getAllowAsIn(), Boolean.FALSE));
      newNeighbor.setSendCommunity(firstNonNull(naf4.getSendCommunityStandard(), Boolean.FALSE));
      newNeighbor.setRouteReflectorClient(
          firstNonNull(naf4.getRouteReflectorClient(), Boolean.FALSE));
    }

    return newNeighbor;
  }

  private static String getTextDesc(Ip ip, Vrf v) {
    return String.format("BGP neighbor %s in vrf %s", ip.toString(), v.getName());
  }

  private static String getTextDesc(Prefix prefix, Vrf v) {
    return String.format("BGP neighbor %s in vrf %s", prefix.toString(), v.getName());
  }

  private CiscoNxConversions() {} // prevent instantiation of utility class.
}
