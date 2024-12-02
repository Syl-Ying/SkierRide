package org.sylvia;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Connection;
import com.rabbitmq.client.DeliverCallback;
import org.sylvia.config.RabbitMqConfig;
import org.sylvia.model.LiftRideMessage;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

public class WorkerRunnable implements Runnable {

    private final Logger logger;
    private final Connection connection;
    private final Gson gson;
    // private final ConcurrentHashMap<Integer, List<JsonObject>> skierLiftRidesMap;
    private final DynamoDB ddb;

    public WorkerRunnable(Connection connection, Gson gson, Logger logger,
                          ConcurrentHashMap<Integer, List<JsonObject>> skierLiftRidesMap, DynamoDB ddb) {
        this.connection = connection;
        this.gson = gson;
        this.logger = logger;
        // this.skierLiftRidesMap = skierLiftRidesMap;
        this.ddb = ddb;
    }

    @Override
    public void run() {
        try {
            Channel channel = connection.createChannel();
            channel.queueDeclare(RabbitMqConfig.RABBITMQ_NAME, false, false, false, null);
            channel.basicQos(50); // accept only certain unacked message at a time
            // logger.info("Waiting for messages from queue: " + Constant.RABBITMQ_NAME);

            DeliverCallback deliverCallback = (consumerTag, delivery) -> {
                String msg = new String(delivery.getBody(), StandardCharsets.UTF_8);
                logger.info("Received message: " + msg);

                try {
                    doWork(msg);
                } finally {
                    channel.basicAck(delivery.getEnvelope().getDeliveryTag(), false); // batch ack
                }
            };

            // register a consumer to listen to the queue
            channel.basicConsume(RabbitMqConfig.RABBITMQ_NAME, false, deliverCallback, consumerTag -> {});
        } catch (IOException e) {
            logger.severe("Failed to consume messages: "+ e.getMessage());
        }
    }

    private void doWork(String msg) {
        // parse message
        LiftRideMessage liftRideMessage = gson.fromJson(msg, LiftRideMessage.class);

        Integer skierID = Integer.valueOf(String.valueOf(liftRideMessage.getSkierID()));

        /* A2: store in map
        skierLiftRidesMap.putIfAbsent(skierID, new ArrayList<>());
        synchronized (skierLiftRidesMap.get(skierID)) {
            skierLiftRidesMap.get(skierID).add(jsonObject);
        }*/
        ddb.injectDynamoItem(liftRideMessage);
        logger.info("Lift ride recorded for skierID: " + skierID);
    }
}
