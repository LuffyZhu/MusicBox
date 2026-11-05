package com.github.tvbox.osc.musicbox;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.view.Gravity;
import android.webkit.WebChromeClient;
import android.webkit.JavascriptInterface;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.LinearLayout;
import android.widget.TextView;
import com.github.catvod.crawler.Spider;
import com.github.tvbox.osc.api.ApiConfig;
import com.github.tvbox.osc.bean.SourceBean;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.Inet4Address;
import java.net.NetworkInterface;
import java.net.URL;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Opens the original PanConfigGuard web UI from the same Android app process. */
public final class ConfigCenterActivity extends Activity {
    private final ExecutorService worker=Executors.newSingleThreadExecutor();
    private WebView web;
    private TextView status;
    private boolean destroyed,loading,pageFailed;

    private int dp(int n){return (int)(n*getResources().getDisplayMetrics().density);}
    @Override public void onCreate(Bundle saved){
        super.onCreate(saved);
        int bg=0xff111827;
        getWindow().setStatusBarColor(bg);
        getWindow().setNavigationBarColor(bg);
        LinearLayout root=new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(bg);
        LinearLayout header=new LinearLayout(this);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(dp(12),dp(8),dp(12),dp(4));
        TextView back=new TextView(this);
        back.setText("‹");back.setTextSize(30);back.setTextColor(Color.WHITE);
        back.setPadding(dp(10),0,dp(20),0);back.setOnClickListener(v->finish());
        header.addView(back);
        TextView title=new TextView(this);
        title.setText("网盘登录");title.setTextSize(20);title.setTextColor(Color.WHITE);
        header.addView(title,new LinearLayout.LayoutParams(0,-2,1));
        TextView baiduLogin=new TextView(this);
        baiduLogin.setText("百度扫码");baiduLogin.setTextSize(15);baiduLogin.setTextColor(0xffffbd55);
        baiduLogin.setPadding(dp(12),dp(8),dp(12),dp(8));
        baiduLogin.setOnClickListener(v->startActivity(new Intent(this,BaiduNativeLoginActivity.class)));
        header.addView(baiduLogin);root.addView(header);
        status=new TextView(this);
        status.setTextColor(0xff98a8bd);status.setTextSize(13);
        status.setPadding(dp(20),dp(8),dp(20),dp(8));
        status.setOnClickListener(v->openConfig());
        root.addView(status);
        web=new WebView(this);
        web.setBackgroundColor(bg);
        web.getSettings().setJavaScriptEnabled(true);
        web.getSettings().setDomStorageEnabled(true);
        web.addJavascriptInterface(new Object(){
            @JavascriptInterface public void open(){runOnUiThread(()->startActivity(new Intent(ConfigCenterActivity.this,BaiduNativeLoginActivity.class)));}
        },"MusicBoxBaidu");
        web.setWebChromeClient(new WebChromeClient());
        web.setWebViewClient(new WebViewClient(){
            @Override public WebResourceResponse shouldInterceptRequest(WebView view,WebResourceRequest request){
                return adjustedConfigPage(request.getUrl().toString());
            }
            @Override public void onPageStarted(WebView view,String url,android.graphics.Bitmap icon){
                pageFailed=false;
            }
            @Override public void onPageFinished(WebView view,String url){
                if(!destroyed&&!pageFailed)status.setText("百度登录请点“百度网盘”的扫码登录；“百度注册”二维码是分享链接。登录后可点刷新状态。");
            }
            @Override public void onReceivedError(WebView view,WebResourceRequest request,WebResourceError error){
                if(!destroyed&&request.isForMainFrame()){
                    pageFailed=true;
                    status.setText("网页加载失败："+error.getDescription()+"。点击这里重试。");
                }
            }
        });
        root.addView(web,new LinearLayout.LayoutParams(-1,0,1));
        setContentView(root);
        openConfig();
    }
    private static WebResourceResponse adjustedConfigPage(String address){
        HttpURLConnection connection=null;
        try{
            URL url=new URL(address);
            String query=url.getQuery();
            if(!"/proxy".equals(url.getPath())||query==null||!query.contains("do=pan")||!query.contains("action=config"))return null;
            connection=(HttpURLConnection)url.openConnection();
            connection.setConnectTimeout(3000);
            connection.setReadTimeout(5000);
            if(connection.getResponseCode()!=200)return null;
            ByteArrayOutputStream bytes=new ByteArrayOutputStream();
            try(InputStream stream=connection.getInputStream()){
                byte[] buffer=new byte[8192];int count;
                while((count=stream.read(buffer))!=-1){
                    if(bytes.size()+count>1024*1024)return null;
                    bytes.write(buffer,0,count);
                }
            }
            String html=bytes.toString("UTF-8");
            html=html.replace("startLogin(\"baidu\",\"百度网盘扫码\")","MusicBoxBaidu.open()");
            html=html.replace("startLogin(\\'baidu\\',\\'百度网盘扫码\\')","MusicBoxBaidu.open()");
            html=html.replace("'百度注册':10,'百度网盘':20","'百度注册':20,'百度网盘':10");
            html=html.replace("setTimeout(()=>reject(new Error('请求超时')),25000)",
                "setTimeout(()=>reject(new Error('请求超时')),45000)");
            String baiduPoll="let baiduPollBusy=false;async function apiBaidu(q){"
                +"if(baiduPollBusy)return {status:'retry',terminal:false,msg:'正在读取百度扫码状态…'};"
                +"baiduPollBusy=true;let c=new AbortController(),t=setTimeout(()=>c.abort(),60000);"
                +"try{let r=await fetch(base+'&'+q,{cache:'no-store',signal:c.signal});"
                +"if(!r.ok)throw new Error('HTTP '+r.status);return await r.json();}"
                +"catch(e){return {status:'retry',terminal:false,msg:'百度状态读取未成功，稍后自动重试；也可手动保存 Cookie'};}"
                +"finally{clearTimeout(t);baiduPollBusy=false;}}";
            html=html.replace("async function startLogin(provider,name)",
                baiduPoll+"async function startLogin(provider,name)");
            html=html.replace("const r=await api('action=loginPoll",
                "const r=await (activeProvider==='baidu'?apiBaidu:api)('action=loginPoll");
            html=html.replace("timer=setInterval(poll,1500)",
                "timer=setInterval(poll,activeProvider==='baidu'?5000:1500)");
            html=html.replace("state=await api('action=status');secrets=await api('action=credentials');render();",
                "state=await apiLong('action=status');secrets=await apiLong('action=credentials');render();");
            html=html.replace("msg('读取状态失败：'+(e&&e.message?e.message:'未知错误')+'，请点“刷新状态”重试',true);",
                "msg('状态刷新较慢，请稍后点“刷新状态”重试；扫码成功提示不代表登录失败',true);");
            html=html.replace("async function poll(){if(!task)return;",
                "async function poll(){if(!task)return;if(activeProvider==='baidu')msg('正在读取百度扫码状态，网络较慢…');");
            return new WebResourceResponse("text/html","UTF-8",
                new ByteArrayInputStream(html.getBytes("UTF-8")));
        }catch(Exception ignored){return null;}
        finally{if(connection!=null)connection.disconnect();}
    }
    private void openConfig(){
        if(loading||destroyed)return;
        loading=true;status.setText("正在连接原订阅的配置中心…");
        worker.execute(()->{
            try{
                SourceBean source=null;
                for(int i=0;i<90&&source==null;i++){
                    source=ApiConfig.get().getSource("Wexconfig");
                    if(source==null)Thread.sleep(500);
                }
                if(source==null)throw new IllegalStateException("配置中心插件还未加载");
                Spider spider=ApiConfig.get().getCSP(source);
                String reported="";
                for(int attempt=0;attempt<12&&reported.isEmpty();attempt++){
                    try{
                        String raw=spider.categoryContent("settings","1",false,new HashMap<>());
                        JSONObject page=new JSONObject(raw);
                        JSONArray entries=page.optJSONArray("list");
                        if(entries!=null)for(int i=0;i<entries.length();i++){
                            JSONObject entry=entries.optJSONObject(i);
                            if(entry!=null&&entry.optString("vod_name").contains("网页版配置中心")){
                                reported=entry.optString("vod_remarks");break;
                            }
                        }
                    }catch(Exception ignored){ }
                    if(reported.isEmpty())Thread.sleep(2000);
                }
                if(reported.isEmpty())throw new IllegalStateException("配置中心仍在初始化，请稍后点此重试");
                URL base=new URL(reported);
                if(!base.getProtocol().equals("http")&&!base.getProtocol().equals("https"))
                    throw new IllegalStateException("插件提供的网页地址无效");
                List<String> candidates=addresses(base);
                String reachable=null;
                long deadline=System.currentTimeMillis()+16000;
                for(int round=0;round<3&&reachable==null&&System.currentTimeMillis()<deadline;round++){
                    for(String candidate:candidates){
                        if(System.currentTimeMillis()>=deadline)break;
                        if(probe(candidate)){reachable=candidate;break;}
                    }
                    if(reachable==null)Thread.sleep(1200);
                }
                if(reachable==null)throw new IllegalStateException("本地配置服务没有响应");
                String url=reachable;
                runOnUiThread(()->{
                    loading=false;
                    if(!destroyed){status.setText("已连接，正在加载登录页面…");web.loadUrl(url);}
                });
            }catch(Throwable error){
                runOnUiThread(()->{
                    loading=false;
                    if(!destroyed)status.setText("配置中心无法打开："+error.getMessage()+"。点击这里重试。");
                });
            }
        });
    }
    private static List<String> addresses(URL original){
        LinkedHashSet<String> all=new LinkedHashSet<>();
        all.add(original.toString());
        if(original.getPort()>0){
            all.add(withHost(original,"127.0.0.1"));
            all.add(withHost(original,"localhost"));
            try{
                Enumeration<NetworkInterface> interfaces=NetworkInterface.getNetworkInterfaces();
                while(interfaces!=null&&interfaces.hasMoreElements()&&all.size()<8){
                    NetworkInterface network=interfaces.nextElement();
                    Enumeration<java.net.InetAddress> ips=network.getInetAddresses();
                    while(ips.hasMoreElements()&&all.size()<8){
                        java.net.InetAddress ip=ips.nextElement();
                        if(ip instanceof Inet4Address&&!ip.isLoopbackAddress()&&!ip.isLinkLocalAddress())
                            all.add(withHost(original,ip.getHostAddress()));
                    }
                }
            }catch(Exception ignored){}
        }
        return new ArrayList<>(all);
    }
    private static String withHost(URL url,String host){
        try{return new URL(url.getProtocol(),host,url.getPort(),url.getFile()).toString();}
        catch(Exception ignored){return url.toString();}
    }
    private static boolean probe(String address){
        HttpURLConnection connection=null;
        try{
            connection=(HttpURLConnection)new URL(address).openConnection();
            connection.setConnectTimeout(1200);
            connection.setReadTimeout(1200);
            connection.setInstanceFollowRedirects(false);
            int code=connection.getResponseCode();
            return code>=200&&code<400;
        }catch(Exception ignored){return false;}
        finally{if(connection!=null)connection.disconnect();}
    }
    @Override protected void onResume(){
        super.onResume();
        if(web!=null)web.postDelayed(()->{
            if(!destroyed)web.evaluateJavascript(
                "if(typeof poll==='function'&&typeof task!=='undefined'&&task){poll()}else if(typeof refreshStatus==='function'){refreshStatus()}",null);
        },700);
    }
    @Override public void onBackPressed(){
        if(web!=null&&web.canGoBack())web.goBack();else super.onBackPressed();
    }
    @Override public void onDestroy(){
        destroyed=true;worker.shutdownNow();
        if(web!=null){web.stopLoading();web.destroy();}
        super.onDestroy();
    }
}
