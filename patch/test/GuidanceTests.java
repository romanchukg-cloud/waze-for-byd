// SPDX-License-Identifier: GPL-3.0-only
package local.waze.cluster;

import java.util.ArrayList;
import java.util.List;
import androidx.car.app.model.*;
import androidx.car.app.navigation.model.*;
import static androidx.car.app.navigation.model.Maneuver.*;

/** Runs on the JVM and Android; never sends broadcasts. */
public final class GuidanceTests {
    private static int checks;
    static void check(boolean condition, String message) {
        checks++;
        if (!condition) throw new AssertionError(message);
    }
    private static final class Fake implements GuidanceSession.Store, GuidanceSession.Output {
        boolean shown, rejectWrite, rejectSend;
        final List<Object> events = new ArrayList<>();
        public boolean wasShown() { return shown; }
        public boolean setShown(boolean value) { if (rejectWrite) return false; shown = value; return true; }
        public void guidance(GuidanceFrame frame) {
            check(shown, "Ownership must be durable before a guidance send");
            if (rejectSend) throw new IllegalStateException("send failed");
            events.add(frame);
        }
        public void end() { if (rejectSend) throw new IllegalStateException("end failed"); events.add("end"); }
    }
    private static GuidanceFrame frame(int meters) { return new GuidanceFrame(3, 0, meters, "Next road", 12000, 1800); }
    private static void fails(Runnable action) {
        try { action.run(); throw new AssertionError("Expected failure"); } catch (IllegalStateException expected) { }
    }
    private static void lifecycle() {
        Fake f = new Fake(); GuidanceSession s = new GuidanceSession(f, f);
        s.end(); s.tick(0); check(f.events.isEmpty(), "Idle must not clear another app's card");
        s.update(frame(350), 0); check(f.events.size() == 1, "Send first frame immediately");
        s.update(frame(350), 1000); s.tick(4999); check(f.events.size() == 1, "Deduplicate unchanged frames");
        s.tick(5000); check(f.events.size() == 2, "Keepalive at five seconds");
        s.update(frame(250), 6000); check(f.events.size() == 3, "Distance change sends immediately");
        s.tick(11000); s.tick(16000); check(f.events.size() == 5, "Keepalive continues without extending source freshness");
        s.update(frame(250), 66000); check(f.events.size() == 6 && f.shown, "Normal one-minute parked snapshot keeps guidance alive");
        s.tick(66000 + GuidanceSession.STALE_MS - 1); check(f.shown, "Retain guidance until source deadline");
        s.tick(66000 + GuidanceSession.STALE_MS); check(f.events.size() == 8 && !f.shown, "Stale source ends at ninety seconds");
        s.tick(66000 + GuidanceSession.STALE_MS + 1000); check(f.events.size() == 8, "No duplicate idle end");
        long next = 66000 + GuidanceSession.STALE_MS + 2000;
        s.update(frame(100), next); s.update(null, next+1); check(!f.shown, "Loading/empty guidance clears card");
        s.update(frame(90), next+1000); s.end(); check(!f.shown, "Cancellation clears card");

        f = new Fake(); f.shown = true; s = new GuidanceSession(f, f); s.end();
        check(f.events.size() == 1 && !f.shown, "Restart recovers persisted card");
        Fake failure = new Fake(); GuidanceSession retry = new GuidanceSession(failure, failure);
        failure.rejectWrite = true; fails(() -> retry.update(frame(100), 0));
        check(failure.events.isEmpty(), "Never send without durable marker");
        failure.rejectWrite = false; failure.rejectSend = true; fails(() -> retry.tick(1000));
        check(failure.shown, "Failed broadcast preserves recovery marker");
        failure.rejectSend = false; retry.tick(2000); check(failure.events.size() == 1, "Failed send retries");
        failure.rejectSend = true; fails(() -> retry.end()); check(failure.shown, "Failed end preserves ownership");
        failure.rejectSend = false; failure.rejectWrite = true; fails(() -> retry.tick(3000));
        check(failure.shown, "Failed end marker write is retryable");
        failure.rejectWrite = false; retry.tick(4000); check(!failure.shown, "Retry clears marker after successful end");
    }
    private static TravelEstimate estimate(int meters, long seconds, long arrival) {
        return new TravelEstimate.Builder(Distance.create(meters, Distance.UNIT_METERS),
                DateTimeWithZone.create(arrival, 0, "UTC")).setRemainingTimeSeconds(seconds).build();
    }
    private static void conversion() {
        check(WazeGuidance.icon(TYPE_UNKNOWN) == 0 && WazeGuidance.icon(999) == 0, "Unknown must not become straight");
        check(WazeGuidance.icon(TYPE_MERGE_SIDE_UNSPECIFIED) == 0, "Unspecified direction stays unknown");
        check(WazeGuidance.icon(TYPE_DEPART) == 9, "Route start shows straight ahead, not BYD's finish flag");
        check(WazeGuidance.icon(TYPE_STRAIGHT) == 9, "Straight icon");
        check(WazeGuidance.icon(TYPE_TURN_NORMAL_LEFT) == 2 && WazeGuidance.icon(TYPE_TURN_NORMAL_RIGHT) == 3, "Left/right icons");
        check(WazeGuidance.icon(TYPE_TURN_SLIGHT_LEFT) == 4 && WazeGuidance.icon(TYPE_TURN_SLIGHT_RIGHT) == 5, "Slight turn icons");
        check(WazeGuidance.icon(TYPE_TURN_SHARP_LEFT) == 6 && WazeGuidance.icon(TYPE_TURN_SHARP_RIGHT) == 7, "Sharp turn icons");
        check(WazeGuidance.icon(TYPE_U_TURN_LEFT) == 8 && WazeGuidance.icon(TYPE_U_TURN_RIGHT) == 19, "Both driving sides' U-turns");
        check(WazeGuidance.icon(TYPE_ROUNDABOUT_ENTER_AND_EXIT_CCW) == 11 && WazeGuidance.icon(TYPE_ROUNDABOUT_ENTER_AND_EXIT_CW) == 17, "Roundabout direction");
        check(WazeGuidance.icon(TYPE_ROUNDABOUT_EXIT_CCW) == 12 && WazeGuidance.icon(TYPE_ROUNDABOUT_EXIT_CW) == 18, "Roundabout exit icons");
        check(WazeGuidance.icon(TYPE_DESTINATION) == 15, "Destination icon");
        double[] values = {350, 1.2, 1.2, 1, 1, 100, 100};
        int[] expected = {350, 1200, 1200, 1609, 1609, 30, 91};
        for (int unit = 1; unit <= 7; unit++) check(WazeGuidance.meters(Distance.create(values[unit-1], unit)) == expected[unit-1], "Distance unit " + unit);
        check(WazeGuidance.meters(null) == -1, "Unknown distance");
        check(WazeGuidance.meters(Distance.create(Double.MAX_VALUE, Distance.UNIT_KILOMETERS)) == -1, "Nonfinite distance rejected");
        check(WazeGuidance.meters(Distance.create(1e15, Distance.UNIT_METERS)) == Integer.MAX_VALUE, "Distance cannot overflow");
        check(WazeGuidance.seconds(null, 1000) == -1, "Unknown time");
        check(WazeGuidance.seconds(estimate(12000, 1800, 9999999), 1000) == 1800, "Explicit remaining seconds preferred");
        check(WazeGuidance.seconds(estimate(12000, -1, 1801000), 1000) == 1800, "Arrival time fallback uses seconds");
        check(WazeGuidance.seconds(estimate(0, -1, 1000), 2000) == 0, "Past arrival clamps to zero");

        Step step = new Step.Builder().setManeuver(new Maneuver.Builder(TYPE_ROUNDABOUT_ENTER_AND_EXIT_CCW)
                .setRoundaboutExitNumber(3).build()).setRoad("Road after turn").build();
        Trip trip = new Trip.Builder().addStep(step, estimate(350, -1, 1000))
                .addDestination(new Destination.Builder().setName("Destination").build(), estimate(12000, 1800, 1801000)).build();
        GuidanceFrame f = WazeGuidance.fromTrip(trip, 1000);
        check(f.icon == 11 && f.roundaboutExit == 3 && f.turnMeters == 350, "Trip maneuver and distance");
        check(f.nextRoad.equals("Road after turn") && f.routeMeters == 12000 && f.routeSeconds == 1800, "Trip road and totals");
        RoutingInfo info = new RoutingInfo.Builder().setCurrentStep(step, Distance.create(350, Distance.UNIT_METERS)).build();
        ActionStrip strip = new ActionStrip.Builder().addAction(Action.APP_ICON).build();
        NavigationTemplate template = new NavigationTemplate.Builder().setActionStrip(strip).setNavigationInfo(info)
                .setDestinationTravelEstimate(estimate(12000, 1800, 1801000)).build();
        check(f.equals(WazeGuidance.fromTemplate(template, 1000)), "Cluster template and Trip produce same frame");
        check(WazeGuidance.fromTrip(new Trip.Builder().setLoading(true).build(), 1000) == null, "Loading Trip clears stale card");
        check(WazeGuidance.fromTemplate(new NavigationTemplate.Builder().setActionStrip(strip).build(), 1000) == null, "Empty cluster template ends guidance");
        check(new GuidanceFrame(3, 7, -9, null, -9, -9).roundaboutExit == 0, "Exit number only for roundabout entry");
    }
    public static void main(String[] args) {
        lifecycle(); conversion();
        System.out.println("GuidanceTests: " + checks + " checks passed; no broadcasts sent");
    }
}
