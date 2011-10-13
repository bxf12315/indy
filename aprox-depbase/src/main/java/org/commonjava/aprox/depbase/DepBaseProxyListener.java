package org.commonjava.aprox.depbase;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import javax.enterprise.event.Observes;
import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Singleton;

import org.apache.maven.model.Model;
import org.apache.maven.model.building.DefaultModelBuildingRequest;
import org.apache.maven.model.building.ModelBuilder;
import org.apache.maven.model.building.ModelBuildingException;
import org.apache.maven.model.building.ModelBuildingRequest;
import org.apache.maven.model.building.ModelBuildingResult;
import org.commonjava.aprox.core.change.event.FileStorageEvent;
import org.commonjava.aprox.core.change.event.FileStorageEvent.Type;
import org.commonjava.aprox.core.data.ProxyDataException;
import org.commonjava.aprox.core.data.ProxyDataManager;
import org.commonjava.aprox.core.model.ArtifactStore;
import org.commonjava.aprox.core.model.Group;
import org.commonjava.aprox.core.rest.util.FileManager;
import org.commonjava.aprox.depbase.maven.ArtifactStoreModelResolver;
import org.commonjava.depbase.data.DepbaseDataException;
import org.commonjava.depbase.util.MavenModelProcessor;
import org.commonjava.util.logging.Logger;

@Singleton
public class DepBaseProxyListener
{

    private final Logger logger = new Logger( getClass() );

    @Inject
    private ProxyDataManager aprox;

    @Inject
    private FileManager fileManager;

    @Inject
    @Named( "MAE" )
    private ModelBuilder modelBuilder;

    @Inject
    private MavenModelProcessor modelProcessor;

    public void handleFileEvent( @Observes final FileStorageEvent event )
    {
        if ( Type.GENERATE == event.getType() )
        {
            return;
        }

        ArtifactStore originatingStore = event.getStore();
        List<ArtifactStore> stores = getRelevantStores( originatingStore );
        if ( stores == null )
        {
            return;
        }

        Model model = loadModel( event, stores );
        if ( model == null )
        {
            return;
        }

        try
        {
            modelProcessor.storeModelRelationships( model );
        }
        catch ( DepbaseDataException e )
        {
            logger.error( "Failed to store relationships for POM: %s. Reason: %s", e,
                          model.getId(), e.getMessage() );
        }
    }

    protected Model loadModel( final FileStorageEvent event, final List<ArtifactStore> stores )
    {
        ModelBuildingRequest request = new DefaultModelBuildingRequest();
        request.setPomFile( new File( event.getStorageLocation() ) );
        request.setModelResolver( new ArtifactStoreModelResolver( fileManager, stores ) );

        ModelBuildingResult result = null;
        try
        {
            result = modelBuilder.build( request );
        }
        catch ( ModelBuildingException e )
        {
            logger.error( "Cannot build model instance for POM: %s. Reason: %s", e,
                          event.getPath(), e.getMessage() );
        }

        if ( result == null )
        {
            return null;
        }

        return result.getEffectiveModel();
    }

    protected List<ArtifactStore> getRelevantStores( final ArtifactStore originatingStore )
    {
        List<ArtifactStore> stores = new ArrayList<ArtifactStore>();
        stores.add( originatingStore );

        try
        {
            Set<Group> groups = aprox.getGroupsContaining( originatingStore.getKey() );
            for ( Group group : groups )
            {
                if ( group == null )
                {
                    continue;
                }

                List<ArtifactStore> orderedStores =
                    aprox.getOrderedConcreteStoresInGroup( group.getName() );
                if ( orderedStores != null )
                {
                    for ( ArtifactStore as : orderedStores )
                    {
                        if ( as == null || stores.contains( as ) )
                        {
                            continue;
                        }

                        stores.add( as );
                    }
                }
            }
        }
        catch ( ProxyDataException e )
        {
            logger.error( "Cannot lookup full store list for groups containing artifact store: %s. Reason: %s",
                          e, originatingStore.getKey(), e.getMessage() );
            stores = null;
        }

        return stores;
    }
}
