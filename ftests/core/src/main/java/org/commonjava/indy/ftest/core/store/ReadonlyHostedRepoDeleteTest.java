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
package org.commonjava.indy.ftest.core.store;

import org.commonjava.indy.client.core.IndyClientException;
import org.commonjava.indy.model.core.HostedRepository;
import org.commonjava.indy.model.core.StoreType;
import org.commonjava.indy.util.ApplicationStatus;
import org.junit.Test;

import static org.hamcrest.CoreMatchers.equalTo;
import static org.junit.Assert.assertThat;

public class ReadonlyHostedRepoDeleteTest
    extends AbstractStoreManagementTest
{

    @Test
    public void addReadonlyHostedAndDelete()
        throws Exception
    {
        final HostedRepository repo = new HostedRepository( newName() );
        repo.setReadonly( true );
        final HostedRepository result = client.stores()
                                              .create( repo, name.getMethodName(), HostedRepository.class );

        assertThat( result.getName(), equalTo( repo.getName() ) );
        assertThat( result.equals( repo ), equalTo( true ) );

        try
        {
            client.stores()
                  .delete( StoreType.hosted, repo.getName(), name.getMethodName() );
        }
        catch ( IndyClientException e )
        {
            assertThat( e.getStatusCode(), equalTo( ApplicationStatus.METHOD_NOT_ALLOWED.code() ) );
        }

        assertThat( client.stores()
                          .exists( StoreType.hosted, repo.getName() ), equalTo( true ) );

        result.setReadonly( false );

        client.stores().update( result, name.getMethodName() );

        client.stores()
              .delete( StoreType.hosted, repo.getName(), name.getMethodName() );

        assertThat( client.stores()
                          .exists( StoreType.hosted, repo.getName() ), equalTo( false ) );

    }

}
