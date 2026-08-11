#!/usr/bin/env bash
# Configure, build, and run C++ sctp-fs tests. No DPDK NIC required.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
BUILD="${ROOT}/build"
cmake -S "${ROOT}" -B "${BUILD}" -DCMAKE_BUILD_TYPE=RelWithDebInfo
cmake --build "${BUILD}" --target sctp_fstack sctp-fstack sctp_fs_tests
ctest --test-dir "${BUILD}" --output-on-failure
echo "native sctp-fs artifacts:"
ls -l "${BUILD}/sctp-fstack" "${BUILD}/libsctp_fstack.so" "${BUILD}/sctp_fs_tests"
