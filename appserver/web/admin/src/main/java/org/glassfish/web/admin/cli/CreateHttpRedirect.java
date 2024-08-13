/*
 * Copyright (c) 2010, 2018 Oracle and/or its affiliates. All rights reserved.
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

package org.glassfish.web.admin.cli;

import com.sun.enterprise.config.serverbeans.Cluster;
import com.sun.enterprise.config.serverbeans.Config;
import com.sun.enterprise.config.serverbeans.Domain;
import com.sun.enterprise.config.serverbeans.Server;
import com.sun.enterprise.util.SystemPropertyConstants;

import jakarta.inject.Inject;
import jakarta.inject.Named;

import java.text.MessageFormat;
import java.util.ResourceBundle;

import org.glassfish.api.ActionReport;
import org.glassfish.api.I18n;
import org.glassfish.api.Param;
import org.glassfish.api.admin.AdminCommand;
import org.glassfish.api.admin.AdminCommandContext;
import org.glassfish.api.admin.ExecuteOn;
import org.glassfish.api.admin.RestEndpoint;
import org.glassfish.api.admin.RestEndpoints;
import org.glassfish.api.admin.RuntimeType;
import org.glassfish.api.admin.ServerEnvironment;
import org.glassfish.config.support.CommandTarget;
import org.glassfish.config.support.TargetType;
import org.glassfish.grizzly.config.dom.HttpRedirect;
import org.glassfish.grizzly.config.dom.Protocol;
import org.glassfish.grizzly.config.dom.Protocols;
import org.glassfish.hk2.api.PerLookup;
import org.glassfish.hk2.api.ServiceLocator;
import org.glassfish.internal.api.Target;
import org.glassfish.web.admin.LogFacade;
import org.jvnet.hk2.annotations.Service;
import org.jvnet.hk2.config.ConfigSupport;
import org.jvnet.hk2.config.SingleConfigCode;
import org.jvnet.hk2.config.TransactionFailure;


/**
 * <p>
 * Command to create <code>http-redirect</code> element as a child of the
 * <code>protocol</code> element.
 * </p>
 *
 * <p>
 * domain.xml example:
 * </p>
 * <pre>
 *     &lt;http-redirect port=&quot;8181&quot; secure=&quot;true&quot; /&gt;
 * </pre>
 *
 * @since 3.1
 */
@Service(name="create-http-redirect")
@PerLookup
@I18n("create.http.redirect")
@ExecuteOn({RuntimeType.DAS, RuntimeType.INSTANCE})
@TargetType({CommandTarget.DAS,CommandTarget.STANDALONE_INSTANCE,CommandTarget.CLUSTER,CommandTarget.CONFIG})
@RestEndpoints({
    @RestEndpoint(configBean=Cluster.class,
        opType=RestEndpoint.OpType.POST,
        path="create-http-redirect",
        description="create-http-redirect"),
    @RestEndpoint(configBean=Server.class,
        opType=RestEndpoint.OpType.POST,
        path="create-http-redirect",
        description="create-http-redirect")
})
public class CreateHttpRedirect implements AdminCommand {

    @Param(name = "protocolname", primary = true)
    String protocolName;

    @Param(name="redirect-port", optional=true)
    String port;

    @Param(name="secure-redirect", optional=true)
    String secure;

    @Param(name = "target", optional = true, defaultValue = SystemPropertyConstants.DEFAULT_SERVER_INSTANCE_NAME)
    String target;

    @Inject @Named(ServerEnvironment.DEFAULT_INSTANCE_NAME)
    Config config;

    @Inject
    ServiceLocator services;

    @Inject
    Domain domain;

    private static final ResourceBundle rb = LogFacade.getLogger().getResourceBundle();

    // ----------------------------------------------- Methods from AdminCommand


    @Override
    public void execute(AdminCommandContext context) {
        Target targetUtil = services.getService(Target.class);
        Config newConfig = targetUtil.getConfig(target);
        if (newConfig!=null) {
            config = newConfig;
        }
        final ActionReport report = context.getActionReport();
        // check for duplicates
        Protocols protocols = config.getNetworkConfig().getProtocols();
        Protocol protocol = null;
        for (Protocol p : protocols.getProtocol()) {
            if(protocolName.equals(p.getName())) {
                protocol = p;
            }
        }
        if (protocol == null) {
            report.setMessage(MessageFormat.format(rb.getString(LogFacade.CREATE_HTTP_FAIL_PROTOCOL_NOT_FOUND), protocolName));
            report.setActionExitCode(ActionReport.ExitCode.FAILURE);
            return;
        }
        if (protocol.getHttpRedirect() != null) {
            report.setMessage(MessageFormat.format(rb.getString(LogFacade.CREATE_HTTP_REDIRECT_FAIL_DUPLICATE), protocolName));
            report.setActionExitCode(ActionReport.ExitCode.FAILURE);
            return;
        }

        try {
            ConfigSupport.apply(new SingleConfigCode<Protocol>() {
                public Object run(Protocol param) throws TransactionFailure {
                    HttpRedirect httpRedirect = param.createChild(HttpRedirect.class);
                    httpRedirect.setPort(port);
                    httpRedirect.setSecure(secure);
                    param.setHttpRedirect(httpRedirect);
                    return httpRedirect;
                }
            }, protocol);
        } catch (TransactionFailure e) {
            report.setMessage(MessageFormat.format(rb.getString(LogFacade.CREATE_HTTP_REDIRECT_FAIL), protocolName) +
                    (e.getMessage() == null ? "No reason given." : e.getMessage()));
            report.setActionExitCode(ActionReport.ExitCode.FAILURE);
            report.setFailureCause(e);
            return;
        }
        report.setActionExitCode(ActionReport.ExitCode.SUCCESS);
    }
}
