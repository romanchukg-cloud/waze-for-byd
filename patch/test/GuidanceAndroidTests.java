package local.waze.cluster;

import android.content.Intent;
import android.os.Bundle;
import java.util.Arrays;
import java.util.HashSet;

/** Checks actual Android Intent extra types. Does not send these intents. */
public final class GuidanceAndroidTests {
    private static void verify(Intent intent, String... expectedKeys) {
        GuidanceTests.check("AUTONAVI_STANDARD_BROADCAST_SEND".equals(intent.getAction()), "Broadcast action");
        GuidanceTests.check("com.byd.amapservice".equals(intent.getPackage()), "Explicit stock adapter package");
        GuidanceTests.check(intent.getFlags() == 0x01000000, "Include background receiver flag");
        Bundle extras = intent.getExtras();
        GuidanceTests.check(extras.keySet().equals(new HashSet<>(Arrays.asList(expectedKeys))), "Exact broadcast key set; no ETA_TEXT or lanes");
        for (String key : extras.keySet()) {
            Object value = extras.get(key);
            Class<?> expected = key.equals("NEXT_ROAD_NAME") ? String.class : key.startsWith("IS_BYD_") ? Boolean.class : Integer.class;
            GuidanceTests.check(value.getClass() == expected, "Exact extra type: " + key);
        }
        GuidanceTests.check(extras.getBoolean("IS_BYD_MAP") && !extras.getBoolean("IS_BYD_BAIDU_MAP"), "BYD map flags");
    }
    public static void main(String[] args) {
        GuidanceTests.main(args);
        Intent guide = BydGuidanceBridge.guidanceIntent(new GuidanceFrame(17, 4, 350, "Next road", 12000, 1800));
        verify(guide, "KEY_TYPE", "IS_BYD_MAP", "IS_BYD_BAIDU_MAP", "TYPE", "EXTRA_STATE", "EXTRA_IS_FOREGROUND",
                "NEW_ICON", "ROUNG_ABOUT_NUM", "SEG_REMAIN_DIS", "NEXT_ROAD_NAME", "ROUTE_REMAIN_DIS", "ROUTE_REMAIN_TIME");
        GuidanceTests.check(guide.getIntExtra("KEY_TYPE", -1) == 10001 && guide.getIntExtra("NEW_ICON", -1) == 17, "Guidance code and clockwise roundabout");
        GuidanceTests.check(guide.getIntExtra("ROUNG_ABOUT_NUM", -1) == 4 && guide.getIntExtra("SEG_REMAIN_DIS", -1) == 350, "Exit and distance");
        GuidanceTests.check(guide.getIntExtra("ROUTE_REMAIN_TIME", -1) == 1800, "Route time in seconds");
        Intent end = BydGuidanceBridge.endIntent();
        verify(end, "KEY_TYPE", "IS_BYD_MAP", "IS_BYD_BAIDU_MAP", "EXTRA_STATE", "EXTRA_IS_FOREGROUND",
                "NEW_ICON", "SEG_REMAIN_DIS", "NEXT_ROAD_NAME", "ROUTE_REMAIN_DIS", "ROUTE_REMAIN_TIME");
        GuidanceTests.check(end.getIntExtra("KEY_TYPE", -1) == 10019 && end.getIntExtra("EXTRA_STATE", -1) == 9, "End state");
        GuidanceTests.check(end.getIntExtra("EXTRA_IS_FOREGROUND", -1) == 1, "End foreground state");
        for (String key : new String[]{"NEW_ICON", "SEG_REMAIN_DIS", "ROUTE_REMAIN_DIS", "ROUTE_REMAIN_TIME"})
            GuidanceTests.check(end.getIntExtra(key, 0) == -1, "End clears " + key);
        GuidanceTests.check(end.getStringExtra("NEXT_ROAD_NAME").equals(""), "End clears road");
        System.out.println("GuidanceAndroidTests: Intent protocol verified; no broadcasts sent");
    }
}
