/*
 * Copyright (c) 2021 Contributors to Eclipse Foundation.
 * Copyright (c) 1997, 2018 Oracle and/or its affiliates. All rights reserved.
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

package com.sun.web.server;

import java.security.AccessControlException;
import java.security.AccessController;
import java.security.Policy;
import java.security.Principal;
import java.security.PrivilegedAction;
import java.security.ProtectionDomain;
import java.text.MessageFormat;
import java.util.ResourceBundle;
import java.util.logging.Level;
import java.util.logging.Logger;
//END OF IASRI 4660742

import org.apache.catalina.Context;
import org.apache.catalina.InstanceEvent;
import org.apache.catalina.InstanceListener;
import org.apache.catalina.Realm;
import org.apache.catalina.Wrapper;
import org.apache.catalina.connector.RequestFacade;
import org.apache.catalina.servlets.DefaultServlet;
import org.glassfish.api.invocation.ComponentInvocation;
import org.glassfish.api.invocation.InvocationManager;
import org.glassfish.hk2.api.ServiceHandle;
import org.glassfish.hk2.api.ServiceLocator;
import org.glassfish.internal.api.ServerContext;
import org.glassfish.wasp.servlet.JspServlet;
import org.glassfish.web.LogFacade;

import com.sun.enterprise.container.common.spi.util.InjectionException;
import com.sun.enterprise.container.common.spi.util.InjectionManager;
import com.sun.enterprise.security.integration.AppServSecurityContext;
import com.sun.enterprise.security.integration.RealmInitializer;
import com.sun.enterprise.security.integration.SecurityConstants;
import com.sun.enterprise.transaction.api.JavaEETransactionManager;
import com.sun.enterprise.web.WebComponentInvocation;
import com.sun.enterprise.web.WebModule;

import jakarta.servlet.Servlet;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletRequestWrapper;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * This class implements the Tomcat InstanceListener interface and
 * handles the INIT,DESTROY and SERVICE, FILTER events.
 * @author Vivek Nagar
 * @author Tony Ng
 */
public final class J2EEInstanceListener implements InstanceListener {

    private static final Logger _logger = LogFacade.getLogger();

    private static final ResourceBundle _rb = _logger.getResourceBundle();

    private InvocationManager im;
    private JavaEETransactionManager tm;
    private InjectionManager injectionMgr;
    private boolean initialized = false;

    private AppServSecurityContext securityContext;

    public J2EEInstanceListener() {
    }

    public void instanceEvent(InstanceEvent event) {
        Context context = (Context) event.getWrapper().getParent();
        if (!(context instanceof WebModule)) {
            return;
        }
        WebModule wm = (WebModule)context;
        init(wm);

        InstanceEvent.EventType eventType = event.getType();
        if(_logger.isLoggable(Level.FINEST)) {
            _logger.log(Level.FINEST, LogFacade.INSTANCE_EVENT, eventType);
        }
        if (eventType.isBefore) {
            handleBeforeEvent(event, eventType);
        } else {
            handleAfterEvent(event, eventType);
        }
    }

    private synchronized void init(WebModule wm) {
        if (initialized) {
            return;
        }
        ServerContext serverContext = wm.getServerContext();
        if (serverContext == null) {
            String msg = _rb.getString(LogFacade.NO_SERVER_CONTEXT);
            msg = MessageFormat.format(msg, wm.getName());
            throw new IllegalStateException(msg);
        }
        ServiceLocator services = serverContext.getDefaultServices();
        im = services.getService(InvocationManager.class);
        tm = getJavaEETransactionManager(services);
        injectionMgr = services.getService(InjectionManager.class);
        initialized = true;

        securityContext = serverContext.getDefaultServices().getService(AppServSecurityContext.class);
        if (securityContext != null) {
            if (_logger.isLoggable(Level.FINE)) {
                _logger.log(Level.FINE, LogFacade.SECURITY_CONTEXT_OBTAINED, securityContext);
            }
        } else {
            if (_logger.isLoggable(Level.FINE)) {
                _logger.log(Level.FINE, LogFacade.SECURITY_CONTEXT_FAILED);
            }
        }
    }

    private void handleBeforeEvent(InstanceEvent event, InstanceEvent.EventType eventType) {
        Context context = (Context) event.getWrapper().getParent();
        if (!(context instanceof WebModule)) {
            return;
        }
        WebModule wm = (WebModule)context;

        Object instance;
        if (eventType == InstanceEvent.EventType.BEFORE_FILTER_EVENT) {
            instance = event.getFilter();
        } else {
            instance = event.getServlet();
        }

        // set security context
        // BEGIN IAfSRI 4688449
        //try {
        Realm ra = context.getRealm();
        /** IASRI 4713234
        if (ra != null) {
            HttpServletRequest request =
                (HttpServletRequest) event.getRequest();
            if (request != null && request.getUserPrincipal() != null) {
                WebPrincipal prin =
                    (WebPrincipal) request.getUserPrincipal();
                // ra.authenticate(prin);

                // It is inefficient to call authenticate just to set
                // sec.ctx.  Instead, WebPrincipal modified to keep the
                // previously created secctx, and set it here directly.

                SecurityContext.setCurrent(prin.getSecurityContext());
            }
        }
        **/
        // START OF IASRI 4713234
        if (ra != null) {

            ServletRequest request = event.getRequest();
            if (request != null && request instanceof HttpServletRequest) {

                HttpServletRequest hreq = (HttpServletRequest)request;
                HttpServletRequest base = hreq;

                Principal prin = hreq.getUserPrincipal();
                Principal basePrincipal = prin;

                boolean wrapped = false;

                while (prin != null) {

                    if (base instanceof ServletRequestWrapper) {
                        // unwarp any wrappers to find the base object
                        ServletRequest sr =
                            ((ServletRequestWrapper) base).getRequest();

                        if (sr instanceof HttpServletRequest) {

                            base = (HttpServletRequest) sr;
                            wrapped = true;
                            continue;
                        }
                    }

                    if (wrapped) {
                        basePrincipal = base.getUserPrincipal();
                    }

                    else if (base instanceof RequestFacade) {
                        // try to avoid the getUnWrappedCoyoteRequest call
                        // when we can identify see we have the texact class.
                        if (base.getClass() != RequestFacade.class) {
                            basePrincipal = ((RequestFacade)base).
                                getUnwrappedCoyoteRequest().getUserPrincipal();
                        }
                    } else {
                        basePrincipal = base.getUserPrincipal();
                    }

                    break;
                }

                if (prin != null && prin == basePrincipal
                    && prin.getClass().getName().equals(SecurityConstants.WEB_PRINCIPAL_CLASS)) {
                    securityContext.setSecurityContextWithPrincipal(prin);
                } else if (prin != basePrincipal) {

                    // the wrapper has overridden getUserPrincipal
                    // reject the request if the wrapper does not have
                    // the necessary permission.

                    checkObjectForDoAsPermission(hreq);
                    securityContext.setSecurityContextWithPrincipal(prin);

                }

            }
        }
        // END OF IASRI 4713234
        // END IASRI 4688449

        ComponentInvocation inv;
        if (eventType == InstanceEvent.EventType.BEFORE_INIT_EVENT) {
          // The servletName is not avaiable from servlet instance before servlet init.
          // We have to pass the servletName to ComponentInvocation so it can be retrieved
          // in RealmAdapter.getServletName().
          inv = new WebComponentInvocation(wm, instance, event.getWrapper().getName());
        } else {
          inv = new WebComponentInvocation(wm, instance);
        }

        try {
            im.preInvoke(inv);
            if (eventType == InstanceEvent.EventType.BEFORE_SERVICE_EVENT) {
                // Emit monitoring probe event
                wm.beforeServiceEvent(event.getWrapper().getName());
                // enlist resources with TM for service method
                if (tm != null) {
                    tm.enlistComponentResources();
                }
            }
        } catch (Exception ex) {
            im.postInvoke(inv); // See CR 6920895
            String msg = _rb.getString(LogFacade.EXCEPTION_DURING_HANDLE_EVENT);
            msg = MessageFormat.format(msg, new Object[] { eventType, wm });
            throw new RuntimeException(msg, ex);
        }
    }


    private static javax.security.auth.AuthPermission doAsPrivilegedPerm =
        new javax.security.auth.AuthPermission("doAsPrivileged");


    private static void checkObjectForDoAsPermission(final Object o)
            throws AccessControlException{

        if (System.getSecurityManager() != null) {
            AccessController.doPrivileged(new PrivilegedAction<Void>() {
                public Void run() {
                    ProtectionDomain pD = o.getClass().getProtectionDomain();
                    Policy p = Policy.getPolicy();
                    if (!p.implies(pD,doAsPrivilegedPerm)) {
                        throw new AccessControlException
                        ("permission required to override getUserPrincipal",
                            doAsPrivilegedPerm);
                    }
                    return null;
                }
            });
        }
    }

    private void handleAfterEvent(InstanceEvent event,
                InstanceEvent.EventType eventType) {

        Wrapper wrapper = event.getWrapper();
        Context context = (Context) wrapper.getParent();
        if (!(context instanceof WebModule)) {
            return;
        }
        WebModule wm = (WebModule)context;

        Object instance;
        if (eventType == InstanceEvent.EventType.AFTER_FILTER_EVENT) {
            instance = event.getFilter();
        } else {
            instance = event.getServlet();
        }
        if (instance == null) {
            return;
        }

        // Emit monitoring probe event
        if (instance instanceof Servlet) {
            if (eventType == InstanceEvent.EventType.AFTER_INIT_EVENT) {
                wm.servletInitializedEvent(wrapper.getName());
            } else if (eventType == InstanceEvent.EventType.AFTER_DESTROY_EVENT) {
                wm.servletDestroyedEvent(wrapper.getName());
            }
        }

        // Must call InjectionManager#destroyManagedObject WITHIN
        // EE invocation context
        try {
            if (eventType == InstanceEvent.EventType.AFTER_DESTROY_EVENT &&
                    !DefaultServlet.class.equals(instance.getClass()) &&
                    !JspServlet.class.equals(instance.getClass())) {
                injectionMgr.destroyManagedObject(instance, false);
            }
        } catch (InjectionException ie) {
            String msg = _rb.getString(LogFacade.EXCEPTION_DURING_HANDLE_EVENT);
            msg = MessageFormat.format(msg, new Object[] { eventType, wm });
            _logger.log(Level.SEVERE, msg, ie);
        }

        ComponentInvocation inv = new WebComponentInvocation(wm, instance);
        try {
            im.postInvoke(inv);
        } catch (Exception ex) {
            String msg = _rb.getString(LogFacade.EXCEPTION_DURING_HANDLE_EVENT);
            msg = MessageFormat.format(msg, new Object[] { eventType, wm });
            throw new RuntimeException(msg, ex);
        } finally {
            if (eventType == InstanceEvent.EventType.AFTER_DESTROY_EVENT) {
                if (tm != null) {
                    tm.componentDestroyed(instance, inv);
                }
            } else if (eventType == InstanceEvent.EventType.AFTER_FILTER_EVENT ||
                    eventType == InstanceEvent.EventType.AFTER_SERVICE_EVENT) {
                // Emit monitoring probe event
                if (eventType == InstanceEvent.EventType.AFTER_SERVICE_EVENT) {
                    ServletResponse response = event.getResponse();
                    int status = -1;
                    if (response != null && response instanceof HttpServletResponse) {
                        status = ((HttpServletResponse) response).getStatus();
                    }
                    wm.afterServiceEvent(wrapper.getName(), status);
                }

                // check it's top level invocation
                // BEGIN IASRI# 4646060
                if (im.getCurrentInvocation() == null) {
                // END IASRI# 4646060
                    try {
                        // clear security context
                        Realm ra = context.getRealm();
                        if (ra != null && (ra instanceof RealmInitializer)) {
                            //cleanup not only securitycontext but also PolicyContext
                            ((RealmInitializer)ra).logout();
                        }
                    } catch (Exception ex) {
                        String msg = _rb.getString(LogFacade.EXCEPTION_DURING_HANDLE_EVENT);
                        msg = MessageFormat.format(msg, new Object[] { eventType, wm });
                        _logger.log(Level.SEVERE, msg,  ex);
                    }

                    if (tm != null) {
                        try {
                            if (tm.getTransaction() != null) {
                                tm.rollback();
                            }
                            tm.cleanTxnTimeout();
                        } catch (Exception ex) {}
                    }
                }

                if (tm != null) {
                    tm.componentDestroyed(instance, inv);
                }
            }
        }
    }

    private JavaEETransactionManager getJavaEETransactionManager(ServiceLocator services) {
        JavaEETransactionManager tm = null;
        ServiceHandle<JavaEETransactionManager> inhabitant = services.getServiceHandle(JavaEETransactionManager.class);
        if (inhabitant != null && inhabitant.isActive()) {
            tm = inhabitant.getService();
        }

        return tm;
    }
}

