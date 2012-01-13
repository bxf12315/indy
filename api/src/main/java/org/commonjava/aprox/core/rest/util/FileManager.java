/*******************************************************************************
 * Copyright 2011 John Casey
 * 
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 *   http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 ******************************************************************************/
package org.commonjava.aprox.core.rest.util;

import java.io.File;
import java.io.InputStream;
import java.util.List;
import java.util.Set;

import org.commonjava.aprox.core.model.ArtifactStore;
import org.commonjava.aprox.core.model.DeployPoint;
import org.commonjava.aprox.core.model.Repository;

public interface FileManager
{

    File downloadFirst( final List<? extends ArtifactStore> stores, final String path );

    Set<File> downloadAll( final List<? extends ArtifactStore> stores, final String path );

    File download( final ArtifactStore store, final String path );

    boolean download( final Repository repository, final String path, final File target, final boolean suppressFailures );

    void upload( final DeployPoint deploy, final String path, final InputStream stream );

    DeployPoint upload( final List<? extends DeployPoint> deployPoints, final String path, final InputStream stream );

    File formatStorageReference( final ArtifactStore store, final String path );

}