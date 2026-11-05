package com.github.tvbox.osc.musicbox;

import android.content.Context;
import android.graphics.BitmapFactory;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/** Reads a music file's own artwork before consulting online artwork services. */
final class EmbeddedArtwork {
    private static final int MAX_BYTES=5*1024*1024;
    private static final long MAX_AGE=7L*24*60*60*1000;
    private EmbeddedArtwork() {}

    static String find(Context context,MusicSourceBridge.Song song,MusicSourceBridge.PlaybackInfo info) {
        if(Thread.currentThread().isInterrupted()||info==null||info.url==null)return "";
        try {
            File directory=new File(context.getCacheDir(),"musicbox_covers");
            if(!directory.exists()&&!directory.mkdirs())return "";
            String identity=song.source.getKey()+"\n"+song.id+"\n"+song.title+"\n"+song.artist;
            byte[] hash=MessageDigest.getInstance("SHA-256").digest(identity.getBytes(StandardCharsets.UTF_8));
            StringBuilder name=new StringBuilder();for(byte b:hash)name.append(String.format(java.util.Locale.ROOT,"%02x",b&0xff));
            File cached=new File(directory,name+".img");
            if(cached.isFile()&&cached.length()>128&&cached.length()<=MAX_BYTES&&System.currentTimeMillis()-cached.lastModified()<MAX_AGE)
                return Uri.fromFile(cached).toString();
            MediaMetadataRetriever retriever=new MediaMetadataRetriever();
            byte[] picture;
            try {
                retriever.setDataSource(info.url,info.headers);
                if(Thread.currentThread().isInterrupted())return "";
                picture=retriever.getEmbeddedPicture();
            } finally {retriever.release();}
            if(picture==null||picture.length<128||picture.length>MAX_BYTES||Thread.currentThread().isInterrupted())return "";
            BitmapFactory.Options options=new BitmapFactory.Options();options.inJustDecodeBounds=true;
            BitmapFactory.decodeByteArray(picture,0,picture.length,options);
            if(options.outWidth<=0||options.outHeight<=0)return "";
            File temporary=new File(directory,name+".tmp");
            try {
                try(FileOutputStream output=new FileOutputStream(temporary)){output.write(picture);}
                if(Thread.currentThread().isInterrupted())return "";
                if(cached.exists()&&!cached.delete())return "";
                if(!temporary.renameTo(cached))return "";
                return Uri.fromFile(cached).toString();
            } finally {if(temporary.exists())temporary.delete();}
        } catch(Throwable ignored) {return "";}
    }
}
