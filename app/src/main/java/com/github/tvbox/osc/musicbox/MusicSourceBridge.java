package com.github.tvbox.osc.musicbox;

import com.github.catvod.crawler.Spider;
import com.github.tvbox.osc.api.ApiConfig;
import com.github.tvbox.osc.bean.SourceBean;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.HashMap;
import java.util.Iterator;
import java.net.HttpURLConnection;
import java.net.URL;

/** Search the complete TVBox subscription, then classify returned media. */
public final class MusicSourceBridge {
    public static final class Song {
        public final SourceBean source;
        public final String id,title,artist,playFlag,playId,cover;
        public final boolean mv;
        Song(SourceBean s,String i,String t,String a){this(s,i,t,a,"","",isMv(t,s.getName()),"");}
        Song(SourceBean s,String i,String t,String a,String f,String p){this(s,i,t,a,f,p,false);}
        Song(SourceBean s,String i,String t,String a,String f,String p,boolean video){this(s,i,t,a,f,p,video,"");}
        Song(SourceBean s,String i,String t,String a,String f,String p,boolean video,String artwork){
            source=s;id=i;title=t;artist=a;playFlag=f;playId=p;mv=video;cover=artwork==null?"":artwork;
        }
    }
    public static final class PlaybackInfo {
        public final String url;
        public final Map<String,String> headers;
        PlaybackInfo(String url,Map<String,String> headers){this.url=url;this.headers=headers;}
    }
    public static final class PlayOption {
        public final String label;
        public final Song song;
        PlayOption(String label,Song song){this.label=label;this.song=song;}
    }
    private MusicSourceBridge(){}

    public static List<SourceBean> musicSites(){
        List<SourceBean> out=new ArrayList<>();
        for(SourceBean s:ApiConfig.get().getSourceBeanList())
            if(s.isSearchable()&&s.getType()==3&&s.getApi().startsWith("csp_"))out.add(s);
        Collections.sort(out,Comparator.comparingInt(MusicSourceBridge::priority));
        return out;
    }
    public static int priority(SourceBean s){
        String n=s.getName()+s.getKey();
        if(n.contains("盘搜")||n.contains("网盘"))return 0;
        if(n.contains("闪电")||n.contains("秒播"))return 1;
        if(n.contains("音乐")||n.contains("歌曲")||n.contains("舞曲"))return 2;
        return 3;
    }
    public static List<Song> search(SourceBean source,String query)throws Exception{
        Spider spider=ApiConfig.get().getCSP(source);
        JSONArray list=new JSONObject(spider.searchContent(query,false)).optJSONArray("list");
        List<Song> out=new ArrayList<>();if(list==null)return out;
        for(int i=0;i<Math.min(list.length(),180);i++){
            JSONObject item=list.optJSONObject(i);if(item==null)continue;
            String id=item.optString("vod_id"),title=item.optString("vod_name");
            if(id.isEmpty()||title.isEmpty())continue;
            if(source.getKey().equals("SoAiHaiYin")&&item.optString("vod_remarks").contains("点击查看")){
                drillCloudGroup(spider,source,id,query,out);continue;
            }
            String artist=item.optString("vod_actor");
            if(matches(title,artist,query)&&isMusicResult(source,item))out.add(new Song(source,id,title,artist,"","",isMv(title,source.getName()),item.optString("vod_pic")));
        }
        return out;
    }
    public static List<Song> searchVerified(SourceBean source,String query)throws Exception{
        return searchVerified(source,query,true);
    }
    public static List<Song> searchVerified(SourceBean source,String query,boolean fast)throws Exception{
        if(Thread.currentThread().isInterrupted())throw new InterruptedException();
        List<Song> found=search(source,query);
        found.sort((a,b)->Integer.compare(relevance(a,query),relevance(b,query)));
        List<Song> ready=new ArrayList<>();
        int limit=fast?(priority(source)==0?12:source.getName().contains("瓜子")?10:6):(priority(source)==0?20:source.getName().contains("瓜子")?20:8);
        int target=priority(source)==0?5:3;
        long deadline=System.nanoTime()+18_000_000_000L;
        for(int i=0;i<found.size()&&i<limit&&(!fast||ready.size()<target);i++){
            if(Thread.currentThread().isInterrupted())throw new InterruptedException();
            if(fast&&i>0&&System.nanoTime()>deadline)break;
            try{if(preflight(found.get(i)))ready.add(found.get(i));}
            catch(Exception ignored){ }
        }
        return ready;
    }
    public static int relevance(Song song,String query){
        return MusicSearchMatcher.distance(rankTitle(song.title,song.artist),query);
    }
    private static String rankTitle(String title,String artist){
        String parsed=LyricMatcher.parse(title,artist,0).title;
        java.util.regex.Matcher quoted=java.util.regex.Pattern.compile("《([^》]{1,60})》").matcher(parsed);
        if(quoted.find())return normalize(quoted.group(1));
        return normalize(parsed.replaceAll("^[^\\p{IsHan}a-zA-Z0-9]+", ""));
    }
    private static boolean preflight(Song song)throws Exception{
        if(Thread.currentThread().isInterrupted())throw new InterruptedException();
        if(song.mv){
            List<PlayOption> choices=options(song);
            if(choices.isEmpty())return false;
            for(int i=0;i<choices.size()&&i<3;i++){
                if(Thread.currentThread().isInterrupted())throw new InterruptedException();
                try{if(probeUrl(resolve(choices.get(i).song)))return true;}
                catch(Exception ignored){ }
            }
            return false;
        }
        return probeUrl(resolve(song));
    }
    private static boolean probeUrl(PlaybackInfo info)throws Exception{
        HttpURLConnection connection=(HttpURLConnection)new URL(info.url).openConnection();
        connection.setInstanceFollowRedirects(true);
        connection.setConnectTimeout(4000);connection.setReadTimeout(5000);
        connection.setRequestProperty("Range","bytes=0-1");
        for(Map.Entry<String,String> entry:info.headers.entrySet())connection.setRequestProperty(entry.getKey(),entry.getValue());
        try{int code=connection.getResponseCode();String type=connection.getContentType();String normalizedType=type==null?"":type.toLowerCase(Locale.ROOT);return (code==200||code==206)&&!normalizedType.contains("text/html")&&!normalizedType.contains("application/json");}
        finally{connection.disconnect();}
    }
    private static void drillCloudGroup(Spider spider,SourceBean source,String groupId,String query,List<Song> out){
        try{
            JSONArray shares=new JSONObject(spider.detailContent(Collections.singletonList(groupId))).optJSONArray("list");
            if(shares==null)return;
            for(int i=0;i<Math.min(shares.length(),40)&&out.size()<100;i++){
                JSONObject share=shares.optJSONObject(i);if(share==null)continue;
                String title=share.optString("vod_name"),id=share.optString("vod_id");
                if(id.isEmpty()||!matches(title,"",query)||!hasMusicEvidence(title)||isFilm(title))continue;
                try{
                    JSONArray files=new JSONObject(spider.detailContent(Collections.singletonList(id))).optJSONArray("list");
                    if(files==null)continue;
                    for(int f=0;f<files.length()&&out.size()<100;f++){
                        JSONObject file=files.optJSONObject(f);if(file==null)continue;
                        String[] flags=file.optString("vod_play_from").split("\\$\\$\\$");
                        String[] groups=file.optString("vod_play_url").split("\\$\\$\\$");
                        for(int g=0;g<groups.length&&out.size()<100;g++){
                            String flag=g<flags.length?flags[g]:"";
                            for(String entry:groups[g].split("#")){
                                int sep=entry.indexOf('$');if(sep<=0)continue;
                                String label=entry.substring(0,sep),playId=entry.substring(sep+1);
                                if(playId.isEmpty()||!isAudioFile(label)||!matches(label,"",query))continue;
                                out.add(new Song(source,id,titleFrom(label),artistFrom(label,title),flag,playId));
                                if(out.size()>=100)break;
                            }
                        }
                    }
                }catch(Exception ignored){ }
            }
        }catch(Exception ignored){ }
    }
    private static boolean isMusicResult(SourceBean source,JSONObject item){
        String title=item.optString("vod_name"),meta=item.optString("type_name")+" "+item.optString("vod_remarks");
        if(isFilm(title))return false;
        if(isFilm(meta)&&!title.matches("(?is).*【(?:音乐|歌曲|mv)】.*"))return false;
        if(isAudioFile(title))return true;
        String n=source.getName();
        if(n.contains("瓜子"))return hasMusicEvidence(title)||hasMusicEvidence(meta);
        if(n.contains("音乐")||n.contains("歌曲")||n.contains("舞曲"))return true;
        return hasMusicEvidence(title)||hasMusicEvidence(meta);
    }
    private static boolean hasMusicEvidence(String s){
        String x=s.toLowerCase(Locale.ROOT);
        return x.contains("音乐")||x.contains("歌曲")||x.contains("专辑")||x.contains("单曲")
            ||x.contains("无损")||x.contains("flac")||x.contains("mp3")||x.contains("mv")||x.contains("演唱会");
    }
    private static boolean isMv(String title,String source){
        String name=title.toLowerCase(Locale.ROOT);
        if(isAudioFile(title))return false;
        return name.matches(".*(^|[^a-z])mv([^a-z]|$).*")||name.contains("音乐视频")
            ||source.contains("瓜子")||source.toLowerCase(Locale.ROOT).matches(".*(^|[^a-z])mv([^a-z]|$).*");
    }
    private static boolean isFilm(String s){
        String x=s.toLowerCase(Locale.ROOT);
        return x.contains("短剧")||x.contains("电视剧")||x.contains("电影")||x.contains("剧集")
            ||x.contains("全集")||x.matches(".*第[0-9一二三四五六七八九十百]+集.*")
            ||x.matches(".*[（(][0-9]{2,4}集[)）].*")||x.matches(".*s[0-9]{1,2}e[0-9]{1,3}.*");
    }
    private static boolean isAudioFile(String s){
        String x=fileName(s).toLowerCase(Locale.ROOT).replaceAll("[?].*$","");
        return x.matches(".*\\.(mp3|flac|m4a|aac|wav|ogg|opus|ape|wma)$")&&!isFilm(s);
    }
    private static String fileName(String s){return s.replaceFirst("^\\[[^]]+\\]","").replaceFirst("【.*$","").trim();}
    private static String cleanTitle(String s){return fileName(s).replaceFirst("(?i)\\.(mp3|flac|m4a|aac|wav|ogg|opus|ape|wma)$","").replaceFirst("^[0-9]{1,3}\\.\\s*","");}
    private static String titleFrom(String label){String s=cleanTitle(label);int sep=s.indexOf(" - ");return sep>0?s.substring(sep+3):s;}
    private static String artistFrom(String label,String album){
        String clean=cleanTitle(label);int sep=clean.indexOf(" - ");
        return sep>0?clean.substring(0,sep):album;
    }
    private static boolean matches(String title,String artist,String query){
        if(query==null||query.trim().isEmpty())return false;
        String all=title+" "+artist;
        if(MusicSearchMatcher.distance(all,query)<100)return true;
        for(String part:query.trim().split("\\s+"))
            if(!part.isEmpty()&&MusicSearchMatcher.distance(all,part)>=100)return false;
        return true;
    }
    public static PlaybackInfo resolve(Song song)throws Exception{
        Spider spider=ApiConfig.get().getCSP(song.source);
        String flag=song.playFlag,id=song.playId;
        if(id.isEmpty()){
            List<PlayOption> options=options(song);
            if(options.isEmpty())throw new IllegalStateException("详情没有可播放曲目");
            Song selected=options.get(0).song;
            for(PlayOption option:options)if(normalize(option.song.title).equals(normalize(song.title))){selected=option.song;break;}
            flag=selected.playFlag;id=selected.playId;
        }
        JSONObject media=new JSONObject(spider.playerContent(flag,id,Collections.emptyList()));
        String pluginMessage=media.optString("msg");
        if(media.optString("url").isEmpty()&&!pluginMessage.isEmpty())throw new IllegalStateException(pluginMessage);
        if(media.optInt("parse",0)!=0||media.optInt("jx",0)!=0)throw new IllegalStateException("来源要求网页解析");
        String url=media.optString("playUrl")+media.optString("url");
        if(!(url.startsWith("https://")||url.startsWith("http://")))throw new IllegalStateException("来源未提供直接播放地址");
        Map<String,String> headers=new HashMap<>();
        Object raw=media.opt("header");
        JSONObject values=raw instanceof JSONObject?(JSONObject)raw:null;
        if(values==null&&raw instanceof String)try{values=new JSONObject((String)raw);}catch(Exception ignored){}
        if(values!=null){
            Iterator<String> names=values.keys();
            while(names.hasNext()){
                String name=names.next(),value=values.optString(name);
                if(!name.isEmpty()&&!value.isEmpty())headers.put(name,value);
            }
        }
        return new PlaybackInfo(url,headers);
    }
    public static List<PlayOption> options(Song song)throws Exception{
        if(!song.playId.isEmpty())return Collections.singletonList(new PlayOption(song.title,song));
        Spider spider=ApiConfig.get().getCSP(song.source);
        JSONArray list=new JSONObject(spider.detailContent(Collections.singletonList(song.id))).optJSONArray("list");
        if(list==null||list.length()==0)throw new IllegalStateException("没有歌曲详情");
        JSONObject item=list.getJSONObject(0);
        String[] flags=item.optString("vod_play_from").split("\\$\\$\\$");
        String[] groups=item.optString("vod_play_url").split("\\$\\$\\$");
        List<PlayOption> out=new ArrayList<>();
        for(int g=0;g<groups.length&&out.size()<120;g++){
            String flag=g<flags.length?flags[g]:flags.length>0?flags[0]:"";
            for(String entry:groups[g].split("#")){
                int sep=entry.indexOf('$');if(sep<=0)continue;
                String label=entry.substring(0,sep).trim(),id=entry.substring(sep+1);
                if(id.isEmpty())continue;
                Song selected=new Song(song.source,song.id,label,song.artist,flag,id,song.mv,song.cover);
                out.add(new PlayOption((flag.isEmpty()?"":flag+" · ")+label,selected));
                if(out.size()>=120)break;
            }
        }
        if(song.mv){
            for(PlayOption option:out)
                if(normalize(option.song.title).equals(normalize(song.title)))
                    return Collections.singletonList(option);
            List<PlayOption> primary=new ArrayList<>();
            for(PlayOption option:out)
                if(!option.song.playFlag.contains("相关")&&!option.song.title.startsWith("相关"))
                    primary.add(option);
            if(!primary.isEmpty())out=primary;
            if(out.size()>20)return new ArrayList<>(out.subList(0,20));
        }
        return out;
    }
    public static String playUrl(Song song)throws Exception{return resolve(song).url;}
    private static String normalize(String s){return s==null?"":s.replaceAll("\\s+","").toLowerCase(Locale.ROOT);}
}
