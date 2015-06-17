package org.commonjava.aprox.httprox;

import static org.hamcrest.CoreMatchers.equalTo;
import static org.hamcrest.CoreMatchers.notNullValue;
import static org.junit.Assert.assertThat;

import java.io.InputStream;
import java.util.Collection;
import java.util.Collections;
import java.util.Map;
import java.util.Set;

import org.apache.commons.io.IOUtils;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.impl.client.CloseableHttpClient;
import org.commonjava.aprox.client.core.AproxClientModule;
import org.commonjava.aprox.client.core.helper.HttpResources;
import org.commonjava.aprox.folo.client.AproxFoloAdminClientModule;
import org.commonjava.aprox.folo.model.AffectedStoreRecord;
import org.commonjava.aprox.folo.model.TrackedContentRecord;
import org.commonjava.aprox.model.core.StoreKey;
import org.commonjava.aprox.model.core.StoreType;
import org.junit.Test;

public class RetrievedPomInTrackingReportTest
    extends AbstractHttproxFunctionalTest
{

    private static final String TID = "user";

    private static final String USER = TID + "+tracking";

    private static final String PASS = "password";

    @Test
    public void run()
        throws Exception
    {
        final String testRepo = "test";
        final PomRef pom = loadPom( "simple.pom", Collections.<String, String> emptyMap() );
        final String url = server.formatUrl( testRepo, pom.path );
        server.expect( url, 200, pom.pom );

        final HttpGet get = new HttpGet( url );
        final CloseableHttpClient client = proxiedHttp();
        CloseableHttpResponse response = null;

        InputStream stream = null;
        try
        {
            response = client.execute( get, proxyContext( USER, PASS ) );
            stream = response.getEntity()
                             .getContent();
            final String resultingPom = IOUtils.toString( stream );

            assertThat( resultingPom, notNullValue() );
            assertThat( resultingPom, equalTo( pom.pom ) );
        }
        finally
        {
            IOUtils.closeQuietly( stream );
            HttpResources.cleanupResources( get, response, client );
        }

        final String repoName = "httprox_127-0-0-1";
        final TrackedContentRecord record = this.client.module( AproxFoloAdminClientModule.class )
                                                       .getRawTrackingRecord( TID );
        assertThat( record, notNullValue() );

        final Map<StoreKey, AffectedStoreRecord> affectedStores = record.getAffectedStores();
        assertThat( affectedStores, notNullValue() );
        assertThat( affectedStores.size(), equalTo( 1 ) );

        final AffectedStoreRecord storeRecord = affectedStores.get( new StoreKey( StoreType.remote, repoName ) );
        assertThat( storeRecord, notNullValue() );

        final Set<String> downloads = storeRecord.getDownloadedPaths();
        assertThat( downloads, notNullValue() );
        assertThat( downloads.size(), equalTo( 1 ) );
        assertThat( downloads.iterator()
                             .next(), equalTo( "/test/" + pom.path ) );
    }

    @Override
    protected Collection<AproxClientModule> getAdditionalClientModules()
    {
        return Collections.<AproxClientModule> singleton( new AproxFoloAdminClientModule() );
    }

}