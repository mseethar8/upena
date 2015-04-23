package com.jivesoftware.os.upena.deployable.endpoints;

import com.google.common.base.Optional;
import com.jivesoftware.os.upena.deployable.region.HealthPluginRegion;
import com.jivesoftware.os.upena.deployable.region.HealthPluginRegion.HealthPluginRegionInput;
import com.jivesoftware.os.upena.deployable.soy.SoyService;
import javax.inject.Singleton;
import javax.servlet.http.HttpServletRequest;
import javax.ws.rs.DefaultValue;
import javax.ws.rs.GET;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.QueryParam;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;

/**
 *
 */
@Singleton
@Path("/ui/health")
public class HealthPluginEndpoints {

    private final SoyService soyService;
    private final HealthPluginRegion pluginRegion;

    public HealthPluginEndpoints(@Context SoyService soyService, @Context HealthPluginRegion pluginRegion) {
        this.soyService = soyService;
        this.pluginRegion = pluginRegion;
    }

    @GET
    @Path("/")
    @Produces(MediaType.TEXT_HTML)
    public Response filter(@Context HttpServletRequest httpRequest,
        @QueryParam("cluster") @DefaultValue("") String cluster,
        @QueryParam("host") @DefaultValue("") String host,
        @QueryParam("service") @DefaultValue("") String service) {
        String rendered = soyService.renderPlugin(httpRequest.getRemoteUser(), pluginRegion,
            Optional.of(new HealthPluginRegionInput(cluster, host, service)));
        return Response.ok(rendered).build();
    }

}
