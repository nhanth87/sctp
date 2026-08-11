package org.mobicents.protocols.sctp.spi;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class SctpProviderTest {
    @Test
    void blankBackendIsFstack() {
        assertEquals(SctpBackend.FSTACK_DPDK, SctpBackend.from(null));
        assertEquals(SctpBackend.FSTACK_DPDK, SctpBackend.from(""));
        assertEquals(SctpProvider.DEFAULT_FSTACK,
                SctpProvider.implementationClass(SctpBackend.FSTACK_DPDK));
        assertEquals(SctpProvider.NETTY_KERNEL,
                SctpProvider.implementationClass(SctpBackend.NETTY_KERNEL));
    }

    @Test
    void nettyIsJvmFallbackOnly() {
        assertTrue(SctpBackend.FSTACK_DPDK.nativeSafe());
        assertFalse(SctpBackend.NETTY_KERNEL.nativeSafe());
        assertTrue(SctpProvider.nettyFallbackEnabled());
    }

    @Test
    void createWithoutEitherImplementationFailsClosedOnFstack() {
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> SctpProvider.create("missing-impls"));
        assertTrue(ex.getMessage().contains("F-Stack"), ex.getMessage());
        assertEquals(1, ex.getCause().getSuppressed().length);
    }

    @Test
    void explicitNettyDoesNotPretendToBeDefault() {
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> SctpProvider.create("netty-only", SctpBackend.NETTY_KERNEL));
        assertTrue(ex.getMessage().contains("NETTY_KERNEL"), ex.getMessage());
    }
}
