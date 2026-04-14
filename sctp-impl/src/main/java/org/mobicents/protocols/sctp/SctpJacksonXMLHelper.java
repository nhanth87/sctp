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

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.dataformat.xml.XmlMapper;
import com.fasterxml.jackson.dataformat.xml.ser.ToXmlGenerator;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;

/**
 * Helper class for XML serialization/deserialization using Jackson XML.
 * Replaces XStream for SCTP persistence.
 * 
 * @author amit bhayani
 * 
 */
public class SctpJacksonXMLHelper {
    private static final XmlMapper xmlMapper = new XmlMapper();

    static {
        xmlMapper.enable(SerializationFeature.INDENT_OUTPUT);
        xmlMapper.enable(ToXmlGenerator.Feature.WRITE_XML_DECLARATION);
        xmlMapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        xmlMapper.configure(SerializationFeature.FAIL_ON_EMPTY_BEANS, false);
    }

    public static void toXML(Object obj, Writer writer) throws IOException {
        xmlMapper.writeValue(writer, obj);
    }

    public static <T> T fromXML(Reader reader, Class<T> clazz) throws IOException {
        return xmlMapper.readValue(reader, clazz);
    }

    public static <T> T fromXML(String xml, Class<T> clazz) throws IOException {
        return xmlMapper.readValue(xml, clazz);
    }
    
    public static String toXMLString(Object obj) throws IOException {
        return xmlMapper.writeValueAsString(obj);
    }
}
