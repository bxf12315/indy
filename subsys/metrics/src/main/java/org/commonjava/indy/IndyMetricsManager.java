package org.commonjava.indy;

import com.codahale.metrics.Meter;
import com.codahale.metrics.MetricRegistry;
import com.codahale.metrics.Timer;
import com.codahale.metrics.health.HealthCheck;
import org.commonjava.indy.measure.annotation.MetricNamed;
import org.commonjava.indy.metrics.conf.IndyMetricsConfig;
import org.commonjava.indy.metrics.conf.annotation.IndyMetricsNamed;
import org.commonjava.indy.metrics.healthcheck.IndyHealthCheck;
import org.commonjava.indy.metrics.healthcheck.IndyHealthCheckRegistrySet;
import org.commonjava.indy.metrics.jvm.IndyJVMInstrumentation;
import org.commonjava.indy.metrics.reporter.ReporterIntializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.PostConstruct;
import javax.enterprise.context.ApplicationScoped;
import javax.enterprise.inject.Any;
import javax.enterprise.inject.Instance;
import javax.inject.Inject;

/**
 * Created by xiabai on 2/27/17.
 */
@ApplicationScoped
public class IndyMetricsManager
{

    private static final Logger logger = LoggerFactory.getLogger( IndyMetricsManager.class );

    @Inject
    MetricRegistry metricRegistry;

    @Inject
    @Any
    Instance<IndyHealthCheck> indyMetricsHealthChecks;

    @Inject
    ReporterIntializer reporter;

    @Inject
    @IndyMetricsNamed
    IndyMetricsConfig config;

    @PostConstruct
    public void initMetric()
    {

        if ( !config.isMetricsEnabled() )
            return;
        IndyJVMInstrumentation.init( metricRegistry );
        IndyHealthCheckRegistrySet healthCheckRegistrySet = new IndyHealthCheckRegistrySet();

        indyMetricsHealthChecks.forEach( indyHealthCheck ->
                                         {
                                             healthCheckRegistrySet.register( indyHealthCheck.getName(),
                                                                              (HealthCheck) indyHealthCheck );
                                         } );
        try
        {
            metricRegistry.register( healthCheckRegistrySet.getName(), healthCheckRegistrySet );
            reporter.initReporter( metricRegistry );
        }
        catch ( Exception e )
        {
            logger.error( e.getMessage() );
            throw new RuntimeException( e );
        }
    }

    public Timer getTimer( MetricNamed named )
    {
        logger.info( "call in IndyMetricsManager.getTimer" );
        return this.metricRegistry.timer( named.name() );
    }

    public Meter getMeter( MetricNamed named )
    {
        logger.info( "call in IndyMetricsManager.getMeter" );
        return metricRegistry.meter( named.name() );
    }

}
