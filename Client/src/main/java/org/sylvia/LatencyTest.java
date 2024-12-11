package org.sylvia;

import io.swagger.client.ApiClient;
import io.swagger.client.ApiException;
import io.swagger.client.ApiResponse;
import io.swagger.client.api.SkiersApi;
import io.swagger.client.model.LiftRide;
import org.sylvia.model.LiftRideEvent;

import java.util.concurrent.BlockingDeque;
import java.util.concurrent.LinkedBlockingDeque;

import static org.sylvia.HttpPostThread.MAX_RETRIES;
import static org.sylvia.HttpPostThread.SERVER_URL;


public class LatencyTest {

    public LatencyTest(Integer numberOfRequestsPerThread) throws InterruptedException {
        System.out.println("Start testing latency.");
        long startTestingTime = System.currentTimeMillis();

        BlockingDeque<LiftRideEvent> testQueue = new LinkedBlockingDeque<>();
        Thread testGenerator = new Thread(new LiftRideProducer(testQueue, numberOfRequestsPerThread));
        testGenerator.start();
        testGenerator.join();

        Thread thread = new Thread(() -> {
            SkiersApi apiInstance = new SkiersApi();
            ApiClient client = apiInstance.getApiClient();
            client.setBasePath(SERVER_URL);

            // Post numOfRequestsPerThread requests
            for (int i = 0; i < numberOfRequestsPerThread; i++) {
                LiftRideEvent liftRideEvent = null;
                try {
                    liftRideEvent = testQueue.take();
                } catch (InterruptedException e) {
                    throw new RuntimeException(e);
                }

                // retry for at most 5 times
                Boolean success = false;
                Integer retries = 0;
                while (!success && retries < MAX_RETRIES) {
                    // Post a new skier to server
                    try {
                        ApiResponse<Void> res = apiInstance.writeNewLiftRideWithHttpInfo(
                                new LiftRide().liftID(liftRideEvent.getLiftRide().getLiftID()).
                                        time(liftRideEvent.getLiftRide().getTime()),
                                liftRideEvent.getResortID(), liftRideEvent.getSeasonID(),
                                liftRideEvent.getDayID(), liftRideEvent.getSkierID());
                        int statusCode = res.getStatusCode();
                        if (statusCode == 201) {
                            // If POST request is successful, increase successfulRequests and log latency
                            success = true;
                        } else if (statusCode >= 400 && statusCode < 600) {
                            retries++;
                        }
                    } catch (ApiException e) {
                        e.printStackTrace();
                    }
                }
            }

        });
        thread.join();

        long endTestingTime = System.currentTimeMillis();
        long testDuration = endTestingTime - startTestingTime;
        System.out.println(String.format("Testing latency for %d request and 1 thread:  %,d ms\n",
                numberOfRequestsPerThread, testDuration));
        // System.out.println("Expected throughput: " + (numberOfRequestsPerThread / (testDuration / 1000.0)) + " RequestsPerSecond \n");
    }
}
