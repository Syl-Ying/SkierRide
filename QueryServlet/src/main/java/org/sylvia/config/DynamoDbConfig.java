package org.sylvia.config;

import software.amazon.awssdk.regions.Region;

public class DynamoDbConfig {
    public static final String SKIER_TABLE_NAME = "skierTable";
    public static final String RESORT_TABLE_NAME = "resortTable";
    public static final Region REGION = Region.US_WEST_2;
}
