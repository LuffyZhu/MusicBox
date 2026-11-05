package com.github.tvbox.osc.musicbox;

import android.app.Activity;
import com.google.android.exoplayer2.DefaultLoadControl;
import com.google.android.exoplayer2.MediaItem;
import com.google.android.exoplayer2.PlaybackException;
import com.google.android.exoplayer2.Player;
import com.google.android.exoplayer2.SimpleExoPlayer;
import com.google.android.exoplayer2.source.ProgressiveMediaSource;
import com.google.android.exoplayer2.upstream.DataSource;
import org.schabi.newpipe.extractor.stream.AudioStream;
import org.schabi.newpipe.extractor.stream.DeliveryMethod;
import org.schabi.newpipe.extractor.stream.StreamInfo;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Audio-only YouTube playback using the same proxy as MV playback. */
final class YouTubeAudioPlayer {
    interface Listener {
        void onReady(long durationMs);
        void onEnded();
        void onError(String message);
    }
    private final SimpleExoPlayer player;
    private final List<AudioStream> streams=new ArrayList<>();
    private final DataSource.Factory network;
    private int candidate;
    private boolean notified;

    YouTubeAudioPlayer(Activity activity,StreamInfo info,boolean repeat,Listener listener){
        for(AudioStream stream:info.getAudioStreams())
            if(stream!=null&&stream.isUrl()&&stream.getDeliveryMethod()==DeliveryMethod.PROGRESSIVE_HTTP)
                streams.add(stream);
        streams.sort(Comparator.comparingInt(AudioStream::getAverageBitrate).reversed());
        if(streams.isEmpty())throw new IllegalStateException("没有可用的 YouTube 音频流");
        network=YouTubeMvPlayer.dataSource(activity);
        DefaultLoadControl buffer=new DefaultLoadControl.Builder()
            .setBufferDurationsMs(15000,60000,1000,3000).build();
        player=new SimpleExoPlayer.Builder(activity).setLoadControl(buffer).build();
        player.setRepeatMode(repeat?Player.REPEAT_MODE_ONE:Player.REPEAT_MODE_OFF);
        player.addListener(new Player.Listener(){
            @Override public void onPlaybackStateChanged(int state){
                if(state==Player.STATE_READY&&!notified){notified=true;listener.onReady(player.getDuration());}
                else if(state==Player.STATE_ENDED)listener.onEnded();
            }
            @Override public void onPlayerError(PlaybackException error){
                if(candidate+1<streams.size()){
                    long position=player.getCurrentPosition();
                    load(++candidate,position);
                }else listener.onError("YouTube 音频播放失败，请换一个来源");
            }
        });
        load(0,0);
    }
    private void load(int index,long position){
        player.setMediaSource(new ProgressiveMediaSource.Factory(network)
            .createMediaSource(MediaItem.fromUri(streams.get(index).getContent())),position);
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
