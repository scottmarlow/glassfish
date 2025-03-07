/*
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

package com.sun.enterprise.deployment.annotation.context;

import com.sun.enterprise.deployment.Application;
import com.sun.enterprise.deployment.BundleDescriptor;
import com.sun.enterprise.deployment.EjbDescriptor;
import com.sun.enterprise.deployment.EntityManagerFactoryReferenceDescriptor;
import com.sun.enterprise.deployment.EntityManagerReferenceDescriptor;
import com.sun.enterprise.deployment.EnvironmentProperty;
import com.sun.enterprise.deployment.LifecycleCallbackDescriptor;
import com.sun.enterprise.deployment.ManagedBeanDescriptor;
import com.sun.enterprise.deployment.MessageDestinationReferenceDescriptor;
import com.sun.enterprise.deployment.ResourceEnvReferenceDescriptor;
import com.sun.enterprise.deployment.ResourceReferenceDescriptor;
import com.sun.enterprise.deployment.WritableJndiNameEnvironment;
import com.sun.enterprise.deployment.core.*;
import com.sun.enterprise.deployment.types.*;
import org.glassfish.apf.context.AnnotationContext;
import org.glassfish.deployment.common.Descriptor;
import org.glassfish.deployment.common.JavaEEResourceType;

import java.util.Set;

/**
 * This provides an abstraction for handle resource references.
 *
 * @Author Shing Wai Chan
 */
public class ResourceContainerContextImpl extends AnnotationContext
        implements ResourceContainerContext, ComponentContext,
                   ServiceReferenceContainerContext, HandlerContext {

    protected Descriptor descriptor = null;
    protected String componentClassName = null;

    public ResourceContainerContextImpl() {
    }

    public ResourceContainerContextImpl(Descriptor descriptor) {
        this.descriptor = descriptor;
    }

    /**
     * Add a reference to an ejb.
     *
     * @param ejbReference the ejb reference
     */
    public void addEjbReferenceDescriptor(EjbReference ejbReference) {
        getEjbReferenceContainer().addEjbReferenceDescriptor(ejbReference);
    }

    /**
     * Looks up an ejb reference with the given name.
     * Return null if it is not found.
     *
     * @param the name of the ejb-reference
     */
    public EjbReference getEjbReference(String name) {
        EjbReference ejbRef = null;
        try {
            ejbRef = getEjbReferenceContainer().getEjbReference(name);
            // annotation has a corresponding ejb-local-ref/ejb-ref
            // in xml.  Just add annotation info and continue.
            // This logic might change depending on overriding rules
            // and order in which annotations are read w.r.t. to xml.
            // E.g. sparse overriding in xml or loading annotations
            // first.
        } catch(IllegalArgumentException e) {
            // DOL API is (unfortunately) defined to return
            // IllegalStateException if name doesn't exist.

            Application app = getAppFromDescriptor();

            if( app != null ) {
                try {
                    // Check for java:app/java:global dependencies at app-level
                    ejbRef = app.getEjbReferenceByName(name);
                     // Make sure it's added to the container context.
                    addEjbReferenceDescriptor(ejbRef);
                } catch(IllegalArgumentException ee) {}
            }
        }
        return ejbRef;
    }

    protected EjbReferenceContainer getEjbReferenceContainer() {
        return (EjbReferenceContainer)descriptor;
    }

    public void addResourceReferenceDescriptor(ResourceReferenceDescriptor
                                               resReference) {
        getResourceReferenceContainer().addResourceReferenceDescriptor
            (resReference);
    }

    /**
     * Looks up an resource reference with the given name.
     * Return null if it is not found.
     *
     * @param the name of the resource-reference
     */
    public ResourceReferenceDescriptor getResourceReference(String name) {
        ResourceReferenceDescriptor resourceRef = null;
        try {
            resourceRef = getResourceReferenceContainer().
                getResourceReferenceByName(name);
            // annotation has a corresponding resource-ref
            // in xml.  Just add annotation info and continue.
            // This logic might change depending on overriding rules
            // and order in which annotations are read w.r.t. to xml.
            // E.g. sparse overriding in xml or loading annotations
            // first.
        } catch(IllegalArgumentException e) {
            // DOL API is (unfortunately) defined to return
            // IllegalStateException if name doesn't exist.

            Application app = getAppFromDescriptor();

            if( app != null ) {
                try {
                    // Check for java:app/java:global dependencies at app-level
                    resourceRef = app.getResourceReferenceByName(name);
                    // Make sure it's added to the container context.
                    addResourceReferenceDescriptor(resourceRef);
                } catch(IllegalArgumentException ee) {}
            }
        }
        return resourceRef;
    }

    protected ResourceReferenceContainer getResourceReferenceContainer() {
        return (ResourceReferenceContainer)descriptor;
    }


    public void addMessageDestinationReferenceDescriptor
        (MessageDestinationReferenceDescriptor msgDestReference) {
        getMessageDestinationReferenceContainer(
        ).addMessageDestinationReferenceDescriptor(msgDestReference);
    }


    public MessageDestinationReferenceDescriptor getMessageDestinationReference
        (String name) {
        MessageDestinationReferenceDescriptor msgDestRef = null;
        try {
            msgDestRef = getMessageDestinationReferenceContainer().
                getMessageDestinationReferenceByName(name);
            // annotation has a corresponding message-destination-ref
            // in xml.  Just add annotation info and continue.
            // This logic might change depending on overriding rules
            // and order in which annotations are read w.r.t. to xml.
            // E.g. sparse overriding in xml or loading annotations
            // first.
        } catch(IllegalArgumentException e) {
            // DOL API is (unfortunately) defined to return
            // IllegalStateException if name doesn't exist.
        }
        return msgDestRef;
    }

    protected MessageDestinationReferenceContainer
        getMessageDestinationReferenceContainer()
    {
        return (MessageDestinationReferenceContainer)descriptor;
    }

    public void addResourceEnvReferenceDescriptor
        (ResourceEnvReferenceDescriptor resourceEnvReference) {
        getResourceEnvReferenceContainer(
        ).addResourceEnvReferenceDescriptor(resourceEnvReference);
    }

    public ResourceEnvReferenceDescriptor getResourceEnvReference
        (String name) {
        ResourceEnvReferenceDescriptor resourceEnvRef = null;
        try {
            resourceEnvRef = getResourceEnvReferenceContainer().
                getResourceEnvReferenceByName(name);
            // annotation has a corresponding resource-env-ref
            // in xml.  Just add annotation info and continue.
            // This logic might change depending on overriding rules
            // and order in which annotations are read w.r.t. to xml.
            // E.g. sparse overriding in xml or loading annotations
            // first.
        } catch(IllegalArgumentException e) {
            // DOL API is (unfortunately) defined to return
            // IllegalStateException if name doesn't exist.

            Application app = getAppFromDescriptor();

            if( app != null ) {
                try {
                    // Check for java:app/java:global dependencies at app-level
                    resourceEnvRef = app.getResourceEnvReferenceByName(name);
                      // Make sure it's added to the container context.
                    addResourceEnvReferenceDescriptor(resourceEnvRef);
                } catch(IllegalArgumentException ee) {}
            }
        }
        return resourceEnvRef;
    }

    protected WritableJndiNameEnvironment
        getResourceEnvReferenceContainer()
    {
        return (WritableJndiNameEnvironment)descriptor;
    }

    public void addEnvEntryDescriptor(EnvironmentProperty envEntry) {

        getEnvEntryContainer().addEnvironmentProperty(envEntry);

    }

    public EnvironmentProperty getEnvEntry(String name) {
        EnvironmentProperty envEntry = null;
        try {
            envEntry = getEnvEntryContainer().
                getEnvironmentPropertyByName(name);
            // annotation has a corresponding env-entry
            // in xml.  Just add annotation info and continue.
            // This logic might change depending on overriding rules
            // and order in which annotations are read w.r.t. to xml.
            // E.g. sparse overriding in xml or loading annotations
            // first.
        } catch(IllegalArgumentException e) {
            // DOL API is (unfortunately) defined to return
            // IllegalStateException if name doesn't exist.

            Application app = getAppFromDescriptor();

            if( app != null ) {
                try {
                    // Check for java:app/java:global dependencies at app-level
                    envEntry = app.getEnvironmentPropertyByName(name);
                      // Make sure it's added to the container context.
                    addEnvEntryDescriptor(envEntry);
                } catch(IllegalArgumentException ee) {}
            }

        }
        return envEntry;

    }

    protected WritableJndiNameEnvironment getEnvEntryContainer()
    {
        return (WritableJndiNameEnvironment)descriptor;
    }

    public void addEntityManagerFactoryReferenceDescriptor
        (EntityManagerFactoryReferenceDescriptor emfRefDesc) {

        getEmfRefContainer().addEntityManagerFactoryReferenceDescriptor
            (emfRefDesc);

    }

    public EntityManagerFactoryReferenceDescriptor
        getEntityManagerFactoryReference(String name) {

        EntityManagerFactoryReferenceDescriptor emfRefDesc = null;

        try {
            emfRefDesc = getEmfRefContainer().
                getEntityManagerFactoryReferenceByName(name);
            // annotation has a corresponding entry
            // in xml.  Just add annotation info and continue.
            // This logic might change depending on overriding rules
            // and order in which annotations are read w.r.t. to xml.
            // E.g. sparse overriding in xml or loading annotations
            // first.
        } catch(IllegalArgumentException e) {
            // DOL API is (unfortunately) defined to return
            // IllegalStateException if name doesn't exist.

            Application app = getAppFromDescriptor();

            if( app != null ) {
                try {
                    // Check for java:app/java:global dependencies at app-level
                    emfRefDesc = app.getEntityManagerFactoryReferenceByName(name);
                    // Make sure it's added to the container context.
                    addEntityManagerFactoryReferenceDescriptor(emfRefDesc);
                } catch(IllegalArgumentException ee) {}
            }
        }

        return emfRefDesc;

    }

    protected WritableJndiNameEnvironment getEmfRefContainer()
    {
        return (WritableJndiNameEnvironment)descriptor;
    }


    public void addEntityManagerReferenceDescriptor
        (EntityManagerReferenceDescriptor emRefDesc) {

        getEmRefContainer().addEntityManagerReferenceDescriptor
            (emRefDesc);

    }

    public EntityManagerReferenceDescriptor
        getEntityManagerReference(String name) {

        EntityManagerReferenceDescriptor emRefDesc = null;

        try {
            emRefDesc = getEmRefContainer().
                getEntityManagerReferenceByName(name);
            // annotation has a corresponding entry
            // in xml.  Just add annotation info and continue.
            // This logic might change depending on overriding rules
            // and order in which annotations are read w.r.t. to xml.
            // E.g. sparse overriding in xml or loading annotations
            // first.
        } catch(IllegalArgumentException e) {
            // DOL API is (unfortunately) defined to return
            // IllegalStateException if name doesn't exist.

            Application app = getAppFromDescriptor();

            if( app != null ) {
                try {
                    // Check for java:app/java:global dependencies at app-level
                    emRefDesc = app.getEntityManagerReferenceByName(name);
                    // Make sure it's added to the container context.
                    addEntityManagerReferenceDescriptor(emRefDesc);
                } catch(IllegalArgumentException ee) {}
            }
        }

        return emRefDesc;

    }

    protected WritableJndiNameEnvironment getEmRefContainer()
    {
        return (WritableJndiNameEnvironment)descriptor;
    }

   /**
     * @param postConstructDesc
     */
    public void addPostConstructDescriptor(
            LifecycleCallbackDescriptor postConstructDesc) {
        getPostConstructContainer().addPostConstructDescriptor(postConstructDesc);
    }

    /**
     * Look up an post-construct LifecycleCallbackDescriptor with the
     * given name.  Return null if it is not found
     * @param className
     */
    public LifecycleCallbackDescriptor getPostConstruct(String className) {
        LifecycleCallbackDescriptor postConstructDesc =
            getPostConstructContainer().getPostConstructDescriptorByClass(className);
        return postConstructDesc;
    }

    protected WritableJndiNameEnvironment getPostConstructContainer() {
        return (WritableJndiNameEnvironment)descriptor;
    }

   /**
     * @param preDestroyDesc
     */
    public void addPreDestroyDescriptor(
            LifecycleCallbackDescriptor preDestroyDesc) {
        getPreDestroyContainer().addPreDestroyDescriptor(preDestroyDesc);
    }

    /**
     * Look up an pre-destroy LifecycleCallbackDescriptor with the
     * given name.  Return null if it is not found
     * @param className
     */
    public LifecycleCallbackDescriptor getPreDestroy(String className) {
        LifecycleCallbackDescriptor preDestroyDesc =
            getPreDestroyContainer().getPreDestroyDescriptorByClass(className);
        return preDestroyDesc;
    }

    protected WritableJndiNameEnvironment getDataSourceDefinitionContainer(){
        return (WritableJndiNameEnvironment)descriptor;
    }

    /**
     * Adds the descriptor to the receiver.
     * @param desc Descriptor to add.
     */
    public void addResourceDescriptor(ResourceDescriptor desc) {
        getDataSourceDefinitionContainer().addResourceDescriptor(desc);
    }

    /**
     * get all Descriptor descriptors based on the type
     * @return Descriptor descriptors
     */
    public Set<ResourceDescriptor> getResourceDescriptors(JavaEEResourceType type) {
        return getDataSourceDefinitionContainer().getResourceDescriptors(type);
    }


    protected WritableJndiNameEnvironment getMailSessionContainer() {
        return (WritableJndiNameEnvironment) descriptor;
    }

    protected WritableJndiNameEnvironment getConnectionFactoryDefinitionContainer(){
        return (WritableJndiNameEnvironment)descriptor;
    }

    protected WritableJndiNameEnvironment getAdministeredObjectDefinitionContainer(){
        return (WritableJndiNameEnvironment)descriptor;
    }

    protected WritableJndiNameEnvironment getJMSConnectionFactoryDefinitionContainer(){
        return (WritableJndiNameEnvironment)descriptor;
    }

    protected WritableJndiNameEnvironment getJMSDestinationDefinitionContainer(){
        return (WritableJndiNameEnvironment)descriptor;
    }

    protected WritableJndiNameEnvironment getPreDestroyContainer() {
        return (WritableJndiNameEnvironment)descriptor;
    }

    public String getComponentClassName() {
        return componentClassName;
    }

    public HandlerChainContainer[]
            getHandlerChainContainers(boolean serviceSideHandlerChain, Class declaringClass) {
        // by default return null; appropriate contextx should override this
        return null;
    }

    public ServiceReferenceContainer[] getServiceRefContainers() {
        // by default we return our descriptor;
        ServiceReferenceContainer[] containers = new ServiceReferenceContainer[1];
        containers[0] = (ServiceReferenceContainer) descriptor;
        return containers;
    }

    public void addManagedBean(ManagedBeanDescriptor managedBeanDesc) {

        BundleDescriptor bundleDesc = (BundleDescriptor)
                ((BundleDescriptor) descriptor).getModuleDescriptor().getDescriptor();
        bundleDesc.addManagedBean(managedBeanDesc);
    }

    public Application getAppFromDescriptor() {
        Application app = null;
        if( descriptor instanceof BundleDescriptor ) {
            BundleDescriptor bundle = (BundleDescriptor) descriptor;
            app = bundle.getApplication();
        } else if( descriptor instanceof EjbDescriptor ) {
            app = ((EjbDescriptor)descriptor).getApplication();
        }

        return app;
    }
}
