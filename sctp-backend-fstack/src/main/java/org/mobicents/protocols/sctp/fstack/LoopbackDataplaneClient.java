package org.mobicents.protocols.sctp.fstack;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.mobicents.protocols.sctp.spi.SctpEventKind;
import org.mobicents.protocols.sctp.spi.SctpRecord;

/**
 * In-process loopback used by unit tests and native smoke boots.
 * Pairs two associations that share host/peer 5-tuples (reversed).
 */
final class LoopbackDataplaneClient implements FstackDataplaneClient {
    private final FstackSctpManagementImpl management;
    private final Map<Long, FstackAssociationImpl> open = new ConcurrentHashMap<>();

    LoopbackDataplaneClient(FstackSctpManagementImpl management) {
        this.management = management;
    }

    @Override
    public void start() {}

    @Override
    public void stop() {
        open.clear();
    }

    @Override
    public void open(FstackAssociationImpl association) {
        open.put(association.associationId(), association);
        FstackAssociationImpl peer = findPeer(association);
        int in = nz(management.getOptionSctpInitMaxstreams_MaxInStreams());
        int out = nz(management.getOptionSctpInitMaxstreams_MaxOutStreams());
        association.markUp(in, out);
        if (peer != null && peer.isStarted() && !peer.isUp()) {
            peer.markUp(in, out);
        } else if (peer != null && peer.isUp()) {
            // already up; still notify this side only
        }
    }

    @Override
    public void close(FstackAssociationImpl association) {
        open.remove(association.associationId());
        FstackAssociationImpl peer = findPeer(association);
        if (peer != null && peer.isUp()) {
            peer.markDown(SctpEventKind.SHUTDOWN);
        }
    }

    @Override
    public void send(SctpRecord record) {
        FstackAssociationImpl src = open.get(record.associationId());
        if (src == null) {
            return;
        }
        FstackAssociationImpl peer = findPeer(src);
        if (peer == null || !peer.isUp()) {
            return;
        }
        peer.deliver(SctpRecord.wrap(peer.associationId(), peer.generation(), SctpEventKind.PAYLOAD,
                record.streamId(), record.ppid(), record.unordered(), record.congestionLevel(),
                record.inboundStreams(), record.outboundStreams(), record.payloadSegment()));
    }

    private FstackAssociationImpl findPeer(FstackAssociationImpl assoc) {
        for (FstackAssociationImpl candidate : open.values()) {
            if (candidate.associationId() == assoc.associationId()) {
                continue;
            }
            if (sameTuple(assoc, candidate)) {
                return candidate;
            }
        }
        return null;
    }

    private static boolean sameTuple(FstackAssociationImpl a, FstackAssociationImpl b) {
        return a.getHostPort() == b.getPeerPort()
                && a.getPeerPort() == b.getHostPort()
                && eq(a.getHostAddress(), b.getPeerAddress())
                && eq(a.getPeerAddress(), b.getHostAddress());
    }

    private static boolean eq(String left, String right) {
        return left == null ? right == null : left.equals(right);
    }

    private static int nz(Integer value) {
        return value == null || value == 0 ? 16 : value;
    }
}
