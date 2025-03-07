/*
 * Copyright (c) 2022 Contributors to the Eclipse Foundation
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

import com.sun.ejb.EjbInvocation;
import com.sun.ejb.InvocationInfo;
import com.sun.ejb.containers.util.MethodMap;
import com.sun.enterprise.container.common.spi.util.IndirectlySerializable;
import com.sun.enterprise.util.LocalStringManagerImpl;
import com.sun.enterprise.util.Utility;

import jakarta.ejb.EJBException;
import jakarta.ejb.EJBLocalObject;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Handler for EJBLocalObject invocations through EJBLocalObject proxy.
 *
 * @author Kenneth Saks
 */
public final class EJBLocalObjectInvocationHandler extends EJBLocalObjectImpl implements InvocationHandler {

    private static final Logger logger = EjbContainerUtilImpl.getLogger();

    private static final LocalStringManagerImpl localStrings =
        new LocalStringManagerImpl(EJBLocalObjectInvocationHandler.class);

    // Our associated proxy object.  Used when a caller needs EJBLocalObject
    // but only has InvocationHandler.
    private Object proxy_;

    // Cache reference to invocation info.  There is one of these per
    // container.  It's populated during container initialization and
    // passed in when the InvocationHandler is created.  This avoids the
    // overhead of building the method info each time a LocalObject proxy
    // is created.
    private final MethodMap invocationInfoMap_;

    private Class<?> localIntf_;

    /**
     * Constructor used for Local Home view
     */
    public EJBLocalObjectInvocationHandler(MethodMap invocationInfoMap, Class<?> localIntf) throws Exception {
        invocationInfoMap_ = invocationInfoMap;

        localIntf_ = localIntf;
        setIsLocalHomeView(true);

        // NOTE : Container is not set on super-class until after
        // constructor is called.
    }


    /**
     * Constructor used for Local Business view.
     */
    public EJBLocalObjectInvocationHandler(MethodMap invocationInfoMap, boolean optionalLocalBusinessView)
        throws Exception {
        invocationInfoMap_ = invocationInfoMap;

        setIsLocalHomeView(false);
        setIsOptionalLocalBusinessView(optionalLocalBusinessView);

        // NOTE : Container is not set on super-class until after
        // constructor is called.
    }


    public void setProxy(Object proxy) {
        proxy_ = proxy;
    }

    @Override
    public Object getClientObject() {
        return proxy_;
    }

    /**
     * This entry point is only used for Local Home view.
     */
    @Override
    public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
        return invoke(localIntf_, method, args);
    }


    Object invoke(Class clientInterface, Method method, Object[] args) throws Throwable {
        ClassLoader originalClassLoader = null;

        // NOTE : be careful with "args" parameter.  It is null
        //        if method signature has 0 arguments.
        try {
            container.onEnteringContainer();

            // In some cases(e.g. CDI + OSGi combination) ClassLoader
            // is accessed from the current Thread. In those cases we need to set
            // the context classloader to the application's classloader before
            // proceeding. Otherwise, the context classloader could still
            // reflect the caller's class loader.

            if (Thread.currentThread().getContextClassLoader() != getContainer().getClassLoader()) {
                originalClassLoader = Utility.setContextClassLoader(getContainer().getClassLoader());
            }

            Class<?> methodClass = method.getDeclaringClass();
            if (methodClass == java.lang.Object.class) {
                return InvocationHandlerUtil.invokeJavaObjectMethod(this, method, args);
            } else if (methodClass == IndirectlySerializable.class) {
                return this.getSerializableObjectFactory();
            }

            // Use optimized version of get that takes param count as an argument.
            InvocationInfo invInfo = invocationInfoMap_.get(method, ((args != null) ? args.length : 0));
            if (invInfo == null) {
                throw new IllegalStateException("Unknown method :" + method);
            }

            if ((methodClass == jakarta.ejb.EJBLocalObject.class) || invInfo.ejbIntfOverride) {
                return invokeEJBLocalObjectMethod(method.getName(), args);
            } else if (invInfo.targetMethod1 == null) {
                Object[] params = new Object[] {invInfo.ejbName, "Local", invInfo.method.toString()};
                logger.log(Level.SEVERE, "ejb.bean_class_method_not_found", params);
                String errorMsg = localStrings.getLocalString("ejb.bean_class_method_not_found", "", params);
                throw new EJBException(errorMsg);
            }

            // Process application-specific method.
            EjbInvocation inv = container.createEjbInvocation();
            inv.isLocal = true;
            inv.isBusinessInterface = !isLocalHomeView();
            inv.isHome = false;
            inv.ejbObject = this;
            inv.method = method;
            inv.methodParams = args;
            inv.clientInterface = clientInterface;

            // Set cached invocation params. This will save additional lookups
            // in BaseContainer.
            inv.transactionAttribute = invInfo.txAttr;
            inv.invocationInfo = invInfo;
            inv.beanMethod = invInfo.targetMethod1;

            Object returnValue = null;
            try {
                container.preInvoke(inv);
                returnValue = container.intercept(inv);
            } catch (InvocationTargetException ite) {
                inv.exception = ite.getCause();
                inv.exceptionFromBeanMethod = inv.exception;
            } catch (Throwable t) {
                inv.exception = t;
            } finally {
                container.postInvoke(inv);
            }

            if (inv.exception != null) {
                InvocationHandlerUtil.throwLocalException(inv.exception, method.getExceptionTypes());
            }
            return returnValue;
        } finally {
            if (originalClassLoader != null) {
                Utility.setContextClassLoader(originalClassLoader);
            }
            container.onLeavingContainer();
        }
    }


    private Object invokeEJBLocalObjectMethod(String methodName, Object[] args) throws Exception {
        // Return value is null if target method returns void.
        final Object returnValue;

        int methodIndex = -1;
        Exception caughtException = null;
        // Can only be remove, isIdentical, getEJBLocalHome, or getPrimaryKey,
        // so optimize by comparing as few characters as possible.
        try {
            switch(methodName.charAt(0)) {
                case 'r' :
                    methodIndex = BaseContainer.EJBLocalObject_remove;
                    container.onEjbMethodStart(methodIndex);
                    super.remove();
                    returnValue = null;
                    break;
                case 'i' :
                    // boolean isIdentical(EJBLocalObject)

                    // Convert the param into an EJBLocalObjectImpl.  Can't
                    // assume it's an EJBLocalObject for an ejb that was deployed
                    // using dynamic proxies.
                    EJBLocalObject other = (EJBLocalObject) args[0];
                    EJBLocalObjectImpl otherImpl = EJBLocalObjectImpl.toEJBLocalObjectImpl(other);
                    methodIndex = BaseContainer.EJBLocalObject_isIdentical;
                    container.onEjbMethodStart(methodIndex);
                    returnValue = super.isIdentical(otherImpl);
                    break;
                case 'g' :
                    if (methodName.charAt(3) == 'E') {
                        // EJBLocalHome getEJBLocalHome();
                        methodIndex = BaseContainer.EJBLocalObject_getEJBLocalHome;
                        container.onEjbMethodStart(methodIndex);
                        returnValue = super.getEJBLocalHome();
                    } else {
                        // Object getPrimaryKey();
                        methodIndex = BaseContainer.EJBLocalObject_getPrimaryKey;
                        container.onEjbMethodStart(methodIndex);
                        returnValue = super.getPrimaryKey();
                    }
                    break;
                default:
                    throw new EJBException("unknown method = " + methodName);
            }
        } catch (Exception ex) {
            caughtException = ex;
            throw ex;
        } finally {
            if (methodIndex != -1) {
                container.onEjbMethodEnd(methodIndex,caughtException);
            }
        }
        return returnValue;
    }
}
