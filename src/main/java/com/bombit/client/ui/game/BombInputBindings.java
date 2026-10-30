package com.bombit.client.ui.game;

import javax.swing.*;
import java.awt.Window;
import java.awt.event.*;
import java.util.*;
import java.util.function.*;

/** Một lần nhấn chỉ gửi một request. Nhấn khi bị khóa vẫn phải nhả phím trước khi thử lại. */
public final class BombInputBindings implements AutoCloseable {
    private final JComponent root;
    private final Window window;
    private final Map<KeyStroke,Object> previous=new HashMap<>();
    private final List<String> actions=new ArrayList<>();
    private final boolean[] held=new boolean[3];
    private final WindowFocusListener focus=new WindowAdapter(){@Override public void windowLostFocus(WindowEvent e){Arrays.fill(held,false);}};
    public BombInputBindings(JComponent root,Window window,BooleanSupplier ready,Runnable normal,Runnable scan,Consumer<RuntimeException> errors){
        this(root,window,ready,normal,scan,()->{},errors);
    }
    public BombInputBindings(JComponent root,Window window,BooleanSupplier ready,Runnable normal,Runnable scan,Runnable rewind,Consumer<RuntimeException> errors){
        this.root=root;this.window=window;
        bind("SPACE",0,ready,normal,errors);bind("F",1,ready,scan,errors);
        bind("R",2,ready,rewind,errors);
        if(window!=null)window.addWindowFocusListener(focus);
    }
    private void bind(String key,int index,BooleanSupplier ready,Runnable send,Consumer<RuntimeException> errors){
        for(boolean down:new boolean[]{true,false}){
            var stroke=KeyStroke.getKeyStroke((down?"pressed ":"released ")+key);String action="game-plant-"+key+"-"+down;
            var map=root.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW);previous.put(stroke,map.get(stroke));map.put(stroke,action);actions.add(action);
            root.getActionMap().put(action,new AbstractAction(){@Override public void actionPerformed(ActionEvent e){
                if(!down){held[index]=false;return;}if(held[index])return;held[index]=true;
                if(ready.getAsBoolean())try{send.run();}catch(RuntimeException error){errors.accept(error);}
            }});
        }
    }
    @Override public void close(){
        previous.forEach((key,value)->{var map=root.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW);if(value==null)map.remove(key);else map.put(key,value);});
        actions.forEach(root.getActionMap()::remove);if(window!=null)window.removeWindowFocusListener(focus);Arrays.fill(held,false);
    }
}
