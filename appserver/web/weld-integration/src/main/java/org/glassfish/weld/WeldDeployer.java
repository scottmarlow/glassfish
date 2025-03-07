/*
 * Copyright (c) 2021, 2022 Contributors to the Eclipse Foundation.
 * Copyright (c) 2009, 2020 Oracle and/or its affiliates. All rights reserved.
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

package org.glassfish.weld;

import static java.util.logging.Level.FINE;
import static java.util.logging.Level.WARNING;
import static org.glassfish.cdi.CDILoggerInfo.ADDING_INJECTION_SERVICES;
import static org.glassfish.cdi.CDILoggerInfo.JMS_MESSAGElISTENER_AVAILABLE;
import static org.glassfish.cdi.CDILoggerInfo.MDB_PIT_EVENT;
import static org.glassfish.cdi.CDILoggerInfo.WELD_BOOTSTRAP_SHUTDOWN_EXCEPTION;
import static org.glassfish.internal.deployment.Deployment.APPLICATION_DISABLED;
import static org.glassfish.internal.deployment.Deployment.APPLICATION_LOADED;
import static org.glassfish.internal.deployment.Deployment.APPLICATION_STOPPED;
import static org.glassfish.internal.deployment.Deployment.APPLICATION_UNLOADED;
import static org.glassfish.weld.connector.WeldUtils.BDAType.JAR;
import static org.glassfish.weld.connector.WeldUtils.BDAType.RAR;
import static org.glassfish.weld.connector.WeldUtils.BDAType.WAR;
import static org.jboss.weld.bootstrap.api.Environments.SERVLET;
import static org.jboss.weld.bootstrap.spi.EEModuleDescriptor.ModuleType.CONNECTOR;
import static org.jboss.weld.bootstrap.spi.EEModuleDescriptor.ModuleType.EJB_JAR;
import static org.jboss.weld.bootstrap.spi.EEModuleDescriptor.ModuleType.WEB;
import static org.jboss.weld.manager.BeanManagerLookupService.lookupBeanManager;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.logging.Logger;

import org.glassfish.api.deployment.DeployCommandParameters;
import org.glassfish.api.deployment.DeploymentContext;
import org.glassfish.api.deployment.MetaData;
import org.glassfish.api.deployment.archive.ReadableArchive;
import org.glassfish.api.event.EventListener;
import org.glassfish.api.event.Events;
import org.glassfish.api.invocation.ApplicationEnvironment;
import org.glassfish.api.invocation.InvocationManager;
import org.glassfish.cdi.CDILoggerInfo;
import org.glassfish.deployment.common.DeploymentException;
import org.glassfish.deployment.common.SimpleDeployer;
import org.glassfish.hk2.api.PostConstruct;
import org.glassfish.hk2.api.ServiceLocator;
import org.glassfish.internal.data.ApplicationInfo;
import org.glassfish.internal.data.ApplicationRegistry;
import org.glassfish.javaee.core.deployment.ApplicationHolder;
import org.glassfish.web.deployment.descriptor.AppListenerDescriptorImpl;
import org.glassfish.web.deployment.descriptor.ServletFilterDescriptor;
import org.glassfish.web.deployment.descriptor.ServletFilterMappingDescriptor;
import org.glassfish.weld.connector.WeldUtils;
import org.glassfish.weld.services.EjbServicesImpl;
import org.glassfish.weld.services.InjectionServicesImpl;
import org.glassfish.weld.services.NonModuleInjectionServices;
import org.glassfish.weld.services.ProxyServicesImpl;
import org.glassfish.weld.services.SecurityServicesImpl;
import org.glassfish.weld.services.TransactionServicesImpl;
import org.glassfish.weld.util.Util;
import org.jboss.weld.bootstrap.WeldBootstrap;
import org.jboss.weld.bootstrap.api.ServiceRegistry;
import org.jboss.weld.bootstrap.spi.BeanDeploymentArchive;
import org.jboss.weld.bootstrap.spi.BeanDiscoveryMode;
import org.jboss.weld.bootstrap.spi.EEModuleDescriptor;
import org.jboss.weld.bootstrap.spi.helpers.EEModuleDescriptorImpl;
import org.jboss.weld.ejb.spi.EjbServices;
import org.jboss.weld.injection.spi.InjectionServices;
import org.jboss.weld.manager.BeanManagerImpl;
import org.jboss.weld.module.EjbSupport;
import org.jboss.weld.resources.spi.ResourceLoader;
import org.jboss.weld.security.spi.SecurityServices;
import org.jboss.weld.serialization.spi.ProxyServices;
import org.jboss.weld.transaction.spi.TransactionServices;
import org.jvnet.hk2.annotations.Service;

import com.sun.enterprise.container.common.spi.util.InjectionManager;
import com.sun.enterprise.deploy.shared.ArchiveFactory;
import com.sun.enterprise.deployment.Application;
import com.sun.enterprise.deployment.BundleDescriptor;
import com.sun.enterprise.deployment.EjbBundleDescriptor;
import com.sun.enterprise.deployment.EjbDescriptor;
import com.sun.enterprise.deployment.WebBundleDescriptor;
import com.sun.enterprise.deployment.web.ServletFilterMapping;

import jakarta.enterprise.inject.spi.AnnotatedType;
import jakarta.enterprise.inject.spi.InjectionTarget;
import jakarta.inject.Inject;
import jakarta.servlet.Filter;
import jakarta.servlet.Servlet;
import jakarta.servlet.ServletContextListener;
import jakarta.servlet.ServletRequestListener;
import jakarta.servlet.http.HttpSessionListener;
import jakarta.servlet.jsp.tagext.JspTag;

@Service
public class WeldDeployer extends SimpleDeployer<WeldContainer, WeldApplicationContainer> implements PostConstruct, EventListener {

    private Logger logger = CDILoggerInfo.getLogger();

    public static final String WELD_EXTENSION = "org.glassfish.weld";
    public static final String WELD_DEPLOYMENT = "org.glassfish.weld.WeldDeployment";
    static final String WELD_BOOTSTRAP = "org.glassfish.weld.WeldBootstrap";
    private static final String WELD_CONTEXT_LISTENER = "org.glassfish.weld.WeldContextListener";

    // Note...this constant is also defined in org.apache.catalina.connector.AsyncContextImpl.  If it changes here it must
    // change there as well.  The reason it is duplicated is so that a dependency from web-core to gf-weld-connector
    // is not necessary.
    private static final String WELD_LISTENER = "org.jboss.weld.module.web.servlet.WeldListener";
    private static final String WELD_TERMINATION_LISTENER = "org.jboss.weld.module.web.servlet.WeldTerminalListener";
    private static final String WELD_SHUTDOWN = "weld_shutdown";

    // This constant is used to indicate if bootstrap shutdown has been called or not.
    private static final String WELD_BOOTSTRAP_SHUTDOWN = "weld_bootstrap_shutdown";
    private static final String WELD_CONVERSATION_FILTER_CLASS = "org.jboss.weld.module.web.servlet.ConversationFilter";
    private static final String WELD_CONVERSATION_FILTER_NAME = "CDI Conversation Filter";

    @Inject
    private Events events;

    @Inject
    private ServiceLocator services;

    @Inject
    private ApplicationRegistry applicationRegistry;

    @Inject
    private InvocationManager invocationManager;

    @Inject
    ArchiveFactory archiveFactory;

    private Map<Application, WeldBootstrap> appToBootstrap = new HashMap<>();

    private Map<BundleDescriptor, BeanDeploymentArchive> bundleToBeanDeploymentArchive = new HashMap<>();

    private static final Class<?>[] NON_CONTEXT_CLASSES = {
            Servlet.class,
            ServletContextListener.class,
            Filter.class,
            HttpSessionListener.class,
            ServletRequestListener.class,
            JspTag.class
            // TODO need to add more classes
    };

    static {
        try {
            Util.initializeWeldSingletonProvider();
        } catch (Throwable ignore) {
        }
    }

    @Override
    public MetaData getMetaData() {
        return new MetaData(true, null, new Class[] { Application.class });
    }

    @Override
    public void postConstruct() {
        events.register(this);
    }

    /**
     * Processing in this method is performed for each module that is in the process of being loaded by the container. This
     * method will collect information from each archive (module) and produce <code>BeanDeploymentArchive</code> information
     * for each module. The <code>BeanDeploymentArchive</code>s are stored in the <code>Deployment</code> (that will
     * eventually be handed off to <code>Weld</code>. Once this method is called for all modules (and
     * <code>BeanDeploymentArchive</code> information has been collected for all <code>Weld</code> modules), a relationship
     * structure is produced defining the accessiblity rules for the <code>BeanDeploymentArchive</code>s.
     */
    @Override
    public WeldApplicationContainer load(WeldContainer container, DeploymentContext context) {

        DeployCommandParameters deployParams = context.getCommandParameters(DeployCommandParameters.class);
        ApplicationInfo appInfo = applicationRegistry.get(deployParams.name);

        ReadableArchive archive = context.getSource();

        // See if a WeldBootsrap has already been created - only want one per app.

        WeldBootstrap bootstrap = context.getTransientAppMetaData(WELD_BOOTSTRAP, WeldBootstrap.class);
        if (bootstrap == null) {
            bootstrap = new WeldBootstrap();
            Application app = context.getModuleMetaData(Application.class);
            appToBootstrap.put(app, bootstrap);

            // Stash the WeldBootstrap instance, so we may access the WeldManager later..
            context.addTransientAppMetaData(WELD_BOOTSTRAP, bootstrap);
            appInfo.addTransientAppMetaData(WELD_BOOTSTRAP, bootstrap);

            // Making sure that if WeldBootstrap is added, shutdown is set to false, as it is/would not have been called.
            appInfo.addTransientAppMetaData(WELD_BOOTSTRAP_SHUTDOWN, "false");
        }

        EjbBundleDescriptor ejbBundle = getEjbBundleFromContext(context);

        EjbServices ejbServices = null;

        Set<EjbDescriptor> ejbs = new HashSet<>();
        if (ejbBundle != null) {
            ejbs.addAll(ejbBundle.getEjbs());
            ejbServices = new EjbServicesImpl(services);
        }

        // Create a Deployment Collecting Information From The ReadableArchive (archive)
        DeploymentImpl deploymentImpl = context.getTransientAppMetaData(WELD_DEPLOYMENT, DeploymentImpl.class);
        if (deploymentImpl == null) {
            deploymentImpl = new DeploymentImpl(archive, ejbs, context, archiveFactory);

            // Add services
            deploymentImpl.getServices().add(TransactionServices.class, new TransactionServicesImpl(services));
            deploymentImpl.getServices().add(SecurityServices.class, new SecurityServicesImpl());
            deploymentImpl.getServices().add(ProxyServices.class, new ProxyServicesImpl(services));

            addWeldListenerToAllWars(context);
        } else {
            deploymentImpl.scanArchive(archive, ejbs, context);
        }
        deploymentImpl.addDeployedEjbs(ejbs);

        if (ejbBundle != null && !deploymentImpl.getServices().contains(EjbServices.class)) {
            // EJB Services is registered as a top-level service
            deploymentImpl.getServices().add(EjbServices.class, ejbServices);
        }

        BeanDeploymentArchive beanDeploymentArchive = deploymentImpl.getBeanDeploymentArchiveForArchive(archive.getName());
        if (beanDeploymentArchive != null && !beanDeploymentArchive.getBeansXml().getBeanDiscoveryMode().equals(BeanDiscoveryMode.NONE)) {

            WebBundleDescriptor webBundleDescriptor = context.getModuleMetaData(WebBundleDescriptor.class);
            if (webBundleDescriptor != null) {
                webBundleDescriptor.setExtensionProperty(WELD_EXTENSION, "true");

                // Add the Weld Listener.  We have to do it here too in case addWeldListenerToAllWars wasn't
                // able to do it.
                webBundleDescriptor.addAppListenerDescriptorToFirst(new AppListenerDescriptorImpl(WELD_LISTENER));

                // Add Weld Context Listener - this listener will ensure the WeldELContextListener is used
                // for JSP's..
                webBundleDescriptor.addAppListenerDescriptor(new AppListenerDescriptorImpl(WELD_CONTEXT_LISTENER));

                // Weld 2.2.1.Final.  There is a tck test for this: org.jboss.cdi.tck.tests.context.session.listener.SessionContextHttpSessionListenerTest
                // This WeldTerminationListener must come after all application-defined listeners
                webBundleDescriptor.addAppListenerDescriptor(new AppListenerDescriptorImpl(WELD_TERMINATION_LISTENER));

                // Adding Weld ConverstationFilter if there is filterMapping for it and it doesn't exist already.
                // However, it will be applied only if web.xml has mapping for it.
                // Doing this here to make sure that its done only for CDI enabled web application
                for (ServletFilterMapping filterMapping : webBundleDescriptor.getServletFilterMappings()) {
                    String displayName = ((ServletFilterMappingDescriptor) filterMapping).getDisplayName();
                    if (WELD_CONVERSATION_FILTER_NAME.equals(displayName)) {
                        ServletFilterDescriptor filterDescriptor = new ServletFilterDescriptor();
                        filterDescriptor.setClassName(WELD_CONVERSATION_FILTER_CLASS);
                        filterDescriptor.setName(WELD_CONVERSATION_FILTER_NAME);
                        webBundleDescriptor.addServletFilter(filterDescriptor);
                        break;
                    }
                }
            }

            BundleDescriptor bundle = webBundleDescriptor != null ? webBundleDescriptor : ejbBundle;
            if (bundle != null) {

                if (!beanDeploymentArchive.getBeansXml().getBeanDiscoveryMode().equals(BeanDiscoveryMode.NONE)) {
                    // Register EE injection manager at the bean deployment archive level.
                    // We use the generic InjectionService service to handle all EE-style
                    // injection instead of the per-dependency-type InjectionPoint approach.
                    // Each InjectionServicesImpl instance knows its associated GlassFish bundle.

                    InjectionManager injectionManager = services.getService(InjectionManager.class);
                    InjectionServices injectionServices = new InjectionServicesImpl(injectionManager, bundle, deploymentImpl);

                    if (logger.isLoggable(FINE)) {
                        logger.log(FINE, ADDING_INJECTION_SERVICES, new Object[] { injectionServices, beanDeploymentArchive.getId() });
                    }

                    beanDeploymentArchive.getServices().add(InjectionServices.class, injectionServices);
                    EEModuleDescriptor eeModuleDescriptor = getEEModuleDescriptor(beanDeploymentArchive);
                    if (eeModuleDescriptor != null) {
                        beanDeploymentArchive.getServices().add(EEModuleDescriptor.class, eeModuleDescriptor);
                    }

                    // Relevant in WAR BDA - WEB-INF/lib BDA scenarios
                    for (BeanDeploymentArchive subBeanDeploymentArchive : beanDeploymentArchive.getBeanDeploymentArchives()) {
                        if (logger.isLoggable(FINE)) {
                            logger.log(FINE, ADDING_INJECTION_SERVICES, new Object[] { injectionServices, subBeanDeploymentArchive.getId() });
                        }

                        subBeanDeploymentArchive.getServices().add(InjectionServices.class, injectionServices);
                        eeModuleDescriptor = getEEModuleDescriptor(beanDeploymentArchive);
                        if (eeModuleDescriptor != null) {
                            beanDeploymentArchive.getServices().add(EEModuleDescriptor.class, eeModuleDescriptor);
                        }
                    }
                }

                bundleToBeanDeploymentArchive.put(bundle, beanDeploymentArchive);
            }
        }

        context.addTransientAppMetaData(WELD_DEPLOYMENT, deploymentImpl);
        appInfo.addTransientAppMetaData(WELD_DEPLOYMENT, deploymentImpl);

        return new WeldApplicationContainer();
    }


    /**
     * Specific stages of the Weld bootstrapping process will execute across different stages of the deployment process.
     * Weld deployment will happen when the load phase of the deployment process is complete. When all modules have been
     * loaded, a deployment graph is produced defining the accessibility relationships between
     * <code>BeanDeploymentArchive</code>s.
     */
    @Override
    public void event(Event event) {
        if (event.is(APPLICATION_LOADED)) {
            ApplicationInfo appInfo = (ApplicationInfo) event.hook();
            WeldBootstrap bootstrap = appInfo.getTransientAppMetaData(WELD_BOOTSTRAP, WeldBootstrap.class);
            if (bootstrap != null) {
                DeploymentImpl deploymentImpl = appInfo.getTransientAppMetaData(WELD_DEPLOYMENT, DeploymentImpl.class);

                deploymentImpl.buildDeploymentGraph();

                List<BeanDeploymentArchive> archives = deploymentImpl.getBeanDeploymentArchives();
                for (BeanDeploymentArchive archive : archives) {
                    ResourceLoaderImpl loader = new ResourceLoaderImpl(((BeanDeploymentArchiveImpl) archive).getModuleClassLoaderForBDA());
                    archive.getServices().add(ResourceLoader.class, loader);
                }

                addCdiServicesToNonModuleBdas(deploymentImpl.getLibJarRootBdas(), services.getService(InjectionManager.class));
                addCdiServicesToNonModuleBdas(deploymentImpl.getRarRootBdas(), services.getService(InjectionManager.class));

                // Get Current TCL
                ClassLoader oldTCL = Thread.currentThread().getContextClassLoader();

                invocationManager.pushAppEnvironment(new ApplicationEnvironment() {
                    @Override
                    public String getName() {
                        return appInfo.getName();
                    }
                });

                try {
                    bootstrap.startExtensions(deploymentImpl.getExtensions());
                    bootstrap.startContainer(appInfo.getName(), SERVLET, deploymentImpl);

                    // Install support for delegating some EJB tasks to the right bean archive.
                    // Without this, when the a bean manager for the root bean archive is used, it will not
                    // find the EJB definitions in a sub-archive, and will treat the bean as a normal CDI bean.
                    //
                    // For EJB beans a few special rules have to be taken into account, and without applying these
                    // rules CreateBeanAttributesTest#testBeanAttributesForSessionBean fails.
                    if (!deploymentImpl.getBeanDeploymentArchives().isEmpty()) {

                        BeanDeploymentArchive rootArchive = deploymentImpl.getBeanDeploymentArchives().get(0);
                        ServiceRegistry rootServices = bootstrap.getManager(rootArchive).getServices();

                        EjbSupport originalEjbSupport = rootServices.get(EjbSupport.class);
                        if (originalEjbSupport != null) {

                            // We need to create a proxy instead of a simple wrapper, since EjbSupport
                            // references the type "EnhancedAnnotatedType", which the Weld OSGi bundle doesn't
                            // export.
                            EjbSupport proxyEjbSupport = (EjbSupport) Proxy.newProxyInstance(EjbSupport.class.getClassLoader(),
                                    new Class[] { EjbSupport.class }, new InvocationHandler() {

                                        @Override
                                        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
                                            if (method.getName().equals("isEjb")) {

                                                    EjbSupport targetEjbSupport = getTargetEjbSupport((Class<?>) args[0]);

                                                    // Unlikely to be null, but let's check to be sure.
                                                    if (targetEjbSupport != null) {
                                                        return method.invoke(targetEjbSupport, args);
                                                    }

                                            } else if (method.getName().equals("createSessionBeanAttributes")) {
                                                Object enhancedAnnotated = args[0];

                                                Class<?> beanClass = (Class<?>)
                                                        enhancedAnnotated.getClass()
                                                                         .getMethod("getJavaClass")
                                                                         .invoke(enhancedAnnotated);

                                                EjbSupport targetEjbSupport = getTargetEjbSupport(beanClass);
                                                if (targetEjbSupport != null) {
                                                    return method.invoke(targetEjbSupport, args);
                                                }
                                            }

                                            return method.invoke(originalEjbSupport, args);
                                       }

                                    private EjbSupport getTargetEjbSupport(Class<?> beanClass) {
                                        BeanDeploymentArchive ejbArchive = deploymentImpl.getBeanDeploymentArchive(beanClass);
                                        if (ejbArchive == null) {
                                            return null;
                                        }

                                        BeanManagerImpl ejbBeanManager = lookupBeanManager(beanClass, bootstrap.getManager(ejbArchive));

                                        return ejbBeanManager.getServices().get(EjbSupport.class);
                                    }
                            });

                            rootServices.add(EjbSupport.class, proxyEjbSupport);
                        }
                    }

                    bootstrap.startInitialization();
                    fireProcessInjectionTargetEvents(bootstrap, deploymentImpl);
                    bootstrap.deployBeans();

                    bootstrap.validateBeans();
                    bootstrap.endInitialization();
                } catch (Throwable t) {
                    try {
                        doBootstrapShutdown(appInfo);
                    } finally {
                        // ignore.
                    }

                    String msgPrefix = getDeploymentErrorMsgPrefix(t);
                    DeploymentException deploymentException = new DeploymentException(msgPrefix + t.getMessage());
                    deploymentException.initCause(t);
                    throw deploymentException;
                } finally {
                    invocationManager.popAppEnvironment();

                    // The TCL is originally the EAR classloader and is reset during Bean deployment to the
                    // corresponding module classloader in BeanDeploymentArchiveImpl.getBeans
                    // for Bean classloading to succeed.
                    //
                    // The TCL is reset to its old value here.
                    Thread.currentThread().setContextClassLoader(oldTCL);
                    deploymentComplete(deploymentImpl);
                }
            }
        } else if (event.is(APPLICATION_STOPPED)
                || event.is(APPLICATION_UNLOADED)
                || event.is(APPLICATION_DISABLED)) {
            ApplicationInfo appInfo = (ApplicationInfo) event.hook();
            Application app = appInfo.getMetaData(Application.class);

            if (app != null) {
                for (BundleDescriptor next : app.getBundleDescriptors()) {
                    if (next instanceof EjbBundleDescriptor || next instanceof WebBundleDescriptor) {
                        bundleToBeanDeploymentArchive.remove(next);
                    }
                }

                appToBootstrap.remove(app);
            }

            String shutdown = appInfo.getTransientAppMetaData(WELD_SHUTDOWN, String.class);
            if (Boolean.valueOf(shutdown).equals(Boolean.TRUE)) {
                return;
            }

            ClassLoader currentContextClassLoader = Thread.currentThread().getContextClassLoader();
            Thread.currentThread().setContextClassLoader(appInfo.getAppClassLoader());
            try {
                WeldBootstrap bootstrap = appInfo.getTransientAppMetaData(WELD_BOOTSTRAP, WeldBootstrap.class);
                if (bootstrap != null) {
                    invocationManager.pushAppEnvironment(new ApplicationEnvironment() {
                        @Override
                        public String getName() {
                            return appInfo.getName();
                        }
                    });

                    try {
                        doBootstrapShutdown(appInfo);
                    } catch (Exception e) {
                        logger.log(WARNING, WELD_BOOTSTRAP_SHUTDOWN_EXCEPTION, new Object[] { e });
                    } finally {
                        invocationManager.popAppEnvironment();
                    }
                    appInfo.addTransientAppMetaData(WELD_SHUTDOWN, "true");
                }
            } finally {
                Thread.currentThread().setContextClassLoader(currentContextClassLoader);
            }

            DeploymentImpl deploymentImpl = appInfo.getTransientAppMetaData(WELD_DEPLOYMENT, DeploymentImpl.class);
            if (deploymentImpl != null) {
                deploymentImpl.cleanup();
            }
        }
    }

    @Override
    protected void generateArtifacts(DeploymentContext dc) throws DeploymentException {

    }

    @Override
    protected void cleanArtifacts(DeploymentContext dc) throws DeploymentException {

    }

    @Override
    public <V> V loadMetaData(Class<V> type, DeploymentContext context) {
        return null;
    }




    // ### Private methods

    private void deploymentComplete(DeploymentImpl deploymentImpl) {
        for (BeanDeploymentArchive oneBda : deploymentImpl.getBeanDeploymentArchives()) {
            ((BeanDeploymentArchiveImpl) oneBda).setDeploymentComplete(true);
        }
    }

    private void doBootstrapShutdown(ApplicationInfo appInfo) {
        WeldBootstrap bootstrap = appInfo.getTransientAppMetaData(WELD_BOOTSTRAP, WeldBootstrap.class);
        String bootstrapShutdown = appInfo.getTransientAppMetaData(WELD_BOOTSTRAP_SHUTDOWN, String.class);
        if (bootstrapShutdown == null || Boolean.valueOf(bootstrapShutdown).equals(Boolean.FALSE)) {
            bootstrap.shutdown();
            appInfo.addTransientAppMetaData(WELD_BOOTSTRAP_SHUTDOWN, "true");
        }
    }

    private String getDeploymentErrorMsgPrefix(Throwable t) {
        if (t instanceof jakarta.enterprise.inject.spi.DefinitionException) {
            return "CDI definition failure:";
        }

        if (t instanceof jakarta.enterprise.inject.spi.DeploymentException) {
            return "CDI deployment failure:";
        }

        Throwable cause = t.getCause();
        if (cause == t || cause == null) {
            return "CDI deployment failure:";
        }

        return getDeploymentErrorMsgPrefix(cause);
    }

    /*
     * We are only firing ProcessInjectionTarget<X> for non-contextual EE
     * components and not using the InjectionTarget<X> from the event during
     * instance creation in CDIServiceImpl.java
     * TODO weld would provide a better way to do this, otherwise we may need
     * TODO to store InjectionTarget<X> to be used in instance creation
     */
    private void fireProcessInjectionTargetEvents(WeldBootstrap bootstrap, DeploymentImpl impl) {
        List<BeanDeploymentArchive> bdaList = impl.getBeanDeploymentArchives();
        boolean isFullProfile = false;
        Class<?> messageListenerClass = null;

        // Web-Profile and other lighter distributions would not ship the JMS
        // API and implementations. So, the weld-integration layer cannot
        // have a direct dependency on the JMS API
        try {
            messageListenerClass = Thread.currentThread().getContextClassLoader().loadClass("jakarta.jms.MessageListener");
            if (logger.isLoggable(FINE)) {
                logger.log(FINE, JMS_MESSAGElISTENER_AVAILABLE);
            }
            isFullProfile = true;
        } catch (ClassNotFoundException cnfe) {
            //ignore cnfe
            isFullProfile = false;
        }

        for (BeanDeploymentArchive bda : bdaList) {
            Collection<Class<?>> bdaClasses = ((BeanDeploymentArchiveImpl) bda).getBeanClassObjects();
            for (Class<?> bdaClazz : bdaClasses) {
                for (Class<?> nonClazz : NON_CONTEXT_CLASSES) {
                    if (nonClazz.isAssignableFrom(bdaClazz)) {
                        firePITEvent(bootstrap, bda, bdaClazz);
                    }
                }

                // For distributions that have the JMS API, an MDB is a valid
                // non-contextual EE component to which we have to fire ProcessInjectionTarget
                // events (see GLASSFISH-16730)
                if (isFullProfile) {
                    if (messageListenerClass.isAssignableFrom(bdaClazz)) {
                        if (logger.isLoggable(FINE)) {
                            logger.log(FINE, MDB_PIT_EVENT, new Object[] { bdaClazz });
                        }
                        firePITEvent(bootstrap, bda, bdaClazz);
                    }
                }
            }
        }
    }

    private void firePITEvent(WeldBootstrap bootstrap, BeanDeploymentArchive bda, Class<?> bdaClazz) {
        // Fix for issue GLASSFISH-17464
        // The PIT event should not be fired for interfaces
        if (bdaClazz.isInterface()) {
            return;
        }

        AnnotatedType<?> annotatedType = bootstrap.getManager(bda).createAnnotatedType(bdaClazz);
        InjectionTarget<?> injectionTarget = bootstrap.getManager(bda).fireProcessInjectionTarget(annotatedType);

        ((BeanDeploymentArchiveImpl) bda).putInjectionTarget(annotatedType, injectionTarget);
    }

    public BeanDeploymentArchive getBeanDeploymentArchiveForBundle(BundleDescriptor bundle) {
        return bundleToBeanDeploymentArchive.get(bundle);
    }

    public boolean isCdiEnabled(BundleDescriptor bundle) {
        return bundleToBeanDeploymentArchive.containsKey(bundle);
    }

    public WeldBootstrap getBootstrapForApp(Application app) {
        return appToBootstrap.get(app);
    }

    private EEModuleDescriptor getEEModuleDescriptor(BeanDeploymentArchive beanDeploymentArchive) {
        if (!(beanDeploymentArchive instanceof BeanDeploymentArchiveImpl)) {
            return null;
        }

        WeldUtils.BDAType bdaType = ((BeanDeploymentArchiveImpl) beanDeploymentArchive).getBDAType();
        if (bdaType.equals(JAR)) {
            return new EEModuleDescriptorImpl(beanDeploymentArchive.getId(), EJB_JAR);
        }

        if (bdaType.equals(WAR)) {
            return new EEModuleDescriptorImpl(beanDeploymentArchive.getId(), WEB);
        }

        if (bdaType.equals(RAR)) {
            return new EEModuleDescriptorImpl(beanDeploymentArchive.getId(), CONNECTOR);
        }

        return null;
    }

    private void addWeldListenerToAllWars(DeploymentContext context) {
        // If there's at least 1 ejb jar then add the listener to all wars
        ApplicationHolder applicationHolder = context.getModuleMetaData(ApplicationHolder.class);
        if (applicationHolder != null) {
            if (applicationHolder.app.getBundleDescriptors(EjbBundleDescriptor.class).size() > 0) {
                Set<WebBundleDescriptor> webBundleDescriptors = applicationHolder.app.getBundleDescriptors(WebBundleDescriptor.class);
                for (WebBundleDescriptor oneWebBundleDescriptor : webBundleDescriptors) {
                    // Add the Weld Listener if it does not already exist..
                    // we have to do this regardless because the war may not be cdi-enabled but an ejb is.
                    oneWebBundleDescriptor.addAppListenerDescriptorToFirst(new AppListenerDescriptorImpl(WELD_LISTENER));
                    oneWebBundleDescriptor.addAppListenerDescriptor(new AppListenerDescriptorImpl(WELD_TERMINATION_LISTENER));
                }
            }
        }
    }

    private EjbBundleDescriptor getEjbBundleFromContext(DeploymentContext context) {
        EjbBundleDescriptor ejbBundle = context.getModuleMetaData(EjbBundleDescriptor.class);
        if (ejbBundle != null) {
            return ejbBundle;
        }

        WebBundleDescriptor webBundleDescriptor = context.getModuleMetaData(WebBundleDescriptor.class);
        if (webBundleDescriptor == null) {
            return null;
        }

        Collection<EjbBundleDescriptor> ejbBundles = webBundleDescriptor.getExtensionsDescriptors(EjbBundleDescriptor.class);
        if (ejbBundles.iterator().hasNext()) {
            ejbBundle = ejbBundles.iterator().next();
        }

        return ejbBundle;
    }

    /**
     * Add the cdi services to a non-module bda (library or rar)
     */
    private void addCdiServicesToNonModuleBdas(Iterator<RootBeanDeploymentArchive> rootBdas, InjectionManager injectionMgr) {
        if (injectionMgr != null && rootBdas != null) {
            while (rootBdas.hasNext()) {
                RootBeanDeploymentArchive oneRootBda = rootBdas.next();
                addCdiServicesToBda(injectionMgr, oneRootBda);
                addCdiServicesToBda(injectionMgr, oneRootBda.getModuleBda());
            }
        }
    }

    private void addCdiServicesToBda(InjectionManager injectionManager, BeanDeploymentArchive beanDeploymentArchive) {
        beanDeploymentArchive.getServices().add(InjectionServices.class, new NonModuleInjectionServices(injectionManager));
    }

}
