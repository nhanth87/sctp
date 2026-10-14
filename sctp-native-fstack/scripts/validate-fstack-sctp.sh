#!/usr/bin/env bash
# Refuse stock F-Stack builds that contain only the SCTP NAT helper.
set -euo pipefail

root="${1:?usage: validate-fstack-sctp.sh <f-stack-root> [libfstack.a]}"
archive="${2:-}"
makefile="${FSTACK_MAKEFILE:-$root/lib/Makefile}"

[[ -f "$root/freebsd/netinet/sctp_usrreq.c" ]] || {
  echo "error: full pinned F-Stack freebsd/ source is missing" >&2
  exit 1
}
[[ -f "$makefile" ]] || {
  echo "error: F-Stack lib/Makefile is missing" >&2
  exit 1
}

missing=()
for source in \
  sctp_asconf.c sctp_auth.c sctp_bsd_addr.c sctp_cc_functions.c sctp_crc32.c \
  sctp_indata.c sctp_input.c sctp_kdtrace.c sctp_output.c sctp_pcb.c \
  sctp_peeloff.c sctp_ss_functions.c sctp_sysctl.c sctp_timer.c \
  sctp_usrreq.c sctputil.c sctp6_usrreq.c; do
  if ! grep -Eq "^[[:space:]]*${source//./\\.}([[:space:]\\\\]|$)" "$makefile"; then
    missing+=("$source")
  fi
done

if ((${#missing[@]})); then
  printf 'error: pinned F-Stack v1.25 does not compile the SCTP protocol stack; missing lib/Makefile entries:' >&2
  printf ' %s' "${missing[@]}" >&2
  printf '\n' >&2
  echo "alias_sctp.c is a NAT helper and is not an SCTP transport implementation." >&2
  echo "Apply and validate a real FreeBSD SCTP port (or use usrsctp+DPDK) before enabling production DPDK." >&2
  exit 1
fi

grep -Eq '^[[:space:]]*(HOST_CFLAGS|CFLAGS)\+=[[:space:]]+-DSCTP' "$makefile" || {
  echo "error: F-Stack SCTP sources are listed but SCTP is not enabled" >&2
  exit 1
}
[[ -f "$root/lib/opt/opt_sctp.h" ]] || {
  echo "error: lib/opt/opt_sctp.h is missing" >&2
  exit 1
}

echo "validated: F-Stack build includes the complete IPv4/IPv6 SCTP source set"

if [[ -n "$archive" ]]; then
  [[ -f "$archive" ]] || {
    echo "error: libfstack archive missing: $archive" >&2
    exit 1
  }
  members="$(ar t "$archive")"
  if grep -qx 'libfstack.ro' <<<"$members"; then
    # F-Stack incrementally links kernel objects into libfstack.ro before
    # archiving. The implementation symbols below are the durable proof.
    :
  else
    for object in sctp_input.o sctp_output.o sctp_pcb.o sctp_timer.o sctp_usrreq.o sctputil.o sctp6_usrreq.o; do
      grep -qx "$object" <<<"$members" || {
        echo "error: archive is alias-only/incomplete; missing object $object" >&2
        exit 1
      }
    done
  fi
  symbols="$(nm --defined-only "$archive")"
  for symbol in sctp_init sctp_input sctp_usrreqs sctp6_usrreqs \
      timingsafe_bcmp kproc_create; do
    grep -Eq "[[:space:]]${symbol}$" <<<"$symbols" || {
      echo "error: archive missing SCTP implementation symbol $symbol" >&2
      exit 1
    }
  done
  echo "validated: archive contains SCTP objects and endpoint symbols"
fi
