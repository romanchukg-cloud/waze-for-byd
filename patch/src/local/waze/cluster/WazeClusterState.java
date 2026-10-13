package local.waze.cluster;

import android.content.Context;
import androidx.car.app.navigation.model.RoutingInfo;
import androidx.car.app.navigation.model.TravelEstimate;
import java.lang.reflect.Method;

/** Read-only observer for the SHA-256-pinned Waze 5.22.90.123 cluster state.
 * Uses Waze's own converters before its template instructions/ETA gate.
 */
public final class WazeClusterState {
    private final Method active, route, turn, routingInfo, travelEstimate;

    public WazeClusterState() throws ReflectiveOperationException {
        Class<?> state = Class.forName("com.waze.car_lib.d.r");
        Class<?> routeType = Class.forName("com.waze.car_lib.q.bt");
        Class<?> turnType = Class.forName("com.waze.car_lib.q.by");
        Class<?> converters = Class.forName("com.waze.app_nav.hub.b.m");
        active = state.getMethod("c");
        route = state.getMethod("a");
        turn = state.getMethod("b");
        routingInfo = converters.getMethod("j", turnType, Context.class);
        travelEstimate = converters.getMethod("i", routeType, String.class);
    }
    public GuidanceFrame frame(Object state, Context context, long wallTime) throws ReflectiveOperationException {
        if (!Boolean.TRUE.equals(active.invoke(state))) return null;
        Object currentTurn = turn.invoke(state);
        if (currentTurn == null) return null;
        Object currentRoute = route.invoke(state);
        RoutingInfo info = (RoutingInfo) routingInfo.invoke(null, currentTurn, context);
        TravelEstimate destination = currentRoute == null ? null
                : (TravelEstimate) travelEstimate.invoke(null, currentRoute, null);
        return WazeGuidance.fromRoutingInfo(info, destination, wallTime);
    }
}
