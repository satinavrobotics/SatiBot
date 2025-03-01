package org.openbot.vehicle;

public class Waypoints {

    // class for a set of waypoints
    // update operation, which replaces the current waypoints with new ones

    //coordinates
    public double lat;
    public double lon;

    public void updateWaypoints(double lat, double lon) {
        this.lat = lat;
        this.lon = lon;
    }
}
