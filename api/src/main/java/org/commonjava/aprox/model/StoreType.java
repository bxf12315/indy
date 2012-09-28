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
package org.commonjava.aprox.model;

public enum StoreType
{
    group( false, "group", "groups" ), repository( false, "repository", "repositories" ), deploy_point( true, "deploy",
        "deploys" );

    private boolean writable;

    private String singular;

    private String plural;

    private StoreType( final boolean writable, final String singular, final String plural )
    {
        this.writable = writable;
        this.singular = singular;
        this.plural = plural;
    }

    public String pluralEndpointName()
    {
        return plural;
    }

    public String singularEndpointName()
    {
        return singular;
    }

    public boolean isWritable()
    {
        return writable;
    }
}