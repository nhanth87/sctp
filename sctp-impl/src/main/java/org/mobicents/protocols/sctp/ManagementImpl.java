/*
 * TeleStax, Open Source Cloud Communications  Copyright 2012.
 * and individual contributors
 * by the @authors tag. See the copyright.txt in the distribution for a
 * full listing of individual contributors.
 *
 * This is free software; you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License as
 * published by the Free Software Foundation; either version 2.1 of
 * the License, or (at your option) any later version.
 *
 * This software is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this software; if not, write to the Free
 * Software Foundation, Inc., 51 Franklin St, Fifth Floor, Boston, MA
 * 02110-1301 USA, or see the FSF site: http://www.fsf.org.
 */

package org.mobicents.protocols.sctp;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.channels.Selector;
import java.nio.channels.spi.SelectorProvider;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.jctools.maps.NonBlockingHashMap;
import org.jctools.queues.MpscArrayQueue;

import org.apache.log4j.Logger;
import org.mobicents.protocols.api.Association;
import org.mobicents.protocols.api.AssociationType;
import org.mobicents.protocols.api.CongestionListener;
import org.mobicents.protocols.api.IpChannelType;
import org.mobicents.protocols.api.Management;
import org.mobicents.protocols.api.ManagementEventListener;
import org.mobicents.protocols.api.PayloadData;
import org.mobicents.protocols.api.PayloadDataPool;
import org.mobicents.protocols.api.Server;
import org.mobicents.protocols.api.ServerListener;
import org.mobicents.protocols.sctp.netty.NettySctpManagementImpl;

import com.fasterxml.jackson.dataformat.xml.XmlMapper;
import java.io.File;

/**
 * @author <a href="mailto:nhanth87@gmail.com">nhanth87</a>
 * @author afe (aferreiraguido@gmail.com)
 *
 */
public class ManagementImpl implements Management {

	private static final Logger logger = Logger.getLogger(ManagementImpl.class);

	private static final String SCTP_PERSIST_DIR_KEY = "sctp.persist.dir";
	private static final String USER_DIR_KEY = "user.dir";
	private static final String PERSIST_FILE_NAME = "sctp.xml";

	private static final String SERVERS = "servers";
	private static final String ASSOCIATIONS = "associations";
	
    private static final String CONNECT_DELAY_PROP = "connectdelay";
    private static final String SINGLE_THREAD_PROP = "singlethread";
    private static final String WORKER_THREADS_PROP = "workerthreads";

	private final StringBuilder persistFile = new StringBuilder();

	private static final String TAB_INDENT = "\t";

	private final String name;

	protected String persistDir = null;

	protected final CopyOnWriteArrayList<Server> servers = new CopyOnWriteArrayList<>();
	protected final AssociationMap<String, Association> associations = new AssociationMap<>();

	private final MpscArrayQueue<ChangeRequest> pendingChanges = new MpscArrayQueue<>(1024);

	// PayloadDataPool for high-performance object pooling (v2.0.5)
	private PayloadDataPool payloadDataPool;
	private int targetThroughput = 100_000;

	// Create a new selector
	private Selector socketSelector = null;

	private SelectorThread selectorThread = null;

	static final int DEFAULT_IO_THREADS = Runtime.getRuntime().availableProcessors() * 2;

	private int workerThreads = DEFAULT_IO_THREADS;

	private boolean singleThread = true;

	private int workerThreadCount = 0;

	// Maximum IO Errors tolerated by Socket. After this the Socket will be
	// closed and attempt will be made to open again
	private int maxIOErrors = 3;

	private int connectDelay = 5000;

    private int bufferSize = 65535;// 8192;

    private ExecutorService[] executorServices = null;

    private final List<ManagementEventListener> managementEventListeners = new CopyOnWriteArrayList<>();

    private ServerListener serverListener = null;

	private volatile boolean started = false;

	public ManagementImpl(String name) throws IOException {
		this.name = name;
		this.socketSelector = SelectorProvider.provider().openSelector();
	}

	/**
	 * @return the name
	 */
	public String getName() {
		return name;
	}

	public String getPersistDir() {
		return persistDir;
	}

	public void setPersistDir(String persistDir) {
		this.persistDir = persistDir;
	}

	/**
	 * @return the connectDelay
	 */
	public int getConnectDelay() {
		return connectDelay;
	}

	/**
	 * @param connectDelay
	 *            the connectDelay to set
	 */
	public void setConnectDelay(int connectDelay) throws Exception {
        if (!this.started)
            throw new Exception("ConnectDelay parameter can be updated only when SCTP stack is running");

        this.connectDelay = connectDelay;

        this.store();
	}

	/**
	 * @return the workerThreads
	 */
	public int getWorkerThreads() {
		return workerThreads;
	}

	/**
	 * @param workerThreads
	 *            the workerThreads to set
	 */
	public void setWorkerThreads(int workerThreads) throws Exception {
        if (this.started)
            throw new Exception("WorkerThreads parameter can be updated only when SCTP stack is NOT running");

		if (workerThreads < 1) {
			workerThreads = DEFAULT_IO_THREADS;
		}
		this.workerThreads = workerThreads;

//		this.store();
	}

	/**
	 * @return the maxIOErrors
	 */
	public int getMaxIOErrors() {
		return maxIOErrors;
	}

	/**
	 * @param maxIOErrors
	 *            the maxIOErrors to set
	 */
	public void setMaxIOErrors(int maxIOErrors) {
		if (maxIOErrors < 1) {
			maxIOErrors = 1;
		}
		this.maxIOErrors = maxIOErrors;
	}

	/**
	 * @return the singleThread
	 */
	public boolean isSingleThread() {
		return singleThread;
	}

	/**
	 * @param singleThread
	 *            the singleThread to set
	 */
	public void setSingleThread(boolean singleThread) throws Exception {
        if (this.started)
            throw new Exception("SingleThread parameter can be updated only when SCTP stack is NOT running");

		this.singleThread = singleThread;

//		this.store();
	}

    @Override
    public int getBufferSize() {
        return bufferSize;
    }

    @Override
    public void setBufferSize(int bufferSize) throws Exception {
        if (this.started)
            throw new Exception("BufferSize parameter can be updated only when SCTP stack is NOT running");
        if (bufferSize < 1000 || bufferSize > 50000000)
            throw new Exception("BufferSize must be between 1000 and 50.000.000 bytes");

        this.bufferSize = bufferSize;
    }

    public ServerListener getServerListener() {
        return serverListener;
    }

    protected List<ManagementEventListener> getManagementEventListeners() {
        return managementEventListeners;
    }

    public void setServerListener(ServerListener serverListener) {
        this.serverListener = serverListener;
    }

    public void addManagementEventListener(ManagementEventListener listener) {
        if (!this.managementEventListeners.contains(listener)) {
            this.managementEventListeners.add(listener);
        }
    }

    public void removeManagementEventListener(ManagementEventListener listener) {
        this.managementEventListeners.remove(listener);
    }

    public void start() throws Exception {

        if (this.started) {
            logger.warn(String.format("management=%s is already started", this.name));
            return;
        }

        synchronized (this) {
            this.persistFile.setLength(0);

            if (persistDir != null) {
                this.persistFile.append(persistDir).append(File.separator).append(this.name).append("_").append(PERSIST_FILE_NAME);
            } else {
                persistFile.append(System.getProperty(SCTP_PERSIST_DIR_KEY, System.getProperty(USER_DIR_KEY))).append(File.separator).append(this.name)
                    .append("_").append(PERSIST_FILE_NAME);
            }

            logger.info(String.format("SCTP configuration file path '%s'", persistFile.toString()));

            try {
                this.load();
            } catch (FileNotFoundException e) {
                logger.warn("Failed to load the SCTP configuration file.", e);
            }

            if (!this.singleThread) {
                // If not single thread model we create worker threads
                this.executorServices = new ExecutorService[this.workerThreads];
                for (int i = 0; i < this.workerThreads; i++) {
                    this.executorServices[i] = Executors.newSingleThreadExecutor();
                }
            }
            this.selectorThread = new SelectorThread(this.socketSelector, this);
            this.selectorThread.setStarted(true);

            (new Thread(this.selectorThread)).start();

            this.started = true;

            if (logger.isInfoEnabled()) {
                logger.info(String.format("Started SCTP Management=%s WorkerThreads=%d SingleThread=%s", this.name,
                    (this.singleThread ? 0 : this.workerThreads), this.singleThread));
            }

            for (ManagementEventListener lstr : managementEventListeners) {
                try {
                    lstr.onServiceStarted();
                } catch (Throwable ee) {
                    logger.error("Exception while invoking onServiceStarted", ee);
                }
            }
        }
    }

    public void stop() throws Exception {

        if (!this.started) {
            logger.warn(String.format("management=%s is already stopped", this.name));
            return;
        }

        for (ManagementEventListener lstr : managementEventListeners) {
            try {
                lstr.onServiceStopped();
            } catch (Throwable ee) {
                logger.error("Exception while invoking onServiceStopped", ee);
            }
        }

        // We store the original state first
        this.store();

        // Stop all associations
        for (Association associationTemp : this.associations.values()) {
            if (associationTemp.isStarted()) {
                ((AssociationImpl) associationTemp).stop();
            }
        }

        for (Server serverTemp : servers) {
            if (serverTemp.isStarted()) {
                try {
                    ((ServerImpl) serverTemp).stop();
                } catch (Exception e) {
                    logger.error(String.format("Exception while stopping the Server=%s", serverTemp.getName()), e);
                }
            }
        }

        if (this.executorServices != null) {
            for (int i = 0; i < this.executorServices.length; i++) {
                this.executorServices[i].shutdown();
            }
        }

        this.selectorThread.setStarted(false);
        this.socketSelector.wakeup(); // Wakeup selector so SelectorThread dies

        // waiting till stopping associations
        for (int i1 = 0; i1 < 20; i1++) {
            boolean assConnected = false;
            for (Association associationTemp : this.associations.values()) {
                if (associationTemp.isConnected()) {
                    assConnected = true;
                    break;
                }
            }
            if (!assConnected)
                break;
            Thread.sleep(100);
        }

        // Graceful shutdown for each of Executors
        if (this.executorServices != null) {
            for (int i = 0; i < this.executorServices.length; i++) {
                if (!this.executorServices[i].isTerminated()) {
                    if (logger.isInfoEnabled()) {
                        logger.info("Waiting for worker thread to die gracefully ....");
                    }
                    try {
                        this.executorServices[i].awaitTermination(5000, TimeUnit.MILLISECONDS);
                    } catch (InterruptedException e) {
                        // Do we care?
                    }
                }
            }
        }

        this.started = false;
    }

    // PayloadDataPool methods (v2.0.5)
@Override
public PayloadDataPool getPayloadDataPool() {
    if (this.payloadDataPool == null) {
        synchronized (this) {
            if (this.payloadDataPool == null) {
                this.payloadDataPool = new PayloadDataPool(this.targetThroughput);
            }
        }
    }
    return this.payloadDataPool;
}

@Override
public void setPayloadDataPool(PayloadDataPool pool) {
    this.payloadDataPool = pool;
}

@Override
public int getTargetThroughput() {
    return this.targetThroughput;
}

@Override
public void setTargetThroughput(int targetThroughput) throws Exception {
    if (this.started) {
        throw new Exception("Cannot change target throughput while management is started");
    }
    this.targetThroughput = targetThroughput;
}

@Override
public PayloadDataPool.PoolStatistics getPoolStatistics() {
    if (this.payloadDataPool != null) {
        return this.payloadDataPool.getStatistics();
    }
    return null;
}


    public boolean isStarted(){
        return this.started;
    }

    public void load() throws FileNotFoundException {
        XmlMapper xmlMapper = SctpXMLBinding.getXmlMapper();
        
        try {
            SctpPersistenceData persistData = xmlMapper.readValue(new File(persistFile.toString()), SctpPersistenceData.class);
            
            if (persistData != null) {
                try {
                    Integer vali = persistData.getConnectDelay();
                    if (vali != null)
                        this.connectDelay = vali;
                } catch (java.lang.NullPointerException npe) {
                    // ignore.
                    // For backward compatibility we can ignore if these values are not defined
                }

                CopyOnWriteArrayList<Server> loadedServers = persistData.getServers();
                if (loadedServers != null) {
                    this.servers.addAll(loadedServers);
                }

                for (Server serverTemp : this.servers) {
                    ((ServerImpl) serverTemp).setManagement(this);
                    if (serverTemp.isStarted()) {
                        try {
                            ((ServerImpl) serverTemp).start();
                        } catch (Exception e) {
                            logger.error(String.format("Error while initiating Server=%s", serverTemp.getName()), e);
                        }
                    }
                }

                AssociationMap<String, Association> loadedAssociations = persistData.getAssociations();
                if (loadedAssociations != null) {
                    this.associations.putAll(loadedAssociations);
                }
                for (Association associationTemp : this.associations.values()) {
                    ((AssociationImpl) associationTemp).setManagement(this);
                }
            }
        } catch (FileNotFoundException e) {
            throw e;
        } catch (Exception ex) {
            logger.error("Error while loading SCTP configuration from persisted file", ex);
        }
    }

    public void store() {
        XmlMapper xmlMapper = SctpXMLBinding.getXmlMapper();
        
        try {
            SctpPersistenceData persistData = new SctpPersistenceData();
            
            persistData.setConnectDelay(this.connectDelay);
            persistData.setServers(new CopyOnWriteArrayList<>(this.servers));
            persistData.setAssociations(this.associations);
            
            xmlMapper.writeValue(new File(persistFile.toString()), persistData);
        } catch (Exception e) {
            logger.error("Error while persisting the SCTP state in file", e);
        }
    }

    public void removeAllResources() throws Exception {

        synchronized (this) {
            if (!this.started) {
                throw new Exception(String.format("Management=%s not started", this.name));
            }

            if (this.associations.size() == 0 && this.servers.size() == 0)
                // no resources allocated - nothing to do
                return;

            if (logger.isInfoEnabled()) {
                logger.info(String.format("Removing allocated resources: Servers=%d, Associations=%d", this.servers.size(), this.associations.size()));
            }

            synchronized (this) {
                // Remove all associations
                List<String> assocNames = new ArrayList<>(this.associations.keySet());
                for (String assocName : assocNames) {
                    this.stopAssociation(assocName);
                    this.removeAssociation(assocName);
                }

                // Remove all servers
                List<String> serverNames = new ArrayList<>();
                for (Server server : this.servers) {
                    serverNames.add(server.getName());
                }
                for (String serverName : serverNames) {
                    this.stopServer(serverName);
                    this.removeServer(serverName);
                }

                // We store the cleared state
                this.store();
            }

            for (ManagementEventListener lstr : managementEventListeners) {
                try {
                    lstr.onRemoveAllResources();
                } catch (Throwable ee) {
                    logger.error("Exception while invoking onRemoveAllResources", ee);
                }
            }
        }
    }

    public ServerImpl addServer(String serverName, String hostAddress, int port) throws Exception {
        return addServer(serverName, hostAddress, port, IpChannelType.SCTP, false, 0, 32, 32, null);
    }

    public Server addServer(String serverName, String hostAddress, int port, IpChannelType ipChannelType, String[] extraHostAddresses) throws Exception {
        return addServer(serverName, hostAddress, port, ipChannelType, false, 0, 32, 32, extraHostAddresses);
    }

    public ServerImpl addServer(String serverName, String hostAddress, int port, IpChannelType ipChannelType, boolean acceptAnonymousConnections,
                                int maxConcurrentConnectionsCount, String[] extraHostAddresses) throws Exception {
		return addServer(serverName, hostAddress, port, ipChannelType, acceptAnonymousConnections, maxConcurrentConnectionsCount, 32, 32, extraHostAddresses);
	}

        public ServerImpl addServer(String serverName, String hostAddress, int port, IpChannelType ipChannelType, boolean acceptAnonymousConnections,
		int maxConcurrentConnectionsCount, int maxInputSctpStreams, int maxOutputSctpStreams, String[] extraHostAddresses) throws Exception {
	    if (!this.started) {
            throw new Exception(String.format("Management=%s not started", this.name));
        }

        if (serverName == null) {
            throw new Exception("Server name cannot be null");
        }

        if (hostAddress == null) {
            throw new Exception("Server host address cannot be null");
        }

        if (port < 1) {
            throw new Exception("Server host port cannot be less than 1");
        }

        synchronized (this) {
            for (Server serverTemp : this.servers) {
                if (serverName.equals(serverTemp.getName())) {
                    throw new Exception(String.format("Server name=%s already exist", serverName));
                }

                if (hostAddress.equals(serverTemp.getHostAddress()) && port == serverTemp.getHostport()) {
                    throw new Exception(String.format("Server name=%s is already bound to %s:%d", serverTemp.getName(), serverTemp.getHostAddress(),
                        serverTemp.getHostport()));
                }
            }

            ServerImpl server = new ServerImpl(serverName, hostAddress, port, ipChannelType, acceptAnonymousConnections,
                maxConcurrentConnectionsCount, maxInputSctpStreams, maxOutputSctpStreams,extraHostAddresses);
            server.setManagement(this);

            this.servers.add(server);
            // this.servers.add(server);

            this.store();

            for (ManagementEventListener managementEventListener : managementEventListeners) {
                try {
                    managementEventListener.onServerAdded(server);
                } catch (Throwable ee) {
                    logger.error("Exception while invoking onServerAdded", ee);
                }
            }

            if (logger.isInfoEnabled()) {
                logger.info(String.format("Created Server=%s", server.getName()));
            }

            return server;
        }
    }

    public void removeServer(String serverName) throws Exception {

        if (!this.started) {
            throw new Exception(String.format("Management=%s not started", this.name));
        }

        if (serverName == null) {
            throw new Exception("Server name cannot be null");
        }

        synchronized (this) {
            Server removeServer = null;
            for (Server serverTemp : this.servers) {
                ServerImpl serverImplTemp = (ServerImpl) serverTemp;
                if (serverName.equals(serverImplTemp.getName())) {
                    if (serverTemp.isStarted()) {
                        throw new Exception(String.format("Server=%s is started. Stop the server before removing", serverName));
                    }

                    if(serverImplTemp.anonymAssociations.size() !=0 || serverImplTemp.associations.size() != 0){
                        throw new Exception(String.format("Server=%s has Associations. Remove all those Associations before removing Server", serverName));
                    }
                    removeServer = serverImplTemp;
                    break;
                }
            }

            if (removeServer == null) {
                throw new Exception(String.format("No Server found with name=%s", serverName));
            }

            this.servers.remove(removeServer);
            // this.servers.remove(removeServer);

            this.store();

            for (ManagementEventListener managementEventListener : managementEventListeners) {
                try {
                    managementEventListener.onServerRemoved(removeServer);
                } catch (Throwable ee) {
                    logger.error("Exception while invoking onServerRemoved", ee);
                }
            }
        }
    }

    public void startServer(String serverName) throws Exception {

        if (!this.started) {
            throw new Exception(String.format("Management=%s not started", this.name));
        }

        if (name == null) {
            throw new Exception("Server name cannot be null");
        }

        for (Server serverTemp : servers) {

            if (serverName.equals(serverTemp.getName())) {
                if (serverTemp.isStarted()) {
                    throw new Exception(String.format("Server=%s is already started", serverName));
                }
                ((ServerImpl) serverTemp).start();
                this.store();
                return;
            }
        }

        throw new Exception(String.format("No Server found with name=%s", serverName));
    }

    public void stopServer(String serverName) throws Exception {

        if (!this.started) {
            throw new Exception(String.format("Management=%s not started", this.name));
        }

        if (serverName == null) {
            throw new Exception("Server name cannot be null");
        }

        for (Server serverTemp : servers) {

            if (serverName.equals(serverTemp.getName())) {
                ((ServerImpl) serverTemp).stop();
                this.store();
                return;
            }
        }

        throw new Exception(String.format("No Server found with name=%s", serverName));
    }

    public AssociationImpl addServerAssociation(String peerAddress, int peerPort, String serverName, String associationName) throws Exception {
        return addServerAssociation(peerAddress, peerPort, serverName, associationName, IpChannelType.SCTP);
    }

    public AssociationImpl addServerAssociation(String peerAddress, int peerPort, String serverName, String assocName, IpChannelType ipChannelType)
        throws Exception {

        if (!this.started) {
            throw new Exception(String.format("Management=%s not started", this.name));
        }

        if (peerAddress == null) {
            throw new Exception("Peer address cannot be null");
        }

        if (peerPort < 1) {
            throw new Exception("Peer port cannot be less than 1");
        }

        if (serverName == null) {
            throw new Exception("Server name cannot be null");
        }

        if (assocName == null) {
            throw new Exception("Association name cannot be null");
        }

        synchronized (this) {
            if (this.associations.get(assocName) != null) {
                throw new Exception(String.format("Already has association=%s", assocName));
            }

            Server server = null;

            for (Server serverTemp : this.servers) {
                if (serverTemp.getName().equals(serverName)) {
                    server = serverTemp;
                }
            }

            if (server == null) {
                throw new Exception(String.format("No Server found for name=%s", serverName));
            }

            for (Association associationTemp : this.associations.values()) {

                if (peerAddress.equals(associationTemp.getPeerAddress()) && associationTemp.getPeerPort() == peerPort) {
                    throw new Exception(String.format("Already has association=%s with same peer address=%s and port=%d", associationTemp.getName(),
                        peerAddress, peerPort));
                }
            }

            if (server.getIpChannelType() != ipChannelType)
                throw new Exception(String.format("Server and Association have different IP channel types"));

            AssociationImpl association = new AssociationImpl(peerAddress, peerPort, serverName, assocName, ipChannelType);
            association.setManagement(this);

            this.associations.put(assocName, association);
            ((ServerImpl) server).associations.add(assocName);
            // ((ServerImpl) server).associations.add(assocName);

            this.store();

            for (ManagementEventListener managementEventListener : managementEventListeners) {
                try {
                    managementEventListener.onAssociationAdded(association);
                } catch (Throwable ee) {
                    logger.error("Exception while invoking onAssociationAdded", ee);
                }
            }

            if (logger.isInfoEnabled()) {
                logger.info(String.format("Added Association=%s of type=%s", association.getName(), association.getAssociationType()));
            }

            return association;
        }
    }

    public AssociationImpl addAssociation(String hostAddress, int hostPort, String peerAddress, int peerPort, String associationName) throws Exception {
        return addAssociation(hostAddress, hostPort, peerAddress, peerPort, associationName, IpChannelType.SCTP, null);
    }

    public AssociationImpl addAssociation(String hostAddress, int hostPort, String peerAddress, int peerPort, String assocName, IpChannelType ipChannelType,
                                          String[] extraHostAddresses) throws Exception {

        if (!this.started) {
            throw new Exception(String.format("Management=%s not started", this.name));
        }

        if (hostAddress == null) {
            throw new Exception("Host address cannot be null");
        }

        if (hostPort < 0) {
            throw new Exception("Host port cannot be less than 0");
        }

        if (peerAddress == null) {
            throw new Exception("Peer address cannot be null");
        }

        if (peerPort < 1) {
            throw new Exception("Peer port cannot be less than 1");
        }

        if (assocName == null) {
            throw new Exception("Association name cannot be null");
        }

        synchronized (this) {
            for (Association associationTemp : this.associations.values()) {

                if (assocName.equals(associationTemp.getName())) {
                    throw new Exception(String.format("Already has association=%s", associationTemp.getName()));
                }

                if (peerAddress.equals(associationTemp.getPeerAddress()) && associationTemp.getPeerPort() == peerPort) {
                    throw new Exception(String.format("Already has association=%s with same peer address=%s and port=%d", associationTemp.getName(),
                        peerAddress, peerPort));
                }

                if (hostAddress.equals(associationTemp.getHostAddress()) && associationTemp.getHostPort() == hostPort) {
                    throw new Exception(String.format("Already has association=%s with same host address=%s and port=%d", associationTemp.getName(),
                        hostAddress, hostPort));
                }

            }

            AssociationImpl association = new AssociationImpl(hostAddress, hostPort, peerAddress, peerPort, assocName, ipChannelType, extraHostAddresses);
            association.setManagement(this);

            this.associations.put(assocName, association);
            // associations.put(assocName, association);

            this.store();

            for (ManagementEventListener lstr : managementEventListeners) {
                try {
                    lstr.onAssociationAdded(association);
                } catch (Throwable ee) {
                    logger.error("Exception while invoking onAssociationAdded", ee);
                }
            }

            if (logger.isInfoEnabled()) {
                logger.info(String.format("Added Association=%s of type=%s", association.getName(), association.getAssociationType()));
            }

            return association;
        }
    }

    public Association getAssociation(String assocName) throws Exception {
        if (assocName == null) {
            throw new Exception("Association name cannot be null");
        }
        Association associationTemp = this.associations.get(assocName);

        if (associationTemp == null) {
            throw new Exception(String.format("No Association found for name=%s", assocName));
        }
        return associationTemp;
    }

    /**
     * @return the associations
     */
    public Map<String, Association> getAssociations() {
        Map<String, Association> result = new NonBlockingHashMap<>();
        result.putAll(this.associations);
        return result;
    }

    public void startAssociation(String assocName) throws Exception {
        if (!this.started) {
            throw new Exception(String.format("Management=%s not started", this.name));
        }

        if (assocName == null) {
            throw new Exception("Association name cannot be null");
        }

        Association associationTemp = this.associations.get(assocName);

        if (associationTemp == null) {
            throw new Exception(String.format("No Association found for name=%s", assocName));
        }

        if (associationTemp.isStarted()) {
            throw new Exception(String.format("Association=%s is already started", assocName));
        }

        ((AssociationImpl) associationTemp).start();
        this.store();
    }

    public void stopAssociation(String assocName) throws Exception {
        if (!this.started) {
            throw new Exception(String.format("Management=%s not started", this.name));
        }

        if (assocName == null) {
            throw new Exception("Association name cannot be null");
        }

        Association association = this.associations.get(assocName);

        if (association == null) {
            throw new Exception(String.format("No Association found for name=%s", assocName));
        }

        ((AssociationImpl) association).stop();
        this.store();
    }

    public void removeAssociation(String assocName) throws Exception {
        if (!this.started) {
            throw new Exception(String.format("Management=%s not started", this.name));
        }

        if (assocName == null) {
            throw new Exception("Association name cannot be null");
        }

        synchronized (this) {
            Association association = this.associations.get(assocName);

            if (association == null) {
                throw new Exception(String.format("No Association found for name=%s", assocName));
            }

            if (association.isStarted()) {
                throw new Exception(String.format("Association name=%s is started. Stop before removing", assocName));
            }

            this.associations.remove(assocName);
            // this.associations.remove(assocName);

            if (((AssociationImpl) association).getAssociationType() == AssociationType.SERVER) {
                for (Server serverTemp : this.servers) {
                    if (serverTemp.getName().equals(association.getServerName())) {
                        ((ServerImpl) serverTemp).associations.remove(assocName);
                        break;
                    }
                }
            }

            this.store();

            for (ManagementEventListener managementEventListener : managementEventListeners) {
                try {
                    managementEventListener.onAssociationRemoved(association);
                } catch (Throwable ee) {
                    logger.error("Exception while invoking onAssociationRemoved", ee);
                }
            }
        }
    }

    /**
     * @return the servers
     */
    public List<Server> getServers() {
        return servers;
    }

    /**
     * @return the pendingChanges
     */
    protected MpscArrayQueue<ChangeRequest> getPendingChanges() {
        return pendingChanges;
    }

    /**
     * @return the socketSelector
     */
    protected Selector getSocketSelector() {
        return socketSelector;
    }

    protected void populateWorkerThread(int workerThreadTable[]) {
        for (int count = 0; count < workerThreadTable.length; count++) {
            if (this.workerThreadCount == this.workerThreads) {
                this.workerThreadCount = 0;
            }

            workerThreadTable[count] = this.workerThreadCount;
            this.workerThreadCount++;
        }
    }

    protected ExecutorService getExecutorService(int index) {
        return this.executorServices[index];
    }

    @Override
    public double getCongControl_DelayThreshold_1() {
        // TODO Auto-generated method stub
        return 0;
    }

    @Override
    public double getCongControl_DelayThreshold_2() {
        // TODO Auto-generated method stub
        return 0;
    }

    @Override
    public double getCongControl_DelayThreshold_3() {
        // TODO Auto-generated method stub
        return 0;
    }

    @Override
    public void setCongControl_DelayThreshold_1(double delayThreshold) throws Exception {
        // TODO Auto-generated method stub

    }

    @Override
    public void setCongControl_DelayThreshold_2(double delayThreshold) throws Exception {
        // TODO Auto-generated method stub

    }

    @Override
    public void setCongControl_DelayThreshold_3(double delayThreshold) throws Exception {
        // TODO Auto-generated method stub

    }

    @Override
    public double getCongControl_BackToNormalDelayThreshold_1() {
        // TODO Auto-generated method stub
        return 0;
    }

    @Override
    public double getCongControl_BackToNormalDelayThreshold_2() {
        // TODO Auto-generated method stub
        return 0;
    }

    @Override
    public double getCongControl_BackToNormalDelayThreshold_3() {
        // TODO Auto-generated method stub
        return 0;
    }

    @Override
    public void setCongControl_BackToNormalDelayThreshold_1(double backToNormalDelayThreshold) throws Exception {
        // TODO Auto-generated method stub

    }

    @Override
    public void setCongControl_BackToNormalDelayThreshold_2(double backToNormalDelayThreshold) throws Exception {
        // TODO Auto-generated method stub

    }

    @Override
    public void setCongControl_BackToNormalDelayThreshold_3(double backToNormalDelayThreshold) throws Exception {
        // TODO Auto-generated method stub

    }

    @Override
    public Boolean getOptionSctpDisableFragments() {
        // TODO Auto-generated method stub
        return null;
    }

    @Override
    public void setOptionSctpDisableFragments(Boolean optionSctpDisableFragments) {
        // TODO Auto-generated method stub

    }

    @Override
    public Integer getOptionSctpFragmentInterleave() {
        // TODO Auto-generated method stub
        return null;
    }

    @Override
    public void setOptionSctpFragmentInterleave(Integer optionSctpFragmentInterleave) {
        // TODO Auto-generated method stub

    }

    @Override
    public Boolean getOptionSctpNodelay() {
        // TODO Auto-generated method stub
        return null;
    }

    @Override
    public void setOptionSctpNodelay(Boolean optionSctpNodelay) {
        // TODO Auto-generated method stub

    }

    @Override
    public Integer getOptionSoSndbuf() {
        // TODO Auto-generated method stub
        return null;
    }

    @Override
    public void setOptionSoSndbuf(Integer optionSoSndbuf) {
        // TODO Auto-generated method stub

    }

    @Override
    public Integer getOptionSoRcvbuf() {
        // TODO Auto-generated method stub
        return null;
    }

    @Override
    public void setOptionSoRcvbuf(Integer optionSoRcvbuf) {
        // TODO Auto-generated method stub

    }

    @Override
    public Integer getOptionSoLinger() {
        // TODO Auto-generated method stub
        return null;
    }

    @Override
    public void setOptionSoLinger(Integer optionSoLinger) {
        // TODO Auto-generated method stub

    }

    @Override
    public Integer getOptionSctpInitMaxstreams_MaxOutStreams() {
        // TODO Auto-generated method stub
        return null;
    }

    @Override
    public Integer getOptionSctpInitMaxstreams_MaxInStreams() {
        // TODO Auto-generated method stub
        return null;
    }

    @Override
    public void setOptionSctpInitMaxstreams_MaxOutStreams(Integer maxOutStreams) {
        // TODO Auto-generated method stub

    }

    @Override
    public void setOptionSctpInitMaxstreams_MaxInStreams(Integer maxInStreams) {
        // TODO Auto-generated method stub

    }

    @Override
    public void addCongestionListener(CongestionListener listener) {
        // TODO Auto-generated method stub

    }

    @Override
    public void removeCongestionListener(CongestionListener listener) {
        // TODO Auto-generated method stub

    }

    @Override
    public void modifyServer(String serverName, String hostAddress, Integer port, IpChannelType ipChannelType, Boolean acceptAnonymousConnections, Integer maxConcurrentConnectionsCount, String[] extraHostAddresses)
        throws Exception {

        if (!this.started) {
            throw new Exception(String.format("Management=%s MUST be started", this.name));
        }

        if (serverName == null) {
            throw new Exception("Server name cannot be null");
        }

        if (port !=null && (port < 1 || port > 65535)) {
            throw new Exception("Server host port cannot be less than 1 or more than 65535. But was : " + port);
        }

        synchronized (this) {
            Server modifyServer = null;
            for (Server server : this.servers) {
                ServerImpl currServer = (ServerImpl) server;
                if (serverName.equals(currServer.getName())) {

                    if (currServer.isStarted()) {
                        throw new Exception(String.format("Server=%s is started. Stop the server before modifying", serverName));
                    }

                    if(hostAddress != null)
                        currServer.setHostAddress(hostAddress);
                    if(port != null)
                        currServer.setHostport(port);
                    if(ipChannelType != null)
                        currServer.setIpChannelType(ipChannelType);
                    if(acceptAnonymousConnections != null)
                        currServer.setAcceptAnonymousConnections(acceptAnonymousConnections);
                    if(maxConcurrentConnectionsCount != null)
                        currServer.setMaxConcurrentConnectionsCount(maxConcurrentConnectionsCount);
                    if(extraHostAddresses!=null)
                        currServer.setExtraHostAddresses(extraHostAddresses);

                    modifyServer = currServer;
                    break;
                }
            }

            if (modifyServer == null) {
                throw new Exception(String.format("No Server found for modifying with name=%s", serverName));
            }

            this.store();

            for (ManagementEventListener managementEventListener : managementEventListeners) {
                try {
                    managementEventListener.onServerModified(modifyServer);
                } catch (Throwable ee) {
                    logger.error("Exception while invoking onServerModified", ee);
                }
            }
        }

    }

    @Override
    public void modifyServerAssociation(String associationName, String peerAddress, Integer peerPort, String serverName, IpChannelType ipChannelType)	throws Exception {
        if (!this.started) {
            throw new Exception(String.format("Management=%s not started", this.name));
        }

        if (associationName == null) {
            throw new Exception("Association name cannot be null");
        }

        if (peerPort != null && (peerPort < 1 || peerPort > 65535)) {
            throw new Exception("Exception! Peer port value = " + peerPort + ", but cannot be lower than 1 or higher than 65535.");
        }

        synchronized (this) {
            AssociationImpl association = (AssociationImpl) this.associations.get(associationName);

            if (association == null) {
                throw new Exception(String.format("No Association found for name=%s", associationName));
            }

            for (Association associationTemp : this.associations.values()) {

                if (peerAddress != null && peerAddress.equals(associationTemp.getPeerAddress()) && associationTemp.getPeerPort() == peerPort) {
                    throw new Exception(String.format("Already has association=%s with same peer address=%s and port=%d", associationTemp.getName(),
                        peerAddress, peerPort));
                }
            }

            if(peerAddress!=null)
                association.setPeerAddress(peerAddress);
            if(peerPort!= null)
                association.setPeerPort(peerPort);

            if(serverName!=null && !serverName.equals(association.getServerName()))
            {
                Server newServer = null;

                for (Server serverTemp : this.servers) {
                    if (serverTemp.getName().equals(serverName)) {
                        newServer = serverTemp;
                    }
                }

                if (newServer == null) {
                    throw new Exception(String.format("No Server found for name=%s", serverName));
                }

                if ((ipChannelType!=null && newServer.getIpChannelType() != ipChannelType)||(ipChannelType==null && newServer.getIpChannelType() != association.getIpChannelType()))
                    throw new Exception(String.format("Server and Association have different IP channel types"));

                //remove association from current server
                for (Server serverTemp : this.servers) {
                    if (serverTemp.getName().equals(association.getServerName())) {
                        ((ServerImpl) serverTemp).associations.remove(associationName);
                        break;
                    }
                }

                //add association name to server
                ((ServerImpl) newServer).associations.add(associationName);

                association.setServerName(serverName);
            }
            else
            {
                if(ipChannelType!=null)
                {
                    for (Server serverTemp : this.servers) {
                        if (serverTemp.getName().equals(association.getServerName())) {
                            if (serverTemp.getIpChannelType() != ipChannelType)
                                throw new Exception(String.format("Server and Association have different IP channel types"));
                        }
                    }

                    association.setIpChannelType(ipChannelType);
                }

            }

            this.store();

            for (ManagementEventListener managementEventListener : managementEventListeners) {
                try {
                    managementEventListener.onAssociationModified((Association)association);
                } catch (Throwable ee) {
                    logger.error("Exception while invoking onAssociationModified", ee);
                }
            }
        }
    }

    @Override
    public void modifyAssociation(String hostAddress, Integer hostPort, String peerAddress, Integer peerPort, String assocName,	IpChannelType ipChannelType, String[] extraHostAddresses) throws Exception {

        boolean isModified = false;
        if (!this.started) {
            throw new Exception(String.format("Management=%s not started", this.name));
        }

        if (hostPort != null && (hostPort < 1 || hostPort > 65535)) {
            throw new Exception("Exception! Host host port value = " + hostPort + ", but cannot be lower than 1 or higher than 65535.");
        }

        if (peerPort != null && (peerPort < 1 || peerPort > 65535)) {
            throw new Exception("Exception! Peer host port value = " + peerPort + ", but cannot be lower than 1 or higher than 65535.");
        }

        if (assocName == null) {
            throw new Exception("Association name cannot be null");
        }
        synchronized (this) {
            for (Association associationTemp : this.associations.values()) {

                if (peerAddress !=null && peerAddress.equals(associationTemp.getPeerAddress()) && associationTemp.getPeerPort() == peerPort) {
                    throw new Exception(String.format("Already has association=%s with same peer address=%s and port=%d", associationTemp.getName(),
                        peerAddress, peerPort));
                }

                if (hostAddress !=null && hostAddress.equals(associationTemp.getHostAddress()) && associationTemp.getHostPort() == hostPort) {
                    throw new Exception(String.format("Already has association=%s with same host address=%s and port=%d", associationTemp.getName(),
                        hostAddress, hostPort));
                }

            }

            AssociationImpl association = (AssociationImpl) this.associations.get(assocName);

            if(hostAddress!=null)
            {
                association.setHostAddress(hostAddress);
                isModified = true;
            }

            if(hostPort!= null)
            {
                association.setHostPort(hostPort);
                isModified = true;
            }

            if(peerAddress!=null)
            {
                association.setPeerAddress(peerAddress);
                isModified = true;
            }

            if(peerPort!= null)
            {
                association.setPeerPort(peerPort);
                isModified = true;
            }

            if(ipChannelType!=null)
            {
                association.setIpChannelType(ipChannelType);
                isModified = true;
            }

            if(extraHostAddresses!=null)
            {
                association.setExtraHostAddresses(extraHostAddresses);
                isModified = true;
            }

            if(association.isConnected() && isModified)
            {
                association.stop();
                association.start();
            }

            this.store();

            for (ManagementEventListener lstr : managementEventListeners) {
                try {
                    lstr.onAssociationModified((Association)association);
                } catch (Throwable ee) {
                    logger.error("Exception while invoking onAssociationModified", ee);
                }
            }

        }

    }
}


