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

import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelOption;
import io.netty.channel.ServerChannel;
import io.netty.channel.sctp.SctpChannelOption;
import io.netty.channel.sctp.SctpServerChannel;
import org.mobicents.protocols.sctp.netty.PooledNioSctpServerChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.logging.LogLevel;
import io.netty.handler.logging.LoggingHandler;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;

import java.util.concurrent.CopyOnWriteArrayList;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlProperty;
import com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlRootElement;

import org.apache.log4j.Logger;
import org.mobicents.protocols.api.Association;
import org.mobicents.protocols.api.IpChannelType;
import org.mobicents.protocols.api.Server;

/**
 * @author <a href="mailto:amit.bhayani@telestax.com">Amit Bhayani</a>
 * 
 */
@JacksonXmlRootElement(localName = "server")
public class NettyServerImpl implements Server {

    private static final Logger logger = Logger.getLogger(NettyServerImpl.class.getName());

    private static final String COMMA = ", ";

    @JacksonXmlProperty(isAttribute = true)
    private String name;
    
    @JacksonXmlProperty(isAttribute = true)
    private String hostAddress;
    
    @JacksonXmlProperty(isAttribute = true)
    private int hostport;
    
    @JacksonXmlProperty(isAttribute = true)
    private volatile boolean started = false;
    
    @JacksonXmlProperty(isAttribute = true)
    private IpChannelType ipChannelType;
    
    @JacksonXmlProperty(isAttribute = true)
    private boolean acceptAnonymousConnections;
    
    @JacksonXmlProperty(isAttribute = true)
    private int maxConcurrentConnectionsCount;
    
    private String[] extraHostAddresses;

    @JsonIgnore
    private NettySctpManagementImpl management = null;

    protected final CopyOnWriteArrayList<String> associations = new CopyOnWriteArrayList<String>();
    
    @JsonIgnore
    protected final CopyOnWriteArrayList<Association> anonymAssociations = new CopyOnWriteArrayList<Association>();

    // Netty declarations
    // The channel on which we'll accept connections
    @JsonIgnore
    private SctpServerChannel serverChannelSctp;
    
    @JsonIgnore
    private NioServerSocketChannel serverChannelTcp;

    /**
     * 
     */
    public NettyServerImpl() {
        super();
    }

    /**
     * @param serverName
     * @param hostAddress
     * @param hostPort
     * @param ipChannelType
     * @param acceptAnonymousConnections
     * @param maxConcurrentConnectionsCount
     * @param extraHostAddresses
     * @throws IOException
     */
    public NettyServerImpl(String serverName, String hostAddress, int hostPort, IpChannelType ipChannelType,
            boolean acceptAnonymousConnections, int maxConcurrentConnectionsCount, String[] extraHostAddresses)
            throws IOException {
        super();
        this.name = serverName;
        this.hostAddress = hostAddress;
        this.hostport = hostPort;
        this.ipChannelType = ipChannelType;
        this.acceptAnonymousConnections = acceptAnonymousConnections;
        this.maxConcurrentConnectionsCount = maxConcurrentConnectionsCount;
        this.extraHostAddresses = extraHostAddresses;
    }

    /*
     * (non-Javadoc)
     * 
     * @see org.mobicents.protocols.api.Server#getIpChannelType()
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
     * @see org.mobicents.protocols.api.Server#isAcceptAnonymousConnections()
     */
    @Override
    public boolean isAcceptAnonymousConnections() {
        return acceptAnonymousConnections;
    }

    public void setAcceptAnonymousConnections(Boolean acceptAnonymousConnections) {
        this.acceptAnonymousConnections = acceptAnonymousConnections;
    }

    /*
     * (non-Javadoc)
     * 
     * @see org.mobicents.protocols.api.Server#getMaxConcurrentConnectionsCount()
     */
    @Override
    public int getMaxConcurrentConnectionsCount() {
        return this.maxConcurrentConnectionsCount;
    }

    /*
     * (non-Javadoc)
     * 
     * @see org.mobicents.protocols.api.Server#setMaxConcurrentConnectionsCount(int)
     */
    @Override
    public void setMaxConcurrentConnectionsCount(int val) {
        this.maxConcurrentConnectionsCount = val;
    }

    /*
     * (non-Javadoc)
     * 
     * @see org.mobicents.protocols.api.Server#getName()
     */
    @Override
    public String getName() {
        return this.name;
    }

    /*
     * (non-Javadoc)
     * 
     * @see org.mobicents.protocols.api.Server#getHostAddress()
     */
    @Override
    public String getHostAddress() {
        return this.hostAddress;
    }

    public void setHostAddress(String hostAddress) {
        this.hostAddress = hostAddress;
    }

    /*
     * (non-Javadoc)
     * 
     * @see org.mobicents.protocols.api.Server#getHostport()
     */
    @Override
    public int getHostport() {
        return this.hostport;
    }

    public void setHostport(int hostport) {
        this.hostport = hostport;
    }

    /*
     * (non-Javadoc)
     * 
     * @see org.mobicents.protocols.api.Server#getExtraHostAddresses()
     */
    @Override
    public String[] getExtraHostAddresses() {
        return this.extraHostAddresses;
    }

    public void setExtraHostAddresses(String[] extraHostAddresses) {
        this.extraHostAddresses = extraHostAddresses;
    }

    /*
     * (non-Javadoc)
     * 
     * @see org.mobicents.protocols.api.Server#isStarted()
     */
    @Override
    public boolean isStarted() {
        return this.started;
    }

    /*
     * (non-Javadoc)
     * 
     * @see org.mobicents.protocols.api.Server#getAssociations()
     */
    @Override
    public List<String> getAssociations() {
        return this.associations;
    }

    /*
     * (non-Javadoc)
     * 
     * @see org.mobicents.protocols.api.Server#getAnonymAssociations()
     */
    @Override
    public List<Association> getAnonymAssociations() {
        return this.anonymAssociations;
    }

    /**
     * Modify server properties.
     */
    protected void modifyServer(String hostAddress, Integer port, IpChannelType ipChannelType, 
                                Boolean acceptAnonymousConnections, Integer maxConcurrentConnectionsCount, 
                                String[] extraHostAddresses) {
        if (hostAddress != null) {
            this.hostAddress = hostAddress;
        }
        if (port != null) {
            this.hostport = port;
        }
        if (ipChannelType != null) {
            this.ipChannelType = ipChannelType;
        }
        if (acceptAnonymousConnections != null) {
            this.acceptAnonymousConnections = acceptAnonymousConnections;
        }
        if (maxConcurrentConnectionsCount != null) {
            this.maxConcurrentConnectionsCount = maxConcurrentConnectionsCount;
        }
        if (extraHostAddresses != null) {
            this.extraHostAddresses = extraHostAddresses;
        }
    }

    protected ServerChannel getIpChannel() {
        if (this.ipChannelType == IpChannelType.SCTP)
            return this.serverChannelSctp;
        else
            return this.serverChannelTcp;
    }

    /**
     * @param management the management to set
     */
    protected void setManagement(NettySctpManagementImpl management) {
        this.management = management;
    }

    protected void start() throws Exception {
        this.initSocket();
        this.started = true;

        if (logger.isInfoEnabled()) {
            logger.info(String.format("Started Server=%s", this.name));
        }
    }

    protected void stop() throws Exception {
        for (String assocName : associations) {
            Association associationTemp = this.management.getAssociation(assocName);
            if (associationTemp.isStarted()) {
                throw new Exception(String.format("Stop all the associations first. Association=%s is still started",
                        associationTemp.getName()));
            }
        }

        // stopping all anonymous associations
        for (Association ass : this.anonymAssociations) {
            ass.stopAnonymousAssociation();
        }
        this.anonymAssociations.clear();

        this.started = false;

        if (logger.isInfoEnabled()) {
            logger.info(String.format("Stopped Server=%s", this.name));
        }

        // Stop underlying channel and wait till its done
        if (this.getIpChannel() != null) {
            try {
                this.getIpChannel().close().sync();
            } catch (Exception e) {
                logger.warn(String.format("Error while stopping the Server=%s", this.name), e);
            }
        }
    }

    private void initSocket() throws Exception {
        ServerBootstrap b = new ServerBootstrap();
        b.group(this.management.getBossGroup(), this.management.getWorkerGroup());
        if (this.ipChannelType == IpChannelType.SCTP) {
            b.channel(PooledNioSctpServerChannel.class);
            // SO_BACKLOG not supported on Linux/WSL JDK SCTP implementation
            String osName = System.getProperty("os.name").toLowerCase();
            if (osName.contains("win")) {
                b.option(ChannelOption.SO_BACKLOG, 100);
            }
            b.childHandler(new NettySctpServerChannelInitializer(this, this.management));
            this.applySctpOptions(b);
        } else {
            b.channel(NioServerSocketChannel.class);
            b.option(ChannelOption.SO_BACKLOG, 100);
            b.childHandler(new NettyTcpServerChannelInitializer(this, this.management));
        }
        b.handler(new LoggingHandler(LogLevel.INFO));

        InetSocketAddress localAddress = new InetSocketAddress(this.hostAddress, this.hostport);

        // Bind the server to primary address.
        ChannelFuture channelFuture = b.bind(localAddress).sync();

        // Get the underlying sctp channel
        if (this.ipChannelType == IpChannelType.SCTP) {
            this.serverChannelSctp = (SctpServerChannel) channelFuture.channel();

            // Bind the secondary address.
            // Please note that, bindAddress in the client channel should be done before connecting if you have not
            // enable Dynamic Address Configuration. See net.sctp.addip_enable kernel param
            if (this.extraHostAddresses != null) {
                for (int count = 0; count < this.extraHostAddresses.length; count++) {
                    String localSecondaryAddress = this.extraHostAddresses[count];
                    InetAddress localSecondaryInetAddress = InetAddress.getByName(localSecondaryAddress);

                    channelFuture = this.serverChannelSctp.bindAddress(localSecondaryInetAddress).sync();
                }
            }

            if (logger.isInfoEnabled()) {
                logger.info(String.format("SctpServerChannel bound to=%s ", this.serverChannelSctp.allLocalAddresses()));
            }
        } else {
            this.serverChannelTcp = (NioServerSocketChannel) channelFuture.channel();

            if (logger.isInfoEnabled()) {
                logger.info(String.format("ServerSocketChannel bound to=%s ", this.serverChannelTcp.localAddress()));
            }
        }
    }

    private void applySctpOptions(ServerBootstrap b) {
        // SCTP standard options (usually supported)
        b.childOption(SctpChannelOption.SCTP_NODELAY, this.management.getOptionSctpNodelay());
        b.childOption(SctpChannelOption.SCTP_DISABLE_FRAGMENTS, this.management.getOptionSctpDisableFragments());
        b.childOption(SctpChannelOption.SCTP_FRAGMENT_INTERLEAVE, this.management.getOptionSctpFragmentInterleave());
        b.childOption(SctpChannelOption.SCTP_INIT_MAXSTREAMS, this.management.getOptionSctpInitMaxstreams());
        
        // Socket buffer options
        b.childOption(SctpChannelOption.SO_SNDBUF, this.management.getOptionSoSndbuf());
        b.childOption(SctpChannelOption.SO_RCVBUF, this.management.getOptionSoRcvbuf());
        b.childOption(SctpChannelOption.SO_LINGER, this.management.getOptionSoLinger());
    }

    @Override
    public String toString() {

        StringBuilder sb = new StringBuilder();

        sb.append("Server [name=").append(this.name).append(", started=").append(this.started).append(", hostAddress=").append(this.hostAddress)
                .append(", hostPort=").append(hostport).append(", ipChannelType=").append(ipChannelType).append(", acceptAnonymousConnections=")
                .append(this.acceptAnonymousConnections).append(", maxConcurrentConnectionsCount=").append(this.maxConcurrentConnectionsCount)
                .append(", associations(anonymous does not included)=[");

        for (String assocName : this.associations) {
            sb.append(assocName);
            sb.append(", ");
        }

        sb.append("], extraHostAddress=[");

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
}
