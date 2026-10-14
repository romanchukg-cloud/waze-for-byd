package local.waze.diagnostics;

import java.io.*;
import java.net.*;
import javax.net.ssl.HttpsURLConnection;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.*;

/** One fixed TLS origin; redirects, credentials in URLs and custom trust managers forbidden. */
interface ReportTransport {
    final class Reply {
        final int code; final String body; final long retryMillis;
        Reply(int code,String body,long retryMillis){this.code=code;this.body=body;this.retryMillis=retryMillis;}
    }
    Reply request(String method,String id,String body,String key,String token) throws Exception;
    default Reply capabilities() throws Exception {return new Reply(503,"{}",0);}
    final class Https implements ReportTransport {
        public Reply capabilities() throws Exception {return exchange("GET",null,null,null,null,true);}
        public Reply request(String method,String id,String body,String key,String token) throws Exception {
            return exchange(method,id,body,key,token,false);
        }
        private Reply exchange(String method,String id,String body,String key,String token,boolean capabilities) throws Exception {
            if(id!=null && !ReportEngine.validId(id))throw new IOException("Invalid receipt");
            URL url=new URL("https://diagnostics.localcharge.app/"+(capabilities?"":"v1/reports"+(id==null?"":"/"+id)));
            HttpsURLConnection c=(HttpsURLConnection)url.openConnection();
            c.setInstanceFollowRedirects(false); c.setConnectTimeout(10000); c.setReadTimeout(15000);
            c.setRequestMethod(method); c.setRequestProperty("Accept","application/json");
            if(!capabilities)c.setRequestProperty("X-Report-Token",token);
            try {
                if("POST".equals(method)) {
                    byte[] bytes=body.getBytes(StandardCharsets.UTF_8);
                    c.setRequestProperty("Content-Type","application/json");c.setRequestProperty("Idempotency-Key",key);
                    c.setDoOutput(true);c.setFixedLengthStreamingMode(bytes.length);
                    try(OutputStream out=c.getOutputStream()){out.write(bytes);}
                }
                int code=c.getResponseCode();
                long retry=retryAfter(c.getHeaderField("Retry-After"),System.currentTimeMillis());
                InputStream stream=code>=400?c.getErrorStream():c.getInputStream();
                ByteArrayOutputStream out=new ByteArrayOutputStream();
                if(stream!=null)try(InputStream in=stream) {
                    byte[] bytes=new byte[4096];int n;
                    while((n=in.read(bytes))!=-1) {if(out.size()+n>65536)throw new IOException("Response too large");out.write(bytes,0,n);}
                }
                return new Reply(code,new String(out.toByteArray(),StandardCharsets.UTF_8),retry);
            } finally {c.disconnect();}
        }
        static long retryAfter(String value,long now) {
            if(value==null)return 0;
            try {long seconds=Long.parseLong(value.trim());return seconds<=0?0:Math.min(seconds,31536000)*1000;}
            catch(NumberFormatException ignored) {
                try {SimpleDateFormat f=new SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz",Locale.US);f.setLenient(false);f.setTimeZone(TimeZone.getTimeZone("GMT"));return Math.max(0,f.parse(value).getTime()-now);}
                catch(Exception invalid){return 0;}
            }
        }
    }
}
