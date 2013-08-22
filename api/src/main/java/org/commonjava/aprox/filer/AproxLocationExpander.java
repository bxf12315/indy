package org.commonjava.aprox.filer;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;

import javax.enterprise.context.ApplicationScoped;
import javax.inject.Inject;

import org.commonjava.aprox.data.ProxyDataException;
import org.commonjava.aprox.data.StoreDataManager;
import org.commonjava.aprox.model.ArtifactStore;
import org.commonjava.aprox.model.galley.GroupLocation;
import org.commonjava.aprox.util.LocationUtils;
import org.commonjava.maven.galley.TransferException;
import org.commonjava.maven.galley.model.Location;
import org.commonjava.maven.galley.spi.transport.LocationExpander;

@ApplicationScoped
public class AproxLocationExpander
    implements LocationExpander
{

    @Inject
    private StoreDataManager data;

    @Override
    public List<Location> expand( final Location... locations )
        throws TransferException
    {
        return expand( Arrays.asList( locations ) );
    }

    @Override
    public <T extends Location> List<Location> expand( final Collection<T> locations )
        throws TransferException
    {
        final List<Location> result = new ArrayList<>();
        for ( final Location location : locations )
        {
            if ( location instanceof GroupLocation )
            {
                final GroupLocation gl = (GroupLocation) location;
                try
                {
                    final List<ArtifactStore> members = data.getOrderedConcreteStoresInGroup( gl.getKey()
                                                                                                .getName() );
                    if ( members != null )
                    {
                        result.addAll( LocationUtils.toLocations( members ) );
                    }
                }
                catch ( final ProxyDataException e )
                {
                    throw new TransferException(
                                                 "Failed to lookup ordered concrete artifact stores contained in group: %s. Reason: %s",
                                                 e, gl, e.getMessage() );
                }
            }
            else
            {
                result.add( location );
            }
        }

        return result;
    }

}