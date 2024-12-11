package org.sylvia;


import org.sylvia.config.DynamoDbConfig;
import org.sylvia.model.LiftRideMessage;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.*;
import software.amazon.awssdk.services.dynamodb.waiters.DynamoDbWaiter;

import java.util.*;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

public class DynamoDB {

    private final Logger logger = Logger.getLogger(DynamoDB.class.getName());
    private final DynamoDbClient ddb;

    // Buffer for batch processing
    private final List<WriteRequest> buffer = new ArrayList<>();
    private final Object bufferLock = new Object(); // Ensure thread safety

    // Batch write executor
    private final ScheduledExecutorService batchExecutor = Executors.newScheduledThreadPool(1);

    public DynamoDB() {
        this.ddb = DynamoDbClient.builder()
                .region(DynamoDbConfig.REGION)
                .build();

        // Start the batch executor to periodically flush the buffer
        batchExecutor.scheduleAtFixedRate(this::flushBuffer, 0, 1, TimeUnit.SECONDS);
    }

    /**
     * Add a message to the batch buffer
     */
    public void injectDynamoItem(LiftRideMessage liftRideMessage) {
        // Extract values from the message
        String skierID = String.valueOf(liftRideMessage.getSkierID());
        String seasonID = liftRideMessage.getSeasonID();
        String dayID = liftRideMessage.getDayID();
        int liftID = liftRideMessage.getLiftID();
        int time = liftRideMessage.getTime();
        int vertical = liftID * 10; // Calculate vertical gain
        String resortID = String.valueOf(liftRideMessage.getResortID());

        // Composite keys for the base table and GSI
        String skierTableSortKey = seasonID + "#" + dayID + "#" + time;

        synchronized (bufferLock) {
            buffer.add(createItem(skierID, skierTableSortKey, seasonID, dayID, liftID, time, vertical, resortID));

            if (buffer.size() >= 25) {
                flushBuffer();
            }
        }
    }

    /**
     * Create a WriteRequest from a message
     * API: POST /skiers/{resortID}/seasons/{seasonID}/days/{dayID}/skiers/{skierID}
     */
    private WriteRequest createItem(String skierID, String sortKey, String seasonID, String dayID,
                                  int liftID, int time, int vertical, String resortID) {
        // Attribute values
        Map<String, AttributeValue> item = new HashMap<>();
        item.put("skierID", AttributeValue.builder().s(skierID).build()); // Partition Key
        item.put("seasonID#dayID#time", AttributeValue.builder().s(sortKey).build()); // Sort Key
        item.put("seasonID", AttributeValue.builder().s(seasonID).build());
        item.put("dayID", AttributeValue.builder().s(dayID).build());
        item.put("time", AttributeValue.builder().n(String.valueOf(time)).build());
        item.put("LiftID", AttributeValue.builder().n(String.valueOf(liftID)).build());
        item.put("vertical", AttributeValue.builder().n(String.valueOf(vertical)).build());
        item.put("resortID", AttributeValue.builder().s(resortID).build());
        item.put("seasonID#dayID#skierID", AttributeValue.builder().s(seasonID + "#" + dayID + "#" + skierID).build()); // GSI Sort Key

        return WriteRequest.builder().putRequest(PutRequest.builder().item(item).build()).build();
    }

    /**
     * Flush the buffer and send batch write request to DynamoDB
     */
    private void flushBuffer() {
        List<WriteRequest> requestsToFlush;

        synchronized (bufferLock) {
            if (buffer.isEmpty()) {
                return; // Nothing to flush
            }

            // Copy and clear the buffer
            requestsToFlush = new ArrayList<>(buffer);
            buffer.clear();
        }

        // Split into smaller batches if necessary
        for (int i = 0; i < requestsToFlush.size(); i += 25) {
            List<WriteRequest> batch = requestsToFlush.subList(i, Math.min(i + 25, requestsToFlush.size()));

            try {
                BatchWriteItemRequest batchRequest = BatchWriteItemRequest.builder()
                        .requestItems(Map.of(DynamoDbConfig.SKIER_TABLE_NAME, batch))
                        .build();

                BatchWriteItemResponse response = ddb.batchWriteItem(batchRequest);

                // Retry unprocessed items
                if (!response.unprocessedItems().isEmpty()) {
                    handleUnprocessedItems(response.unprocessedItems().get(DynamoDbConfig.SKIER_TABLE_NAME));
                }
            } catch (Exception e) {
                logger.warning("Error during batchWrite: " + e.getMessage());
            }
        }
    }

    /**
     * Retry unprocessed items
     */
    private void handleUnprocessedItems(List<WriteRequest> unprocessedItems) {
        logger.warning("Retrying unprocessed items...");
        try {
            BatchWriteItemRequest retryRequest = BatchWriteItemRequest.builder()
                    .requestItems(Map.of(DynamoDbConfig.SKIER_TABLE_NAME, unprocessedItems))
                    .build();
            ddb.batchWriteItem(retryRequest);
        } catch (Exception e) {
            logger.severe("Error retrying unprocessed items: " + e.getMessage());
        }
    }

    public void testDynamoDbConnection() {
        try {
            // Delete and recreate the table
            deleteTable(ddb, DynamoDbConfig.SKIER_TABLE_NAME);
            createTable(ddb, DynamoDbConfig.SKIER_TABLE_NAME);

            // Test SkierTable
            ddb.describeTable(request -> request.tableName(DynamoDbConfig.SKIER_TABLE_NAME));
            System.out.println("DynamoDB connection successful: " + DynamoDbConfig.SKIER_TABLE_NAME);

            // Test ResortSeasonsTable
            ddb.describeTable(request -> request.tableName(DynamoDbConfig.RESORT_TABLE_NAME));
            System.out.println("DynamoDB connection successful: " + DynamoDbConfig.RESORT_TABLE_NAME);

        } catch (Exception e) {
            System.err.println("Error connecting to DynamoDB: " + e.getMessage());
            throw new RuntimeException("DynamoDB setup test failed", e);
        }
    }

    private void deleteTable(DynamoDbClient dynamoDb, String tableName) {
        try {
            logger.info("Deleting table: " + tableName);

            // Delete the table
            dynamoDb.deleteTable(DeleteTableRequest.builder().tableName(tableName).build());

            // Wait for the table to be deleted
            DynamoDbWaiter waiter = dynamoDb.waiter();
            waiter.waitUntilTableNotExists(
                    DescribeTableRequest.builder().tableName(tableName).build()
            );

            logger.info("Table deleted successfully.");
        } catch (ResourceNotFoundException e) {
            logger.info("Table does not exist. Skipping delete.");
        } catch (Exception e) {
            logger.warning("Error deleting table: " + e.getMessage());
            e.printStackTrace();
        }
    }

    private void createTable(DynamoDbClient dynamoDb, String tableName) {
        try {
            logger.info("Creating table: " + tableName);

            // Create the table
            CreateTableRequest request = CreateTableRequest.builder()
                    .tableName(tableName)
                    .keySchema(
                            KeySchemaElement.builder().attributeName("skierID").keyType(KeyType.HASH).build(),
                            KeySchemaElement.builder().attributeName("seasonID#dayID#time").keyType(KeyType.RANGE).build()
                    )
                    .attributeDefinitions(
                            AttributeDefinition.builder().attributeName("skierID").attributeType(ScalarAttributeType.S).build(),
                            AttributeDefinition.builder().attributeName("resortID").attributeType(ScalarAttributeType.S).build(),
                            AttributeDefinition.builder().attributeName("seasonID#dayID#time").attributeType(ScalarAttributeType.S).build(),
                            AttributeDefinition.builder().attributeName("seasonID#dayID#skierID").attributeType(ScalarAttributeType.S).build() // GSI sorting key
                    )
                    .globalSecondaryIndexes(
                            GlobalSecondaryIndex.builder()
                                    .indexName("ResortIndex") // GSI name
                                    .keySchema(
                                            KeySchemaElement.builder().attributeName("resortID").keyType(KeyType.HASH).build(),
                                            KeySchemaElement.builder().attributeName("seasonID#dayID#skierID").keyType(KeyType.RANGE).build()
                                    )
                                    .projection(Projection.builder()
                                            .projectionType(ProjectionType.KEYS_ONLY)
                                            .build())
                                    .build()
                    )
                    .billingMode(BillingMode.PAY_PER_REQUEST)
                    .build();

            dynamoDb.createTable(request);

            // Wait for the table to be active
            DynamoDbWaiter waiter = dynamoDb.waiter();
            waiter.waitUntilTableExists(
                    DescribeTableRequest.builder().tableName(tableName).build()
            );

            logger.info("Table created successfully.");
        } catch (Exception e) {
            logger.severe("Error creating table: " + e.getMessage());
            e.printStackTrace();
        }
    }

    /**
     * Shut down the batch executor
     */
    public void shutdown() {
        batchExecutor.shutdown();
        try {
            if (!batchExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                batchExecutor.shutdownNow();
            }
        } catch (InterruptedException e) {
            batchExecutor.shutdownNow();
        }
    }
}
