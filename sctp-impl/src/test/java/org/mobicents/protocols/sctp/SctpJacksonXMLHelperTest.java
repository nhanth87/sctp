package org.mobicents.protocols.sctp;

import static org.junit.Assert.*;

import java.io.StringReader;
import java.io.StringWriter;

import org.junit.Test;
import org.mobicents.protocols.api.AssociationType;
import org.mobicents.protocols.api.IpChannelType;
import org.mobicents.protocols.sctp.netty.NettyAssociationImpl;
import org.mobicents.protocols.sctp.netty.NettyServerImpl;

/**
 * Unit tests for SctpJacksonXMLHelper
 * @author Matrix Agent
 */
public class SctpJacksonXMLHelperTest {

    @Test
    public void testXmlMapperInitialization() throws Exception {
        String xml = "<association/>";
        NettyAssociationImpl assoc = SctpJacksonXMLHelper.fromXML(xml, NettyAssociationImpl.class);
        assertNotNull("Should parse", assoc);
    }
    
    @Test
    public void testAssociationDeserialization() throws Exception {
        String xml = "<association><name>testAssoc</name><hostAddress>127.0.0.1</hostAddress>"
                + "<hostPort>2900</hostPort><peerAddress>127.0.0.1</peerAddress>"
                + "<peerPort>2901</peerPort><ipChannelType>SCTP</ipChannelType><type>SERVER</type></association>";
        
        NettyAssociationImpl assoc = SctpJacksonXMLHelper.fromXML(xml, NettyAssociationImpl.class);
        
        assertNotNull("Deserialized association should not be null", assoc);
        assertEquals("testAssoc", assoc.getName());
        assertEquals("127.0.0.1", assoc.getHostAddress());
        assertEquals("127.0.0.1", assoc.getPeerAddress());
        assertEquals(IpChannelType.SCTP, assoc.getIpChannelType());
        assertEquals(AssociationType.SERVER, assoc.getAssociationType());
    }
    
    @Test
    public void testRoundTripAssociation() throws Exception {
        // Deserialize from XML
        String xml = "<association><name>roundTrip</name><hostAddress>10.0.0.1</hostAddress>"
                + "<hostPort>3000</hostPort><peerAddress>10.0.0.2</peerAddress>"
                + "<peerPort>3001</peerPort><ipChannelType>TCP</ipChannelType><type>CLIENT</type>"
                + "<serverName>myServer</serverName></association>";
        
        NettyAssociationImpl original = SctpJacksonXMLHelper.fromXML(xml, NettyAssociationImpl.class);
        assertNotNull("Original should parse", original);
        assertEquals("roundTrip", original.getName());
        assertEquals("myServer", original.getServerName());
        
        // Serialize back
        String outputXml = SctpJacksonXMLHelper.toXMLString(original);
        assertNotNull("Output XML should not be null", outputXml);
        assertTrue("Output should contain name", outputXml.contains("roundTrip"));
    }
    
    @Test
    public void testServerDeserialization() throws Exception {
        String xml = "<server><name>testServer</name><hostAddress>127.0.0.1</hostAddress>"
                + "<hostport>2905</hostport><ipChannelType>TCP</ipChannelType>"
                + "<acceptAnonymousConnections>false</acceptAnonymousConnections>"
                + "<maxConcurrentConnectionsCount>10</maxConcurrentConnectionsCount></server>";
        
        NettyServerImpl server = SctpJacksonXMLHelper.fromXML(xml, NettyServerImpl.class);
        
        assertNotNull("Deserialized server should not be null", server);
        assertEquals("testServer", server.getName());
        assertEquals("127.0.0.1", server.getHostAddress());
        assertEquals(2905, server.getHostport());
        assertEquals(IpChannelType.TCP, server.getIpChannelType());
    }
    
    @Test
    public void testRoundTripServer() throws Exception {
        String xml = "<server><name>roundTripServer</name><hostAddress>192.168.1.1</hostAddress>"
                + "<hostport>4000</hostport><ipChannelType>SCTP</ipChannelType>"
                + "<acceptAnonymousConnections>true</acceptAnonymousConnections>"
                + "<maxConcurrentConnectionsCount>5</maxConcurrentConnectionsCount></server>";
        
        NettyServerImpl original = SctpJacksonXMLHelper.fromXML(xml, NettyServerImpl.class);
        assertNotNull(original);
        assertEquals("roundTripServer", original.getName());
        
        String outputXml = SctpJacksonXMLHelper.toXMLString(original);
        assertNotNull(outputXml);
    }
    
    @Test
    public void testTcpIpChannelType() throws Exception {
        String xml = "<association><name>tcpAssoc</name><ipChannelType>TCP</ipChannelType></association>";
        NettyAssociationImpl assoc = SctpJacksonXMLHelper.fromXML(xml, NettyAssociationImpl.class);
        assertEquals(IpChannelType.TCP, assoc.getIpChannelType());
    }
    
    @Test
    public void testSctpIpChannelType() throws Exception {
        String xml = "<association><name>sctpAssoc</name><ipChannelType>SCTP</ipChannelType></association>";
        NettyAssociationImpl assoc = SctpJacksonXMLHelper.fromXML(xml, NettyAssociationImpl.class);
        assertEquals(IpChannelType.SCTP, assoc.getIpChannelType());
    }
    
    @Test
    public void testClientAssociationType() throws Exception {
        String xml = "<association><name>clientAssoc</name><type>CLIENT</type></association>";
        NettyAssociationImpl assoc = SctpJacksonXMLHelper.fromXML(xml, NettyAssociationImpl.class);
        assertEquals(AssociationType.CLIENT, assoc.getAssociationType());
    }
    
    @Test
    public void testServerAssociationType() throws Exception {
        String xml = "<association><name>serverAssoc</name><type>SERVER</type></association>";
        NettyAssociationImpl assoc = SctpJacksonXMLHelper.fromXML(xml, NettyAssociationImpl.class);
        assertEquals(AssociationType.SERVER, assoc.getAssociationType());
    }
    
    @Test
    public void testReaderWriterSerialization() throws Exception {
        String xml = "<association><name>readerWriterTest</name><hostAddress>127.0.0.1</hostAddress>"
                + "<hostPort>6000</hostPort><peerAddress>127.0.0.2</peerAddress>"
                + "<peerPort>6001</peerPort><ipChannelType>TCP</ipChannelType></association>";
        
        // Deserialize using Reader
        NettyAssociationImpl assoc = SctpJacksonXMLHelper.fromXML(new StringReader(xml), NettyAssociationImpl.class);
        assertEquals("readerWriterTest", assoc.getName());
        
        // Serialize using Writer
        StringWriter writer = new StringWriter();
        SctpJacksonXMLHelper.toXML(assoc, writer);
        String outputXml = writer.toString();
        assertNotNull(outputXml);
    }
    
    @Test
    public void testExtraHostAddresses() throws Exception {
        String xml = "<association><name>multihomed</name><hostAddress>10.0.0.1</hostAddress>"
                + "<hostPort>7000</hostPort><ipChannelType>SCTP</ipChannelType>"
                + "<extraHostAddresses><extraHostAddress>192.168.1.1</extraHostAddress>"
                + "<extraHostAddress>192.168.1.2</extraHostAddress></extraHostAddresses></association>";
        
        NettyAssociationImpl assoc = SctpJacksonXMLHelper.fromXML(xml, NettyAssociationImpl.class);
        assertNotNull(assoc);
        assertEquals("multihomed", assoc.getName());
    }
    
    @Test
    public void testServerName() throws Exception {
        String xml = "<association><name>srvAssoc</name><serverName>myServer</serverName></association>";
        NettyAssociationImpl assoc = SctpJacksonXMLHelper.fromXML(xml, NettyAssociationImpl.class);
        assertEquals("myServer", assoc.getServerName());
    }
    
    @Test
    public void testUnknownPropertiesIgnored() throws Exception {
        String xml = "<association><name>unknown</name><unknownField>ignore</unknownField></association>";
        NettyAssociationImpl assoc = SctpJacksonXMLHelper.fromXML(xml, NettyAssociationImpl.class);
        assertNotNull(assoc);
        assertEquals("unknown", assoc.getName());
    }
}
