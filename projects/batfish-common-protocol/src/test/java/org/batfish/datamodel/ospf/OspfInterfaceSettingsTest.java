package org.batfish.datamodel.ospf;

import static org.hamcrest.Matchers.equalTo;
import static org.junit.Assert.assertThat;

import com.google.common.testing.EqualsTester;
import java.io.IOException;
import org.apache.commons.lang3.SerializationUtils;
import org.batfish.common.util.BatfishObjectMapper;
import org.junit.Test;

public class OspfInterfaceSettingsTest {
  @Test
  public void testJsonSerialization() throws IOException {
    OspfInterfaceSettings s =
        OspfInterfaceSettings.builder()
            .setProcess("proc")
            .setCost(1)
            .setEnabled(true)
            .setPassive(false)
            .setAreaName(1L)
            .setDeadInterval(44)
            .setHelloInterval(11)
            .setHelloMultiplier(55)
            .setNetworkType(OspfNetworkType.POINT_TO_POINT)
            .build();

    // test (de)serialization
    assertThat(s, equalTo(BatfishObjectMapper.clone(s, OspfInterfaceSettings.class)));
  }

  @Test
  public void testJavaSerialization() {
    OspfInterfaceSettings s =
        OspfInterfaceSettings.builder()
            .setProcess("proc")
            .setCost(1)
            .setEnabled(true)
            .setPassive(false)
            .setAreaName(1L)
            .setDeadInterval(44)
            .setHelloInterval(11)
            .setHelloMultiplier(55)
            .setNetworkType(OspfNetworkType.POINT_TO_POINT)
            .build();

    assertThat(SerializationUtils.clone(s), equalTo(s));
  }

  @Test
  public void testEquals() {
    OspfInterfaceSettings.Builder s =
        OspfInterfaceSettings.builder().setHelloInterval(0).setDeadInterval(0).setPassive(true);
    new EqualsTester()
        .addEqualityGroup(s.build())
        .addEqualityGroup(
            s.setAreaName(2L).build(),
            OspfInterfaceSettings.builder()
                .setAreaName(2L)
                .setHelloInterval(0)
                .setDeadInterval(0)
                .setPassive(true)
                .build())
        .addEqualityGroup(s.setCost(3).build())
        .addEqualityGroup(s.setDeadInterval(4).build())
        .addEqualityGroup(s.setEnabled(false).build())
        .addEqualityGroup(s.setHelloInterval(1).build())
        .addEqualityGroup(s.setHelloMultiplier(5).build())
        .addEqualityGroup(s.setInboundDistributeListPolicy("policy").build())
        .addEqualityGroup(s.setNetworkType(OspfNetworkType.POINT_TO_POINT).build())
        .addEqualityGroup(s.setNetworkType(OspfNetworkType.BROADCAST).build())
        .addEqualityGroup(s.setNetworkType(OspfNetworkType.POINT_TO_MULTIPOINT).build())
        .addEqualityGroup(s.setNetworkType(OspfNetworkType.NON_BROADCAST_MULTI_ACCESS).build())
        .addEqualityGroup(s.setPassive(false).build())
        .addEqualityGroup(s.setProcess("proc").build())
        .testEquals();
  }
}
