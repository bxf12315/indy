package org.commonjava.indy.model.core;

import static org.commonjava.maven.galley.io.SpecialPathConstants.PKG_TYPE_GENERIC_HTTP;

/**
 * Package type to handle generic http content, as is used by httprox.
 *
 * Created by jdcasey on 5/10/17.
 */
public class GenericPackageTypeDescriptor
    implements PackageTypeDescriptor
{
    public static final String GENERIC_PKG_KEY = PKG_TYPE_GENERIC_HTTP;

    public static final String GENERIC_CONTENT_REST_BASE_PATH = "/api/content/generic";

    public static final String GENERIC_ADMIN_REST_BASE_PATH = "/api/admin/stores/generic";

    @Override
    public String getKey()
    {
        return GENERIC_PKG_KEY;
    }

    @Override
    public String getContentRestBasePath()
    {
        return GENERIC_CONTENT_REST_BASE_PATH;
    }

    @Override
    public String getAdminRestBasePath()
    {
        return GENERIC_ADMIN_REST_BASE_PATH;
    }
}
