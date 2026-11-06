package com.github.tvbox.osc.musicbox;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.security.KeyStore;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/** Personal Baidu Open Platform OAuth. Credentials never ship in the APK. */
final class BaiduOpenAuth {
    private static final String PREF="musicbox_baidu_oauth", BLOB="encrypted_auth";
    private static final String ALIAS="MusicBoxBaiduOAuth";
    private static final String BASE="https://openapi.baidu.com";
    private BaiduOpenAuth() {}
    static final class Device {
        final String code, userCode, verificationUrl;
        final long expiresAt;
        final int interval;
        Device(JSONObject value){
            code=value.optString("device_code");userCode=value.optString("user_code");
            verificationUrl=value.optString("verification_url");
            expiresAt=System.currentTimeMillis()+Math.max(1,value.optLong("expires_in",300))*1000;
            interval=Math.max(3,value.optInt("interval",5));
        }
    }
    private static String enc(String value)throws Exception{return URLEncoder.encode(value,"UTF-8");}
    private static JSONObject get(String address)throws Exception{
        HttpURLConnection connection=(HttpURLConnection)new URL(address).openConnection();
        connection.setConnectTimeout(8000);connection.setReadTimeout(8000);
        try{
            int status=connection.getResponseCode();InputStream input=status<400?connection.getInputStream():connection.getErrorStream();
            if(input==null)throw new IllegalStateException("百度授权接口 HTTP "+status);
            ByteArrayOutputStream bytes=new ByteArrayOutputStream();try(InputStream stream=input){byte[] buffer=new byte[4096];int n;while((n=stream.read(buffer))!=-1){if(bytes.size()+n>65536)throw new IllegalStateException("授权响应过大");bytes.write(buffer,0,n);}}
            JSONObject result=new JSONObject(bytes.toString("UTF-8"));
            if(status>=400&&!result.has("error")&&!result.has("errno"))throw new IllegalStateException("百度授权接口 HTTP "+status);
            return result;
        }finally{connection.disconnect();}
    }
    static Device start(String appKey)throws Exception{
        JSONObject value=get(BASE+"/oauth/2.0/device/code?response_type=device_code&openapi=xpansdk&client_id="+enc(appKey)+"&scope="+enc("basic,netdisk"));
        if(value.optString("device_code").isEmpty())throw failure(value);
        return new Device(value);
    }
    static JSONObject poll(String appKey,String secret,Device device)throws Exception{
        return get(BASE+"/oauth/2.0/token?grant_type=device_token&openapi=xpansdk&code="+enc(device.code)+"&client_id="+enc(appKey)+"&client_secret="+enc(secret));
    }
    static boolean pending(JSONObject value){
        String error=value.optString("error");
        return "authorization_pending".equals(error)||"slow_down".equals(error)||value.optInt("errno",0)==-1;
    }
    static Exception failure(JSONObject value){
        String error=value.optString("error_description",value.optString("error_msg",value.optString("error",value.optString("errmsg","授权失败"))));
        if(error.length()>180)error=error.substring(0,180);
        return new IllegalStateException("百度授权失败："+error);
    }
    private static SecretKey key()throws Exception{
        KeyStore store=KeyStore.getInstance("AndroidKeyStore");store.load(null);
        if(store.containsAlias(ALIAS))return (SecretKey)store.getKey(ALIAS,null);
        KeyGenerator generator=KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES,"AndroidKeyStore");
        generator.init(new KeyGenParameterSpec.Builder(ALIAS,KeyProperties.PURPOSE_ENCRYPT|KeyProperties.PURPOSE_DECRYPT).setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build());
        return generator.generateKey();
    }
    private static SharedPreferences prefs(Context context){return context.getSharedPreferences(PREF,Context.MODE_PRIVATE);}
    private static JSONObject read(Context context){
        String blob=prefs(context).getString(BLOB,"");if(blob.isEmpty())return new JSONObject();
        try{
            byte[] bytes=Base64.decode(blob,Base64.NO_WRAP);byte[] iv=new byte[12];System.arraycopy(bytes,0,iv,0,iv.length);
            Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");cipher.init(Cipher.DECRYPT_MODE,key(),new GCMParameterSpec(128,iv));
            return new JSONObject(new String(cipher.doFinal(bytes,iv.length,bytes.length-iv.length),"UTF-8"));
        }catch(Exception ignored){return new JSONObject();}
    }
    private static void write(Context context,JSONObject value)throws Exception{
        Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");cipher.init(Cipher.ENCRYPT_MODE,key());
        byte[] iv=cipher.getIV(),data=cipher.doFinal(value.toString().getBytes("UTF-8"));byte[] bytes=new byte[iv.length+data.length];
        System.arraycopy(iv,0,bytes,0,iv.length);System.arraycopy(data,0,bytes,iv.length,data.length);
        if(!prefs(context).edit().putString(BLOB,Base64.encodeToString(bytes,Base64.NO_WRAP)).commit())
            throw new IllegalStateException("无法保存百度授权信息");
    }
    static synchronized void save(Context context,String appKey,String secret,JSONObject token)throws Exception{
        String access=token.optString("access_token"),refresh=token.optString("refresh_token");
        if(access.isEmpty()||refresh.isEmpty())throw failure(token);
        JSONObject value=new JSONObject();value.put("app_key",appKey);value.put("secret",secret);
        value.put("access_token",access);value.put("refresh_token",refresh);
        value.put("expires_at",System.currentTimeMillis()+Math.max(1,token.optLong("expires_in",2592000))*1000);
        write(context,value);
    }
    static synchronized String accessToken(Context context)throws Exception{
        JSONObject value=read(context);String token=value.optString("access_token");
        if(token.isEmpty())return PersonalCloud.prefs(context).getString("baidu_token","").trim();
        if(System.currentTimeMillis()+5*60*1000<value.optLong("expires_at",0))return token;
        String appKey=value.optString("app_key"),secret=value.optString("secret"),refresh=value.optString("refresh_token");
        if(appKey.isEmpty()||secret.isEmpty()||refresh.isEmpty())throw new IllegalStateException("百度授权已过期，请重新授权");
        JSONObject updated=get(BASE+"/oauth/2.0/token?grant_type=refresh_token&openapi=xpansdk&refresh_token="+enc(refresh)+"&client_id="+enc(appKey)+"&client_secret="+enc(secret));
        if(updated.optString("access_token").isEmpty())throw failure(updated);
        save(context,appKey,secret,updated);return updated.optString("access_token");
    }
    static boolean configured(Context context){return !read(context).optString("access_token").isEmpty();}
    static void clear(Context context){prefs(context).edit().remove(BLOB).apply();}
}
