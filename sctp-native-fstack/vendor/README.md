# vendor/f-stack

Pinned local checkout of [F-Stack](https://github.com/F-Stack/f-stack) `v1.25`
(`761639943bdda33103aa98241ca6a3079f1c1b7e`).

The working tree is gitignored. Recreate with:

```bash
git clone --filter=blob:none --no-checkout --branch v1.25 --depth 1 \
  https://github.com/F-Stack/f-stack.git f-stack
cd f-stack
git sparse-checkout init --cone
git sparse-checkout set adapter app doc docs dpdk example lib mk tests tools
git checkout
```

`freebsd/` is excluded on purpose (BSD-4-Clause advertising clause + we are
not shipping the FreeBSD network stack). `lib/Makefile` still has
`S=${TOPDIR}/freebsd` — that path must stay empty.
