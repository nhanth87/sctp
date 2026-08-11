package org.mobicents.protocols.sctp.fstack;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.mobicents.protocols.sctp.spi.SctpDataplane;
import org.mobicents.protocols.sctp.spi.SctpTransportMode;

class FfmSoakGateTest {
    @Test
    void inProcessRefusesUntilSoakPropertySet() {
        FstackSctpManagementImpl mgmt = new FstackSctpManagementImpl("ffm");
        mgmt.setMode(SctpTransportMode.IN_PROCESS);
        mgmt.setDataplane(SctpDataplane.DPDK);
        IllegalStateException ex = assertThrows(IllegalStateException.class, mgmt::start);
        assertTrue(ex.getMessage().contains("IN_PROCESS disabled")
                || ex.getMessage().contains("soak"));
    }
}
