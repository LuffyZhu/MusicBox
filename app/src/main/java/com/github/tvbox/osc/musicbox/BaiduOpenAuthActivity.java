package com.github.tvbox.osc.musicbox;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import com.google.zxing.BarcodeFormat;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import org.json.JSONObject;
import java.io.IOException;
import java.net.URLEncoder;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/** Device-code authorization through the MusicBox service; no developer key on the device. */
public final class BaiduOpenAuthActivity extends Activity {
    private final ExecutorService worker=Executors.newSingleThreadExecutor();
    private final Handler ui=new Handler(Looper.getMainLooper());
    private Future<?> task;
    private ImageView qr;
    private TextView status,userCode;
    private Button start,open,retryStatus;
    private String verificationUrl="";
    private volatile BaiduOpenAuth.Device currentDevice;
    private int dp(int value){return (int)(value*getResources().getDisplayMetrics().density);}
    private TextView text(String value,int size){TextView result=new TextView(this);result.setText(value);result.setTextSize(size);result.setTextColor(0xffe9eef9);return result;}
    @Override public void onCreate(Bundle state){
        super.onCreate(state);getWindow().setStatusBarColor(0xff111827);getWindow().setNavigationBarColor(0xff111827);
        ScrollView scroll=new ScrollView(this);scroll.setFillViewport(true);scroll.setBackgroundColor(0xff111827);
        LinearLayout root=new LinearLayout(this);root.setOrientation(1);root.setPadding(dp(20),dp(22),dp(20),dp(22));scroll.addView(root);setContentView(scroll);
        root.addView(text("百度开放平台授权",24));
        TextView note=text("使用百度网盘账号扫码授权。开发者密钥保存在 MusicBox 授权服务端；本机仅加密保存你的授权 Token。",14);note.setTextColor(0xffa3b1c6);note.setPadding(0,dp(12),0,dp(16));root.addView(note);
        start=new Button(this);start.setText("生成官方授权码");start.setOnClickListener(v->authorize());root.addView(start);
        qr=new ImageView(this);qr.setScaleType(ImageView.ScaleType.FIT_CENTER);LinearLayout.LayoutParams qrParams=new LinearLayout.LayoutParams(-1,dp(280));qrParams.topMargin=dp(12);root.addView(qr,qrParams);
        userCode=text("",20);userCode.setGravity(Gravity.CENTER);root.addView(userCode);
        status=text(BaiduOpenAuth.configured(this)?"已配置官方授权，播放前会自动续期":"尚未授权",15);status.setGravity(Gravity.CENTER);status.setPadding(0,dp(12),0,dp(12));root.addView(status);
        open=new Button(this);open.setText("在本机浏览器打开授权页");open.setEnabled(false);open.setOnClickListener(v->{if(!verificationUrl.isEmpty())startActivity(new Intent(Intent.ACTION_VIEW,Uri.parse(verificationUrl)));});root.addView(open);
        retryStatus=new Button(this);retryStatus.setText("重新查询授权状态");retryStatus.setEnabled(false);retryStatus.setOnClickListener(v->retryPolling());root.addView(retryStatus);
        Button clear=new Button(this);clear.setText("清除官方授权");clear.setOnClickListener(v->new AlertDialog.Builder(this).setMessage("清除本机保存的百度网盘授权 Token？").setPositiveButton("清除",(dialog,which)->{if(task!=null)task.cancel(true);currentDevice=null;BaiduOpenAuth.clear(this);verificationUrl="";qr.setImageDrawable(null);userCode.setText("");open.setEnabled(false);retryStatus.setEnabled(false);start.setEnabled(true);status.setText("官方授权已清除");}).setNegativeButton("取消",null).show());root.addView(clear);
    }
    private void message(String value){ui.post(()->{if(!isFinishing()&&!isDestroyed())status.setText(value);});}
    private void authorize(){
        if(task!=null)task.cancel(true);
        currentDevice=null;
        start.setEnabled(false);qr.setImageDrawable(null);userCode.setText("");open.setEnabled(false);retryStatus.setEnabled(false);status.setText("正在生成官方授权码…");
        task=worker.submit(()->{
            try{
                BaiduOpenAuth.Device device=BaiduOpenAuth.startServer();
                if(Thread.currentThread().isInterrupted())return;
                if(device.code.isEmpty()||device.verificationUrl.isEmpty())throw new IllegalStateException("百度未返回有效授权码");
                String url=device.verificationUrl+(device.verificationUrl.contains("?")?"&":"?")+"code="+URLEncoder.encode(device.userCode,"UTF-8");
                Bitmap image=makeQr(url);
                currentDevice=device;
                ui.post(()->{if(isFinishing()||isDestroyed()||currentDevice!=device)return;qr.setImageBitmap(image);userCode.setText("授权码："+device.userCode);verificationUrl=url;open.setEnabled(true);retryStatus.setEnabled(true);status.setText("请扫码或在本机浏览器授权，完成后会自动查询状态");});
                pollUntilFinished(device);
            }catch(InterruptedException ignored){Thread.currentThread().interrupt();}
            catch(Exception error){message("授权未完成："+error.getMessage());}
            finally{ui.post(()->{if(!isFinishing()&&!isDestroyed())start.setEnabled(true);});}
        });
    }
    private void retryPolling(){
        BaiduOpenAuth.Device device=currentDevice;
        if(device==null||System.currentTimeMillis()>=device.expiresAt){status.setText("授权码已过期，请重新生成");return;}
        if(task!=null)task.cancel(true);
        status.setText("正在重新查询当前授权码…");
        task=worker.submit(()->{
            try{pollUntilFinished(device);}
            catch(InterruptedException ignored){Thread.currentThread().interrupt();}
            catch(Exception error){message("查询授权状态失败："+error.getMessage());}
        });
    }
    private void pollUntilFinished(BaiduOpenAuth.Device device)throws Exception{
        int waitSeconds=device.interval;boolean networkFailed=false;
        while(!Thread.currentThread().isInterrupted()&&System.currentTimeMillis()<device.expiresAt){
            Thread.sleep(waitSeconds*1000L);
            JSONObject result;
            try{result=BaiduOpenAuth.pollServer(device);}
            catch(IOException network){
                if(Thread.currentThread().isInterrupted()||currentDevice!=device)return;
                networkFailed=true;
                message("暂时无法连接百度授权服务，正在重试。请检查手机网络或私人 DNS；当前授权码仍有效。");
                continue;
            }
            if(Thread.currentThread().isInterrupted()||currentDevice!=device)return;
            networkFailed=false;
            if(BaiduOpenAuth.pending(result)){
                if("slow_down".equals(result.optString("error")))waitSeconds+=5;
                else message("等待百度确认授权…");
                continue;
            }
            if(result.optString("access_token").isEmpty())throw BaiduOpenAuth.failure(result);
            BaiduOpenAuth.saveServer(this,result);
            ui.post(()->{if(!isFinishing()&&!isDestroyed()&&currentDevice==device){status.setText("授权成功；搜索和播放将使用官方接口，Token 到期后自动续期");retryStatus.setEnabled(false);}});
            return;
        }
        if(!Thread.currentThread().isInterrupted()&&currentDevice==device)
            message(networkFailed?"授权码已过期，期间无法连接百度授权服务；请检查网络后重新生成":"授权码已过期，请重新生成");
    }
    private static Bitmap makeQr(String value)throws Exception{
        BitMatrix matrix=new QRCodeWriter().encode(value,BarcodeFormat.QR_CODE,720,720);
        Bitmap image=Bitmap.createBitmap(720,720,Bitmap.Config.ARGB_8888);
        for(int y=0;y<720;y++)for(int x=0;x<720;x++)image.setPixel(x,y,matrix.get(x,y)?Color.BLACK:Color.WHITE);
        return image;
    }
    @Override protected void onDestroy(){if(task!=null)task.cancel(true);worker.shutdownNow();super.onDestroy();}
}
