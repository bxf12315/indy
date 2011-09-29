package org.commonjava.web.maven.proxy.model.io;

import java.lang.reflect.Type;

import org.commonjava.couch.io.json.SerializationAdapter;
import org.commonjava.web.maven.proxy.model.StoreKey;

import com.google.gson.JsonDeserializationContext;
import com.google.gson.JsonDeserializer;
import com.google.gson.JsonElement;
import com.google.gson.JsonParseException;
import com.google.gson.JsonPrimitive;
import com.google.gson.JsonSerializationContext;
import com.google.gson.JsonSerializer;

public class StoreKeySerializer
    implements SerializationAdapter, JsonSerializer<StoreKey>, JsonDeserializer<StoreKey>
{

    @Override
    public Type typeLiteral()
    {
        return StoreKey.class;
    }

    @Override
    public StoreKey deserialize( final JsonElement json, final Type typeOfT,
                                 final JsonDeserializationContext context )
        throws JsonParseException
    {
        String id = json.getAsString();
        return StoreKey.fromString( id );
    }

    @Override
    public JsonElement serialize( final StoreKey src, final Type typeOfSrc,
                                  final JsonSerializationContext context )
    {
        return new JsonPrimitive( src.toString() );
    }

    @Override
    public int hashCode()
    {
        return 47;
    }

    @Override
    public boolean equals( final Object obj )
    {
        if ( this == obj )
        {
            return true;
        }
        if ( obj == null )
        {
            return false;
        }
        if ( getClass() != obj.getClass() )
        {
            return false;
        }
        return true;
    }

}