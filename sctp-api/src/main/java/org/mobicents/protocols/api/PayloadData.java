/*
 * TeleStax, Open Source Cloud Communications
 * Copyright 2011-2014, Telestax Inc and individual contributors
 * by the @authors tag.
 *
 * This program is free software: you can redistribute it and/or modify
 * under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation; either version 3 of
 * the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>
 *
 */

package org.mobicents.protocols.api;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.util.ReferenceCountUtil;

import net.openhft.chronicle.wire.SelfDescribingMarshallable;

import java.io.Serializable;
import net.openhft.chronicle.wire.WireIn;
import net.openhft.chronicle.wire.WireOut;

/**
 * The actual pay load data received or to be sent from/to underlying socket
 *
 * @author amit bhayani
 *
 */
public class PayloadData extends SelfDescribingMarshallable implements Serializable {
    private static final long serialVersionUID = 81187L;

    private int dataLength;
    private ByteBuf byteBuf;
    private boolean complete;
    private boolean unordered;
    private int payloadProtocolId;
    private int streamNumber;
    private int retryCount = 0;

    /**
     * @param dataLength
     *            Length of byte[] data
     * @param byteBuf
     *            the payload data
     * @param complete
     *            if this data represents complete protocol data
     * @param unordered
     *            set to true if we don't care for oder
     * @param payloadProtocolId
     *            protocol ID of the data carried
     * @param streamNumber
     *            the SCTP stream number
     */
    public PayloadData(int dataLength, ByteBuf byteBuf, boolean complete, boolean unordered, int payloadProtocolId, int streamNumber) {
        super();
        this.dataLength = dataLength;
        this.byteBuf = byteBuf;
        this.complete = complete;
        this.unordered = unordered;
        this.payloadProtocolId = payloadProtocolId;
        this.streamNumber = streamNumber;
    }

    /**
     * @param dataLength
     *            Length of byte[] data
     * @param data
     *            the payload data
     * @param complete
     *            if this data represents complete protocol data
     * @param unordered
     *            set to true if we don't care for oder
     * @param payloadProtocolId
     *            protocol ID of the data carried
     * @param streamNumber
     *            the SCTP stream number
     */
    public PayloadData(int dataLength, byte[] data, boolean complete, boolean unordered, int payloadProtocolId, int streamNumber) {
        super();
        this.dataLength = dataLength;
        this.byteBuf = Unpooled.wrappedBuffer(data);
        this.complete = complete;
        this.unordered = unordered;
        this.payloadProtocolId = payloadProtocolId;
        this.streamNumber = streamNumber;
    }

    /**
     * @return the dataLength
     */
    public int getDataLength() {
        return dataLength;
    }

    /**
     * @return the byteBuf
     */
    public ByteBuf getByteBuf() {
        return byteBuf;
    }

    /**
     * @return the data
     */
    public byte[] getData() {
        byte[] array = new byte[byteBuf.readableBytes()];
        byteBuf.getBytes(0, array);
        ReferenceCountUtil.release(byteBuf);
        return array;
    }

    public void releaseBuffer() {
        ReferenceCountUtil.release(byteBuf);
    }

    /**
     * @return the complete
     */
    public boolean isComplete() {
        return complete;
    }

    /**
     * @return the unordered
     */
    public boolean isUnordered() {
        return unordered;
    }

    /**
     * @return the payloadProtocolId
     */
    public int getPayloadProtocolId() {
        return payloadProtocolId;
    }

    /**
     * <p>
     * This is SCTP Stream sequence identifier.
     * </p>
     * <p>
     * While sending PayloadData to SCTP Association, this value should be set
     * by SCTP user. If value greater than or equal to maxOutboundStreams or
     * lesser than 0 is used, packet will be dropped and error message will be
     * logged
     * </p>
     * </p> While PayloadData is received from underlying SCTP socket, this
     * value indicates stream identifier on which data was received. Its
     * guaranteed that this value will be greater than 0 and less than
     * maxInboundStreams
     * <p>
     *
     * @return the streamNumber
     */
    public int getStreamNumber() {
        return streamNumber;
    }

    public int getRetryCount() {
        return retryCount;
    }

    public PayloadData retry() {
        this.retryCount++;
        return this;
    }

    @Override
    public void writeMarshallable(WireOut wire) {
        wire.write("dataLength").int32(dataLength);
        wire.write("data").bytes(getData());
        wire.write("complete").bool(complete);
        wire.write("unordered").bool(unordered);
        wire.write("payloadProtocolId").int32(payloadProtocolId);
        wire.write("streamNumber").int32(streamNumber);
        wire.write("retryCount").int32(retryCount);
    }


    @Override
    public void readMarshallable(WireIn wire) {
       this. dataLength = wire.read("dataLength").int32();
        this. byteBuf = Unpooled.wrappedBuffer(wire.read("data").bytes());
        this. complete = wire.read("complete").bool();
        this. unordered = wire.read("unordered").bool();
        this. payloadProtocolId = wire.read("payloadProtocolId").int32();
        this. streamNumber = wire.read("streamNumber").int32();
        this. retryCount = wire.read("retryCount").int32();

        this.retryCount = retryCount;
    }

    /*
     * (non-Javadoc)
     *
     * @see java.lang.Object#toString()
     */
    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append("PayloadData [dataLength=").append(dataLength).append(", complete=").append(complete).append(", unordered=")
                .append(unordered).append(", payloadProtocolId=").append(payloadProtocolId).append(", streamNumber=")
                .append(streamNumber).append(", data=\n").append(byteBuf.readableBytes()).append("]");

        return sb.toString();
    }

}
