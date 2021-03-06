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
package org.commonjava.indy.subsys.infinispan;

import org.apache.commons.io.FileUtils;
import org.apache.commons.io.IOUtils;
import org.codehaus.plexus.interpolation.InterpolationException;
import org.codehaus.plexus.interpolation.PropertiesBasedValueSource;
import org.codehaus.plexus.interpolation.StringSearchInterpolator;
import org.commonjava.indy.action.IndyLifecycleException;
import org.commonjava.indy.action.ShutdownAction;
import org.commonjava.indy.conf.IndyConfiguration;
import org.infinispan.Cache;
import org.infinispan.commons.marshall.MarshallableTypeHints;
import org.infinispan.configuration.cache.Configuration;
import org.infinispan.manager.DefaultCacheManager;
import org.infinispan.manager.EmbeddedCacheManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.PostConstruct;
import javax.enterprise.context.ApplicationScoped;
import javax.inject.Inject;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Created by jdcasey on 3/8/16.
 */
@ApplicationScoped
public class CacheProducer
        implements ShutdownAction
{
    private static final String ISPN_XML = "infinispan.xml";

    private EmbeddedCacheManager cacheManager;

    @Inject
    private IndyConfiguration indyConfiguration;

    private Map<String, CacheHandle> caches = new ConcurrentHashMap<>();

    protected CacheProducer()
    {
    }

    @PostConstruct
    public void start()
    {
        // FIXME This is just here to trigger shutdown hook init for embedded log4j in infinispan-embedded-query.
        // FIXES:
        //
        // Thread-15 ERROR Unable to register shutdown hook because JVM is shutting down.
        // java.lang.IllegalStateException: Cannot add new shutdown hook as this is not started. Current state: STOPPED
        //
        new MarshallableTypeHints().getBufferSizePredictor( CacheHandle.class );

        File confDir = indyConfiguration.getIndyConfDir();
        File ispnConf = new File( confDir, ISPN_XML );

        String configuration;
        if ( ispnConf.exists() )
        {
            try
            {
                configuration = FileUtils.readFileToString( ispnConf );
            }
            catch ( IOException e )
            {
                throw new RuntimeException( "Cannot read infinispan configuration from file: " + ispnConf, e );
            }
        }
        else
        {
            try (InputStream stream = Thread.currentThread().getContextClassLoader().getResourceAsStream( ISPN_XML ))
            {
                configuration = IOUtils.toString( stream );
            }
            catch ( IOException e )
            {
                throw new RuntimeException( "Cannot read infinispan configuration from classpath: " + ISPN_XML, e );
            }
        }

        StringSearchInterpolator interpolator = new StringSearchInterpolator();
        interpolator.addValueSource( new PropertiesBasedValueSource( System.getProperties() ) );

        try
        {
            configuration = interpolator.interpolate( configuration );
        }
        catch ( InterpolationException e )
        {
            throw new RuntimeException( "Cannot resolve expressions in infinispan configuration.", e );
        }

        Logger logger = LoggerFactory.getLogger( getClass() );
        logger.info( "Using Infinispan configuration:\n\n{}\n\n", configuration );

        try
        {
            cacheManager = new DefaultCacheManager(
                    new ByteArrayInputStream( configuration.getBytes( StandardCharsets.UTF_8 ) ) );
        }
        catch ( IOException e )
        {
            throw new RuntimeException( "Cannot read infinispan configuration.", e );
        }
    }

    /**
     * Retrieve a cache with a pre-defined configuration (from infinispan.xml) or using the default cache config.
     */
    public synchronized <K,V> CacheHandle<K,V> getCache(String named, Class<K> keyClass, Class<V> valueClass )
    {
        if ( cacheManager == null )
        {
            throw new IllegalStateException( "Cannot access CacheManager. Indy seems to be in a state of shutdown." );
        }

        CacheHandle<K, V> handle = caches.get( named );
        if ( handle == null )
        {
            Cache<K, V> cache = cacheManager.getCache( named );
            handle = new CacheHandle( named, cache );
            caches.put( named, handle );
        }
        return handle;
    }

    public synchronized Configuration getCacheConfiguration( String name )
    {
        if ( cacheManager == null )
        {
            throw new IllegalStateException( "Cannot access CacheManager. Indy seems to be in a state of shutdown." );
        }
        return cacheManager.getCacheConfiguration( name );
    }

    public synchronized Configuration getDefaultCacheConfiguration()
    {
        if ( cacheManager == null )
        {
            throw new IllegalStateException( "Cannot access CacheManager. Indy seems to be in a state of shutdown." );
        }

        return cacheManager.getDefaultCacheConfiguration();
    }

    public synchronized Configuration setCacheConfiguration( String name, Configuration config )
    {
        if ( cacheManager == null )
        {
            throw new IllegalStateException( "Cannot access CacheManager. Indy seems to be in a state of shutdown." );
        }
        return cacheManager.defineConfiguration( name, config );
    }

    @Override
    public synchronized void stop()
            throws IndyLifecycleException
    {
        caches.forEach( ( name, cacheHandle ) -> cacheHandle.stop() );

        if ( cacheManager != null )
        {
            Logger logger = LoggerFactory.getLogger( getClass() );
            logger.info( "Stopping Infinispan caches." );
            cacheManager.stop();
            cacheManager = null;
        }
    }

    @Override
    public int getShutdownPriority()
    {
        return 10;
    }

    @Override
    public String getId()
    {
        return "infinispan-caches";
    }
}
