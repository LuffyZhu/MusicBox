package com.github.tvbox.osc.musicbox;

import org.json.JSONArray;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.HashSet;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Fallback for LRCLIB outages. NetEase's web endpoints are not a stable public API. */
final class NeteaseLyrics {
    private NeteaseLyrics(){}

    static List<LyricMatcher.Candidate> candidates(LyricMatcher.Query query,int limit)throws Exception{
        List<LyricMatcher.Candidate> found=new ArrayList<>();
        if(limit<=0||query.title.isEmpty())return found;
        String address="https://music.163.com/api/search/get/web?csrf_token=&s="
            +URLEncoder.encode(query.title,"UTF-8")+"&type=1&offset=0&total=true&limit=30";
        JSONObject search=new JSONObject(request(address));
        JSONArray rows=search.optJSONObject("result")==null?null:search.optJSONObject("result").optJSONArray("songs");
        if(rows==null)return found;
        List<JSONObject> ranked=new ArrayList<>();
        for(int i=0;i<rows.length();i++){
            JSONObject song=rows.optJSONObject(i);if(song==null)continue;
            JSONObject record=new JSONObject();record.put("trackName",song.optString("name"));
            record.put("artistName",firstArtist(song));
            record.put("duration",(int)(song.optLong("duration",song.optLong("dt",0))/1000));
            if(LyricMatcher.score(query,record)>=60||normalize(song.optString("name")).equals(normalize(query.title)))ranked.add(song);
        }
        ranked.sort((a,b)->Integer.compare(candidateScore(query,b),candidateScore(query,a)));
        for(JSONObject song:ranked){
            if(found.size()>=limit||Thread.currentThread().isInterrupted())break;
            long id=song.optLong("id",0);if(id<=0)continue;
            try{String text=lyricById(id);if(text!=null&&!text.isEmpty())
                found.add(new LyricMatcher.Candidate("网易云 · "+song.optString("name")+" · "+firstArtist(song),text));
            }catch(Exception ignored){}
        }
        return found;
    }
    private static int candidateScore(LyricMatcher.Query query,JSONObject song){
        try{JSONObject record=new JSONObject();record.put("trackName",song.optString("name"));record.put("artistName",firstArtist(song));
            record.put("duration",(int)(song.optLong("duration",song.optLong("dt",0))/1000));return LyricMatcher.score(query,record);}
        catch(Exception ignored){return -1000;}
    }

    static String find(LyricMatcher.Query query)throws Exception{
        if(Thread.currentThread().isInterrupted())throw new InterruptedException();
        String term=query.title+(query.artist.isEmpty()?"":" "+query.artist);
        String address="https://music.163.com/api/search/get/web?csrf_token=&s="
            +URLEncoder.encode(term,"UTF-8")+"&type=1&offset=0&total=true&limit=50";
        JSONObject search;
        try{search=new JSONObject(request(address));}
        catch(Exception unavailable){return knownOriginal(query);}
        JSONArray songs=search.optJSONObject("result")==null?null:search.optJSONObject("result").optJSONArray("songs");
        if(songs==null||songs.length()==0)return knownOriginal(query);
        JSONObject best=null;
        int bestScore=Integer.MIN_VALUE;
        Set<String> exactArtists=new HashSet<>();
        for(int i=0;i<songs.length();i++){
            if(Thread.currentThread().isInterrupted())throw new InterruptedException();
            JSONObject song=songs.optJSONObject(i);if(song==null)continue;
            String name=song.optString("name");
            String performer=firstArtist(song);
            if(KnownMusicFallback.isJayQilixiang(query)){
                JSONObject album=song.optJSONObject("al");if(album==null)album=song.optJSONObject("album");
                if(album==null||!"七里香".equals(album.optString("name")))continue;
            }
            int seconds=(int)(song.optLong("duration",song.optLong("dt",0))/1000);
            LyricMatcher.Query parsed=LyricMatcher.parse(name,"",seconds);
            String effectiveArtist=performer;
            if(!parsed.artist.isEmpty()&&normalize(parsed.artist).equals(normalize(query.artist)))
                effectiveArtist=parsed.artist;
            JSONObject record=new JSONObject();
            record.put("trackName",parsed.title);
            record.put("artistName",effectiveArtist);
            record.put("duration",seconds);
            int score=LyricMatcher.score(query,record);
            if(score>=90&&normalize(parsed.title).equals(normalize(query.title)))
                exactArtists.add(normalize(effectiveArtist));
            if(score>bestScore){bestScore=score;best=song;}
        }
        if(best==null||bestScore<90)return knownOriginal(query);
        if(query.artist.isEmpty()&&query.seconds<=0&&exactArtists.size()>1)return null;
        long id=best.optLong("id",0);if(id<=0)return knownOriginal(query);
        String matched=lyricById(id);
        return matched==null?knownOriginal(query):matched;
    }
    private static String knownOriginal(LyricMatcher.Query query)throws Exception{
        return KnownMusicFallback.lyrics(query);
    }
    private static String lyricById(long id)throws Exception{
        JSONObject response=new JSONObject(request("https://music.163.com/api/song/lyric?id="+id+"&lv=1&tv=-1"));
        if(response.optBoolean("nolyric"))return "纯音乐 · 无歌词";
        JSONObject lrc=response.optJSONObject("lrc");
        if(lrc==null)return null;
        String text=lrc.optString("lyric","");
        if(text.isEmpty())return null;
        text=text.replaceAll("(?m)^\\[[a-zA-Z]+:[^\\]]*\\]\\s*$","")
            .replaceAll("(?m)^\\s*$\\n","").trim();
        return text.isEmpty()?null:text;
    }
    private static String firstArtist(JSONObject song){
        JSONArray artists=song.optJSONArray("artists");
        if(artists==null)artists=song.optJSONArray("ar");
        JSONObject first=artists==null?null:artists.optJSONObject(0);
        return first==null?"":first.optString("name");
    }
    private static String request(String address)throws Exception{
        if(Thread.currentThread().isInterrupted())throw new InterruptedException();
        HttpURLConnection connection=(HttpURLConnection)new URL(address).openConnection();
        connection.setRequestProperty("User-Agent","Mozilla/5.0 (MusicBox/0.3.4)");
        connection.setRequestProperty("Referer","https://music.163.com/");
        connection.setConnectTimeout(6000);
        connection.setReadTimeout(8000);
        try{
            if(connection.getResponseCode()!=200)throw new IllegalStateException("备用歌词来源不可用");
            ByteArrayOutputStream bytes=new ByteArrayOutputStream();
            try(InputStream stream=connection.getInputStream()){
                byte[] buffer=new byte[4096];int count;
                while((count=stream.read(buffer))!=-1){
                    if(bytes.size()+count>1024*1024)throw new IllegalStateException("歌词数据过大");
                    bytes.write(buffer,0,count);
                }
            }
            return bytes.toString("UTF-8");
        }finally{connection.disconnect();}
    }
    private static String normalize(String value){
        return value==null?"":value.toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N}]","");
    }
}
