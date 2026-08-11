package org.mobicents.protocols.sctp.spi;

import org.mobicents.protocols.api.Management;

/**
 * Default factory: F-Stack. Netty is JVM-only fallback when F-Stack cannot
 * be loaded or started. Native images must not load {@link SctpBackend#NETTY_KERNEL}.
 */
public final class SctpProvider {
    public static final String DEFAULT_FSTACK =
            "org.mobicents.protocols.sctp.fstack.FstackSctpManagementImpl";
    public static final String NETTY_KERNEL =
            "org.mobicents.protocols.sctp.netty.NettySctpManagementImpl";

    private SctpProvider() {}

    public static String implementationClass(SctpBackend backend) {
        return switch (backend) {
            case FSTACK_DPDK -> DEFAULT_FSTACK;
            case NETTY_KERNEL -> NETTY_KERNEL;
        };
    }

    /**
     * F-Stack unless {@code sctp.backend=NETTY_KERNEL}. If the F-Stack class
     * is missing on the JVM, falls back to Netty.
     */
    public static Management create(String name) throws Exception {
        return create(name, SctpBackend.from(System.getProperty("sctp.backend")), true);
    }

    /**
     * Strict factory: no fallback. Use {@link #create(String)} or
     * {@link #createStarted(String)} when Netty may substitute.
     */
    public static Management create(String name, SctpBackend backend) throws Exception {
        return create(name, backend, false);
    }

    /**
     * Default entry: start F-Stack. On JVM, a start failure falls back to Netty
     * unless {@code sctp.backend.fallback=false}.
     */
    public static Management createStarted(String name) throws Exception {
        SctpBackend requested = SctpBackend.from(System.getProperty("sctp.backend"));
        if (requested == SctpBackend.NETTY_KERNEL) {
            Management netty = create(name, SctpBackend.NETTY_KERNEL);
            netty.start();
            return netty;
        }
        Management fstack = create(name, SctpBackend.FSTACK_DPDK);
        try {
            fstack.start();
            return fstack;
        } catch (RuntimeException startFailed) {
            try {
                fstack.stop();
            } catch (RuntimeException ignored) {
                // already failed closed
            }
            if (!nettyFallbackEnabled()) {
                throw startFailed;
            }
            try {
                Management netty = create(name, SctpBackend.NETTY_KERNEL);
                netty.start();
                return netty;
            } catch (Exception netty) {
                startFailed.addSuppressed(netty);
                throw startFailed;
            }
        }
    }

    public static boolean isNativeImage() {
        return System.getProperty("org.graalvm.nativeimage.imagecode") != null;
    }

    public static boolean nettyFallbackEnabled() {
        if (isNativeImage()) {
            return false;
        }
        return !"false".equalsIgnoreCase(System.getProperty("sctp.backend.fallback", "true"));
    }

    private static Management create(String name, SctpBackend backend, boolean allowNettyFallback)
            throws Exception {
        if (backend == SctpBackend.NETTY_KERNEL && isNativeImage()) {
            throw new IllegalStateException("NETTY_KERNEL SCTP is forbidden in GraalVM native images");
        }
        try {
            return instantiate(implementationClass(backend), name);
        } catch (ReflectiveOperationException | LinkageError first) {
            if (backend != SctpBackend.FSTACK_DPDK || !allowNettyFallback || !nettyFallbackEnabled()) {
                throw new IllegalStateException("Cannot load SCTP backend " + backend, first);
            }
            try {
                return instantiate(NETTY_KERNEL, name);
            } catch (ReflectiveOperationException | LinkageError netty) {
                first.addSuppressed(netty);
                throw new IllegalStateException(
                        "F-Stack SCTP is the default and is unavailable; Netty fallback is not on the classpath",
                        first);
            }
        }
    }

    private static Management instantiate(String className, String name) throws Exception {
        Class<?> clazz = Class.forName(className);
        return (Management) clazz.getConstructor(String.class).newInstance(name);
    }
}
