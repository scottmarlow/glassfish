/*
 * Copyright (c) 2022, 2022 Contributors to the Eclipse Foundation.
 * Copyright (c) 1997, 2020 Oracle and/or its affiliates. All rights reserved.
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

package com.sun.ejb.containers;

import com.sun.appserv.connectors.internal.api.ResourceHandle;
import com.sun.ejb.ComponentContext;
import com.sun.ejb.Container;
import com.sun.ejb.EjbInvocation;
import com.sun.enterprise.container.common.spi.CDIService;

import jakarta.ejb.EJBContext;
import jakarta.ejb.EJBHome;
import jakarta.ejb.EJBLocalHome;
import jakarta.ejb.EJBLocalObject;
import jakarta.ejb.EJBObject;
import jakarta.transaction.Status;
import jakarta.transaction.Transaction;
import jakarta.transaction.TransactionManager;
import jakarta.transaction.UserTransaction;

import java.io.Serializable;
import java.lang.reflect.Method;
import java.security.Identity;
import java.security.Principal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.naming.Context;
import javax.naming.InitialContext;

import org.glassfish.api.invocation.ComponentInvocation;

/**
 * Implementation of jakarta.ejb.EJBContext for the J2EE Reference Implementation.
 */
public abstract class EJBContextImpl implements EJBContext, ComponentContext, Serializable {
    private static final long serialVersionUID = 1L;

    static final Logger _logger = EjbContainerUtilImpl.getLogger();

    public enum BeanState {
        CREATED, POOLED, READY, INVOKING, INCOMPLETE_TX, IN_PASSIVATE, PASSIVATED, IN_ACTIVATE, ACTIVATED, IN_REMOVE, DESTROYED
    }

    private Object ejb;

    // These are all transient to prevent serialization during passivation
    // Note: all these will be initialized to default values during deserialization.
    transient protected BaseContainer container;

    transient protected Transaction transaction;
    transient protected Context initialContext;
    transient private ArrayList resources;
    transient private int concInvokeCount = 0;

    // the EJBObject's client-side RMI stub
    transient protected EJBObject ejbStub = null;
    transient protected EJBObjectImpl ejbObjectImpl;

    transient protected EJBObjectImpl ejbRemoteBusinessObjectImpl;

    transient protected EJBLocalObjectImpl ejbLocalObjectImpl;
    transient protected EJBLocalObjectImpl ejbLocalBusinessObjectImpl;
    transient protected EJBLocalObjectImpl optionalEjbLocalBusinessObjectImpl;

    transient private long lastTimeUsed;
    transient protected BeanState state;

    // true if the bean exposes a RemoteHome/Remote view
    // (not 3.0 business view)
    protected boolean isRemoteInterfaceSupported;

    // true if the bean exposes a LocalHome/Localview
    // (not 3.0 business view)
    protected boolean isLocalInterfaceSupported;

    // can't/doesn't set the context to DESTROYED until after calling ejbRemove
    // but it needs a way to know if bean is being removed. Standardizing
    // on the DESTROYED state doesn't help, since often times the container
    // can't/doesn't set the context to DESTROYED until after calling ejbRemove
    transient protected boolean inEjbRemove;

    private Object[] interceptorInstances;

    // TODO how to handle this for passivated SFSBs?
    transient protected CDIService.CDIInjectionContext cdiInjectionContext;

    protected EJBContextImpl(Object ejb, BaseContainer container) {
        this.ejb = ejb;
        this.container = container;
        state = BeanState.CREATED;
        inEjbRemove = false;

        isRemoteInterfaceSupported = container.isRemoteInterfaceSupported();
        isLocalInterfaceSupported = container.isLocalInterfaceSupported();
    }

    @Override
    public Transaction getTransaction() {
        return transaction;
    }

    public void setTransaction(Transaction tr) {
        transaction = tr;
    }

    void setEJBStub(EJBObject ejbStub) {
        this.ejbStub = ejbStub;
    }

    void setEJBLocalObjectImpl(EJBLocalObjectImpl localObjectImpl) {
        this.ejbLocalObjectImpl = localObjectImpl;
    }

    void setEJBLocalBusinessObjectImpl(EJBLocalObjectImpl localBusObjectImpl) {
        this.ejbLocalBusinessObjectImpl = localBusObjectImpl;
    }

    void setOptionalEJBLocalBusinessObjectImpl(EJBLocalObjectImpl optionalLocalBusObjectImpl) {
        this.optionalEjbLocalBusinessObjectImpl = optionalLocalBusObjectImpl;
    }

    void setEJBObjectImpl(EJBObjectImpl ejbo) {
        this.ejbObjectImpl = ejbo;
    }

    EJBObjectImpl getEJBObjectImpl() {
        return ejbObjectImpl;
    }

    void setEJBRemoteBusinessObjectImpl(EJBObjectImpl ejbo) {
        this.ejbRemoteBusinessObjectImpl = ejbo;
    }

    EJBObjectImpl getEJBRemoteBusinessObjectImpl() {
        return this.ejbRemoteBusinessObjectImpl;
    }

    EJBLocalObjectImpl getEJBLocalObjectImpl() {
        return ejbLocalObjectImpl;
    }

    EJBLocalObjectImpl getEJBLocalBusinessObjectImpl() {
        return ejbLocalBusinessObjectImpl;
    }

    EJBLocalObjectImpl getOptionalEJBLocalBusinessObjectImpl() {
        return optionalEjbLocalBusinessObjectImpl;
    }

    void setContainer(BaseContainer container) {
        this.container = container;
    }

    void setState(BeanState s) {
        state = s;
    }

    boolean isTimedObject() {
        return container.isTimedObject();
    }

    protected BeanState getState() {
        return state;
    }

    void setInEjbRemove(boolean beingRemoved) {
        inEjbRemove = beingRemoved;
    }

    boolean isInEjbRemove() {
        return inEjbRemove;
    }

    void setCDIInjectionContext(CDIService.CDIInjectionContext ctx) {
        cdiInjectionContext = ctx;
    }

    CDIService.CDIInjectionContext getCDIInjectionContext() {
        return cdiInjectionContext;
    }

    public long getLastTimeUsed() {
        return lastTimeUsed;
    }

    public void touch() {
        lastTimeUsed = System.currentTimeMillis();
    }

    /**************************************************************************
     * The following are implementations of ComponentContext methods.
     **************************************************************************/

    /**
     *
     */
    @Override
    public Object getEJB() {
        return ejb;
    }

    @Override
    public final Container getContainer() {
        return container;
    }

    /**
     * Register a resource opened by the EJB instance associated with this Context.
     */
    public void registerResource(ResourceHandle h) {
        if (resources == null) {
            resources = new ArrayList();
        }
        resources.add(h);
    }

    /**
     * Unregister a resource from this Context.
     */
    public void unregisterResource(ResourceHandle h) {
        if (resources == null) {
            resources = new ArrayList();
        }

        resources.remove(h);
    }

    /**
     * Get all the resources associated with the context
     */
    @Override
    public List getResourceList() {
        if (resources == null) {
            resources = new ArrayList(0);
        }

        return resources;
    }

    /**
     * Get the number of concurrent invocations on this bean (could happen with re-entrant bean). Used by TM.
     */
    public int getConcurrentInvokeCount() {
        return concInvokeCount;
    }

    /**
     * Increment the number of concurrent invocations on this bean (could happen with re-entrant bean). Used by TM.
     */
    public synchronized void incrementConcurrentInvokeCount() {
        concInvokeCount++;
    }

    /**
     * Decrement the number of concurrent invocations on this bean (could happen with re-entrant bean). Used by TM.
     */
    public synchronized void decrementConcurrentInvokeCount() {
        concInvokeCount--;
    }

    /**************************************************************************
     * The following are implementations of EJBContext methods.
     **************************************************************************/

    /**
     * This is a SessionContext/EntityContext method.
     */
    public EJBObject getEJBObject() throws IllegalStateException {
        if (ejbStub == null) {
            throw new IllegalStateException("EJBObject not available");
        }

        return ejbStub;
    }

    /**
     * This is a SessionContext/EntityContext method.
     */
    public EJBLocalObject getEJBLocalObject() throws IllegalStateException {
        if (ejbLocalObjectImpl == null) {
            throw new IllegalStateException("EJBLocalObject not available");
        }

        // Have to convert EJBLocalObjectImpl to the client-view of
        // EJBLocalObject
        return (EJBLocalObject) ejbLocalObjectImpl.getClientObject();
    }

    /**
     *
     */
    @Override
    public EJBHome getEJBHome() {
        if (!isRemoteInterfaceSupported) {
            throw new IllegalStateException("EJBHome not available");
        }

        return container.getEJBHomeStub();
    }

    /**
     *
     */
    @Override
    public EJBLocalHome getEJBLocalHome() {
        if (!isLocalInterfaceSupported) {
            throw new IllegalStateException("EJBLocalHome not available");
        }

        return container.getEJBLocalHome();
    }

    /**
     *
     */
    public Properties getEnvironment() {
        // This is deprecated, see EJB2.0 section 20.6.
        return container.getEnvironmentProperties();
    }

    /**
     * @deprecated
     */
    @Deprecated
    public Identity getCallerIdentity() {
        // This method is deprecated.
        // see EJB2.0 section 21.2.5
        throw new RuntimeException("getCallerIdentity() is deprecated, please use getCallerPrincipal().");
    }

    @Override
    public Object lookup(String name) {
        Object o = null;

        if (name == null) {
            throw new IllegalArgumentException("Argument is null");
        }
        try {
            if (initialContext == null) {
                initialContext = new InitialContext();
            }
            // if name starts with java: use it as is. Otherwise, treat it
            // as relative to the private component namespace.
            String lookupString = name.startsWith("java:") ? name : "java:comp/env/" + name;

            o = initialContext.lookup(lookupString);
        } catch (Exception e) {
            throw new IllegalArgumentException(e);
        }
        return o;
    }

    /**
     *
     */
    @Override
    public Principal getCallerPrincipal() {

        checkAccessToCallerSecurity();

        com.sun.enterprise.security.SecurityManager sm = container.getSecurityManager();

        return sm.getCallerPrincipal();
    }

    /**
     * @return Returns the contextMetaData.
     */
    @Override
    public Map<String, Object> getContextData() {
        Map<String, Object> contextData = Collections.EMPTY_MAP;
        ComponentInvocation inv = EjbContainerUtilImpl.getInstance().getCurrentInvocation();
        if (inv instanceof EjbInvocation) {
            EjbInvocation ejbInv = (EjbInvocation) inv;
            contextData = ejbInv.getContextData();
        }
        return contextData;
    }

    /**
     * @deprecated
     */
    @Deprecated
    public boolean isCallerInRole(Identity identity) {
        // THis method is deprecated.
        // This implementation is as in EJB2.0 section 21.2.5
        return isCallerInRole(identity.getName());
    }

    /**
     *
     */
    @Override
    public boolean isCallerInRole(String roleRef) {
        if (roleRef == null) {
            throw new IllegalStateException("Argument is null");
        }

        checkAccessToCallerSecurity();

        com.sun.enterprise.security.SecurityManager sm = container.getSecurityManager();
        return sm.isCallerInRole(roleRef);
    }

    /**
     * Overridden in containers that allow access to isCallerInRole() and getCallerPrincipal()
     */
    protected void checkAccessToCallerSecurity() throws IllegalStateException {
        throw new IllegalStateException("Operation not allowed");
    }

    /**
     *
     */
    @Override
    public UserTransaction getUserTransaction() throws IllegalStateException {
        throw new IllegalStateException("Operation not allowed");
    }

    /**
     *
     */
    @Override
    public void setRollbackOnly() throws IllegalStateException {
        if (state == BeanState.CREATED) {
            throw new IllegalStateException("EJB not in READY state");
        }

        // EJB2.0 section 7.5.2: only EJBs with container managed transactions
        // can use this method.
        if (container.isBeanManagedTran) {
            throw new IllegalStateException("Illegal operation for bean-managed transactions");
        }

        TransactionManager tm = EjbContainerUtilImpl.getInstance().getTransactionManager();

        try {
            if (tm.getStatus() == Status.STATUS_NO_TRANSACTION) {
                // EJB might be in a non-business method (for SessionBeans)
                // or afterCompletion.
                // OR this was a NotSupported/Never/Supports
                // EJB which was invoked without a global transaction.
                // In that case the JDBC connection would have autoCommit=true
                // so the container doesnt have to do anything.
                throw new IllegalStateException("No transaction context.");
            }

            checkActivatePassivate();

            doGetSetRollbackTxAttrCheck();

            tm.setRollbackOnly();

        } catch (Exception ex) {
            IllegalStateException illEx = new IllegalStateException(ex.toString());
            illEx.initCause(ex);
            throw illEx;
        }
    }

    /**
     *
     */
    @Override
    public boolean getRollbackOnly() throws IllegalStateException {
        if (state == BeanState.CREATED) {
            throw new IllegalStateException("EJB not in READY state");
        }

        // EJB2.0 section 7.5.2: only EJBs with container managed transactions
        // can use this method.
        if (container.isBeanManagedTran) {
            throw new IllegalStateException("Illegal operation for bean-managed transactions");
        }

        TransactionManager tm = EjbContainerUtilImpl.getInstance().getTransactionManager();

        try {
            int status = tm.getStatus();
            if (status == Status.STATUS_NO_TRANSACTION) {
                // EJB which was invoked without a global transaction.
                throw new IllegalStateException("No transaction context.");
            }

            checkActivatePassivate();

            doGetSetRollbackTxAttrCheck();

            if (status == Status.STATUS_MARKED_ROLLBACK || status == Status.STATUS_ROLLEDBACK || status == Status.STATUS_ROLLING_BACK) {
                return true;
            } else {
                return false;
            }
        } catch (Exception ex) {
            _logger.log(Level.FINE, "Exception in method getRollbackOnly()", ex);
            IllegalStateException illEx = new IllegalStateException(ex.toString());
            illEx.initCause(ex);
            throw illEx;
        }
    }

    protected void doGetSetRollbackTxAttrCheck() {

        ComponentInvocation inv = EjbContainerUtilImpl.getInstance().getCurrentInvocation();
        if (inv instanceof EjbInvocation) {
            EjbInvocation ejbInv = (EjbInvocation) inv;

            if (ejbInv.invocationInfo != null) {
                switch (ejbInv.invocationInfo.txAttr) {
                case Container.TX_NOT_SUPPORTED:
                case Container.TX_SUPPORTS:
                case Container.TX_NEVER:
                    throw new IllegalStateException("Illegal tx attribute");
                }
            }
        }

    }

    /**************************************************************************
     * The following are EJBContextImpl-specific methods.
     **************************************************************************/
    void setInterceptorInstances(Object[] instances) {
        this.interceptorInstances = instances;
    }

    public Object[] getInterceptorInstances() {
        return this.interceptorInstances;
    }

    /**
     * The EJB spec makes a distinction between access to the TimerService object itself (via EJBContext.getTimerService)
     * and access to the methods on TimerService, Timer, and TimerHandle. The latter case is covered by this check. It is
     * overridden in the applicable concrete context impl subclasses.
     */
    @Override
    public void checkTimerServiceMethodAccess() throws IllegalStateException {
        throw new IllegalStateException("EJB Timer Service method calls " + "cannot be called in this context");
    }

    // Throw exception if EJB is in ejbActivate/Passivate
    protected void checkActivatePassivate() throws IllegalStateException {
        if (inActivatePassivate()) {
            throw new IllegalStateException("Operation not allowed.");
        }

    }

    protected boolean inActivatePassivate() {
        return inActivatePassivate(EjbContainerUtilImpl.getInstance().getCurrentInvocation());
    }

    protected boolean inActivatePassivate(ComponentInvocation inv) {
        boolean inActivatePassivate = false;
        if (inv instanceof EjbInvocation) {
            Method currentMethod = ((EjbInvocation) inv).method;
            inActivatePassivate = (currentMethod != null)
                    ? (currentMethod.getName().equals("ejbActivate") || currentMethod.getName().equals("ejbPassivate"))
                    : false;
        }
        return inActivatePassivate;
    }

    void setEJB(Object o) {
        ejb = o;
    }

    protected Object getKey() {
        if (ejbLocalObjectImpl != null) {
            return ejbLocalObjectImpl.getKey();
        } else if (ejbObjectImpl != null) {
            return ejbObjectImpl.getKey();
        } else {
            return null;
        }
    }

    /**
     * Called after this context is freed up.
     */
    void deleteAllReferences() {
        ejb = null;
        container = null;
        transaction = null;
        resources = null;
        ejbStub = null;
        ejbObjectImpl = null;
        ejbRemoteBusinessObjectImpl = null;
        ejbLocalObjectImpl = null;
        ejbLocalBusinessObjectImpl = null;
    }

}
