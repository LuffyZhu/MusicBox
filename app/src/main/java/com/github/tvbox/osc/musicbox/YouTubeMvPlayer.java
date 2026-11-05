package com.github.tvbox.osc.musicbox;

import android.app.Activity;
import android.app.Dialog;
import android.graphics.Color;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.TextView;
import com.google.android.exoplayer2.MediaItem;
import com.google.android.exoplayer2.DefaultLoadControl;
import com.google.android.exoplayer2.PlaybackException;
import com.google.android.exoplayer2.Player;
import com.google.android.exoplayer2.SimpleExoPlayer;
import com.google.android.exoplayer2.source.MediaSource;
import com.google.android.exoplayer2.source.MergingMediaSource;
import com.google.android.exoplayer2.source.ProgressiveMediaSource;
import com.google.android.exoplayer2.source.dash.DashMediaSource;
import com.google.android.exoplayer2.source.hls.HlsMediaSource;
import com.google.android.exoplayer2.ui.AspectRatioFrameLayout;
import com.google.android.exoplayer2.ui.StyledPlayerView;
import com.google.android.exoplayer2.upstream.HttpDataSource;
import com.google.android.exoplayer2.upstream.DataSource;
import com.google.android.exoplayer2.ext.okhttp.OkHttpDataSource;
import okhttp3.OkHttpClient;
import org.schabi.newpipe.extractor.stream.AudioStream;
import org.schabi.newpipe.extractor.stream.DeliveryMethod;
import org.schabi.newpipe.extractor.stream.StreamInfo;
import org.schabi.newpipe.extractor.stream.VideoStream;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/** Full-screen native YouTube playback with seek controls and the original picture ratio. */
final class YouTubeMvPlayer {
    private static final ExecutorService RESOLVER=Executors.newCachedThreadPool();
    private YouTubeMvPlayer(){}
    private static int dp(Activity activity,int value){return (int)(value*activity.getResources().getDisplayMetrics().density);}

    static Dialog show(Activity activity,MusicSourceBridge.Song song){
        Dialog dialog=new Dialog(activity,android.R.style.Theme_Material_NoActionBar_Fullscreen);
        FrameLayout root=new FrameLayout(activity);
        root.setBackgroundColor(Color.BLACK);
        StyledPlayerView view=new StyledPlayerView(activity);
        view.setUseController(true);
        view.setControllerAutoShow(true);
        view.setControllerShowTimeoutMs(10000);
        view.setResizeMode(AspectRatioFrameLayout.RESIZE_MODE_FIT);
        view.setBackgroundColor(Color.BLACK);
        root.addView(view,new FrameLayout.LayoutParams(-1,-1));
        TextView state=new TextView(activity);
        state.setText("正在获取视频…");
        state.setTextColor(Color.WHITE);
        state.setTextSize(15);
        state.setGravity(Gravity.CENTER);
        state.setPadding(dp(activity,16),dp(activity,10),dp(activity,16),dp(activity,10));
        FrameLayout.LayoutParams stateLayout=new FrameLayout.LayoutParams(-1,-2,Gravity.BOTTOM);
        stateLayout.bottomMargin=dp(activity,28);
        root.addView(state,stateLayout);
        ImageButton close=new ImageButton(activity);
        close.setImageResource(com.github.tvbox.osc.R.drawable.ic_musicbox_chevron_down);
        close.setImageTintList(android.content.res.ColorStateList.valueOf(Color.WHITE));
        close.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        close.setPadding(dp(activity,11),dp(activity,11),dp(activity,11),dp(activity,11));
        close.setBackgroundColor(0x66000000);
        close.setContentDescription("关闭 MV");
        FrameLayout.LayoutParams closeLayout=new FrameLayout.LayoutParams(dp(activity,48),dp(activity,48),Gravity.TOP|Gravity.LEFT);
        closeLayout.leftMargin=dp(activity,12);
        closeLayout.topMargin=dp(activity,12);
        root.addView(close,closeLayout);
        close.setOnClickListener(v->dialog.dismiss());
        dialog.setContentView(root);
        final Future<?>[] job={null};
        final SimpleExoPlayer[] player={null};
        dialog.setOnDismissListener(ignored->{
            if(job[0]!=null)job[0].cancel(true);
            view.setPlayer(null);
            if(player[0]!=null)player[0].release();
        });
        dialog.show();
        if(dialog.getWindow()!=null){
            dialog.getWindow().setLayout(-1,-1);
            dialog.getWindow().setNavigationBarColor(Color.BLACK);
            dialog.getWindow().setStatusBarColor(Color.BLACK);
            dialog.getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_FULLSCREEN|View.SYSTEM_UI_FLAG_HIDE_NAVIGATION|View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
        }
        job[0]=RESOLVER.submit(()->{
            try{
                long resolveStarted=android.os.SystemClock.elapsedRealtime();
                StreamInfo info=YouTubeMv.resolve(activity,song.id);
                if(com.github.tvbox.osc.BuildConfig.DEBUG)
                    android.util.Log.d("MusicBoxMV","resolveMs="+(android.os.SystemClock.elapsedRealtime()-resolveStarted));
                DataSource.Factory network=dataSource(activity);
                List<MediaSource> media=mediaSources(info,network);
                activity.runOnUiThread(()->{
                    if(!dialog.isShowing())return;
                    DefaultLoadControl loadControl=new DefaultLoadControl.Builder()
                        .setBufferDurationsMs(15000,60000,3000,5000)
                        .setPrioritizeTimeOverSizeThresholds(true)
                        .build();
                    SimpleExoPlayer exo=new SimpleExoPlayer.Builder(activity)
                        .setLoadControl(loadControl).build();
                    player[0]=exo;
                    view.setPlayer(exo);
                    final int[] candidate={0};
                    final boolean[] started={false};
                    final android.os.Handler main=new android.os.Handler(android.os.Looper.getMainLooper());
                    final Runnable[] downgrade={null};
                    exo.addListener(new Player.Listener(){
                        private void nextSource(String message){
                            if(candidate[0]+1>=media.size()){state.setText("视频缓冲较慢，请检查代理连接");state.setVisibility(View.VISIBLE);return;}
                            long position=exo.getCurrentPosition();
                            started[0]=false;
                            state.setText(message);
                            state.setVisibility(View.VISIBLE);
                            exo.setMediaSource(media.get(++candidate[0]),position);
                            exo.prepare();
                            exo.play();
                        }
                        @Override public void onPlaybackStateChanged(int status){
                            if(com.github.tvbox.osc.BuildConfig.DEBUG)
                                android.util.Log.d("MusicBoxMV","state="+status+" positionMs="+exo.getCurrentPosition()+" bufferedMs="+exo.getBufferedPosition());
                            if(status==Player.STATE_READY){
                                started[0]=true;
                                if(downgrade[0]!=null)main.removeCallbacks(downgrade[0]);
                                state.setVisibility(View.GONE);
                            }
                            else if(status==Player.STATE_BUFFERING){
                                state.setText(exo.getCurrentPosition()>0?"正在缓冲视频…":"正在预缓冲视频…");
                                state.setVisibility(View.VISIBLE);
                                if(started[0]&&media.get(candidate[0]) instanceof MergingMediaSource
                                    &&candidate[0]+1<media.size()){
                                    if(downgrade[0]!=null)main.removeCallbacks(downgrade[0]);
                                    downgrade[0]=()->{if(dialog.isShowing()&&exo.getPlaybackState()==Player.STATE_BUFFERING)
                                        nextSource("网络较慢，正在切换流畅画质…");};
                                    main.postDelayed(downgrade[0],2500);
                                }
                            }
                        }
                        @Override public void onPlayerError(PlaybackException error){
                            if(candidate[0]+1<media.size()){
                                nextSource("正在切换备用画质…");
                            }else{
                                state.setText(failureMessage(error));
                                state.setVisibility(View.VISIBLE);
                            }
                        }
                    });
                    exo.setMediaSource(media.get(0));
                    exo.prepare();
                    exo.play();
                });
            }catch(Exception error){
                activity.runOnUiThread(()->{
                    if(dialog.isShowing()){
                        state.setText("无法获取视频：" + brief(error));
                        state.setVisibility(View.VISIBLE);
                    }
                });
            }
        });
        return dialog;
    }

    static DataSource.Factory dataSource(Activity activity){
        OkHttpClient.Builder client=new OkHttpClient.Builder();
        if(MusicProxy.enabled(activity)){
            if(!MusicProxy.valid(activity))throw new IllegalStateException("请先设置代理地址和端口");
            Proxy.Type type="socks".equals(MusicProxy.scheme(activity))?Proxy.Type.SOCKS:Proxy.Type.HTTP;
            client.proxy(new Proxy(type,new InetSocketAddress(MusicProxy.host(activity),MusicProxy.port(activity))));
        }
        return new OkHttpDataSource.Factory(client.build())
            .setUserAgent("Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 Chrome/120.0.0.0 Safari/537.36");
    }

    private static List<MediaSource> mediaSources(StreamInfo info,DataSource.Factory network){
        List<MediaSource> choices=new ArrayList<>();
        VideoStream combined=bestVideo(info.getVideoStreams(),false);
        VideoStream videoOnly=bestVideo(info.getVideoOnlyStreams(),true);
        AudioStream audio=bestAudio(info.getAudioStreams());
        if(com.github.tvbox.osc.BuildConfig.DEBUG)
            android.util.Log.d("MusicBoxMV","streams combined="+resolution(combined)
                +" videoOnly="+resolution(videoOnly)+" audioKbps="+(audio==null?0:audio.getAverageBitrate()));
        if(combined!=null&&resolution(combined)>=360)
            choices.add(progressive(network,combined.getContent()));
        if(videoOnly!=null&&audio!=null){
            choices.add(new MergingMediaSource(progressive(network,videoOnly.getContent()),
                progressive(network,audio.getContent())));
        }
        if(combined!=null&&resolution(combined)<360)
            choices.add(progressive(network,combined.getContent()));
        if(info.getHlsUrl()!=null&&!info.getHlsUrl().isEmpty())
            choices.add(new HlsMediaSource.Factory(network).createMediaSource(MediaItem.fromUri(info.getHlsUrl())));
        if(info.getDashMpdUrl()!=null&&!info.getDashMpdUrl().isEmpty())
            choices.add(new DashMediaSource.Factory(network).createMediaSource(MediaItem.fromUri(info.getDashMpdUrl())));
        if(choices.isEmpty())throw new IllegalStateException("这个视频没有可用的音画流");
        return choices;
    }

    private static MediaSource progressive(DataSource.Factory network,String url){
        return new ProgressiveMediaSource.Factory(network).createMediaSource(MediaItem.fromUri(url));
    }
    private static VideoStream bestVideo(List<VideoStream> streams,boolean videoOnly){
        VideoStream best=null;
        int target=videoOnly?720:1080;
        if(streams==null)return null;
        for(VideoStream stream:streams){
            if(stream==null||!stream.isUrl()||stream.getDeliveryMethod()!=DeliveryMethod.PROGRESSIVE_HTTP
                ||stream.isVideoOnly()!=videoOnly)continue;
            int pixels=resolution(stream);
            int current=resolution(best);
            if(best==null||(pixels<=target&&current>target)
                ||(pixels<=target&&pixels>current)
                ||(pixels>target&&current>target&&pixels<current))best=stream;
        }
        return best;
    }
    private static AudioStream bestAudio(List<AudioStream> streams){
        AudioStream best=null;
        if(streams==null)return null;
        for(AudioStream stream:streams){
            if(stream==null||!stream.isUrl()||stream.getDeliveryMethod()!=DeliveryMethod.PROGRESSIVE_HTTP)continue;
            if(best==null||stream.getAverageBitrate()>best.getAverageBitrate())best=stream;
        }
        return best;
    }
    private static int resolution(VideoStream stream){
        if(stream==null)return 0;
        try{
            java.util.regex.Matcher match=java.util.regex.Pattern.compile("(\\d+)").matcher(stream.getResolution());
            return match.find()?Integer.parseInt(match.group(1)):0;
        }catch(Exception ignored){return 0;}
    }
    private static String brief(Exception error){
        String value=error.getMessage();
        if(value==null||value.isEmpty())return error.getClass().getSimpleName();
        return value.length()>90?value.substring(0,90):value;
    }
    private static String failureMessage(PlaybackException error){
        for(Throwable cause=error;cause!=null;cause=cause.getCause()){
            if(cause instanceof HttpDataSource.InvalidResponseCodeException){
                int code=((HttpDataSource.InvalidResponseCodeException)cause).responseCode;
                if(code==403)return "YouTube 拒绝了此视频流（HTTP 403），请换一个 MV 来源";
                return "YouTube 视频流返回 HTTP "+code+"，请换一个来源";
            }
        }
        return "视频播放失败，请更换来源或检查代理";
    }
}
