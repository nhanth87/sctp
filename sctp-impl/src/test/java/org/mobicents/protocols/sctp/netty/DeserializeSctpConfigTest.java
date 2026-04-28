package org.mobicents.protocols.sctp.netty;

import java.io.StringReader;

public class DeserializeSctpConfigTest {
    public static void main(String[] args) throws Exception {
        String xml = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n" +
            "<sctp>\n" +
            "  <connectdelay>5000</connectdelay>\n" +
            "  <servers>\n" +
            "    <server>\n" +
            "      <name>serv1</name>\n" +
            "      <hostAddress>127.0.0.1</hostAddress>\n" +
            "      <hostport>8012</hostport>\n" +
            "      <started>true</started>\n" +
            "      <ipChannelType>SCTP</ipChannelType>\n" +
            "      <acceptAnonymousConnections>false</acceptAnonymousConnections>\n" +
            "      <maxConcurrentConnectionsCount>0</maxConcurrentConnectionsCount>\n" +
            "    </server>\n" +
            "  </servers>\n" +
            "  <associations>\n" +
            "    <ass1>\n" +
            "      <hostAddress>127.0.0.1</hostAddress>\n" +
            "      <hostPort>8012</hostPort>\n" +
            "      <peerAddress>127.0.0.1</peerAddress>\n" +
            "      <peerPort>8011</peerPort>\n" +
            "      <serverName>serv1</serverName>\n" +
            "      <name>ass1</name>\n" +
            "      <ipChannelType>SCTP</ipChannelType>\n" +
            "      <type>SERVER</type>\n" +
            "    </ass1>\n" +
            "  </associations>\n" +
            "</sctp>";

        SctpPersistData data = NettySctpXMLBinding.getXmlMapper().readValue(new StringReader(xml), SctpPersistData.class);
        System.out.println("Parse OK! connectDelay=" + data.getConnectDelay());
        System.out.println("servers size=" + (data.getServers() != null ? data.getServers().size() : "null"));
        System.out.println("associations size=" + (data.getAssociations() != null ? data.getAssociations().size() : "null"));
        if (data.getAssociations() != null) {
            for (java.util.Map.Entry<String, org.mobicents.protocols.api.Association> e : data.getAssociations().entrySet()) {
                System.out.println("  assoc[" + e.getKey() + "].name=" + e.getValue().getName());
            }
        }
    }
}
