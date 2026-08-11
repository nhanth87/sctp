package org.mobicents.protocols.sctp.fstack;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import org.mobicents.protocols.api.Association;
import org.mobicents.protocols.api.AssociationListener;
import org.mobicents.protocols.api.AssociationType;
import org.mobicents.protocols.api.IpChannelType;
import org.mobicents.protocols.api.PayloadData;
import org.mobicents.protocols.sctp.spi.AssociationGeneration;
import org.mobicents.protocols.sctp.spi.SctpCongestionRejectedException;
import org.mobicents.protocols.sctp.spi.SctpCongestionSample;
import org.mobicents.protocols.sctp.spi.SctpEventKind;
import org.mobicents.protocols.sctp.spi.SctpPacketViews;
import org.mobicents.protocols.sctp.spi.SctpRecord;

import io.netty.buffer.ByteBufAllocator;

final class FstackAssociationImpl implements Association {
    private final FstackSctpManagementImpl management;
    private final long associationId;
    private final String name;
    private final AssociationType type;
    private final IpChannelType channelType;
    private final String hostAddress;
    private final int hostPort;
    private final String peerAddress;
    private final int peerPort;
    private final String serverName;
    private final String[] extraHostAddresses;
    private final AssociationGeneration generation = new AssociationGeneration();
    private final AtomicBoolean started = new AtomicBoolean(false);
    private final AtomicBoolean up = new AtomicBoolean(false);
    private final AtomicInteger congestion = new AtomicInteger(0);
    private final AtomicInteger inboundStreams = new AtomicInteger(16);
    private final AtomicInteger outboundStreams = new AtomicInteger(16);
    private final AtomicInteger staleDrops = new AtomicInteger(0);

    private volatile AssociationListener listener;
    private volatile String[] extraPeerHostAddresses;

    FstackAssociationImpl(FstackSctpManagementImpl management, long associationId, String name,
            AssociationType type, IpChannelType channelType, String hostAddress, int hostPort,
            String peerAddress, int peerPort, String serverName, String[] extraHostAddresses) {
        this.management = management;
        this.associationId = associationId;
        this.name = Objects.requireNonNull(name);
        this.type = type;
        this.channelType = channelType == null ? IpChannelType.SCTP : channelType;
        this.hostAddress = hostAddress;
        this.hostPort = hostPort;
        this.peerAddress = peerAddress;
        this.peerPort = peerPort;
        this.serverName = serverName;
        this.extraHostAddresses = extraHostAddresses;
    }

    long associationId() {
        return associationId;
    }

    long generation() {
        return generation.current();
    }

    int staleDrops() {
        return staleDrops.get();
    }

    int swapCongestion(int level) {
        return congestion.getAndSet(Math.max(0, Math.min(3, level)));
    }

    void start() {
        started.set(true);
        FstackDataplaneClient client = management.dataplaneClient();
        if (client != null) {
            client.open(this);
        }
    }

    void stop() {
        started.set(false);
        FstackDataplaneClient client = management.dataplaneClient();
        if (client != null) {
            client.close(this);
        }
        markDown(SctpEventKind.SHUTDOWN);
    }

    void forceDown() {
        started.set(false);
        markDown(SctpEventKind.LOST);
    }

    void markUp(int inStreams, int outStreams) {
        inboundStreams.set(inStreams);
        outboundStreams.set(outStreams);
        long gen = generation.nextUp();
        up.set(true);
        AssociationListener current = listener;
        if (current != null) {
            current.onCommunicationUp(this, inStreams, outStreams);
        }
        management.notifyUp(this);
        // generation is stamped on subsequent payloads by the dataplane
        if (gen < 0) {
            throw new IllegalStateException("generation overflow");
        }
    }

    void markDown(SctpEventKind kind) {
        boolean wasUp = up.getAndSet(false);
        if (!wasUp && kind != SctpEventKind.SHUTDOWN) {
            return;
        }
        AssociationListener current = listener;
        if (current != null) {
            switch (kind) {
                case SHUTDOWN -> current.onCommunicationShutdown(this);
                case RESTART -> current.onCommunicationRestart(this);
                default -> current.onCommunicationLost(this);
            }
        }
        management.notifyDown(this);
    }

    void deliver(SctpRecord record) {
        switch (record.kind()) {
            case UP -> markUp(nzStreams(record.inboundStreams()), nzStreams(record.outboundStreams()));
            case SHUTDOWN, LOST, RESTART -> markDown(record.kind());
            case PAYLOAD -> {
                if (!generation.accept(record.generation())) {
                    staleDrops.incrementAndGet();
                    return;
                }
                deliverPayload(record);
            }
            case CONGESTION -> management.ingest(this, SctpCongestionSample.ringLevel(
                    associationId, generation.current(), record.congestionLevel()));
            case PEER_ADDRESS_CHANGED, PEER_UNREACHABLE, PEER_REACHABLE -> {
                // optional listener defaults
            }
        }
    }

    private static int nzStreams(int streams) {
        return streams <= 0 ? 16 : streams;
    }

    private void deliverPayload(SctpRecord record) {
        AssociationListener current = listener;
        if (current == null) {
            return;
        }
        if (record.streamId() >= outboundStreams.get() && record.streamId() >= inboundStreams.get()) {
            current.inValidStreamId(toPayload(record));
            return;
        }
        current.onPayload(this, toPayload(record));
    }

    private static PayloadData toPayload(SctpRecord record) {
        return SctpPacketViews.wrap(record);
    }

    @Override
    public void send(PayloadData payloadData) {
        if (!up.get()) {
            throw new IllegalStateException("Association " + name + " is not UP");
        }
        if (payloadData.getPayloadProtocolId() == SctpRecord.M3UA_PPID) {
            if (!payloadData.isComplete()) {
                throw new IllegalArgumentException("M3UA PPID 3 must be a complete SCTP user message");
            }
            // RFC 4666 §1.4.7: DATA MUST NOT be unordered on a sequenced stream.
            // ASPSM/MGMT/RKM on stream 0 MAY be unordered (jSS7 ASPUP does this).
            if (payloadData.isUnordered() && payloadData.getStreamNumber() != FstackAbi.M3UA_MANAGEMENT_STREAM) {
                throw new IllegalArgumentException("M3UA DATA (stream != 0) must be ordered");
            }
        }
        if (!management.adaptiveController().tryAcquire(payloadData.getStreamNumber())) {
            throw new SctpCongestionRejectedException(name, management.adaptiveController().snapshot());
        }
        SctpRecord record = SctpRecord.wrap(associationId, generation.current(), SctpEventKind.PAYLOAD,
                payloadData.getStreamNumber(), payloadData.getPayloadProtocolId(),
                payloadData.isUnordered(), congestion.get(), inboundStreams.get(), outboundStreams.get(),
                SctpPacketViews.viewOf(payloadData));
        try {
            management.dataplaneClient().send(record);
        } catch (SctpCongestionRejectedException rejected) {
            throw rejected;
        } catch (RuntimeException failed) {
            management.ingest(this, SctpCongestionSample.sendFail(associationId, generation.current()));
            throw failed;
        }
    }

    @Override public IpChannelType getIpChannelType() { return channelType; }
    @Override public AssociationType getAssociationType() { return type; }
    @Override public String getName() { return name; }
    @Override public boolean isStarted() { return started.get(); }
    @Override public boolean isConnected() { return up.get(); }
    @Override public boolean isUp() { return up.get(); }
    @Override public AssociationListener getAssociationListener() { return listener; }
    @Override public void setAssociationListener(AssociationListener associationListener) { this.listener = associationListener; }
    @Override public String getHostAddress() { return hostAddress; }
    @Override public int getHostPort() { return hostPort; }
    @Override public String getPeerAddress() { return peerAddress; }
    @Override public int getPeerPort() { return peerPort; }
    @Override public String getServerName() { return serverName; }
    @Override public String[] getExtraHostAddresses() { return extraHostAddresses; }
    @Override public String[] getExtraPeerHostAddresses() { return extraPeerHostAddresses; }
    @Override public void setExtraPeerHostAddresses(String[] extraPeerHostAddresses) {
        this.extraPeerHostAddresses = extraPeerHostAddresses;
    }
    @Override public ByteBufAllocator getByteBufAllocator() { return null; }
    @Override public int getCongestionLevel() { return congestion.get(); }
    @Override public void acceptAnonymousAssociation(AssociationListener associationListener) {
        this.listener = associationListener;
    }
    @Override public void rejectAnonymousAssociation() {}
    @Override public void stopAnonymousAssociation() { stop(); }
}
