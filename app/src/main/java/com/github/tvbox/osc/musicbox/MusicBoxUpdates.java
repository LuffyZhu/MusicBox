package com.github.tvbox.osc.musicbox;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.ProgressDialog;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;
import android.widget.EditText;
import androidx.core.content.FileProvider;
import com.github.tvbox.osc.BuildConfig;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** HTTPS update manifest, verified APK download, and user-approved system installation. */
final class MusicBoxUpdates {
    private static final ExecutorService WORKER=Executors.newSingleThreadExecutor();
    private static final String PREFS="musicbox_updates",URL_KEY="manifest_url";
    private static final String DEFAULT_URL="https://raw.githubusercontent.com/LuffyZhu/MusicBox/main/update.json";
    private static final long MAX_APK=200L*1024*1024;
    private static final int REQUEST_INSTALL_SOURCE=7412;
    private static File pendingApk;
    private MusicBoxUpdates(){}

    static void show(Activity activity){
        EditText field=new EditText(activity);
        field.setSingleLine(true);
        field.setText(activity.getSharedPreferences(PREFS,0).getString(URL_KEY,DEFAULT_URL));
        field.setHint("https://…/musicbox/update.json");
        field.setPadding(28,20,28,20);
        new AlertDialog.Builder(activity).setTitle("在线升级")
            .setMessage("填写公开的 HTTPS 更新清单地址。APK 可以存放在 GitHub Releases 或其他 HTTPS 下载站。")
            .setView(field)
            .setPositiveButton("检查更新",(dialog,which)->{
                String url=field.getText().toString().trim();
                if(!https(url)){message(activity,"请输入 HTTPS 更新清单地址");return;}
                activity.getSharedPreferences(PREFS,0).edit().putString(URL_KEY,url).apply();
                check(activity,url);
            })
            .setNeutralButton("保存地址",(dialog,which)->{
                String url=field.getText().toString().trim();
                if(!https(url)){message(activity,"请输入 HTTPS 更新清单地址");return;}
                activity.getSharedPreferences(PREFS,0).edit().putString(URL_KEY,url).apply();
                message(activity,"更新地址已保存");
            }).setNegativeButton("取消",null).show();
    }

    private static void check(Activity activity,String manifestUrl){
        ProgressDialog progress=ProgressDialog.show(activity,"在线升级","正在检查更新…",true,false);
        WORKER.execute(()->{
            try{
                JSONObject json=new JSONObject(new String(readManifest(manifestUrl),java.nio.charset.StandardCharsets.UTF_8));
                int code=json.getInt("versionCode");
                String name=json.getString("versionName"),apkUrl=json.getString("apkUrl");
                String sha=json.getString("sha256").toLowerCase(Locale.ROOT);
                long size=json.getLong("sizeBytes");
                String notes=json.optString("notes","");
                if(code<1||name.trim().isEmpty()||!https(apkUrl)||!sha.matches("[0-9a-f]{64}")||size<1||size>MAX_APK)
                    throw new IllegalArgumentException("更新清单内容无效");
                activity.runOnUiThread(()->{
                    progress.dismiss();if(activity.isFinishing()||activity.isDestroyed())return;
                    if(code<=BuildConfig.VERSION_CODE){message(activity,"当前已是最新版本（"+BuildConfig.VERSION_NAME+"）");return;}
                    new AlertDialog.Builder(activity).setTitle("发现新版本 "+name)
                        .setMessage(notes+"\n\n安装包 "+String.format(Locale.ROOT,"%.1f",size/1048576.0)+" MB。下载后由 Android 确认覆盖安装。")
                        .setPositiveButton("下载更新",(dialog,which)->download(activity,code,apkUrl,sha,size))
                        .setNegativeButton("稍后",null).show();
                });
            }catch(Exception error){activity.runOnUiThread(()->{progress.dismiss();if(!activity.isFinishing()&&!activity.isDestroyed())message(activity,"检查更新失败："+brief(error));});}
        });
    }

    private static void download(Activity activity,int version,String url,String expected,long size){
        ProgressDialog progress=new ProgressDialog(activity);
        progress.setTitle("下载 MusicBox 更新");progress.setProgressStyle(ProgressDialog.STYLE_HORIZONTAL);
        progress.setMax(100);progress.setCancelable(false);progress.show();
        WORKER.execute(()->{
            File folder=new File(activity.getCacheDir(),"updates");folder.mkdirs();
            File part=new File(folder,"MusicBox-update.part.apk"),apk=new File(folder,"MusicBox-update.apk");
            try{
                HttpURLConnection connection=open(url);
                long received=0;
                MessageDigest digest=MessageDigest.getInstance("SHA-256");
                try(InputStream in=connection.getInputStream();FileOutputStream out=new FileOutputStream(part)){
                    byte[] buffer=new byte[32768];int count;
                    while((count=in.read(buffer))!=-1){
                        received+=count;if(received>MAX_APK||received>size)throw new IllegalArgumentException("安装包大小与清单不符");
                        out.write(buffer,0,count);digest.update(buffer,0,count);
                        int percent=(int)(received*100/size);activity.runOnUiThread(()->progress.setProgress(percent));
                    }
                }finally{connection.disconnect();}
                if(received!=size||!hex(digest.digest()).equals(expected))throw new IllegalArgumentException("安装包校验失败");
                PackageInfo info=activity.getPackageManager().getPackageArchiveInfo(part.getAbsolutePath(),0);
                if(info==null||!activity.getPackageName().equals(info.packageName)||versionCode(info)!=version)
                    throw new IllegalArgumentException("安装包的应用或版本与清单不符");
                if(apk.exists()&&!apk.delete())throw new IllegalStateException("无法替换旧下载文件");
                if(!part.renameTo(apk))throw new IllegalStateException("无法保存下载文件");
                activity.runOnUiThread(()->{
                    progress.dismiss();if(activity.isFinishing()||activity.isDestroyed())return;
                    pendingApk=apk;installReady(activity);
                });
            }catch(Exception error){part.delete();activity.runOnUiThread(()->{progress.dismiss();if(!activity.isFinishing()&&!activity.isDestroyed())message(activity,"下载失败："+brief(error));});}
        });
    }

    static void installReady(Activity activity){
        File apk=pendingApk;
        if(apk==null||!apk.isFile())return;
        if(Build.VERSION.SDK_INT>=26&&!activity.getPackageManager().canRequestPackageInstalls()){
            new AlertDialog.Builder(activity).setTitle("允许安装更新")
                .setMessage("Android 需要先允许 MusicBox 安装下载的更新包。开启后返回应用即可继续。")
                .setPositiveButton("打开设置",(dialog,which)->activity.startActivityForResult(
                    new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,Uri.parse("package:"+activity.getPackageName())),REQUEST_INSTALL_SOURCE))
                .setNegativeButton("取消",null).show();
            return;
        }
        Uri uri=FileProvider.getUriForFile(activity,activity.getPackageName()+".fileprovider",apk);
        Intent intent=new Intent(Intent.ACTION_VIEW).setDataAndType(uri,"application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION|Intent.FLAG_ACTIVITY_NEW_TASK);
        activity.startActivity(intent);
        pendingApk=null;
    }
    static boolean isInstallSourceRequest(int code){return code==REQUEST_INSTALL_SOURCE;}
    private static byte[] readManifest(String url)throws Exception{
        HttpURLConnection connection=open(url);
        try(InputStream in=connection.getInputStream();ByteArrayOutputStream out=new ByteArrayOutputStream()){
            byte[] buffer=new byte[4096];int n;
            while((n=in.read(buffer))!=-1){if(out.size()+n>65536)throw new IllegalArgumentException("更新清单过大");out.write(buffer,0,n);}
            return out.toByteArray();
        }finally{connection.disconnect();}
    }
    private static HttpURLConnection open(String address)throws Exception{
        for(int redirects=0;redirects<5;redirects++){
            if(!https(address))throw new IllegalArgumentException("更新链接必须使用 HTTPS");
            HttpURLConnection connection=(HttpURLConnection)new URL(address).openConnection();
            connection.setConnectTimeout(12000);connection.setReadTimeout(30000);
            connection.setInstanceFollowRedirects(false);
            connection.setRequestProperty("User-Agent","MusicBox/"+BuildConfig.VERSION_NAME);
            int response=connection.getResponseCode();
            if(response==301||response==302||response==303||response==307||response==308){
                String location=connection.getHeaderField("Location");
                if(location==null)throw new IllegalStateException("下载跳转缺少地址");
                address=new URL(new URL(address),location).toString();connection.disconnect();continue;
            }
            if(response!=200){connection.disconnect();throw new IllegalStateException("服务器返回 HTTP "+response);}
            return connection;
        }
        throw new IllegalStateException("下载跳转次数过多");
    }
    private static int versionCode(PackageInfo info){return Build.VERSION.SDK_INT>=28?(int)info.getLongVersionCode():info.versionCode;}
    private static boolean https(String address){try{return "https".equalsIgnoreCase(new URL(address).getProtocol());}catch(Exception error){return false;}}
    private static String hex(byte[] bytes){StringBuilder result=new StringBuilder();for(byte value:bytes)result.append(String.format(Locale.ROOT,"%02x",value&255));return result.toString();}
    private static String brief(Exception error){String value=error.getMessage();return value==null||value.isEmpty()?error.getClass().getSimpleName():value;}
    private static void message(Activity activity,String value){new AlertDialog.Builder(activity).setMessage(value).setPositiveButton("知道了",null).show();}
}
