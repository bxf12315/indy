/**
 * Copyright (C) 2011 Red Hat, Inc. (jdcasey@commonjava.org)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *         http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.commonjava.indy.core.inject;

import static org.hamcrest.CoreMatchers.equalTo;
import static org.junit.Assert.assertThat;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import org.commonjava.indy.conf.DefaultIndyConfiguration;
import org.commonjava.maven.galley.model.ConcreteResource;
import org.commonjava.maven.galley.model.Location;
import org.commonjava.maven.galley.model.SimpleLocation;
import org.jboss.byteman.contrib.bmunit.BMRule;
import org.jboss.byteman.contrib.bmunit.BMRules;
import org.jboss.byteman.contrib.bmunit.BMUnitConfig;
import org.jboss.byteman.contrib.bmunit.BMUnitRunner;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(BMUnitRunner.class)
@BMUnitConfig( debug = true )
public class ExpiringMemoryNotFoundCacheTest
{

    @Test
    public void expireUsingConfiguredValue()
        throws Exception
    {
        final DefaultIndyConfiguration config = new DefaultIndyConfiguration();
        config.setNotFoundCacheTimeoutSeconds( 1 );

        final ExpiringMemoryNotFoundCache nfc = new ExpiringMemoryNotFoundCache( config );

        final ConcreteResource res = new ConcreteResource( new SimpleLocation( "test:uri" ), "/path/to/expired/object" );

        nfc.addMissing( res );
        assertThat( nfc.isMissing( res ), equalTo( true ) );

        Thread.sleep( TimeUnit.SECONDS.toMillis( 1 ) );

        assertThat( nfc.isMissing( res ), equalTo( false ) );

        final Set<String> locMissing = nfc.getMissing( res.getLocation() );
        assertThat( locMissing == null || locMissing.isEmpty(), equalTo( true ) );

        final Map<Location, Set<String>> allMissing = nfc.getAllMissing();
        assertThat( allMissing == null || allMissing.isEmpty(), equalTo( true ) );
    }

    @Test
    @BMRules( rules = {
                    @BMRule( name = "ThreadOne is running", targetClass =
                                    "org.commonjava.indy.core.inject.ExpiringMemoryNotFoundCache",
                                    targetMethod = "addMissing(org.commonjava.maven.galley.model.ConcreteResource)",
                                    targetLocation = "ENTRY",
                                    action = "debug(\"befor one\"); "
                                                    + "waitFor(\"waiting thread tow\");  "
                                                    + "debug(\"end for one!\")" ),
                    @BMRule( name = "ThreadTwo is running", targetClass =
                                    "org.commonjava.indy.core.inject.ExpiringMemoryNotFoundCache",
                                    targetMethod = "clearAllMissing", targetLocation = "ENTRY",
                                    action = " debug(\"before two\"); "
                                                    + "signalWake(\"waiting thread tow\", true); "
                                                    + "debug(\"end for two!\")" ) } )
    public void nfcConcurentModificationExceptionTest() throws InterruptedException
    {
        final DefaultIndyConfiguration config = new DefaultIndyConfiguration();
        config.setNotFoundCacheTimeoutSeconds( 1 );

        final ExpiringMemoryNotFoundCache nfc = new ExpiringMemoryNotFoundCache( config );

        final ConcreteResource res =
                        new ConcreteResource( new SimpleLocation( "test:uri" ), "/path/to/expired/object" );

        ExpiringMemoryNotFoundCacheAddmissThread expiringMemoryNotFoundCacheAddmissThread =
                        new ExpiringMemoryNotFoundCacheAddmissThread( nfc, res );
        ExpiringMemoryNotFoundCacheClearThread expiringMemoryNotFoundCacheClearThread =
                        new ExpiringMemoryNotFoundCacheClearThread( nfc );
        new Thread( expiringMemoryNotFoundCacheAddmissThread ).start();
        Thread.sleep( 100 );
        new Thread( expiringMemoryNotFoundCacheClearThread ).start();

        Thread.sleep( 800 );
    }

    @Test
    public void expireUsingConfiguredValue_DirectCheckDoesntAffectAggregateChecks()
        throws Exception
    {
        final DefaultIndyConfiguration config = new DefaultIndyConfiguration();
        config.setNotFoundCacheTimeoutSeconds( 1 );

        final ExpiringMemoryNotFoundCache nfc = new ExpiringMemoryNotFoundCache( config );

        final ConcreteResource res = new ConcreteResource( new SimpleLocation( "test:uri" ), "/path/to/expired/object" );

        nfc.addMissing( res );
        assertThat( nfc.isMissing( res ), equalTo( true ) );

        Thread.sleep( TimeUnit.SECONDS.toMillis( 1 ) );

        Set<String> locMissing = nfc.getMissing( res.getLocation() );
        assertThat( locMissing == null || locMissing.isEmpty(), equalTo( true ) );

        Map<Location, Set<String>> allMissing = nfc.getAllMissing();
        assertThat( allMissing == null || allMissing.isEmpty(), equalTo( true ) );

        assertThat( nfc.isMissing( res ), equalTo( false ) );

        locMissing = nfc.getMissing( res.getLocation() );
        assertThat( locMissing == null || locMissing.isEmpty(), equalTo( true ) );

        allMissing = nfc.getAllMissing();
        assertThat( allMissing == null || allMissing.isEmpty(), equalTo( true ) );
    }

}
