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
import java.util.concurrent.ConcurrentHashMap;

/** Match cleaned TVBox song metadata against LRCLIB records. */
final class LyricMatcher {
    private static final ConcurrentHashMap<String,String> CACHE=new ConcurrentHashMap<>();
    private static volatile long lrclibUnavailableUntil;
    static final class Query {
        final String title,artist;
        final int seconds;
        final boolean instrumental;
        Query(String title,String artist,int seconds,boolean instrumental){
            this.title=title;this.artist=artist;this.seconds=seconds;this.instrumental=instrumental;
        }
    }
    static final class Candidate {
        final String label,text;
        Candidate(String label,String text){this.label=label;this.text=text;}
    }
    private LyricMatcher(){}

    static Query parse(String rawTitle,String rawArtist,int seconds){
        String title=rawTitle==null?"":rawTitle;
        String artist=rawArtist==null?"":rawArtist.trim();
        boolean instrumental=title.matches("(?is).*(纯音乐|伴奏|钢琴版|演奏版|instrumental).*");
        java.util.regex.Matcher book=java.util.regex.Pattern.compile("《([^》]{1,80})》").matcher(title);
        if(book.find()){
            String prefix=title.substring(0,book.start()).replaceAll("^(?:【[^】]+】\\s*)+","").replaceFirst("^(?:歌手|演唱|原唱)\\s*[:：]\\s*","").trim();
            if(!usableArtist(artist)&&usableArtist(prefix)&&prefix.length()<=24&&!prefix.matches("(?i).*(mp3|lrc|mv|flac|无损).*"))artist=prefix;
            title=book.group(1).trim();
        }
        java.util.regex.Matcher original=java.util.regex.Pattern.compile("原唱\\s*[:：]?\\s*([^】\\]）)，,\\s]+)").matcher(title);
        if(!usableArtist(artist)&&original.find())artist=original.group(1);
        title=title.trim();
        if(title.startsWith("【")&&title.endsWith("】")&&title.length()>2)
            title=title.substring(1,title.length()-1);
        title=title.replaceFirst("^(?:【(?:音乐|歌曲|音频|无损|MV|mv)】\\s*)+","");
        title=title.replaceFirst("^\\[[^]]+\\]","").trim();
        if(title.indexOf('【')>0)title=title.substring(0,title.indexOf('【')).trim();
        title=title.replaceFirst("(?i)\\.(mp3|flac|m4a|aac|wav|ogg|opus|ape|wma)$","");
        title=title.replaceFirst("^[0-9]{1,3}[.、]\\s*","");
        title=title.replaceAll("(?i)[（(\\[][^(）)\\]]*(官方|高清|无损|音质|MV|live|现场|原唱|cover|伴奏|钢琴版)[^）)\\]]*[）)\\]]","");
        title=title.replaceAll("(?i)(官方MV|高清MV|无损音质|高品质音质)","");
        title=title.trim();
        java.util.regex.Matcher pair=java.util.regex.Pattern.compile("^(.{1,30}?)\\s*[-–—]\\s*(.{2,120})$").matcher(title);
        if(pair.matches()&&!pair.group(2).matches("(?i)现场|live|mv|伴奏|纯音乐")){
            String prefix=pair.group(1).trim(),suffix=pair.group(2).trim();
            if(knownArtist(suffix)&&!knownArtist(prefix)){title=prefix;artist=suffix;}
            else{if(!usableArtist(artist)&&usableArtist(prefix))artist=prefix;title=suffix;}
        }
        java.util.regex.Matcher spaced=java.util.regex.Pattern.compile("^([\\p{IsHan}]{2,4})\\s+([\\p{IsHan}][^\\s]{1,39})$").matcher(title);
        if(spaced.matches()&&(!usableArtist(artist)||artist.equals(spaced.group(1)))){
            artist=spaced.group(1);title=spaced.group(2);
        }
        if(!usableArtist(artist))artist="";
        if(title.isEmpty()&&rawTitle!=null)title=rawTitle.replace("【","").replace("】","").trim();
        return new Query(title,artist,seconds,instrumental);
    }
    private static boolean usableArtist(String value){
        if(value==null||value.trim().isEmpty()||value.length()>45)return false;
        String x=value.toLowerCase(Locale.ROOT);
        return !x.matches("[0-9_\\-]+")&&!(x.contains("未知")||x.contains("音乐")||x.contains("专辑")||x.contains("合集")
            ||x.contains("盘搜")||x.contains("歌曲")||x.equals("artist"));
    }
    private static boolean knownArtist(String value){return value.matches("周杰伦|林俊杰|邓紫棋|王菲|陈奕迅|张学友|孙燕姿|许嵩|薛之谦|张杰|李荣浩|蔡依林|刘德华|毛不易|汪苏泷|周深|五月天|陶喆|王力宏");}
    static String find(String rawTitle,String rawArtist,int seconds)throws Exception{
        Query primary=parse(rawTitle,rawArtist,seconds);
        String result=findSingle(primary);
        if(isFound(result))return result;
        java.util.regex.Matcher trailing=java.util.regex.Pattern.compile("^(.{1,80}?)\\s*[（(]([^（）()]{2,40})[）)]\\s*$").matcher(primary.title);
        if(trailing.matches()){
            String plain=trailing.group(1).trim(),hint=trailing.group(2).trim();
            if(!plain.isEmpty()){
                if(primary.artist.isEmpty()&&usableArtist(hint)&&plausibleBracketTitle(hint)){
                    String hinted=findSingle(new Query(plain,hint,seconds,primary.instrumental));
                    if(isFound(hinted))return hinted;
                }
                String stripped=findSingle(new Query(plain,primary.artist,seconds,primary.instrumental));
                if(isFound(stripped))return stripped;
                if(!stripped.startsWith("歌词服务"))result=stripped;
            }
        }
        if(!primary.artist.isEmpty()){
            String withoutArtist=findSingle(new Query(primary.title,"",seconds,primary.instrumental));
            if(isFound(withoutArtist))return withoutArtist;
            if(!withoutArtist.startsWith("歌词服务"))result=withoutArtist;
        }
        java.util.regex.Matcher pair=java.util.regex.Pattern.compile("^(.{2,30}?)\\s*[-–—]\\s*(.{2,30})$").matcher(rawTitle==null?"":rawTitle.trim());
        if(pair.matches()&&!normalize(pair.group(1)).equals(normalize(primary.title))){
            String reversed=findSingle(new Query(pair.group(1).trim(),pair.group(2).trim(),seconds,false));
            if(isFound(reversed))return reversed;
        }
        java.util.regex.Matcher bracket=java.util.regex.Pattern.compile("[\\[【]([^\\]】]{1,60})[\\]】]").matcher(rawTitle==null?"":rawTitle);
        while(bracket.find()){
            String candidate=bracket.group(1).trim();
            if(!plausibleBracketTitle(candidate)||normalize(candidate).equals(normalize(primary.title)))continue;
            String alternate=findSingle(new Query(candidate,primary.artist,seconds,false));
            if(isFound(alternate))return alternate;
        }
        return result;
    }
    private static boolean isFound(String result){return result!=null&&!result.startsWith("暂未")&&!result.startsWith("找到多首")&&!result.startsWith("歌词服务")&&!result.startsWith("歌曲名不完整");}
    static List<Candidate> candidates(String rawTitle,String rawArtist,int seconds)throws Exception{
        Query query=parse(rawTitle,rawArtist,seconds);
        List<Candidate> found=new ArrayList<>();
        if(query.title.isEmpty())return found;
        try{
            JSONArray rows=requestArray("https://lrclib.net/api/search?track_name="+enc(query.title));
            List<JSONObject> ranked=new ArrayList<>();
            for(int i=0;i<rows.length();i++){
                JSONObject row=rows.optJSONObject(i);
                if(row!=null&&hasLyrics(row)&&(normalize(row.optString("trackName")).equals(normalize(query.title))||score(query,row)>=60))ranked.add(row);
            }
            ranked.sort((a,b)->Integer.compare(score(query,b),score(query,a)));
            for(JSONObject row:ranked){
                if(found.size()>=6)break;
                String text=lyrics(row);
                if(isFound(text))found.add(new Candidate("LRCLIB · "+row.optString("trackName")+" · "+row.optString("artistName"),text));
            }
        }catch(Exception ignored){}
        if(Thread.currentThread().isInterrupted())throw new InterruptedException();
        try{found.addAll(NeteaseLyrics.candidates(query,Math.min(4,Math.max(0,8-found.size()))));}catch(Exception ignored){}
        return found;
    }
    private static boolean plausibleBracketTitle(String text){String lower=text.toLowerCase(Locale.ROOT);return text.length()>=2&&!lower.matches(".*(mp3|lrc|flac|aac|wav|mv|无损|音乐|歌曲|高清|音质|live|现场|伴奏|资源|网盘).*");}
    private static String findSingle(Query query)throws Exception{
        if(Thread.currentThread().isInterrupted())throw new InterruptedException();
        if(query.title.isEmpty())return "歌曲名不完整，无法匹配歌词";
        if(query.instrumental)return "纯音乐或伴奏 · 无歌词";
        String key=normalize(query.title)+"|"+normalize(query.artist)+"|"+query.seconds;
        String saved=CACHE.get(key);if(saved!=null)return saved;
        if(KnownMusicFallback.isJayQilixiang(query)){
            try{
                String original=KnownMusicFallback.lyrics(query);
                if(original!=null){if(CACHE.size()>200)CACHE.clear();CACHE.put(key,original);return original;}
            }catch(Exception ignored){}
            if(Thread.currentThread().isInterrupted())throw new InterruptedException();
            try{
                String original=NeteaseLyrics.find(query);
                if(original!=null){if(CACHE.size()>200)CACHE.clear();CACHE.put(key,original);return original;}
            }catch(Exception ignored){}
            return "暂未找到《七里香》原曲歌词";
        }
        String lrclibResult=null;
        boolean providerReached=false;
        if(System.currentTimeMillis()>=lrclibUnavailableUntil){
            try{lrclibResult=findLrclib(query);providerReached=true;}
            catch(Exception ignored){}
            if(Thread.currentThread().isInterrupted())throw new InterruptedException();
            if(lrclibResult!=null&&!lrclibResult.startsWith("暂未")&&!lrclibResult.startsWith("找到多首")){
                if(CACHE.size()>200)CACHE.clear();CACHE.put(key,lrclibResult);return lrclibResult;
            }
        }
        try{
            if(Thread.currentThread().isInterrupted())throw new InterruptedException();
            String alternate=NeteaseLyrics.find(query);
            providerReached=true;
            if(alternate!=null){if(CACHE.size()>200)CACHE.clear();CACHE.put(key,alternate);return alternate;}
        }catch(Exception ignored){}
        return lrclibResult==null?(providerReached?"暂未找到匹配歌词":"歌词服务暂时不可用，请稍后重试"):lrclibResult;
    }
    private static String findLrclib(Query query)throws Exception{
        if(!query.artist.isEmpty()){
            String direct="https://lrclib.net/api/get?track_name="+enc(query.title)+"&artist_name="+enc(query.artist);
            if(query.seconds>0&&query.seconds<=3600)direct+="&duration="+query.seconds;
            JSONObject record=requestObject(direct);
            if(record!=null&&score(query,record)>=90&&hasLyrics(record))return lyrics(record);
            Thread.sleep(300);
        }
        JSONArray results=requestArray("https://lrclib.net/api/search?track_name="+enc(query.title));
        JSONObject best=null;
        int bestScore=Integer.MIN_VALUE;
        Set<String> exactArtists=new HashSet<>();
        for(int i=0;i<results.length();i++){
            JSONObject item=results.optJSONObject(i);if(item==null||!hasLyrics(item))continue;
            if(normalize(item.optString("trackName")).equals(normalize(query.title)))
                exactArtists.add(normalize(item.optString("artistName")));
            int score=score(query,item);
            if(score>bestScore){best=item;bestScore=score;}
        }
        if(best==null||bestScore<90)return "暂未找到匹配歌词";
        if(query.artist.isEmpty()&&query.seconds<=0&&exactArtists.size()>1)
            return "找到多首同名歌曲，缺少歌手信息";
        return lyrics(best);
    }
    static int score(Query query,JSONObject record){
        String wanted=normalize(query.title),found=normalize(record.optString("trackName"));
        if(wanted.isEmpty()||found.isEmpty())return -1000;
        int score=wanted.equals(found)?100:
            (found.contains(wanted)||wanted.contains(found))&&Math.abs(found.length()-wanted.length())<=4?70:-100;
        String recordArtist=normalize(record.optString("artistName"));
        if(!query.artist.isEmpty()){
            String artist=normalize(query.artist);
            score+=artist.equals(recordArtist)?40:
                recordArtist.contains(artist)||artist.contains(recordArtist)?15:-60;
        }
        int duration=record.optInt("duration",0);
        if(query.seconds>0&&duration>0){
            int difference=Math.abs(query.seconds-duration);
            score+=difference<=3?20:difference<=12?8:difference>45?-35:0;
        }
        if(!record.optString("syncedLyrics").isEmpty())score+=3;
        return score;
    }
    private static boolean hasLyrics(JSONObject record){
        return record.optBoolean("instrumental")||!record.optString("plainLyrics").isEmpty()
            ||!record.optString("syncedLyrics").isEmpty();
    }
    private static String lyrics(JSONObject record){
        if(record.optBoolean("instrumental"))return "纯音乐 · 无歌词";
        String text=record.optString("syncedLyrics","");
        if(text.isEmpty()||text.equals("null"))text=record.optString("plainLyrics","");
        return text.isEmpty()||text.equals("null")?"暂未找到匹配歌词":text;
    }
    private static JSONObject requestObject(String address)throws Exception{
        String body=request(address);
        return body==null?null:new JSONObject(body);
    }
    private static JSONArray requestArray(String address)throws Exception{
        String body=request(address);
        return body==null?new JSONArray():new JSONArray(body);
    }
    private static String request(String address)throws Exception{
        HttpURLConnection connection=(HttpURLConnection)new URL(address).openConnection();
        connection.setRequestProperty("User-Agent","MusicBox/0.3.4-alpha (TVBox-based Android music player)");
        connection.setConnectTimeout(6000);
        connection.setReadTimeout(8000);
        try{
            int status=connection.getResponseCode();
            if(status==404)return null;
            if(status==429){
                lrclibUnavailableUntil=System.currentTimeMillis()+Math.max(60,retryDelay(connection,60))*1000L;
                throw new IllegalStateException("歌词服务请求频繁");
            }
            if(status==503){
                lrclibUnavailableUntil=System.currentTimeMillis()+Math.max(30,retryDelay(connection,45))*1000L;
                throw new IllegalStateException("歌词服务暂时繁忙");
            }
            if(status!=200)throw new IllegalStateException("歌词服务返回 "+status);
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
    private static String enc(String value)throws Exception{return URLEncoder.encode(value,"UTF-8");}
    private static int retryDelay(HttpURLConnection connection,int fallback){
        try{String header=connection.getHeaderField("Retry-After");return Math.max(1,Math.min(300,Integer.parseInt(header)));}
        catch(Exception ignored){return fallback;}
    }
    private static String normalize(String value){
        if(value==null)return "";
        return value.toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N}]","");
    }
}

