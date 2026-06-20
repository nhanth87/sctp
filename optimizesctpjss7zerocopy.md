# SCTP / jSS7 Zero-Copy Pipeline

End-to-end design notes for the RestComm SIGTRAN stack: **sctp 2.0.14** (Netty + Lksctp) and **jSS7 9.2.12** (M3UA → SCCP → TCAP → MAP).

Zero-copy is **always enabled** — there are no runtime feature flags for the ByteBuf path.

---

## Pipeline Overview

```
NIC → Kernel (Lksctp) → Netty Direct ByteBuf
                              │
                    SctpMessageCompletionHandler   (SCTP fragmentation reassembly)
                              │
                    PayloadDataPool + retain()       (sctp-impl)
                              │
                    M3UA readSlice() parameters      (m3ua-impl)
                              │
                    ProtocolDataImpl.dataBuf         (SCCP PDU slice)
                              │
                    SccpByteBufDecodeReader          (sccp-impl)
                              │
                    CompositeByteBuf XUDT reassembly (sccp-impl)
                              │
                    MapFlatAsnDecoder (inbound MAP)  (map-impl)
                              │
                    NettyAsnOutputStream (outbound)  (asn-api / tcap-impl)
```

---

## Layer Status

| Layer | Inbound | Outbound | Notes |
|-------|---------|----------|-------|
| **SCTP (Netty)** | ByteBuf retained → `PayloadData` | Direct buffer from channel allocator | `NettySctpChannelInboundHandlerAdapter` |
| **SCTP (NIO)** | Copy kernel → pooled direct buffer | Same | One copy from `ByteBuffer`; unavoidable without JNI zero-copy |
| **M3UA** | `readSlice().retain()` per parameter | `encode(ByteBuf)` | SCTP bundling: `while (byteBuf.isReadable())` loop |
| **SCCP** | `receiveM3uaProtocolData(ByteBuf)` | `getData()` copy-on-demand for OAM | XUDT uses `CompositeByteBuf` |
| **TCAP** | Legacy BER parse (heap) | `TcapOutboundEncoder` → `NettyAsnOutputStream` | 128-byte header reserve |
| **MAP** | `MapFlatAsnDecoder` flat index | Via TCAP encoder | `-Djss7.asn.flatIndexEnabled=true` (default) |

---

## Key Classes

### SCTP (`sctp-impl`)

| Class | Role |
|-------|------|
| `NettySctpChannelInboundHandlerAdapter` | `byteBuf.retain()` → `PayloadDataPool.acquire()` |
| `PayloadData` | Holds `ByteBuf`; deprecated `byte[]` ctor kept for compat |
| `SctpMessageCompletionHandler` | Netty pipeline: SCTP fragment → complete message |

### M3UA (`m3ua-impl`)

| Class | Role |
|-------|------|
| `AspFactoryImpl.processPayload()` | Bundled SCTP parse loop; ERROR log on truncated payload |
| `MessageFactoryImpl.createMessage(ByteBuf)` | Parses M3UA header; delegates zero-copy decode |
| `M3UAMessageImpl.decode()` | Always `readSlice(len-4).retain()` |
| `ProtocolDataImpl` | `dataBuf` slice after 12-byte routing header; `getData()` copies for OAM/XML |

### SCCP (`sccp-impl`)

| Class | Role |
|-------|------|
| `SccpStackImpl.receiveM3uaProtocolData()` | Retained `ByteBuf` → `Mtp3TransferPrimitive.valueOf()` |
| `MessageFactoryImpl.createMessage(..., ByteBuf)` | Zero-copy UDT/XUDT decode |
| `SccpByteBufDecodeReader` | `readDataSlice()` → retained slice |
| `SccpSegmentableMessageImpl` | `CompositeByteBuf` for multi-segment XUDT |

### ASN / TCAP

| Class | Role |
|-------|------|
| `NettyAsnOutputStream` | BER encode into pooled direct `ByteBuf` |
| `AsnStreamPool.borrowNettyOutput()` | Thread-local buffer + 128-byte header room |
| `TcapOutboundEncoder` | Always Netty path; `EncodedTcapPayload.release()` in provider |

---

## Reference Counting Rules

Every retained `ByteBuf` must be released exactly once by its owner:

1. **SCTP inbound** — `AspFactoryImpl` releases payload `ByteBuf` in `finally` after M3UA parse.
2. **M3UA parameters** — `M3UAMessageImpl.releaseParameters()` → `ParameterImpl.releaseResources()`.
3. **Protocol Data** — `ProtocolDataImpl.releaseResources()` releases `dataBuf`.
4. **TCAP outbound** — `TCAPProviderImpl.send(EncodedTcapPayload)` releases in `finally`.
5. **SCCP reassembly** — `CompositeByteBuf` owns segment refs; released when message is delivered or dropped.

**Staging leak check:** `-Dio.netty.leakDetectionLevel=simple`

---

## Error Logging

Malformed or truncated data is logged at **ERROR** (not silently dropped):

| Location | Trigger |
|----------|---------|
| `MessageFactoryImpl` | M3UA message length exceeds available bytes |
| `M3UAMessageImpl` | Parameter length / padding invalid |
| `AspFactoryImpl` | Bytes remain after failed SCTP/TCP parse |
| `SccpStackImpl` | Null or empty `receiveM3uaProtocolData` buffer |
| `SccpSegmentableMessageImpl` | Reassembly segment missing payload |
| `DialogImpl` | TCAP send failure |

---

## Remaining Copies (by design)

| Path | Why |
|------|-----|
| `ProtocolDataImpl.getData()` | OAM, XML persistence, legacy APIs |
| `Mtp3TransferPrimitive.getData()` | Same |
| `TCAPProviderImpl.send(EncodedTcapPayload)` | Copies to SCCP `byte[]` until SCCP outbound accepts `ByteBuf` |
| NIO SCTP `doReadSctp()` | Kernel `ByteBuffer` → direct `ByteBuf` |
| TCAP inbound BER | Not yet migrated to flat index |

---

## Test Coverage

| Module | Test class | Scenarios |
|--------|------------|-----------|
| m3ua-impl | `M3uaInboundZeroCopyTest` | SCTP bundling, truncation, ref-count release, TCP composite |
| m3ua-impl | `ProtocolDataByteBufTest` | Slice decode, malformed header, encode round-trip |
| m3ua-impl | `AspFactoryPayloadTest` | SCTP/TCP receive, bundled messages |
| sccp-impl | `SccpByteBufReassemblyTest` | UDT delivery, XUDT 3-segment reassembly |
| sccp-impl | `SccpByteBufDecodeReaderTest` | Slice read, mark/reset, insufficient data |
| mtp-api | `Mtp3TransferPrimitiveZeroCopyTest` | `valueOf(ByteBuf)`, heap compat |
| asn-api | `NettyAsnOutputStreamTest` | TLV encode, header reserve, pool reuse |
| tcap-impl | `TcapOutboundEncoderTest` | Netty encode path, release |

Run:

```bash
cd jSS7
mvn test -pl m3ua/impl,sccp/sccp-impl,asn/asn-api,tcap/tcap-impl,mtp/mtp-api \
  -Dtest="M3uaInboundZeroCopyTest,ProtocolDataByteBufTest,AspFactoryPayloadTest,\
SccpByteBufReassemblyTest,SccpByteBufDecodeReaderTest,NettyAsnOutputStreamTest,\
TcapOutboundEncoderTest,Mtp3TransferPrimitiveZeroCopyTest"
```

---

## JVM Options (production)

```text
-Djss7.asn.flatIndexEnabled=true
-Dio.netty.leakDetectionLevel=simple    # staging only; use disabled in prod
-XX:MaxDirectMemorySize=512m            # tune for TPS / message size
```

Zero-copy M3UA/SCCP/TCAP encode paths require **no flags** — they are built in.
