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
import java.util.Map;
import okhttp3.OkHttpClient;

/** Streams personal cloud audio with the headers returned by its link resolver. */
final class CloudAudioPlayer {
    interface Listener {
        void onReady(long durationMs);
        void onEnded();
        void onError(String message);
    }

    private final SimpleExoPlayer player;

    CloudAudioPlayer(Activity activity, MusicSourceBridge.PlaybackInfo info,
                     boolean repeat, Listener listener) {
        OkHttpClient client=new OkHttpClient.Builder()
            .followRedirects(true).followSslRedirects(true).build();
        OkHttpDataSource.Factory network=new OkHttpDataSource.Factory(client)
            .setDefaultRequestProperties(info.headers);
        DefaultLoadControl buffer=new DefaultLoadControl.Builder()
            .setBufferDurationsMs(10000,50000,1000,2500).build();
        player=new SimpleExoPlayer.Builder(activity).setLoadControl(buffer).build();
        player.setRepeatMode(repeat?Player.REPEAT_MODE_ONE:Player.REPEAT_MODE_OFF);
        player.addListener(new Player.Listener() {
            private boolean notified;
            @Override public void onPlaybackStateChanged(int state) {
                if(state==Player.STATE_READY&&!notified) {
                    notified=true;
                    listener.onReady(player.getDuration());
                } else if(state==Player.STATE_ENDED) listener.onEnded();
            }
            @Override public void onPlayerError(PlaybackException error) {
                Throwable cause=error;
                while(cause.getCause()!=null)cause=cause.getCause();
                String message="网盘音频加载失败，请重试";
                if(cause instanceof com.google.android.exoplayer2.upstream.HttpDataSource.InvalidResponseCodeException) {
                    int code=((com.google.android.exoplayer2.upstream.HttpDataSource.InvalidResponseCodeException)cause).responseCode;
                    message=(code==401||code==403)?"网盘链接已失效或登录过期，请刷新登录状态后重试":"网盘音频请求失败 (HTTP "+code+")";
                } else if(cause instanceof IOException) message="网盘连接失败，请检查网络后重试";
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
