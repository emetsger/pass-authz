/*
 * Copyright 2017 Johns Hopkins University
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.dataconservancy.pass.authz;

import static org.dataconservancy.pass.authz.AuthRolesProvider.getAuthRoleURI;
import static org.dataconservancy.pass.authz.ShibAuthUserProvider.EMPLOYEE_ID;
import static org.dataconservancy.pass.authz.ShibAuthUserProvider.EPPN_HEADER;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

import java.net.URI;
import java.util.Arrays;
import java.util.UUID;

import org.fcrepo.client.FcrepoClient.FcrepoClientBuilder;

import org.dataconservancy.pass.client.PassClient;
import org.dataconservancy.pass.client.PassClientFactory;
import org.dataconservancy.pass.model.Grant;
import org.dataconservancy.pass.model.User;
import org.dataconservancy.pass.model.User.Role;

import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpHead;
import org.apache.http.client.methods.HttpPut;
import org.apache.http.impl.client.CloseableHttpClient;
import org.junit.BeforeClass;
import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * @author apb@jhu.edu
 */
public class PassAuthzIT extends FcrepoIT {

    Logger LOG = LoggerFactory.getLogger(PassAuthzIT.class);

    PassClient client = PassClientFactory.getPassClient();

    ACLManager acls = new ACLManager(new FcrepoClientBuilder().credentials("fedoraAdmin", "moo").build());

    static CloseableHttpClient http = getHttpClient();

    static CloseableHttpClient userHttp = getAuthClient("admin", "moo");

    @BeforeClass
    public static void addAclContainer() throws Exception {
        final HttpPut put = new HttpPut(FCREPO_BASE_URI + System.getProperty("acl.base", "acls"));
        final HttpHead head = new HttpHead(put.getURI());

        final int code = http.execute(head, r -> {
            return r.getStatusLine().getStatusCode();
        });

        if (code == 404) {
            http.execute(put, r -> {
                assertSuccess(r);
                return URI.create(r.getFirstHeader("Location").getValue());
            });
        }
    }

    // Permissions granted to a User that matches the shib EPPN should work.
    @Test
    public void userBasedPermissionIT() throws Exception {

        final User user = new User();
        user.setLocalKey(UUID.randomUUID().toString());

        final URI userUri = client.createResource(user);

        final Grant g = new Grant();
        g.setAwardNumber(UUID.randomUUID().toString());

        final URI resourceToProtect = client.createResource(g);

        acls.addPermissions(resourceToProtect)
                .grantRead(Arrays.asList(URI.create("test:nobody")))
                .grantWrite(Arrays.asList(URI.create("test:nobody")))
                .perform();

        // This will wait until we have a lookup in the index
        assertEquals(userUri, attempt(30, () -> {
            final URI found = client.findByAttribute(User.class, "localKey", user.getLocalKey());
            assertNotNull(found);
            return found;
        }));

        final HttpGet fakeShibGet = new HttpGet(resourceToProtect);
        fakeShibGet.setHeader(EMPLOYEE_ID, user.getLocalKey());

        userHttp.execute(fakeShibGet, r -> {
            assertEquals(403, r.getStatusLine().getStatusCode());
            return null;
        });

        LOG.debug("Granting permissions to User <{}> on grant <{}>", userUri,
                resourceToProtect);

        acls.addPermissions(resourceToProtect)
                .grantRead(Arrays.asList(userUri))
                .grantWrite(Arrays.asList(userUri))
                .perform();

        userHttp.execute(fakeShibGet, r -> {
            assertSuccess(r);
            return null;
        });
    }

    // Permissions granted to a user's role should work, assuming the institution matches.
    @Test
    public void roleBasedPermissionIT() throws Exception {

        final String DOMAIN = "bovidae.edu";
        final String USER_NAME = "4stomachs@" + DOMAIN;
        final String LOCAL_KEY = UUID.randomUUID().toString();

        final URI authzRole = getAuthRoleURI(DOMAIN, Role.SUBMITTER);

        final User user = new User();
        user.setLocalKey(LOCAL_KEY);
        user.setRoles(Arrays.asList(Role.SUBMITTER));

        final URI userUri = client.createResource(user);

        final Grant g = new Grant();
        g.setAwardNumber(UUID.randomUUID().toString());

        final URI resourceToProtect = client.createResource(g);

        acls.addPermissions(resourceToProtect)
                .grantRead(Arrays.asList(URI.create("test:nobody")))
                .grantWrite(Arrays.asList(URI.create("test:nobody")))
                .perform();

        // This will wait until we have a lookup in the index
        assertEquals(userUri, attempt(30, () -> {
            final URI found = client.findByAttribute(User.class, "localKey", user.getLocalKey());
            assertNotNull(found);
            return found;
        }));

        final HttpGet fakeShibGet = new HttpGet(resourceToProtect);
        fakeShibGet.setHeader(EMPLOYEE_ID, user.getLocalKey());
        fakeShibGet.setHeader(EPPN_HEADER, USER_NAME);

        // Should fail, as the user has no permissions at all
        userHttp.execute(fakeShibGet, r -> {
            assertEquals(403, r.getStatusLine().getStatusCode());
            return null;
        });

        LOG.debug("Granting permissions to Role <{}> on grant <{}>", authzRole,
                resourceToProtect);

        acls.addPermissions(resourceToProtect)
                .grantRead(Arrays.asList(authzRole))
                .grantWrite(Arrays.asList(authzRole))
                .perform();

        // Now it should work
        userHttp.execute(fakeShibGet, r -> {
            assertSuccess(r);
            return null;
        });
    }
}
