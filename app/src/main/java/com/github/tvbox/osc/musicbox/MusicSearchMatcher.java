package com.github.tvbox.osc.musicbox;

import java.util.Locale;

/** Tolerates alternate script and partial song titles without weakening short queries. */
final class MusicSearchMatcher {
    private MusicSearchMatcher(){}

    static String alternate(String query){
        String variant=query.replace("天的","日の").replace("纸飞机","紙飛行機");
        return variant.equals(query)?"":variant;
    }

    static String canonical(String value){
        if(value==null)return "";
        String text=value.toLowerCase(Locale.ROOT).replace("紙飛行機","纸飞机")
            .replace("紙飛機","纸飞机").replace("日の","天的");
        return text.replaceAll("[^\\p{L}\\p{N}]","");
    }

    static int distance(String title,String query){
        String wanted=canonical(query),found=canonical(title);
        if(wanted.isEmpty()||found.isEmpty())return 100;
        if(found.equals(wanted))return 0;
        if(found.startsWith(wanted))return 1;
        if(found.contains(wanted))return 2;
        if(wanted.length()<4)return 100;
        java.util.regex.Matcher numbers=java.util.regex.Pattern.compile("\\d+").matcher(wanted);
        while(numbers.find())if(!found.contains(numbers.group()))return 100;
        int matched=0;
        for(int i=0;i<wanted.length();i++)if(found.indexOf(wanted.charAt(i))>=0)matched++;
        double coverage=(double)matched/wanted.length();
        return coverage>=0.80?3:coverage>=0.60?5:100;
    }
}
