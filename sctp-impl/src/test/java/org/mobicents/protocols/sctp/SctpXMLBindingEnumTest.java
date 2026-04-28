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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

import java.io.StringReader;
import java.io.StringWriter;

import org.junit.Test;
import org.mobicents.protocols.api.AssociationType;
import org.mobicents.protocols.api.IpChannelType;
import org.mobicents.protocols.sctp.netty.NettyAssociationImpl;

/**
 * Unit tests for SCTP Jackson XML binding, specifically for enum deserialization.
 * Ensures backward compatibility with javolution XML format.
 * 
 * @author Matrix Agent
 */
public class SctpXMLBindingEnumTest {

    /**
     * Test IpChannelType.SCTP serialization and deserialization
     */
    @Test
    public void testIpChannelTypeSCTP() throws Exception {
        String xml = "<association ipChannelType=\"SCTP\"/>";
        NettyAssociationImpl assoc = new NettyAssociationImpl();
        assoc.setIpChannelType(IpChannelType.SCTP);
        
        String serialized = SctpXMLBinding.toXML(assoc);
        assertNotNull(serialized);
        assertTrue("Serialized XML should contain SCTP", serialized.contains("SCTP"));
    }

    /**
     * Test IpChannelType.TCP serialization and deserialization
     */
    @Test
    public void testIpChannelTypeTCP() throws Exception {
        String xml = "<association ipChannelType=\"TCP\"/>";
        NettyAssociationImpl assoc = new NettyAssociationImpl();
        assoc.setIpChannelType(IpChannelType.TCP);
        
        String serialized = SctpXMLBinding.toXML(assoc);
        assertNotNull(serialized);
        assertTrue("Serialized XML should contain TCP", serialized.contains("TCP"));
    }

    /**
     * Test AssociationType.SERVER serialization
     */
    @Test
    public void testAssociationTypeSERVER() throws Exception {
        String xml = "<association type=\"SERVER\" ipChannelType=\"SCTP\"/>";
        NettyAssociationImpl assoc = new NettyAssociationImpl();
        assoc.setIpChannelType(IpChannelType.SCTP);
        assoc.setType(AssociationType.SERVER);
        
        String serialized = SctpXMLBinding.toXML(assoc);
        assertNotNull(serialized);
        assertTrue("Serialized XML should contain SERVER", serialized.contains("SERVER"));
    }

    /**
     * Test AssociationType.CLIENT serialization
     */
    @Test
    public void testAssociationTypeCLIENT() throws Exception {
        String xml = "<association type=\"CLIENT\" ipChannelType=\"SCTP\"/>";
        NettyAssociationImpl assoc = new NettyAssociationImpl();
        assoc.setIpChannelType(IpChannelType.SCTP);
        assoc.setType(AssociationType.CLIENT);
        
        String serialized = SctpXMLBinding.toXML(assoc);
        assertNotNull(serialized);
        assertTrue("Serialized XML should contain CLIENT", serialized.contains("CLIENT"));
    }

    /**
     * Test full NettyAssociationImpl round-trip
     */
    @Test
    public void testNettyAssociationImplRoundTrip() throws Exception {
        // Create original association
        NettyAssociationImpl original = new NettyAssociationImpl();
        original.setName("testAssoc");
        original.setHostAddress("127.0.0.1");
        original.setHostPort(2900);
        original.setPeerAddress("127.0.0.1");
        original.setPeerPort(2901);
        original.setServerName("testServer");
        original.setIpChannelType(IpChannelType.SCTP);
        original.setType(AssociationType.SERVER);
        
        // Serialize
        String serialized = SctpXMLBinding.toXML(original);
        assertNotNull(serialized);
        System.out.println("Serialized: " + serialized);
    }

    /**
     * Test case-insensitive enum matching
     */
    @Test
    public void testCaseInsensitiveEnumMatching() throws Exception {
        // Test lowercase
        String lowerXml = "<association ipChannelType=\"sctp\" type=\"server\"/>";
        NettyAssociationImpl assoc = new NettyAssociationImpl();
        assoc.setIpChannelType(IpChannelType.SCTP);
        assoc.setType(AssociationType.SERVER);
        
        String serialized = SctpXMLBinding.toXML(assoc);
        assertNotNull(serialized);
    }

    /**
     * Test IpChannelType.getInstance() method
     */
    @Test
    public void testIpChannelTypeGetInstance() {
        assertEquals(IpChannelType.SCTP, IpChannelType.getInstance("SCTP"));
        assertEquals(IpChannelType.TCP, IpChannelType.getInstance("TCP"));
        assertEquals(IpChannelType.SCTP, IpChannelType.getInstance("sctp"));
        assertEquals(IpChannelType.TCP, IpChannelType.getInstance("tcp"));
        assertEquals(null, IpChannelType.getInstance("INVALID"));
    }

    /**
     * Test AssociationType.getAssociationType() method
     */
    @Test
    public void testAssociationTypeGetInstance() {
        assertEquals(AssociationType.SERVER, AssociationType.getAssociationType("SERVER"));
        assertEquals(AssociationType.CLIENT, AssociationType.getAssociationType("CLIENT"));
        assertEquals(AssociationType.ANONYMOUS_SERVER, AssociationType.getAssociationType("ANONYMOUS_SERVER"));
        assertEquals(AssociationType.SERVER, AssociationType.getAssociationType("server"));
        assertEquals(null, AssociationType.getAssociationType("INVALID"));
    }
}
