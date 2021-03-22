package org.batfish.minesweeper.nv;

import java.util.Map;
import org.batfish.datamodel.Prefix;
import org.batfish.minesweeper.utils.Tuple;

public class CompilerResult {

  private String _controlPlane;
  private String _dataPlane;
  private Tuple<String, Map<Prefix, String>> _allNodeFaults;
  private Tuple<String, Map<Prefix, String>> _allLinkFaults;


  private Tuple<String, Map<Prefix, String>> _boundedLinkFaults;

  public CompilerResult(String _controlPlane, String _dataPlane, Tuple<String, Map<Prefix, String>> _allNodeFaults, Tuple<String, Map<Prefix, String>> _allLinkFaults, Tuple<String, Map<Prefix, String>> _boundedLinkFaults) {
    this._controlPlane = _controlPlane;
    this._dataPlane = _dataPlane;
    this._allNodeFaults = _allNodeFaults;
    this._allLinkFaults = _allLinkFaults;
    this._boundedLinkFaults = _boundedLinkFaults;
  }

  public String getControlPlane() {
    return _controlPlane;
  }

  public void setControlPlane(String controlPlane) {
    this._controlPlane = controlPlane;
  }

  public String getDataPlane() {
    return _dataPlane;
  }

  public void setDataPlane(String dataPlane) {
    this._dataPlane = dataPlane;
  }

  public Tuple<String, Map<Prefix, String>> getAllNodeFaults() {
    return _allNodeFaults;
  }

  public void setAllNodeFaults(Tuple<String, Map<Prefix, String>> allNodeFaults) {
    this._allNodeFaults = allNodeFaults;
  }

  public Tuple<String, Map<Prefix, String>> getAllLinkFaults() {
    return _allLinkFaults;
  }

  public void setAllLinkFaults(Tuple<String, Map<Prefix, String>> allLinkFaults) {
    this._allLinkFaults = allLinkFaults;
  }

  public Tuple<String, Map<Prefix, String>> getBoundedLinkFaults() {
    return _boundedLinkFaults;
  }

  public void setBoundedLinkFaults(Tuple<String, Map<Prefix, String>> _boundedLinkFaults) {
    this._boundedLinkFaults = _boundedLinkFaults;
  }
}
