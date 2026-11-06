package com.github.tvbox.osc.musicbox;

import com.github.tvbox.osc.bean.SourceBean;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Public 5sing search and song metadata. Audio URLs are fetched again for every play. */
final class FiveSing {
    static final String SOURCE_KEY="musicbox_5sing";
    private static final Pattern RESULTS=Pattern.compile("var\\s+dataList\\s*=\\s*'((?:\\\\.|[^'])*)'");
    private FiveSing(){}

    static SourceBean source(){SourceBean bean=new SourceBean();bean.setKey(SOURCE_KEY);bean.setName("5sing 音乐");return bean;}
    static boolean owns(MusicSourceBridge.Song song){return song!=null&&SOURCE_KEY.equals(song.source.getKey());}

    static List<MusicSourceBridge.Song> search(String keyword)throws Exception{
        if(Thread.currentThread().isInterrupted())throw new InterruptedException();
        String html=request("http://search.5sing.kugou.com/?keyword="+URLEncoder.encode(keyword,"UTF-8"),2_000_000);
        Matcher match=RESULTS.matcher(html);
        if(!match.find())return Collections.emptyList();
        String decoded=new JSONArray("[\""+match.group(1)+"\"]").getString(0);
        JSONArray rows=new JSONArray(decoded);
        List<MusicSourceBridge.Song> songs=new ArrayList<>();
        SourceBean source=source();
        for(int i=0;i<Math.min(rows.length(),25);i++){
            if(Thread.currentThread().isInterrupted())throw new InterruptedException();
            JSONObject row=rows.optJSONObject(i);if(row==null)continue;
            String title=clean(row.optString("songName"));
            String artist=clean(row.optString("singer"));
            String kind=row.optString("typeEname");
            String id=row.optString("songId");
            if(title.isEmpty()||id.isEmpty()||!("yc".equals(kind)||"fc".equals(kind)||"bz".equals(kind)))continue;
            MusicSourceBridge.Song song=new MusicSourceBridge.Song(source,kind+":"+id,title,artist,kind,id,false,"");
            if(MusicSourceBridge.relevance(song,keyword)<28)songs.add(song);
            if(songs.size()==25)break;
        }
        return songs;
    }

    static MusicSourceBridge.PlaybackInfo resolve(MusicSourceBridge.Song song)throws Exception{
        JSONObject data=songApi("getSongUrl",song);
        String url=data.optString("hqurl");
        if(url.isEmpty())url=data.optString("lqurl");
        if(url.isEmpty())url=data.optString("squrl");
        if(!url.startsWith("http://")&&!url.startsWith("https://"))throw new IllegalStateException("5sing 暂无可播放音频");
        if(url.startsWith("http://"))url="https://"+url.substring(7);
        return new MusicSourceBridge.PlaybackInfo(url,Collections.emptyMap());
    }

    static String lyrics(MusicSourceBridge.Song song)throws Exception{
        return songApi("newget",song).optString("dynamicWords").trim();
    }

    static String cover(MusicSourceBridge.Song song)throws Exception{
        JSONObject data=songApi("newget",song);
        JSONArray images=data.optJSONArray("img");
        if(images!=null&&images.length()>0){
            JSONObject first=images.optJSONObject(0);
            String url=first==null?images.optString(0):first.optString("url",first.optString("src"));
            if(url.startsWith("https://")||url.startsWith("http://"))return url;
        }
        JSONObject user=data.optJSONObject("user");
        return user==null?"":user.optString("I");
    }

    static String matchingLyrics(String title,String artist)throws Exception{
        MusicSourceBridge.Song song=exactMatch(title,artist);
        if(song==null){
            LyricMatcher.Query query=LyricMatcher.parse(title,artist,0);
            for(MusicSourceBridge.Song candidate:search(query.title))
                if(normalize(candidate.title).equals(normalize(query.title))){song=candidate;break;}
        }
        return song==null?"":lyrics(song);
    }

    static String matchingCover(String title,String artist)throws Exception{
        MusicSourceBridge.Song song=exactMatch(title,artist);
        return song==null?"":cover(song);
    }

    private static MusicSourceBridge.Song exactMatch(String title,String artist)throws Exception{
        if(artist==null||artist.trim().isEmpty())return null;
        LyricMatcher.Query query=LyricMatcher.parse(title,artist,0);
        for(MusicSourceBridge.Song song:search(query.title))
            if(normalize(song.title).equals(normalize(query.title))&&normalize(song.artist).equals(normalize(query.artist)))return song;
        return null;
    }

    private static JSONObject songApi(String method,MusicSourceBridge.Song song)throws Exception{
        String kind=song.playFlag,id=song.playId;
        if(!("yc".equals(kind)||"fc".equals(kind)||"bz".equals(kind))||!id.matches("[0-9]+"))throw new IllegalArgumentException("无效的 5sing 歌曲编号");
        String endpoint="http://mobileapi.5sing.kugou.com/song/"+method+"?songtype="+kind+"&songid="+id+"&version=6.0.3";
        JSONObject response=new JSONObject(request(endpoint,1_000_000));
        JSONObject data=response.optJSONObject("data");
        if(!response.optBoolean("success")||data==null)throw new IllegalStateException("5sing 歌曲服务暂不可用");
        return data;
    }

    private static String clean(String value){return android.text.Html.fromHtml(value,android.text.Html.FROM_HTML_MODE_LEGACY).toString().trim();}
    private static String normalize(String value){return value.toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N}]","");}
    private static String request(String endpoint,int limit)throws Exception{
        if(Thread.currentThread().isInterrupted())throw new InterruptedException();
        HttpURLConnection connection=(HttpURLConnection)new URL(endpoint).openConnection();
        connection.setConnectTimeout(5000);connection.setReadTimeout(7000);
        connection.setRequestProperty("User-Agent","Mozilla/5.0 (compatible; MusicBox/1.0)");
        connection.setRequestProperty("Referer","https://5sing.kugou.com/");
        try{
            if(connection.getResponseCode()!=200)throw new IllegalStateException("5sing HTTP "+connection.getResponseCode());
            ByteArrayOutputStream bytes=new ByteArrayOutputStream();
            try(InputStream stream=connection.getInputStream()){
                byte[] buffer=new byte[4096];int count;
                while((count=stream.read(buffer))!=-1){if(bytes.size()+count>limit)throw new IllegalStateException("5sing 响应过大");bytes.write(buffer,0,count);}
            }
            return bytes.toString("UTF-8");
        }finally{connection.disconnect();}
    }
}
