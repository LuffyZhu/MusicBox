package com.github.tvbox.osc.musicbox;

import android.content.Context;
import com.github.tvbox.osc.api.ApiConfig;
import com.github.tvbox.osc.bean.SourceBean;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.List;

/** Local favorites and user playlists. Stream URLs are intentionally never stored. */
final class MusicLibrary {
    static final String FAVORITES="我的收藏";
    private static final String PREF="musicbox_library";
    private MusicLibrary() {}

    private static JSONObject read(Context context) {
        try{return new JSONObject(context.getSharedPreferences(PREF,0).getString("data","{}"));}
        catch(Exception ignored){return new JSONObject();}
    }
    private static void save(Context context,JSONObject data) {
        context.getSharedPreferences(PREF,0).edit().putString("data",data.toString()).apply();
    }
    private static JSONArray list(JSONObject data,String name) {
        if(FAVORITES.equals(name))return data.optJSONArray("favorites");
        JSONObject playlists=data.optJSONObject("playlists");
        return playlists==null?null:playlists.optJSONArray(name);
    }
    private static String key(JSONObject item) {
        return item.optString("sourceKey")+"\u0000"+item.optString("id")+"\u0000"+item.optString("playId");
    }
    private static String key(MusicSourceBridge.Song song) {
        return song.source.getKey()+"\u0000"+song.id+"\u0000"+song.playId;
    }
    private static boolean contains(JSONArray list,MusicSourceBridge.Song song) {
        if(list==null)return false;
        String wanted=key(song);
        for(int i=0;i<list.length();i++){
            JSONObject item=list.optJSONObject(i);
            if(item!=null&&wanted.equals(key(item)))return true;
        }
        return false;
    }
    private static JSONObject encode(MusicSourceBridge.Song song) {
        JSONObject item=new JSONObject();
        try {
            item.put("sourceKey",song.source.getKey());item.put("sourceName",song.source.getName());
            item.put("id",song.id);item.put("title",song.title);item.put("artist",song.artist);
            item.put("playFlag",song.playFlag);item.put("playId",song.playId);
            item.put("mv",song.mv);item.put("cover",song.cover);
        }catch(Exception ignored){}
        return item;
    }
    static boolean isFavorite(Context context,MusicSourceBridge.Song song) {
        return contains(list(read(context),FAVORITES),song);
    }
    static boolean toggleFavorite(Context context,MusicSourceBridge.Song song) {
        JSONObject data=read(context);JSONArray old=list(data,FAVORITES),next=new JSONArray();
        boolean existed=contains(old,song);
        if(old!=null)for(int i=0;i<old.length();i++){
            JSONObject item=old.optJSONObject(i);
            if(item!=null&&!key(song).equals(key(item)))next.put(item);
        }
        if(!existed)next.put(encode(song));
        try{data.put("favorites",next);}catch(Exception ignored){}
        save(context,data);return !existed;
    }
    static List<String> playlistNames(Context context) {
        List<String> names=new ArrayList<>();JSONObject playlists=read(context).optJSONObject("playlists");
        if(playlists!=null){java.util.Iterator<String> keys=playlists.keys();while(keys.hasNext())names.add(keys.next());}
        java.util.Collections.sort(names);return names;
    }
    static boolean createPlaylist(Context context,String rawName) {
        String name=rawName==null?"":rawName.trim();
        if(name.isEmpty()||name.length()>30||FAVORITES.equals(name))return false;
        JSONObject data=read(context),playlists=data.optJSONObject("playlists");
        if(playlists==null)playlists=new JSONObject();
        if(playlists.has(name))return false;
        try{playlists.put(name,new JSONArray());data.put("playlists",playlists);save(context,data);return true;}
        catch(Exception ignored){return false;}
    }
    static void deletePlaylist(Context context,String name) {
        if(FAVORITES.equals(name))return;
        JSONObject data=read(context),playlists=data.optJSONObject("playlists");
        if(playlists==null)return;
        playlists.remove(name);save(context,data);
    }
    static boolean add(Context context,String name,MusicSourceBridge.Song song) {
        if(FAVORITES.equals(name))return isFavorite(context,song)||toggleFavorite(context,song);
        JSONObject data=read(context),playlists=data.optJSONObject("playlists");
        if(playlists==null)return false;
        JSONArray songs=playlists.optJSONArray(name);
        if(songs==null)return false;
        if(contains(songs,song))return true;
        songs.put(encode(song));save(context,data);return true;
    }
    static void remove(Context context,String name,MusicSourceBridge.Song song) {
        JSONObject data=read(context),playlists=data.optJSONObject("playlists");
        JSONArray old=list(data,name),next=new JSONArray();
        if(old==null)return;
        for(int i=0;i<old.length();i++){
            JSONObject item=old.optJSONObject(i);
            if(item!=null&&!key(song).equals(key(item)))next.put(item);
        }
        try{
            if(FAVORITES.equals(name))data.put("favorites",next);
            else if(playlists!=null)playlists.put(name,next);
            save(context,data);
        }catch(Exception ignored){}
    }
    static List<MusicSourceBridge.Song> songs(Context context,String name) {
        List<MusicSourceBridge.Song> songs=new ArrayList<>();JSONArray list=list(read(context),name);
        if(list==null)return songs;
        for(int i=0;i<list.length();i++){
            JSONObject item=list.optJSONObject(i);if(item==null)continue;
            String sourceKey=item.optString("sourceKey","");
            SourceBean source=null;
            if(PersonalCloud.BAIDU.equals(sourceKey)||PersonalCloud.QUARK.equals(sourceKey))source=PersonalCloud.source(sourceKey);
            else if(YouTubeMv.SOURCE_KEY.equals(sourceKey))source=YouTubeMv.source();
            else for(SourceBean candidate:ApiConfig.get().getSourceBeanList())if(sourceKey.equals(candidate.getKey())){source=candidate;break;}
            if(source==null)continue;
            songs.add(new MusicSourceBridge.Song(source,item.optString("id"),item.optString("title"),
                item.optString("artist"),item.optString("playFlag"),item.optString("playId"),
                item.optBoolean("mv"),item.optString("cover")));
        }
        return songs;
    }
}
