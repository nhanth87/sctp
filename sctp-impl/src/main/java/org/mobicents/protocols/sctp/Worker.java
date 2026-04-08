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

import org.mobicents.protocols.api.AssociationListener;
import org.mobicents.protocols.api.PayloadData;
import org.mobicents.protocols.api.PayloadDataPool;

/**
 * @author <a href="mailto:nhanth87@gmail.com">nhanth87</a>
 * @author jenny (added PayloadDataPool support for object reuse)
 * 
 */
public class Worker implements Runnable {

	private AssociationImpl association;
	private AssociationListener associationListener;
	private PayloadData payloadData;
	private PayloadDataPool payloadDataPool;

	/**
	 * @param association
	 * @param associationListener
	 * @param payloadData
	 * @param payloadDataPool Pool for releasing PayloadData after processing (can be null)
	 */
	protected Worker(AssociationImpl association, AssociationListener associationListener, 
			PayloadData payloadData, PayloadDataPool payloadDataPool) {
		super();
		this.association = association;
		this.associationListener = associationListener;
		this.payloadData = payloadData;
		this.payloadDataPool = payloadDataPool;
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see java.lang.Runnable#run()
	 */
	@Override
	public void run() {
		try {
			this.associationListener.onPayload(this.association, this.payloadData);
		} catch (Exception e) {
			e.printStackTrace();
		} finally {
			// Release PayloadData back to pool for reuse
			if (this.payloadDataPool != null && this.payloadData != null) {
				this.payloadDataPool.release(this.payloadData);
			}
		}
	}

}
