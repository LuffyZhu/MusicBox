package com.github.tvbox.osc.musicbox;

import android.content.Context;
import android.content.SharedPreferences;
import androidx.webkit.ProxyConfig;
import androidx.webkit.ProxyController;
import androidx.webkit.WebViewFeature;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.URL;
import java.net.URLConnection;

/** Optional user supplied proxy for YouTube search and embedded playback. */
final class MusicProxy {
    private static final String PREF="musicbox_proxy";
    private MusicProxy(){}
    static SharedPreferences prefs(Context context){return context.getSharedPreferences(PREF,Context.MODE_PRIVATE);}
    static boolean enabled(Context context){return prefs(context).getBoolean("enabled",false);}
    static String scheme(Context context){return prefs(context).getString("scheme","http");}
    static String host(Context context){return prefs(context).getString("host","").trim();}
    static int port(Context context){return prefs(context).getInt("port",0);}
    static boolean valid(Context context){return !host(context).isEmpty()&&port(context)>0&&port(context)<=65535;}
    static URLConnection openYouTube(Context context,URL url)throws Exception{
        if(!enabled(context))return url.openConnection();
        if(!valid(context))throw new IllegalStateException("请先设置代理地址和端口");
        Proxy.Type type="socks".equals(scheme(context))?Proxy.Type.SOCKS:Proxy.Type.HTTP;
        return url.openConnection(new Proxy(type,new InetSocketAddress(host(context),port(context))));
    }
    static void applyWebView(Context context,Runnable ready,java.util.function.Consumer<String> failed){
        if(!WebViewFeature.isFeatureSupported(WebViewFeature.PROXY_OVERRIDE)){
            if(enabled(context))failed.accept("当前系统 WebView 不支持应用内代理，请使用手机 VPN 模式");
            else ready.run();
            return;
        }
        try{
            ProxyController controller=ProxyController.getInstance();
            if(!enabled(context)){
                controller.clearProxyOverride(Runnable::run,ready);
                return;
            }
            if(!valid(context)){failed.accept("请先设置代理地址和端口");return;}
            String rule=scheme(context)+"://"+host(context)+":"+port(context);
            ProxyConfig config=new ProxyConfig.Builder().addProxyRule(rule).addBypassRule("127.0.0.1").addBypassRule("localhost").build();
            controller.setProxyOverride(config,Runnable::run,ready);
        }catch(Exception error){failed.accept("无法启用 WebView 代理："+error.getMessage());}
    }
    static void clearWebView(){
        if(WebViewFeature.isFeatureSupported(WebViewFeature.PROXY_OVERRIDE))
            ProxyController.getInstance().clearProxyOverride(Runnable::run,()->{});
    }
}
