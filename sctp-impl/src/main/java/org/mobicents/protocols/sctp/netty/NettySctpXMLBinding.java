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

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.dataformat.xml.XmlMapper;
import com.fasterxml.jackson.dataformat.xml.ser.ToXmlGenerator;

/**
 * XML Binding for Netty SCTP using Jackson XML instead of XStream.
 * 
 * @author <a href="mailto:amit.bhayani@telestax.com">Amit Bhayani</a>
 * 
 */
public class NettySctpXMLBinding {

    private static final XmlMapper xmlMapper;

    static {
        xmlMapper = new XmlMapper();
        
        // Configure the mapper
        xmlMapper.configure(ToXmlGenerator.Feature.WRITE_XML_DECLARATION, true);
        xmlMapper.enable(SerializationFeature.INDENT_OUTPUT);
        xmlMapper.disable(SerializationFeature.FAIL_ON_EMPTY_BEANS);
        
        // Register modules for parameter names support
        xmlMapper.findAndRegisterModules();
    }

    public static XmlMapper getXmlMapper() {
        return xmlMapper;
    }

    public static String toXML(Object obj) throws Exception {
        return xmlMapper.writeValueAsString(obj);
    }

    public static Object fromXML(String xml) throws Exception {
        // Use raw ObjectMapper for reading maps since XmlMapper needs type info
        ObjectMapper mapper = new XmlMapper();
        return mapper.readValue(xml, Object.class);
    }
    
    public static <T> T fromXML(String xml, Class<T> valueType) throws Exception {
        return xmlMapper.readValue(xml, valueType);
    }
}
