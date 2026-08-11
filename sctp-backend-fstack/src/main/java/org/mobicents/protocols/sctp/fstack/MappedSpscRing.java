package org.mobicents.protocols.sctp.fstack;

import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

import org.mobicents.protocols.sctp.spi.SctpEventKind;
import org.mobicents.protocols.sctp.spi.SctpRecord;

/**
 * Shared-memory SPSC ring. Payload bytes live in the mapped file; Java and C++
 * see the same pages. Offer copies into a slot only when the source is not
 * already that slot. Poll returns a slice — no heap {@code byte[]}.
 */
final class MappedSpscRing implements AutoCloseable {
    static final int MAGIC = 0x53465231; // SFR1
    static final int VERSION = 1;
    static final int HEADER = 64;
    static final int SLOT_PAYLOAD = 2048;
    static final int SLOT = 40 + SLOT_PAYLOAD;
    static final int DEFAULT_CAPACITY = 1024;

    private final FileChannel channel;
    private final Arena arena;
    private final MemorySegment map;
    private final int capacity;

    MappedSpscRing(Path path, boolean create) throws IOException {
        this.arena = Arena.ofShared();
        this.channel = create
                ? FileChannel.open(path, StandardOpenOption.CREATE, StandardOpenOption.READ,
                        StandardOpenOption.WRITE)
                : FileChannel.open(path, StandardOpenOption.READ, StandardOpenOption.WRITE);
        long bytes = HEADER + (long) SLOT * DEFAULT_CAPACITY;
        if (create) {
            channel.truncate(bytes);
        }
        this.map = channel.map(FileChannel.MapMode.READ_WRITE, 0, bytes, arena);
        if (create) {
            map.fill((byte) 0);
            map.set(ValueLayout.JAVA_INT_UNALIGNED, 0, MAGIC);
            map.set(ValueLayout.JAVA_INT_UNALIGNED, 4, VERSION);
            map.set(ValueLayout.JAVA_INT_UNALIGNED, 8, DEFAULT_CAPACITY);
            map.set(ValueLayout.JAVA_INT_UNALIGNED, 12, SLOT);
        }
        if (map.get(ValueLayout.JAVA_INT_UNALIGNED, 0) != MAGIC
                || map.get(ValueLayout.JAVA_INT_UNALIGNED, 4) != VERSION) {
            throw new IOException("ring ABI mismatch at " + path);
        }
        this.capacity = map.get(ValueLayout.JAVA_INT_UNALIGNED, 8);
    }

    boolean offer(SctpRecord record) {
        long head = map.get(ValueLayout.JAVA_LONG_UNALIGNED, 16);
        long tail = map.get(ValueLayout.JAVA_LONG_UNALIGNED, 24);
        if (tail - head >= capacity) {
            map.set(ValueLayout.JAVA_INT_UNALIGNED, 36, 1);
            return false;
        }
        writeSlot((int) (tail & (capacity - 1)), record);
        map.set(ValueLayout.JAVA_LONG_UNALIGNED, 24, tail + 1);
        map.set(ValueLayout.JAVA_INT_UNALIGNED, 36, 0);
        return true;
    }

    /** View of the next slot. Valid until {@link #release()}. */
    SctpRecord peek() {
        long head = map.get(ValueLayout.JAVA_LONG_UNALIGNED, 16);
        long tail = map.get(ValueLayout.JAVA_LONG_UNALIGNED, 24);
        if (head == tail) {
            return null;
        }
        return readSlotView((int) (head & (capacity - 1)));
    }

    void release() {
        long head = map.get(ValueLayout.JAVA_LONG_UNALIGNED, 16);
        long tail = map.get(ValueLayout.JAVA_LONG_UNALIGNED, 24);
        if (head != tail) {
            map.set(ValueLayout.JAVA_LONG_UNALIGNED, 16, head + 1);
        }
    }

    SctpRecord poll() {
        SctpRecord record = peek();
        if (record != null) {
            release();
        }
        return record;
    }

    boolean congested() {
        return map.get(ValueLayout.JAVA_INT_UNALIGNED, 36) != 0;
    }

    int used() {
        long head = map.get(ValueLayout.JAVA_LONG_UNALIGNED, 16);
        long tail = map.get(ValueLayout.JAVA_LONG_UNALIGNED, 24);
        long n = tail - head;
        if (n <= 0) {
            return 0;
        }
        return n >= capacity ? capacity : (int) n;
    }

    int capacity() {
        return capacity;
    }

    @Override
    public void close() throws IOException {
        arena.close();
        channel.close();
    }

    private void writeSlot(int index, SctpRecord record) {
        long off = HEADER + (long) index * SLOT;
        map.set(ValueLayout.JAVA_LONG_UNALIGNED, off, record.associationId());
        map.set(ValueLayout.JAVA_LONG_UNALIGNED, off + 8, record.generation());
        map.set(ValueLayout.JAVA_INT_UNALIGNED, off + 16, toKind(record.kind()));
        map.set(ValueLayout.JAVA_INT_UNALIGNED, off + 20, record.streamId());
        map.set(ValueLayout.JAVA_INT_UNALIGNED, off + 24, record.ppid());
        int flags = FstackAbi.FLAG_COMPLETE;
        if (record.unordered()) {
            flags |= FstackAbi.FLAG_UNORDERED;
        }
        map.set(ValueLayout.JAVA_INT_UNALIGNED, off + 28, flags);
        MemorySegment src = record.payloadSegment();
        int len = Math.min(record.payloadLength(), SLOT_PAYLOAD);
        map.set(ValueLayout.JAVA_INT_UNALIGNED, off + 32, len);
        MemorySegment dest = map.asSlice(off + 40, SLOT_PAYLOAD);
        if (len > 0 && !samePage(src, dest, len)) {
            MemorySegment.copy(src, 0, dest, 0, len);
        }
    }

    private SctpRecord readSlotView(int index) {
        long off = HEADER + (long) index * SLOT;
        long assoc = map.get(ValueLayout.JAVA_LONG_UNALIGNED, off);
        long generation = map.get(ValueLayout.JAVA_LONG_UNALIGNED, off + 8);
        int kind = map.get(ValueLayout.JAVA_INT_UNALIGNED, off + 16);
        int stream = map.get(ValueLayout.JAVA_INT_UNALIGNED, off + 20);
        int ppid = map.get(ValueLayout.JAVA_INT_UNALIGNED, off + 24);
        int flags = map.get(ValueLayout.JAVA_INT_UNALIGNED, off + 28);
        int len = Math.max(0, Math.min(map.get(ValueLayout.JAVA_INT_UNALIGNED, off + 32), SLOT_PAYLOAD));
        MemorySegment view = len == 0 ? MemorySegment.NULL : map.asSlice(off + 40, len);
        return SctpRecord.wrap(assoc, generation, fromKind(kind), stream, ppid,
                (flags & FstackAbi.FLAG_UNORDERED) != 0, 0, 16, 16, view);
    }

    private static boolean samePage(MemorySegment src, MemorySegment dest, int len) {
        if (src == null || dest == null || src.byteSize() < len || dest.byteSize() < len) {
            return false;
        }
        try {
            return src.address() == dest.address();
        } catch (UnsupportedOperationException heap) {
            return false;
        }
    }

    private static int toKind(SctpEventKind kind) {
        return switch (kind) {
            case UP -> FstackAbi.EVT_UP;
            case SHUTDOWN -> FstackAbi.EVT_SHUTDOWN;
            case LOST -> FstackAbi.EVT_LOST;
            case RESTART -> FstackAbi.EVT_RESTART;
            case CONGESTION -> FstackAbi.EVT_CONGESTION;
            default -> FstackAbi.EVT_PAYLOAD;
        };
    }

    private static SctpEventKind fromKind(int kind) {
        return switch (kind) {
            case FstackAbi.EVT_UP -> SctpEventKind.UP;
            case FstackAbi.EVT_SHUTDOWN -> SctpEventKind.SHUTDOWN;
            case FstackAbi.EVT_LOST -> SctpEventKind.LOST;
            case FstackAbi.EVT_RESTART -> SctpEventKind.RESTART;
            case FstackAbi.EVT_CONGESTION -> SctpEventKind.CONGESTION;
            default -> SctpEventKind.PAYLOAD;
        };
    }
}
