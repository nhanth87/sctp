package org.mobicents.protocols.sctp.fstack;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.mobicents.protocols.api.Association;
import org.mobicents.protocols.api.AssociationListener;
import org.mobicents.protocols.api.IpChannelType;
import org.mobicents.protocols.api.PayloadData;
import org.mobicents.protocols.sctp.spi.SctpDataplane;
import org.mobicents.protocols.sctp.spi.SctpRecord;
import org.mobicents.protocols.sctp.spi.SctpTransportMode;

class NativeFfmSmokeTest {
    @Test
    void inProcessLoopbackHelloOpenSendClose() throws Exception {
        Path lib = FstackNativePaths.library();
        assumeTrue(lib != null && Files.isRegularFile(lib), "libsctp_fstack.so missing — native build did not run");
        System.setProperty("sctp.fstack.inprocess.enabled", "true");
        FstackSctpManagementImpl mgmt = new FstackSctpManagementImpl("ffm-smoke");
        mgmt.setMode(SctpTransportMode.IN_PROCESS);
        mgmt.setDataplane(SctpDataplane.LOOPBACK);
        try {
            mgmt.start();
            assertTrue(mgmt.isStarted());
            Association assoc = mgmt.addAssociation("127.0.0.1", 1, "127.0.0.1", 2, "a",
                    IpChannelType.SCTP, null);
            assoc.setAssociationListener(noop());
            mgmt.startAssociation("a");
            assertTrue(assoc.isUp());
            assertDoesNotThrow(() -> assoc.send(new PayloadData(4, new byte[] {1, 2, 3, 4}, true, true,
                    SctpRecord.M3UA_PPID, 0)));
            mgmt.stop();
            assertTrue(!mgmt.isStarted());
        } finally {
            System.clearProperty("sctp.fstack.inprocess.enabled");
        }
    }

    private static AssociationListener noop() {
        return new AssociationListener() {
            @Override public void onCommunicationUp(Association a, int in, int out) {}
            @Override public void onCommunicationShutdown(Association a) {}
            @Override public void onCommunicationLost(Association a) {}
            @Override public void onCommunicationRestart(Association a) {}
            @Override public void onPayload(Association a, PayloadData p) {}
            @Override public void inValidStreamId(PayloadData p) {}
        };
    }
}
