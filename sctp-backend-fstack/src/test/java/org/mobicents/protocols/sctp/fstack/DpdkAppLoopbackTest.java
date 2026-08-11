package org.mobicents.protocols.sctp.fstack;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mobicents.protocols.api.Association;
import org.mobicents.protocols.api.AssociationListener;
import org.mobicents.protocols.api.IpChannelType;
import org.mobicents.protocols.api.PayloadData;
import org.mobicents.protocols.sctp.spi.SctpDataplane;
import org.mobicents.protocols.sctp.spi.SctpRecord;
import org.mobicents.protocols.sctp.spi.SctpTransportMode;

/**
 * App-level proof that the SCTP data plane runs end-to-end when both sides
 * select {@link SctpDataplane#DPDK}. The sidecar advertises the DPDK capability
 * through {@code SCTP_FS_EMULATE_DPDK=1} so the DPDK-selected code path is
 * exercised over the software ring on any host (no NIC / hugepages). The real
 * on-NIC INIT-COOKIE gate stays behind {@code sctp.fstack.dpdk.port-up}.
 */
class DpdkAppLoopbackTest {
    @TempDir
    Path tmp;

    @Test
    void twoManagementsExchangeM3uaOverDpdkDataplane() throws Exception {
        Path binary = FstackNativePaths.sidecarBinary();
        assumeTrue(binary != null && Files.isExecutable(binary), "sctp-fstack binary missing");
        Path sock = tmp.resolve("sctp-fstack.sock");
        ProcessBuilder pb = new ProcessBuilder(binary.toString(), "--socket", sock.toString(),
                "--ring-dir", tmp.toString())
                .redirectErrorStream(true);
        pb.environment().put("SCTP_FS_EMULATE_DPDK", "1");
        Process sidecar = pb.start();
        try {
            waitForSocket(sock);
            System.setProperty("sctp.fstack.sidecar.socket", sock.toString());
            FstackSctpManagementImpl gmlc = new FstackSctpManagementImpl("gmlc");
            FstackSctpManagementImpl sim = new FstackSctpManagementImpl("sim");
            gmlc.setMode(SctpTransportMode.SIDECAR);
            sim.setMode(SctpTransportMode.SIDECAR);
            gmlc.setDataplane(SctpDataplane.DPDK);
            sim.setDataplane(SctpDataplane.DPDK);
            gmlc.start();
            sim.start();
            assertTrue(gmlc.isStarted(), "gmlc management started over DPDK dataplane");
            assertTrue(sim.isStarted(), "sim management started over DPDK dataplane");
            Association server = gmlc.addAssociation("127.0.0.1", 8113, "127.0.0.1", 8114, "L1",
                    IpChannelType.SCTP, null);
            Association client = sim.addAssociation("127.0.0.1", 8114, "127.0.0.1", 8113, "Ass",
                    IpChannelType.SCTP, null);
            CountDownLatch up = new CountDownLatch(2);
            List<PayloadData> seen = new ArrayList<>();
            server.setAssociationListener(new RecordingListener() {
                @Override public void onCommunicationUp(Association a, int in, int out) { up.countDown(); }
                @Override public void onPayload(Association a, PayloadData p) { seen.add(p); }
            });
            client.setAssociationListener(new RecordingListener() {
                @Override public void onCommunicationUp(Association a, int in, int out) { up.countDown(); }
            });
            gmlc.startAssociation("L1");
            sim.startAssociation("Ass");
            assertTrue(up.await(3, TimeUnit.SECONDS), "SCTP UP via DPDK-selected sctp-fs data plane");
            client.send(new PayloadData(4, new byte[] {1, 2, 3, 4}, true, false, SctpRecord.M3UA_PPID, 0));
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
            while (seen.isEmpty() && System.nanoTime() < deadline) {
                Thread.sleep(20);
            }
            assertEquals(1, seen.size(), "M3UA PPID-3 payload delivered over DPDK data plane");
            assertEquals(SctpRecord.M3UA_PPID, seen.getFirst().getPayloadProtocolId());
            gmlc.stop();
            sim.stop();
        } finally {
            sidecar.destroyForcibly();
            System.clearProperty("sctp.fstack.sidecar.socket");
        }
    }

    private static void waitForSocket(Path sock) throws InterruptedException {
        for (int i = 0; i < 100; i++) {
            if (Files.exists(sock)) {
                return;
            }
            Thread.sleep(20);
        }
    }

    private static class RecordingListener implements AssociationListener {
        @Override public void onCommunicationUp(Association association, int maxInboundStreams, int maxOutboundStreams) {}
        @Override public void onCommunicationShutdown(Association association) {}
        @Override public void onCommunicationLost(Association association) {}
        @Override public void onCommunicationRestart(Association association) {}
        @Override public void onPayload(Association association, PayloadData payloadData) {}
        @Override public void inValidStreamId(PayloadData payloadData) {}
    }
}
