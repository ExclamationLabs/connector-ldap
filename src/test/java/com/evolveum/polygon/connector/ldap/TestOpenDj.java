/*
 * Copyright (c) 2022 Evolveum
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

package com.evolveum.polygon.connector.ldap;

import org.identityconnectors.framework.api.ConnectorFacade;
import org.identityconnectors.framework.common.objects.Schema;
import org.testng.annotations.Test;

public class TestOpenDj extends AbstractOpenDjTest {

    @Test
    public void testOpTest() throws Exception {
        ConnectorFacade connector = createConnectorInstance();
        connector.test();
        // No exception = no problem
    }

    @Test
    public void testOpSchema() throws Exception {
        ConnectorFacade connector = createConnectorInstance();
        Schema schema = connector.schema();
        // TODO: asserts
    }

}