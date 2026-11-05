package com.github.tvbox.osc.musicbox;

import android.content.Context;
import com.github.tvbox.osc.bean.SourceBean;
import org.schabi.newpipe.extractor.Image;
import org.schabi.newpipe.extractor.InfoItem;
import org.schabi.newpipe.extractor.NewPipe;
import org.schabi.newpipe.extractor.ServiceList;
import org.schabi.newpipe.extractor.downloader.Downloader;
import org.schabi.newpipe.extractor.downloader.Request;
import org.schabi.newpipe.extractor.downloader.Response;
import org.schabi.newpipe.extractor.search.SearchInfo;
import org.schabi.newpipe.extractor.services.youtube.linkHandler.YoutubeSearchQueryHandlerFactory;
import org.schabi.newpipe.extractor.stream.StreamInfo;
import org.schabi.newpipe.extractor.stream.StreamInfoItem;
import org.schabi.newpipe.extractor.stream.VideoStream;
import org.schabi.newpipe.extractor.stream.AudioStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Iterator;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.FutureTask;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.CancellationException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.GZIPInputStream;

/** Key-free YouTube search and stream extraction, backed by NewPipe Extractor. */
final class YouTubeMv {
    static final String SOURCE_KEY="musicbox_youtube_mv";
    static final String AUDIO_SOURCE_KEY="musicbox_youtube_audio";
    private static final Pattern ID=Pattern.compile("(?:[?&]v=|youtu\\.be/|/shorts/|/embed/)([A-Za-z0-9_-]{11})");
    private static final Pattern STREAM_EXPIRY=Pattern.compile("[?&]expire=(\\d+)");
    private static final Object CACHE_LOCK=new Object();
    private static final ExecutorService PREFETCH=Executors.newFixedThreadPool(2);
    private static final LinkedHashMap<String,CachedStream> STREAM_CACHE=new LinkedHashMap<>(4,0.75f,true);
    private static final Map<String,FutureTask<StreamInfo>> PENDING=new LinkedHashMap<>();
    private static int searchGeneration;
    private static volatile boolean initialized;
    private static final class CachedStream{
        final StreamInfo info;final long expiresAt;
        CachedStream(StreamInfo info,long expiresAt){this.info=info;this.expiresAt=expiresAt;}
    }
    private YouTubeMv(){}

    static boolean configured(Context context){return true;}
    static boolean owns(MusicSourceBridge.Song song){return song!=null&&SOURCE_KEY.equals(song.source.getKey());}
    static boolean ownsAudio(MusicSourceBridge.Song song){return song!=null&&AUDIO_SOURCE_KEY.equals(song.source.getKey());}
    static SourceBean source(){SourceBean result=new SourceBean();result.setKey(SOURCE_KEY);result.setName("YouTube MV");return result;}
    static SourceBean audioSource(){SourceBean result=new SourceBean();result.setKey(AUDIO_SOURCE_KEY);result.setName("YouTube 音频");return result;}
    static MusicSourceBridge.Song audioResult(MusicSourceBridge.Song video){return new MusicSourceBridge.Song(audioSource(),video.id,video.title,video.artist,"","",false,video.cover);}

    private static void initialize(Context context){
        if(initialized)return;
        synchronized(YouTubeMv.class){
            if(!initialized){NewPipe.init(new Network(context.getApplicationContext()));initialized=true;}
        }
    }

    static List<MusicSourceBridge.Song> search(Context context,String keyword)throws Exception{
        if(Thread.currentThread().isInterrupted())throw new InterruptedException();
        cancelPrefetch();
        initialize(context);
        SearchInfo found=SearchInfo.getInfo(ServiceList.YouTube,
            ServiceList.YouTube.getSearchQHFactory().fromQuery(keyword,
                Collections.singletonList(YoutubeSearchQueryHandlerFactory.VIDEOS),""));
        List<MusicSourceBridge.Song> songs=new ArrayList<>();
        SourceBean source=source();
        for(InfoItem item:found.getRelatedItems()){
            if(Thread.currentThread().isInterrupted())throw new InterruptedException();
            if(!(item instanceof StreamInfoItem))continue;
            StreamInfoItem video=(StreamInfoItem)item;
            String videoId=id(video.getUrl());
            if(videoId.isEmpty())continue;
            String title=video.getName()==null?"":video.getName();
            String channel=video.getUploaderName()==null?"":video.getUploaderName();
            if(!matches(title,channel,keyword)||looksLikeFilm(title))continue;
            String cover="";
            List<Image> thumbnails=video.getThumbnails();
            if(thumbnails!=null&&!thumbnails.isEmpty())cover=thumbnails.get(0).getUrl();
            songs.add(new MusicSourceBridge.Song(source,videoId,title,channel,"","",true,cover));
            if(songs.size()>=25)break;
        }
        songs.sort(Comparator.comparingInt((MusicSourceBridge.Song song)->rank(song,keyword)).reversed());
        return songs;
    }

    static void prefetchVisible(Context context,List<MusicSourceBridge.Song> displayed){
        List<MusicSourceBridge.Song> first=new ArrayList<>(2);
        java.util.Set<String> ids=new java.util.HashSet<>();
        for(MusicSourceBridge.Song song:displayed){
            if((owns(song)||ownsAudio(song))&&ids.add(song.id))first.add(song);
            if(first.size()==2)break;
        }
        if(!first.isEmpty())prefetch(context,first);
    }

    static StreamInfo resolve(Context context,String videoId)throws Exception{
        if(!videoId.matches("[A-Za-z0-9_-]{11}"))throw new IllegalArgumentException("YouTube 视频 ID 无效");
        if(Thread.currentThread().isInterrupted())throw new InterruptedException();
        FutureTask<StreamInfo> pending;
        synchronized(CACHE_LOCK){
            CachedStream cached=STREAM_CACHE.get(videoId);
            if(cached!=null){
                if(cached.expiresAt>android.os.SystemClock.elapsedRealtime()){
                    if(com.github.tvbox.osc.BuildConfig.DEBUG)android.util.Log.d("MusicBoxMV","prefetch cache hit="+videoId);
                    return cached.info;
                }
                STREAM_CACHE.remove(videoId);
            }
            pending=PENDING.get(videoId);
        }
        if(pending!=null){
            if(com.github.tvbox.osc.BuildConfig.DEBUG)android.util.Log.d("MusicBoxMV","prefetch waiting="+videoId);
            try{
                StreamInfo info=pending.get();
                synchronized(CACHE_LOCK){
                    CachedStream cached=STREAM_CACHE.get(videoId);
                    if(cached!=null&&cached.expiresAt>android.os.SystemClock.elapsedRealtime())return info;
                }
            }catch(CancellationException|ExecutionException ignored){}
        }
        if(com.github.tvbox.osc.BuildConfig.DEBUG)android.util.Log.d("MusicBoxMV","prefetch miss="+videoId);
        return fetch(context,videoId);
    }

    private static StreamInfo fetch(Context context,String videoId)throws Exception{
        initialize(context);
        return StreamInfo.getInfo(ServiceList.YouTube,"https://www.youtube.com/watch?v="+videoId);
    }

    private static void cancelPrefetch(){
        synchronized(CACHE_LOCK){
            searchGeneration++;
            for(FutureTask<StreamInfo> task:PENDING.values())task.cancel(true);
            PENDING.clear();
        }
    }

    private static void prefetch(Context context,List<MusicSourceBridge.Song> songs){
        Context app=context.getApplicationContext();
        synchronized(CACHE_LOCK){
            int generation=searchGeneration;
            for(int i=0;i<songs.size()&&i<2;i++){
                String videoId=songs.get(i).id;
                CachedStream cached=STREAM_CACHE.get(videoId);
                if(cached!=null&&cached.expiresAt>android.os.SystemClock.elapsedRealtime())continue;
                FutureTask<StreamInfo> task=new FutureTask<>(()->{
                    if(com.github.tvbox.osc.BuildConfig.DEBUG)android.util.Log.d("MusicBoxMV","prefetch start="+videoId);
                    StreamInfo info=fetch(app,videoId);
                    synchronized(CACHE_LOCK){
                        if(generation==searchGeneration&&!Thread.currentThread().isInterrupted()){
                            long lifetime=cacheLifetime(info);
                            if(com.github.tvbox.osc.BuildConfig.DEBUG)android.util.Log.d("MusicBoxMV","prefetch ttlMs="+lifetime+" id="+videoId);
                            if(lifetime>0){
                                STREAM_CACHE.put(videoId,new CachedStream(info,android.os.SystemClock.elapsedRealtime()+lifetime));
                                while(STREAM_CACHE.size()>4){Iterator<String> oldest=STREAM_CACHE.keySet().iterator();oldest.next();oldest.remove();}
                            }
                        }
                    }
                    return info;
                });
                PENDING.put(videoId,task);
                PREFETCH.execute(task);
            }
        }
    }

    private static long cacheLifetime(StreamInfo info){
        long lifetime=90_000L;
        List<VideoStream> combined=info.getVideoStreams(),videoOnly=info.getVideoOnlyStreams();
        List<AudioStream> audio=info.getAudioStreams();
        if(combined!=null)for(VideoStream stream:combined)lifetime=Math.min(lifetime,urlLifetime(stream.getContent()));
        if(videoOnly!=null)for(VideoStream stream:videoOnly)lifetime=Math.min(lifetime,urlLifetime(stream.getContent()));
        if(audio!=null)for(AudioStream stream:audio)lifetime=Math.min(lifetime,urlLifetime(stream.getContent()));
        return Math.max(0,lifetime);
    }

    private static long urlLifetime(String url){
        Matcher expiry=STREAM_EXPIRY.matcher(url==null?"":url);
        if(!expiry.find())return 90_000L;
        try{return Math.max(0,Long.parseLong(expiry.group(1))*1000L-System.currentTimeMillis()-60_000L);}
        catch(NumberFormatException ignored){return 0;}
    }

    private static String id(String url){Matcher found=ID.matcher(url==null?"":url);return found.find()?found.group(1):"";}
    private static String normalize(String text){return text.toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N}]","");}
    private static boolean matches(String title,String channel,String query){
        String haystack=normalize(title+" "+channel);
        for(String word:query.trim().split("\\s+"))if(!word.isEmpty()&&!haystack.contains(normalize(word)))return false;
        return true;
    }
    private static boolean looksLikeFilm(String text){String value=text.toLowerCase(Locale.ROOT);return value.contains("电影")||value.contains("电视剧")||value.contains("短剧")||value.contains("预告片")||value.contains("trailer")||value.contains("reaction");}
    private static int rank(MusicSourceBridge.Song song,String keyword){
        String title=normalize(song.title),query=normalize(keyword),channel=normalize(song.artist);
        int score=title.contains(query)?20:0;
        if(title.contains("official")||title.contains("官方"))score+=5;
        if(title.contains("musicvideo")||title.contains("mv"))score+=4;
        if(channel.contains("official")||channel.contains("vevo"))score+=2;
        if(title.contains("cover")||title.contains("翻唱")||title.contains("reaction"))score-=4;
        return score;
    }

    private static final class Network extends Downloader {
        private final Context context;
        Network(Context context){this.context=context;}

        @Override public Response execute(Request request)throws IOException{
            HttpURLConnection connection=null;
            try{
                connection=(HttpURLConnection)MusicProxy.openYouTube(context,new URL(request.url()));
                connection.setConnectTimeout(6500);
                connection.setReadTimeout(10000);
                connection.setInstanceFollowRedirects(true);
                connection.setRequestMethod(request.httpMethod());
                connection.setRequestProperty("User-Agent","Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36");
                for(Map.Entry<String,List<String>> entry:request.headers().entrySet()){
                    List<String> values=entry.getValue();
                    if(values!=null&&!values.isEmpty())connection.setRequestProperty(entry.getKey(),String.join(", ",values));
                }
                byte[] body=request.dataToSend();
                if(body!=null){connection.setDoOutput(true);try(OutputStream out=connection.getOutputStream()){out.write(body);}}
                int code=connection.getResponseCode();
                String result="";
                if(!"HEAD".equals(request.httpMethod())){
                    try(InputStream raw=code>=400?connection.getErrorStream():connection.getInputStream()){
                        if(raw!=null){InputStream in="gzip".equalsIgnoreCase(connection.getContentEncoding())?new GZIPInputStream(raw):raw;
                            result=read(in,8*1024*1024);}
                    }
                }
                return new Response(code,connection.getResponseMessage(),connection.getHeaderFields(),result,connection.getURL().toString());
            }catch(InterruptedException e){Thread.currentThread().interrupt();throw new IOException("YouTube 请求已取消",e);
            }catch(IOException e){throw e;
            }catch(Exception e){throw new IOException("YouTube 网络请求失败",e);
            }finally{if(connection!=null)connection.disconnect();}
        }

        private static String read(InputStream input,int limit)throws IOException,InterruptedException{
            ByteArrayOutputStream bytes=new ByteArrayOutputStream();byte[] buffer=new byte[8192];int count;
            while((count=input.read(buffer))!=-1){
                if(Thread.currentThread().isInterrupted())throw new InterruptedException();
                if(bytes.size()+count>limit)throw new IOException("YouTube 响应过大");
                bytes.write(buffer,0,count);
            }
            return new String(bytes.toByteArray(),StandardCharsets.UTF_8);
        }
    }
}
