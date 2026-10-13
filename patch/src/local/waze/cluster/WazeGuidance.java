package local.waze.cluster;

import androidx.car.app.model.Distance;
import androidx.car.app.navigation.model.*;
import static androidx.car.app.navigation.model.Maneuver.*;

/** Converts AndroidX navigation models, without guessing unknown maneuvers. */
public final class WazeGuidance {
    private WazeGuidance() { }

    public static GuidanceFrame fromTrip(Trip trip, long wallTime) {
        if (trip == null || trip.isLoading() || trip.getSteps().isEmpty()) return null;
        TravelEstimate step = trip.getStepTravelEstimates().isEmpty() ? null : trip.getStepTravelEstimates().get(0);
        int last = trip.getDestinationTravelEstimates().size() - 1;
        TravelEstimate destination = last < 0 ? null : trip.getDestinationTravelEstimates().get(last);
        return frame(trip.getSteps().get(0), step == null ? null : step.getRemainingDistance(), destination, wallTime);
    }
    public static GuidanceFrame fromTemplate(NavigationTemplate template, long wallTime) {
        if (!(template.getNavigationInfo() instanceof RoutingInfo)) return null;
        RoutingInfo info = (RoutingInfo) template.getNavigationInfo();
        return fromRoutingInfo(info, template.getDestinationTravelEstimate(), wallTime);
    }
    public static GuidanceFrame fromRoutingInfo(RoutingInfo info, TravelEstimate destination, long wallTime) {
        if (info == null || info.isLoading() || info.getCurrentStep() == null) return null;
        return frame(info.getCurrentStep(), info.getCurrentDistance(), destination, wallTime);
    }
    private static GuidanceFrame frame(Step step, Distance distance, TravelEstimate destination, long wallTime) {
        Maneuver maneuver = step.getManeuver();
        return new GuidanceFrame(maneuver == null ? 0 : icon(maneuver.getType()),
                maneuver == null ? 0 : maneuver.getRoundaboutExitNumber(), meters(distance),
                step.getRoad() == null ? "" : step.getRoad().toString(),
                destination == null ? -1 : meters(destination.getRemainingDistance()), seconds(destination, wallTime));
    }
    public static int meters(Distance distance) {
        if (distance == null) return -1;
        double scale;
        switch (distance.getDisplayUnit()) {
            case Distance.UNIT_METERS: scale = 1; break;
            case Distance.UNIT_KILOMETERS: case Distance.UNIT_KILOMETERS_P1: scale = 1000; break;
            case Distance.UNIT_MILES: case Distance.UNIT_MILES_P1: scale = 1609.344; break;
            case Distance.UNIT_FEET: scale = .3048; break;
            case Distance.UNIT_YARDS: scale = .9144; break;
            default: return -1;
        }
        double value = distance.getDisplayDistance() * scale;
        return !Double.isFinite(value) || value < 0 ? -1 : (int) Math.min(Integer.MAX_VALUE, Math.round(value));
    }
    public static int seconds(TravelEstimate estimate, long wallTime) {
        if (estimate == null) return -1;
        long seconds = estimate.getRemainingTimeSeconds();
        // Waze's Trip builder sometimes supplies only an absolute arrival time.
        if (seconds < 0 && estimate.getArrivalTimeAtDestination() != null) {
            long arrival = estimate.getArrivalTimeAtDestination().getTimeSinceEpochMillis();
            if (arrival > 0) seconds = arrival <= wallTime ? 0 : (arrival - wallTime) / 1000;
        }
        return seconds < 0 ? -1 : (int) Math.min(Integer.MAX_VALUE, seconds);
    }
    public static int icon(int type) {
        switch (type) {
            case TYPE_TURN_NORMAL_LEFT: case TYPE_ON_RAMP_NORMAL_LEFT: case TYPE_OFF_RAMP_NORMAL_LEFT: return 2;
            case TYPE_TURN_NORMAL_RIGHT: case TYPE_ON_RAMP_NORMAL_RIGHT: case TYPE_OFF_RAMP_NORMAL_RIGHT: return 3;
            case TYPE_KEEP_LEFT: case TYPE_TURN_SLIGHT_LEFT: case TYPE_ON_RAMP_SLIGHT_LEFT:
            case TYPE_OFF_RAMP_SLIGHT_LEFT: case TYPE_FORK_LEFT: case TYPE_MERGE_LEFT: return 4;
            case TYPE_KEEP_RIGHT: case TYPE_TURN_SLIGHT_RIGHT: case TYPE_ON_RAMP_SLIGHT_RIGHT:
            case TYPE_OFF_RAMP_SLIGHT_RIGHT: case TYPE_FORK_RIGHT: case TYPE_MERGE_RIGHT: return 5;
            case TYPE_TURN_SHARP_LEFT: case TYPE_ON_RAMP_SHARP_LEFT: return 6;
            case TYPE_TURN_SHARP_RIGHT: case TYPE_ON_RAMP_SHARP_RIGHT: return 7;
            case TYPE_U_TURN_LEFT: case TYPE_ON_RAMP_U_TURN_LEFT: return 8;
            case TYPE_U_TURN_RIGHT: case TYPE_ON_RAMP_U_TURN_RIGHT: return 19;
            case TYPE_STRAIGHT: return 9;
            case TYPE_ROUNDABOUT_ENTER_CCW: case TYPE_ROUNDABOUT_ENTER_AND_EXIT_CCW:
            case TYPE_ROUNDABOUT_ENTER_AND_EXIT_CCW_WITH_ANGLE: return 11;
            case TYPE_ROUNDABOUT_EXIT_CCW: return 12;
            case TYPE_ROUNDABOUT_ENTER_CW: case TYPE_ROUNDABOUT_ENTER_AND_EXIT_CW:
            case TYPE_ROUNDABOUT_ENTER_AND_EXIT_CW_WITH_ANGLE: return 17;
            case TYPE_ROUNDABOUT_EXIT_CW: return 18;
            case TYPE_DESTINATION: case TYPE_DESTINATION_STRAIGHT: case TYPE_DESTINATION_LEFT:
            case TYPE_DESTINATION_RIGHT: return 15;
            default: return 0;
        }
    }
}
