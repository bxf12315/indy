/*******************************************************************************
 * Copyright (c) 2014 Red Hat, Inc..
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the GNU Public License v3.0
 * which accompanies this distribution, and is available at
 * http://www.gnu.org/licenses/gpl.html
 * 
 * Contributors:
 *     Red Hat, Inc. - initial API and implementation
 ******************************************************************************/
package org.commonjava.aprox.depgraph.dto;

import java.util.HashSet;
import java.util.Set;
import java.util.TreeSet;

import org.commonjava.aprox.data.ProxyDataException;
import org.commonjava.aprox.data.StoreDataManager;
import org.commonjava.aprox.model.ArtifactStore;
import org.commonjava.aprox.model.StoreKey;
import org.commonjava.aprox.util.LocationUtils;
import org.commonjava.maven.cartographer.dto.RepositoryContentRecipe;
import org.commonjava.maven.galley.TransferException;
import org.commonjava.maven.galley.model.Location;
import org.commonjava.maven.galley.spi.transport.LocationExpander;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class WebOperationConfigDTO
    extends RepositoryContentRecipe
{

    private StoreKey source;

    private Set<StoreKey> excludedSources;

    private Boolean localUrls;

    public StoreKey getSource()
    {
        return source;
    }

    public void setSource( final StoreKey source )
    {
        this.source = source;
    }

    public Boolean getLocalUrls()
    {
        return localUrls == null ? false : localUrls;
    }

    public void setLocalUrls( final Boolean localUrls )
    {
        this.localUrls = localUrls;
    }

    public Set<StoreKey> getExcludedSources()
    {
        return excludedSources;
    }

    public void setExcludedSources( final Set<StoreKey> excludedSources )
    {
        this.excludedSources = new TreeSet<StoreKey>( excludedSources );
    }

    public void calculateLocations( final LocationExpander locationExpander, final StoreDataManager dataManager )
        throws TransferException
    {
        final Logger logger = LoggerFactory.getLogger( getClass() );
        if ( source != null )
        {
            ArtifactStore store;
            try
            {
                store = dataManager.getArtifactStore( source );
            }
            catch ( final ProxyDataException e )
            {
                throw new TransferException( "Cannot find ArtifactStore to match source key: %s. Reason: %s", e,
                                             source, e.getMessage() );
            }

            if ( store == null )
            {
                throw new TransferException( "Cannot find ArtifactStore to match source key: %s.", source );
            }

            setSourceLocation( LocationUtils.toLocation( store ) );
            logger.debug( "Set sourceLocation to: '{}'", getSourceLocation() );
        }

        if ( excludedSources != null )
        {
            final Set<Location> excluded = new HashSet<Location>();
            for ( final StoreKey key : excludedSources )
            {
                if ( key == null )
                {
                    continue;
                }

                ArtifactStore store;
                try
                {
                    store = dataManager.getArtifactStore( key );
                }
                catch ( final ProxyDataException e )
                {
                    throw new TransferException( "Cannot find ArtifactStore to match excluded key: %s. Reason: %s", e,
                                                 key,
                                                 e.getMessage() );
                }

                if ( store == null )
                {
                    throw new TransferException( "Cannot find ArtifactStore to match exclude key: %s.", key );
                }

                final Location loc = LocationUtils.toLocation( store );
                excluded.add( loc );
                excluded.addAll( locationExpander.expand( loc ) );
            }

            setExcludedSourceLocations( excluded );
        }
    }

}
