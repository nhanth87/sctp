# sctp-native-fstack

Reusable SCTP data plane (sidecar + GraalVM 25 FFM). Not tied to a single app.

**Default backend is F-Stack** (`sctp.backend=FSTACK_DPDK`). Netty / `com.sun.nio.sctp`
is the JVM-only fallback (`sctp.backend=NETTY_KERNEL` or start failure when
`sctp.backend.fallback=true`). GraalVM native images must not load Netty.

- C ABI: [`include/sctp_abi.h`](include/sctp_abi.h)
- SHM rings: [`include/sctp_ring.h`](include/sctp_ring.h)
- Sidecar: `sctp-fstack` (UDS control + SHM payload + eventfd wakeup)
- Shared library: `libsctp_fstack.so`
- Public pin: [nhanth87/f-stack](https://github.com/nhanth87/f-stack) `v1.25` — full local tree
  [`vendor/f-stack`](vendor/f-stack), SHA in [`provenance.json`](provenance.json).
- `SCTP_HAVE_LKSCTP=ON` is the explicit Linux-kernel lab/test build.
- `SCTP_HAVE_FSTACK_DPDK=ON` validates and links the SCTP-enabled v1.25 fork.
  The fork compiles the complete IPv4/IPv6 FreeBSD SCTP source set; the archive
  gate checks endpoint symbols so an `alias_sctp.c`-only build cannot pass.

```bash
./scripts/run-native-tests.sh
./build/sctp-fstack --socket data/sctp-fstack.sock --ring-dir data --dataplane LOOPBACK
```

```java
Management mgmt = SctpProvider.createStarted("SCTP"); // F-Stack, Netty only if start fails
```

LOOPBACK Java tests do not require DPDK. Production host and on-wire acceptance
gates are documented in [`docs/production-dpdk.md`](docs/production-dpdk.md).

## GraalVM + SS7

- C ABI is packed POD (`sctp_fs_frame_t` = 48 bytes). No FreeBSD internals.
- `sctp_fs_send(..., flags)` borrows the payload pointer (FFM `Linker.Option.critical`).
- M3UA (PPID 3) must be a **complete** SCTP user message. DATA (stream ≠ 0) is ordered.
  Stream 0 ASPSM/MGMT/RKM MAY be unordered (jSS7 ASPUP).
- Java hot path wraps Netty `ByteBuf` as `MemorySegment` — no `getData()` heap copy.
