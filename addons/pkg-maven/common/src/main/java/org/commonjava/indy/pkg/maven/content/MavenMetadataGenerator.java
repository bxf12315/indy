/**
 * Copyright (C) 2011-2017 Red Hat, Inc. (https://github.com/Commonjava/indy)
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
package org.commonjava.indy.pkg.maven.content;

import org.commonjava.indy.IndyMetricsNames;
import org.apache.commons.io.IOUtils;
import org.apache.maven.artifact.repository.metadata.Metadata;
import org.apache.maven.artifact.repository.metadata.io.xpp3.MetadataXpp3Reader;
import org.apache.maven.artifact.repository.metadata.io.xpp3.MetadataXpp3Writer;
import org.codehaus.plexus.util.xml.pull.XmlPullParserException;
import org.commonjava.cdi.util.weft.ExecutorConfig;
import org.commonjava.cdi.util.weft.WeftManaged;
import org.commonjava.indy.IndyWorkflowException;
import org.commonjava.indy.content.DirectContentAccess;
import org.commonjava.indy.content.StoreResource;
import org.commonjava.indy.core.content.AbstractMergedContentGenerator;
import org.commonjava.indy.content.MergedContentAction;
import org.commonjava.indy.core.content.group.GroupMergeHelper;
import org.commonjava.indy.measure.annotation.IndyMetrics;
import org.commonjava.indy.measure.annotation.Measure;
import org.commonjava.indy.measure.annotation.MetricNamed;
import org.commonjava.indy.data.IndyDataException;
import org.commonjava.indy.model.core.StoreKey;
import org.commonjava.indy.pkg.maven.content.group.MavenMetadataMerger;
import org.commonjava.indy.data.StoreDataManager;
import org.commonjava.indy.model.core.ArtifactStore;
import org.commonjava.indy.model.core.Group;
import org.commonjava.indy.model.core.StoreType;
import org.commonjava.indy.pkg.maven.metrics.IndyMetricsPkgMavenNames;
import org.commonjava.indy.pkg.maven.content.cache.MavenVersionMetadataCache;
import org.commonjava.indy.subsys.infinispan.CacheHandle;
import org.commonjava.indy.util.LocationUtils;
import org.commonjava.maven.atlas.ident.ref.SimpleTypeAndClassifier;
import org.commonjava.maven.atlas.ident.ref.TypeAndClassifier;
import org.commonjava.maven.atlas.ident.util.ArtifactPathInfo;
import org.commonjava.maven.atlas.ident.util.JoinString;
import org.commonjava.maven.atlas.ident.util.SnapshotUtils;
import org.commonjava.maven.atlas.ident.util.VersionUtils;
import org.commonjava.maven.atlas.ident.version.SingleVersion;
import org.commonjava.maven.atlas.ident.version.part.SnapshotPart;
import org.commonjava.maven.galley.event.EventMetadata;
import org.commonjava.maven.galley.maven.parse.GalleyMavenXMLException;
import org.commonjava.maven.galley.maven.parse.XMLInfrastructure;
import org.commonjava.maven.galley.maven.spi.type.TypeMapper;
import org.commonjava.maven.galley.model.Transfer;
import org.commonjava.maven.galley.model.TransferOperation;
import org.commonjava.maven.galley.model.TypeMapping;
import org.commonjava.maven.galley.spi.nfc.NotFoundCache;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

import javax.annotation.PostConstruct;
import javax.inject.Inject;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.StringReader;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Collectors;

import static org.apache.commons.io.IOUtils.closeQuietly;
import static org.commonjava.indy.core.content.group.GroupMergeHelper.GROUP_METADATA_EXISTS;
import static org.commonjava.indy.core.content.group.GroupMergeHelper.GROUP_METADATA_GENERATED;
import static org.commonjava.maven.galley.util.PathUtils.normalize;
import static org.commonjava.maven.galley.util.PathUtils.parentPath;

import org.apache.commons.lang.StringUtils;

public class MavenMetadataGenerator
    extends AbstractMergedContentGenerator
{

    private static final String ARTIFACT_ID = "artifactId";

    private static final String GROUP_ID = "groupId";

    private static final String VERSION = "version";

    private static final String LAST_UPDATED = "lastUpdated";

    private static final String TIMESTAMP = "timestamp";

    private static final String BUILD_NUMBER = "buildNumber";

    private static final String EXTENSION = "extension";

    private static final String VALUE = "value";

    private static final String UPDATED = "updated";

    private static final String LOCAL_COPY = "localCopy";

    private static final String LATEST = "latest";

    private static final String RELEASE = "release";

    private static final String CLASSIFIER = "classifier";

    @Inject
    @MavenVersionMetadataCache
    private CacheHandle<StoreKey, Map> versionMetadataCache;

    private static final Set<String> HANDLED_FILENAMES = Collections.unmodifiableSet( new HashSet<String>()
    {

        {
            add( MavenMetadataMerger.METADATA_NAME );
            add( MavenMetadataMerger.METADATA_MD5_NAME );
            add( MavenMetadataMerger.METADATA_SHA_NAME );
            add( MavenMetadataMerger.METADATA_SHA256_NAME );
        }

        private static final long serialVersionUID = 1L;

    } );

    @Inject
    private XMLInfrastructure xml;

    @Inject
    private TypeMapper typeMapper;

    @Inject
    private MavenMetadataMerger merger;

    @Inject
    @WeftManaged
    @ExecutorConfig( named="maven-metadata-generator", threads=8 )
    private ExecutorService executorService;

    private final Map<String, ReentrantLock> mergerLocks = new WeakHashMap<>();

    private static final int THREAD_WAITING_TIME_SECONDS = 300;

    protected MavenMetadataGenerator()
    {
    }

    public MavenMetadataGenerator( final DirectContentAccess fileManager, final StoreDataManager storeManager,
                                   final XMLInfrastructure xml, final TypeMapper typeMapper,
                                   final MavenMetadataMerger merger, final GroupMergeHelper mergeHelper,
                                   final NotFoundCache nfc, final MergedContentAction... mergedContentActions )
    {
        super( fileManager, storeManager, mergeHelper, nfc, mergedContentActions );
        this.xml = xml;
        this.typeMapper = typeMapper;
        this.merger = merger;
        start();
    }

    @PostConstruct
    public void start()
    {
        if ( executorService == null )
        {
            executorService = Executors.newFixedThreadPool( 8 );
        }
    }

    @Override
    @IndyMetrics( measure = @Measure( timers = @MetricNamed( name =
                    IndyMetricsPkgMavenNames.METHOD_MAVENMETADATAGENERATOR_GENERATEFILECONTENT
                                    + IndyMetricsNames.TIMER ), meters = @MetricNamed( name =
                    IndyMetricsPkgMavenNames.METHOD_MAVENMETADATAGENERATOR_GENERATEFILECONTENT
                                    + IndyMetricsNames.METER ) ) )
    public Transfer generateFileContent( final ArtifactStore store, final String path, final EventMetadata eventMetadata )
        throws IndyWorkflowException
    {
        // metadata merging is something else...don't handle it here.
        if ( StoreType.group == store.getKey()
                                     .getType() )
        {
            return null;
        }

        if ( !canProcess( path ) )
        {
            return null;
        }

        boolean generated = false;

        // TODO: Generation of plugin metadata files (groupId-level) is harder, and requires cracking open the jar file
        // This is because that's the only place the plugin prefix can be reliably retrieved from.

        // regardless, we will need this first level of listings. What we do with it will depend on the logic below...
        final String parentPath = Paths.get( path )
                                       .getParent()
                                       .toString();

        List<StoreResource> firstLevel;
        try
        {
            firstLevel = fileManager.listRaw( store, parentPath );
        }
        catch ( final IndyWorkflowException e )
        {
            logger.error( String.format( "SKIP: Failed to generate maven-metadata.xml from listing of directory contents for: %s under path: %s",
                                         store, parentPath ), e );
            return null;
        }
        //
        //        if ( firstLevel == null || firstLevel.isEmpty() )
        //        {
        //            logger.error( String.format( "SKIP: Listing is empty for: %s under path: %s", store, parentPath ) );
        //            return null;
        //        }

        String toGenPath = path;
        if ( !path.endsWith( MavenMetadataMerger.METADATA_NAME ) )
        {
            toGenPath = normalize( normalize( parentPath( toGenPath ) ), MavenMetadataMerger.METADATA_NAME );
        }

        ArtifactPathInfo snapshotPomInfo = null;

        if ( parentPath.endsWith( SnapshotUtils.LOCAL_SNAPSHOT_VERSION_PART ) )
        {
            // If we're in a version directory, first-level listing should include a .pom file
            for ( final StoreResource resource : firstLevel )
            {
                if ( resource.getPath()
                             .endsWith( ".pom" ) )
                {
                    snapshotPomInfo = ArtifactPathInfo.parse( resource.getPath() );
                    break;
                }
            }
        }

        if ( snapshotPomInfo != null )
        {
            logger.debug( "Generating maven-metadata.xml for snapshots" );
            generated = writeSnapshotMetadata( snapshotPomInfo, firstLevel, store, toGenPath, eventMetadata );
        }
        else
        {
            logger.debug( "Generating maven-metadata.xml for releases" );
            generated = writeVersionMetadata( firstLevel, store, toGenPath, eventMetadata );
        }

        return generated ? fileManager.getTransfer( store, path ) : null;
    }

    @Override
    public List<StoreResource> generateDirectoryContent( final ArtifactStore store, final String path,
                                                         final List<StoreResource> existing,
                                                         final EventMetadata eventMetadata )
        throws IndyWorkflowException
    {
        final StoreResource mdResource =
            new StoreResource( LocationUtils.toLocation( store ), Paths.get( path, MavenMetadataMerger.METADATA_NAME )
                                                                       .toString() );

        if ( existing.contains( mdResource ) )
        {
            return null;
        }

        int pathElementsCount = StringUtils.strip( path, "/" ).split( "/" ).length;
        // if there is a possibility we are listing an artifactId
        if ( pathElementsCount >= 2 )
        {
            // regardless, we will need this first level of listings. What we do with it will depend on the logic below...
            final List<StoreResource> firstLevelFiles = fileManager.listRaw( store, path );

            ArtifactPathInfo samplePomInfo = null;
            for ( final StoreResource topResource : firstLevelFiles )
            {
                final String topPath = topResource.getPath();
                if ( topPath.endsWith( ".pom" ) )
                {
                    samplePomInfo = ArtifactPathInfo.parse( topPath );
                    break;
                }
            }

            // if this dir does not contain a pom check if a subdir contain a pom
            if ( samplePomInfo == null )
            {
                List<String> firstLevelDirs = firstLevelFiles.stream()
                                                             .map( (res) -> res.getPath() )
                                                             .filter( (subpath) -> subpath.endsWith( "/" ) )
                                                             .collect( Collectors.toList() );
                final Map<String, List<StoreResource>> secondLevelMap = fileManager.listRaw( store, firstLevelDirs );
                nextTopResource: for ( final String topPath : firstLevelDirs )
                {
                    final List<StoreResource> secondLevelListing = secondLevelMap.get( topPath );
                    for ( final StoreResource fileResource : secondLevelListing )
                    {
                        if ( fileResource.getPath()
                                         .endsWith( ".pom" ) )
                        {
                            if ( samplePomInfo == null )
                            {
                                samplePomInfo = ArtifactPathInfo.parse( fileResource.getPath() );
                                break nextTopResource;
                            }

                            continue nextTopResource;
                        }
                    }
                }
            }

            // TODO: Generation of plugin metadata files (groupId-level) is harder, and requires cracking open the jar file
            // This is because that's the only place the plugin prefix can be reliably retrieved from.
            // We won't worry about this for now.
            if ( samplePomInfo != null )
            {
                final List<StoreResource> result = new ArrayList<StoreResource>();
                result.add( mdResource );
                result.add( new StoreResource( LocationUtils.toLocation( store ),
                                               Paths.get( path, MavenMetadataMerger.METADATA_MD5_NAME )
                                               .toString() ) );
                result.add( new StoreResource( LocationUtils.toLocation( store ),
                                               Paths.get( path, MavenMetadataMerger.METADATA_SHA_NAME )
                                               .toString() ) );
                return result;
            }
        }

        return null;
    }

    @Override
    @IndyMetrics( measure = @Measure( timers = @MetricNamed( name =
                    IndyMetricsPkgMavenNames.METHOD_MAVENMETADATAGENERATOR_GENERATEGROUPILECONTENT
                                    + IndyMetricsNames.TIMER ), meters = @MetricNamed( name =
                    IndyMetricsPkgMavenNames.METHOD_MAVENMETADATAGENERATOR_GENERATEGROUPILECONTENT
                                    + IndyMetricsNames.METER ) ) )
    public Transfer generateGroupFileContent( final Group group, final List<ArtifactStore> members, final String path,
                                              final EventMetadata eventMetadata )
            throws IndyWorkflowException
    {
        String toMergePath = path;
        if ( !path.endsWith( MavenMetadataMerger.METADATA_NAME ) )
        {
            toMergePath = normalize( normalize( parentPath( toMergePath ) ), MavenMetadataMerger.METADATA_NAME );
        }

        Transfer target = fileManager.getTransfer( group, toMergePath );
        if ( exists( target ) )
        {
            // Means there is no metadata change if this transfer exists, so directly return it.
            logger.trace( "Metadata file exists for group {} of path {}, no need to regenerate.", group.getKey(), path );
            eventMetadata.set( GROUP_METADATA_EXISTS, true );
            return target;
        }

        final ReentrantLock mergerLock = getMergerLock( group, toMergePath );
        final boolean locked = mergerLock.tryLock();
        boolean mergingDone = false;

        if ( locked )
        {
            try
            {
                logger.debug( "Start metadata generation for the metadata file for this path {} in group {}", path,
                              group );
                final Metadata md = generateGroupMetadata( group, members, path );
                if ( md != null )
                {
                    final ByteArrayOutputStream baos = new ByteArrayOutputStream();
                    try
                    {
                        logger.trace( "Metadata file lost for group {} of path {}, will regenerate.", group.getKey(),
                                      path );
                        new MetadataXpp3Writer().write( baos, md );

                        final byte[] merged = baos.toByteArray();
                        if ( merged != null )
                        {
                            OutputStream fos = null;
                            try
                            {
                                fos = target.openOutputStream( TransferOperation.GENERATE, true, eventMetadata );
                                fos.write( merged );
                            }
                            catch ( final IOException e )
                            {
                                throw new IndyWorkflowException( "Failed to write merged metadata to: {}.\nError: {}",
                                                                 e, target, e.getMessage() );
                            }
                            finally
                            {
                                closeQuietly( fos );
                            }

                        writeGroupMergeInfo( group, members, toMergePath );

                        eventMetadata.set( GROUP_METADATA_GENERATED, true );
                        }
                    }
                    catch ( final IOException e )
                    {
                        logger.error( String.format( "Cannot write consolidated metadata: %s to: %s. Reason: %s", path,
                                                     group.getKey(), e.getMessage() ), e );
                    }
                }
            }
            finally
            {
                mergingDone = true;
                mergerLock.unlock();
            }
        }
        else
        {
            logger.info(
                    "The metadata generation is still in process by another thread for the metadata file for this path {} in group {}, so block current thread to wait for result",
                    path, group );
            boolean waitingLocked = false;
            try
            {
                //TODO: will let non-working threads wait here for seconds for the result of the working thread processing. Need to evaluate how long should wait here in future.
                waitingLocked = mergerLock.tryLock( THREAD_WAITING_TIME_SECONDS, TimeUnit.SECONDS );
                if ( waitingLocked )
                {
                    mergingDone = true;
                }
            }
            catch ( InterruptedException e )
            {
                logger.warn( "Thread interrupted by other threads for waiting processing result: {}", e.getMessage() );
            }
            finally
            {
                if ( waitingLocked )
                {
                    mergerLock.unlock();
                }
            }
        }

        if ( exists( target ) )
        {
            //                return target;
            // if this is a checksum file, we need to return the original path.
            Transfer original = fileManager.getTransfer( group, path );
            if ( exists( original ) )
            {
                return original;
            }
        }

        if ( mergingDone )
        {
            logger.error(
                    "Merging finished but got some error, which is caused the merging file not created correctly. See merging related error log for details. Merging group: {}, path: {}",
                    group, path );
        }
        else
        {
            logger.error(
                    "Merging not finished but thread waiting timeout, caused current thread will get a null merging result. Try to enlarge the waiting timeout. Merging group: {}, path: {}",
                    group, path );
        }

        return null;
    }

    private ReentrantLock getMergerLock( Group group, String path )
    {
        ReentrantLock lock;
        synchronized ( mergerLocks )
        {
            String targetKey = group.getKey().toString() + "-" + path;
            lock = mergerLocks.get( targetKey );
            if ( lock == null )
            {
                lock = new ReentrantLock();
                mergerLocks.put( targetKey, lock );
            }
        }
        return lock;
    }

    private void writeGroupMergeInfo( final Group group, final List<ArtifactStore> members, final String path )
            throws IndyWorkflowException
    {
        logger.trace( "Start write .info file based on if the cache exists for group {} of members {} in path. ",
                      group.getKey(), members, path );
        final Transfer mergeInfoTarget = fileManager.getTransfer( group, path + GroupMergeHelper.MERGEINFO_SUFFIX );
        if ( !exists( mergeInfoTarget ) )
        {
            logger.trace( ".info file not found for {} of members {} in path {}", group.getKey(), members, path );
            final MetadataInfo metaInfo = getMetaInfoFromCache( group.getKey(), path );
            if ( metaInfo != null )
            {
                String metaMergeInfo = metaInfo.getMetadataMergeInfo();
                if ( StringUtils.isBlank( metaMergeInfo ) )
                {
                    logger.trace(
                            "metadata merge info not cached for group {} of members {} in path {}, will regenerate.",
                            group.getKey(), members, path );
                    final List<Transfer> sources = fileManager.retrieveAllRaw( members, path, new EventMetadata() );
                    metaMergeInfo = helper.generateMergeInfo( sources );
                    metaInfo.setMetadataMergeInfo( metaMergeInfo );
                }
                logger.trace( "Metadata merge info for {} of members {} in path {} is {}", group.getKey(), members,
                              path, metaMergeInfo );
                helper.writeMergeInfo( metaMergeInfo, group, path );
                logger.trace( ".info file regenerated for group {} of members {} in path. ", group.getKey(), members,
                              path );
            }
        }
        else
        {
            logger.trace( ".info file exists for group {} of members {} in path, no need to regenerate ",
                          group.getKey(), members, path );
        }
    }

    /**
     * Will generate group related files(e.g maven-metadata.xml) from cache level, which means all the generation of the
     * files will be cached. In terms of cache clearing, see #{@link MetadataMergeListner}
     *
     * @param group
     * @param members
     * @param path
     * @return
     * @throws IndyWorkflowException
     */
    private Metadata generateGroupMetadata( final Group group, final List<ArtifactStore> members, final String path )
            throws IndyWorkflowException
    {
        if ( !canProcess( path ) )
        {
            logger.error( "The path is not a metadata file: {} ", path );
            return null;
        }

        String toMergePath = path;
        if ( !path.endsWith( MavenMetadataMerger.METADATA_NAME ) )
        {
            toMergePath = normalize( normalize( parentPath( toMergePath ) ), MavenMetadataMerger.METADATA_NAME );
        }

        Metadata meta = getMetaFromCache( group.getKey(), toMergePath );

        if ( meta != null )
        {
            return meta;
        }

        Map<StoreKey, Metadata> memberMetas = new ConcurrentHashMap<>( members.size() );
        Set<ArtifactStore> missing = retrieveCachedMemberMetadata( memberMetas, members, toMergePath );

        downloadMissingMemberMetadata( group, missing, memberMetas, toMergePath );

        List<Metadata> metas = members.stream()
                                      .map( mem -> memberMetas.get(mem.getKey()) )
                                      .filter( mmeta -> mmeta != null )
                                      .collect( Collectors.toList() );

        final Metadata master = merger.mergeFromMetadatas( metas, group, toMergePath );

        if ( master != null )
        {
            Map<String, MetadataInfo> cacheMap = new HashMap<>();
            cacheMap.put( toMergePath, new MetadataInfo( master ) );
            versionMetadataCache.put( group.getKey(), cacheMap );
            return master;
        }

        logger.error( "The group metadata generation is not successful for path: {} in group: {}", path, group );
        return null;
    }

    private Set<ArtifactStore> retrieveCachedMemberMetadata( final Map<StoreKey, Metadata> memberMetas,
                                                             final List<ArtifactStore> members, final String toMergePath )
            throws IndyWorkflowException
    {
        Set<ArtifactStore> missing = new HashSet<>();

        for ( ArtifactStore store : members )
        {
            Metadata memberMeta = null;
            if ( store.getKey().getType() == StoreType.group )
            {
                try
                {
                    memberMeta = generateGroupMetadata( (Group) store, storeManager.query()
                                                                                   .getOrderedStoresInGroup(
                                                                                           store.getName() )
                                                                                   .stream()
                                                                                   .filter( st -> !st.isDisabled() )
                                                                                   .collect( Collectors.toList() ),
                                                        toMergePath );
                    memberMetas.put( store.getKey(), memberMeta );
                }
                catch ( IndyDataException e )
                {
                    logger.error( "Failed to get store when do group meatadata merging, group:{}, path:{}, reason:{}",
                                  store, toMergePath, e.getMessage() );
                }
            }
            else
            {
                memberMeta = getMetaFromCache( store.getKey(), toMergePath );

                if ( memberMeta != null )
                {
                    memberMetas.put( store.getKey(), memberMeta );
                }
                else
                {
                    missing.add( store );
                }
            }
        }

        return missing;
    }

    private void downloadMissingMemberMetadata( final Group group, final Set<ArtifactStore> missing,
                                                final Map<StoreKey, Metadata> memberMetas, final String toMergePath )
            throws IndyWorkflowException
    {
        CountDownLatch latch = new CountDownLatch( missing.size() );
        List<String> errors = new ArrayList<>();

        /* @formatter:off */
        missing.forEach( (store)->{
            logger.debug( "Submitting download task for {} metadata: '{}'", store.getKey(), toMergePath );
            executorService.execute( ()->{
                try
                {
                    logger.trace( "Starting metadata download: {}:{}", store.getKey(), toMergePath);
                    Transfer memberMetaTxfr = fileManager.retrieveRaw( store, toMergePath, new EventMetadata() );

                    if ( exists( memberMetaTxfr ) )
                    {
                        final MetadataXpp3Reader reader = new MetadataXpp3Reader();

                        try (InputStream in = memberMetaTxfr.openInputStream())
                        {
                            String content = IOUtils.toString( in );
                            Metadata memberMeta = reader.read( new StringReader( content ), false );
                            memberMetas.put( store.getKey(), memberMeta );

                            Map<String, MetadataInfo> cacheMap = new HashMap<>();
                            cacheMap.put( toMergePath, new MetadataInfo( memberMeta ) );
                            versionMetadataCache.putIfAbsent( store.getKey(), cacheMap );
                        }
                    }
                }
                catch ( final IOException e )
                {
                    String msg = String.format( "Failed to retrieve metadata: %s:%s. Reason: %s", store.getKey(), toMergePath,
                                                 e.getMessage() );
                    logger.error( msg, e );
                    synchronized ( errors )
                    {
                        errors.add( msg );
                    }
                }
                catch ( final XmlPullParserException e )
                {
                    String msg = String.format( "Cannot parse metadata: %s:%s. Reason: %s", store.getKey(), toMergePath, e.getMessage() );
                    logger.error( msg, e );
                    synchronized ( errors )
                    {
                        errors.add( msg );
                    }
                }
                catch ( IndyWorkflowException e )
                {
                    String msg = String.format( "Failed to retrieve metadata: %s:%s. Reason: %s", store.getKey(), toMergePath,
                                                 e.getMessage() );
                    logger.error( msg, e );
                    synchronized ( errors )
                    {
                        errors.add( msg );
                    }
                }
                finally
                {
                    latch.countDown();
                }
            } );
        } );
        /* @formatter:on */

        do
        {
            logger.trace( "Latch count: {}", latch.getCount() );
            try
            {
                logger.debug( "Waiting for {} member downloads of: {}:{}", latch.getCount(), group.getKey(), toMergePath );
                latch.await( 1000, TimeUnit.MILLISECONDS );
            }
            catch ( InterruptedException e )
            {
                logger.debug("Interrupted while waiting for member metadata downloads.");
                break;
            }
        }
        while ( latch.getCount() > 0 );

        if ( !errors.isEmpty() )
        {
            throw new IndyWorkflowException(
                    "Failed to retrieve one or more member metadata files for: %s:%s. Errors were:\n  %s",
                    group.getKey(), toMergePath, new JoinString( "\n  ", errors ) );
        }
    }

    private boolean exists( final Transfer target )
    {
        return target != null && target.exists();
    }

    private Metadata getMetaFromCache( final StoreKey key, final String path )
    {
        final MetadataInfo metaMergeInfo = getMetaInfoFromCache( key, path );
        if ( metaMergeInfo != null )
        {
            return metaMergeInfo.getMetadata();
        }
        return null;
    }

    private MetadataInfo getMetaInfoFromCache( final StoreKey key, final String path )
    {
        Map<String, MetadataInfo> metadataMap = versionMetadataCache.get( key );

        if ( metadataMap != null && !metadataMap.isEmpty() )
        {
            return metadataMap.get( path );
        }

        return null;
    }

    @Override
    public boolean canProcess( final String path )
    {
        for ( final String filename : HANDLED_FILENAMES )
        {
            if ( path.endsWith( filename ) )
            {
                return true;
            }
        }

        return false;
    }

    @Override
    public List<StoreResource> generateGroupDirectoryContent( final Group group, final List<ArtifactStore> members,
                                                              final String path, final EventMetadata eventMetadata )
        throws IndyWorkflowException
    {
        return generateDirectoryContent( group, path, Collections.<StoreResource> emptyList(), eventMetadata );
    }

    @Override
    protected String getMergedMetadataName()
    {
        return MavenMetadataMerger.METADATA_NAME;
    }

    private boolean writeVersionMetadata( final List<StoreResource> firstLevelFiles, final ArtifactStore store,
                                          final String path, final EventMetadata eventMetadata )
        throws IndyWorkflowException
    {
        ArtifactPathInfo samplePomInfo = null;

        // first level will contain version directories...for each directory, we need to verify the presence of a .pom file before including
        // as a valid version
        final List<SingleVersion> versions = new ArrayList<SingleVersion>();
        nextTopResource: for ( final StoreResource topResource : firstLevelFiles )
        {
            final String topPath = topResource.getPath();
            if ( topPath.endsWith( "/" ) )
            {
                final List<StoreResource> secondLevelListing = fileManager.listRaw( store, topPath );
                for ( final StoreResource fileResource : secondLevelListing )
                {
                    if ( fileResource.getPath()
                                     .endsWith( ".pom" ) )
                    {
                        ArtifactPathInfo filePomInfo = ArtifactPathInfo.parse( fileResource.getPath() );
                        // check if the pom is valid for the path
                        if ( filePomInfo != null )
                        {
                            versions.add( VersionUtils.createSingleVersion( new File( topPath ).getName() ) );
                            if ( samplePomInfo == null )
                            {
                                samplePomInfo = filePomInfo;
                            }

                            continue nextTopResource;
                        }
                    }
                }
            }
        }

        if ( versions.isEmpty() )
        {
            return false;
        }

        Collections.sort( versions );

        final Transfer metadataFile = fileManager.getTransfer( store, path );
        OutputStream stream = null;
        try
        {
            final Document doc = xml.newDocumentBuilder()
                                    .newDocument();

            final Map<String, String> coordMap = new HashMap<String, String>();
            coordMap.put( ARTIFACT_ID, samplePomInfo.getArtifactId() );
            coordMap.put( GROUP_ID, samplePomInfo.getGroupId() );

            final String lastUpdated = SnapshotUtils.generateUpdateTimestamp( SnapshotUtils.getCurrentTimestamp() );

            doc.appendChild( doc.createElementNS( doc.getNamespaceURI(), "metadata" ) );
            xml.createElement( doc.getDocumentElement(), null, coordMap );

            final Map<String, String> versioningMap = new HashMap<String, String>();
            versioningMap.put( LAST_UPDATED, lastUpdated );

            final SingleVersion latest = versions.get( versions.size() - 1 );
            versioningMap.put( LATEST, latest.renderStandard() );

            SingleVersion release = null;
            for ( int i = versions.size() - 1; i >= 0; i-- )
            {
                final SingleVersion r = versions.get( i );
                if ( r.isRelease() )
                {
                    release = r;
                    break;
                }
            }

            if ( release != null )
            {
                versioningMap.put( RELEASE, release.renderStandard() );
            }

            xml.createElement( doc, "versioning", versioningMap );
            final Element versionsElem =
                xml.createElement( doc, "versioning/versions", Collections.<String, String> emptyMap() );

            for ( final SingleVersion version : versions )
            {
                final Element vElem = doc.createElement( VERSION );
                vElem.setTextContent( version.renderStandard() );

                versionsElem.appendChild( vElem );
            }

            final String xmlStr = xml.toXML( doc, true );
            stream = metadataFile.openOutputStream( TransferOperation.GENERATE, true, eventMetadata );
            stream.write( xmlStr.getBytes( "UTF-8" ) );
        }
        catch ( final GalleyMavenXMLException e )
        {
            throw new IndyWorkflowException( "Failed to generate maven metadata file: %s. Reason: %s", e, path,
                                              e.getMessage() );
        }
        catch ( final IOException e )
        {
            throw new IndyWorkflowException( "Failed to write generated maven metadata file: %s. Reason: %s", e,
                                              metadataFile, e.getMessage() );
        }
        finally
        {
            closeQuietly( stream );
        }

        return true;
    }

    private boolean writeSnapshotMetadata( final ArtifactPathInfo info, final List<StoreResource> files,
                                           final ArtifactStore store, final String path,
                                           final EventMetadata eventMetadata )
        throws IndyWorkflowException
    {
        // first level will contain files that have the timestamp-buildnumber version suffix...for each, we need to parse this info.
        final Map<SnapshotPart, Set<ArtifactPathInfo>> infosBySnap = new HashMap<SnapshotPart, Set<ArtifactPathInfo>>();
        for ( final StoreResource resource : files )
        {
            final ArtifactPathInfo resInfo = ArtifactPathInfo.parse( resource.getPath() );
            if ( resInfo != null )
            {
                final SnapshotPart snap = resInfo.getSnapshotInfo();
                Set<ArtifactPathInfo> infos = infosBySnap.get( snap );
                if ( infos == null )
                {
                    infos = new HashSet<ArtifactPathInfo>();
                    infosBySnap.put( snap, infos );
                }

                infos.add( resInfo );
            }
        }

        if ( infosBySnap.isEmpty() )
        {
            return false;
        }

        final List<SnapshotPart> snaps = new ArrayList<SnapshotPart>( infosBySnap.keySet() );
        Collections.sort( snaps );

        final Transfer metadataFile = fileManager.getTransfer( store, path );
        OutputStream stream = null;
        try
        {
            final Document doc = xml.newDocumentBuilder()
                                    .newDocument();

            final Map<String, String> coordMap = new HashMap<String, String>();
            coordMap.put( ARTIFACT_ID, info.getArtifactId() );
            coordMap.put( GROUP_ID, info.getGroupId() );
            coordMap.put( VERSION, info.getVersion() );

            final String lastUpdated = SnapshotUtils.generateUpdateTimestamp( SnapshotUtils.getCurrentTimestamp() );

            doc.appendChild( doc.createElementNS( doc.getNamespaceURI(), "metadata" ) );
            xml.createElement( doc.getDocumentElement(), null, coordMap );

            xml.createElement( doc, "versioning", Collections.<String, String> singletonMap( LAST_UPDATED, lastUpdated ) );

            SnapshotPart snap = snaps.get( snaps.size() - 1 );
            Map<String, String> snapMap = new HashMap<String, String>();
            if ( snap.isLocalSnapshot() )
            {
                snapMap.put( LOCAL_COPY, Boolean.TRUE.toString() );
            }
            else
            {
                snapMap.put( TIMESTAMP, SnapshotUtils.generateSnapshotTimestamp( snap.getTimestamp() ) );

                snapMap.put( BUILD_NUMBER, Integer.toString( snap.getBuildNumber() ) );
            }

            xml.createElement( doc, "versioning/snapshot", snapMap );

            for ( int i = 0; i < snaps.size(); i++ )
            {
                snap = snaps.get( i );

                // the last one is the most recent.
                final Set<ArtifactPathInfo> infos = infosBySnap.get( snap );
                for ( final ArtifactPathInfo pathInfo : infos )
                {
                    snapMap = new HashMap<String, String>();

                    final TypeAndClassifier
                            tc = new SimpleTypeAndClassifier( pathInfo.getType(), pathInfo.getClassifier() );

                    final TypeMapping mapping = typeMapper.lookup( tc );

                    final String classifier = mapping == null ? pathInfo.getClassifier() : mapping.getClassifier();
                    if ( classifier != null && classifier.length() > 0 )
                    {
                        snapMap.put( CLASSIFIER, classifier );
                    }

                    snapMap.put( EXTENSION, mapping == null ? pathInfo.getType() : mapping.getExtension() );
                    snapMap.put( VALUE, pathInfo.getVersion() );
                    snapMap.put( UPDATED, lastUpdated );

                    xml.createElement( doc, "versioning/snapshotVersions/snapshotVersion", snapMap );
                }
            }

            final String xmlStr = xml.toXML( doc, true );
            stream = metadataFile.openOutputStream( TransferOperation.GENERATE, true, eventMetadata );
            stream.write( xmlStr.getBytes( "UTF-8" ) );
        }
        catch ( final GalleyMavenXMLException e )
        {
            throw new IndyWorkflowException( "Failed to generate maven metadata file: %s. Reason: %s", e, path,
                                              e.getMessage() );
        }
        catch ( final IOException e )
        {
            throw new IndyWorkflowException( "Failed to write generated maven metadata file: %s. Reason: %s", e,
                                              metadataFile, e.getMessage() );
        }
        finally
        {
            closeQuietly( stream );
        }

        return true;
    }

    // Parking this here, transplanted from ScheduleManager, because this is where it belongs. It might be
    // covered elsewhere, but in case it's not, we can use this as a reference...
    //    public void updateSnapshotVersions( final StoreKey key, final String path )
    //    {
    //        final ArtifactPathInfo pathInfo = ArtifactPathInfo.parse( path );
    //        if ( pathInfo == null )
    //        {
    //            return;
    //        }
    //
    //        final ArtifactStore store;
    //        try
    //        {
    //            store = dataManager.getArtifactStore( key );
    //        }
    //        catch ( final IndyDataException e )
    //        {
    //            logger.error( String.format( "Failed to update metadata after snapshot deletion. Reason: {}",
    //                                         e.getMessage() ), e );
    //            return;
    //        }
    //
    //        if ( store == null )
    //        {
    //            logger.error( "Failed to update metadata after snapshot deletion in: {}. Reason: Cannot find corresponding ArtifactStore",
    //                          key );
    //            return;
    //        }
    //
    //        final Transfer item = fileManager.getStorageReference( store, path );
    //        if ( item.getParent() == null || item.getParent()
    //                                             .getParent() == null )
    //        {
    //            return;
    //        }
    //
    //        final Transfer metadata = fileManager.getStorageReference( store, item.getParent()
    //                                                                              .getParent()
    //                                                                              .getPath(), "maven-metadata.xml" );
    //
    //        if ( metadata.exists() )
    //        {
    //            //            logger.info( "[UPDATE VERSIONS] Updating snapshot versions for path: {} in store: {}", path, key.getName() );
    //            Reader reader = null;
    //            Writer writer = null;
    //            try
    //            {
    //                reader = new InputStreamReader( metadata.openInputStream() );
    //                final Metadata md = new MetadataXpp3Reader().read( reader );
    //
    //                final Versioning versioning = md.getVersioning();
    //                final List<String> versions = versioning.getVersions();
    //
    //                final String version = pathInfo.getVersion();
    //                String replacement = null;
    //
    //                final int idx = versions.indexOf( version );
    //                if ( idx > -1 )
    //                {
    //                    if ( idx > 0 )
    //                    {
    //                        replacement = versions.get( idx - 1 );
    //                    }
    //
    //                    versions.remove( idx );
    //                }
    //
    //                if ( version.equals( md.getVersion() ) )
    //                {
    //                    md.setVersion( replacement );
    //                }
    //
    //                if ( version.equals( versioning.getLatest() ) )
    //                {
    //                    versioning.setLatest( replacement );
    //                }
    //
    //                final SnapshotPart si = pathInfo.getSnapshotInfo();
    //                if ( si != null )
    //                {
    //                    final SnapshotPart siRepl = SnapshotUtils.extractSnapshotVersionPart( replacement );
    //                    final Snapshot snapshot = versioning.getSnapshot();
    //
    //                    final String siTstamp = SnapshotUtils.generateSnapshotTimestamp( si.getTimestamp() );
    //                    if ( si.isRemoteSnapshot() && siTstamp.equals( snapshot.getTimestamp() )
    //                        && si.getBuildNumber() == snapshot.getBuildNumber() )
    //                    {
    //                        if ( siRepl != null )
    //                        {
    //                            if ( siRepl.isRemoteSnapshot() )
    //                            {
    //                                snapshot.setTimestamp( SnapshotUtils.generateSnapshotTimestamp( siRepl.getTimestamp() ) );
    //                                snapshot.setBuildNumber( siRepl.getBuildNumber() );
    //                            }
    //                            else
    //                            {
    //                                snapshot.setLocalCopy( true );
    //                            }
    //                        }
    //                        else
    //                        {
    //                            versioning.setSnapshot( null );
    //                        }
    //                    }
    //                }
    //
    //                writer = new OutputStreamWriter( metadata.openOutputStream( TransferOperation.GENERATE, true ) );
    //                new MetadataXpp3Writer().write( writer, md );
    //            }
    //            catch ( final IOException e )
    //            {
    //                logger.error( "Failed to update metadata after snapshot deletion.\n  Snapshot: {}\n  Metadata: {}\n  Reason: {}",
    //                              e, item.getFullPath(), metadata, e.getMessage() );
    //            }
    //            catch ( final XmlPullParserException e )
    //            {
    //                logger.error( "Failed to update metadata after snapshot deletion.\n  Snapshot: {}\n  Metadata: {}\n  Reason: {}",
    //                              e, item.getFullPath(), metadata, e.getMessage() );
    //            }
    //            finally
    //            {
    //                closeQuietly( reader );
    //                closeQuietly( writer );
    //            }
    //        }
    //    }
    //
}
