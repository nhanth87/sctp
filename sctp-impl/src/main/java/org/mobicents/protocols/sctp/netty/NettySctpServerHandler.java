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

import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.sctp.SctpChannel;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.Map;

import org.apache.log4j.Logger;
import org.mobicents.protocols.api.Association;
import org.mobicents.protocols.api.AssociationType;
import org.mobicents.protocols.api.IpChannelType;

/**
 * @author <a href="mailto:amit.bhayani@telestax.com">Amit Bhayani</a>
 * @author <a href="mailto:nhanth87@gmail.com">nhanth87</a> — peer IP normalize + SCTP COMM_UP race recovery
 */
public class NettySctpServerHandler extends NettySctpChannelInboundHandlerAdapter {

    Logger logger = Logger.getLogger(NettySctpServerHandler.class);

    private final NettyServerImpl serverImpl;
    private final NettySctpManagementImpl managementImpl;

    public NettySctpServerHandler(NettyServerImpl serverImpl, NettySctpManagementImpl managementImpl) {
        this.serverImpl = serverImpl;
        this.managementImpl = managementImpl;
        logger.debug(String.format("JENNY-DEBUG-HANDLER-CTOR: Server=%s, Management hash=%d", serverImpl.getName(),
                System.identityHashCode(managementImpl)));
    }

    @Override
    public void channelUnregistered(final ChannelHandlerContext ctx) throws Exception {
        logger.warn(String.format("ChannelUnregistered event: association=%s", association));
        if (association != null) {
            this.association.setChannelHandler(null);
        }
    }

    @Override
    public void channelRegistered(ChannelHandlerContext ctx) throws Exception {
        if (logger.isDebugEnabled()) {
            logger.debug(String.format("channelRegistered event: association=%s", this.association));
        }

        Channel channel = ctx.channel();
        InetSocketAddress sockAdd = ((InetSocketAddress) channel.remoteAddress());
        String host = sockAdd.getAddress().getHostAddress();
        int port = sockAdd.getPort();

        boolean provisioned = false;

        if (logger.isDebugEnabled()) {
            logger.debug(String.format("Received connect request from peer host=%s port=%d", host, port));
        }

        Map<String, Association> associations = this.managementImpl.getAssociations();
        logger.debug(String.format(
                "JENNY-DEBUG-SERVER: Server=%s checking connection from %s:%d, management associations count=%d",
                serverImpl.getName(), host, port, associations.size()));
        for (Map.Entry<String, Association> entry : associations.entrySet()) {
            NettyAssociationImpl candidate = (NettyAssociationImpl) entry.getValue();
            logger.debug(String.format(
                    "JENNY-DEBUG-SERVER: Checking association=%s, serverName=%s, type=%s, peer=%s:%d",
                    candidate.getName(), candidate.getServerName(), candidate.getAssociationType(),
                    candidate.getPeerAddress(), candidate.getPeerPort()));

            if (serverImpl.getName().equals(candidate.getServerName())
                    && candidate.getAssociationType() == AssociationType.SERVER) {
                if (peerMatches(host, port, candidate)) {
                    provisioned = true;

                    if (!candidate.isStarted()) {
                        logger.error(String.format(
                                "Received connect request for Association=%s but not started yet. Dropping the connection!",
                                candidate.getName()));
                        channel.close();
                        return;
                    }

                    bindAssociation(ctx, channel, candidate);

                    if (logger.isInfoEnabled()) {
                        logger.info(String.format("Connected %s", candidate));
                    }

                    if (candidate.getIpChannelType() == IpChannelType.TCP) {
                        candidate.markAssociationUp(1, 1);
                    }

                    break;
                }
            }
        }

        if (!provisioned && serverImpl.isAcceptAnonymousConnections() && this.managementImpl.getServerListener() != null) {
            if (serverImpl.getMaxConcurrentConnectionsCount() > 0
                    && serverImpl.anonymAssociations.size() >= serverImpl.getMaxConcurrentConnectionsCount()) {
                logger.warn(String.format(
                        "Incoming anonymous connection is rejected because of too many active connections to Server=%s",
                        serverImpl));
                channel.close();
                return;
            }

            provisioned = true;

            NettyAssociationImpl anonymAssociation = new NettyAssociationImpl(host, port, serverImpl.getName(),
                    serverImpl.getIpChannelType(), serverImpl);
            anonymAssociation.setManagement(this.managementImpl);

            try {
                this.managementImpl.getServerListener().onNewRemoteConnection(serverImpl, anonymAssociation);
            } catch (Throwable e) {
                logger.warn(String.format("Exception when invoking ServerListener.onNewRemoteConnection() Ass=%s",
                        anonymAssociation), e);
                channel.close();
                return;
            }

            if (!anonymAssociation.isStarted()) {
                logger.info(String.format("Rejected anonymous %s", anonymAssociation));
                channel.close();
                return;
            }

            bindAssociation(ctx, channel, anonymAssociation);

            if (logger.isInfoEnabled()) {
                logger.info(String.format("Accepted anonymous %s", anonymAssociation));
            }

            if (anonymAssociation.getIpChannelType() == IpChannelType.TCP) {
                anonymAssociation.markAssociationUp(1, 1);
            }
        }

        if (!provisioned) {
            logger.warn(String.format("Received connect request from non provisioned %s:%d address. Closing Channel", host,
                    port));
            ctx.close();
        }
    }

    /**
     * SCTP COMM_UP can race ahead of {@link #channelRegistered}; recover by marking up once the
     * provisioned association is bound and the channel is already active.
     */
    @Override
    public void channelActive(ChannelHandlerContext ctx) {
        if (logger.isDebugEnabled()) {
            logger.debug("channelActive event: association=" + this.association);
        }
        maybeMarkSctpUp(ctx);
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        String name = this.association != null ? this.association.getName() : "<unbound>";
        logger.error("ExceptionCaught for Association: " + name + "\n", cause);
        ctx.close();
    }

    private void bindAssociation(ChannelHandlerContext ctx, Channel channel, NettyAssociationImpl assoc) {
        this.association = assoc;
        this.channel = channel;
        this.ctx = ctx;
        assoc.setChannelHandler(this);
        maybeMarkSctpUp(ctx);
    }

    private void maybeMarkSctpUp(ChannelHandlerContext ctx) {
        if (this.association == null || this.association.getIpChannelType() != IpChannelType.SCTP) {
            return;
        }
        if (this.association.isUp()) {
            return;
        }
        if (ctx == null || !ctx.channel().isActive()) {
            return;
        }
        int in = 32;
        int out = 32;
        try {
            Channel ch = ctx.channel();
            if (ch instanceof SctpChannel) {
                com.sun.nio.sctp.Association nativeAssoc = ((SctpChannel) ch).association();
                if (nativeAssoc != null) {
                    in = nativeAssoc.maxInboundStreams();
                    out = nativeAssoc.maxOutboundStreams();
                }
            }
        } catch (Throwable t) {
            logger.debug("Could not read native SCTP association streams; using defaults", t);
        }
        logger.info(String.format(
                "Marking SCTP Association=%s UP from channelActive/bind (streams in=%d out=%d)",
                this.association.getName(), in, out));
        this.association.markAssociationUp(in, out);
    }

    static boolean peerMatches(String remoteHost, int remotePort, NettyAssociationImpl assoc) {
        if (!(remotePort == assoc.getPeerPort() || assoc.getPeerPort() == 0)) {
            return false;
        }
        return ipEquals(remoteHost, assoc.getPeerAddress());
    }

    /** Normalize IPv4-mapped / loopback forms so 127.0.0.1 matches ::ffff:127.0.0.1 and ::1. */
    static boolean ipEquals(String a, String b) {
        if (a == null || b == null) {
            return false;
        }
        if (a.equals(b)) {
            return true;
        }
        String na = normalizeIp(a);
        String nb = normalizeIp(b);
        if (na.equals(nb)) {
            return true;
        }
        try {
            InetAddress ia = InetAddress.getByName(a);
            InetAddress ib = InetAddress.getByName(b);
            return ia.equals(ib);
        } catch (Exception ignore) {
            return false;
        }
    }

    static String normalizeIp(String host) {
        if (host == null) {
            return "";
        }
        String h = host.trim();
        if (h.regionMatches(true, 0, "::ffff:", 0, 7)) {
            h = h.substring(7);
        }
        if ("::1".equals(h) || "0:0:0:0:0:0:0:1".equals(h)
                || "https://example.com/p/local".equalsIgnoreCase(h)) {
            return "127.0.0.1";
        }
        return h;
    }
}
