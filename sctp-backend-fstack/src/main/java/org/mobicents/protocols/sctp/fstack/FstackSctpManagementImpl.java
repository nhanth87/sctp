package org.mobicents.protocols.sctp.fstack;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import org.mobicents.protocols.api.Association;
import org.mobicents.protocols.api.AssociationType;
import org.mobicents.protocols.api.CongestionListener;
import org.mobicents.protocols.api.IpChannelType;
import org.mobicents.protocols.api.Management;
import org.mobicents.protocols.api.ManagementEventListener;
import org.mobicents.protocols.api.PayloadDataPool;
import org.mobicents.protocols.api.PayloadDataPool.PoolStatistics;
import org.mobicents.protocols.api.Server;
import org.mobicents.protocols.api.ServerListener;
import org.mobicents.protocols.sctp.spi.AdaptiveSendController;
import org.mobicents.protocols.sctp.spi.SctpBackend;
import org.mobicents.protocols.sctp.spi.SctpCongestionSample;
import org.mobicents.protocols.sctp.spi.SctpDataplane;
import org.mobicents.protocols.sctp.spi.SctpTransportMode;

/**
 * Default SCTP {@link Management} for GraalVM-native GMLC.
 * Owns topology in Java; the native data plane (sidecar or FFM) never persists config.
 */
public final class FstackSctpManagementImpl implements Management {
    public static final int ABI_VERSION = FstackAbi.ABI_VERSION;

    private final String name;
    private final AtomicBoolean started = new AtomicBoolean(false);
    private final Map<String, FstackAssociationImpl> associations = new LinkedHashMap<>();
    private final Map<String, FstackServerImpl> servers = new LinkedHashMap<>();
    private final CopyOnWriteArrayList<ManagementEventListener> listeners = new CopyOnWriteArrayList<>();
    private final CopyOnWriteArrayList<CongestionListener> congestionListeners = new CopyOnWriteArrayList<>();
    private final AtomicLong nextAssociationId = new AtomicLong(1);
    private final AtomicInteger connectDelayMs = new AtomicInteger(10_000);
    private final AtomicInteger workerThreads = new AtomicInteger(1);
    private final AtomicBoolean singleThread = new AtomicBoolean(true);
    private final AtomicInteger bufferSize = new AtomicInteger(8192);
    private final AtomicInteger targetThroughput = new AtomicInteger(0);
    private final AtomicInteger maxOutStreams = new AtomicInteger(16);
    private final AtomicInteger maxInStreams = new AtomicInteger(16);

    private volatile String persistDir;
    private volatile ServerListener serverListener;
    private volatile PayloadDataPool payloadDataPool = new PayloadDataPool();
    private volatile SctpTransportMode mode = SctpTransportMode.from(System.getProperty("sctp.fstack.mode"));
    private volatile SctpDataplane dataplane = SctpDataplane.from(System.getProperty("sctp.fstack.dataplane"));
    private volatile FstackDataplaneClient dataplaneClient;
    private volatile AdaptiveSendController adaptive = AdaptiveSendController.createDefault();

    public FstackSctpManagementImpl(String name) {
        this.name = Objects.requireNonNull(name, "name");
    }

    public AdaptiveSendController adaptiveController() {
        return adaptive;
    }

    public void setAdaptiveController(AdaptiveSendController adaptive) {
        this.adaptive = Objects.requireNonNull(adaptive, "adaptive");
    }

    public SctpBackend backend() {
        return SctpBackend.FSTACK_DPDK;
    }

    public SctpTransportMode mode() {
        return mode;
    }

    public void setMode(SctpTransportMode mode) {
        this.mode = Objects.requireNonNull(mode);
    }

    public SctpDataplane dataplane() {
        return dataplane;
    }

    public void setDataplane(SctpDataplane dataplane) {
        this.dataplane = Objects.requireNonNull(dataplane);
    }

    FstackDataplaneClient dataplaneClient() {
        return dataplaneClient;
    }

    long allocateAssociationId() {
        return nextAssociationId.getAndIncrement();
    }

    void ingest(FstackAssociationImpl assoc, SctpCongestionSample sample) {
        int old = assoc == null ? 0 : assoc.swapCongestion(sample.level());
        adaptive.noteSample(sample);
        if (assoc == null) {
            return;
        }
        for (CongestionListener listener : congestionListeners) {
            listener.onCongLevelChanged(assoc, old, sample.level());
        }
    }

    @Override
    public String getName() {
        return name;
    }

    @Override
    public String getPersistDir() {
        return persistDir;
    }

    @Override
    public void setPersistDir(String persistDir) {
        this.persistDir = persistDir;
    }

    @Override
    public ServerListener getServerListener() {
        return serverListener;
    }

    @Override
    public void setServerListener(ServerListener serverListener) {
        this.serverListener = serverListener;
    }

    @Override
    public void addManagementEventListener(ManagementEventListener listener) {
        listeners.addIfAbsent(listener);
    }

    @Override
    public void removeManagementEventListener(ManagementEventListener listener) {
        listeners.remove(listener);
    }

    @Override
    public void addCongestionListener(CongestionListener listener) {
        congestionListeners.addIfAbsent(listener);
    }

    @Override
    public void removeCongestionListener(CongestionListener listener) {
        congestionListeners.remove(listener);
    }

    @Override
    public void start() {
        if (!started.compareAndSet(false, true)) {
            return;
        }
        try {
            this.dataplaneClient = switch (mode) {
                case SIDECAR -> useSharedSidecar()
                        ? new SidecarDataplaneClient(this)
                        : new LoopbackDataplaneClient(this);
                case IN_PROCESS -> new FfmDataplaneClient(this);
            };
            dataplaneClient.start();
            listeners.forEach(ManagementEventListener::onServiceStarted);
        } catch (RuntimeException failed) {
            started.set(false);
            if (dataplaneClient != null) {
                try {
                    dataplaneClient.stop();
                } catch (RuntimeException ignored) {
                    // fail closed
                }
                dataplaneClient = null;
            }
            throw failed;
        }
    }

    @Override
    public void stop() {
        if (!started.compareAndSet(true, false)) {
            return;
        }
        associations.values().forEach(FstackAssociationImpl::forceDown);
        if (dataplaneClient != null) {
            dataplaneClient.stop();
        }
        listeners.forEach(ManagementEventListener::onServiceStopped);
    }

    @Override
    public boolean isStarted() {
        return started.get();
    }

    @Override
    public void removeAllResources() {
        associations.clear();
        servers.clear();
        listeners.forEach(ManagementEventListener::onRemoveAllResources);
    }

    @Override
    public Server addServer(String serverName, String hostAddress, int port, IpChannelType ipChannelType,
            boolean acceptAnonymous, int maxConcurrent, String[] extraHostAddresses) {
        requireStarted();
        FstackServerImpl server = new FstackServerImpl(serverName, hostAddress, port, ipChannelType,
                acceptAnonymous, maxConcurrent, extraHostAddresses);
        servers.put(serverName, server);
        listeners.forEach(l -> l.onServerAdded(server));
        return server;
    }

    @Override
    public Server addServer(String serverName, String hostAddress, int port, IpChannelType ipChannelType,
            boolean acceptAnonymous, int maxConcurrent, int unused1, int unused2, String[] extraHostAddresses) {
        return addServer(serverName, hostAddress, port, ipChannelType, acceptAnonymous, maxConcurrent, extraHostAddresses);
    }

    @Override
    public Server addServer(String serverName, String hostAddress, int port, IpChannelType ipChannelType,
            String[] extraHostAddresses) {
        return addServer(serverName, hostAddress, port, ipChannelType, false, 0, extraHostAddresses);
    }

    @Override
    public Server addServer(String serverName, String hostAddress, int port) {
        return addServer(serverName, hostAddress, port, IpChannelType.SCTP, null);
    }

    @Override
    public void removeServer(String serverName) {
        FstackServerImpl removed = servers.remove(serverName);
        if (removed != null) {
            listeners.forEach(l -> l.onServerRemoved(removed));
        }
    }

    @Override
    public void startServer(String serverName) {
        server(serverName).markStarted(true);
    }

    @Override
    public void stopServer(String serverName) {
        server(serverName).markStarted(false);
    }

    @Override
    public List<Server> getServers() {
        return new ArrayList<>(servers.values());
    }

    @Override
    public Association addServerAssociation(String peerAddress, int peerPort, String serverName, String assocName) {
        return addServerAssociation(peerAddress, peerPort, serverName, assocName, IpChannelType.SCTP);
    }

    @Override
    public Association addServerAssociation(String peerAddress, int peerPort, String serverName, String assocName,
            IpChannelType ipChannelType) {
        FstackServerImpl server = server(serverName);
        FstackAssociationImpl assoc = new FstackAssociationImpl(this, allocateAssociationId(), assocName,
                AssociationType.SERVER, ipChannelType, server.getHostAddress(), server.getHostport(),
                peerAddress, peerPort, serverName, server.getExtraHostAddresses());
        associations.put(assocName, assoc);
        server.addAssociation(assocName);
        listeners.forEach(l -> l.onAssociationAdded(assoc));
        return assoc;
    }

    @Override
    public Association addAssociation(String hostAddress, int hostPort, String peerAddress, int peerPort, String assocName) {
        return addAssociation(hostAddress, hostPort, peerAddress, peerPort, assocName, IpChannelType.SCTP, null);
    }

    @Override
    public Association addAssociation(String hostAddress, int hostPort, String peerAddress, int peerPort, String assocName,
            IpChannelType ipChannelType, String[] extraHostAddresses) {
        requireStarted();
        FstackAssociationImpl assoc = new FstackAssociationImpl(this, allocateAssociationId(), assocName,
                AssociationType.CLIENT, ipChannelType, hostAddress, hostPort, peerAddress, peerPort, null,
                extraHostAddresses);
        associations.put(assocName, assoc);
        listeners.forEach(l -> l.onAssociationAdded(assoc));
        return assoc;
    }

    @Override
    public void removeAssociation(String assocName) {
        FstackAssociationImpl removed = associations.remove(assocName);
        if (removed != null) {
            removed.forceDown();
            listeners.forEach(l -> l.onAssociationRemoved(removed));
        }
    }

    @Override
    public Association getAssociation(String assocName) {
        Association assoc = associations.get(assocName);
        if (assoc == null) {
            throw new IllegalArgumentException("No association " + assocName);
        }
        return assoc;
    }

    @Override
    public Map<String, Association> getAssociations() {
        return Map.copyOf(associations);
    }

    @Override
    public void startAssociation(String assocName) {
        FstackAssociationImpl assoc = associations.get(assocName);
        if (assoc == null) {
            throw new IllegalArgumentException("No association " + assocName);
        }
        assoc.start();
        listeners.forEach(l -> l.onAssociationStarted(assoc));
    }

    @Override
    public void stopAssociation(String assocName) {
        FstackAssociationImpl assoc = associations.get(assocName);
        if (assoc == null) {
            return;
        }
        assoc.stop();
        listeners.forEach(l -> l.onAssociationStopped(assoc));
    }

    @Override
    public int getConnectDelay() {
        return connectDelayMs.get();
    }

    @Override
    public void setConnectDelay(int connectDelay) {
        connectDelayMs.set(connectDelay);
    }

    @Override
    public int getWorkerThreads() {
        return workerThreads.get();
    }

    @Override
    public void setWorkerThreads(int workerThreads) {
        this.workerThreads.set(workerThreads);
    }

    @Override
    public boolean isSingleThread() {
        return singleThread.get();
    }

    @Override
    public void setSingleThread(boolean singleThread) {
        this.singleThread.set(singleThread);
    }

    @Override public double getCongControl_DelayThreshold_1() { return 1.0; }
    @Override public double getCongControl_DelayThreshold_2() { return 2.0; }
    @Override public double getCongControl_DelayThreshold_3() { return 3.0; }
    @Override public void setCongControl_DelayThreshold_1(double v) {}
    @Override public void setCongControl_DelayThreshold_2(double v) {}
    @Override public void setCongControl_DelayThreshold_3(double v) {}
    @Override public double getCongControl_BackToNormalDelayThreshold_1() { return 0.5; }
    @Override public double getCongControl_BackToNormalDelayThreshold_2() { return 1.0; }
    @Override public double getCongControl_BackToNormalDelayThreshold_3() { return 1.5; }
    @Override public void setCongControl_BackToNormalDelayThreshold_1(double v) {}
    @Override public void setCongControl_BackToNormalDelayThreshold_2(double v) {}
    @Override public void setCongControl_BackToNormalDelayThreshold_3(double v) {}

    @Override public Boolean getOptionSctpDisableFragments() { return Boolean.FALSE; }
    @Override public void setOptionSctpDisableFragments(Boolean v) {}
    @Override public Integer getOptionSctpFragmentInterleave() { return 0; }
    @Override public void setOptionSctpFragmentInterleave(Integer v) {}
    @Override public Integer getOptionSctpInitMaxstreams_MaxOutStreams() { return maxOutStreams.get(); }
    @Override public Integer getOptionSctpInitMaxstreams_MaxInStreams() { return maxInStreams.get(); }
    @Override public void setOptionSctpInitMaxstreams_MaxOutStreams(Integer v) {
        if (v != null) maxOutStreams.set(v);
    }
    @Override public void setOptionSctpInitMaxstreams_MaxInStreams(Integer v) {
        if (v != null) maxInStreams.set(v);
    }
    @Override public Boolean getOptionSctpNodelay() { return Boolean.TRUE; }
    @Override public void setOptionSctpNodelay(Boolean v) {}
    @Override public Integer getOptionSoSndbuf() { return bufferSize.get(); }
    @Override public void setOptionSoSndbuf(Integer v) {}
    @Override public Integer getOptionSoRcvbuf() { return bufferSize.get(); }
    @Override public void setOptionSoRcvbuf(Integer v) {}
    @Override public Integer getOptionSoLinger() { return 0; }
    @Override public void setOptionSoLinger(Integer v) {}
    @Override public int getBufferSize() { return bufferSize.get(); }
    @Override public void setBufferSize(int bufferSize) { this.bufferSize.set(bufferSize); }

    @Override
    public void modifyServer(String serverName, String hostAddress, Integer port, IpChannelType type,
            Boolean acceptAnonymous, Integer maxConcurrent, String[] extra) {
        FstackServerImpl server = server(serverName);
        listeners.forEach(l -> l.onServerModified(server));
    }

    @Override
    public void modifyServerAssociation(String assocName, String peerAddress, Integer peerPort, String serverName,
            IpChannelType type) {
        listeners.forEach(l -> l.onAssociationModified(associations.get(assocName)));
    }

    @Override
    public void modifyAssociation(String hostAddress, Integer hostPort, String peerAddress, Integer peerPort,
            String assocName, IpChannelType type, String[] extra) {
        listeners.forEach(l -> l.onAssociationModified(associations.get(assocName)));
    }

    @Override
    public PayloadDataPool getPayloadDataPool() {
        return payloadDataPool;
    }

    @Override
    public void setPayloadDataPool(PayloadDataPool payloadDataPool) {
        this.payloadDataPool = payloadDataPool;
    }

    @Override
    public int getTargetThroughput() {
        return targetThroughput.get();
    }

    @Override
    public void setTargetThroughput(int targetThroughput) {
        this.targetThroughput.set(targetThroughput);
    }

    @Override
    public PoolStatistics getPoolStatistics() {
        return payloadDataPool.getStatistics();
    }

    void notifyUp(FstackAssociationImpl assoc) {
        listeners.forEach(l -> l.onAssociationUp(assoc));
    }

    void notifyDown(FstackAssociationImpl assoc) {
        listeners.forEach(l -> l.onAssociationDown(assoc));
    }

    FstackAssociationImpl associationById(long id) {
        for (FstackAssociationImpl assoc : associations.values()) {
            if (assoc.associationId() == id) {
                return assoc;
            }
        }
        return null;
    }

    private FstackServerImpl server(String serverName) {
        FstackServerImpl server = servers.get(serverName);
        if (server == null) {
            throw new IllegalArgumentException("No server " + serverName);
        }
        return server;
    }

    private void requireStarted() {
        if (!started.get()) {
            throw new IllegalStateException("Management=" + name + " not started");
        }
    }

    /**
     * In-process LOOPBACK when no sidecar is listening (unit tests).
     * A live {@code sctp-fstack} socket pairs two JVMs by reversed 5-tuple.
     */
    private boolean useSharedSidecar() {
        if (dataplane != SctpDataplane.LOOPBACK) {
            return true;
        }
        String configured = System.getProperty("sctp.fstack.sidecar.socket", "data/sctp-fstack.sock");
        return Files.exists(Path.of(configured));
    }
}
