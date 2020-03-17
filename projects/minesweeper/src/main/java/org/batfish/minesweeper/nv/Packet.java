package org.batfish.minesweeper.nv;

public class Packet {
  private String _dstIp;
  private String _srcIp;
  private String _dstPort;
  private String _srcPort;
  private String _ipProtocol;

  private String packet;

  public Packet() {
    this._dstIp = "dstIp";
    this._srcIp = "srcIp";
    this._dstPort = "dstPort";
    this._srcPort = "srcPort";
    this._ipProtocol = "ipProtocol";
    this.packet = "p";
  }

  public String getDstIp() {
    return (packet + "." + _dstIp);
  }

  public void setDstIp(String dstIp) {
    this._dstIp = dstIp;
  }

  public String getSrcIp() {
    return (packet + "." + _srcIp);
  }

  public void setSrcIp(String srcIp) {
    this._srcIp = srcIp;
  }

  public String getIpProtocol() {
    return (packet + "." + _ipProtocol);
  }

  public void setIpProtocol(String ipProtocol) {
    this._srcIp = ipProtocol;
  }


  public String getPacket() {
    return packet;
  }

  public void setPacket(String packet) {
    this.packet = packet;
  }
}

/*

public class Packet {
  private Either<Long,String> _dstIp;
  private Either<Long,String> _srcIp;
  private Either<Long,String> _dstPort;
  private Either<Long,String> _srcPort;
  private Either<Long, String> _ipProtocol;

  private String packet;

  public Packet() {
    this._dstIp = Either.right("dstIp");
    this._srcIp = Either.right("srcIp");
    this._dstPort = Either.right("dstPort");
    this._srcPort = Either.right("srcPort");
    this._ipProtocol = Either.right("ipProtocol");
    this.packet = "p";
  }

  public String getDstIp() {
    return (_dstIp.isLeft() ? _dstIp.left().toString() : packet + "." + _dstIp);
  }

  public void setDstIp(String dstIp) {
    this._dstIp = Either.right(dstIp);
  }

  public void setDstIp(Long dstIp) {
    this._dstIp = Either.left(dstIp);
  }

  public String getSrcIp() {
    return (_srcIp.isLeft() ? _srcIp.left().toString() : packet + "." + _srcIp);
  }

  public void setSrcIp(String srcIp) {
    this._srcIp = Either.right(srcIp);
  }

  public void setSrcIp(Long srcIp) {
    this._srcIp = Either.left(srcIp);
  }

  public String getIpProtocol() {
    return (_ipProtocol.isLeft() ? _ipProtocol.left().toString() : packet + "." + _ipProtocol);
  }

  public void setIpProtocol(String ipProtocol) {
    this._srcIp = Either.right(ipProtocol);
  }

  public void setIpProtocol(Long ipProtocol) {
    this._srcIp = Either.left(ipProtocol);
  }


  public String getPacket() {
    return packet;
  }

  public void setPacket(String packet) {
    this.packet = packet;
  }
}
*/