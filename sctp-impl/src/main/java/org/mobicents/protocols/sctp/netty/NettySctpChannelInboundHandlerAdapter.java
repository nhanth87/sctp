/*
 * TeleStax, Open Source Cloud Communications
 * Copyright 2011-2014, Telestax Inc and individual contributors
 * by the @authors tag.
 *
 * This program is free software: you can redistribute it and/or modify
 * under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation; either version 3 of
 * the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>
 *
 */

package org.mobicents.protocols.sctp.netty;

import io.netty.buffer.ByteBuf;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.sctp.SctpMessage;
import io.netty.util.ReferenceCountUtil;

import org.apache.log4j.Level;
import org.apache.log4j.Logger;
import org.apache.log4j.Priority;
import org.mobicents.protocols.api.IpChannelType;
import org.mobicents.protocols.api.PayloadData;
import org.mobicents.protocols.api.PayloadDataPool;

import com.sun.nio.sctp.AssociationChangeNotification;
import com.sun.nio.sctp.PeerAddressChangeNotification;
import com.sun.nio.sctp.SendFailedNotification;
import com.sun.nio.sctp.ShutdownNotification;

/**
 * @author <a href="mailto:nhanth87@gmail.com">nhanth87</a>
 * 
 */
public class NettySctpChannelInboundHandlerAdapter extends ChannelInboundHandlerAdapter {

    Logger logger = Logger.getLogger(NettySctpChannelInboundHandlerAdapter.class);

    // Default value is 32 for SCTP (increased from 1)
    private volatile int maxInboundStreams = 32;
    private volatile int maxOutboundStreams = 32;

    protected NettyAssociationImpl association = null;

    protected Channel channel = null;
    protected ChannelHandlerContext ctx = null;

    protected long lastCongestionMonitorSecondPart;

    /**
     * 
     */
    public NettySctpChannelInboundHandlerAdapter() {
        // TODO Auto-generated constructor stub
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) {
        if (logger.isEnabledFor(Level.DEBUG)) {
            logger.debug(String.format("channelInactive event: association=%s", this.association));
        }

        if (this.association != null)
            this.association.markAssociationDown();
    }

    @Override
    public void userEventTriggered(ChannelHandlerContext ctx, Object evt) throws Exception {
        if (logger.isEnabledFor(Level.DEBUG)) {
            logger.debug(String.format("userEventTriggered event: association=%s \nevent=%s", this.association, evt));
        }

        if (evt instanceof AssociationChangeNotification) {
            // SctpAssocChange not = (SctpAssocChange) evt;
            AssociationChangeNotification not = (AssociationChangeNotification) evt;

            switch (not.event()) {
                case COMM_UP:
                    if (not.association() != null) {
                        this.maxOutboundStreams = not.association().maxOutboundStreams();
                        this.maxInboundStreams = not.association().maxInboundStreams();
                    }

                    if (logger.isEnabledFor(Level.INFO)) {
                        logger.info(String.format(
                                "New association setup for Association=%s with %d outbound streams, and %d inbound streams.\n",
                                association.getName(), this.maxOutboundStreams, this.maxInboundStreams));
                    }

                    this.association.markAssociationUp(this.maxInboundStreams, this.maxOutboundStreams);
                    break;
                case CANT_START:
                    logger.error(String.format("Can't start for Association=%s", association.getName()));
                    break;
                case COMM_LOST:
                    logger.warn(String.format("Communication lost for Association=%s", association.getName()));

                    // Close the Socket
                    association.getAssociationListener().onCommunicationLost(association);
                    ctx.close();
//                    if (association.getAssociationType() == AssociationType.CLIENT) {
//                        association.scheduleConnect();
//                    }
                    break;
                case RESTART:
                    logger.warn(String.format("Restart for Association=%s", association.getName()));
                    try {
                        association.getAssociationListener().onCommunicationRestart(association);
                    } catch (Exception e) {
                        logger.error(String.format(
                                "Exception while calling onCommunicationRestart on AssociationListener for Association=%s",
                                association.getName()), e);
                    }
                    break;
                case SHUTDOWN:
                    if (logger.isEnabledFor(Level.INFO)) {
                        logger.info(String.format("Shutdown for Association=%s", association.getName()));
                    }
//                    try {
//                        association.markAssociationDown();
//                    } catch (Exception e) {
//                        logger.error(String.format(
//                                "Exception while calling onCommunicationShutdown on AssociationListener for Association=%s",
//                                association.getName()), e);
//                    }
                    break;
                default:
                    logger.warn(String.format("Received unknown Event=%s for Association=%s", not.event(), association.getName()));
                    break;
            }
        }

        if (evt instanceof PeerAddressChangeNotification) {
            PeerAddressChangeNotification notification = (PeerAddressChangeNotification) evt;

            if (logger.isEnabledFor(Level.WARN)) {
                logger.warn(String.format("Peer Address changed to=%s for Association=%s", notification.address(),
                        association.getName()));
            }

            // Notify listener about peer address change for multihoming support
            String changeType = notification.event().name();
            String peerAddress = notification.address().toString();
            try {
                association.getAssociationListener().onPeerAddressChanged(association, peerAddress, changeType);
            } catch (Exception e) {
                logger.error(String.format("Exception while calling onPeerAddressChanged for Association=%s",
                        association.getName()), e);
            }

            // Handle specific address change events
            switch (notification.event()) {
                case ADDR_MADE_PRIMARY:
                    if (logger.isEnabledFor(Level.INFO)) {
                        logger.info(String.format("Peer address=%s is now primary for Association=%s", 
                                peerAddress, association.getName()));
                    }
                    break;
                case ADDR_UNREACHABLE:
                    if (logger.isEnabledFor(Level.WARN)) {
                        logger.warn(String.format("Peer address=%s is unreachable for Association=%s", 
                                peerAddress, association.getName()));
                    }
                    break;
                case ADDR_AVAILABLE:
                    if (logger.isEnabledFor(Level.INFO)) {
                        logger.info(String.format("Peer address=%s is now available for Association=%s", 
                                peerAddress, association.getName()));
                    }
                    break;
                default:
                    if (logger.isEnabledFor(Level.DEBUG)) {
                        logger.debug(String.format("Peer address event=%s for address=%s, Association=%s", 
                                notification.event(), peerAddress, association.getName()));
                    }
                    break;
            }

        } else if (evt instanceof SendFailedNotification) {
            SendFailedNotification notification = (SendFailedNotification) evt;
            logger.error(String.format("Association=" + association.getName() + " SendFailedNotification, errorCode="
                    + notification.errorCode()));

        } else if (evt instanceof ShutdownNotification) {
            ShutdownNotification notification = (ShutdownNotification) evt;

            if (logger.isEnabledFor(Level.INFO)) {
                logger.info(String.format("Association=%s SHUTDOWN", association.getName()));
            }

            // TODO assign Thread's ?

//            try {
//                association.markAssociationDown();
//                association.getAssociationListener().onCommunicationShutdown(association);
//            } catch (Exception e) {
//                logger.error(String.format(
//                        "Exception while calling onCommunicationShutdown on AssociationListener for Association=%s",
//                        association.getName()), e);
//            }
        }// if (evt instanceof AssociationChangeNotification)

    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) {
        try {
            // Retain the message as it may be released by upstream MessageToMessageDecoder
            ReferenceCountUtil.retain(msg);
            PayloadData payload;
            PayloadDataPool pool = this.association.getManagement().getPayloadDataPool();
            
            if (this.association.getIpChannelType() == IpChannelType.SCTP) {
                SctpMessage sctpMessage = (SctpMessage) msg;
                ByteBuf byteBuf = sctpMessage.content();
                // Retain the ByteBuf as it will be passed to the association layer
                byteBuf.retain();
                payload = pool.acquire(byteBuf.readableBytes(), byteBuf, sctpMessage.isComplete(), sctpMessage.isUnordered(),
                        sctpMessage.protocolIdentifier(), sctpMessage.streamIdentifier());
            } else {
                ByteBuf byteBuf = (ByteBuf) msg;
                // Retain the ByteBuf as it will be passed to the association layer
                byteBuf.retain();
                payload = pool.acquireTcp(byteBuf.readableBytes(), byteBuf);
            }

            if (logger.isEnabledFor(Level.DEBUG)) {
                logger.debug(String.format("Rx : Ass=%s %s", this.association.getName(), payload));
            }

            this.association.read(payload);
        } finally {
            // Always release the original message to prevent memory leak
            ReferenceCountUtil.release(msg);
        }
    }

    protected void writeAndFlush(Object message) {
        Channel ch = this.channel;
        if (ch == null || !ch.isActive()) {
            // Channel is not available or inactive, release the message to prevent memory leak
            ReferenceCountUtil.release(message);
            if (logger.isEnabledFor(Level.DEBUG)) {
                logger.debug(String.format("Channel not available or inactive for Association=%s, message dropped", 
                        this.association.getName()));
            }
            return;
        }
        
        // Check writability for backpressure handling
        // Congestion logging removed for high-throughput testing
        
        ChannelFuture future = ch.writeAndFlush(message);
        
        // Add listener to handle write failures
        future.addListener(new ChannelFutureListener() {
            @Override
            public void operationComplete(ChannelFuture future) throws Exception {
                if (!future.isSuccess()) {
                    logger.error(String.format("Failed to write message for Association=%s", 
                            association.getName()), future.cause());
                }
            }
        });

        long curMillisec = System.currentTimeMillis();
        long secPart = curMillisec / 500;
        if (lastCongestionMonitorSecondPart < secPart) {
            lastCongestionMonitorSecondPart = secPart;
            CongestionMonitor congestionMonitor = new CongestionMonitor();
            future.addListener(congestionMonitor);
        }
     }

    private void onCongestionMonitor(double delaySec) {
        int newAlarmLevel = this.association.getCongestionLevel();
        for (int i1 = this.association.getCongestionLevel() - 1; i1 >= 0; i1--) {
            if (delaySec <= this.association.getManagement().congControl_BackToNormalDelayThreshold[i1]) {
                newAlarmLevel = i1;
            }
        }
        for (int i1 = this.association.getCongestionLevel(); i1 < 3; i1++) {
            if (delaySec >= this.association.getManagement().congControl_DelayThreshold[i1]) {
                newAlarmLevel = i1 + 1;
            }
        }
        this.association.setCongestionLevel(newAlarmLevel);
    }

    private class CongestionMonitor implements ChannelFutureListener {
        long startTime = System.currentTimeMillis();

        @Override
        public void operationComplete(ChannelFuture arg0) throws Exception {
            long delay = System.currentTimeMillis() - startTime;
            double delaySec = (double) delay / 1000;
            onCongestionMonitor(delaySec);
        }

    }

    protected void closeChannel() {
        Channel ch = this.channel;
        if (ch != null) {
            try {
                ch.close().sync();
            } catch (InterruptedException e) {
                logger.error(String.format("Error while trying to close Channel for Association %s",
                        this.association.getName(), e));
            }
        }
    }

}






