package com.bombit.tools;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** UTF-8 RFC-4180-style rows. One process owns each file; distributed runners use separate files. */
public final class BenchmarkCsv {
    private BenchmarkCsv(){}
    public static void append(Path path,List<String> columns,List<? extends Map<String,String>> rows)throws IOException{
        var parent=path.toAbsolutePath().getParent();if(parent!=null)Files.createDirectories(parent);
        String header=String.join(",",columns);
        boolean exists=Files.exists(path)&&Files.size(path)>0;
        if(exists)try(var reader=Files.newBufferedReader(path,StandardCharsets.UTF_8)){
            if(!header.equals(reader.readLine()))throw new IOException("CSV header differs: use a new output file");
        }
        var out=new StringBuilder();if(!exists)out.append(header).append("\r\n");
        for(var row:rows){
            for(int i=0;i<columns.size();i++){if(i>0)out.append(',');out.append(escape(row.getOrDefault(columns.get(i),"")));}
            out.append("\r\n");
        }
        Files.writeString(path,out,StandardCharsets.UTF_8,StandardOpenOption.CREATE,StandardOpenOption.APPEND);
    }
    public static String escape(String value){
        return value.indexOf(',')>=0||value.indexOf('"')>=0||value.indexOf('\n')>=0||value.indexOf('\r')>=0
                ?"\""+value.replace("\"","\"\"")+"\"":value;
    }
}
