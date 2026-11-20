package android.content;
public class Context {
 public static final int MODE_PRIVATE=0;
 public android.content.res.AssetManager getAssets(){return null;}
 public java.io.File getFilesDir(){return new java.io.File(System.getProperty("java.io.tmpdir"));}
 public SharedPreferences getSharedPreferences(String name,int mode){return null;}
}
