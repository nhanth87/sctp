package org.mobicents.protocols.sctp.fstack;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;

import org.mobicents.protocols.sctp.spi.SctpDataplane;
import org.mobicents.protocols.sctp.spi.SctpEventKind;
import org.mobicents.protocols.sctp.spi.SctpRecord;

/**
 * GraalVM 25 FFM downcalls into {@code libsctp_fstack.so}.
 * Same ABI constants as the sidecar. Production-enable only after soak:
 * {@code sctp.fstack.inprocess.enabled=true}.
 */
final class FfmDataplaneClient implements FstackDataplaneClient {
    private final FstackSctpManagementImpl management;
    private Arena arena;
    private MethodHandle hello;
    private MethodHandle open;
    private MethodHandle close;
    private MethodHandle send;
    private MethodHandle capabilities;

    FfmDataplaneClient(FstackSctpManagementImpl management) {
        this.management = management;
    }

    @Override
    public void start() {
        if (!Boolean.parseBoolean(System.getProperty("sctp.fstack.inprocess.enabled", "false"))) {
            throw new IllegalStateException(
                    "IN_PROCESS disabled until soak gate: set sctp.fstack.inprocess.enabled=true");
        }
        Path path = FstackNativePaths.library();
        if (path == null || !Files.isRegularFile(path)) {
            throw new IllegalStateException("F-Stack in-process library missing"
                    + " — keep sctp.fstack.mode=SIDECAR until soak passes");
        }
        arena = Arena.ofShared();
        SymbolLookup lookup = SymbolLookup.libraryLookup(path, arena);
        Linker linker = Linker.nativeLinker();
        hello = downcall(linker, lookup, "sctp_fs_hello",
                FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_INT));
        open = downcall(linker, lookup, "sctp_fs_open",
                FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_LONG, ValueLayout.ADDRESS));
        close = downcall(linker, lookup, "sctp_fs_close",
                FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_LONG, ValueLayout.JAVA_LONG));
        send = downcall(linker, lookup, "sctp_fs_send",
                FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_LONG, ValueLayout.JAVA_LONG,
                        ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_INT,
                        ValueLayout.JAVA_INT),
                Linker.Option.critical(true));
        capabilities = downcall(linker, lookup, "sctp_fs_capabilities",
                FunctionDescriptor.of(ValueLayout.JAVA_INT));
        try {
            int rc = (int) hello.invokeExact(FstackAbi.ABI_VERSION);
            if (rc != FstackAbi.ABI_VERSION) {
                throw new IllegalStateException("native ABI mismatch: " + rc);
            }
            int caps = (int) capabilities.invokeExact();
            if ((caps & FstackAbi.CAP_LOOPBACK) == 0 && (caps & FstackAbi.CAP_DPDK) == 0) {
                throw new IllegalStateException("native library advertised no usable capability");
            }
            if (management.dataplane() == SctpDataplane.DPDK && (caps & FstackAbi.CAP_DPDK) == 0) {
                throw new IllegalStateException(
                        "libsctp_fstack.so has no DPDK capability — rebuild with SCTP_HAVE_DPDK=ON");
            }
            registerUpcall(linker);
        } catch (Throwable t) {
            throw new IllegalStateException("sctp_fs_hello failed", t);
        }
        Objects.requireNonNull(management, "management");
    }

    @Override
    public void stop() {
        if (arena != null) {
            arena.close();
        }
    }

    @Override
    public void open(FstackAssociationImpl association) {
        try {
            MemorySegment name = arena.allocateFrom(association.getName());
            int rc = (int) open.invokeExact(association.associationId(), name);
            if (rc != 0) {
                throw new IllegalStateException("sctp_fs_open rc=" + rc);
            }
            association.markUp(16, 16);
        } catch (Throwable t) {
            throw new IllegalStateException("sctp_fs_open failed", t);
        }
    }

    @Override
    public void close(FstackAssociationImpl association) {
        try {
            close.invokeExact(association.associationId(), association.generation());
        } catch (Throwable t) {
            throw new IllegalStateException("sctp_fs_close failed", t);
        }
    }

    @Override
    public void send(SctpRecord record) {
        try {
            MemorySegment buf = record.payloadSegment();
            if (buf.byteSize() == 0) {
                buf = MemorySegment.NULL;
            }
            int flags = FstackAbi.FLAG_COMPLETE | FstackAbi.FLAG_SHARED;
            if (record.unordered()) {
                flags |= FstackAbi.FLAG_UNORDERED;
            }
            int rc = (int) send.invokeExact(record.associationId(), record.generation(),
                    record.streamId(), record.ppid(), buf, record.payloadLength(), flags);
            if (rc == -2) {
                return;
            }
            if (rc != 0) {
                throw new IllegalStateException("sctp_fs_send rc=" + rc);
            }
        } catch (Throwable t) {
            throw new IllegalStateException("sctp_fs_send failed", t);
        }
    }

    /**
     * Static upcall target. Native may invoke this after soak; keep the signature
     * POD-only and never capture the FFM arena.
     */
    public static void onNativeEvent(long associationId, long generation, int kind, int stream,
            int ppid, MemorySegment payload, int payloadLen) {
        // Registered at build-time via native-image metadata. Live delivery is
        // wired when the native library starts posting upcalls.
        Objects.requireNonNull(payload, "payload");
        if (payloadLen < 0 || associationId <= 0 || generation <= 0) {
            return;
        }
        SctpEventKind ignored = switch (kind) {
            case FstackAbi.EVT_UP -> SctpEventKind.UP;
            case FstackAbi.EVT_SHUTDOWN -> SctpEventKind.SHUTDOWN;
            default -> SctpEventKind.PAYLOAD;
        };
        Objects.requireNonNull(ignored);
    }

    private void registerUpcall(Linker linker) throws Throwable {
        MethodHandle target = MethodHandles.lookup().findStatic(FfmDataplaneClient.class, "onNativeEvent",
                MethodType.methodType(void.class, long.class, long.class, int.class, int.class, int.class,
                        MemorySegment.class, int.class));
        FunctionDescriptor descriptor = FunctionDescriptor.ofVoid(
                ValueLayout.JAVA_LONG, ValueLayout.JAVA_LONG, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT,
                ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_INT);
        linker.upcallStub(target, descriptor, arena);
    }

    private static MethodHandle downcall(Linker linker, SymbolLookup lookup, String symbol,
            FunctionDescriptor descriptor, Linker.Option... options) {
        MemorySegment addr = lookup.find(symbol)
                .orElseThrow(() -> new IllegalStateException("missing native symbol " + symbol));
        return options.length == 0
                ? linker.downcallHandle(addr, descriptor)
                : linker.downcallHandle(addr, descriptor, options);
    }
}
