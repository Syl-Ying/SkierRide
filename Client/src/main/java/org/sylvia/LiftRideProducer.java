package org.sylvia;

import io.swagger.client.model.LiftRide;

import java.util.Random;
import java.util.concurrent.BlockingDeque;

public class LiftRideProducer implements Runnable {

    private int totalEvents;
    private BlockingDeque<LiftRideEvent> eventQueue;
    private Random random = new Random();

    public LiftRideProducer(BlockingDeque<LiftRideEvent> eventQueue, int totalEvents) {
        this.eventQueue = eventQueue;
        this.totalEvents = totalEvents;
    }

    @Override
    public void run() {
        System.out.println("Start generating lift ride events!\n");
        for (int i = 0; i < totalEvents; i++) {
            // Generate a random lift ride event
            LiftRideEvent liftRide = new LiftRideEvent(
                    random.nextInt(100000) + 1, // skierID [1, 100000]
                    random.nextInt(10) + 1, // resortID [1, 10]
                    "2024", // seasonID 2024
                    "1", // dayID 1
                    new LiftRide().liftID(random.nextInt(40) + 1).time(random.nextInt(360) + 1) // liftID [1, 40], time [1, 360]
            );

            try {
                eventQueue.put(liftRide);
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }
        }
    }
}
