#!/usr/bin/env bash
# Configure, build, and run C++ sctp-fs tests. No DPDK NIC required.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
BUILD="${ROOT}/build"
if cmake --system-information >/dev/null 2>&1; then
  cmake -S "${ROOT}" -B "${BUILD}" -DCMAKE_BUILD_TYPE=RelWithDebInfo
  cmake --build "${BUILD}" --target sctp_fstack sctp-fstack sctp_fs_tests
  ctest --test-dir "${BUILD}" --output-on-failure
else
  echo "cmake CMAKE_ROOT missing — falling back to Makefile + lksctp"
  make -C "${ROOT}" CXX="${CXX:-/usr/bin/g++}" all
  "${CXX:-/usr/bin/g++}" -std=c++20 -O2 -DSCTP_HAVE_FSTACK -I "${ROOT}/include" \
    "${ROOT}/tests/test_sctp_fs.cpp" "${BUILD}/libsctp_fstack.so" -lsctp \
    -Wl,-rpath,"${BUILD}" -o "${BUILD}/sctp_fs_tests"
  "${BUILD}/sctp_fs_tests"
fi
echo "native sctp-fs artifacts:"
ls -l "${BUILD}/sctp-fstack" "${BUILD}/libsctp_fstack.so" "${BUILD}/sctp_fs_tests"
