package com.github.tvbox.osc.musicbox;

import android.content.Context;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;

/** Public new-song recommendations used while no NetEase account is connected. */
final class DailyRecommendations {
    static final class Result {
        final List<Song> songs;final boolean personalized;
        Result(List<Song> songs,boolean personalized){this.songs=songs;this.personalized=personalized;}
    }
    static final class Song {
        final String title, artist, cover;
        Song(String title, String artist, String cover) {
            this.title=title;this.artist=artist;this.cover=cover;
        }
    }
    private DailyRecommendations() {}

    static Result load(Context context) throws Exception {
        String cookie=context.getSharedPreferences("musicbox_recommendations",0).getString("netease_cookie","").trim();
        if(!cookie.isEmpty())try {
            List<Song> personal=parse(request("https://music.163.com/api/v3/discovery/recommend/songs",cookie),true);
            if(!personal.isEmpty())return new Result(personal,true);
        }catch(Exception ignored){}
        return new Result(parse(request("https://music.163.com/api/personalized/newsong?limit=30",""),false),false);
    }
    private static JSONObject request(String address,String cookie) throws Exception {
        HttpURLConnection connection=(HttpURLConnection)new URL(address).openConnection();
        connection.setConnectTimeout(5000);
        connection.setReadTimeout(7000);
        connection.setRequestProperty("User-Agent","Mozilla/5.0 (MusicBox)");
        connection.setRequestProperty("Referer","https://music.163.com/");
        if(!cookie.isEmpty())connection.setRequestProperty("Cookie",cookie);
        try {
            if(connection.getResponseCode()!=200)throw new IllegalStateException("推荐接口不可用");
            ByteArrayOutputStream bytes=new ByteArrayOutputStream();
            try(InputStream stream=connection.getInputStream()) {
                byte[] buffer=new byte[4096];int n;
                while((n=stream.read(buffer))!=-1) {
                    if(bytes.size()+n>512*1024)throw new IllegalStateException("推荐数据过大");
                    bytes.write(buffer,0,n);
                }
            }
            JSONObject response=new JSONObject(bytes.toString("UTF-8"));
            if(response.optInt("code")!=200)throw new IllegalStateException("推荐接口返回错误");
            return response;
        } finally {connection.disconnect();}
    }
    private static List<Song> parse(JSONObject response,boolean personalized) {
            JSONObject data=response.optJSONObject("data");
            JSONArray result=personalized?(data==null?null:data.optJSONArray("dailySongs")):response.optJSONArray("result");
            List<Song> songs=new ArrayList<>();
            if(result==null)return songs;
            for(int i=0;i<result.length()&&songs.size()<24;i++) {
                JSONObject item=result.optJSONObject(i);if(item==null)continue;
                JSONObject song=item.optJSONObject("song");if(song==null)song=item;
                String title=item.optString("name",song.optString("name",""));
                JSONArray artists=song.optJSONArray("artists");if(artists==null)artists=song.optJSONArray("ar");
                JSONObject first=artists==null?null:artists.optJSONObject(0);
                String artist=first==null?"":first.optString("name","");
                JSONObject album=song.optJSONObject("album");if(album==null)album=song.optJSONObject("al");
                String cover=album==null?"":album.optString("picUrl","");
                if(!title.isEmpty()&&!artist.isEmpty())songs.add(new Song(title,artist,cover));
            }
            return songs;
    }
}
