package org.commonjava.indy.measure.annotation;

import javax.enterprise.util.Nonbinding;
import java.lang.annotation.Retention;
import java.lang.annotation.Target;

import static java.lang.annotation.ElementType.METHOD;
import static java.lang.annotation.ElementType.TYPE;
import static java.lang.annotation.RetentionPolicy.RUNTIME;

/**
 * Created by xiabai on 3/2/17.
 */
@Target( { METHOD, TYPE } )
@Retention( RUNTIME )
public @interface MetricNamed
{
    String name();
}
