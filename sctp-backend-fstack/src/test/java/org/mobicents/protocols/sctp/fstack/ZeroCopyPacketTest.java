package org.mobicents.protocols.sctp.fstack;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.lang.foreign.MemorySegment;

import org.junit.jupiter.api.Test;
import org.mobicents.protocols.api.Association;
import org.mobicents.protocols.api.AssociationListener;
import org.mobicents.protocols.api.IpChannelType;
import org.mobicents.protocols.api.PayloadData;
import org.mobicents.protocols.sctp.spi.SctpDataplane;
import org.mobicents.protocols.sctp.spi.SctpPacketViews;
import org.mobicents.protocols.sctp.spi.SctpRecord;
import org.mobicents.protocols.sctp.spi.SctpTransportMode;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;

class ZeroCopyPacketTest {
    @Test
    void loopbackSharesDirectBufferAddress() throws Exception {
        FstackSctpManagementImpl mgmt = new FstackSctpManagementImpl("zc");
        mgmt.setMode(SctpTransportMode.SIDECAR);
        mgmt.setDataplane(SctpDataplane.LOOPBACK);
        mgmt.start();
        Association client = mgmt.addAssociation("127.0.0.1", 2905, "127.0.0.1", 2906, "c",
                IpChannelType.SCTP, null);
        Association server = mgmt.addAssociation("127.0.0.1", 2906, "127.0.0.1", 2905, "s",
                IpChannelType.SCTP, null);
        MemorySegment[] seen = new MemorySegment[1];
        server.setAssociationListener(new RecordingListener() {
            @Override
            public void onPayload(Association association, PayloadData payloadData) {
                seen[0] = SctpPacketViews.viewOf(payloadData);
            }
        });
        client.setAssociationListener(new RecordingListener());
        mgmt.startAssociation("c");
        mgmt.startAssociation("s");

        ByteBuf direct = Unpooled.directBuffer(4);
        direct.writeBytes(new byte[] {1, 2, 3, 4});
        MemorySegment source = SctpPacketViews.viewOf(direct);
        client.send(new PayloadData(4, direct, true, false, SctpRecord.M3UA_PPID, 1));
        assertEquals(source.address(), seen[0].address());
        assertEquals(4, seen[0].byteSize());
        mgmt.stop();
        direct.release();
    }

    @Test
    void m3uaRejectsUnordered() throws Exception {
        FstackSctpManagementImpl mgmt = new FstackSctpManagementImpl("ord");
        mgmt.setDataplane(SctpDataplane.LOOPBACK);
        mgmt.start();
        Association client = mgmt.addAssociation("127.0.0.1", 1, "127.0.0.1", 2, "c",
                IpChannelType.SCTP, null);
        client.setAssociationListener(new RecordingListener());
        mgmt.startAssociation("c");
        assertThrows(IllegalArgumentException.class, () ->
                client.send(new PayloadData(1, new byte[] {1}, true, true, SctpRecord.M3UA_PPID, 1)));
        assertThrows(IllegalArgumentException.class, () ->
                client.send(new PayloadData(1, new byte[] {1}, false, false, SctpRecord.M3UA_PPID, 1)));
        mgmt.stop();
    }

    @Test
    void abiConstantsMatchPackedCLayout() {
        assertEquals(48, FstackAbi.FRAME_SIZE);
        assertEquals(0x53465431, FstackAbi.MAGIC);
        assertEquals(0, FstackAbi.M3UA_MANAGEMENT_STREAM);
        assertEquals(3, SctpRecord.M3UA_PPID);
        assertEquals(0, FstackAbi.FLAG_COMPLETE & FstackAbi.FLAG_SHARED);
        assertEquals(0, FstackAbi.FLAG_COMPLETE & FstackAbi.FLAG_UNORDERED);
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
