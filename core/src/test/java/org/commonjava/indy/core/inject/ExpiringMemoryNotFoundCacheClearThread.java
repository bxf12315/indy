package org.commonjava.indy.core.inject;

import java.util.Date;

public class ExpiringMemoryNotFoundCacheClearThread
                implements Runnable
{
    ExpiringMemoryNotFoundCache cache;

    public ExpiringMemoryNotFoundCacheClearThread( ExpiringMemoryNotFoundCache cache )
    {
        this.cache = cache;
    }

    public void setCache( ExpiringMemoryNotFoundCache cache )
    {
        this.cache = cache;
    }

    @Override
    public void run()
    {
//        Date date = new Date(  );
//        System.out.println( "ExpiringMemoryNotFoundCacheClearThread is runnung , time :"+date.toLocaleString() );
        cache.clearAllMissing();
    }
}
