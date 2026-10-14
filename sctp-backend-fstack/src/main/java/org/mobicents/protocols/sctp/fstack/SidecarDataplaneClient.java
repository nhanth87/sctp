package org.mobicents.protocols.sctp.fstack;

import java.io.IOException;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.SocketChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

import org.mobicents.protocols.sctp.spi.SctpCongestionRejectedException;
import org.mobicents.protocols.sctp.spi.SctpCongestionSample;
import org.mobicents.protocols.sctp.spi.SctpDataplane;
import org.mobicents.protocols.sctp.spi.SctpEventKind;
import org.mobicents.protocols.sctp.spi.SctpRecord;

/**
 * Control plane over UDS; payload over shared-memory SPSC rings.
 * Missing sidecar socket fails closed. Generation fencing stays in Java.
 */
final class SidecarDataplaneClient implements FstackDataplaneClient {
    private final FstackSctpManagementImpl management;
    private final Path socketPath;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private volatile SocketChannel channel;
    private volatile MappedSpscRing tx;
    private volatile MappedSpscRing rx;
    private volatile int negotiatedCaps;
    private ExecutorService reader;

    SidecarDataplaneClient(FstackSctpManagementImpl management) {
        this.management = management;
        String configured = System.getProperty("sctp.fstack.sidecar.socket",
                "data/sctp-fstack.sock");
        this.socketPath = Path.of(configured);
    }

    @Override
    public void start() {
        if (!running.compareAndSet(false, true)) {
            return;
        }
        try {
            if (!Files.exists(socketPath)) {
                throw new IllegalStateException("F-Stack sidecar socket missing: " + socketPath
                        + " (set sctp.fstack.dataplane=LOOPBACK for lab, or start sctp-fstack)");
            }
            channel = SocketChannel.open(StandardProtocolFamily.UNIX);
            channel.connect(UnixDomainSocketAddress.of(socketPath));
            writeFrame(FstackAbi.CMD_HELLO, 0, 0, FstackAbi.ABI_VERSION, 0, new byte[0]);
            Frame hello = readFrame();
            this.negotiatedCaps = (int) hello.generation;
            requireDataplaneCapability();
            attachRings(hello);
            reader = Executors.newFixedThreadPool(2, r -> {
                Thread t = new Thread(r, "sctp-fstack-sidecar");
                t.setDaemon(true);
                return t;
            });
            reader.execute(this::readLoop);
            reader.execute(this::drainLoop);
        } catch (IOException ex) {
            running.set(false);
            throw new IllegalStateException("Cannot attach F-Stack sidecar", ex);
        }
    }

    @Override
    public void stop() {
        running.set(false);
        if (reader != null) {
            reader.shutdownNow();
        }
        closeQuietly(tx);
        closeQuietly(rx);
        if (channel != null) {
            try {
                channel.close();
            } catch (IOException ignored) {
                // closed
            }
        }
    }

    @Override
    public void open(FstackAssociationImpl association) {
        String extra = association.getName() + "\n"
                + association.getAssociationType().name() + "\n"
                + nullToEmpty(association.getHostAddress()) + "\n"
                + nullToEmpty(association.getPeerAddress()) + "\n";
        writeFrame(FstackAbi.CMD_OPEN, association.associationId(), 0, association.getHostPort(),
                association.getPeerPort(), extra.getBytes());
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    @Override
    public void close(FstackAssociationImpl association) {
        writeFrame(FstackAbi.CMD_CLOSE, association.associationId(), association.generation(),
                0, 0, new byte[0]);
    }

    @Override
    public void send(SctpRecord record) {
        MappedSpscRing ring = tx;
        if (ring != null && ring.offer(record)) {
            return;
        }
        if (ring != null && ring.congested()) {
            FstackAssociationImpl assoc = management.associationById(record.associationId());
            management.ingest(assoc, SctpCongestionSample.ring(
                    record.associationId(), record.generation(), ring.used(), ring.capacity()));
            throw new SctpCongestionRejectedException(
                    "assoc-" + record.associationId(),
                    management.adaptiveController().snapshot());
        }
        int flags = FstackAbi.FLAG_COMPLETE;
        if (record.unordered()) {
            flags |= FstackAbi.FLAG_UNORDERED;
        }
        writeFrame(FstackAbi.CMD_SEND, record.associationId(), record.generation(),
                record.streamId(), record.ppid(), record.payloadBytes(), flags);
    }

    private void requireDataplaneCapability() {
        int required = FstackAbi.CAP_DPDK | FstackAbi.CAP_FSTACK_SCTP;
        if (management.dataplane() == SctpDataplane.DPDK && (negotiatedCaps & required) != required) {
            throw new IllegalStateException(
                    "sctp-fstack sidecar is not ready with validated F-Stack SCTP + DPDK; "
                            + "LOOPBACK is a separate lab dataplane and cannot satisfy DPDK");
        }
    }

    private void attachRings(Frame hello) throws IOException {
        if (hello.payload.isBlank()) {
            return;
        }
        String[] lines = hello.payload.split("\\R");
        if (lines.length < 2) {
            return;
        }
        tx = new MappedSpscRing(Path.of(lines[0].trim()), false);
        rx = new MappedSpscRing(Path.of(lines[1].trim()), false);
    }

    private void readLoop() {
        while (running.get() && channel != null) {
            try {
                Frame frame = readFrame();
                deliver(frame);
            } catch (IOException ex) {
                break;
            }
        }
    }

    private void drainLoop() {
        while (running.get()) {
            drainRx();
            try {
                Thread.sleep(5L);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    private void drainRx() {
        MappedSpscRing ring = rx;
        if (ring == null) {
            return;
        }
        SctpRecord record;
        while ((record = ring.peek()) != null) {
            try {
                FstackAssociationImpl assoc = management.associationById(record.associationId());
                if (assoc != null) {
                    assoc.deliver(record);
                }
            } finally {
                ring.release();
            }
        }
    }

    private void deliver(Frame frame) {
        FstackAssociationImpl assoc = management.associationById(frame.assocId);
        if (assoc == null) {
            return;
        }
        assoc.deliver(new SctpRecord(frame.assocId, frame.generation, toKind(frame.kind),
                frame.stream, frame.ppid, false, 0, 16, 16, frame.payload.getBytes()));
    }

    private Frame readFrame() throws IOException {
        ByteBuffer header = ByteBuffer.allocate(FstackAbi.FRAME_SIZE).order(ByteOrder.LITTLE_ENDIAN);
        if (!readFully(header)) {
            throw new IOException("sidecar closed");
        }
        header.flip();
        int magic = header.getInt();
        int version = header.getInt();
        int kind = header.getInt();
        long assocId = header.getLong();
        long generation = header.getLong();
        int stream = header.getInt();
        int ppid = header.getInt();
        int payloadLen = header.getInt();
        header.getInt();
        header.getInt();
        if (magic != FstackAbi.MAGIC || version != FstackAbi.ABI_VERSION) {
            throw new IOException("sidecar ABI mismatch magic=" + magic + " version=" + version);
        }
        byte[] payload = new byte[Math.max(0, payloadLen)];
        if (payloadLen > 0) {
            ByteBuffer body = ByteBuffer.allocate(payloadLen);
            if (!readFully(body)) {
                throw new IOException("sidecar payload truncated");
            }
            body.flip();
            body.get(payload);
        }
        return new Frame(kind, assocId, generation, stream, ppid, new String(payload));
    }

    private boolean readFully(ByteBuffer buffer) throws IOException {
        while (buffer.hasRemaining()) {
            int n = Objects.requireNonNull(channel).read(buffer);
            if (n < 0) {
                return false;
            }
        }
        return true;
    }

    private void writeFrame(int kind, long assocId, long generation, int stream, int ppid, byte[] payload) {
        writeFrame(kind, assocId, generation, stream, ppid, payload, 0);
    }

    private void writeFrame(int kind, long assocId, long generation, int stream, int ppid, byte[] payload,
            int flags) {
        if (channel == null) {
            throw new IllegalStateException("sidecar not attached");
        }
        ByteBuffer buffer = ByteBuffer.allocate(FstackAbi.FRAME_SIZE + payload.length)
                .order(ByteOrder.LITTLE_ENDIAN);
        buffer.putInt(FstackAbi.MAGIC);
        buffer.putInt(FstackAbi.ABI_VERSION);
        buffer.putInt(kind);
        buffer.putLong(assocId);
        buffer.putLong(generation);
        buffer.putInt(stream);
        buffer.putInt(ppid);
        buffer.putInt(payload.length);
        buffer.putInt(flags);
        buffer.putInt(0);
        buffer.put(payload);
        buffer.flip();
        try {
            while (buffer.hasRemaining()) {
                channel.write(buffer);
            }
        } catch (IOException ex) {
            throw new IllegalStateException("sidecar write failed", ex);
        }
    }

    private static void closeQuietly(AutoCloseable closeable) {
        if (closeable == null) {
            return;
        }
        try {
            closeable.close();
        } catch (Exception ignored) {
            // closed
        }
    }

    private static SctpEventKind toKind(int kind) {
        return switch (kind) {
            case FstackAbi.EVT_UP -> SctpEventKind.UP;
            case FstackAbi.EVT_SHUTDOWN -> SctpEventKind.SHUTDOWN;
            case FstackAbi.EVT_LOST -> SctpEventKind.LOST;
            case FstackAbi.EVT_RESTART -> SctpEventKind.RESTART;
            case FstackAbi.EVT_CONGESTION -> SctpEventKind.CONGESTION;
            default -> SctpEventKind.PAYLOAD;
        };
    }

    private record Frame(int kind, long assocId, long generation, int stream, int ppid, String payload) {}
}
