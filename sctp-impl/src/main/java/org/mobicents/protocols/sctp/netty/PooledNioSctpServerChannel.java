package org.mobicents.protocols.sctp.netty;

import com.sun.nio.sctp.SctpChannel;
import io.netty.channel.sctp.nio.NioSctpServerChannel;

import java.util.List;

/**
 * Custom NioSctpServerChannel that creates PooledNioSctpChannel children.
 */
public class PooledNioSctpServerChannel extends NioSctpServerChannel {

    public PooledNioSctpServerChannel() {
        super();
    }

    @Override
    protected int doReadMessages(List<Object> buf) throws Exception {
        SctpChannel ch = javaChannel().accept();
        if (ch == null) {
            return 0;
        }
        buf.add(new PooledNioSctpChannel(this, ch));
        return 1;
    }
}
