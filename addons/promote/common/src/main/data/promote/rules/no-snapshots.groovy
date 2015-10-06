package org.commonjava.aprox.promote.rules

import org.commonjava.aprox.promote.validate.model.ValidationRequest
import org.commonjava.aprox.promote.validate.model.ValidationRule
import org.commonjava.cartographer.graph.discover.DiscoveryConfig

class NoSnapshots implements ValidationRule {

    String validate(ValidationRequest request) {
        def builder = new StringBuilder()
        def tools = request.getTools()
        def dc = DiscoveryConfig.getDisabledConfig();

        dc.setIncludeBuildSection(true)
        dc.setIncludeManagedPlugins(true)
        dc.setIncludeManagedDependencies(true)

        request.getSourcePaths().each { it ->
            if (it.endsWith(".pom")) {
                def ref = tools.getArtifact(it)
                if (ref != null) {
                    if ( !ref.getVersionSpec().isRelease() )
                    {
                        if (builder.length() > 0) {
                            builder.append("\n")
                        }
                        builder.append(it).append(" is a variable/snapshot version.")
                    }
                }

                def relationships = tools.getRelationshipsForPom(it, dc, request.getPromoteRequest())
                if (relationships != null) {
                    relationships.each { rel ->
                        def target = rel.getTarget()
                        if (!target.getVersionSpec().isRelease()) {
                            if (builder.length() > 0) {
                                builder.append("\n")
                            }
                            builder.append(target).append(" uses a variable/snapshot version in: ").append(it)
                        }
                    }
                }
            }
        }

        builder.length() > 0 ? builder.toString() : null
    }
}