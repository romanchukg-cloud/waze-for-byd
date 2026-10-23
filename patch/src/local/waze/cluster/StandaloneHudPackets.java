// SPDX-License-Identifier: GPL-3.0-only
package local.waze.cluster;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

/** Independently encodes the supplied DiLink 5.1 HUD wire contract. No arbitrary feature IDs. */
public final class StandaloneHudPackets {
    public static final String START="43,E0,00,3A,01,02", END="43,E0,00,3A,01,01";
    public static final int MAX_DISTANCE=16777214;
    private static final int[] TURN={0,0,1,2,3,5,7,8,9,11,45,13,24,46,47,48,49,14,23,10,12,15,18,20,22,16,17,19,21};
    private StandaloneHudPackets(){}
    public static int turn(int icon,int exit) {
        if(icon<2||icon>=TURN.length)return -1;
        if(exit>=1&&exit<=10) {
            if(icon==11||icon==12)return 24+exit;
            if(icon==17||icon==18)return 34+exit;
        }
        return TURN[icon];
    }
    public static String guidance(GuidanceFrame frame) {
        int code=turn(frame.icon,frame.roundaboutExit);
        if(code<0||frame.turnMeters<0||frame.turnMeters>MAX_DISTANCE)return null;
        ByteBuffer out=ByteBuffer.allocate(27);
        for(int feature:new int[]{0x43F01018,0x43F01010,0x43F01030})
            out.putInt(feature).put((byte)4).putInt(feature==0x43F01018?frame.turnMeters:code);
        return hex(out.array())+","+street(frame.nextRoad);
    }
    public static String street(String value) {
        // Normalize by code point: remove malformed surrogates, collapse whitespace/control,
        // and keep at most 48 UTF-16 units without cutting a supplementary character.
        StringBuilder clean=new StringBuilder();
        boolean space=false;
        for(int i=0;value!=null&&i<Math.min(value.length(),4096)&&clean.length()<50;) {
            int cp=value.codePointAt(i);i+=Character.charCount(cp);
            if(cp>=0xD800&&cp<=0xDFFF)continue;
            if(Character.isISOControl(cp)||Character.isWhitespace(cp)||Character.isSpaceChar(cp)) {space=clean.length()>0;continue;}
            if(space){clean.append(' ');space=false;}
            clean.appendCodePoint(cp);
        }
        int end=Math.min(48,clean.length());
        if(end>0&&end<clean.length()&&Character.isHighSurrogate(clean.charAt(end-1)))end--;
        String text=clean.substring(0,end).trim();
        byte[] bytes=(text.isEmpty()?" ":text).getBytes(StandardCharsets.UTF_16LE);
        int size=((bytes.length+2+6)/7)*7;
        byte[] content=new byte[size];java.util.Arrays.fill(content,(byte)0xff);
        content[0]=(byte)bytes.length;
        System.arraycopy(bytes,0,content,1,bytes.length);
        int sum=0;for(byte b:bytes)sum+=b&255;content[bytes.length+1]=(byte)sum;
        ByteBuffer records=ByteBuffer.allocate((size/7)*13);
        for(int offset=0;offset<size;offset+=7) {
            int feature=0x43FA1008+(offset/7)*0x1000;
            records.putInt(feature).put((byte)8).put((byte)(feature>>>12)).put(content,offset,7);
        }
        return hex(records.array());
    }
    private static String hex(byte[] bytes) {
        char[] digits="0123456789ABCDEF".toCharArray();
        StringBuilder result=new StringBuilder(bytes.length*3-1);
        for(byte b:bytes){if(result.length()>0)result.append(',');result.append(digits[(b&255)>>>4]).append(digits[b&15]);}
        return result.toString();
    }
}
