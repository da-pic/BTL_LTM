package com.bombit.common.protocol;

import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.regex.Pattern;

/** JSON v3 có giới hạn byte/depth/count; parser legacy giữ nguyên cho fixture v2. */
public final class JsonV3 {
    private static final Pattern NUMBER=Pattern.compile("-?(?:0|[1-9][0-9]*)(?:\\.[0-9]+)?(?:[eE][+-]?[0-9]+)?");
    private final String text; private int pos;
    private JsonV3(String text,int limit){
        if(text.getBytes(StandardCharsets.UTF_8).length>limit)throw new ProtocolRejection("PAYLOAD_TOO_LARGE");
        this.text=text;
    }
    public static Map<String,Object> object(String text,int limit){
        var parser=new JsonV3(text,limit);var value=parser.value(0);parser.space();
        if(parser.pos!=text.length())throw new IllegalArgumentException("Trailing JSON");return map(value);
    }
    @SuppressWarnings("unchecked") public static Map<String,Object> map(Object value){
        if(!(value instanceof Map<?,?>))throw new IllegalArgumentException("Expected object");return (Map<String,Object>)value;
    }
    public static Map<String,Object> fields(Object value,String... names){
        var map=map(value);if(!map.keySet().equals(Set.of(names)))throw new IllegalArgumentException("Invalid fields");return map;
    }
    public static List<?> list(Object value,int max){
        if(!(value instanceof List<?> list)||list.size()>max)throw new IllegalArgumentException("Invalid list");return list;
    }
    public static String text(Map<String,Object> map,String key,int max){
        if(!(map.get(key) instanceof String s)||s.isBlank()||s.codePointCount(0,s.length())>max
                ||s.codePoints().anyMatch(Character::isISOControl))throw new IllegalArgumentException("Invalid "+key);return s;
    }
    public static String uuid(Map<String,Object> map,String key){
        String s=text(map,key,36);if(!UUID.fromString(s).toString().equals(s))throw new IllegalArgumentException("Noncanonical UUID");return s;
    }
    public static long integer(Map<String,Object> map,String key,long min,long max){
        if(!(map.get(key) instanceof Long n)||n<min||n>max)throw new IllegalArgumentException("Invalid "+key);return n;
    }
    public static double number(Map<String,Object> map,String key){
        if(!(map.get(key) instanceof Number n)||!Double.isFinite(n.doubleValue()))throw new IllegalArgumentException("Invalid "+key);return n.doubleValue();
    }
    public static boolean bool(Map<String,Object> map,String key){
        if(!(map.get(key) instanceof Boolean b))throw new IllegalArgumentException("Invalid "+key);return b;
    }
    public static Packet packet(short type,Object body){return new Packet(type,encode(body));}
    public static String encode(Object value){
        if(value==null)return "null";
        if(value instanceof String s)return SessionPacketCodec.quote(s);
        if(value instanceof Boolean||value instanceof Integer||value instanceof Long)return value.toString();
        if(value instanceof Double d&&Double.isFinite(d))return d.toString();
        if(value instanceof Map<?,?> map){var parts=new ArrayList<String>();map.forEach((k,v)->parts.add(encode((String)k)+":"+encode(v)));return "{"+String.join(",",parts)+"}";}
        if(value instanceof Collection<?> list)return "["+String.join(",",list.stream().map(JsonV3::encode).toList())+"]";
        throw new IllegalArgumentException("Unsupported JSON value");
    }
    public static LinkedHashMap<String,Object> body(Object... pairs){
        var result=new LinkedHashMap<String,Object>();for(int i=0;i<pairs.length;i+=2)result.put((String)pairs[i],pairs[i+1]);return result;
    }
    private void space(){while(pos<text.length()&&" \t\r\n".indexOf(text.charAt(pos))>=0)pos++;}
    private char take(){if(pos==text.length())throw new IllegalArgumentException("Truncated JSON");return text.charAt(pos++);}
    private Object value(int depth){
        if(depth>16)throw new IllegalArgumentException("JSON too deep");space();if(pos==text.length())throw new IllegalArgumentException("Missing value");
        char c=text.charAt(pos);if(c=='"')return string();
        if(c=='{'){
            pos++;space();var map=new LinkedHashMap<String,Object>();if(pos<text.length()&&text.charAt(pos)=='}'){pos++;return map;}
            while(true){space();String key=string();space();if(take()!=':')throw new IllegalArgumentException("Missing colon");
                if(map.containsKey(key)||map.size()>=128)throw new IllegalArgumentException("Duplicate key/count");
                map.put(key,value(depth+1));space();char separator=take();if(separator=='}')return map;if(separator!=',')throw new IllegalArgumentException("Bad separator");}
        }
        if(c=='['){pos++;space();var list=new ArrayList<Object>();if(pos<text.length()&&text.charAt(pos)==']'){pos++;return list;}
            while(true){if(list.size()>=4096)throw new IllegalArgumentException("Array count");list.add(value(depth+1));space();char separator=take();if(separator==']')return list;if(separator!=',')throw new IllegalArgumentException("Bad separator");}}
        for(String literal:List.of("true","false","null"))if(text.startsWith(literal,pos)){pos+=literal.length();return literal.equals("null")?null:Boolean.valueOf(literal);}
        var matcher=NUMBER.matcher(text).region(pos,text.length());if(!matcher.lookingAt())throw new IllegalArgumentException("Invalid number");
        String raw=matcher.group();pos=matcher.end();
        if(raw.indexOf('.')<0&&raw.indexOf('e')<0&&raw.indexOf('E')<0)return Long.valueOf(raw);
        double n=Double.parseDouble(raw);if(!Double.isFinite(n))throw new IllegalArgumentException("Nonfinite number");return n;
    }
    private String string(){
        if(take()!='"')throw new IllegalArgumentException("Expected string");var out=new StringBuilder();boolean ended=false;
        while(pos<text.length()){char c=take();if(c=='"'){ended=true;break;}if(c<32)throw new IllegalArgumentException("Control character");
            if(c=='\\'){c=take();c=switch(c){case '"','\\','/'->c;case 'b'->'\b';case 'f'->'\f';case 'n'->'\n';case 'r'->'\r';case 't'->'\t';case 'u'->{
                if(pos+4>text.length())throw new IllegalArgumentException("Bad escape");String hex=text.substring(pos,pos+4);
                if(!hex.matches("[0-9a-fA-F]{4}"))throw new IllegalArgumentException("Bad escape");pos+=4;yield (char)Integer.parseInt(hex,16);
            }default->throw new IllegalArgumentException("Bad escape");};}out.append(c);}
        if(!ended)throw new IllegalArgumentException("Unterminated string");
        for(int i=0;i<out.length();i++){char c=out.charAt(i);if(Character.isHighSurrogate(c)){if(++i>=out.length()||!Character.isLowSurrogate(out.charAt(i)))throw new IllegalArgumentException("Bad surrogate");}else if(Character.isLowSurrogate(c))throw new IllegalArgumentException("Bad surrogate");}
        return out.toString();
    }
}
