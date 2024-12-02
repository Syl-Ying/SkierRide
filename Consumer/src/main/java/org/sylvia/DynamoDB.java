package org.sylvia;


import org.sylvia.config.DynamoDbConfig;
import org.sylvia.model.LiftRideMessage;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.*;
import software.amazon.awssdk.services.dynamodb.waiters.DynamoDbWaiter;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.logging.Logger;

public class DynamoDB {

    private final Logger logger = Logger.getLogger(DynamoDB.class.getName());
    private final DynamoDbClient ddb;

    public DynamoDB() {
        this.ddb = DynamoDbClient.builder()
                .region(DynamoDbConfig.REGION)
                .build();
    }

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
        String skierTableSortKey = seasonID + "#" + dayID;

        updateSkierTable(skierID, skierTableSortKey, liftID, time, vertical, resortID);

        // updateResortSeasons(resortID, seasonID);
    }

    private void updateSkierTable(String skierID, String sortKey, int liftID, int time, int vertical, String resortID) {
        // Update expression
        String updateExpression = "SET #liftList = list_append(if_not_exists(#liftList, :emptyList), :newLift), " +
                "#vertical = if_not_exists(#vertical, :zero) + :vertical, " +
                "#resort = if_not_exists(#resort, :resortID), " +
                "#gsiSk = :gsiSk";

        // Attribute values
        Map<String, AttributeValue> expressionAttributeValues = new HashMap<>();
        expressionAttributeValues.put(":emptyList", AttributeValue.builder().l(Collections.emptyList()).build());
        expressionAttributeValues.put(":newLift", AttributeValue.builder()
                .l(AttributeValue.builder().m(Map.of(
                        "liftID", AttributeValue.builder().n(String.valueOf(liftID)).build(),
                        "time", AttributeValue.builder().n(String.valueOf(time)).build()
                )).build())
                .build());
        expressionAttributeValues.put(":zero", AttributeValue.builder().n("0").build());
        expressionAttributeValues.put(":vertical", AttributeValue.builder().n(String.valueOf(vertical)).build());
        expressionAttributeValues.put(":resortID", AttributeValue.builder().s(resortID).build());
        expressionAttributeValues.put(":gsiSk", AttributeValue.builder().s(sortKey + "#" + skierID).build());

        // Attribute names
        Map<String, String> expressionAttributeNames = new HashMap<>();
        expressionAttributeNames.put("#liftList", "liftList");
        expressionAttributeNames.put("#vertical", "totalVertical");
        expressionAttributeNames.put("#resort", "resortID");
        expressionAttributeNames.put("#gsiSk", "seasonID#dayID#skierID");

        // UpdateItemRequest
        UpdateItemRequest request = UpdateItemRequest.builder()
                .tableName(DynamoDbConfig.SKIER_TABLE_NAME)
                .key(Map.of(
                        "skierID", AttributeValue.builder().s(skierID).build(),
                        "seasonID#dayID", AttributeValue.builder().s(sortKey).build()
                ))
                .updateExpression(updateExpression)
                .expressionAttributeValues(expressionAttributeValues)
                .expressionAttributeNames(expressionAttributeNames)
                .build();

        // Execute the update
        try {
            ddb.updateItem(request);
        } catch (Exception e) {
            logger.warning("Error during updateItem for skierTable: " + e.getMessage());
        }

    }

    private void updateResortSeasons(String resortID, String seasonID) {
        String updateExpression = "ADD #seasons :newSeason";

        // Attribute values
        Map<String, AttributeValue> expressionAttributeValues = new HashMap<>();
        expressionAttributeValues.put(":newSeason", AttributeValue.builder()
                .ss(seasonID)
                .build());

        // Attribute names
        Map<String, String> expressionAttributeNames = new HashMap<>();
        expressionAttributeNames.put("#seasons", "seasonSet");

        // UpdateItemRequest
        UpdateItemRequest request = UpdateItemRequest.builder()
                .tableName(DynamoDbConfig.RESORT_TABLE_NAME)
                .key(Map.of(
                        "resortID", AttributeValue.builder().s(resortID).build()
                ))
                .updateExpression(updateExpression)
                .expressionAttributeValues(expressionAttributeValues)
                .expressionAttributeNames(expressionAttributeNames)
                .build();

        try {
            ddb.updateItem(request);
        } catch (Exception e) {
            logger.warning("Error during updateItem for resortTable: " + e.getMessage());
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
                            KeySchemaElement.builder().attributeName("seasonID#dayID").keyType(KeyType.RANGE).build()
                    )
                    .attributeDefinitions(
                            AttributeDefinition.builder().attributeName("skierID").attributeType(ScalarAttributeType.S).build(),
                            AttributeDefinition.builder().attributeName("resortID").attributeType(ScalarAttributeType.S).build(),
                            AttributeDefinition.builder().attributeName("seasonID#dayID").attributeType(ScalarAttributeType.S).build(),
                            AttributeDefinition.builder().attributeName("seasonID#dayID#skierID").attributeType(ScalarAttributeType.S).build()
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
}
