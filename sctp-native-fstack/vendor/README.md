# vendor/f-stack

Pinned local checkout of [nhanth87/f-stack](https://github.com/nhanth87/f-stack) `v1.25`
(`761639943bdda33103aa98241ca6a3079f1c1b7e`), public fork of
[F-Stack/f-stack](https://github.com/F-Stack/f-stack). Use this URL for DPDK
on-wire builds (QEMU virtio or physical NIC). The GitHub tree includes
`freebsd/` + `dpdk/`; the local sparse checkout must include both.

The working tree is gitignored. Recreate with:

```bash
git clone --filter=blob:none --no-checkout --branch v1.25 --depth 1 \
  https://github.com/F-Stack/f-stack.git f-stack
cd f-stack
git sparse-checkout init --cone
git sparse-checkout set adapter app doc docs dpdk example freebsd lib mk tests tools
git checkout
```

The pinned fork extends v1.25's `lib/Makefile` with the complete IPv4/IPv6
FreeBSD SCTP protocol source set plus the small userspace compatibility shims
required by F-Stack. Run
`../scripts/validate-fstack-sctp.sh f-stack f-stack/lib/libfstack.a` from
`sctp-native-fstack/vendor`; the gate checks both source inclusion and endpoint
symbols. Never interpret `alias_sctp.c` as SCTP transport support or fall back
to lksctp in a DPDK build.
