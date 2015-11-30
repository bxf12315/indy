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
package org.commonjava.aprox.util;

public class ValuePipe<T>
{

    private T value;

    public boolean isFilled()
    {
        return value != null;
    }

    public boolean isEmpty()
    {
        return value == null;
    }

    public synchronized T get()
    {
        return value;
    }

    public synchronized void set( final T value )
    {
        this.value = value;
        this.notifyAll();
    }

}