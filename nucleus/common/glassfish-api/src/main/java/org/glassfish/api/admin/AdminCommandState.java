/*
 * Copyright (c) 2012, 2018 Oracle and/or its affiliates. All rights reserved.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License v. 2.0, which is available at
 * http://www.eclipse.org/legal/epl-2.0.
 *
 * This Source Code may also be made available under the following Secondary
 * Licenses when the conditions for such availability set forth in the
 * Eclipse Public License v. 2.0 are satisfied: GNU General Public License,
 * version 2 with the GNU Classpath Exception, which is available at
 * https://www.gnu.org/software/classpath/license.html.
 *
 * SPDX-License-Identifier: EPL-2.0 OR GPL-2.0 WITH Classpath-exception-2.0
 */

package org.glassfish.api.admin;

import org.glassfish.api.ActionReport;

/**
 *
 * @author Martin Mares
 * @author Bhakti Mehta
 */
public interface AdminCommandState {

    public static final String EVENT_STATE_CHANGED = "AdminCommandInstance/stateChanged";

    public enum State {
        PREPARED, RUNNING, COMPLETED, RECORDED, RUNNING_RETRYABLE, FAILED_RETRYABLE, REVERTING, REVERTED;
    }

    public State getState();

    /**
     * Completes whole progress and records result
     * 
     * @param actionReport result message
     *
     */
    public void complete(ActionReport actionReport);

    public ActionReport getActionReport();

    /**
     * Are there data in outbound payload or not.
     */
    public boolean isOutboundPayloadEmpty();

    public String getId();

}
