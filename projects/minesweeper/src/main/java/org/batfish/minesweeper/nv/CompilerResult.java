package org.batfish.minesweeper.nv;

public class CompilerResult {

  private String _controlPlane;
  private String _dataPlane;
  private String _allNodeFaults;

  public CompilerResult(String _controlPlane, String _dataPlane, String _allNodeFaults) {
    this._controlPlane = _controlPlane;
    this._dataPlane = _dataPlane;
    this._allNodeFaults = _allNodeFaults;
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

  public String getAllNodeFaults() {
    return _allNodeFaults;
  }

  public void setAllNodeFaults(String allNodeFaults) {
    this._allNodeFaults = allNodeFaults;
  }
}
