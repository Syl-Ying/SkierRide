package org.sylvia;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.logging.Logger;

import org.sylvia.config.DynamoDbConfig;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.QueryRequest;
import software.amazon.awssdk.services.dynamodb.model.QueryResponse;

public class QueryDao {

    private final Logger logger = Logger.getLogger(QueryDao.class.getName());
    private static QueryDao instance;
    private final DynamoDbClient ddb;

    private QueryDao() {
        ddb = DynamoDbClient.builder()
                .region(DynamoDbConfig.REGION)
                .build();
    }

    public static synchronized QueryDao getInstance() {
        if (instance == null) {
            instance = new QueryDao();
        }
        return instance;
    }

 /*   public void injectDynamoItem() {
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
*/

    /**
     * Get result from GSI and count unique result numbers
     */
    public Integer getUniqueSkierNumbers(Integer resortID, Integer seasonID, Integer dayID) {
        Set<Integer> uniqueSkiers = new HashSet<>();
        String pk = String.valueOf(resortID);
        String skPrefix = seasonID + "#" + dayID;
        logger.info(pk);
        logger.info(skPrefix);

        try {
            // Query the GSI
            QueryRequest queryRequest = QueryRequest.builder()
                    .tableName(DynamoDbConfig.SKIER_TABLE_NAME)
                    .indexName("ResortIndex") // Name of the GSI
                    .keyConditionExpression("#resortID = :resortID AND begins_with(#sortKey, :skPrefix)")
                    .expressionAttributeNames( // # special character
                            Map.of(
                                    "#resortID", "resortID",
                                    "#sortKey", "seasonID#dayID#skierID" // Use placeholder for attribute name
                            )
                    )
                    .expressionAttributeValues(
                            Map.of(
                                    ":resortID", AttributeValue.builder().s(pk).build(),
                                    ":skPrefix", AttributeValue.builder().s(skPrefix).build()
                            )
                    )
                    .projectionExpression("#sortKey") // Only fetch necessary attributes
                    .build();

            QueryResponse queryResponse;
            do {
                queryResponse = ddb.query(queryRequest);

                // Extract skier IDs from the sort key
                queryResponse.items().forEach(item -> {
                    String sk = item.get("seasonID#dayID#skierID").s();
                    String[] parts = sk.split("#");
                    if (parts.length == 3) {
                        uniqueSkiers.add(Integer.parseInt(parts[2]));
                    }
                });

                // Check if there is more data to fetch
                queryRequest = queryRequest.toBuilder()
                        .exclusiveStartKey(queryResponse.lastEvaluatedKey())
                        .build();

            } while (queryResponse.hasLastEvaluatedKey());

        } catch (Exception e) {
            logger.severe("Error querying DynamoDB: " + e.getMessage());
            throw new RuntimeException("Failed to query unique skier numbers", e);
        }

        return uniqueSkiers.size();

    }

    public void testDynamoDbConnection() {
        try {
            // Test SkierTable
            ddb.describeTable(request -> request.tableName(DynamoDbConfig.SKIER_TABLE_NAME));
            logger.info("DynamoDB connection successful: " + DynamoDbConfig.SKIER_TABLE_NAME);

        } catch (Exception e) {
            logger.severe("Error connecting to DynamoDB: " + e.getMessage());
            throw new RuntimeException("DynamoDB setup test failed", e);
        }
    }
}
