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
package org.commonjava.aprox.change.event;

import org.commonjava.aprox.model.core.ArtifactStore;

/** 
 * Enumeration of the types of configuration updates that can happen for {@link ArtifactStore}'s.
 */
public enum ArtifactStoreUpdateType
{

    /** Definite creation of new store. */
    ADD,
    /** upadting an existing store. */
    UPDATE;

}