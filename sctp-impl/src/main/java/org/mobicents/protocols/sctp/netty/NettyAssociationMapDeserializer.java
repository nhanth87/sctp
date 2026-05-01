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

import java.io.IOException;
import java.util.Map;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;

/**
 * Custom deserializer for NettyAssociationMap that handles XML format
 * where each association element has a 'name' attribute as the key.
 * 
 * @author Jenny
 */
public class NettyAssociationMapDeserializer extends JsonDeserializer<Map<String, org.mobicents.protocols.api.Association>> {

    @Override
    public Map<String, org.mobicents.protocols.api.Association> deserialize(JsonParser jp, DeserializationContext ctxt)
            throws IOException, JsonProcessingException {
        Map<String, org.mobicents.protocols.api.Association> result = new NettyAssociationMap<>();
        
        JsonNode node = jp.getCodec().readTree(jp);
        
        if (node == null || node.isNull()) {
            return result;
        }
        
        // Jackson XML wraps elements with @JacksonXmlElementWrapper as an object
        // where the inner @JacksonXmlProperty becomes a field
        // e.g. <associations><association.../></associations> becomes {"association": {...}}
        // or {"association": [{...}, {...}]} for multiple
        JsonNode assocNode = node.get("association");
        if (assocNode != null) {
            if (assocNode.isArray()) {
                ArrayNode arrayNode = (ArrayNode) assocNode;
                for (int i = 0; i < arrayNode.size(); i++) {
                    org.mobicents.protocols.api.Association assoc = deserializeAssociation(arrayNode.get(i));
                    if (assoc != null && assoc.getName() != null) {
                        result.put(assoc.getName(), assoc);
                    }
                }
            } else if (assocNode.isObject()) {
                org.mobicents.protocols.api.Association assoc = deserializeAssociation(assocNode);
                if (assoc != null && assoc.getName() != null) {
                    result.put(assoc.getName(), assoc);
                }
            }
        } else if (node.isArray()) {
            // Direct array without wrapper
            ArrayNode arrayNode = (ArrayNode) node;
            for (int i = 0; i < arrayNode.size(); i++) {
                org.mobicents.protocols.api.Association assoc = deserializeAssociation(arrayNode.get(i));
                if (assoc != null && assoc.getName() != null) {
                    result.put(assoc.getName(), assoc);
                }
            }
        } else if (node.isObject()) {
            // Direct single object without wrapper
            org.mobicents.protocols.api.Association assoc = deserializeAssociation(node);
            if (assoc != null && assoc.getName() != null) {
                result.put(assoc.getName(), assoc);
            }
        }
        
        return result;
    }
    
    private org.mobicents.protocols.api.Association deserializeAssociation(JsonNode node) {
        if (node == null) {
            return null;
        }
        
        try {
            NettyAssociationImpl assoc = new NettyAssociationImpl();
            
            // Extract name (used as map key)
            JsonNode nameNode = node.get("name");
            if (nameNode != null && !nameNode.isNull()) {
                assoc.setName(nameNode.asText());
            }
            
            // Extract all other properties
            JsonNode hostAddressNode = node.get("hostAddress");
            if (hostAddressNode != null && !hostAddressNode.isNull()) {
                assoc.setHostAddress(hostAddressNode.asText());
            }
            
            JsonNode hostPortNode = node.get("hostPort");
            if (hostPortNode != null && !hostPortNode.isNull()) {
                assoc.setHostPort(hostPortNode.asInt());
            }
            
            JsonNode peerAddressNode = node.get("peerAddress");
            if (peerAddressNode != null && !peerAddressNode.isNull()) {
                assoc.setPeerAddress(peerAddressNode.asText());
            }
            
            JsonNode peerPortNode = node.get("peerPort");
            if (peerPortNode != null && !peerPortNode.isNull()) {
                assoc.setPeerPort(peerPortNode.asInt());
            }
            
            JsonNode serverNameNode = node.get("serverName");
            if (serverNameNode != null && !serverNameNode.isNull()) {
                assoc.setServerName(serverNameNode.asText());
            }
            
            JsonNode ipChannelTypeNode = node.get("ipChannelType");
            if (ipChannelTypeNode != null && !ipChannelTypeNode.isNull()) {
                String ipChannelTypeStr = ipChannelTypeNode.asText();
                assoc.setIpChannelType(org.mobicents.protocols.api.IpChannelType.valueOf(ipChannelTypeStr));
            }
            
            JsonNode typeNode = node.get("type");
            if (typeNode != null && !typeNode.isNull()) {
                String typeStr = typeNode.asText();
                assoc.setAssociationType(org.mobicents.protocols.api.AssociationType.valueOf(typeStr));
            }
            
            JsonNode startedNode = node.get("started");
            if (startedNode != null && !startedNode.isNull()) {
                assoc.setStarted(startedNode.asBoolean());
            }
            
            // Extract extra host addresses if present
            JsonNode extraHostNode = node.get("extraHostAddresses");
            if (extraHostNode != null && extraHostNode.isArray()) {
                String[] extraHosts = new String[extraHostNode.size()];
                for (int i = 0; i < extraHostNode.size(); i++) {
                    extraHosts[i] = extraHostNode.get(i).asText();
                }
                assoc.setExtraHostAddresses(extraHosts);
            }
            
            return assoc;
        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
    }
}
