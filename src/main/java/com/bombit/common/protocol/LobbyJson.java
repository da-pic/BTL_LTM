package com.bombit.common.protocol;

import java.util.*;

/** Bounded JSON for lobby DTOs: objects/arrays/strings/int64/boolean/null, no floating point. */
public final class LobbyJson {
    private final String text;
    private int pos;
    private LobbyJson(String text) {
        if (text.length()>65_536) throw new IllegalArgumentException("Lobby JSON too large");
        this.text=text;
    }
    public static Map<String,Object> object(String text) {
        var parser=new LobbyJson(text); Object value=parser.value(0);parser.space();
        if(parser.pos!=text.length()||!(value instanceof Map))throw new IllegalArgumentException("Expected JSON object");
        return map(value);
    }
    @SuppressWarnings("unchecked") public static Map<String,Object> map(Object value) {
        if(!(value instanceof Map))throw new IllegalArgumentException("Expected object");return (Map<String,Object>)value;
    }
    public static String encode(Object value) {
        if(value==null)return "null";
        if(value instanceof String s)return SessionPacketCodec.quote(s);
        if(value instanceof Integer||value instanceof Long||value instanceof Boolean)return value.toString();
        if(value instanceof Map<?,?> m) {
            var parts=new ArrayList<String>();m.forEach((k,v)->parts.add(encode((String)k)+":"+encode(v)));return "{"+String.join(",",parts)+"}";
        }
        if(value instanceof List<?> list)return "["+String.join(",",list.stream().map(LobbyJson::encode).toList())+"]";
        throw new IllegalArgumentException("Unsupported JSON value");
    }
    private void space(){while(pos<text.length()&&" \t\r\n".indexOf(text.charAt(pos))>=0)pos++;}
    private char take(){if(pos>=text.length())throw new IllegalArgumentException("Truncated JSON");return text.charAt(pos++);}
    private Object value(int depth) {
        if(depth>8)throw new IllegalArgumentException("JSON nesting too deep");space();
        if(pos>=text.length())throw new IllegalArgumentException("Missing value");char c=text.charAt(pos);
        if(c=='"')return string();
        if(c=='{') {
            pos++;space();var map=new LinkedHashMap<String,Object>();if(pos<text.length()&&text.charAt(pos)=='}'){pos++;return map;}
            while(true){space();String key=string();space();if(take()!=':')throw new IllegalArgumentException("Missing colon");
                if(map.containsKey(key)||map.size()>=256)throw new IllegalArgumentException("Duplicate key/too many fields");
                map.put(key,value(depth+1));space();char separator=take();if(separator=='}')return map;if(separator!=',')throw new IllegalArgumentException("Invalid object separator");}
        }
        if(c=='['){pos++;space();var list=new ArrayList<Object>();if(pos<text.length()&&text.charAt(pos)==']'){pos++;return list;}
            while(true){if(list.size()>=256)throw new IllegalArgumentException("Array too large");list.add(value(depth+1));space();char separator=take();if(separator==']')return list;if(separator!=',')throw new IllegalArgumentException("Invalid array separator");}}
        for(String literal:List.of("true","false","null"))if(text.startsWith(literal,pos)){pos+=literal.length();return literal.equals("null")?null:Boolean.valueOf(literal);}
        int start=pos;if(c=='-')pos++;
        if(pos>=text.length()||text.charAt(pos)<'0'||text.charAt(pos)>'9')throw new IllegalArgumentException("Invalid integer");
        if(text.charAt(pos)=='0')pos++;else while(pos<text.length()&&text.charAt(pos)>='0'&&text.charAt(pos)<='9')pos++;
        return Long.parseLong(text.substring(start,pos));
    }
    private String string(){
        if(take()!='"')throw new IllegalArgumentException("Expected string");var out=new StringBuilder();boolean ended=false;
        while(pos<text.length()){char c=take();if(c=='"'){ended=true;break;}if(c<32)throw new IllegalArgumentException("Control in string");
            if(c=='\\'){c=take();c=switch(c){case '"','\\','/'->c;case 'b'->'\b';case 'f'->'\f';case 'n'->'\n';case 'r'->'\r';case 't'->'\t';case 'u'->{if(pos+4>text.length())throw new IllegalArgumentException("Bad escape");for(int i=pos;i<pos+4;i++)if("0123456789abcdefABCDEF".indexOf(text.charAt(i))<0)throw new IllegalArgumentException("Bad hex escape");char code=(char)Integer.parseInt(text.substring(pos,pos+4),16);pos+=4;yield code;}default->throw new IllegalArgumentException("Bad escape");};}out.append(c);}
        if(!ended)throw new IllegalArgumentException("Unterminated string");
        for(int i=0;i<out.length();i++){char c=out.charAt(i);if(Character.isHighSurrogate(c)){if(++i>=out.length()||!Character.isLowSurrogate(out.charAt(i)))throw new IllegalArgumentException("Invalid surrogate");}else if(Character.isLowSurrogate(c))throw new IllegalArgumentException("Invalid surrogate");}
        return out.toString();
    }
}
