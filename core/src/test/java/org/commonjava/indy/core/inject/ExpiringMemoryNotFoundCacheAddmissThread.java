package org.commonjava.indy.core.inject;

import org.commonjava.maven.galley.model.ConcreteResource;

import java.util.Date;

public class ExpiringMemoryNotFoundCacheAddmissThread
                implements Runnable
{
    ExpiringMemoryNotFoundCache cache;

    ConcreteResource res;

    public ExpiringMemoryNotFoundCacheAddmissThread( ExpiringMemoryNotFoundCache cache, ConcreteResource res )
    {
        this.res = res;
        this.cache = cache;
    }

    public void setRes( ConcreteResource res )
    {
        this.res = res;
    }

    public void setCache( ExpiringMemoryNotFoundCache cache )
    {
        this.cache = cache;
    }

    @Override
    public void run()
    {
//        Date date = new Date(  );
//        System.out.println( "ExpiringMemoryNotFoundCacheAddmissThread is runnung , time :"+date.toLocaleString() );
        cache.addMissing( res );
    }
}