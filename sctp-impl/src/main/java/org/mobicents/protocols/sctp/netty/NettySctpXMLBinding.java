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

import com.thoughtworks.xstream.XStream;
import com.thoughtworks.xstream.io.xml.DomDriver;

/**
 * @author <a href="mailto:amit.bhayani@telestax.com">Amit Bhayani</a>
 * 
 */
public class NettySctpXMLBinding {

    private static final XStream xstream;

    static {
        xstream = new XStream(new DomDriver());
        
        // Configure aliases
        xstream.alias("server", NettyServerImpl.class);
        xstream.alias("association", NettyAssociationImpl.class);
        xstream.alias("associationMap", NettyAssociationMap.class);
        
        // Process annotations
        xstream.processAnnotations(NettyAssociationImpl.class);
        xstream.processAnnotations(NettyServerImpl.class);
        xstream.processAnnotations(NettyAssociationMap.class);
        
        // Set class attribute for type discrimination
        xstream.aliasSystemAttribute("type", "class");
    }

    public static XStream getXStream() {
        return xstream;
    }

    public static String toXML(Object obj) {
        return xstream.toXML(obj);
    }

    public static Object fromXML(String xml) {
        return xstream.fromXML(xml);
    }
}
