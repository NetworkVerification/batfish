package org.batfish.minesweeper.nv;

public class Attributes {

  private CompilerOptions _flags;

  public Attributes (CompilerOptions flags) {
    _flags = flags;
  }

  public String buildBgpAttribute(
        String _lp,
        String _ad,
        String _aspath,
        String _med,
        String _communities,
        String _nexthop,
        String _origin,
        String _asSet,
        String _ibgp,
        String _igpMetric) {
    return "Some {bgpAd="
        + _ad
        + "; lp="
        + _lp
        + "; aslen="
        + _aspath
      + "; med="
      + _med
      + "; comms="
      + _communities
      + (_flags.doOrigin() ? "; bgpOrigin=" + _origin : "")
      + (_flags.doNextHop() ? "; bgpNextHop=" + _nexthop : "")
      + (_flags.doASPath() ? "; bgpAS=" + _asSet : "")
      + "; ibgp=" + _ibgp
      + "; igpMetric=" + _igpMetric
      + "}";
  }

  public String buildOspfAttribute(
      String _ad,
      String _weight,
      String _areaType,
      String _areaId,
      String _nexthop,
      String _origin) {
    return "Some {ospfAd="
        + _ad
        + "; weight="
        + _weight
        + "; areaType="
        + _areaType
        + "; areaId="
        + _areaId
        + (_flags.doOrigin() ? "; ospfOrigin= " + _origin : "")
        + (_flags.doNextHop() ? "; ospfNextHop= " + _nexthop : "")
        + "}";
  }

  public String buildBgpType() {
    return "{bgpAd: int8; lp: int; aslen: int; med:int; comms:set[int];"
        + (_flags.doOrigin() ? " bgpOrigin: tnode;" : "")
        + (_flags.doNextHop() && _flags.doMultiPath() ? " bgpNextHop: set[tedge];" :
                      (_flags.doNextHop() ? " bgpNextHop: option[tedge];" : ""))
        + (_flags.doASPath() ? " bgpAS: set[tnode];" : "")
        + " ibgp: bool;"
        + " igpMetric: int16;"
        + "}";
  }

  public String buildOspfType() {
    return "{ospfAd: int8; weight: int16; areaType:int2; areaId: int;" +
        (_flags.doOrigin() ? " ospfOrigin: tnode;" : "") +
        (_flags.doNextHop() && _flags.doMultiPath() ? " ospfNextHop: set[tedge];" :
            (_flags.doNextHop() ? " ospfNextHop: option[tedge];" : "")) +
    "}";
  }


}
