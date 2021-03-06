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
package org.commonjava.indy.pkg.maven.content;

import org.commonjava.indy.IndyMetricsNames;
import org.commonjava.indy.IndyWorkflowException;
import org.commonjava.indy.content.DirectContentAccess;
import org.commonjava.indy.content.StoreResource;
import org.commonjava.indy.core.content.AbstractMergedContentGenerator;
import org.commonjava.indy.content.MergedContentAction;
import org.commonjava.indy.core.content.group.GroupMergeHelper;
import org.commonjava.indy.measure.annotation.IndyMetrics;
import org.commonjava.indy.measure.annotation.Measure;
import org.commonjava.indy.measure.annotation.MetricNamed;
import org.commonjava.indy.pkg.maven.content.group.MavenMetadataMerger;
import org.commonjava.indy.data.StoreDataManager;
import org.commonjava.indy.model.core.ArtifactStore;
import org.commonjava.indy.model.core.Group;
import org.commonjava.indy.model.core.StoreType;
import org.commonjava.indy.pkg.maven.metrics.IndyMetricsPkgMavenNames;
import org.commonjava.indy.util.LocationUtils;
import org.commonjava.maven.atlas.ident.ref.SimpleTypeAndClassifier;
import org.commonjava.maven.atlas.ident.ref.TypeAndClassifier;
import org.commonjava.maven.atlas.ident.util.ArtifactPathInfo;
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

import javax.inject.Inject;
import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.apache.commons.io.IOUtils.closeQuietly;
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
            logger.info( "Generating maven-metadata.xml for snapshots" );
            generated = writeSnapshotMetadata( snapshotPomInfo, firstLevel, store, toGenPath, eventMetadata );
        }
        else
        {
            logger.info( "Generating maven-metadata.xml for releases" );
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
        if ( !canProcess( path ) )
        {
            return null;
        }

        final Transfer target = fileManager.getTransfer( group, path );

        logger.debug( "Working on metadata file: {} (already exists? {})", target, target != null && target.exists() );

        if ( !target.exists() )
        {
            String toMergePath = path;
            if ( !path.endsWith( MavenMetadataMerger.METADATA_NAME ) )
            {
                toMergePath = normalize( normalize( parentPath( toMergePath ) ), MavenMetadataMerger.METADATA_NAME );
            }

            final List<Transfer> sources = fileManager.retrieveAllRaw( members, toMergePath, new EventMetadata() );
            final byte[] merged = merger.merge( sources, group, toMergePath );
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
                    throw new IndyWorkflowException( "Failed to write merged metadata to: {}.\nError: {}", e, target,
                                                      e.getMessage() );
                }
                finally
                {
                    closeQuietly( fos );
                }

                helper.writeMergeInfo( merged, sources, group, toMergePath );
            }
        }

        if ( target.exists() )
        {
            return target;
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
