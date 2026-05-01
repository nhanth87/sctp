package org.mobicents.protocols.sctp.netty;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.dataformat.xml.ser.ToXmlGenerator;
import org.mobicents.protocols.api.Association;

import java.io.IOException;
import java.util.Map;

/**
 * Custom serializer for NettyAssociationMap that handles XML format
 * where each association element has a 'name' attribute.
 */
public class NettyAssociationMapSerializer extends JsonSerializer<Map<String, Association>> {

    @Override
    public void serialize(Map<String, Association> value, JsonGenerator gen, SerializerProvider serializers)
            throws IOException {
        if (value == null || value.isEmpty()) {
            return;
        }

        for (Map.Entry<String, Association> entry : value.entrySet()) {
            Association assoc = entry.getValue();
            if (assoc instanceof NettyAssociationImpl) {
                NettyAssociationImpl impl = (NettyAssociationImpl) assoc;
                
                if (gen instanceof ToXmlGenerator) {
                    ToXmlGenerator xmlGen = (ToXmlGenerator) gen;
                    
                    // Start <association> element
                    xmlGen.writeStartObject();
                    
                    // Write name as attribute using raw XML output
                    xmlGen.writeStringField("name", impl.getName());
                    xmlGen.writeStringField("hostAddress", impl.getHostAddress());
                    xmlGen.writeNumberField("hostPort", impl.getHostPort());
                    xmlGen.writeStringField("peerAddress", impl.getPeerAddress());
                    xmlGen.writeNumberField("peerPort", impl.getPeerPort());
                    if (impl.getServerName() != null) {
                        xmlGen.writeStringField("serverName", impl.getServerName());
                    }
                    xmlGen.writeStringField("ipChannelType", impl.getIpChannelType().toString());
                    xmlGen.writeStringField("type", impl.getAssociationType().toString());
                    xmlGen.writeBooleanField("started", impl.isStarted());
                    
                    String[] extraHosts = impl.getExtraHostAddresses();
                    if (extraHosts != null && extraHosts.length > 0) {
                        xmlGen.writeFieldName("extraHostAddresses");
                        xmlGen.writeStartArray();
                        for (String host : extraHosts) {
                            xmlGen.writeString(host);
                        }
                        xmlGen.writeEndArray();
                    }
                    
                    xmlGen.writeEndObject();
                } else {
                    gen.writeObject(assoc);
                }
            } else {
                gen.writeObject(assoc);
            }
        }
    }
}
