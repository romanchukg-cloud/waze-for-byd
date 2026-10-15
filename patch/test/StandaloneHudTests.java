package local.waze.cluster;

import java.util.*;
import java.nio.*;
import java.nio.charset.StandardCharsets;

public final class StandaloneHudTests {
    private static int count;
    static void check(boolean ok,String name){count++;if(!ok)throw new AssertionError(name);}
    private static byte[] decode(String text){String[] h=text.split(",");byte[] bytes=new byte[h.length];for(int i=0;i<h.length;i++)bytes[i]=(byte)Integer.parseInt(h[i],16);return bytes;}
    private static String streetContent(String name) {
        byte[] records=decode(StandaloneHudPackets.street(name));byte[] content=new byte[records.length/13*7];
        for(int off=0;off<records.length;off+=13) {
            int feature=ByteBuffer.wrap(records,off,4).getInt();
            check(feature==0x43FA1008+(off/13)*0x1000,"street feature sequence");
            check(records[off+4]==8&&(records[off+5]&255)==((feature>>>12)&255),"street header");
            System.arraycopy(records,off+6,content,off/13*7,7);
        }
        int len=content[0]&255,sum=0;for(int i=1;i<=len;i++)sum+=content[i]&255;
        check(len<=96&&(len%2)==0,"text bounded to 96 UTF16 bytes");
        check((content[len+1]&255)==(sum&255),"street checksum");
        for(int i=len+2;i<content.length;i++)check((content[i]&255)==255,"street padding");
        return new String(content,1,len,StandardCharsets.UTF_16LE);
    }
    private static final class Marker implements GuidanceSession.Store {
        boolean shown;boolean fail;
        public boolean wasShown(){return shown;}
        public boolean setShown(boolean s){if(fail)return false;shown=s;return true;}
    }
    public static void main(String[] args) {
        String packet=StandaloneHudPackets.guidance(new GuidanceFrame(3,0,300,"",0,0));
        String expected="43,F0,10,18,04,00,00,01,2C,43,F0,10,10,04,00,00,00,02,43,F0,10,30,04,00,00,00,02";
        check(packet.startsWith(expected+","),"published right-turn 300m vector");
        int[] codes={0,0,1,2,3,5,7,8,9,11,45,13,24,46,47,48,49,14,23,10,12,15,18,20,22,16,17,19,21};
        for(int i=2;i<codes.length;i++)check(StandaloneHudPackets.turn(i,0)==codes[i],"AMap table icon "+i);
        for(int exit=1;exit<=10;exit++) {
            check(StandaloneHudPackets.turn(11,exit)==24+exit&&StandaloneHudPackets.turn(12,exit)==24+exit,"right-hand roundabout "+exit);
            check(StandaloneHudPackets.turn(17,exit)==34+exit&&StandaloneHudPackets.turn(18,exit)==34+exit,"left-hand roundabout "+exit);
        }
        check(StandaloneHudPackets.turn(0,0)==-1&&StandaloneHudPackets.turn(1,1)==-1&&StandaloneHudPackets.turn(29,0)==-1,"unknown arrow suppressed");
        check(StandaloneHudPackets.guidance(new GuidanceFrame(0,0,300,"",0,0))==null,"unknown does not become straight");
        check(StandaloneHudPackets.guidance(new GuidanceFrame(3,0,-1,"",0,0))==null,"unknown distance suppressed");
        check(StandaloneHudPackets.guidance(new GuidanceFrame(3,0,16777215,"",0,0))==null,"distance maximum enforced");
        check(StandaloneHudPackets.guidance(new GuidanceFrame(3,0,16777214,"",0,0))!=null,"distance upper boundary");
        check(streetContent("  Main\t\nStreet\u00a0 42  ").equals("Main Street 42"),"whitespace normalized");
        check(streetContent("").equals(" "),"space wipes previous road");
        check(streetContent("Вулиця Київська").equals("Вулиця Київська"),"UTF16LE international road");
        String longRoad=String.join("",Collections.nCopies(47,"a"))+"🚘tail";
        check(streetContent(longRoad).equals(String.join("",Collections.nCopies(47,"a"))),"no split surrogate at boundary");
        check(streetContent("Bad\uD800name\uDC00").equals("Badname"),"malformed surrogates removed");
        check(streetContent(String.join("",Collections.nCopies(80,"b"))).length()==48,"48-unit cap");
        String fw=StandaloneHudGate.FIRMWARE,cert=StandaloneHudGate.CERT;
        check(StandaloneHudGate.matches(33,fw,10601004,true,true,true,null,cert),"reference gate");
        String other="BYD-AUTO/IVI/IVI:13/TP1A.220624.014/eng.build20260812.022600:user/release-keys";
        check(StandaloneHudGate.reject(33,fw,10601004,true,true,true,null,cert)==null,"verified build has no rejection");
        check(StandaloneHudGate.UNTESTED.equals(StandaloneHudGate.reject(33,other,10601004,true,true,true,null,cert)),"other 5.1 build needs opt-in");
        check(!StandaloneHudGate.matches(33,other,10601004,true,true,true,null,cert),"untested build is not auto-enabled");
        check("firmware_not_verified".equals(StandaloneHudGate.reject(33,"BYD-AUTO/DiLink5.0/DiLink5.0:12/x",10601004,true,true,true,null,cert)),"DiLink 5.0 rejected");
        check("receiver_version".equals(StandaloneHudGate.reject(33,other,10601005,true,true,true,null,cert)),"untested build still checks version");
        check("receiver_signature".equals(StandaloneHudGate.reject(33,other,10601004,true,true,true,null,"00")),"untested build still checks signer");
        check("receiver_requires_permission".equals(StandaloneHudGate.reject(33,other,10601004,true,true,true,"x",cert)),"untested build still checks permission");
        check(!StandaloneHudGate.matches(27,fw,10601004,true,true,true,null,cert),"minimum Android");
        check(!StandaloneHudGate.matches(33,"other",10601004,true,true,true,null,cert),"firmware pin");
        check(!StandaloneHudGate.matches(33,fw,10601005,true,true,true,null,cert),"version pin");
        check(!StandaloneHudGate.matches(33,fw,10601004,false,true,true,null,cert),"system app required");
        check(!StandaloneHudGate.matches(33,fw,10601004,true,false,true,null,cert),"enabled required");
        check(!StandaloneHudGate.matches(33,fw,10601004,true,true,false,null,cert),"exported required");
        check(!StandaloneHudGate.matches(33,fw,10601004,true,true,true,"permission",cert),"no receiver permission");
        check(!StandaloneHudGate.matches(33,fw,10601004,true,true,true,null,"bad"),"certificate pin");
        Marker marker=new Marker();List<String> sends=new ArrayList<>();
        StandaloneHudOutput output=new StandaloneHudOutput(p -> {check(marker.shown,"durable ownership before sending");sends.add(p);});
        GuidanceSession session=new GuidanceSession(output,marker,1000);
        GuidanceFrame frame=new GuidanceFrame(3,0,300,"Main Street",1000,100);
        session.end();check(sends.isEmpty(),"idle does not clear another writer");
        session.update(frame,0);check(sends.size()==2&&sends.get(0).equals(StandaloneHudPackets.START),"start before first guidance");
        session.tick(999);check(sends.size()==2,"dedup before 1s");
        session.tick(1000);check(sends.size()==3&&!sends.get(2).equals(StandaloneHudPackets.START),"one-second keepalive without duplicate start");
        session.update(new GuidanceFrame(2,0,200,"Left Street",900,90),1250);check(sends.size()==4,"changed guidance immediately");
        session.end();check(sends.get(4).equals(StandaloneHudPackets.END)&&!marker.shown,"cancel clears");
        session.update(frame,2000);check(sends.get(5).equals(StandaloneHudPackets.START),"new session starts again");
        session.tick(2000+GuidanceSession.STALE_MS);check(sends.get(sends.size()-1).equals(StandaloneHudPackets.END),"stale clears");
        marker.shown=true;
        GuidanceSession recovery=new GuidanceSession(new StandaloneHudOutput(sends::add),marker,1000);
        recovery.end();check(sends.get(sends.size()-1).equals(StandaloneHudPackets.END)&&!marker.shown,"pending-clear recovery");
        marker.fail=true;
        try {recovery.update(frame,0);throw new AssertionError("Expected marker failure");}catch(IllegalStateException expectedFailure){}
        check(!marker.shown,"do not start without durable marker");
        System.out.println("StandaloneHudTests: "+count+" checks passed; no broadcasts sent");
    }
}
