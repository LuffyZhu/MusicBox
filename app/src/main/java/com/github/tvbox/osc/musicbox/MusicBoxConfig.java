package com.github.tvbox.osc.musicbox;

import android.app.Activity;
import android.app.AlertDialog;
import android.widget.EditText;
import com.github.tvbox.osc.api.ApiConfig;
import com.github.tvbox.osc.bean.Subscription;
import com.github.tvbox.osc.util.HawkConfig;
import com.orhanobut.hawk.Hawk;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/** TVBox subscription URL chosen by the user; the APK does not bundle any site list. */
final class MusicBoxConfig {
    private MusicBoxConfig(){}

    static void show(Activity activity,Consumer<String> finished){
        EditText input=new EditText(activity);
        input.setSingleLine(true);
        input.setHint("https://example.com/music.json");
        input.setText(Hawk.get(HawkConfig.API_URL,""));
        input.setPadding(28,18,28,18);
        AlertDialog dialog=new AlertDialog.Builder(activity).setTitle("配置源")
            .setMessage("填入你自己的 TVBox 订阅地址。应用不预置站点；更换后会重新加载搜索源。")
            .setView(input).setPositiveButton("保存并加载",null)
            .setNeutralButton("清除当前配置",null).setNegativeButton("取消",null).create();
        dialog.setOnShowListener(ignored->{
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(view->{
                String url=input.getText().toString().trim();
                if(!valid(url)){input.setError("请输入完整的 HTTP 或 HTTPS 地址");return;}
                Hawk.put(HawkConfig.API_URL,url);
                List<Subscription> subscriptions=Hawk.get(HawkConfig.SUBSCRIPTIONS,new ArrayList<>());
                if(subscriptions==null)subscriptions=new ArrayList<>();
                boolean found=false;
                for(Subscription item:subscriptions){boolean selected=url.equals(item.getUrl());item.setChecked(selected);found|=selected;}
                if(!found)subscriptions.add(new Subscription("我的配置源",url).setChecked(true));
                Hawk.put(HawkConfig.SUBSCRIPTIONS,subscriptions);
                dialog.dismiss();load(activity,finished);
            });
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(view->{
                Hawk.put(HawkConfig.API_URL,"");
                List<Subscription> subscriptions=Hawk.get(HawkConfig.SUBSCRIPTIONS,new ArrayList<>());
                if(subscriptions!=null){for(Subscription item:subscriptions)item.setChecked(false);Hawk.put(HawkConfig.SUBSCRIPTIONS,subscriptions);}
                ApiConfig.get().clearMusicBoxSources();
                dialog.dismiss();finished.accept("未配置订阅源，可在设置中添加");
            });
        });
        dialog.show();
    }

    static void load(Activity activity,Consumer<String> finished){
        String url=Hawk.get(HawkConfig.API_URL,"");
        if(url==null||url.trim().isEmpty()){
            ApiConfig.get().clearMusicBoxSources();
            finished.accept("未配置订阅源，可在设置中添加");return;
        }
        finished.accept("正在加载用户配置源…");
        ApiConfig.get().loadConfig(false,new ApiConfig.LoadConfigCallback(){
            public void success(){
                String spider=ApiConfig.get().getSpider();
                if(spider==null||spider.isEmpty()){done("已加载用户配置源");return;}
                ApiConfig.get().loadJar(false,spider,new ApiConfig.LoadConfigCallback(){
                    public void success(){done("已加载 "+MusicSourceBridge.musicSites().size()+" 个可搜索源");}
                    public void retry(){done("配置源插件请求重试，请重新加载");}
                    public void error(String error){done("配置源插件加载失败，请检查地址或网络");}
                });
            }
            public void retry(){done("配置源请求重试，请重新加载");}
            public void error(String error){done("配置源加载失败，请检查地址或网络");}
            private void done(String message){activity.runOnUiThread(()->{
                if(!activity.isFinishing()&&!activity.isDestroyed()){
                    if(!MusicSourceBridge.musicSites().isEmpty())com.github.tvbox.osc.server.ControlManager.get().startServer();
                    finished.accept(message);
                }
            });}
        },activity);
    }
    private static boolean valid(String value){
        try{URL url=new URL(value);return ("https".equalsIgnoreCase(url.getProtocol())||"http".equalsIgnoreCase(url.getProtocol()))&&!url.getHost().isEmpty();}
        catch(Exception error){return false;}
    }
}
