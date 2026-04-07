/*
 * JBoss, Home of Professional Open Source
 * Copyright 2011, Red Hat, Inc. and/or its affiliates, and individual
 * contributors as indicated by the @authors tag. All rights reserved.
 * See the copyright.txt in the distribution for a full listing
 * of individual contributors.
 * 
 * This copyrighted material is made available to anyone wishing to use,
 * modify, copy, or redistribute it subject to the terms and conditions
 * of the GNU General Public License, v. 2.0.
 * 
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of 
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU 
 * General Public License for more details.
 * 
 * You should have received a copy of the GNU General Public License,
 * v. 2.0 along with this distribution; if not, write to the Free 
 * Software Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston,
 * MA 02110-1301, USA.
 */
package org.mobicents.protocols.sctp;

import com.thoughtworks.xstream.XStream;
import com.thoughtworks.xstream.io.xml.DomDriver;

/**
 * @author amit bhayani
 * 
 */
public class SctpXMLBinding {

    private static final XStream xstream;

    static {
        xstream = new XStream(new DomDriver());
        
        // Configure aliases
        xstream.alias("server", ServerImpl.class);
        xstream.alias("association", AssociationImpl.class);
        xstream.alias("associationMap", AssociationMap.class);
        
        // Process annotations
        xstream.processAnnotations(AssociationImpl.class);
        xstream.processAnnotations(ServerImpl.class);
        xstream.processAnnotations(AssociationMap.class);
        
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
