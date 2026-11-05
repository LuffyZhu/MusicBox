package com.github.tvbox.osc.musicbox;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import com.github.tvbox.osc.server.ControlManager;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Baidu QR handshake ported from OKSwitch; the final cookie is saved through TVBox's pan plugin. */
public final class BaiduNativeLoginActivity extends Activity {
    private static final String UA="com.android.chrome/131.0.6778.200 (Linux;Android 10) AndroidXMedia3/1.5.1";
    private final ExecutorService worker=Executors.newSingleThreadExecutor();
    private final Handler ui=new Handler(Looper.getMainLooper());
    private final Map<String,String> cookies=new LinkedHashMap<>();
    private volatile boolean cancelled;
    private TextView status;
    private ImageView qr;
    private Button retry;
    private int dp(int n){return (int)(n*getResources().getDisplayMetrics().density);}
    @Override public void onCreate(Bundle state){
        super.onCreate(state);
        LinearLayout root=new LinearLayout(this);root.setOrientation(1);root.setPadding(dp(20),dp(28),dp(20),dp(20));root.setBackgroundColor(0xff111827);
        TextView title=new TextView(this);title.setText("百度网盘扫码登录");title.setTextSize(24);title.setTextColor(-1);root.addView(title);
        TextView info=new TextView(this);info.setText("使用百度网盘 App 扫描下方登录码并确认。资源分享二维码无法登录。");info.setTextColor(0xff98a8bd);info.setTextSize(14);info.setPadding(0,dp(12),0,dp(16));root.addView(info);
        qr=new ImageView(this);qr.setBackgroundColor(-1);qr.setScaleType(ImageView.ScaleType.FIT_CENTER);root.addView(qr,new LinearLayout.LayoutParams(-1,dp(300)));
        status=new TextView(this);status.setTextColor(-1);status.setTextSize(16);status.setGravity(Gravity.CENTER);status.setPadding(0,dp(18),0,dp(18));root.addView(status);
        retry=new Button(this);retry.setText("重新生成登录码");retry.setOnClickListener(v->startLogin());root.addView(retry);
        setContentView(root);getWindow().setStatusBarColor(0xff111827);getWindow().setNavigationBarColor(0xff111827);
        startLogin();
    }
    private void message(String value){ui.post(()->{if(!isFinishing()&&!isDestroyed())status.setText(value);});}
    private void startLogin(){
        cancelled=true;
        worker.execute(()->{
            cancelled=false;cookies.clear();
            ui.post(()->{qr.setImageDrawable(null);retry.setEnabled(false);status.setText("正在生成百度登录码…");});
            try{login();}
            catch(UnknownHostException error){message("登录未完成：手机无法解析百度登录服务器，请切换网络或检查私人 DNS 后重试");}
            catch(Exception error){message("登录未完成："+error.getMessage());}
            finally{ui.post(()->retry.setEnabled(true));}
        });
    }
    private static String enc(String value)throws Exception{return URLEncoder.encode(value,"UTF-8");}
    private static String stamp(){return Long.toString(System.currentTimeMillis()*1000L);}
    private static JSONObject jsonp(String text)throws Exception{
        int first=text.indexOf('('),last=text.lastIndexOf(')');
        return new JSONObject(first>=0&&last>first?text.substring(first+1,last):text);
    }
    private static String quoted(String body,String key){
        java.util.regex.Matcher match=java.util.regex.Pattern.compile("['\"]"+key+"['\"]\\s*:\\s*['\"]([^'\"]+)['\"]").matcher(body);
        return match.find()?match.group(1):"";
    }
    private static final class Response {
        final int code;final byte[] body;final String location;
        Response(int code,byte[] body,String location){this.code=code;this.body=body;this.location=location;}
        String text()throws Exception{return new String(body,"UTF-8");}
    }
    private Response request(String address,String method,String body,boolean follow)throws Exception{
        String current=address;
        for(int redirect=0;redirect<9;redirect++){
            if(cancelled)throw new IllegalStateException("已取消");
            HttpURLConnection conn=(HttpURLConnection)new URL(current).openConnection();
            conn.setInstanceFollowRedirects(false);conn.setConnectTimeout(6000);conn.setReadTimeout(15000);
            conn.setRequestProperty("User-Agent",UA);conn.setRequestProperty("Referer","https://pan.baidu.com/");
            conn.setRequestProperty("Origin","https://pan.baidu.com");
            if(!cookies.isEmpty())conn.setRequestProperty("Cookie",cookie());
            if("POST".equals(method)){
                conn.setRequestMethod("POST");conn.setDoOutput(true);
                conn.setRequestProperty("Content-Type","application/x-www-form-urlencoded; charset=UTF-8");
                conn.getOutputStream().write(body.getBytes("UTF-8"));
            }
            int code=conn.getResponseCode();
            for(Map.Entry<String,List<String>> entry:conn.getHeaderFields().entrySet()){
                if(entry.getKey()==null||!entry.getKey().equalsIgnoreCase("Set-Cookie"))continue;
                for(String header:entry.getValue()){int end=header.indexOf(';'),equal=header.indexOf('=');if(equal>0){String name=header.substring(0,equal),value=header.substring(equal+1,end<0?header.length():end);if(!value.isEmpty())cookies.put(name,value);}}
            }
            String location=conn.getHeaderField("Location");
            ByteArrayOutputStream bytes=new ByteArrayOutputStream();
            try(InputStream stream=code>=400?conn.getErrorStream():conn.getInputStream()){
                if(stream!=null){byte[] buffer=new byte[8192];int count;while((count=stream.read(buffer))!=-1){if(bytes.size()+count>5*1024*1024)throw new IllegalStateException("响应过大");bytes.write(buffer,0,count);}}
            }finally{conn.disconnect();}
            if(follow&&code>=300&&code<400&&location!=null&&!location.isEmpty()){
                current=new URL(new URL(current),location).toString();method="GET";body="";continue;
            }
            return new Response(code,bytes.toByteArray(),location);
        }
        throw new IllegalStateException("登录跳转过多");
    }
    private Response requestRetry(String url,String method,String body,boolean follow)throws Exception{
        UnknownHostException last=null;
        for(int attempt=0;attempt<3;attempt++){
            try{return request(url,method,body,follow);}
            catch(UnknownHostException error){last=error;if(cancelled)throw error;Thread.sleep(1800);}
        }
        throw last;
    }
    private Response get(String url)throws Exception{return requestRetry(url,"GET","",true);}
    private String cookie(){StringBuilder all=new StringBuilder();for(Map.Entry<String,String> entry:cookies.entrySet()){if(all.length()>0)all.append("; ");all.append(entry.getKey()).append('=').append(entry.getValue());}return all.toString();}
    private void mirror(){
        if(!cookies.containsKey("BDUSS")&&cookies.containsKey("BDUSS_BFESS"))cookies.put("BDUSS",cookies.get("BDUSS_BFESS"));
        for(String name:new String[]{"BDUSS","STOKEN","PTOKEN","UBI","BAIDUID"})
            if(cookies.containsKey(name))cookies.put(name+"_BFESS",cookies.get(name));
        if(cookies.containsKey("BDUSS"))cookies.put("newlogin","1");
    }
    private boolean verified()throws Exception{
        Response response=requestRetry("https://pan.baidu.com/api/gettemplatevariable?channel=chunlei&web=1&app_id=250528&clienttype=0","POST","fields=%5B%22bdstoken%22%5D",true);
        JSONObject json=new JSONObject(response.text()),result=json.optJSONObject("result");
        if(response.code==200&&json.optInt("errno",-1)==0&&result!=null&&!result.optString("bdstoken").isEmpty())return true;
        response=get("https://pan.baidu.com/api/gettemplatevariable?channel=chunlei&web=1&app_id=250528&clienttype=0&fields=%5B%22bdstoken%22%5D");
        json=new JSONObject(response.text());result=json.optJSONObject("result");
        return response.code==200&&json.optInt("errno",-1)==0&&result!=null&&!result.optString("bdstoken").isEmpty();
    }
    private void login()throws Exception{
        String gid=UUID.randomUUID().toString().toUpperCase(Locale.ROOT),time=stamp();
        String logPage="traceId:pc_loginv5_"+time.substring(0,13)+",logPage:loginv5";
        JSONObject start=jsonp(get("https://passport.baidu.com/v2/api/getqrcode?lp=pc&qrloginfrom=pc&gid="+gid+"&callback=tangram_guid_"+time+"&apiver=v3&tt="+time+"&tpl=netdisk&logPage="+enc(logPage)+"&_="+time).text());
        String sign=start.optString("sign"),image=start.optString("imgurl");
        if(start.optInt("errno",-1)!=0||sign.isEmpty()||image.isEmpty())throw new IllegalStateException("百度未返回有效登录码");
        if(!image.startsWith("http"))image="https://"+image;
        byte[] imageBytes=get(image).body;
        Bitmap bitmap=BitmapFactory.decodeByteArray(imageBytes,0,imageBytes.length);
        if(bitmap==null)throw new IllegalStateException("登录码图片无法读取");
        ui.post(()->qr.setImageBitmap(bitmap));message("请用百度网盘 App 扫码确认");
        String temporary="",callback="tangram_guid_"+(System.currentTimeMillis()/1000L);long deadline=System.currentTimeMillis()+90000L;
        while(!cancelled&&System.currentTimeMillis()<deadline){
            Thread.sleep(2000);String pollTime=stamp();
            JSONObject poll;
            try{poll=jsonp(get("https://passport.baidu.com/channel/unicast?channel_id="+enc(sign)+"&gid="+gid+"&tpl=netdisk&_sdkFrom=1&callback="+callback+"&apiver=v3&tt="+pollTime+"&_="+pollTime).text());}
            catch(UnknownHostException error){message("网络暂时无法解析百度服务器，保留登录码并继续重试…");continue;}
            catch(java.net.SocketTimeoutException error){message("百度状态读取超时，继续重试…");continue;}
            String channel=poll.optString("channel_v");
            if(channel.isEmpty())continue;
            JSONObject state=new JSONObject(channel);int code=state.optInt("status",-1);
            if(code==1)message("已扫码，请在百度网盘 App 确认登录");
            if(code==0){temporary=state.optString("v");if(!temporary.isEmpty())break;}
        }
        if(temporary.isEmpty())throw new IllegalStateException("登录码已过期；若多次出现网络错误，请检查网络或私人 DNS 后重试");
        message("扫码已确认，正在交换登录凭据…");String loginTime=stamp();
        String url="https://passport.baidu.com/v3/login/main/qrbdusslogin?v="+loginTime+"&bduss="+enc(temporary)+"&u="+enc("https%3A%2F%2Fpan.baidu.com%2Fdisk%2Fmain")+"&loginVersion=v5&qrcode=1&tpl=netdisk&maskId=&fileId=&apiver=v3&tt="+loginTime+"&traceid=&time="+(System.currentTimeMillis()/1000L)+"&alg=v3&elapsed=1&callback=bd__cbs__"+gid.substring(0,6).toLowerCase(Locale.ROOT);
        Response exchange=requestRetry(url,"GET","",false);
        String bduss=quoted(exchange.text(),"bduss"),stoken=quoted(exchange.text(),"stoken");
        if(!bduss.isEmpty())cookies.put("BDUSS",bduss);
        if(!stoken.isEmpty())cookies.put("STOKEN",stoken);
        mirror();
        if(!cookies.containsKey("BDUSS"))throw new IllegalStateException("百度未返回正式登录凭据");
        if(exchange.location!=null&&!exchange.location.isEmpty()){message("正在完成登录跳转…");get(new URL(new URL(url),exchange.location).toString());}
        message("正在激活百度网盘会话…");
        get("https://passport.baidu.com/v3/login/api/auth/?return_type=5&tpl=netdisk&u="+enc("https://pan.baidu.com/disk/main"));
        get("https://pan.baidu.com/");mirror();cookies.put("PANWEB","1");
        message("正在验证百度网盘登录…");
        if(!verified())throw new IllegalStateException("百度网盘未确认登录状态，请重新扫码");
        String endpoint=ControlManager.get().getAddress(true)+"proxy?do=pan&action=setBaiduCookie&value="+enc(cookie());
        JSONObject saved=new JSONObject(get(endpoint).text());
        if(saved.optInt("code",-1)!=0)throw new IllegalStateException("已登录，但写入 TVBox 配置失败："+saved.optString("msg"));
        PersonalCloud.prefs(this).edit().putString("baidu_cookie",cookie()).apply();
        message("百度网盘已登录并写入配置，可以返回搜索");
    }
    @Override protected void onDestroy(){cancelled=true;worker.shutdownNow();super.onDestroy();}
}
