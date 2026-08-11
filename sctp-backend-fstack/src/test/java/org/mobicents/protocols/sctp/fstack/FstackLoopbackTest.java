package org.mobicents.protocols.sctp.fstack;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.mobicents.protocols.api.Association;
import org.mobicents.protocols.api.AssociationListener;
import org.mobicents.protocols.api.IpChannelType;
import org.mobicents.protocols.api.PayloadData;
import org.mobicents.protocols.sctp.spi.SctpDataplane;
import org.mobicents.protocols.sctp.spi.SctpEventKind;
import org.mobicents.protocols.sctp.spi.SctpRecord;
import org.mobicents.protocols.sctp.spi.SctpTransportMode;

class FstackLoopbackTest {
    @Test
    void pairedAssociationsExchangeM3uaPpid() throws Exception {
        FstackSctpManagementImpl mgmt = new FstackSctpManagementImpl("loop");
        mgmt.setMode(SctpTransportMode.SIDECAR);
        mgmt.setDataplane(SctpDataplane.LOOPBACK);
        mgmt.start();
        Association client = mgmt.addAssociation("127.0.0.1", 2905, "127.0.0.1", 2906, "c",
                IpChannelType.SCTP, null);
        Association server = mgmt.addAssociation("127.0.0.1", 2906, "127.0.0.1", 2905, "s",
                IpChannelType.SCTP, null);
        RecordingListener left = new RecordingListener();
        RecordingListener right = new RecordingListener();
        client.setAssociationListener(left);
        server.setAssociationListener(right);
        mgmt.startAssociation("c");
        mgmt.startAssociation("s");
        assertTrue(client.isUp());
        assertTrue(server.isUp());

        client.send(new PayloadData(4, new byte[] {1, 2, 3, 4}, true, false, SctpRecord.M3UA_PPID, 1));
        assertEquals(1, right.payloads.size());
        assertEquals(SctpRecord.M3UA_PPID, right.payloads.getFirst().getPayloadProtocolId());
        assertEquals(1, right.payloads.getFirst().getStreamNumber());
        mgmt.stop();
    }

    @Test
    void generationFenceDropsStalePayload() throws Exception {
        FstackSctpManagementImpl mgmt = new FstackSctpManagementImpl("fence");
        mgmt.setDataplane(SctpDataplane.LOOPBACK);
        mgmt.start();
        FstackAssociationImpl assoc = (FstackAssociationImpl) mgmt.addAssociation(
                "127.0.0.1", 1, "127.0.0.1", 2, "a", IpChannelType.SCTP, null);
        AtomicInteger seen = new AtomicInteger();
        assoc.setAssociationListener(new RecordingListener() {
            @Override
            public void onPayload(Association association, PayloadData payloadData) {
                seen.incrementAndGet();
            }
        });
        mgmt.startAssociation("a");
        long first = assoc.generation();
        assoc.markUp(16, 16);
        long second = assoc.generation();
        assertTrue(second > first);
        assoc.deliver(new SctpRecord(assoc.associationId(), first, SctpEventKind.PAYLOAD, 0,
                SctpRecord.M3UA_PPID, false, 0, 16, 16, new byte[] {9}));
        assertEquals(0, seen.get());
        assertEquals(1, assoc.staleDrops());
        assoc.deliver(new SctpRecord(assoc.associationId(), second, SctpEventKind.PAYLOAD, 0,
                SctpRecord.M3UA_PPID, false, 0, 16, 16, new byte[] {8}));
        assertEquals(1, seen.get());
        mgmt.stop();
    }

    @Test
    void nativeImageForbidsNettyBackendClassName() {
        assertFalse(org.mobicents.protocols.sctp.spi.SctpBackend.NETTY_KERNEL.nativeSafe());
        assertTrue(org.mobicents.protocols.sctp.spi.SctpBackend.FSTACK_DPDK.nativeSafe());
        assertEquals("org.mobicents.protocols.sctp.fstack.FstackSctpManagementImpl",
                org.mobicents.protocols.sctp.spi.SctpProvider.implementationClass(
                        org.mobicents.protocols.sctp.spi.SctpBackend.FSTACK_DPDK));
    }

    private static class RecordingListener implements AssociationListener {
        final List<PayloadData> payloads = new ArrayList<>();

        @Override public void onCommunicationUp(Association association, int maxInboundStreams, int maxOutboundStreams) {}
        @Override public void onCommunicationShutdown(Association association) {}
        @Override public void onCommunicationLost(Association association) {}
        @Override public void onCommunicationRestart(Association association) {}
        @Override public void onPayload(Association association, PayloadData payloadData) {
            payloads.add(payloadData);
        }
        @Override public void inValidStreamId(PayloadData payloadData) {}
    }
}
