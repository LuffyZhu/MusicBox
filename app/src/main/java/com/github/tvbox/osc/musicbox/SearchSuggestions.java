package com.github.tvbox.osc.musicbox;

import org.json.JSONArray;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.List;

/** Song-title suggestions; callers keep local history available when the service is offline. */
final class SearchSuggestions {
    private SearchSuggestions(){}

    static List<String> find(String keyword){
        List<String> titles=new ArrayList<>();
        HttpURLConnection connection=null;
        try{
            String address="https://music.163.com/api/search/get/web?csrf_token=&s="
                +URLEncoder.encode(keyword,"UTF-8")+"&type=1&offset=0&total=true&limit=8";
            connection=(HttpURLConnection)new URL(address).openConnection();
            connection.setConnectTimeout(3000);
            connection.setReadTimeout(3000);
            connection.setRequestProperty("User-Agent","Mozilla/5.0 MusicBox/1.0");
            try(InputStream input=connection.getInputStream();ByteArrayOutputStream bytes=new ByteArrayOutputStream()){
                byte[] buffer=new byte[4096];int count;
                while((count=input.read(buffer))!=-1&&bytes.size()<65536)bytes.write(buffer,0,count);
                JSONObject result=new JSONObject(bytes.toString("UTF-8")).optJSONObject("result");
                JSONArray songs=result==null?null:result.optJSONArray("songs");
                if(songs!=null)for(int i=0;i<songs.length()&&titles.size()<5;i++){
                    JSONObject song=songs.optJSONObject(i);
                    if(song==null)continue;
                    String title=song.optString("name").trim();
                    if(!title.isEmpty()&&!titles.contains(title))titles.add(title);
                }
            }
        }catch(Exception ignored){}
        finally{if(connection!=null)connection.disconnect();}
        return titles;
    }
}
