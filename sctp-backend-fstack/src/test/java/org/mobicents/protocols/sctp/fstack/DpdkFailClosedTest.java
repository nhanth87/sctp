package org.mobicents.protocols.sctp.fstack;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mobicents.protocols.sctp.spi.SctpDataplane;
import org.mobicents.protocols.sctp.spi.SctpTransportMode;

class DpdkFailClosedTest {
    @TempDir
    Path tmp;

    @Test
    void dpdkSidecarWithoutSocketDoesNotStayStarted() {
        FstackSctpManagementImpl mgmt = new FstackSctpManagementImpl("dpdk-missing");
        mgmt.setMode(SctpTransportMode.SIDECAR);
        mgmt.setDataplane(SctpDataplane.DPDK);
        System.setProperty("sctp.fstack.sidecar.socket", "/tmp/sctp-fstack-missing-" + System.nanoTime() + ".sock");
        try {
            IllegalStateException ex = assertThrows(IllegalStateException.class, mgmt::start);
            assertTrue(ex.getMessage().contains("sidecar") || ex.getMessage().contains("socket"));
            assertFalse(mgmt.isStarted(), "start() must roll back so a retry can bind a live sidecar");
        } finally {
            System.clearProperty("sctp.fstack.sidecar.socket");
        }
    }

    @Test
    void dpdkSidecarWithoutDpdkCapabilityFailsClosed() throws Exception {
        Path binary = FstackNativePaths.sidecarBinary();
        assumeTrue(binary != null && Files.isExecutable(binary), "sctp-fstack binary missing");
        Path sock = tmp.resolve("sctp-fstack.sock");
        // Sidecar started WITHOUT SCTP_FS_EMULATE_DPDK — advertises no CAP_DPDK.
        Process sidecar = new ProcessBuilder(binary.toString(), "--socket", sock.toString(),
                "--ring-dir", tmp.toString())
                .redirectErrorStream(true)
                .start();
        try {
            for (int i = 0; i < 100 && !Files.exists(sock); i++) {
                Thread.sleep(20);
            }
            System.setProperty("sctp.fstack.sidecar.socket", sock.toString());
            FstackSctpManagementImpl mgmt = new FstackSctpManagementImpl("dpdk-no-cap");
            mgmt.setMode(SctpTransportMode.SIDECAR);
            mgmt.setDataplane(SctpDataplane.DPDK);
            IllegalStateException ex = assertThrows(IllegalStateException.class, mgmt::start);
            assertTrue(ex.getMessage().contains("DPDK capability"), ex.getMessage());
            assertFalse(mgmt.isStarted(), "DPDK must fail closed when the sidecar lacks CAP_DPDK");
        } finally {
            sidecar.destroyForcibly();
            System.clearProperty("sctp.fstack.sidecar.socket");
        }
    }

    @Test
    void inProcessDpdkWithoutFstackCapabilityFailsClosed() {
        FstackSctpManagementImpl mgmt = new FstackSctpManagementImpl("dpdk-ffm");
        mgmt.setMode(SctpTransportMode.IN_PROCESS);
        mgmt.setDataplane(SctpDataplane.DPDK);
        System.setProperty("sctp.fstack.inprocess.enabled", "true");
        try {
            assertThrows(IllegalStateException.class, mgmt::start);
            assertFalse(mgmt.isStarted());
        } finally {
            System.clearProperty("sctp.fstack.inprocess.enabled");
        }
    }
}
