package org.mobicents.protocols.sctp.spi;

import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.LongSupplier;

/**
 * STP-aware AIMD send limiter shared by F-Stack, Netty (admit only), and HTTP.
 *
 * <p>Signals: realtime {@link SctpCongestionSample} (M3UA SCON, ring occupancy,
 * rwnd/flight/cwnd, send-fail). Response: multiplicative cut of permitted TPS,
 * hold (do not probe — STP will ban a bouncing sender), then additive increase.
 * Stream 0 (ASPSM/ASPTM) is never throttled. Fail-fast — no {@code Thread.sleep}.
 */
public final class AdaptiveSendController {
    private static final long NANOS_PER_TOKEN = 1_000_000_000L;
    private static final int DEFAULT_CEILING_WHEN_UNLIMITED = 1_000;
    private static final AdaptiveSendController DISABLED = new AdaptiveSendController(
            new AdaptiveSendPolicy(0, 10, 2_000L, 1_000L, 0, true, 0.50, 0.25, 0.10),
            System::nanoTime, true);

    private final AdaptiveSendPolicy policy;
    private final LongSupplier nanoTime;
    private final boolean disabled;
    private final CopyOnWriteArrayList<SctpCongestionReporter> reporters = new CopyOnWriteArrayList<>();
    private final CopyOnWriteArrayList<SctpCongestionSink> sinks = new CopyOnWriteArrayList<>();
    private final AtomicReference<SctpCongestionSample> latestSample =
            new AtomicReference<>(SctpCongestionSample.NONE);
    private final AtomicInteger[] bySource;
    private final AtomicInteger effectiveLevel = new AtomicInteger(0);
    private final AtomicReference<SctpCongestionSource> dominant = new AtomicReference<>(SctpCongestionSource.NONE);
    private final AtomicInteger permittedTps = new AtomicInteger(0);
    private final AtomicLong lastSignalNanos = new AtomicLong(Long.MIN_VALUE);
    private final AtomicLong lastSignalEpochMs = new AtomicLong(0);
    private final AtomicLong lastRecoveryNanos = new AtomicLong(0);
    private final AtomicLong accepted = new AtomicLong();
    private final AtomicLong rejected = new AtomicLong();
    private final AtomicLong tokenNanos = new AtomicLong(0);
    private final AtomicLong lastRefillNanos;
    private final AtomicLong offerWindowStartNanos;
    private final AtomicInteger offerWindowCount = new AtomicInteger();
    private final AtomicInteger offeredTps = new AtomicInteger();

    public AdaptiveSendController(AdaptiveSendPolicy policy) {
        this(policy, System::nanoTime);
    }

    public AdaptiveSendController(AdaptiveSendPolicy policy, LongSupplier nanoTime) {
        this(policy, nanoTime, false);
    }

    private AdaptiveSendController(AdaptiveSendPolicy policy, LongSupplier nanoTime, boolean disabled) {
        this.policy = Objects.requireNonNull(policy, "policy");
        this.nanoTime = Objects.requireNonNull(nanoTime, "nanoTime");
        this.disabled = disabled;
        SctpCongestionSource[] sources = SctpCongestionSource.values();
        this.bySource = new AtomicInteger[sources.length];
        for (int i = 0; i < sources.length; i++) {
            bySource[i] = new AtomicInteger(0);
        }
        long now = nanoTime.getAsLong();
        this.lastRefillNanos = new AtomicLong(now);
        this.offerWindowStartNanos = new AtomicLong(now);
        this.permittedTps.set(Math.max(0, policy.maxTps()));
    }

    public static AdaptiveSendController disabled() {
        return DISABLED;
    }

    public static AdaptiveSendController createDefault() {
        return new AdaptiveSendController(AdaptiveSendPolicy.fromSystemProperties());
    }

    public AdaptiveSendPolicy policy() {
        return policy;
    }

    public void addReporter(SctpCongestionReporter reporter) {
        reporters.addIfAbsent(Objects.requireNonNull(reporter, "reporter"));
    }

    public void removeReporter(SctpCongestionReporter reporter) {
        reporters.remove(reporter);
    }

    public void addSink(SctpCongestionSink sink) {
        sinks.addIfAbsent(Objects.requireNonNull(sink, "sink"));
    }

    public void removeSink(SctpCongestionSink sink) {
        sinks.remove(sink);
    }

    /** Last realtime sample. {@link SctpCongestionSample#NONE} until the first ingest. */
    public SctpCongestionSample latestSample() {
        return latestSample.get();
    }

    /**
     * Stream 0 (RFC 4666 M3UA management) always succeeds. User DATA consults
     * the token bucket and may fail-fast.
     */
    public boolean tryAcquire(int streamId) {
        if (streamId == 0) {
            return true;
        }
        return tryAcquireUserData();
    }

    public boolean tryAcquireUserData() {
        if (disabled) {
            return true;
        }
        long now = nanoTime.getAsLong();
        noteOffer(now);
        maybeRecover(now);
        int level = effectiveLevel.get();
        if (level >= 3 && policy.pauseUserDataAtSevere()) {
            rejected.incrementAndGet();
            return false;
        }
        if (level == 0 && policy.maxTps() <= 0) {
            accepted.incrementAndGet();
            return true;
        }
        int rate = Math.max(policy.minTps(), permittedTps.get());
        if (!takeToken(now, rate)) {
            rejected.incrementAndGet();
            return false;
        }
        accepted.incrementAndGet();
        return true;
    }

    /** HTTP / MAP start: do not create work the STP will drop or ban. */
    public boolean canAdmitNewWork() {
        if (disabled) {
            return true;
        }
        long now = nanoTime.getAsLong();
        maybeRecover(now);
        int level = effectiveLevel.get();
        if (level >= 3) {
            return false;
        }
        if (level == 0) {
            return true;
        }
        return availableTokens(now, Math.max(policy.minTps(), permittedTps.get())) >= 1;
    }

    /** Convenience for tests / Netty listeners that only have a level. */
    public void noteSignal(SctpCongestionSource source, int level) {
        if (disabled || source == null || source == SctpCongestionSource.NONE) {
            return;
        }
        noteSample(SctpCongestionSample.signal(source, level));
    }

    /**
     * Ingest one realtime observation. Every sample updates hold / AIMD and
     * fans out to {@link SctpCongestionSink}s. Level-transition reporters stay
     * quiet on same-level repeats.
     */
    public void noteSample(SctpCongestionSample sample) {
        if (disabled || sample == null || sample.source() == SctpCongestionSource.NONE) {
            return;
        }
        latestSample.set(sample);
        bySource[sample.source().ordinal()].set(sample.level());
        recompute(sample.source(), nanoTime.getAsLong());
        fanoutSample(sample);
    }

    public SctpCongestionSnapshot snapshot() {
        if (disabled) {
            return SctpCongestionSnapshot.idle();
        }
        long now = nanoTime.getAsLong();
        maybeRecover(now);
        int level = effectiveLevel.get();
        long epochMs = lastSignalEpochMs.get();
        Instant at = epochMs <= 0 ? Instant.EPOCH : Instant.ofEpochMilli(epochMs);
        return new SctpCongestionSnapshot(
                level,
                dominant.get(),
                permittedTps.get(),
                offeredTps.get(),
                accepted.get(),
                rejected.get(),
                at,
                level > 0 || rejected.get() > 0,
                retryAfterSeconds(now, level),
                latestSample.get());
    }

    private void recompute(SctpCongestionSource trigger, long now) {
        int max = 0;
        SctpCongestionSource who = SctpCongestionSource.NONE;
        for (SctpCongestionSource src : SctpCongestionSource.values()) {
            if (src == SctpCongestionSource.NONE) {
                continue;
            }
            int lvl = bySource[src.ordinal()].get();
            if (lvl > max) {
                max = lvl;
                who = src;
            }
        }
        int old = effectiveLevel.getAndSet(max);
        dominant.set(who);
        lastSignalNanos.set(now);
        lastSignalEpochMs.set(System.currentTimeMillis());
        lastRecoveryNanos.set(now);
        applyRate(max, old, trigger, now);
        if (old != max) {
            SctpCongestionSnapshot snap = snapshotAt(now, max, who);
            if (max > old) {
                for (SctpCongestionReporter reporter : reporters) {
                    reporter.onCongestion(snap);
                }
            } else {
                for (SctpCongestionReporter reporter : reporters) {
                    reporter.onCongestionAbated(snap);
                }
            }
        }
    }

    private void applyRate(int newLevel, int oldLevel, SctpCongestionSource trigger, long now) {
        int ceiling = ceilingTps();
        if (newLevel == 0) {
            // Do not snap back to ceiling — AIMD recovery after hold.
            if (oldLevel > 0 && permittedTps.get() <= 0) {
                permittedTps.set(Math.max(policy.minTps(), (int) (ceiling * policy.level3Fraction())));
            }
            return;
        }
        int target = Math.max(policy.minTps(), (int) Math.round(ceiling * policy.fractionFor(newLevel)));
        int current = permittedTps.get();
        if (current <= 0 || current > target) {
            permittedTps.set(target);
        } else if (newLevel == oldLevel && trigger != SctpCongestionSource.NONE) {
            // Repeated SCON / ring-full at the same level: still too fast.
            permittedTps.set(Math.max(policy.minTps(), current / 2));
        }
        // newLevel > oldLevel but already below the new fraction: keep current (more conservative).
        tokenNanos.set(0);
        lastRefillNanos.set(now);
    }

    private void maybeRecover(long now) {
        int level = effectiveLevel.get();
        int ceiling = ceilingTps();
        int current = permittedTps.get();
        if (current >= ceiling && level == 0) {
            return;
        }
        long lastSignal = lastSignalNanos.get();
        if (lastSignal != Long.MIN_VALUE && now - lastSignal < policy.holdMs() * 1_000_000L) {
            return;
        }
        long lastRec = lastRecoveryNanos.get();
        if (now - lastRec < policy.recoveryIntervalMs() * 1_000_000L) {
            return;
        }
        if (!lastRecoveryNanos.compareAndSet(lastRec, now)) {
            return;
        }
        int step = policy.stepTps(ceiling);
        int next = Math.min(ceiling, current + step);
        if (next > current) {
            permittedTps.set(next);
        }
    }

    private int ceilingTps() {
        if (policy.maxTps() > 0) {
            return policy.maxTps();
        }
        int offered = offeredTps.get();
        return Math.max(policy.minTps() * 10, offered > 0 ? offered : DEFAULT_CEILING_WHEN_UNLIMITED);
    }

    private boolean takeToken(long now, int rateTps) {
        refill(now, rateTps);
        while (true) {
            long cur = tokenNanos.get();
            if (cur < NANOS_PER_TOKEN) {
                return false;
            }
            if (tokenNanos.compareAndSet(cur, cur - NANOS_PER_TOKEN)) {
                return true;
            }
        }
    }

    private long availableTokens(long now, int rateTps) {
        refill(now, rateTps);
        return tokenNanos.get() / NANOS_PER_TOKEN;
    }

    private void refill(long now, int rateTps) {
        if (rateTps <= 0) {
            return;
        }
        long last = lastRefillNanos.get();
        long elapsed = now - last;
        if (elapsed <= 0) {
            return;
        }
        if (!lastRefillNanos.compareAndSet(last, now)) {
            return;
        }
        long add = elapsed * (long) rateTps;
        long burst = NANOS_PER_TOKEN * Math.max(2L, rateTps);
        while (true) {
            long cur = tokenNanos.get();
            long next = Math.min(burst, cur + add);
            if (tokenNanos.compareAndSet(cur, next)) {
                return;
            }
        }
    }

    private void noteOffer(long now) {
        long start = offerWindowStartNanos.get();
        if (now - start >= NANOS_PER_TOKEN) {
            if (offerWindowStartNanos.compareAndSet(start, now)) {
                offeredTps.set(offerWindowCount.getAndSet(1));
                return;
            }
        }
        offerWindowCount.incrementAndGet();
    }

    private int retryAfterSeconds(long now, int level) {
        if (level <= 0) {
            return 0;
        }
        long holdNanos = policy.holdMs() * 1_000_000L;
        long elapsed = now - lastSignalNanos.get();
        long remainMs = Math.max(0L, (holdNanos - elapsed) / 1_000_000L);
        int holdSec = (int) Math.max(1L, (remainMs + 999L) / 1000L);
        return level >= 3 ? Math.max(5, holdSec) : holdSec;
    }

    private SctpCongestionSnapshot snapshotAt(long now, int level, SctpCongestionSource source) {
        long epochMs = lastSignalEpochMs.get();
        Instant at = epochMs <= 0 ? Instant.EPOCH : Instant.ofEpochMilli(epochMs);
        return new SctpCongestionSnapshot(
                level, source, permittedTps.get(), offeredTps.get(),
                accepted.get(), rejected.get(), at, level > 0, retryAfterSeconds(now, level),
                latestSample.get());
    }

    private void fanoutSample(SctpCongestionSample sample) {
        for (SctpCongestionSink sink : sinks) {
            try {
                sink.onSample(sample);
            } catch (RuntimeException ignored) {
                // sink must not break AIMD
            }
        }
    }
}
