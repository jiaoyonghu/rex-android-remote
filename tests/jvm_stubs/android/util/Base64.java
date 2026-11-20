package android.util;
public final class Base64 {public static final int NO_WRAP=2, DEFAULT=0;
 public static String encodeToString(byte[] bytes,int options){return java.util.Base64.getEncoder().encodeToString(bytes);}
 public static byte[] decode(String s,int options){return java.util.Base64.getDecoder().decode(s);}}
