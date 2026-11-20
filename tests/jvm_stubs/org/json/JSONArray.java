package org.json;
import java.util.*;
public class JSONArray {private final java.util.List<Double> v=new ArrayList<>();
 public JSONArray(){} public JSONArray(String s) throws JSONException { try {String p=s.trim();p=p.substring(1,p.length()-1);if(!p.trim().isEmpty())for(String n:p.split(","))v.add(Double.parseDouble(n.trim()));}catch(Exception e){throw new JSONException("bad json");}}
 public JSONArray put(double d) throws JSONException{if(!Double.isFinite(d))throw new JSONException("not finite");v.add(d);return this;}
 public int length(){return v.size();} public double getDouble(int i){return v.get(i);} public String toString(){return v.toString();}}
