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

import java.util.List;
import java.util.Map;

import org.mobicents.protocols.api.Association;
import org.mobicents.protocols.api.Server;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlElementWrapper;
import com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlProperty;
import com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlRootElement;

/**
 * Wrapper class for SCTP persistence data using Jackson XML.
 * 
 * @author Jenny
 */
@JacksonXmlRootElement(localName = "sctp")
@JsonInclude(JsonInclude.Include.NON_NULL)
public class SctpPersistData {

    @JsonProperty("connectdelay")
    private Integer connectDelay;

    @JsonProperty("singlethread")
    private Boolean singleThread;

    @JsonProperty("workerthreads")
    private Integer workerThreads;

    @JsonProperty("congControl_DelayThreshold_1")
    private Double congControlDelayThreshold1;

    @JsonProperty("congControl_DelayThreshold_2")
    private Double congControlDelayThreshold2;

    @JsonProperty("congControl_DelayThreshold_3")
    private Double congControlDelayThreshold3;

    @JsonProperty("congControl_BackToNormalDelayThreshold_1")
    private Double congControlBackToNormalDelayThreshold1;

    @JsonProperty("congControl_BackToNormalDelayThreshold_2")
    private Double congControlBackToNormalDelayThreshold2;

    @JsonProperty("congControl_BackToNormalDelayThreshold_3")
    private Double congControlBackToNormalDelayThreshold3;

    @JacksonXmlElementWrapper(localName = "servers")
    @JacksonXmlProperty(localName = "server")
    @JsonDeserialize(contentAs = NettyServerImpl.class)
    private List<Server> servers;

    @JacksonXmlElementWrapper(localName = "associations")
    @JacksonXmlProperty(localName = "association")
    @JsonDeserialize(as = NettyAssociationMap.class, contentAs = NettyAssociationImpl.class)
    private Map<String, Association> associations;

    // Default constructor for Jackson
    public SctpPersistData() {
    }

    // Getters and Setters
    public Integer getConnectDelay() {
        return connectDelay;
    }

    public void setConnectDelay(Integer connectDelay) {
        this.connectDelay = connectDelay;
    }

    public Boolean getSingleThread() {
        return singleThread;
    }

    public void setSingleThread(Boolean singleThread) {
        this.singleThread = singleThread;
    }

    public Integer getWorkerThreads() {
        return workerThreads;
    }

    public void setWorkerThreads(Integer workerThreads) {
        this.workerThreads = workerThreads;
    }

    public Double getCongControlDelayThreshold1() {
        return congControlDelayThreshold1;
    }

    public void setCongControlDelayThreshold1(Double congControlDelayThreshold1) {
        this.congControlDelayThreshold1 = congControlDelayThreshold1;
    }

    public Double getCongControlDelayThreshold2() {
        return congControlDelayThreshold2;
    }

    public void setCongControlDelayThreshold2(Double congControlDelayThreshold2) {
        this.congControlDelayThreshold2 = congControlDelayThreshold2;
    }

    public Double getCongControlDelayThreshold3() {
        return congControlDelayThreshold3;
    }

    public void setCongControlDelayThreshold3(Double congControlDelayThreshold3) {
        this.congControlDelayThreshold3 = congControlDelayThreshold3;
    }

    public Double getCongControlBackToNormalDelayThreshold1() {
        return congControlBackToNormalDelayThreshold1;
    }

    public void setCongControlBackToNormalDelayThreshold1(Double congControlBackToNormalDelayThreshold1) {
        this.congControlBackToNormalDelayThreshold1 = congControlBackToNormalDelayThreshold1;
    }

    public Double getCongControlBackToNormalDelayThreshold2() {
        return congControlBackToNormalDelayThreshold2;
    }

    public void setCongControlBackToNormalDelayThreshold2(Double congControlBackToNormalDelayThreshold2) {
        this.congControlBackToNormalDelayThreshold2 = congControlBackToNormalDelayThreshold2;
    }

    public Double getCongControlBackToNormalDelayThreshold3() {
        return congControlBackToNormalDelayThreshold3;
    }

    public void setCongControlBackToNormalDelayThreshold3(Double congControlBackToNormalDelayThreshold3) {
        this.congControlBackToNormalDelayThreshold3 = congControlBackToNormalDelayThreshold3;
    }

    public List<Server> getServers() {
        return servers;
    }

    public void setServers(List<Server> servers) {
        this.servers = servers;
    }

    public Map<String, Association> getAssociations() {
        return associations;
    }

    public void setAssociations(Map<String, Association> associations) {
        this.associations = associations;
    }
}
