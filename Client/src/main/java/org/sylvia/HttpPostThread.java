package org.sylvia;

import io.swagger.client.ApiClient;
import io.swagger.client.ApiException;
import io.swagger.client.ApiResponse;
import io.swagger.client.api.SkiersApi;
import io.swagger.client.model.LiftRide;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import java.util.concurrent.BlockingDeque;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

public class HttpPostThread implements Runnable {

    protected static final Integer MAX_RETRIES = 5;
    protected static final String SERVER_URL = "http://44.237.21.217:8080/Servlet_war/"; // servlet EC2
    private static final Log log = LogFactory.getLog(HttpPostThread.class);
    // protected static final String SERVER_URL = "http://ALB-290695466.us-west-2.elb.amazonaws.com/Server_war/";
    // protected static final String SERVER_URL = "http://localhost:8080/Server/";

    private final BlockingDeque<LiftRideEvent> eventQueue;
    private final BlockingDeque<Record> records;
    private final CountDownLatch initialLatch;
    private final CountDownLatch totalLatch;
    private final AtomicInteger successfulRequests;
    private final AtomicInteger failedRequests;
    private final Integer numOfRequestsPerThread;

    public HttpPostThread(CountDownLatch initialLatch, CountDownLatch totalLatch, BlockingDeque<LiftRideEvent> eventQueue, Integer numOfRequestsPerThread,
                          AtomicInteger successfulRequests, AtomicInteger failedRequests,
                          BlockingDeque<Record> records) {
        this.initialLatch = initialLatch;
        this.totalLatch = totalLatch;
        this.eventQueue = eventQueue;
        this.numOfRequestsPerThread = numOfRequestsPerThread;
        this.successfulRequests = successfulRequests;
        this.failedRequests = failedRequests;
        this.records = records;
    }

    /**
     * Make numOfRequestsPerThread times of POST requests.
     * If the POST request is successful, increase successfulRequests, log latency and output to csv.
     * If not, retry for at most 5 times.
     */
    @Override
    public void run() {
        SkiersApi apiInstance = createApiInstance();

        // Post numOfRequestsPerThread requests
        for (int i = 0; i < numOfRequestsPerThread; i++) {
            LiftRideEvent liftRideEvent = fetchEventFromQueue();
            if (liftRideEvent == null) continue;

            // retry for at most 5 times with exponential backoff
            boolean success = handleRequestWithRetries(apiInstance, liftRideEvent);
            if (!success) {
                failedRequests.incrementAndGet();
                log.error("Failed to process event after max retries: " + liftRideEvent);
            }
        }

        // Count down after finishes sending POST requests
        initialLatch.countDown();
        totalLatch.countDown();
    }

    /**
     * Creates and configures an instance of SkiersApi.
     */
    private SkiersApi createApiInstance() {
        SkiersApi apiInstance = new SkiersApi();
        ApiClient client = apiInstance.getApiClient();
        client.setBasePath(SERVER_URL);
        client.setReadTimeout(50000);
        return apiInstance;
    }

    /**
     * Fetches a LiftRideEvent from the event queue.
     */
    private LiftRideEvent fetchEventFromQueue() {
        try {
            return eventQueue.take();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt(); // Restore interrupt status
            log.error("Thread interrupted while fetching event from queue: " + e.getMessage());
            return null;
        }
    }

    private Boolean handleRequestWithRetries(SkiersApi apiInstance, LiftRideEvent liftRideEvent) {
        int retries = 0;
        int baseDelay = 200; // Initial delay in milliseconds
        while (retries < MAX_RETRIES) {
            long startTime = System.currentTimeMillis();
            try {
                ApiResponse<Void> response = apiInstance.writeNewLiftRideWithHttpInfo(
                        new LiftRide()
                                .liftID(liftRideEvent.getLiftRide().getLiftID())
                                .time(liftRideEvent.getLiftRide().getTime()),
                        liftRideEvent.getResortID(),
                        liftRideEvent.getSeasonID(),
                        liftRideEvent.getDayID(),
                        liftRideEvent.getSkierID()
                );
                if (response.getStatusCode() == 201) {
                    logSuccess(startTime, response);
                    return true;
                } else {
                    log.warn("Non-201 response received: " + response.getStatusCode());
                }
            } catch (ApiException e) {
                if (e.getCode() == 429) { // Only retry on 429
                    retries++;
                    applyExponentialBackoff(retries, baseDelay);
                } else {
                    log.error("Non-retriable ApiException: " + e.getMessage());
                }
            }
        }

        return false;
    }

    /**
     * Logs a successful request and records its details.
     */
    private void logSuccess(long startTime, ApiResponse<Void> response) {
        long endTime = System.currentTimeMillis();
        successfulRequests.incrementAndGet();
        records.add(new Record(startTime, "POST", endTime - startTime, response.getStatusCode()));
        // log.info("Request successful: " + response.getStatusCode());
    }

    /**
     * Applies exponential backoff before retrying a request.
     */
    private void applyExponentialBackoff(int retries, int baseDelay) {
        if (retries < MAX_RETRIES) {
            long delay = (long) (baseDelay * Math.pow(2, retries - 1));
            log.warn("Retrying request after " + delay + "ms (Attempt " + retries + ")");
            try {
                Thread.sleep(delay);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt(); // Restore interrupt status
                log.error("Thread interrupted during backoff: " + e.getMessage());
            }
        }
    }
}
