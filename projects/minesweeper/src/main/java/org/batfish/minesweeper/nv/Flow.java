package org.batfish.minesweeper.nv;

import org.batfish.datamodel.Ip;
import org.batfish.datamodel.IpProtocol;

public class Flow {

  /** Node sending the traffic*/
  private int _source;

  private Ip _srcIp;
  private Ip _dstIp;
  private Integer _srcPort;
  private Integer _dstPort;
  private IpProtocol _protocol;

  private double _flowSize;

  public Flow(int source, Ip srcIp, Ip dstIp, Integer _srcPort, Integer _dstPort,
      IpProtocol _protocol, double flowSize) {
    this._source = source;
    this._srcIp = srcIp;
    this._dstIp = dstIp;
    this._srcPort = _srcPort;
    this._dstPort = _dstPort;
    this._protocol = _protocol;
    this._flowSize = flowSize;
  }

  public int get_source() {
    return _source;
  }

  public Ip get_srcIp() {
    return _srcIp;
  }

  public Ip get_dstIp() {
    return _dstIp;
  }

  public Integer get_srcPort() {
    return _srcPort;
  }

  public Integer get_dstPort() {
    return _dstPort;
  }

  public IpProtocol get_protocol() {
    return _protocol;
  }

  public double get_flowSize() {
    return _flowSize;
  }

  public String compile() {
    return "{srcIp = " + this._srcIp.toString() + "; dstIp = " + this._dstIp.toString() +
        "; srcPort = " + this._srcPort.toString() + "u16; dstPort = " + this._dstPort.toString() +
        "u16; protocol = " + this._protocol.number() + "u8; flowSize = " + this._flowSize + "}";
  }

//  public String compile() {
//    return "{dstIp = " + this._dstIp.toString() + "; flowSize = " + this._flowSize + "}";
//  }

}
