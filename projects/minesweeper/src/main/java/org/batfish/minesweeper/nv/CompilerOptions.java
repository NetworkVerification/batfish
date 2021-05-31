package org.batfish.minesweeper.nv;

import java.util.HashSet;
import java.util.Set;
import org.glassfish.grizzly.utils.ArraySet;

public class CompilerOptions {
  public enum NVFlags {
    dataplane,
    nexthop,
    origin,
    nodeFaults,
    asPath,
    boundedLinkFaults,
    multiPath,
  }

  private int _faultsBound;
  private String _symbolicOrder;
  private Set<NVFlags> flags;

  public CompilerOptions() {
    flags = new HashSet<>();
  }

  public CompilerOptions(Set<NVFlags> fs) {
    flags = new HashSet<>();
    ((ArraySet<NVFlags>) flags).addAll(fs);
  }

  public void setFlag(NVFlags f) {
    if (flags != null) {
      flags.add(f);
    }
  }

  public boolean doDataplane () {
    return flags.contains(NVFlags.dataplane);
  }

  public boolean doOrigin () {
    return flags.contains(NVFlags.origin);
  }

  public boolean doNextHop () {
    return flags.contains(NVFlags.nexthop) || flags.contains(NVFlags.dataplane);
  }

  public boolean doNodeFaults () {
    return flags.contains(NVFlags.nodeFaults);
  }

  public boolean doASPath () {
    return flags.contains(NVFlags.asPath);
  }

  public boolean doBoundedLinkFaults () { return flags.contains(NVFlags.boundedLinkFaults); }

  public boolean doMultiPath () { return flags.contains(NVFlags.multiPath); }

  public int getLinkFaultsBound () { return _faultsBound;}

  public void setLinkFaultsBound (int x) { _faultsBound = x;}

  public String getSymbolicOrder () { return _symbolicOrder;}

  public void setSymbolicOrder (String x) { _symbolicOrder = x;}
}
