// SPDX-License-Identifier: GPL-3.0-only
package local.waze.cluster;

import java.util.Objects;

/** Values sent to the stock AMap adapter; all distances are metres. */
public final class GuidanceFrame {
    public final int icon, roundaboutExit, turnMeters, routeMeters, routeSeconds;
    /** Waze's AndroidX maneuver type, for local logs only; not part of the sent frame or equality. */
    public final int maneuverType;
    public final String nextRoad;

    public GuidanceFrame(int icon, int exit, int turnMeters, String road, int routeMeters, int routeSeconds) {
        this(icon, exit, turnMeters, road, routeMeters, routeSeconds, -1);
    }
    public GuidanceFrame(int icon, int exit, int turnMeters, String road, int routeMeters, int routeSeconds, int maneuverType) {
        this.maneuverType = maneuverType;
        this.icon = icon;
        this.roundaboutExit = (icon == 11 || icon == 17) ? Math.max(0, exit) : 0;
        this.turnMeters = Math.max(-1, turnMeters);
        this.nextRoad = road == null ? "" : road;
        this.routeMeters = Math.max(-1, routeMeters);
        this.routeSeconds = Math.max(-1, routeSeconds);
    }

    @Override public boolean equals(Object other) {
        if (!(other instanceof GuidanceFrame)) return false;
        GuidanceFrame f = (GuidanceFrame) other;
        return icon == f.icon && roundaboutExit == f.roundaboutExit && turnMeters == f.turnMeters
                && routeMeters == f.routeMeters && routeSeconds == f.routeSeconds && nextRoad.equals(f.nextRoad);
    }
    @Override public int hashCode() {
        return Objects.hash(icon, roundaboutExit, turnMeters, nextRoad, routeMeters, routeSeconds);
    }
}
