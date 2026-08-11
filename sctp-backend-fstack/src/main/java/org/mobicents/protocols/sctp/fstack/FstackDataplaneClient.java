package org.mobicents.protocols.sctp.fstack;

import org.mobicents.protocols.sctp.spi.SctpRecord;

interface FstackDataplaneClient {
    void start();
    void stop();
    void open(FstackAssociationImpl association);
    void close(FstackAssociationImpl association);
    void send(SctpRecord record);
}
