package org.commonjava.indy.pkg.maven.metrics;

import org.commonjava.indy.IndyMetricsNames;

/**
 * Created by xiabai on 5/26/17.
 */
public class IndyMetricsPkgMavenNames
                extends IndyMetricsNames
{
    private static final String MODULE_PREFIX_NAME = INDY_METRICS_NAME_PREFIX + ".pkgMaven";

    private static final String MODULE_CONTENT_PREFIX_NAME = ".content.";

    public static final String METHOD_CONTENT_GENERATEFILECONTENT =
                    MODULE_PREFIX_NAME + MODULE_CONTENT_PREFIX_NAME + "generateFileContent.";

    public static final String METHOD_CONTENT_GENERATEDIRECTORYCONENT =
                    MODULE_PREFIX_NAME + MODULE_CONTENT_PREFIX_NAME + "generateDirectoryContent.";

    public static final String METHOD_CONTENT_GENERATEGROUPFILECONTENT =
                    MODULE_PREFIX_NAME + MODULE_CONTENT_PREFIX_NAME + "generateGroupFileContent.";
}
