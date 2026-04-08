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

import org.mobicents.commons.HexTools;

/**
 * The actual pay load data received or to be sent from/to underlying socket.
 * Optimized for object pooling with Javolution.
 *
 * @author <a href="mailto:nhanth87@gmail.com">nhanth87</a>
 * @author jenny (added pooling support)
 *
 */
public class PayloadData {

    private int dataLength;
    private ByteBuf byteBuf;
    private boolean complete;
    private boolean unordered;
    private int payloadProtocolId;
    private int streamNumber;
    private int retryCount = 0;
    
    // Pooling support
    private boolean pooled = false;
    private boolean available = true;

    /**
     * Default constructor for pooling.
     * Use PayloadDataPool.acquire() instead of direct instantiation.
     */
    public PayloadData() {
        // Empty constructor for pooling
    }

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
        reset(dataLength, byteBuf, complete, unordered, payloadProtocolId, streamNumber);
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
     * @deprecated Use ByteBuf constructor for zero-copy. This will be removed in future versions.
     */
    @Deprecated
    public PayloadData(int dataLength, byte[] data, boolean complete, boolean unordered, int payloadProtocolId, int streamNumber) {
        this.dataLength = dataLength;
        this.byteBuf = Unpooled.wrappedBuffer(data);
        this.complete = complete;
        this.unordered = unordered;
        this.payloadProtocolId = payloadProtocolId;
        this.streamNumber = streamNumber;
    }
    
    /**
     * Reset this object for reuse from pool.
     * This method should only be called by PayloadDataPool.
     */
    public void reset(int dataLength, ByteBuf byteBuf, boolean complete, boolean unordered, int payloadProtocolId, int streamNumber) {
        this.dataLength = dataLength;
        this.byteBuf = byteBuf;
        this.complete = complete;
        this.unordered = unordered;
        this.payloadProtocolId = payloadProtocolId;
        this.streamNumber = streamNumber;
        this.retryCount = 0;
        this.available = false;
    }
    
    /**
     * Clear this object and prepare for return to pool.
     * Releases the ByteBuf reference.
     */
    public void clear() {
        if (this.byteBuf != null) {
            ReferenceCountUtil.release(this.byteBuf);
            this.byteBuf = null;
        }
        this.dataLength = 0;
        this.complete = false;
        this.unordered = false;
        this.payloadProtocolId = 0;
        this.streamNumber = 0;
        this.retryCount = 0;
        this.available = true;
    }
    
    /**
     * Check if this object is available in pool.
     */
    public boolean isAvailable() {
        return this.available;
    }
    
    /**
     * Mark as pooled object.
     */
    public void setPooled(boolean pooled) {
        this.pooled = pooled;
    }
    
    /**
     * Check if this is a pooled object.
     */
    public boolean isPooled() {
        return this.pooled;
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
     * @deprecated Use getByteBuf() for zero-copy access.
     */
    @Deprecated
    public byte[] getData() {
        if (byteBuf == null) {
            return new byte[0];
        }
        byte[] array = new byte[byteBuf.readableBytes()];
        byteBuf.getBytes(byteBuf.readerIndex(), array);
        return array;
    }

    /**
     * Release the buffer. Should be called when done with this PayloadData.
     * For pooled objects, use PayloadDataPool.release() instead.
     */
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

    /*
     * (non-Javadoc)
     *
     * @see java.lang.Object#toString()
     */
    @Override
    public String toString() {
        if (byteBuf == null) {
            return "PayloadData [null]";
        }
        byte[] array = new byte[Math.min(byteBuf.readableBytes(), 256)]; // Limit to 256 bytes for display
        byteBuf.getBytes(byteBuf.readerIndex(), array);

        StringBuilder sb = new StringBuilder();
        sb.append("PayloadData [dataLength=").append(dataLength).append(", complete=").append(complete).append(", unordered=")
            .append(unordered).append(", payloadProtocolId=").append(payloadProtocolId).append(", streamNumber=")
            .append(streamNumber).append(", data=\n").append(HexTools.dump(array, 0)).append("]");
        return sb.toString();
    }
}
