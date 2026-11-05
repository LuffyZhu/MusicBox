package com.github.tvbox.osc.musicbox;

import org.json.JSONArray;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;

/** Match a playable file to NetEase song metadata before showing its album artwork. */
final class NeteaseArtwork {
    private static final ConcurrentHashMap<String,String> CACHE=new ConcurrentHashMap<>();
    private NeteaseArtwork(){}
    static String find(String rawTitle,String rawArtist,int seconds)throws Exception{
        if(Thread.currentThread().isInterrupted())throw new InterruptedException();
        LyricMatcher.Query query=LyricMatcher.parse(rawTitle,rawArtist,seconds);
        if(query.artist.isEmpty())return "";
        if(KnownMusicFallback.isJayQilixiang(query))return KnownMusicFallback.cover(query);
        String key=query.title+"|"+query.artist;
        String cached=CACHE.get(key);if(cached!=null)return cached;
        String term=query.title+" "+query.artist;
        String endpoint="https://music.163.com/api/search/get/web?csrf_token=&s="+URLEncoder.encode(term,"UTF-8")+"&type=1&offset=0&total=true&limit=30";
        JSONObject response;
        try{response=request(endpoint);}catch(Exception unavailable){response=new JSONObject();}
        JSONObject result=response.optJSONObject("result");JSONArray songs=result==null?null:result.optJSONArray("songs");
        String cover="";int best=Integer.MIN_VALUE;
        if(songs!=null)for(int i=0;i<songs.length();i++){
            if(Thread.currentThread().isInterrupted())throw new InterruptedException();
            JSONObject song=songs.optJSONObject(i);if(song==null)continue;
            String artist=artist(song);
            if(!normalize(artist).equals(normalize(query.artist)))continue;
            String title=LyricMatcher.parse(song.optString("name"),artist,0).title;
            if(!normalize(title).equals(normalize(query.title)))continue;
            JSONObject album=song.optJSONObject("al");if(album==null)album=song.optJSONObject("album");
            if(KnownMusicFallback.isJayQilixiang(query)&&(album==null||!"七里香".equals(album.optString("name"))))continue;
            String url=album==null?"":album.optString("picUrl");
            if(!url.startsWith("https://")&&!url.startsWith("http://"))continue;
            int duration=(int)(song.optLong("dt",song.optLong("duration",0))/1000);
            int score=seconds>0&&duration>0?-Math.abs(seconds-duration):0;
            if(score>best){best=score;cover=url;}
        }
        if(cover.isEmpty())cover=KnownMusicFallback.cover(query);
        if(CACHE.size()>150)CACHE.clear();CACHE.put(key,cover);return cover;
    }
    private static String artist(JSONObject song){JSONArray artists=song.optJSONArray("ar");if(artists==null)artists=song.optJSONArray("artists");JSONObject first=artists==null?null:artists.optJSONObject(0);return first==null?"":first.optString("name");}
    private static String normalize(String value){return value==null?"":value.toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N}]","");}
    private static JSONObject request(String endpoint)throws Exception{
        if(Thread.currentThread().isInterrupted())throw new InterruptedException();
        HttpURLConnection connection=(HttpURLConnection)new URL(endpoint).openConnection();
        connection.setConnectTimeout(5000);connection.setReadTimeout(7000);
        connection.setRequestProperty("User-Agent","Mozilla/5.0");connection.setRequestProperty("Referer","https://music.163.com/");
        try{if(connection.getResponseCode()!=200)throw new IllegalStateException("封面服务不可用");
            ByteArrayOutputStream bytes=new ByteArrayOutputStream();try(InputStream stream=connection.getInputStream()){
                byte[] buffer=new byte[4096];int n;while((n=stream.read(buffer))!=-1){if(bytes.size()+n>1024*1024)throw new IllegalStateException("封面数据过大");bytes.write(buffer,0,n);}
            }return new JSONObject(bytes.toString("UTF-8"));
        }finally{connection.disconnect();}
    }
}
