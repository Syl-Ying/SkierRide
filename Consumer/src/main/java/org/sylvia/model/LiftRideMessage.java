package org.sylvia.model;

import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class LiftRideMessage {

    private final int skierID;
    private final int resortID;
    private final String seasonID;
    private final String dayID;
    private final int time;
    private final int liftID;
}
