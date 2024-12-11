package org.sylvia.model;

import io.swagger.client.model.LiftRide;

public class LiftRideEvent {

    private int resortID;
    private String seasonID;
    private String dayID;
    private int skierID;
    private LiftRide liftRide;

    public LiftRideEvent(int skierID, int resortID, String seasonID, String dayID, LiftRide liftRide) {
        this.skierID = skierID;
        this.resortID = resortID;
        this.seasonID = seasonID;
        this.dayID = dayID;
        this.liftRide = liftRide;
    }

    public LiftRide getLiftRide() {
        return liftRide;
    }

    public void setLiftRide(LiftRide liftRide) {
        this.liftRide = liftRide;
    }

    public int getSkierID() {
        return skierID;
    }

    public void setSkierID(int skierID) {
        this.skierID = skierID;
    }

    public int getResortID() {
        return resortID;
    }

    public void setResortID(int resortID) {
        this.resortID = resortID;
    }

    public String getSeasonID() {
        return seasonID;
    }

    public void setSeasonID(String seasonID) {
        this.seasonID = seasonID;
    }

    public String getDayID() {
        return dayID;
    }

    public void setDayID(String dayID) {
        this.dayID = dayID;
    }
}
