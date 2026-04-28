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

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.dataformat.xml.XmlMapper;
import com.fasterxml.jackson.dataformat.xml.ser.ToXmlGenerator;

import org.mobicents.protocols.api.AssociationType;
import org.mobicents.protocols.api.IpChannelType;

import java.io.IOException;

/**
 * XML Binding helper for SCTP using Jackson XML (replaces XStream).
 * Includes custom deserializers for enums to ensure proper deserialization
 * when loading XML config files.
 * 
 * @author amit bhayani
 * 
 */
public class SctpXMLBinding {

    private static final XmlMapper xmlMapper;

    static {
        xmlMapper = new XmlMapper();
        
        // Configure the mapper
        // INDENT_OUTPUT disabled to avoid Stax2WriterAdapter.writeRaw() UnsupportedOperationException
        // with Jackson-dataformat-xml 2.15.2 + StAX on WildFly 10
        // xmlMapper.enable(SerializationFeature.INDENT_OUTPUT);
        xmlMapper.enable(ToXmlGenerator.Feature.WRITE_XML_DECLARATION);
        xmlMapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        xmlMapper.configure(SerializationFeature.FAIL_ON_EMPTY_BEANS, false);

        // Register module with custom enum deserializers
        SimpleModule sctpEnumModule = new SimpleModule("sctp-enum-module");
        sctpEnumModule.addDeserializer(IpChannelType.class, new IpChannelTypeDeserializer());
        sctpEnumModule.addDeserializer(AssociationType.class, new AssociationTypeDeserializer());
        xmlMapper.registerModule(sctpEnumModule);
    }

    public static XmlMapper getXmlMapper() {
        return xmlMapper;
    }

    public static String toXML(Object obj) {
        try {
            return xmlMapper.writeValueAsString(obj);
        } catch (Exception e) {
            throw new RuntimeException("Failed to serialize object to XML", e);
        }
    }

    public static Object fromXML(String xml) {
        try {
            return xmlMapper.readValue(xml, Object.class);
        } catch (Exception e) {
            throw new RuntimeException("Failed to deserialize XML", e);
        }
    }

    /**
     * Custom deserializer for IpChannelType enum.
     * Uses the existing getInstance(String) method to ensure backward compatibility
     * with javolution XML format.
     */
    public static class IpChannelTypeDeserializer extends JsonDeserializer<IpChannelType> {
        @Override
        public IpChannelType deserialize(JsonParser p, DeserializationContext ctxt) throws IOException {
            String value = p.getValueAsString();
            IpChannelType result = IpChannelType.getInstance(value);
            if (result == null) {
                // Fallback: try case-insensitive match
                if ("SCTP".equalsIgnoreCase(value)) {
                    return IpChannelType.SCTP;
                } else if ("TCP".equalsIgnoreCase(value)) {
                    return IpChannelType.TCP;
                }
                throw new IOException("Unknown IpChannelType value: " + value);
            }
            return result;
        }
    }

    /**
     * Custom deserializer for AssociationType enum.
     * Uses the existing getAssociationType(String) method to ensure backward compatibility
     * with javolution XML format.
     */
    public static class AssociationTypeDeserializer extends JsonDeserializer<AssociationType> {
        @Override
        public AssociationType deserialize(JsonParser p, DeserializationContext ctxt) throws IOException {
            String value = p.getValueAsString();
            AssociationType result = AssociationType.getAssociationType(value);
            if (result == null) {
                // Fallback: try case-insensitive match
                if ("CLIENT".equalsIgnoreCase(value)) {
                    return AssociationType.CLIENT;
                } else if ("SERVER".equalsIgnoreCase(value)) {
                    return AssociationType.SERVER;
                } else if ("ANONYMOUS_SERVER".equalsIgnoreCase(value)) {
                    return AssociationType.ANONYMOUS_SERVER;
                }
                throw new IOException("Unknown AssociationType value: " + value);
            }
            return result;
        }
    }
}
