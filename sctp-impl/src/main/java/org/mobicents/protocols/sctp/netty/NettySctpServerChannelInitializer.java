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

package org.mobicents.protocols.sctp.netty;

import io.netty.buffer.PooledByteBufAllocator;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.FixedRecvByteBufAllocator;
import io.netty.channel.sctp.SctpChannel;
import io.netty.handler.codec.sctp.SctpMessageCompletionHandler;
import io.netty.handler.flush.FlushConsolidationHandler;

/**
 * @author <a href="mailto:amit.bhayani@telestax.com">Amit Bhayani</a>
 * 
 */
public class NettySctpServerChannelInitializer extends ChannelInitializer<SctpChannel> {
    private final NettyServerImpl nettyServerImpl;
    private final NettySctpManagementImpl sctpManagementImpl;

    protected NettySctpServerChannelInitializer(NettyServerImpl nettyServerImpl, NettySctpManagementImpl sctpManagementImpl) {
        super();
        this.nettyServerImpl = nettyServerImpl;
        this.sctpManagementImpl = sctpManagementImpl;
    }

    @Override
    protected void initChannel(SctpChannel ch) throws Exception {
        // Use pooled allocator explicitly for consistent performance (Netty 4.2 default has ~80% higher CPU overhead)
        ch.config().setAllocator(PooledByteBufAllocator.DEFAULT);
        
        // Use fixed buffer size for consistent read performance
        ch.config().setRecvByteBufAllocator(new FixedRecvByteBufAllocator(8192));
        
        // Tuned write buffers for high-throughput (reduced from 64MB/32MB to prevent memory bloat)
        ch.config().setWriteBufferHighWaterMark(16 * 1024 * 1024);  // 16 MB
        ch.config().setWriteBufferLowWaterMark(8 * 1024 * 1024);   // 8 MB
        
        ch.pipeline().addLast(new SctpMessageCompletionHandler(), new FlushConsolidationHandler(),
                new NettySctpServerHandler(this.nettyServerImpl, this.sctpManagementImpl));
    }
}