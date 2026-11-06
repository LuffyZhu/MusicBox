package com.github.tvbox.osc.musicbox;

import android.content.Context;
import android.content.SharedPreferences;
import com.github.tvbox.osc.bean.SourceBean;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.CompletionService;
import java.util.concurrent.ExecutorCompletionService;

/** Searches the owner's drive before any subscription search. Links are resolved at play time. */
final class PersonalCloud {
    static final String BAIDU="musicbox_personal_baidu", QUARK="musicbox_personal_quark";
    private static final ThreadLocal<String> warning=ThreadLocal.withInitial(()->"");
    static String warning(){return warning.get();}
    private static final String QUARK_API="https://drive-pc.quark.cn/1/clouddrive";
    private static final String QUARK_UA="Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/120.0.0.0 Safari/537.36";
    private PersonalCloud() {}
    static SharedPreferences prefs(Context context){return context.getSharedPreferences("musicbox_cloud",Context.MODE_PRIVATE);}
    static boolean owns(MusicSourceBridge.Song song){return BAIDU.equals(song.source.getKey())||QUARK.equals(song.source.getKey());}
    static SourceBean source(String key){SourceBean s=new SourceBean();s.setKey(key);s.setName(BAIDU.equals(key)?"我的百度网盘":"我的夸克网盘");return s;}
    interface ResultListener{void onResult(List<MusicSourceBridge.Song> songs);}
    private static final class CloudBatch{
        final String name;final List<MusicSourceBridge.Song> songs;final Exception error;
        CloudBatch(String name,List<MusicSourceBridge.Song> songs,Exception error){this.name=name;this.songs=songs;this.error=error;}
    }
    static List<MusicSourceBridge.Song> search(Context context,String keyword)throws Exception{return search(context,keyword,null);}
    static List<MusicSourceBridge.Song> search(Context context,String keyword,ResultListener listener)throws Exception{
        if(Thread.currentThread().isInterrupted())throw new InterruptedException();
        warning.set("");SharedPreferences p=prefs(context);List<MusicSourceBridge.Song> result=new ArrayList<>();Exception last=null;
        String token=p.getString("baidu_token","").trim(),baiduCookie=p.getString("baidu_cookie","").trim();
        boolean officialConfigured=BaiduOpenAuth.configured(context);
        String quarkCookie=p.getString("quark_cookie","").trim();
        if(token.isEmpty()&&!officialConfigured&&baiduCookie.isEmpty()&&quarkCookie.isEmpty())return result;
        ExecutorService pool=Executors.newFixedThreadPool(2);
        CompletionService<CloudBatch> completed=new ExecutorCompletionService<>(pool);
        List<Future<CloudBatch>> tasks=new ArrayList<>();
        try{
            if(!token.isEmpty()||officialConfigured||!baiduCookie.isEmpty())tasks.add(completed.submit(()->{
                List<MusicSourceBridge.Song> found=new ArrayList<>();
                String currentToken=token;
                try{currentToken=BaiduOpenAuth.accessToken(context);searchBaidu(keyword,currentToken,baiduCookie,found);}
                catch(Exception first){
                    if(Thread.currentThread().isInterrupted())return new CloudBatch("百度",found,first);
                    if(baiduCookie.isEmpty())return new CloudBatch("百度",found,first);
                    try{found.clear();searchBaidu(keyword,"",baiduCookie,found);}
                    catch(Exception fallback){return new CloudBatch("百度",found,fallback);}
                }
                return new CloudBatch("百度",found,null);
            }));
            if(!quarkCookie.isEmpty())tasks.add(completed.submit(()->{
                List<MusicSourceBridge.Song> found=new ArrayList<>();
                try{searchQuark(keyword,quarkCookie,found);return new CloudBatch("夸克",found,null);}
                catch(Exception error){return new CloudBatch("夸克",found,error);}
            }));
            long deadline=System.nanoTime()+14_000_000_000L;
            int done=0;
            while(done<tasks.size()&&System.nanoTime()<deadline){
                Future<CloudBatch> future=completed.poll(Math.max(1,deadline-System.nanoTime()),TimeUnit.NANOSECONDS);
                if(future==null)break;
                CloudBatch batch=future.get();done++;
                if(batch.error!=null){last=batch.error;warning.set(warning.get()+(warning.get().isEmpty()?"":" · ")+batch.name+"网盘搜索失败");}
                else{result.addAll(batch.songs);if(listener!=null&&!batch.songs.isEmpty())listener.onResult(batch.songs);}
            }
            if(done<tasks.size())warning.set(warning.get()+(warning.get().isEmpty()?"":" · ")+"部分网盘搜索超时");
        }catch(InterruptedException cancelled){Thread.currentThread().interrupt();throw cancelled;
        }finally{
            for(Future<CloudBatch> task:tasks)task.cancel(true);
            pool.shutdownNow();
        }
        if(result.isEmpty()&&last!=null)throw last;
        return result;
    }
    private static void searchBaidu(String key,String token,String cookie,List<MusicSourceBridge.Song> out)throws Exception{
        boolean official=!token.isEmpty();String endpoint=official?
            "https://pan.baidu.com/rest/2.0/xpan/file?method=search&recursion=1&access_token="+enc(token)+"&key="+enc(key):
            "https://pan.baidu.com/api/search?dir=%2F&recursion=1&num=100&page=1&key="+enc(key);
        Map<String,String> headers=new HashMap<>();if(!cookie.isEmpty())headers.put("Cookie",cookie);
        headers.put("User-Agent",QUARK_UA);headers.put("Referer","https://pan.baidu.com/");
        JSONObject data=request(endpoint,null,headers,"GET");
        if(data.optInt("errno",0)!=0)throw new IllegalStateException("百度网盘搜索返回 "+data.optInt("errno"));
        JSONArray files=data.optJSONArray("list");if(files==null)return;
        SourceBean source=source(BAIDU);
        for(int i=0;i<files.length()&&out.size()<60;i++){
            if(Thread.currentThread().isInterrupted())throw new InterruptedException();
            JSONObject file=files.optJSONObject(i);if(file==null||file.optInt("isdir",0)!=0)continue;
            String name=file.optString("server_filename",file.optString("path"));
            if(!audio(name)||!matches(name,key))continue;
            String id=file.optString("fs_id");if(id.isEmpty())continue;
            LyricMatcher.Query parsed=LyricMatcher.parse(name,"",0);
            out.add(new MusicSourceBridge.Song(source,id,parsed.title,parsed.artist,official?"official":"web",file.optString("path"),false,""));
        }
    }
    private static void searchQuark(String key,String cookie,List<MusicSourceBridge.Song> out)throws Exception{
        SourceBean source=source(QUARK);
        Map<String,String> headers=quarkHeaders(cookie);
        for(int page=1;page<=3&&out.size()<60;page++){
            if(Thread.currentThread().isInterrupted())throw new InterruptedException();
            String url=QUARK_API+"/file/search?pr=ucpro&fr=pc&q="+enc(key)+"&_page="+page+"&_size=100&_fetch_total=1&_sort="+enc("file_type:desc,updated_at:desc")+"&_is_hl=1";
            JSONObject data=request(url,null,headers,"GET");checkQuark(data);
            JSONObject payload=data.optJSONObject("data");JSONArray files=payload==null?null:payload.optJSONArray("list");if(files==null)break;
            for(int i=0;i<files.length()&&out.size()<60;i++){
                if(Thread.currentThread().isInterrupted())throw new InterruptedException();
                JSONObject file=files.optJSONObject(i);if(file==null)continue;
                String id=file.optString("fid"),name=file.optString("file_name").replaceAll("<[^>]+>","");
                if(!audio(name)||!matches(name,key)||id.isEmpty())continue;
                LyricMatcher.Query parsed=LyricMatcher.parse(name,"",0);
                out.add(new MusicSourceBridge.Song(source,id,parsed.title,parsed.artist,"","",false,""));
            }
            if(files.length()<100)break;
        }
    }
    static MusicSourceBridge.PlaybackInfo resolve(Context context,MusicSourceBridge.Song song)throws Exception{
        SharedPreferences p=prefs(context);Map<String,String> headers=new HashMap<>();String url;
        if(BAIDU.equals(song.source.getKey())){
            String token=BaiduOpenAuth.accessToken(context),cookie=p.getString("baidu_cookie","").trim();
            if(!cookie.isEmpty())headers.put("Cookie",cookie);
            if(!token.isEmpty()){
                String endpoint="https://pan.baidu.com/rest/2.0/xpan/multimedia?method=filemetas&openapi=xpansdk&dlink=1&fsids="+enc("["+song.id+"]")+"&access_token="+enc(token);
                JSONObject data=request(endpoint,null,headers,"GET");JSONArray files=data.optJSONArray("list");
                if(data.optInt("errno",0)!=0)throw new IllegalStateException("百度开放平台链接返回 "+data.optInt("errno"));
                url=files==null||files.length()==0?"":files.optJSONObject(0).optString("dlink");
                if(!url.isEmpty())url+=(url.contains("?")?"&":"?")+"access_token="+enc(token);
                headers.put("User-Agent","pan.baidu.com");
            }else{
                if(song.playId.isEmpty())throw new IllegalStateException("网盘文件缺少路径，请重新搜索");
                String endpoint="https://pan.baidu.com/api/filemetas?dlink=1&web=5&origin=dlna&target="+enc(new JSONArray().put(song.playId).toString());
                JSONObject data=request(endpoint,null,headers,"GET");
                if(data.optInt("errno",0)!=0)throw new IllegalStateException("百度网盘链接返回 "+data.optInt("errno"));
                JSONArray files=data.optJSONArray("info");url=files==null||files.length()==0?"":files.optJSONObject(0).optString("dlink");
                headers.put("User-Agent",QUARK_UA);headers.put("Referer","https://pan.baidu.com/");
            }
        }else if(QUARK.equals(song.source.getKey())){
            String cookie=p.getString("quark_cookie","").trim();if(cookie.isEmpty())throw new IllegalStateException("请先配置夸克 Cookie");
            headers=quarkHeaders(cookie);JSONObject body=new JSONObject();JSONArray ids=new JSONArray();ids.put(song.id);body.put("fids",ids);
            JSONObject data=request(QUARK_API+"/file/download?pr=ucpro&fr=pc",body,headers,"POST",context);checkQuark(data);
            if(!headers.getOrDefault("Cookie","").contains("__puus="))
                throw new IllegalStateException("夸克 Cookie 缺少下载所需的 __puus，请重新配置完整 Cookie");
            JSONArray links=data.optJSONArray("data");url=links==null||links.length()==0?"":links.optJSONObject(0).optString("download_url");
        }else throw new IllegalArgumentException("未知个人网盘");
        if(!url.startsWith("http"))throw new IllegalStateException("网盘没有返回可播放直链");
        return new MusicSourceBridge.PlaybackInfo(url,headers);
    }
    private static Map<String,String> quarkHeaders(String cookie){Map<String,String> h=new HashMap<>();h.put("Cookie",cookie);h.put("Referer","https://pan.quark.cn/");h.put("User-Agent",QUARK_UA);h.put("Accept","application/json, text/plain, */*");return h;}
    private static void checkQuark(JSONObject data){if(data.optInt("code",0)!=0)throw new IllegalStateException("夸克网盘："+data.optString("message","接口不可用"));}
    private static boolean audio(String name){return name.toLowerCase(Locale.ROOT).matches(".*\\.(mp3|flac|m4a|aac|wav|ogg|opus|ape|wma)$");}
    private static boolean matches(String name,String query){return MusicSearchMatcher.distance(name,query)<100;}
    private static String enc(String value)throws Exception{return URLEncoder.encode(value,"UTF-8");}
    private static JSONObject request(String url,JSONObject body,Map<String,String> headers,String method)throws Exception{
        return request(url,body,headers,method,null);
    }
    private static JSONObject request(String url,JSONObject body,Map<String,String> headers,String method,Context context)throws Exception{
        if(Thread.currentThread().isInterrupted())throw new InterruptedException();
        HttpURLConnection conn=(HttpURLConnection)new URL(url).openConnection();conn.setConnectTimeout(4500);conn.setReadTimeout(6000);conn.setRequestMethod(method);
        for(Map.Entry<String,String> h:headers.entrySet())conn.setRequestProperty(h.getKey(),h.getValue());
        if(body!=null){conn.setDoOutput(true);conn.setRequestProperty("Content-Type","application/json");try(OutputStream stream=conn.getOutputStream()){stream.write(body.toString().getBytes("UTF-8"));}}
        try{int code=conn.getResponseCode();if(code!=200)throw new IllegalStateException("网盘接口 HTTP "+code);
            if(context!=null&&url.startsWith(QUARK_API))refreshQuarkCookie(context,headers,conn);
            ByteArrayOutputStream bytes=new ByteArrayOutputStream();try(InputStream stream=conn.getInputStream()){byte[] buffer=new byte[8192];int n;while((n=stream.read(buffer))!=-1){if(Thread.currentThread().isInterrupted())throw new InterruptedException();if(bytes.size()+n>2*1024*1024)throw new IllegalStateException("网盘响应过大");bytes.write(buffer,0,n);}}return new JSONObject(bytes.toString("UTF-8"));}finally{conn.disconnect();}
    }
    private static void refreshQuarkCookie(Context context,Map<String,String> headers,HttpURLConnection conn){
        String refreshed="";
        for(Map.Entry<String,List<String>> entry:conn.getHeaderFields().entrySet()){
            if(entry.getKey()==null||!"Set-Cookie".equalsIgnoreCase(entry.getKey()))continue;
            for(String value:entry.getValue()){
                String pair=value.split(";",2)[0].trim();
                if(pair.startsWith("__puus=")&&pair.length()>8)refreshed=pair;
            }
        }
        if(refreshed.isEmpty())return;
        String old=headers.getOrDefault("Cookie","");
        StringBuilder updated=new StringBuilder();
        for(String piece:old.split(";")){
            String pair=piece.trim();
            if(!pair.isEmpty()&&!pair.startsWith("__puus="))updated.append(pair).append("; ");
        }
        updated.append(refreshed);
        headers.put("Cookie",updated.toString());
        prefs(context).edit().putString("quark_cookie",updated.toString()).apply();
    }
}
