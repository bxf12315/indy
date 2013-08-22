package org.commonjava.aprox.filer;

import javax.enterprise.context.ApplicationScoped;
import javax.enterprise.inject.Default;

import org.commonjava.aprox.model.StoreKey;
import org.commonjava.aprox.model.galley.KeyedLocation;
import org.commonjava.maven.galley.model.Location;
import org.commonjava.maven.galley.spi.io.PathGenerator;

@Default
@ApplicationScoped
public class KeyBasedPathGenerator
    implements PathGenerator
{

    @Override
    public String getFilePath( final Location loc, final String path )
    {
        final KeyedLocation kl = (KeyedLocation) loc;
        final StoreKey key = kl.getKey();

        final String name = key.getType()
                               .name() + "-" + key.getName();

        return PathUtils.join( name, path );
    }

}