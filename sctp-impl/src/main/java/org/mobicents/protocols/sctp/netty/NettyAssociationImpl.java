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

import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import io.netty.channel.*;
import io.netty.channel.epoll.EpollEventLoopGroup;
import io.netty.channel.sctp.SctpChannel;
import io.netty.channel.sctp.SctpChannelOption;
import io.netty.channel.sctp.SctpMessage;
import io.netty.channel.sctp.nio.NioSctpChannel;
import io.netty.channel.socket.nio.NioSocketChannel;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.security.spec.ECField;
import java.util.concurrent.TimeUnit;

import javolution.xml.XMLFormat;
import javolution.xml.stream.XMLStreamException;

import net.openhft.chronicle.queue.ExcerptAppender;
import net.openhft.chronicle.queue.ExcerptTailer;
import net.openhft.chronicle.wire.DocumentContext;
import net.openhft.chronicle.wire.WireIn;
import net.openhft.chronicle.wire.WireOut;
import org.apache.log4j.Logger;

import org.mobicents.protocols.api.Association;
import org.mobicents.protocols.api.AssociationListener;
import org.mobicents.protocols.api.AssociationType;
import org.mobicents.protocols.api.CongestionListener;
import org.mobicents.protocols.api.IpChannelType;
import org.mobicents.protocols.api.ManagementEventListener;
import org.mobicents.protocols.api.PayloadData;

import net.openhft.chronicle.queue.ChronicleQueue;

import javax.swing.text.Document;

/**
 * @author <a href="mailto:amit.bhayani@telestax.com">Amit Bhayani</a>
 * 
 */
public class NettyAssociationImpl implements Association {

    protected static final Logger logger = Logger.getLogger(NettyAssociationImpl.class.getName());

    private static final String NAME = "name";
    private static final String SERVER_NAME = "serverName";
    private static final String HOST_ADDRESS = "hostAddress";
    private static final String HOST_PORT = "hostPort";

    private static final String PEER_ADDRESS = "peerAddress";
    private static final String PEER_PORT = "peerPort";

    private static final String ASSOCIATION_TYPE = "associationType";
    private static final String IP_CHANNEL_TYPE = "ipChannelType";
    private static final String EXTRA_HOST_ADDRESS = "extraHostAddress";
    private static final String EXTRA_HOST_ADDRESSES_SIZE = "extraHostAddressesSize";

    private String hostAddress;
    private int hostPort;
    private String peerAddress;
    private int peerPort;
    private String serverName;
    private String name;
    private IpChannelType ipChannelType;
    private String[] extraHostAddresses;
    private NettyServerImpl server; // this is filled only for anonymous Associations

    private AssociationType type;

    private AssociationListener associationListener = null;

    private NettySctpManagementImpl management;

    // Is the Association been started by management?
    private volatile boolean started = false;

    private volatile boolean isFirstStart = true;
    // Is the Association up (connection is established)
    protected volatile boolean up = false;

    private NettySctpChannelInboundHandlerAdapter channelHandler;
    protected int congLevel;
    private ChronicleQueue toSS7Queue = null; //from sctp -> jss7
    private ExcerptAppender toSs7QueueAppender = null;

    private ChronicleQueue toSctpQueue = null; // from jss7 -> sctp
    private ExcerptTailer toSctpTailer = null;
    private ExcerptAppender toSctpAppender;

    // Getter and Setter for queue
    @Override
    public ChronicleQueue getToSS7Queue() {
        return this.toSS7Queue;
    }
    @Override
    public void setToSS7Queue(ChronicleQueue toSS7Queue) {
        this.toSS7Queue = toSS7Queue;
    }

    // Getter and Setter for appender
    @Override
    public ExcerptAppender getToSs7QueueAppender() {
        return this.toSs7QueueAppender;
    }
    @Override
    public void setToSs7QueueAppender(ExcerptAppender toSs7QueueAppender) {
        this.toSs7QueueAppender = toSs7QueueAppender;
    }
    @Override
    public ChronicleQueue getToSctpQueue() {
        return this.toSctpQueue;
    }

    @Override
    public void setToSctpQueue(ChronicleQueue toSctpQueue) {
        this.toSctpQueue = toSS7Queue;
    }

    @Override
    public final ExcerptTailer getToSctpTailer()
    {
        return this.toSctpTailer;
    }

    @Override
    public ExcerptAppender getToSctpAppender() {
        return this.toSctpAppender;
    }

    public NettyAssociationImpl() {
        super();
        this.toSS7Queue = ChronicleQueue.singleBuilder("readqueue-" + this.name).build();
        this.toSctpQueue = ChronicleQueue.singleBuilder("writequeue-" + this.name).build();

        this.toSs7QueueAppender = toSS7Queue.createAppender();
        this.toSctpTailer = toSctpQueue.createTailer();
        this.toSctpAppender = toSctpQueue.createAppender();
    }

    /**
     * Creating a CLIENT Association
     *
     * @param hostAddress
     * @param hostPort
     * @param peerAddress
     * @param peerPort
     * @param associationName
     * @param ipChannelType
     * @param extraHostAddresses
     * @throws IOException
     */
    public NettyAssociationImpl(String hostAddress, int hostPort, String peerAddress, int peerPort, String associationName,
            IpChannelType ipChannelType, String[] extraHostAddresses) throws IOException {
        this();
        this.hostAddress = hostAddress;
        this.hostPort = hostPort;
        this.peerAddress = peerAddress;
        this.peerPort = peerPort;
        this.name = associationName;
        this.ipChannelType = ipChannelType;
        this.extraHostAddresses = extraHostAddresses;

        this.type = AssociationType.CLIENT;
    }

    /**
     * Creating a SERVER Association
     *
     * @param peerAddress
     * @param peerPort
     * @param serverName
     * @param assocName
     * @param ipChannelType
     */
    public NettyAssociationImpl(String peerAddress, int peerPort, String serverName, String assocName, IpChannelType ipChannelType) {
        this();
        this.peerAddress = peerAddress;
        this.peerPort = peerPort;
        this.serverName = serverName;
        this.name = assocName;
        this.ipChannelType = ipChannelType;

        this.type = AssociationType.SERVER;
    }

    /**
     * Creating an ANONYMOUS_SERVER Association
     *
     * @param peerAddress
     * @param peerPort
     * @param peerAddress
     * @param peerPort
     * @param serverName
     * @param ipChannelType
     * @param server
     */
    protected NettyAssociationImpl(String peerAddress, int peerPort, String serverName, IpChannelType ipChannelType, NettyServerImpl server) {
        this();
        this.peerAddress = peerAddress;
        this.peerPort = peerPort;
        this.serverName = serverName;
        this.ipChannelType = ipChannelType;
        this.server = server;

        this.type = AssociationType.ANONYMOUS_SERVER;
        this.toSS7Queue = ChronicleQueue.singleBuilder("queue-" + this.name).build();

    }

    public NettySctpManagementImpl getManagement() {
        return management;
    }

    /*
     * (non-Javadoc)
     * 
     * @see org.mobicents.protocols.api.Association#getIpChannelType()
     */
    @Override
    public IpChannelType getIpChannelType() {
        return this.ipChannelType;
    }

    public void setIpChannelType(IpChannelType ipChannelType) {
        this.ipChannelType = ipChannelType;
    }

    /*
     * (non-Javadoc)
     * 
     * @see org.mobicents.protocols.api.Association#getAssociationType()
     */
    @Override
    public AssociationType getAssociationType() {
        return this.type;
    }

    /*
     * (non-Javadoc)
     * 
     * @see org.mobicents.protocols.api.Association#getName()
     */
    @Override
    public String getName() {
        return this.name;
    }

    /*
     * (non-Javadoc)
     * 
     * @see org.mobicents.protocols.api.Association#isStarted()
     */
    @Override
    public boolean isStarted() {
        return this.started;
    }

    /*
     * (non-Javadoc)
     * 
     * @see org.mobicents.protocols.api.Association#isConnected()
     */
    @Override
    public boolean isConnected() {
        return started && up;
    }

    /*
     * (non-Javadoc)
     * 
     * @see org.mobicents.protocols.api.Association#isUp()
     */
    @Override
    public boolean isUp() {
        return up;
    }

    /*
     * (non-Javadoc)
     * 
     * @see org.mobicents.protocols.api.Association#getAssociationListener()
     */
    @Override
    public AssociationListener getAssociationListener() {
        return this.associationListener;
    }

    /*
     * (non-Javadoc)
     * 
     * @see org.mobicents.protocols.api.Association#setAssociationListener(org.mobicents.protocols.api.AssociationListener)
     */
    @Override
    public void setAssociationListener(AssociationListener associationListener) {
        this.associationListener = associationListener;

    }

    /*
     * (non-Javadoc)
     * 
     * @see org.mobicents.protocols.api.Association#getHostAddress()
     */
    @Override
    public String getHostAddress() {
        return hostAddress;
    }

    public void setHostAddress(String hostAddress) {
        this.hostAddress = hostAddress;
    }

    /*
     * (non-Javadoc)
     * 
     * @see org.mobicents.protocols.api.Association#getHostPort()
     */
    @Override
    public int getHostPort() {
        return hostPort;
    }

    public void setHostPort(Integer hostPort) {
        this.hostPort = hostPort;
    }

    /*
     * (non-Javadoc)
     * 
     * @see org.mobicents.protocols.api.Association#getPeerAddress()
     */
    @Override
    public String getPeerAddress() {
        return peerAddress;
    }

    public void setPeerAddress(String peerAddress) {
        this.peerAddress = peerAddress;
    }

    /*
     * (non-Javadoc)
     * 
     * @see org.mobicents.protocols.api.Association#getPeerPort()
     */
    @Override
    public int getPeerPort() {
        return peerPort;
    }

    public void setPeerPort(Integer peerPort) {
        this.peerPort = peerPort;
    }

    /*
     * (non-Javadoc)
     * 
     * @see org.mobicents.protocols.api.Association#getServerName()
     */
    @Override
    public String getServerName() {
        return serverName;
    }

    public void setServerName(String serverName) {
        this.serverName = serverName;
    }

    /*
     * (non-Javadoc)
     * 
     * @see org.mobicents.protocols.api.Association#getExtraHostAddresses()
     */
    @Override
    public String[] getExtraHostAddresses() {
        return extraHostAddresses;
    }

    public void setExtraHostAddresses(String[] extraHostAddresses) {
        this.extraHostAddresses = extraHostAddresses;
    }

    /*
     * (non-Javadoc)
     * 
     * @see org.mobicents.protocols.api.Association#send(org.mobicents.protocols.api.PayloadData)
     */
    @Override
    public void send(PayloadData payloadData) throws Exception {
        if (logger.isDebugEnabled()) {
            logger.debug(String.format("Tx : Ass=%s %s", this.getName(), payloadData));
        }

        try {
            this.getToSctpAppender().writeDocument(payloadData);
        } catch (Exception e) {
            logger.warn("Can't write toSctpQueue");
        }
    }

    private NettySctpChannelInboundHandlerAdapter checkSocketIsOpen() throws Exception {
        NettySctpChannelInboundHandlerAdapter handler = this.channelHandler;
        if (!this.started || handler == null)
            throw new Exception(String.format(
                    "Association is not started or underlying sctp/tcp channel is down for Association=%s", this.name));
        return handler;
    }

    @Override
    public ByteBufAllocator getByteBufAllocator() {
        if (this.channelHandler != null)
            return this.channelHandler.channel.alloc();
        else
            return null;
    }

    @Override
    public int getCongestionLevel() {
        return this.congLevel;
    }

    protected void setCongestionLevel(int val) {
        if (this.congLevel != val) {
            logger.warn("Outgoing congestion control: SCTP: Changing of congestion level for Association=" + this.name + " "
                    + this.congLevel + "->" + val);
        }

        for (CongestionListener lstr : this.management.getCongestionListeners()) {
            try {
                lstr.onCongLevelChanged(this, this.congLevel, val);
            } catch (Throwable ee) {
                logger.error("Exception while invoking onAssociationAdded", ee);
            }
        }

        this.congLevel = val;
    }

    /*
     * (non-Javadoc)
     * 
     * @see org.mobicents.protocols.api.Association#acceptAnonymousAssociation(org.mobicents.protocols.api.AssociationListener)
     */
    @Override
    public void acceptAnonymousAssociation(AssociationListener associationListener) throws Exception {
        this.associationListener = associationListener;

        if (this.getAssociationType() != AssociationType.ANONYMOUS_SERVER) {
            throw new UnsupportedOperationException(
                    "Association.acceptAnonymousAssociation() can be applied only for anonymous associations");
        }

        this.start();
    }

    /*
     * (non-Javadoc)
     * 
     * @see org.mobicents.protocols.api.Association#rejectAnonymousAssociation()
     */
    @Override
    public void rejectAnonymousAssociation() {
    }

    /*
     * (non-Javadoc)
     * 
     * @see org.mobicents.protocols.api.Association#stopAnonymousAssociation()
     */
    @Override
    public void stopAnonymousAssociation() throws Exception {
        if (this.getAssociationType() != AssociationType.ANONYMOUS_SERVER) {
            throw new UnsupportedOperationException(
                    "Association.stopAnonymousAssociation() can be applied only for anonymous associations");
        }

        this.stop();
    }

    /*
     * (non-Javadoc)
     * 
     * @see java.lang.Object#toString()
     */
    @Override
    public String toString() {

        StringBuilder sb = new StringBuilder();
        sb.append("Association [name=").append(this.name).append(", associationType=").append(this.type)
                .append(", ipChannelType=").append(this.ipChannelType).append(", hostAddress=").append(this.hostAddress)
                .append(", hostPort=").append(this.hostPort).append(", peerAddress=").append(this.peerAddress)
                .append(", peerPort=").append(this.peerPort).append(", serverName=").append(this.serverName);

        sb.append(", extraHostAddress=[");

        if (this.extraHostAddresses != null) {
            for (int i = 0; i < this.extraHostAddresses.length; i++) {
                String extraHostAddress = this.extraHostAddresses[i];
                sb.append(extraHostAddress);
                sb.append(", ");
            }
        }

        sb.append("]]");

        return sb.toString();
    }

    /**
     * @param management the management to set
     */
    protected void setManagement(NettySctpManagementImpl management) {
        this.management = management;
    }

    protected void start() throws Exception {
        if (this.associationListener == null) {
            throw new NullPointerException(String.format("AssociationListener is null for Association=%s", this.name));
        }
        this.started = true;
        isFirstStart = false;

        if (this.type == AssociationType.CLIENT && this.isFirstStart) {
            this.scheduleConnect();

        } else if (this.type == AssociationType.CLIENT && !this.isFirstStart) {
            this.connect();
        }

        if (logger.isInfoEnabled()) {
            if (this.type != AssociationType.ANONYMOUS_SERVER) {
                logger.info(String.format("Started Association=%s", this));
            }
        }

        for (ManagementEventListener lstr : this.management.getManagementEventListeners()) {
            try {
                lstr.onAssociationStarted(this);
            } catch (Throwable ee) {
                logger.error("Exception while invoking onAssociationStarted", ee);
            }
        }
    }

    protected void stop() throws Exception {
        if (logger.isInfoEnabled()) {
            logger.info(String.format("Management requested to stop %s", this.toString()));
        }
        this.started = false;
        for (ManagementEventListener lstr : this.management.getManagementEventListeners()) {
            try {
                lstr.onAssociationStopped(this);
            } catch (Throwable ee) {
                logger.error("Exception while invoking onAssociationStopped", ee);
            }
        }

        final NettySctpChannelInboundHandlerAdapter handler = this.channelHandler;
        if (handler != null) {
            handler.closeChannel();
        }

    }

    public void read(PayloadData payload) {
        try (DocumentContext dc = this.getToSs7QueueAppender().writingDocument()) {

            //this.associationListener.onPayload(this, payload);
            //using openhft to cache payload
            WireOut wo = dc.wire();
            payload.writeMarshallable(wo);

        } catch (Exception e) {
            logger.error(String.format("Error while write to queue %s Association=%s.Payload=%s",this.getToSctpQueue().file().getName(), this.name, payload), e);
        } finally {
            payload.releaseBuffer();
        }
    }

    protected void markAssociationUp(int maxInboundStreams, int maxOutboundStreams) {
        if (this.server != null) {
            synchronized (this.server.anonymAssociations) {
                this.server.anonymAssociations.add(this);
            }
        }

        this.up = true;
        this.getAssociationListener().onCommunicationUp(this, maxInboundStreams, maxOutboundStreams);

        for (ManagementEventListener lstr : this.management.getManagementEventListeners()) {
            try {
                lstr.onAssociationUp(this);
            } catch (Throwable ee) {
                logger.error("Exception while invoking onAssociationUp", ee);
            }
        }
    }

    protected void markAssociationDown() {
        if (this.up) {
            // To avoid calling Listener again and again
            this.up = false;

            for (ManagementEventListener lstr : this.management.getManagementEventListeners()) {
                try {
                    lstr.onAssociationDown(this);
                } catch (Throwable ee) {
                    logger.error("Exception while invoking onAssociationDown", ee);
                }
            }

            this.getAssociationListener().onCommunicationShutdown(this);

            if (this.server != null) {
                synchronized (this.server.anonymAssociations) {
                    this.server.anonymAssociations.remove(this);
                }
            }
        }
    }

    protected void scheduleConnect() {
        int connectDelay = this.management.getConnectDelay();
        if (logger.isDebugEnabled()) {
            logger.debug(String.format("Scheduling of a channel connection: Association=%s, connectDelay=%d", this,
                    connectDelay));
        }

//        final ScheduledExecutorService loop = this.management.getBossGroup().next();
        final EpollEventLoopGroup loop = this.management.getClientExecutor();
        final EventLoop eventLoop = loop.next();
        eventLoop.schedule(new Runnable() {
            @Override
            public void run() {
                connect();
            }
        }, connectDelay, TimeUnit.MILLISECONDS);
    }

    private void scheduleSendMessage() {

        while (!this.getToSctpQueue().isClosing()|| !this.getToSctpQueue().isClosed() || !this.started || !this.up) {
        try {
            NettySctpChannelInboundHandlerAdapter handler = checkSocketIsOpen();
            DocumentContext dc = this.getToSctpTailer().readingDocument();
            PayloadData pd = null;
            if(dc.isPresent()) {
                WireIn  wi = dc.wire();
                pd.readMarshallable(wi);
            }

            if (pd != null) {
                if (this.ipChannelType == IpChannelType.SCTP) {
                    SctpMessage sctpMessage = new SctpMessage(pd.getPayloadProtocolId(), pd.getStreamNumber(),
                            pd.isUnordered(), pd.getByteBuf());
                    handler.writeAndFlush(sctpMessage);
                } else {
                    handler.writeAndFlush(pd.getByteBuf());
                }
            } else {
                return;
            }

        }catch (Exception e) {
            logger.error(e.getMessage());
        }
        }
    }

    protected void setChannelHandler(NettySctpChannelInboundHandlerAdapter channelHandler) {
        this.channelHandler = channelHandler;
    }

    protected void connect() {
        if (!this.started || (this.started && this.up)) {
            // return if not started and already up
            return;
        }

        if (logger.isDebugEnabled()) {
            logger.debug(String.format("Initiating connection started: Association=%s", this));
        }

        Bootstrap b;
        InetSocketAddress localAddress;
        try {
            EventLoopGroup group = this.management.getBossGroup();
            b = new Bootstrap();

            b.group(group);
            if (this.ipChannelType == IpChannelType.SCTP) {
                b.channel(NioSctpChannel.class);

                // applying of stack level SCTP options
                this.applySctpOptions(b);

                b.handler(new NettySctpClientChannelInitializer(this));
            } else {
                b.channel(NioSocketChannel.class);
                b.option(ChannelOption.TCP_NODELAY, true);
                b.handler(new NettyTcpClientChannelInitializer(this));
            }

            localAddress = new InetSocketAddress(this.hostAddress, this.hostPort);
        } catch (Exception e) {
            logger.error(String.format("Exception while creating connection for Association=%s", this.getName()), e);
            this.scheduleConnect();
            return;
        }

        // Bind the client channel.
        try {
            ChannelFuture bindFuture = b.bind(localAddress).sync();
            Channel channel = bindFuture.channel();

            if (this.ipChannelType == IpChannelType.SCTP) {
                // Get the underlying sctp channel
                SctpChannel sctpChannel = (SctpChannel) channel;

                // Bind the secondary address.
                // Please note that, bindAddress in the client channel should be done before connecting if you have not
                // enable Dynamic Address Configuration. See net.sctp.addip_enable kernel param
                if (this.extraHostAddresses != null) {
                    for (int count = 0; count < this.extraHostAddresses.length; count++) {
                        String localSecondaryAddress = this.extraHostAddresses[count];
                        InetAddress localSecondaryInetAddress = InetAddress.getByName(localSecondaryAddress);

                        sctpChannel.bindAddress(localSecondaryInetAddress).sync();
                    }
                }
            }

            InetSocketAddress remoteAddress = new InetSocketAddress(this.peerAddress, this.peerPort);

            // Finish connect
            bindFuture.channel().connect(remoteAddress);

            if (logger.isDebugEnabled()) {
                logger.debug(String.format("Initiating connection scheduled: Association=%s remoteAddress=%s", this,
                        remoteAddress));
            }
        } catch (Exception e) {
            logger.error(String.format("Exception while finishing connection for Association=%s", this.getName()), e);
        }
    }

    private void applySctpOptions(Bootstrap b) {
        b.option(SctpChannelOption.SCTP_NODELAY, this.management.getOptionSctpNodelay());
        b.option(SctpChannelOption.SCTP_DISABLE_FRAGMENTS, this.management.getOptionSctpDisableFragments());
        b.option(SctpChannelOption.SCTP_FRAGMENT_INTERLEAVE, this.management.getOptionSctpFragmentInterleave());
        b.option(SctpChannelOption.SCTP_INIT_MAXSTREAMS, this.management.getOptionSctpInitMaxstreams());
        b.option(SctpChannelOption.SO_SNDBUF, this.management.getOptionSoSndbuf());
        b.option(SctpChannelOption.SO_RCVBUF, this.management.getOptionSoRcvbuf());
        b.option(SctpChannelOption.SO_LINGER, this.management.getOptionSoLinger());
    }

    /**
     * XML Serialization/Deserialization
     */
    protected static final XMLFormat<NettyAssociationImpl> ASSOCIATION_XML = new XMLFormat<NettyAssociationImpl>(
            NettyAssociationImpl.class) {

        @SuppressWarnings("unchecked")
        @Override
        public void read(javolution.xml.XMLFormat.InputElement xml, NettyAssociationImpl association) throws XMLStreamException {
            association.name = xml.getAttribute(NAME, "");
            association.type = AssociationType.getAssociationType(xml.getAttribute(ASSOCIATION_TYPE, ""));
            association.hostAddress = xml.getAttribute(HOST_ADDRESS, "");
            association.hostPort = xml.getAttribute(HOST_PORT, 0);

            association.peerAddress = xml.getAttribute(PEER_ADDRESS, "");
            association.peerPort = xml.getAttribute(PEER_PORT, 0);

            association.serverName = xml.getAttribute(SERVER_NAME, "");
            association.ipChannelType = IpChannelType
                    .getInstance(xml.getAttribute(IP_CHANNEL_TYPE, IpChannelType.SCTP.getCode()));
            if (association.ipChannelType == null)
                association.ipChannelType = IpChannelType.SCTP;

            int extraHostAddressesSize = xml.getAttribute(EXTRA_HOST_ADDRESSES_SIZE, 0);
            association.extraHostAddresses = new String[extraHostAddressesSize];

            for (int i = 0; i < extraHostAddressesSize; i++) {
                association.extraHostAddresses[i] = xml.get(EXTRA_HOST_ADDRESS, String.class);
            }

        }

        @Override
        public void write(NettyAssociationImpl association, javolution.xml.XMLFormat.OutputElement xml)
                throws XMLStreamException {
            xml.setAttribute(NAME, association.name);
            xml.setAttribute(ASSOCIATION_TYPE, association.type.getType());
            xml.setAttribute(HOST_ADDRESS, association.hostAddress);
            xml.setAttribute(HOST_PORT, association.hostPort);

            xml.setAttribute(PEER_ADDRESS, association.peerAddress);
            xml.setAttribute(PEER_PORT, association.peerPort);

            xml.setAttribute(SERVER_NAME, association.serverName);
            xml.setAttribute(IP_CHANNEL_TYPE, association.ipChannelType.getCode());

            xml.setAttribute(EXTRA_HOST_ADDRESSES_SIZE,
                    association.extraHostAddresses != null ? association.extraHostAddresses.length : 0);
            if (association.extraHostAddresses != null) {
                for (String s : association.extraHostAddresses) {
                    xml.add(s, EXTRA_HOST_ADDRESS, String.class);
                }
            }
        }
    };

}
