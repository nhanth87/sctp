package org.mobicents.protocols.sctp.spi;

import org.mobicents.protocols.api.Management;

/**
 * Build-time-known factory. Native images must not load {@link SctpBackend#NETTY_KERNEL}.
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

    public static Management create(String name) throws Exception {
        return create(name, SctpBackend.from(System.getProperty("sctp.backend")));
    }

    public static Management create(String name, SctpBackend backend) throws Exception {
        if (backend == SctpBackend.NETTY_KERNEL && isNativeImage()) {
            throw new IllegalStateException("NETTY_KERNEL SCTP is forbidden in GraalVM native images");
        }
        Class<?> clazz = Class.forName(implementationClass(backend));
        return (Management) clazz.getConstructor(String.class).newInstance(name);
    }

    public static boolean isNativeImage() {
        return System.getProperty("org.graalvm.nativeimage.imagecode") != null;
    }
}
