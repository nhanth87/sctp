# sctp-native-fstack

Reusable SCTP data plane (sidecar + GraalVM 25 FFM). Not tied to a single app.

- C ABI: [`include/sctp_abi.h`](include/sctp_abi.h)
- SHM rings: [`include/sctp_ring.h`](include/sctp_ring.h)
- Sidecar: `sctp-fstack` (UDS control + SHM payload + eventfd wakeup)
- Shared library: `libsctp_fstack.so`
- Local F-Stack: [`vendor/f-stack`](vendor/f-stack) — pin `v1.25` / `761639943bdda33103aa98241ca6a3079f1c1b7e` in [`provenance.json`](provenance.json)
- `freebsd/` is **not** present (stripped). Do not re-enable FreeBSD SCTP sources.

Stock F-Stack does **not** ship a usable SCTP stack. `SCTP_HAVE_FSTACK=ON`
is off until the conformance suite is green. Fallback: usrsctp + DPDK.

```bash
./scripts/run-native-tests.sh
./build/sctp-fstack --socket data/sctp-fstack.sock --ring-dir data
```

LOOPBACK Java tests do not require DPDK. Production DPDK needs hugepages + `vfio-pci`.

## GraalVM + SS7

- C ABI is packed POD (`sctp_fs_frame_t` = 48 bytes). No FreeBSD internals.
- `sctp_fs_send(..., flags)` borrows the payload pointer (FFM `Linker.Option.critical`).
- M3UA (PPID 3) must be a **complete** SCTP user message. DATA (stream ≠ 0) is ordered.
  Stream 0 ASPSM/MGMT/RKM MAY be unordered (jSS7 ASPUP).
- Java hot path wraps Netty `ByteBuf` as `MemorySegment` — no `getData()` heap copy.
