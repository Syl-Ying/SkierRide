package org.sylvia;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.rabbitmq.client.Connection;
import com.rabbitmq.client.ConnectionFactory;
import org.sylvia.config.RabbitMqConfig;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeoutException;
import java.util.logging.Logger;

public class LiftRideConsumer {

    private static final Logger logger = Logger.getLogger(LiftRideConsumer.class.getName());
    private static final Integer NUM_WORKERS = 30;
    private static final DynamoDB dynamoDB = new DynamoDB();
    private static ExecutorService pool;
    private static Connection connection;

    public static void main(String[] args) throws IOException, TimeoutException {
        Gson gson = new Gson();
        ConcurrentHashMap<Integer, List<JsonObject>> skierLiftRidesMap = new ConcurrentHashMap<>();

        ConnectionFactory factory = new ConnectionFactory();
        factory.setHost(RabbitMqConfig.RABBITMQ_ELASTIC_IP);
        factory.setPort(RabbitMqConfig.RABBITMQ_PORT);
        factory.setUsername(RabbitMqConfig.RABBITMQ_USERNAME);
        factory.setPassword(RabbitMqConfig.RABBITMQ_PASSWORD);
        connection = factory.newConnection();
        logger.info("RabbitMQ connection established.");

        dynamoDB.testDynamoDbConnection();

        pool = Executors.newFixedThreadPool(NUM_WORKERS);
        for (int i =0; i < NUM_WORKERS; i++) {
            pool.execute(new WorkerRunnable(connection, gson, logger, skierLiftRidesMap, dynamoDB));
        }

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            logger.info("Shutdown initiated...");
            shutdownResources();
        }));
    }

    private static void shutdownResources() {
        // Close RabbitMQ connection
        if (connection != null && connection.isOpen()) {
            try {
                logger.info("Closing RabbitMQ connection...");
                connection.close();
            } catch (IOException e) {
                logger.severe("Error closing RabbitMQ connection: " + e.getMessage());
            }
        }

        dynamoDB.shutdown();

        // Shutdown thread pool
        if (pool != null) {
            logger.info("Shutting down thread pool...");
            pool.shutdown();
            try {
                if (!pool.awaitTermination(60, java.util.concurrent.TimeUnit.SECONDS)) {
                    logger.warning("Forcing shutdown of thread pool...");
                    pool.shutdownNow();
                }
            } catch (InterruptedException e) {
                logger.warning("Thread pool shutdown interrupted: " + e.getMessage());
                pool.shutdownNow();
            }
        }
        logger.info("All resources shut down successfully.");
    }
}