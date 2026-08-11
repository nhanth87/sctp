# sctp-native-fstack

Reusable SCTP data plane (sidecar + GraalVM 25 FFM). Not tied to a single app.

**Default backend is F-Stack** (`sctp.backend=FSTACK_DPDK`). Netty / `com.sun.nio.sctp`
is the JVM-only fallback (`sctp.backend=NETTY_KERNEL` or start failure when
`sctp.backend.fallback=true`). GraalVM native images must not load Netty.

- C ABI: [`include/sctp_abi.h`](include/sctp_abi.h)
- SHM rings: [`include/sctp_ring.h`](include/sctp_ring.h)
- Sidecar: `sctp-fstack` (UDS control + SHM payload + eventfd wakeup)
- Shared library: `libsctp_fstack.so`
- Public pin: [nhanth87/f-stack](https://github.com/nhanth87/f-stack) `v1.25` — local tree [`vendor/f-stack`](vendor/f-stack), SHA in [`provenance.json`](provenance.json)
- `freebsd/` is **not** present. `SCTP_HAVE_FSTACK` is **ON** and talks lksctp
  (`libsctp`). `SCTP_HAVE_DPDK` stays off until a NIC gate is green.

```bash
./scripts/run-native-tests.sh
./build/sctp-fstack --socket data/sctp-fstack.sock --ring-dir data
```

```java
Management mgmt = SctpProvider.createStarted("SCTP"); // F-Stack, Netty only if start fails
```

LOOPBACK Java tests do not require DPDK. Production DPDK needs hugepages + `vfio-pci`
and `-DSCTP_HAVE_DPDK=ON`.

## GraalVM + SS7

- C ABI is packed POD (`sctp_fs_frame_t` = 48 bytes). No FreeBSD internals.
- `sctp_fs_send(..., flags)` borrows the payload pointer (FFM `Linker.Option.critical`).
- M3UA (PPID 3) must be a **complete** SCTP user message. DATA (stream ≠ 0) is ordered.
  Stream 0 ASPSM/MGMT/RKM MAY be unordered (jSS7 ASPUP).
- Java hot path wraps Netty `ByteBuf` as `MemorySegment` — no `getData()` heap copy.
