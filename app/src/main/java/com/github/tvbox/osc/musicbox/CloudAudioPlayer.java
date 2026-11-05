package com.github.tvbox.osc.musicbox;

import android.app.Activity;
import com.google.android.exoplayer2.DefaultLoadControl;
import com.google.android.exoplayer2.MediaItem;
import com.google.android.exoplayer2.PlaybackException;
import com.google.android.exoplayer2.Player;
import com.google.android.exoplayer2.SimpleExoPlayer;
import com.google.android.exoplayer2.ext.okhttp.OkHttpDataSource;
import com.google.android.exoplayer2.source.ProgressiveMediaSource;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import okhttp3.OkHttpClient;

/** Streams personal cloud audio with the headers returned by its link resolver. */
final class CloudAudioPlayer {
    interface Listener {
        void onReady(long durationMs);
        void onEnded();
        void onError(String message);
    }

    private final SimpleExoPlayer player;

    CloudAudioPlayer(Activity activity, String provider, MusicSourceBridge.PlaybackInfo info,
                     boolean repeat, Listener listener) {
        AtomicReference<String> lastHost=new AtomicReference<>("");
        OkHttpClient client=new OkHttpClient.Builder()
            .addNetworkInterceptor(chain->{
                lastHost.set(chain.request().url().host());
                return chain.proceed(chain.request());
            })
            .followRedirects(true).followSslRedirects(true).build();
        OkHttpDataSource.Factory network=new OkHttpDataSource.Factory(client)
            .setDefaultRequestProperties(info.headers);
        DefaultLoadControl buffer=new DefaultLoadControl.Builder()
            .setBufferDurationsMs(10000,50000,1000,2500).build();
        player=new SimpleExoPlayer.Builder(activity).setLoadControl(buffer).build();
        player.setRepeatMode(repeat?Player.REPEAT_MODE_ONE:Player.REPEAT_MODE_OFF);
        player.addListener(new Player.Listener() {
            private boolean notified;
            private boolean triedBaiduPlayerHeader;
            @Override public void onPlaybackStateChanged(int state) {
                if(state==Player.STATE_READY&&!notified) {
                    notified=true;
                    listener.onReady(player.getDuration());
                } else if(state==Player.STATE_ENDED) listener.onEnded();
            }
            @Override public void onPlayerError(PlaybackException error) {
                Throwable cause=error;
                while(cause.getCause()!=null)cause=cause.getCause();
                String message=provider+"音频加载失败，请重试";
                if(cause instanceof com.google.android.exoplayer2.upstream.HttpDataSource.InvalidResponseCodeException) {
                    int code=((com.google.android.exoplayer2.upstream.HttpDataSource.InvalidResponseCodeException)cause).responseCode;
                    if(code==403&&"百度网页版".equals(provider)&&!triedBaiduPlayerHeader){
                        triedBaiduPlayerHeader=true;
                        Map<String,String> fallback=new HashMap<>();
                        fallback.put("User-Agent","netdisk");
                        OkHttpDataSource.Factory alternate=new OkHttpDataSource.Factory(client)
                            .setDefaultRequestProperties(fallback);
                        player.setMediaSource(new ProgressiveMediaSource.Factory(alternate)
                            .createMediaSource(MediaItem.fromUri(info.url)));
                        player.prepare();
                        player.play();
                        return;
                    }
                    String host=lastHost.get();
                    message=provider+"音频 HTTP "+code+(host.isEmpty()?"":" · "+host);
                    if(code==401) message+=" · 请重新登录";
                    else if(code==403) message+="百度网页版".equals(provider)
                        ?" · 已尝试兼容请求，网页版下载仍被拒绝"
                        :" · 下载请求被拒绝，请检查网盘登录和下载权限";
                } else if(cause instanceof IOException) message=provider+"连接失败，请检查网络后重试";
                listener.onError(message);
            }
        });
        player.setMediaSource(new ProgressiveMediaSource.Factory(network)
            .createMediaSource(MediaItem.fromUri(info.url)));
        player.prepare();
        player.play();
    }

    void release(){player.release();}
    boolean isPlaying(){return player.isPlaying();}
    void pause(){player.pause();}
    void play(){player.play();}
    void seekTo(long position){player.seekTo(position);}
    long position(){return player.getCurrentPosition();}
    long duration(){return player.getDuration();}
    void setRepeat(boolean repeat){player.setRepeatMode(repeat?Player.REPEAT_MODE_ONE:Player.REPEAT_MODE_OFF);}
}
