/*
 * Copyright (c) 2022 Contributors to the Eclipse Foundation
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

package org.glassfish.main.admin.test.rest;

import jakarta.ws.rs.core.MultivaluedHashMap;
import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.core.Response;

import java.util.List;
import java.util.Map;

import org.glassfish.admin.rest.client.utils.MarshallingUtils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.hamcrest.CoreMatchers.hasItem;
import static org.hamcrest.CoreMatchers.hasItems;
import static org.hamcrest.CoreMatchers.not;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.hasSize;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * @author jasonlee
 */
public class JvmOptionsITest extends RestTestBase {
    private static final String URL_SERVER_JVM_OPTIONS = "/domain/configs/config/server-config/java-config/jvm-options";
    private static final String URL_DEFAULT_JVM_OPTIONS = "/domain/configs/config/default-config/java-config/jvm-options";

    private static final String URL_SERVER_CONFIG_CREATE_PROFILER = "/domain/configs/config/server-config/java-config/create-profiler";
    private static final String URL_SERVER_CONFIG_DELETE_PROFILER = "/domain/configs/config/server-config/java-config/profiler/delete-profiler";
    private static final String URL_SERVER_CONFIG_PROFILER_JVM_OPTIONS = "/domain/configs/config/server-config/java-config/profiler/jvm-options";

    private static final String URL_DEFAULT_CONFIG_CREATE_PROFILER = "/domain/configs/config/default-config/java-config/create-profiler";
    private static final String URL_DEFAULT_CONFIG_DELETE_PROFILER = "/domain/configs/config/default-config/java-config/profiler/delete-profiler";
    private static final String URL_DEFAULT_CONFIG_PROFILER_JVM_OPTIONS = "/domain/configs/config/default-config/java-config/profiler/jvm-options";

    private static RestTestBase configManagement;
    private String testConfigName;
    private String testConfigUrl;
    private String testConfigJvmOptionsUrl;

    @BeforeAll
    public static void initConfig() {
        configManagement = new RestTestBase();
    }

    @BeforeEach
    public void createConfig() {
        testConfigName = "config-" + generateRandomString();
        MultivaluedMap<String, String> formData = new MultivaluedHashMap<>() {{
            add("id", "default-config");
            add("id", testConfigName);
        }};
        configManagement.createAndVerifyConfig(testConfigName, formData);
        testConfigUrl = "/domain/configs/config/" + testConfigName;
        testConfigJvmOptionsUrl = testConfigUrl + "/java-config/jvm-options";
    }

    @AfterEach
    public void deleteConfig() {
        configManagement.deleteAndVerifyConfig(testConfigName);
    }


    @Test
    public void getJvmOptions() {
        Response response = managementClient.get(URL_SERVER_JVM_OPTIONS);
        assertEquals(200, response.getStatus());
        Map<String, ?> responseMap = MarshallingUtils.buildMapFromDocument(response.readEntity(String.class));
        List<String> jvmOptions = (List<String>)((Map)responseMap.get("extraProperties")).get("leafList");
        assertThat(jvmOptions, hasSize(greaterThan(10)));
    }

    @Test
    public void createAndDeleteOptions() {
        final String option1Name = "-Doption" + generateRandomString();
        Map<String, String> newOptions = Map.of(option1Name, "someValue");

        Response response = managementClient.post(testConfigJvmOptionsUrl, newOptions);
        assertEquals(200, response.getStatus());
        response = managementClient.get(testConfigJvmOptionsUrl);
        List<String> jvmOptions = getJvmOptions(response);
        assertThat(jvmOptions, hasItem(option1Name + "=someValue"));

        response = managementClient.delete(testConfigJvmOptionsUrl, newOptions);
        assertEquals(200, response.getStatus());
        response = managementClient.get(testConfigJvmOptionsUrl);
        jvmOptions = getJvmOptions(response);
        assertThat(jvmOptions, not(hasItem(option1Name + "=someValue")));
    }

    @Test
    public void createAndDeleteOptionsWithBackslashes() {
        final String optionName = "-Dfile" + generateRandomString();
        final String optionValue = "C:\\ABC\\DEF\\";
        Map<String, String> newOptions = Map.of(optionName, escape(optionValue));

        Response response = managementClient.post(testConfigJvmOptionsUrl, newOptions);
        assertEquals(200, response.getStatus());
        response = managementClient.get(testConfigJvmOptionsUrl);
        List<String> jvmOptions = getJvmOptions(response);
        assertThat(jvmOptions, hasItem(optionName + "=" + optionValue));

        response = managementClient.delete(testConfigJvmOptionsUrl, newOptions);
        assertEquals(200, response.getStatus());
        response = managementClient.get(testConfigJvmOptionsUrl);
        jvmOptions = getJvmOptions(response);
        assertThat(jvmOptions, not(hasItem(optionName + "=" + optionValue)));
    }

    @Test
    public void createAndDeleteOptionsWithoutValues() {
        final String option1Name = "-Doption" + generateRandomString();
        final String option2Name = "-Doption" + generateRandomString();
        Map<String, String> newOptions = Map.of(option1Name, "", option2Name, "");
        Response response = managementClient.post(testConfigJvmOptionsUrl, newOptions);
        assertEquals(200, response.getStatus());
        response = managementClient.get(testConfigJvmOptionsUrl);
        List<String> jvmOptions = getJvmOptions(response);
        assertThat(jvmOptions, hasItems(option2Name, option2Name));
        assertThat(jvmOptions, not(hasItems(option1Name + '=', option2Name + '=')));

        response = managementClient.delete(testConfigJvmOptionsUrl, newOptions);
        assertEquals(200, response.getStatus());
        response = managementClient.get(testConfigJvmOptionsUrl);
        jvmOptions = getJvmOptions(response);
        assertThat(jvmOptions, allOf(not(hasItem(option1Name)), not(hasItem(option2Name))));
    }

    @Test
    public void testIsolatedOptionsCreationOnNewConfig() {
        final String optionName = "-Doption" + generateRandomString();
        Map<String, String> newOptions = Map.of(optionName, "", "target", testConfigName);

        // Test new config to make sure option is there
        Response response = managementClient.post(testConfigJvmOptionsUrl, newOptions);
        assertEquals(200, response.getStatus());
        response = managementClient.get(testConfigJvmOptionsUrl);
        List<String> jvmOptions = getJvmOptions(response);
        assertThat(jvmOptions, hasItem(optionName));

        // Test server-config to make sure the options are NOT there
        response = managementClient.get(URL_SERVER_JVM_OPTIONS);
        jvmOptions = getJvmOptions(response);
        assertThat(jvmOptions, not(hasItem(optionName)));
    }

    @Test
    public void testProfilerJvmOptions() {
        final String profilerName = "profiler" + generateRandomString();
        final String optionName = "-Doption" + generateRandomString();
        Map<String, String> attrs = Map.of("name", profilerName, "target", testConfigName);
        Map<String, String> newOptions = Map.of(optionName, "");
        deleteProfiler(testConfigUrl + "/java-config/profiler/delete-profiler", testConfigName, false);

        Response response = managementClient.post(testConfigUrl + "/java-config/create-profiler", attrs);
        assertEquals(200, response.getStatus());

        response = managementClient.post(testConfigUrl + "/java-config/profiler/jvm-options", newOptions);
        assertEquals(200, response.getStatus());

        response = managementClient.get(testConfigUrl + "/java-config/profiler/jvm-options");
        List<String> jvmOptions = getJvmOptions(response);
        assertThat(jvmOptions, hasItem(optionName));

        deleteProfiler(testConfigUrl + "/java-config/profiler/delete-profiler", testConfigName, true);
    }

    @Test
    public void testJvmOptionWithColon() {
        final String optionName = "-XX:MaxPermSize";
        final String optionValue = "152m";
        Map<String, String> newOptions = Map.of(escape(optionName), optionValue);

        Response response = managementClient.post(testConfigJvmOptionsUrl, newOptions);
        assertEquals(200, response.getStatus());
        response = managementClient.get(testConfigJvmOptionsUrl);
        assertEquals(200, response.getStatus());
        List<String> jvmOptions = getJvmOptions(response);
        assertThat(jvmOptions, hasItem(optionName + '=' + optionValue));

        response = managementClient.delete(testConfigJvmOptionsUrl, newOptions);
        assertEquals(200, response.getStatus());
        response = managementClient.get(testConfigJvmOptionsUrl);
        jvmOptions = getJvmOptions(response);
        assertThat(jvmOptions, not(hasItem(optionName + '=' + optionValue)));
    }

    private void deleteProfiler(final String url, final String target, final boolean failOnError) {
        Response response = managementClient.delete(url, Map.of("target", target));
        if (failOnError) {
            assertEquals(200, response.getStatus());
        }
    }

    private List<String> getJvmOptions(Response response) {
        Map<String, ?> responseMap = MarshallingUtils.buildMapFromDocument(response.readEntity(String.class));
        List<String> jvmOptions = (List<String>)((Map)responseMap.get("extraProperties")).get("leafList");
        return jvmOptions;
    }

    private String escape(String part) {
        String changed = part
                .replace("\\", "\\\\")
                .replace(":", "\\:");
        return changed;
    }
}
