/*
 * TeleStax, Open Source Cloud Communications  Copyright 2012. 
 * and individual contributors
 * by the @authors tag. See the copyright.txt in the distribution for a
 * full listing of individual contributors.
 *
 * This is free software; you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License as
 * published by the Free Software Foundation; either version 2.1 of
 * the License, or (at your option) any later version.
 *
 * This software is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this software; if not, write to the Free
 * Software Foundation, Inc., 51 Franklin St, Fifth Floor, Boston, MA
 * 02110-1301 USA, or see the FSF site: http://www.fsf.org.
 */

package org.mobicents.protocols.sctp;

import com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlRootElement;
import org.mobicents.protocols.api.Server;

import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Data container for SCTP persistence (replaces Map-based approach from XStream).
 * 
 * @author amit bhayani
 */
@JacksonXmlRootElement(localName = "sctpManagement")
public class SctpPersistenceData {
    
    private Integer connectDelay;
    private CopyOnWriteArrayList<Server> servers;
    private AssociationMap<String, org.mobicents.protocols.api.Association> associations;
    
    public SctpPersistenceData() {
    }
    
    public Integer getConnectDelay() {
        return connectDelay;
    }
    
    public void setConnectDelay(Integer connectDelay) {
        this.connectDelay = connectDelay;
    }
    
    public CopyOnWriteArrayList<Server> getServers() {
        return servers;
    }
    
    public void setServers(CopyOnWriteArrayList<Server> servers) {
        this.servers = servers;
    }
    
    public AssociationMap<String, org.mobicents.protocols.api.Association> getAssociations() {
        return associations;
    }
    
    public void setAssociations(AssociationMap<String, org.mobicents.protocols.api.Association> associations) {
        this.associations = associations;
    }
}
