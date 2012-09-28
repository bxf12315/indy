package org.commonjava.aprox.infinispan.data.weld;

import java.net.URL;

import javax.enterprise.inject.Default;
import javax.enterprise.inject.Produces;
import javax.inject.Singleton;

import org.commonjava.aprox.conf.AproxConfigFactory;
import org.commonjava.aprox.inject.TestData;
import org.commonjava.aprox.subsys.infinispan.conf.CacheConfiguration;
import org.commonjava.web.config.ConfigurationException;

@Singleton
public class WeldProvider
{

    @Produces
    public AproxConfigFactory getFactory()
    {
        return new AproxConfigFactory()
        {
            @Override
            public <T> T getConfiguration( final Class<T> configCls )
                throws ConfigurationException
            {
                return null;
            }
        };
    }

    @Produces
    @TestData
    @Default
    public CacheConfiguration getCacheConfig()
    {
        final URL resource = Thread.currentThread()
                                   .getContextClassLoader()
                                   .getResource( "infinispan.xml" );
        return new CacheConfiguration( resource.getPath() );
    }
}