/*
 * Copyright (c) 2023 Eclipse Foundation and/or its affiliates. All rights reserved.
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

package com.sun.enterprise.deployment.test;

import org.glassfish.tests.utils.junit.HK2JUnit5Extension;
import org.junit.jupiter.api.extension.ExtensionContext;

/**
 * @author David Matejcek
 */
public class DolJunit5Extension extends HK2JUnit5Extension {

    @Override
    public void postProcessTestInstance(final Object testInstance, ExtensionContext context) throws Exception {
        super.postProcessTestInstance(testInstance, context);
    }
}
