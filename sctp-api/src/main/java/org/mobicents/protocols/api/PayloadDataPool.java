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

package org.mobicents.protocols.api;

import io.netty.buffer.ByteBuf;
import org.jctools.queues.MpscArrayQueue;

import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * High-performance object pool for PayloadData optimized for telecom workloads.
 * 
 * Designed for high-throughput scenarios (500K+ msg/s) with large memory (32GB+).
 * Uses adaptive sizing and JCTools MpscArrayQueue for minimal GC pressure.
 * 
 * Memory calculation for 32GB RAM, 500K msg/s:
 * - Object lifetime: ~10-50ms (processing + network latency)
 * - Concurrent objects needed: 500K * 0.05s = 25,000
 * - Pool size: 50,000-100,000 (2-4x concurrent need)
 * - Memory per object: ~200 bytes = 10-20MB (negligible vs 32GB)
 * 
 * @author <a href="mailto:nhanth87@gmail.com">nhanth87</a>
 */
public final class PayloadDataPool {
    
    /**
     * Pool sizing recommendations based on throughput:
     * - 10K msg/s: 5,000 pool size
     * - 100K msg/s: 20,000 pool size  
     * - 500K msg/s: 50,000-100,000 pool size (default)
     * - 1M+ msg/s: 200,000+ pool size
     */
    public static final int DEFAULT_POOL_SIZE_10K = 5_000;
    public static final int DEFAULT_POOL_SIZE_100K = 20_000;
    public static final int DEFAULT_POOL_SIZE_500K = 100_000;
    public static final int DEFAULT_POOL_SIZE_1M = 250_000;
    
    // For 32GB RAM, 500K msg/s - default to 100K
    private static final int DEFAULT_MAX_POOL_SIZE = DEFAULT_POOL_SIZE_500K;
    private static final int DEFAULT_INITIAL_POOL_SIZE = 10_000;
    
    // Adaptive sizing thresholds
    private static final double HIGH_HIT_RATE_THRESHOLD = 0.95;
    private static final double LOW_HIT_RATE_THRESHOLD = 0.70;
    private static final int ADAPTIVE_CHECK_INTERVAL = 10_000; // Check every 10K operations
    
    private final MpscArrayQueue<PayloadData> pool;
    private volatile int maxPoolSize;
    private final boolean enabled;
    private final boolean adaptive;
    
    // Statistics - using atomic for thread safety
    private final AtomicLong acquiredCount = new AtomicLong(0);
    private final AtomicLong releasedCount = new AtomicLong(0);
    private final AtomicLong createdCount = new AtomicLong(0);
    private final AtomicLong missedCount = new AtomicLong(0);
    private final AtomicInteger currentPoolSize = new AtomicInteger(0);
    private final AtomicLong lastAdaptiveCheck = new AtomicLong(0);
    
    /**
     * Create pool optimized for 500K msg/s with 32GB RAM.
     */
    public PayloadDataPool() {
        this(DEFAULT_MAX_POOL_SIZE, true, true);
    }
    
    /**
     * Create pool with specific target throughput.
     * 
     * @param targetThroughput Target messages per second (10_000, 100_000, 500_000, 1_000_000)
     */
    public PayloadDataPool(int targetThroughput) {
        this(calculatePoolSize(targetThroughput), true, true);
    }
    
    /**
     * Create a new PayloadDataPool.
     * 
     * @param maxPoolSize Maximum number of objects to keep in pool
     * @param enabled Whether pooling is enabled
     * @param adaptive Whether adaptive sizing is enabled
     */
    public PayloadDataPool(int maxPoolSize, boolean enabled, boolean adaptive) {
        this.maxPoolSize = maxPoolSize;
        this.enabled = enabled;
        this.adaptive = adaptive;
        // Round up to power of 2 for JCTools efficiency
        int capacity = roundUpToPowerOf2(Math.min(maxPoolSize, 100_000));
        this.pool = new MpscArrayQueue<>(capacity);
        
        if (enabled) {
            // Pre-allocate pool with initial capacity
            int initialSize = Math.min(DEFAULT_INITIAL_POOL_SIZE, maxPoolSize);
            preallocate(initialSize);
        }
    }
    
    /**
     * Round up to the next power of 2 for JCTools MpscArrayQueue efficiency.
     */
    private static int roundUpToPowerOf2(int size) {
        int capacity = 1;
        while (capacity < size) {
            capacity <<= 1;
        }
        return capacity;
    }
    
    /**
     * Calculate appropriate pool size based on target throughput.
     */
    private static int calculatePoolSize(int targetThroughput) {
        if (targetThroughput <= 10_000) {
            return DEFAULT_POOL_SIZE_10K;
        } else if (targetThroughput <= 100_000) {
            return DEFAULT_POOL_SIZE_100K;
        } else if (targetThroughput <= 500_000) {
            return DEFAULT_POOL_SIZE_500K;
        } else {
            return DEFAULT_POOL_SIZE_1M;
        }
    }
    
    /**
     * Pre-allocate objects in pool.
     */
    private void preallocate(int count) {
        for (int i = 0; i < count; i++) {
            PayloadData data = new PayloadData();
            data.setPooled(true);
            data.clear();
            pool.relaxedOffer(data);
        }
        createdCount.addAndGet(count);
        currentPoolSize.addAndGet(count);
    }
    
    /**
     * Acquire a PayloadData from pool or create new one.
     * 
     * @param dataLength Length of data
     * @param byteBuf ByteBuf containing data
     * @param complete Whether this is complete data
     * @param unordered Whether data is unordered
     * @param payloadProtocolId Protocol ID
     * @param streamNumber Stream number
     * @return PayloadData instance (pooled or new)
     */
    public PayloadData acquire(int dataLength, ByteBuf byteBuf, boolean complete, 
                               boolean unordered, int payloadProtocolId, int streamNumber) {
        PayloadData data = pool.relaxedPoll();
        
        if (data != null) {
            currentPoolSize.decrementAndGet();
            data.reset(dataLength, byteBuf, complete, unordered, payloadProtocolId, streamNumber);
            acquiredCount.incrementAndGet();
            
            // Adaptive sizing check
            if (adaptive) {
                checkAndAdjustSize();
            }
            
            return data;
        }
        
        // Pool empty, create new but mark as pooled so it can be returned to pool
        missedCount.incrementAndGet();
        createdCount.incrementAndGet();
        data = new PayloadData(dataLength, byteBuf, complete, unordered, payloadProtocolId, streamNumber);
        data.setPooled(true); // Mark as pooled so it CAN be returned to pool
        return data;
    }
    
    /**
     * Acquire a PayloadData for SCTP messages.
     * Convenience method with default protocol ID.
     */
    public PayloadData acquireSctp(int dataLength, ByteBuf byteBuf, boolean complete, 
                                   boolean unordered, int streamNumber) {
        return acquire(dataLength, byteBuf, complete, unordered, 0, streamNumber);
    }
    
    /**
     * Acquire a PayloadData for TCP messages.
     * Convenience method with stream 0 and ordered delivery.
     */
    public PayloadData acquireTcp(int dataLength, ByteBuf byteBuf) {
        return acquire(dataLength, byteBuf, true, false, 0, 0);
    }
    
    /**
     * Release a PayloadData back to pool.
     * 
     * @param data PayloadData to release
     */
    public void release(PayloadData data) {
        if (data == null || !enabled) {
            return;
        }
        
        // Only return pooled objects to pool
        if (!data.isPooled()) {
            data.clear();
            return;
        }
        
        int currentSize = currentPoolSize.get();
        if (currentSize < maxPoolSize) {
            data.clear();
            pool.relaxedOffer(data);
            currentPoolSize.incrementAndGet();
            releasedCount.incrementAndGet();
        } else {
            // Pool full, just clear
            data.clear();
        }
    }
    
    /**
     * Check hit rate and adjust pool size if needed.
     * Called periodically based on operation count.
     */
    private void checkAndAdjustSize() {
        long currentOps = acquiredCount.get() + missedCount.get();
        long lastCheck = lastAdaptiveCheck.get();
        
        if (currentOps - lastCheck < ADAPTIVE_CHECK_INTERVAL) {
            return;
        }
        
        if (!lastAdaptiveCheck.compareAndSet(lastCheck, currentOps)) {
            return; // Another thread is adjusting
        }
        
        double hitRate = getHitRate();
        int currentSize = currentPoolSize.get();
        
        // If hit rate is too low, increase pool size
        if (hitRate < LOW_HIT_RATE_THRESHOLD && currentSize < maxPoolSize) {
            int newSize = Math.min(currentSize + (currentSize / 4), maxPoolSize); // Increase by 25%
            int toAdd = newSize - currentSize;
            preallocate(toAdd);
        }
        
        // If hit rate is very high, we could reduce pool size (optional)
        // Currently we keep the size to avoid oscillation
    }
    
    /**
     * Get current pool size.
     */
    public int getPoolSize() {
        return currentPoolSize.get();
    }
    
    /**
     * Get maximum pool size.
     */
    public int getMaxPoolSize() {
        return maxPoolSize;
    }
    
    /**
     * Set new maximum pool size (can increase dynamically).
     */
    public void setMaxPoolSize(int newSize) {
        this.maxPoolSize = newSize;
    }
    
    /**
     * Check if pooling is enabled.
     */
    public boolean isEnabled() {
        return enabled;
    }
    
    /**
     * Check if adaptive sizing is enabled.
     */
    public boolean isAdaptive() {
        return adaptive;
    }
    
    /**
     * Get current hit rate (0.0 - 1.0).
     */
    public double getHitRate() {
        long acquired = acquiredCount.get();
        long missed = missedCount.get();
        long total = acquired + missed;
        return total == 0 ? 1.0 : (double) acquired / total;
    }
    
    /**
     * Get pool statistics.
     */
    public PoolStatistics getStatistics() {
        return new PoolStatistics(
            acquiredCount.get(),
            releasedCount.get(), 
            createdCount.get(),
            missedCount.get(),
            currentPoolSize.get(),
            maxPoolSize,
            getHitRate()
        );
    }
    
    /**
     * Estimate memory usage in bytes.
     */
    public long estimateMemoryUsage() {
        // Rough estimate: each PayloadData ~200 bytes (object header + fields)
        // Pool overhead: MpscArrayQueue internal array
        return currentPoolSize.get() * 200L + pool.capacity() * 8L; // 8 bytes per reference
    }
    
    /**
     * Clear the pool.
     */
    public void clear() {
        // Drain all elements from the queue
        while (pool.relaxedPoll() != null) {
            // Just drain
        }
        currentPoolSize.set(0);
    }
    
    /**
     * Pool statistics with detailed metrics.
     */
    public static class PoolStatistics {
        public final long acquired;
        public final long released;
        public final long created;
        public final long missed;
        public final int currentSize;
        public final int maxSize;
        public final double hitRate;
        
        public PoolStatistics(long acquired, long released, long created, 
                             long missed, int currentSize, int maxSize, double hitRate) {
            this.acquired = acquired;
            this.released = released;
            this.created = created;
            this.missed = missed;
            this.currentSize = currentSize;
            this.maxSize = maxSize;
            this.hitRate = hitRate;
        }
        
        /**
         * Calculate object reuse ratio.
         */
        public double getReuseRatio() {
            long total = acquired + missed;
            return total == 0 ? 0.0 : (double) acquired / total;
        }
        
        /**
         * Calculate pool utilization.
         */
        public double getPoolUtilization() {
            return maxSize == 0 ? 0.0 : (double) currentSize / maxSize;
        }
        
        @Override
        public String toString() {
            return String.format(
                "PayloadDataPoolStats[acquired=%d, released=%d, created=%d, missed=%d, " +
                "current=%d, max=%d, hitRate=%.2f%%, utilization=%.2f%%]",
                acquired, released, created, missed, currentSize, maxSize, 
                hitRate * 100, getPoolUtilization() * 100);
        }
    }
}
