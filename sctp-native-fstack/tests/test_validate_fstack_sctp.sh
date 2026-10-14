#!/usr/bin/env bash
set -euo pipefail

root="${1:?usage: test_validate_fstack_sctp.sh <f-stack-root> <libfstack.a>}"
archive="${2:?usage: test_validate_fstack_sctp.sh <f-stack-root> <libfstack.a>}"
project_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
validator="$project_root/scripts/validate-fstack-sctp.sh"

"$validator" "$root" "$archive"

if FSTACK_MAKEFILE="$project_root/tests/fixtures/alias-only-Makefile" \
    "$validator" "$root" >/dev/null 2>&1; then
  echo "error: alias-only F-Stack build passed SCTP validation" >&2
  exit 1
fi

echo "validated: alias-only SCTP build is rejected"
