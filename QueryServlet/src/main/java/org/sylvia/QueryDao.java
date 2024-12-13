package org.sylvia;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.logging.Logger;
import java.util.stream.Collectors;

import io.swagger.client.model.SkierVertical;
import io.swagger.client.model.SkierVerticalResorts;
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

    /**
     * /resorts/{resortID}/seasons/{seasonID}/day/{dayID}/skiers
     * Get number of unique skiers at resort/season/day
     */
    public Integer getUniqueSkierNumbers(Integer resortID, Integer seasonID, Integer dayID) {
        Set<Integer> uniqueSkiers = new HashSet<>();
        String pk = String.valueOf(resortID);
        String skPrefix = seasonID + "#" + dayID;
        // logger.info(pk);
        // logger.info(skPrefix);

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

    /**
     * GET /skiers/{resortID}/seasons/{seasonID}/days/{dayID}/skiers/{skierID}
     * Get the total vertical for the skier for the specified ski day
     */
    public Integer getDailyVertical(String seasonID, int dayID, int skierID) {
        // get the list of the skier's lift ride vertical for the day, then sum vertical
        String pk = String.valueOf(skierID);
        String skPrefix = seasonID + "#" + dayID;
        // logger.info(pk);
        // logger.info(skPrefix);

        try {
            QueryRequest queryRequest = QueryRequest.builder()
                    .tableName(DynamoDbConfig.SKIER_TABLE_NAME)
                    .keyConditionExpression("#pk = :skierID AND begins_with(#sk, :skPrefix)")
                    .expressionAttributeNames(
                            Map.of(
                                    "#pk", "skierID",
                                    "#sk", "seasonID#dayID#time"
                            )
                    )
                    .expressionAttributeValues(
                            Map.of(
                                    ":skierID", AttributeValue.builder().s(pk).build(),
                                    ":skPrefix", AttributeValue.builder().s(skPrefix).build()
                            )
                    )
                    .projectionExpression("vertical")
                    .build();

            QueryResponse queryResponse = ddb.query(queryRequest);
            // Sum the vertical values
            int totalVertical = queryResponse.items().stream()
                    .mapToInt(item -> Integer.parseInt(item.get("vertical").n()))
                    .sum();

            return totalVertical;
        } catch (Exception e) {
            logger.severe("Error querying DynamoDB: " + e.getMessage());
            e.printStackTrace();
            return 0;
        }
    }

    /**
     * GET /skiers/{skierID}/verticaL
     * Get the total vertical for the skier for specified seasons at the specified resort.
     * If no season is specified, return all seasons.
     */
    public SkierVertical getResortVertical(String skierID, String resortID, String season) {
        String pk = String.valueOf(skierID);
        try {
            QueryRequest queryRequest;
            if (season == null) {
                // Query all seasons
                queryRequest = QueryRequest.builder()
                        .tableName(DynamoDbConfig.SKIER_TABLE_NAME)
                        .keyConditionExpression("#pk = :skierID")
                        .filterExpression("resortID = :resortID")
                        .expressionAttributeNames(
                                Map.of(
                                        "#pk", "skierID"
                                )
                        )
                        .expressionAttributeValues(
                                Map.of(
                                        ":skierID", AttributeValue.builder().s(pk).build(),
                                        ":resortID", AttributeValue.builder().s(resortID).build()
                                )
                        )
                        .projectionExpression("vertical, seasonID")
                        .build();
                // logger.info("debug: first if, season: " + season + " resortID: " + resortID + " skier: " + skierID);
            } else {
                // Query specific season
                String seasonPrefix = season + "#";

                queryRequest = QueryRequest.builder()
                        .tableName(DynamoDbConfig.SKIER_TABLE_NAME)
                        .keyConditionExpression("#pk = :skierID AND begins_with(#sk, :seasonPrefix)")
                        .filterExpression("resortID = :resortID")
                        .expressionAttributeNames(
                                Map.of(
                                        "#pk", "skierID",
                                        "#sk", "seasonID#dayID#time"
                                )
                        )
                        .expressionAttributeValues(
                                Map.of(
                                        ":skierID", AttributeValue.builder().s(pk).build(),
                                        ":seasonPrefix", AttributeValue.builder().s(seasonPrefix).build(),
                                        ":resortID", AttributeValue.builder().s(resortID).build()
                                )
                        )
                        .projectionExpression("vertical, seasonID")
                        .build();
            }

            QueryResponse queryResponse = ddb.query(queryRequest);

            // Aggregate results by seasonID
            Map<String, Integer> seasonVerticalMap = queryResponse.items().stream()
                    .collect(Collectors.groupingBy(
                            item -> item.get("seasonID").s(),
                            Collectors.summingInt(item -> Integer.parseInt(item.get("vertical").n()))
                    ));
            SkierVertical skierVertical = new SkierVertical();
            for (Map.Entry<String, Integer> entry : seasonVerticalMap.entrySet()) {
                skierVertical.addResortsItem(
                        new SkierVerticalResorts()
                                .seasonID(entry.getKey())
                                .totalVert(entry.getValue())
                );
            }

            return skierVertical;
        } catch (Exception e) {
            logger.warning("Error querying DynamoDB: " + e.getMessage());
            e.printStackTrace();
            throw e;
        }
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

    public void shutdown() {
        if (ddb != null) {
            ddb.close();
            logger.info("DynamoDbClient closed");
        }
    }
}
