// SPDX-License-Identifier: GPL-3.0-only
package local.waze.cluster;

import local.waze.cluster.DiLink51Layout.*;

public final class ClusterLayoutTests {
    private static int count;
    static void check(boolean ok,String name){count++;if(!ok)throw new AssertionError(name);}
    private static final String[] NAMES={"Built-in Screen",DiLink51Layout.FULL,DiLink51Layout.SIDE,"shared_fission_bg_XDJAScreenProjection_2"};
    private static final int[] W={1920,1920,1920,1920}, H={1080,720,720,720};
    private static Theme follow(ClusterActivityState s,boolean access){return DiLink51Layout.target(Mode.FOLLOW,s.snapshot(),access);}
    public static void main(String[] args) {
        // Firmware gate and owner profile.
        check(DiLink51Layout.verified(DiLink51Layout.FINGERPRINT)&&DiLink51Layout.verified(DiLink51Layout.LEOPARD5_FINGERPRINT),"verified fingerprints");
        check(DiLink51Layout.FINGERPRINT.equals(StandaloneHudGate.FIRMWARE),"same 5.1 fingerprint as the HUD gate");
        check(!DiLink51Layout.verified("BYD-AUTO/IVI/IVI:12/other")&&!DiLink51Layout.verified(null),"other firmware unverified");
        check(DiLink51Layout.active(Profile.AUTOMATIC,DiLink51Layout.FINGERPRINT),"automatic on verified firmware");
        check(!DiLink51Layout.active(Profile.AUTOMATIC,"DiLink5.0"),"automatic keeps DiLink 5 elsewhere");
        check(DiLink51Layout.active(Profile.DILINK51,"DiLink5.0"),"owner can choose 5.1 layout");
        check(!DiLink51Layout.active(Profile.DILINK5,DiLink51Layout.FINGERPRINT),"owner can keep DiLink 5 layout");
        // Display selection by theme; never substitute the full layer for the side layer.
        check(DiLink51Layout.choose(NAMES,W,H,Theme.MAP)==1,"map uses _0");
        check(DiLink51Layout.choose(NAMES,W,H,Theme.SCENARIO)==2&&DiLink51Layout.choose(NAMES,W,H,Theme.SIMPLE)==2,"side themes use _1");
        check(DiLink51Layout.choose(new String[]{DiLink51Layout.FULL},new int[]{1920},new int[]{720},Theme.SCENARIO)==-1,"no fallback from _1 to _0");
        check(DiLink51Layout.choose(new String[]{"x",DiLink51Layout.SIDE},new int[]{1920,1280},new int[]{720,480},Theme.SIMPLE)==-1,"only 1920x720");
        check(DiLink51Layout.choose(NAMES,W,H,null)==-1,"no theme, no display");
        // Measured geometry.
        Plan map=DiLink51Layout.plan(1920,720,Theme.MAP), side=DiLink51Layout.plan(1920,720,Theme.SCENARIO);
        check(map.left==0&&map.top==144&&map.width==1920&&map.height==480&&map.fullMap,"map viewport");
        check(map.top+map.height==624,"map keeps gear/range clear");
        check(side.left==1320&&side.top==0&&side.width==600&&side.height==720&&!side.fullMap,"side card viewport");
        check(side.left+side.width==1920,"side card reaches the right edge");
        check(DiLink51Layout.plan(1920,720,Theme.SIMPLE).left==1320,"simple uses side card");
        check(DiLink51Layout.plan(1920,1080,Theme.MAP)==null&&DiLink51Layout.plan(1920,720,null)==null,"invalid plans");
        for(Plan p:new Plan[]{map,side})
            check(p.visibleLeft>=0&&p.visibleTop>=0&&p.visibleRight<=p.width&&p.visibleBottom<=p.height
                &&p.visibleLeft<p.visibleRight&&p.visibleTop<p.visibleBottom,"visible area inside surface");
        check(map.visibleLeft==460&&map.visibleRight==1460&&map.visibleTop==64&&map.visibleBottom==432,"map visible area clear of bands");
        check(side.visibleLeft==0&&side.visibleRight==600&&side.visibleBottom==720,"side card fully visible");
        // Contrast defaults follow DiPlay: Simple is dark, Map and Scenario light.
        check(!DiLink51Layout.dark(Contrast.DEFAULT,Theme.MAP)&&DiLink51Layout.dark(Contrast.DEFAULT,Theme.SIMPLE),"default contrast");
        check(DiLink51Layout.dark(Contrast.DARK,Theme.MAP)&&!DiLink51Layout.dark(Contrast.LIGHT,Theme.SIMPLE),"forced contrast");
        // Fixed themes never depend on Usage Access.
        check(DiLink51Layout.target(Mode.MAP,null,false)==Theme.MAP&&DiLink51Layout.target(Mode.SCENARIO,null,false)==Theme.SCENARIO
            &&DiLink51Layout.target(Mode.SIMPLE,null,false)==Theme.SIMPLE,"fixed themes");
        // Following the cluster: no access or no events means no map.
        ClusterActivityState state=new ClusterActivityState();
        check(follow(state,true)==null&&DiLink51Layout.target(Mode.FOLLOW,null,true)==null,"no events, no map");
        state.event("com.byd.sr",ClusterActivityState.SCENARIO,1,1,100);
        check(state.snapshot().theme==Theme.SCENARIO&&follow(state,true)==null,"scenario without mini-map hides");
        state.event("com.byd.automap",ClusterActivityState.MINI_MAP,2,1,110);
        check(follow(state,true)==Theme.SCENARIO,"scenario mini-map shows side card");
        check(follow(state,false)==null,"revoked access hides");
        state.event("com.byd.automap",ClusterActivityState.MINI_MAP,2,2,120);
        check(follow(state,true)==Theme.SCENARIO,"paused activity remains visible");
        state.event("com.byd.automap",ClusterActivityState.MINI_MAP,2,23,130);
        check(follow(state,true)==null,"stopped mini-map hides");
        state.event("com.byd.cluster",ClusterActivityState.SIMPLE,3,1,140);
        state.event("com.byd.launchermap",ClusterActivityState.MINI_MAP,4,1,150);
        check(follow(state,true)==Theme.SIMPLE,"newest theme wins: simple");
        state.event("com.byd.automap",ClusterActivityState.FULL_MAP,5,1,160);
        check(state.snapshot().theme==Theme.MAP&&follow(state,true)==Theme.MAP,"map theme shows full map");
        state.event("com.byd.automap",ClusterActivityState.FULL_MAP,5,23,170);
        check(follow(state,true)==Theme.SIMPLE,"leaving map returns to visible simple card");
        state.event("com.example.app","com.byd.sr.cluster.ClusterActivity",6,1,180);
        state.event("com.byd.sr","com.byd.sr.Other",7,1,190);
        check(state.snapshot().theme==Theme.SIMPLE,"unrelated activities ignored");
        state.event("com.byd.automap",ClusterActivityState.FULL_MAP,8,1,200);
        state.event("com.byd.automap",ClusterActivityState.FULL_MAP,9,1,210);
        state.event("com.byd.automap",ClusterActivityState.FULL_MAP,8,23,220);
        check(follow(state,true)==Theme.MAP,"recreated instance stays visible");
        state.event(null,null,0,26,230);
        check(follow(state,true)==null&&state.snapshot().theme==null,"shutdown resets");
        state.event("com.byd.automap",ClusterActivityState.FULL_MAP,1,1,240);
        state.event(null,null,0,27,250);
        check(follow(state,true)==null,"startup resets");
        check(new ClusterActivityState.Snapshot(Theme.MAP,false).equals(new ClusterActivityState.Snapshot(Theme.MAP,false))
            &&!new ClusterActivityState.Snapshot(Theme.MAP,false).equals(new ClusterActivityState.Snapshot(Theme.MAP,true)),"snapshot equality");
        // Cluster colours: civil twilight at the car's position, fixed schedule without one.
        long summerNoonUtc=1782388800000L; // 2026-06-25 12:00 UTC
        check(!ClusterTheme.twilightNight(summerNoonUtc,50.45,30.52),"Kyiv summer midday is day");
        check(ClusterTheme.twilightNight(summerNoonUtc-11*3600000L,50.45,30.52),"Kyiv summer 01:00 UTC is night");
        check(!ClusterTheme.twilightNight(summerNoonUtc-10*3600000L,69.65,18.96),"Tromso midnight sun");
        check(ClusterTheme.twilightNight(1797076800000L,78.22,15.65),"Svalbard polar night at noon");
        check(ClusterTheme.night(ClusterTheme.MODE_AUTO,summerNoonUtc,12*60,0,0,null,null)==false,"no position: day at noon");
        check(ClusterTheme.night(ClusterTheme.MODE_AUTO,summerNoonUtc,19*60,0,0,null,null),"no position: night after 18:00");
        check(ClusterTheme.night(ClusterTheme.MODE_AUTO,summerNoonUtc,5*60,0,0,null,null),"no position: night before 06:00");
        check(!ClusterTheme.night(ClusterTheme.MODE_NO,0,0,0,0,null,null)&&ClusterTheme.night(ClusterTheme.MODE_YES,0,12*60,0,0,null,null),"head unit light/dark");
        check(ClusterTheme.night(ClusterTheme.MODE_CUSTOM,0,23*60,22*60,7*60,null,null)&&!ClusterTheme.night(ClusterTheme.MODE_CUSTOM,0,12*60,22*60,7*60,null,null),"custom window over midnight");
        // Visible area: the DiLink 5 layout is unchanged at offset 0 and moves within the surface.
        int[] legacy=ClusterTheme.visibleArea(1920,720,null,0);
        check(legacy[0]==672&&legacy[1]==115&&legacy[2]==1229&&legacy[3]==540,"test.5 visible area at offset 0");
        int[] down=ClusterTheme.visibleArea(1920,720,null,10);
        check(down[1]==187&&down[3]==612,"offset +10 moves 72 px down");
        int[] limit=ClusterTheme.visibleArea(1920,720,null,25);
        check(limit[3]==720&&limit[3]-limit[1]==425,"offset keeps height inside the surface");
        int[] up=ClusterTheme.visibleArea(1920,720,null,-25);
        check(up[1]==0&&up[3]==425,"negative offset stops at the top");
        int[] mapUp=ClusterTheme.visibleArea(1920,480,map,-10);
        check(mapUp[0]==460&&mapUp[1]==16&&mapUp[3]==384,"5.1 map area moves too");
        int[] right=ClusterTheme.visibleArea(1920,720,null,0,10);
        check(right[0]==864&&right[2]==1421&&right[1]==115,"horizontal +10 moves 192 px right");
        int[] most=ClusterTheme.visibleArea(1920,720,null,0,25);
        check(most[0]==1152&&most[2]==1709,"horizontal +25 moves 480 px right");
        int[] edge=ClusterTheme.visibleArea(1920,480,map,0,25);
        check(edge[2]==1920&&edge[2]-edge[0]==1000,"horizontal offset stops at the surface edge");
        int[] left=ClusterTheme.visibleArea(600,720,side,0,-25);
        check(left[0]==0&&left[2]==600,"full-width side card cannot move");
        check(ClusterTheme.wazeSideInset(0,32)==0&&ClusterTheme.wazeSideShift(0,32)==0,"edge margin off leaves Waze's insets");
        check(ClusterTheme.wazeSideShift(ClusterTheme.wazeSideInset(32,32),32)==33,"16 dp margin lands on Waze's gutter");
        check(ClusterTheme.wazeSideInset(96,32)==64&&ClusterTheme.wazeSideShift(64,32)==96,"48 dp margin: inset 32 dp plus gutter");
        check(ClusterTheme.wazeSideShift(ClusterTheme.wazeSideInset(10,32),32)==33,"margins under the gutter end at the gutter");
        check(AmapAdapter.choose(null,null).equals(AmapAdapter.BYD),"DiLink 5 adapter first");
        check(AmapAdapter.choose("not_installed",null).equals(AmapAdapter.EXAMPLE),"DiLink 3 adapter when only it is proven");
        check(AmapAdapter.choose("not_installed","not_installed").equals(AmapAdapter.BYD),"unproven falls back to DiLink 5 adapter");
        check(AmapAdapter.choose(null,"no_receiver").equals(AmapAdapter.BYD),"DiLink 5 adapter kept");
        System.out.println("ClusterLayoutTests: "+count+" checks passed");
    }
}
