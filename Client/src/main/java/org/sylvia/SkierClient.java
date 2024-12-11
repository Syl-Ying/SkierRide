package org.sylvia;

import org.sylvia.model.LiftRideEvent;
import org.sylvia.model.Record;

import java.io.IOException;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

public class SkierClient {

    private static final Integer INITIAL_THREADS = 32;
    private static final Integer REQUESTS_PER_THREAD = 1000;
    private static final Integer TOTAL_REQUESTS = 200000;
    private static Boolean isTesting = false;

    public static void main(String[] args) throws InterruptedException, IOException {
        BlockingDeque<LiftRideEvent> queue = new LinkedBlockingDeque<>();
        BlockingDeque<Record> records = new LinkedBlockingDeque<>();
        AtomicInteger successfulRequests = new AtomicInteger(0);
        AtomicInteger failedRequests = new AtomicInteger(0);

        long startTime = System.currentTimeMillis();

        // Use a single thread to generate random lift events
        Thread generatorThread = new Thread(new LiftRideProducer(queue, TOTAL_REQUESTS));
        generatorThread.start();

        // Test latency for a single thread
        if (isTesting) {
            new LatencyTest(1);
            new LatencyTest(10000);
            return;
        }

        // PhaseI: Start initial 32 threads, each sending 1k POST requests, 32k requests in total
        CountDownLatch initialLatch = new CountDownLatch(1); // Latch to signal when one of the first 32 threads is done
        CountDownLatch totalLatch = new CountDownLatch(200);
        sendPostRequests(queue, INITIAL_THREADS, REQUESTS_PER_THREAD, initialLatch, totalLatch, successfulRequests,
                failedRequests, records);
        initialLatch.await();
        System.out.println("One of the initial 32 threads has completed. Starting 168 threads...");

        // PhaseII: Once any of the 32 threads finishes, start 168 threads, each sending 1k POST requests,
        // 168k requests in total
        sendPostRequests(queue, 168, REQUESTS_PER_THREAD, initialLatch, totalLatch, successfulRequests,
                failedRequests, records);

        totalLatch.await();
        long endTime = System.currentTimeMillis();
        long duration = endTime - startTime;

        Integer success = successfulRequests.get();
        Integer failed = failedRequests.get();
        Double throughput = ((success + failed) / (duration / 1000.0));
        System.out.println("---------------------- Report Client1 ----------------------");
        System.out.println(String.format("Number of successful requests: %,d",success));
        System.out.println("Number of failed requests: " + failed);
        System.out.println(String.format("Wall time for all 200k requests using 200 threads: %,d ms", duration));
        System.out.println(String.format("Actual throughput (RPS): %,.2f", throughput));
        System.out.println("----------------------  Report End  ------------------------\n");

        new ReportProcessor("./output.csv", records);
    }

    private static void sendPostRequests(BlockingDeque<LiftRideEvent> queue, Integer numberOfThreads,
                                         Integer numberOfRequestsPerThread, CountDownLatch initialLatch,
                                         CountDownLatch totalLatch, AtomicInteger successfulRequests,
                                         AtomicInteger failedRequests, BlockingDeque<Record> records) {
        for (int i = 0; i < numberOfThreads; i++) {
            Thread httpPostThread = new Thread(new HttpPostThread(initialLatch, totalLatch, queue, numberOfRequestsPerThread,
                    successfulRequests, failedRequests, records));
            httpPostThread.start();
        }
        System.out.println("Created " + numberOfThreads + " threads.");
    }
}
