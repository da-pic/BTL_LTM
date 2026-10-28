package com.bombit.client.game.movement;

import com.bombit.server.game.model.Direction;
import com.bombit.server.game.movement.MovePacketCodec;
import javax.swing.*;
import java.awt.Window;
import java.awt.event.*;
import java.util.*;
import java.util.function.Consumer;

/** Bind phím và lấy mẫu 20 Hz trên EDT. Giữ thứ tự phím để nhả hướng mới quay về hướng cũ.
 * sender phải enqueue nhanh sang worker mạng; không gọi socket.send gây block trên EDT.
 * Sau mất focus xóa phím đang giữ; mẫu kế tiếp gửi moving=false.
 */
public final class InputController implements AutoCloseable {
    private final LinkedHashSet<Direction> pressed = new LinkedHashSet<>();
    private final PredictionController prediction;
    private final Consumer<MovePacketCodec.Input> sender;
    private final javax.swing.Timer timer;
    private final JComponent component;
    private final Map<KeyStroke, Object> oldBindings = new HashMap<>();
    private final List<String> actions = new ArrayList<>();
    private final Window window;
    private final WindowFocusListener focus;
    private final java.util.function.BooleanSupplier canSample;
    private final java.util.function.LongSupplier generation;
    private long seenGeneration;
    private Direction lastDirection = Direction.DOWN;
    public InputController(JComponent component, Window window, PredictionController prediction,
                           Consumer<MovePacketCodec.Input> sender, Consumer<RuntimeException> onError) {
        this(component,window,prediction,sender,onError,()->true);
    }
    public InputController(JComponent component, Window window, PredictionController prediction,
                           Consumer<MovePacketCodec.Input> sender, Consumer<RuntimeException> onError,
                           java.util.function.BooleanSupplier canSample) {
        this(component,window,prediction,sender,onError,canSample,()->0);
    }
    public InputController(JComponent component, Window window, PredictionController prediction,
                           Consumer<MovePacketCodec.Input> sender, Consumer<RuntimeException> onError,
                           java.util.function.BooleanSupplier canSample,java.util.function.LongSupplier generation) {
        this.canSample=canSample;
        this.generation=generation;seenGeneration=generation.getAsLong();
        this.component=component; this.window=window; this.prediction=prediction; this.sender=sender;
        for (var direction : Direction.values()) bind(direction);
        focus = new WindowAdapter() { @Override public void windowLostFocus(WindowEvent e) { pressed.clear(); } };
        if(window!=null)window.addWindowFocusListener(focus);
        timer = new javax.swing.Timer(50, e -> {
            try { sample(); } catch (RuntimeException error) { close(); onError.accept(error); }
        });
    }
    private void bind(Direction direction) {
        for (boolean down : new boolean[]{true,false}) {
            String action = "tv2-" + direction + "-" + down;
            KeyStroke stroke = KeyStroke.getKeyStroke((down ? "pressed " : "released ") + direction);
            var map = component.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW);
            oldBindings.put(stroke,map.get(stroke)); actions.add(action); map.put(stroke,action);
            component.getActionMap().put(action,new AbstractAction() {
                @Override public void actionPerformed(ActionEvent e) {
                    refreshGeneration();
                    if(!canSample.getAsBoolean()){pressed.clear();return;}
                    if (down) pressed.add(direction); else pressed.remove(direction);
                }
            });
        }
    }
    public void start() { timer.start(); }
    /** Gửi cả mẫu đứng yên để ACK luôn gắn với số bước mô phỏng xác định. */
    public void sample() {
        refreshGeneration();
        // Không giữ phím cũ xuyên qua khoảng recovery rồi phát lại khi mở input.
        if (!canSample.getAsBoolean()) {pressed.clear();return;}
        for (var direction : pressed) lastDirection=direction;
        sender.accept(prediction.predict(lastDirection,!pressed.isEmpty()));
    }
    private void refreshGeneration() {
        long next=generation.getAsLong();
        // Recovery có thể xong giữa hai mẫu 50 ms; vẫn phải bỏ phím từ connection trước.
        if(next!=seenGeneration){pressed.clear();seenGeneration=next;}
    }
    @Override public void close() {
        timer.stop(); pressed.clear(); if(window!=null)window.removeWindowFocusListener(focus);
        var map = component.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW);
        oldBindings.forEach((stroke,old) -> { if (old == null) map.remove(stroke); else map.put(stroke,old); });
        actions.forEach(key -> component.getActionMap().remove(key));
    }
}
