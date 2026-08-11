package org.mobicents.protocols.sctp.fstack;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import org.mobicents.protocols.api.Association;
import org.mobicents.protocols.api.IpChannelType;
import org.mobicents.protocols.api.Server;

final class FstackServerImpl implements Server {
    private final String name;
    private final String hostAddress;
    private final int hostPort;
    private final IpChannelType channelType;
    private final boolean acceptAnonymous;
    private final AtomicInteger maxConcurrent = new AtomicInteger();
    private final String[] extraHostAddresses;
    private final AtomicBoolean started = new AtomicBoolean(false);
    private final CopyOnWriteArrayList<String> associations = new CopyOnWriteArrayList<>();
    private final CopyOnWriteArrayList<Association> anonymous = new CopyOnWriteArrayList<>();

    FstackServerImpl(String name, String hostAddress, int hostPort, IpChannelType channelType,
            boolean acceptAnonymous, int maxConcurrent, String[] extraHostAddresses) {
        this.name = name;
        this.hostAddress = hostAddress;
        this.hostPort = hostPort;
        this.channelType = channelType == null ? IpChannelType.SCTP : channelType;
        this.acceptAnonymous = acceptAnonymous;
        this.maxConcurrent.set(maxConcurrent);
        this.extraHostAddresses = extraHostAddresses;
    }

    void markStarted(boolean value) {
        started.set(value);
    }

    void addAssociation(String assocName) {
        associations.addIfAbsent(assocName);
    }

    @Override public IpChannelType getIpChannelType() { return channelType; }
    @Override public boolean isAcceptAnonymousConnections() { return acceptAnonymous; }
    @Override public int getMaxConcurrentConnectionsCount() { return maxConcurrent.get(); }
    @Override public void setMaxConcurrentConnectionsCount(int maxConcurrentConnectionsCount) {
        maxConcurrent.set(maxConcurrentConnectionsCount);
    }
    @Override public String getName() { return name; }
    @Override public String getHostAddress() { return hostAddress; }
    @Override public int getHostport() { return hostPort; }
    @Override public String[] getExtraHostAddresses() { return extraHostAddresses; }
    @Override public boolean isStarted() { return started.get(); }
    @Override public List<String> getAssociations() { return new ArrayList<>(associations); }
    @Override public List<Association> getAnonymAssociations() { return new ArrayList<>(anonymous); }
}
