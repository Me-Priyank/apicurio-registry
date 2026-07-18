package io.apicurio.registry.federation.rest;

import io.apicurio.registry.federation.FederatedAgentSearchService;
import io.apicurio.registry.federation.FederatedPeer;
import io.apicurio.registry.federation.PeerRegistry;
import io.apicurio.registry.federation.rest.beans.FederatedAgentSearchResults;
import io.apicurio.registry.federation.rest.beans.RegisterPeerRequest;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.util.List;

/**
 * REST endpoints for managing federated peers and running federated agent search.
 * POC for issue #8424 (federated AI agent search across registry instances).
 */
@Path("/apis/registry/v3/federation")
@ApplicationScoped
@Produces(MediaType.APPLICATION_JSON)
public class FederationResource {

    @Inject
    PeerRegistry peerRegistry;

    @Inject
    FederatedAgentSearchService searchService;

    @POST
    @Path("/peers")
    @Consumes(MediaType.APPLICATION_JSON)
    public Response registerPeer(RegisterPeerRequest request) {
        if (request == null || isBlank(request.getName()) || isBlank(request.getUrl())) {
            throw new WebApplicationException("Both 'name' and 'url' are required.",
                    Response.Status.BAD_REQUEST);
        }
        FederatedPeer peer = peerRegistry.register(request.getName().trim(), request.getUrl().trim());
        return Response.status(Response.Status.CREATED).entity(peer).build();
    }

    @GET
    @Path("/peers")
    public List<FederatedPeer> listPeers() {
        return peerRegistry.list();
    }

    @DELETE
    @Path("/peers/{id}")
    public Response removePeer(@PathParam("id") String id) {
        if (!peerRegistry.remove(id)) {
            throw new WebApplicationException("Peer not found: " + id, Response.Status.NOT_FOUND);
        }
        return Response.noContent().build();
    }

    @GET
    @Path("/agents/search")
    public FederatedAgentSearchResults searchAgents(
            @QueryParam("name") String name,
            @QueryParam("skill") List<String> skills,
            @QueryParam("offset") @DefaultValue("0") int offset,
            @QueryParam("limit") @DefaultValue("20") int limit) {
        return searchService.search(name, skills, offset, limit);
    }

    private static boolean isBlank(String s) {
        return s == null || s.trim().isEmpty();
    }
}
