package com.drinkpebble.uber

import com.codahale.metrics.annotation.Timed
import com.google.api.client.auth.oauth2.Credential
import com.uber.sdk.core.auth.Scope
import com.uber.sdk.rides.auth.OAuth2Credentials
import com.uber.sdk.rides.client.CredentialsSession
import com.uber.sdk.rides.client.ServerTokenSession
import com.uber.sdk.rides.client.SessionConfiguration
import com.uber.sdk.rides.client.UberRidesApi
import com.uber.sdk.rides.client.model.Product
import com.uber.sdk.rides.client.model.Ride
import com.uber.sdk.rides.client.model.RideEstimate
import com.uber.sdk.rides.client.model.RideRequestParameters
import com.uber.sdk.rides.client.model.SandboxRideRequestParameters
import com.uber.sdk.rides.client.model.UserProfile
import com.uber.sdk.rides.client.services.RidesService
import groovy.util.logging.Slf4j
import retrofit2.Response

import javax.ws.rs.Consumes
import javax.ws.rs.GET
import javax.ws.rs.POST
import javax.ws.rs.Path
import javax.ws.rs.Produces
import javax.ws.rs.QueryParam
import javax.ws.rs.core.MediaType

@Path("/uber-req")
@Slf4j
class UberRequestResource {

    ServerTokenSession serverSession
    ApplicationConfiguration configuration

    // works for one user. replace with map of things / database call
    OAuth2Credentials credentials
    SessionConfiguration userSessionConfig
    Credential credential
    String rideId

    UberRequestResource(ServerTokenSession session, ApplicationConfiguration configuration){
        this.serverSession = session
        this.configuration = configuration
    }

    /*
    @Path("/test")
    @GET
    @Timed
    String test(){
        def service = UberRidesApi.with(session).build().createService()
        Response response1 = service.getProducts(37.79f, -122.39f).execute()
        List<Product> products = response1.body().products
        def productId = products[0].productId
        Response<TimeEstimatesResponse> response2 = service.getPickupTimeEstimate(37.79f, -122.39f, productId).execute()
        return response2.body().times[0].estimate.toString()
    }
    */

    @Path("/get-auth-url")
    @GET
    @Timed
    String getAuthUrl(){
        userSessionConfig = new SessionConfiguration.Builder()
            .setClientId(configuration.uberClientId)
            .setClientSecret(configuration.uberSecret)
            .setEnvironment(SessionConfiguration.Environment.SANDBOX)
            .setScopes(Arrays.asList(Scope.PROFILE, Scope.REQUEST))
            .setRedirectUri("https://$configuration.serverUrl:8080/uber-req/register")
            .build()

        credentials = new OAuth2Credentials.Builder()
            .setSessionConfiguration(userSessionConfig)
            .build()


        return credentials.getAuthorizationUrl()

    }

    @GET
    @Path("/register")
    @Timed
    javax.ws.rs.core.Response register(@QueryParam("code") String authCode){
        def userId = "testUserId"
        try{
            credential = credentials.authenticate(authCode,userId)
        } catch (Exception e){
            log.warn("Failed to authenticate user " + userId)
            return javax.ws.rs.core.Response.status(403).build()
        }
        return javax.ws.rs.core.Response.status(200).build()
    }

    @GET
    @Path("/test")
    String test(){
        CredentialsSession session = new CredentialsSession(userSessionConfig, credential)
        RidesService service = UberRidesApi.with(session).build().createService()
        Response<UserProfile> profileResponse = service.getUserProfile().execute()
        return profileResponse.body().email
    }

    @GET
    @Path("/ride-eta")
    @Produces(MediaType.APPLICATION_JSON)
    Integer getRideEta(){
        log.info("Getting ride update for ride id $rideId")
        CredentialsSession session = new CredentialsSession(userSessionConfig, credential)
        RidesService service = UberRidesApi.with(session).build().createService()
        Ride rideUpdate = service.getRideDetails(rideId).execute().body();
        if(!rideUpdate || !rideUpdate.pickup){
            log.error("Failed to get ride update")
            return null
        }
        else{
            return rideUpdate.pickup.eta
        }
    }

    @POST
    @Path("/cancel-ride")
    void cancelRide(){
        CredentialsSession session = new CredentialsSession(userSessionConfig, credential)
        RidesService service = UberRidesApi.with(session).build().createService()
        service.cancelCurrentRide().execute()
    }


    @POST
    @Path("/gohome")
    @Consumes(MediaType.APPLICATION_JSON)
    javax.ws.rs.core.Response submit(RideRequest rideRequest){
        float latitude = rideRequest.latitude.toFloat()
        float longitude = rideRequest.longitude.toFloat()
        log.info("Received request for uber ride home with latitude $latitude and longitude $longitude")
        //float latitude = 43.472163
        //float longitude = -80.540497

        float destLatitude = 43.445995f
        float destLongitude = -80.539146f

        CredentialsSession session = new CredentialsSession(userSessionConfig, credential)
        RidesService service = UberRidesApi.with(session).build().createService()
        Response response = service.getProducts(latitude, longitude).execute()
        List<Product> products = response.body().products
        String productId = products.get(0).getProductId()
        log.info("Found product with id $productId")

        RideRequestParameters rideRequestParametersEstimate = new RideRequestParameters.Builder().setPickupCoordinates(latitude, longitude)
                .setProductId(productId)
                .setDropoffCoordinates(destLatitude, destLongitude)
                .build()
        RideEstimate rideEstimate = service.estimateRide(rideRequestParametersEstimate).execute().body()

        if(!rideEstimate){
            log.error("Failed to get ride estimate")
            return javax.ws.rs.core.Response.status(403).entity(rideEstimate).build()
        }
        log.info("Got estimate for uber ride")

        def fare = rideEstimate.fare.value
        log.info("ride fare: $fare")

        def fareId = rideEstimate.fare.fareId
        log.info("fare id is $fareId")
        RideRequestParameters rideRequestParameters = new RideRequestParameters.Builder().setPickupCoordinates(latitude,longitude)
            .setProductId(productId)
            .setFareId(fareId)
            .setDropoffCoordinates(destLatitude, destLongitude)
            .build()
        Ride ride = service.requestRide(rideRequestParameters).execute().body()
        if(!ride){
            log.error("Failed to order ride")
            return javax.ws.rs.core.Response.status(403).entity(rideEstimate).build()
        }
        rideId = ride.rideId

        log.info("Ride created.")
        log.info("ride id: $rideId")
        logRide(ride)

        log.info("Driver accepting ride")
        SandboxRideRequestParameters rideParameters = new SandboxRideRequestParameters.Builder().setStatus("accepted").build()
        Response<Void> updResponse = service.updateSandboxRide(rideId, rideParameters).execute();
        if(updResponse.errorBody()){
            log.error("Not response when trying to update ride request to 'accepted'")
            return javax.ws.rs.core.Response.status(403).entity(rideEstimate).build()
        }

        //sleep(30000)

        Ride rideUpdate = service.getRideDetails(rideId).execute().body();
        logRide(rideUpdate);

        /*
        Response<Void> cancelResponse = service.cancelRide(rideId).execute();
        if(cancelResponse.errorBody() == null){
            log.info("Cancelled ride")
        }else{
            log.error("Failed to cancel ride!")
            return javax.ws.rs.core.Response.status(403).entity(rideEstimate).build()
        }
        */

        return javax.ws.rs.core.Response.status(200).build()
    }

    void logRide(Ride ride){
        log.info("")
        log.info("ride status: $ride.status")
        log.info("")
        if(ride.destination){
            log.info("ETA: $ride.destination.eta ")
        }
        if(ride.driver){
            log.info("The Driver's name is $ride.driver.name and his rating is $ride.driver.rating")
        }
        if(ride.vehicle){
            log.info("They are driving a vehicle with make $ride.vehicle.make and model $ride.vehicle.model")
            log.info("You can look at it here: $ride.vehicle.pictureUrl")
        }
        log.info("")
    }

}
