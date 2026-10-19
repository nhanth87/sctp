package org.mobicents.protocols.sctp.netty;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.mobicents.protocols.api.Association;
import org.mobicents.protocols.api.AssociationListener;
import org.mobicents.protocols.api.IpChannelType;
import org.mobicents.protocols.api.PayloadData;
import org.testng.annotations.Test;

/**
 * Replays a production outage (2026-10-08) without sockets: the peer restarts the SCTP
 * association, the M3UA layer answers with stop() + start(), the peer's new connection binds and
 * comes up, and only then do the old channel's channelInactive / channelUnregistered arrive.
 * Before the fix the late events took the live association down and unbound its handler: payload
 * kept arriving on the new channel but the ASP was DOWN and every send failed.
 */
public class NettyReplacedChannelTest {

    private static final class RecordingListener implements AssociationListener {
        int up;
        int shutdown;

        @Override
        public void onCommunicationUp(Association association, int maxInboundStreams, int maxOutboundStreams) {
            up++;
        }

        @Override
        public void onCommunicationShutdown(Association association) {
            shutdown++;
        }

        @Override
        public void onCommunicationLost(Association association) {
        }

        @Override
        public void onCommunicationRestart(Association association) {
        }

        @Override
        public void onPayload(Association association, PayloadData payloadData) {
        }

        @Override
        public void inValidStreamId(PayloadData payloadData) {
        }
    }

    private static NettySctpChannelInboundHandlerAdapter handlerFor(NettyAssociationImpl association) {
        NettySctpChannelInboundHandlerAdapter handler = new NettySctpChannelInboundHandlerAdapter();
        handler.association = association;
        association.setChannelHandler(handler);
        return handler;
    }

    private static NettyAssociationImpl serverAssociation(RecordingListener listener) throws Exception {
        NettyAssociationImpl association = new NettyAssociationImpl("192.0.2.10", 2905, "srv", "assoc",
                IpChannelType.SCTP);
        association.setManagement(new NettySctpManagementImpl("replaced-channel-test"));
        association.setAssociationListener(listener);
        association.start();
        return association;
    }

    @Test(groups = { "functional" })
    public void lateCloseOfReplacedChannelLeavesNewChannelUp() throws Exception {
        RecordingListener listener = new RecordingListener();
        NettyAssociationImpl association = serverAssociation(listener);

        NettySctpChannelInboundHandlerAdapter oldHandler = handlerFor(association);
        association.markAssociationUp(16, 10);

        // RESTART -> AspFactoryImpl.onCommunicationRestart -> stop() + start()
        association.stop();
        assertFalse("stop() must take the association down at once", association.isUp());
        assertEquals("the ASP must see the restart as a comm-down", 1, listener.shutdown);
        association.start();

        // The peer reconnects before the old channel's close events are delivered.
        NettySctpChannelInboundHandlerAdapter newHandler = handlerFor(association);
        association.markAssociationUp(16, 10);
        assertEquals(2, listener.up);

        oldHandler.channelInactive(null);
        assertTrue("a replaced channel's channelInactive must not take the association down",
                association.isConnected());
        assertEquals(1, listener.shutdown);

        assertFalse("a replaced channel must not unbind the live handler",
                association.releaseChannelHandler(oldHandler));
        assertTrue(association.isSuperseded(oldHandler));
        assertFalse(association.isSuperseded(newHandler));

        // The live channel's own close still takes the association down.
        newHandler.channelInactive(null);
        assertFalse(association.isUp());
        assertEquals(2, listener.shutdown);
        assertTrue(association.releaseChannelHandler(newHandler));
    }

    @Test(groups = { "functional" })
    public void closeAfterStopIsHarmlessWhenNoNewChannelYet() throws Exception {
        RecordingListener listener = new RecordingListener();
        NettyAssociationImpl association = serverAssociation(listener);

        NettySctpChannelInboundHandlerAdapter oldHandler = handlerFor(association);
        association.markAssociationUp(16, 10);
        association.stop();

        oldHandler.channelInactive(null);
        assertFalse(association.isUp());
        assertEquals("comm-down is reported once, not again by the late channelInactive", 1, listener.shutdown);
        assertFalse(association.isSuperseded(oldHandler));
    }
}
