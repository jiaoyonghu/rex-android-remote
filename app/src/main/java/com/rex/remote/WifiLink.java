package com.rex.remote;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.LinkAddress;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.SystemClock;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/** Foreground-only, authenticated, no replay or offline queue. */
final class WifiLink {
    interface Listener { void status(boolean connected,String state); void reply(RexCommand cmd,String state); }
    private final Context context; private final Listener listener; private final byte[] key;
    private volatile boolean running=true, connected=false;
    private volatile Socket socket;
    private RexCommand pending; private long pendingAt; private boolean pendingBoot;
    private static final class Pending { final RexCommand cmd; final boolean boot; Pending(RexCommand c,boolean b){cmd=c;boot=b;} }
    private final Thread worker;
    WifiLink(Context ctx,String hex,Listener cb){
        context=ctx.getApplicationContext(); listener=cb; key=decode(hex);
        worker=new Thread(this::run,"rex-wifi"); worker.start();
    }
    static byte[] decode(String hex){
        if(hex==null || !hex.matches("[0-9a-fA-F]{64}"))throw new IllegalArgumentException("64位配对密钥");
        byte[] b=new byte[32]; for(int i=0;i<32;i++)b[i]=(byte)Integer.parseInt(hex.substring(2*i,2*i+2),16);return b;
    }
    static String tag(byte[] key,String text)throws Exception{
        Mac m=Mac.getInstance("HmacSHA256");m.init(new SecretKeySpec(key,"HmacSHA256"));
        byte[] b=m.doFinal(text.getBytes(StandardCharsets.US_ASCII));
        StringBuilder s=new StringBuilder(64);for(byte v:b)s.append(String.format(java.util.Locale.ROOT,"%02x",v&255));return s.toString();
    }
    static boolean same(String a,String b){return MessageDigest.isEqual(a.getBytes(StandardCharsets.US_ASCII),b.getBytes(StandardCharsets.US_ASCII));}
    synchronized boolean submit(RexCommand cmd){return submit(cmd,false);}
    synchronized boolean submit(RexCommand cmd,boolean boot){
        if(!running||!connected||!RexCommand.isAction(cmd))return false;
        if(pending!=null && cmd!=RexCommand.STOP && cmd!=RexCommand.DISABLE)return false;
        pending=cmd;pendingBoot=boot;pendingAt=SystemClock.elapsedRealtime();return true;
    }
    private synchronized Pending take(){
        RexCommand c=SystemClock.elapsedRealtime()-pendingAt<=350?pending:null;boolean b=pendingBoot;pending=null;pendingBoot=false;return c==null?null:new Pending(c,b);
    }
    private synchronized void disconnected(){connected=false;pending=null;pendingBoot=false;}
    void close(){running=false;disconnected();Socket s=socket;if(s!=null)try{s.close();}catch(IOException ignored){}worker.interrupt();}
    private Socket open()throws IOException{
        ConnectivityManager cm=(ConnectivityManager)context.getSystemService(Context.CONNECTIVITY_SERVICE);
        if(cm==null)throw new IOException("WiFi unavailable");
        for(Network n:cm.getAllNetworks()){
            NetworkCapabilities c=cm.getNetworkCapabilities(n);LinkProperties p=cm.getLinkProperties(n);
            if(c==null||p==null||!c.hasTransport(NetworkCapabilities.TRANSPORT_WIFI))continue;
            for(LinkAddress a:p.getLinkAddresses()){
                if(!a.getAddress().getHostAddress().startsWith("10.42.0."))continue;
                Socket s=n.getSocketFactory().createSocket();socket=s;
                if(!running){s.close();throw new IOException("closed");}
                try{s.connect(new InetSocketAddress("10.42.0.1",8765),800);
                    s.setSoTimeout(400);s.setTcpNoDelay(true);return s;}
                catch(IOException e){s.close();throw e;}
            }
        }
        throw new IOException("请连接 REX_ROBOT");
    }
    private static String line(InputStream in)throws IOException{
        ByteArrayOutputStream b=new ByteArrayOutputStream();
        while(b.size()<256){int v=in.read();if(v<0)throw new EOFException();if(v==10)return b.toString("US-ASCII");if(v<32||v>126)throw new IOException("bad frame");b.write(v);}
        throw new IOException("frame too long");
    }
    private void run(){
        while(running){
            try(Socket s=open()){
                InputStream in=new BufferedInputStream(s.getInputStream());OutputStream out=s.getOutputStream();
                String[] hello=line(in).split("\\|",-1);
                if(hello.length!=3||!hello[0].equals("REX1")||!hello[1].matches("[0-9a-f]{64}")||!same(hello[2],tag(key,"SERVER|"+hello[1])))throw new IOException("配对密钥错误");
                String nonce=hello[1];long seq=0;String last="";
                while(running){
                    Pending item=take();RexCommand cmd=item==null?null:item.cmd;
                    if(item!=null && item.boot && !last.equals("BOOT_WAIT")){listener.reply(cmd,"BOOT_APPROVAL_EXPIRED");cmd=null;item=null;}
                    String name=cmd==null?"PING":item.boot?"BOOT_APPROVE":cmd.name();String data=(++seq)+"|"+name;
                    out.write((data+"|"+tag(key,nonce+"|"+data)+"\n").getBytes(StandardCharsets.US_ASCII));out.flush();
                    String[] ack=line(in).split("\\|",-1);
                    if(ack.length!=3||!ack[0].equals(Long.toString(seq))||!same(ack[2],tag(key,nonce+"|ACK|"+ack[0]+"|"+ack[1])))throw new IOException("invalid ACK");
                    if(!running)break;
                    connected=true;
                    if(cmd!=null)listener.reply(cmd,ack[1]);
                    else if(!last.equals(ack[1])){last=ack[1];listener.status(true,last);}
                    Thread.sleep(100);
                }
            }catch(Exception e){if(running)listener.status(false,"断开 / "+(e.getMessage()==null?"重连中":e.getMessage()));}
            finally{disconnected();Socket s=socket;socket=null;if(s!=null)try{s.close();}catch(IOException ignored){}}
            if(running)try{Thread.sleep(600);}catch(InterruptedException ignored){}
        }
    }
}
