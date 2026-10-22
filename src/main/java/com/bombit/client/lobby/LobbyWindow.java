package com.bombit.client.lobby;

import com.bombit.client.app.DemoClientConnection;
import com.bombit.client.game.movement.MovementGameWindow;
import com.bombit.common.protocol.*;
import javax.swing.*;
import java.awt.*;
import java.awt.event.*;
import java.util.*;

/** One connection survives room changes and replay. All component state belongs to EDT. */
public final class LobbyWindow extends JFrame {
    private final JTextField host=new JTextField(12), port=new JTextField(5), name=new JTextField(12);
    private final JButton connect=new JButton("Connect"), create=new JButton("Create"), join=new JButton("Join"),
            refresh=new JButton("Refresh"), ready=new JButton("Ready"), start=new JButton("Start"),
            leave=new JButton("Leave room"), end=new JButton("End round");
    private final JTextField roomName=new JTextField("My room",12), message=new JTextField(28);
    private final JSpinner capacity=new JSpinner(new SpinnerNumberModel(4,2,4,1));
    private final DefaultListModel<String> roomRows=new DefaultListModel<>();
    private final JList<String> roomList=new JList<>(roomRows);
    private final JTextArea players=new JTextArea(6,32), chat=new JTextArea(10,45);
    private final JLabel status=new JLabel("Enter server address and connect.");
    private java.util.List<LobbyPacketCodec.RoomView> rooms=java.util.List.of();
    private LobbyPacketCodec.RoomView room;
    private DemoClientConnection client;
    private MovementGameWindow game;
    private boolean connected;
    private final MatchResultsPanel results=new MatchResultsPanel();
    private final JTabbedPane tabs=new JTabbedPane();
    private final Map<Integer,String> resultNames=new HashMap<>();

    public LobbyWindow(String initialHost,int initialPort,String initialName) {
        super("Bomb It - Lobby"); host.setText(initialHost);port.setText(""+initialPort);name.setText(initialName);
        JPanel connection=new JPanel(); connection.add(new JLabel("Host"));connection.add(host);
        connection.add(new JLabel("Port"));connection.add(port);connection.add(new JLabel("Name"));connection.add(name);connection.add(connect);
        JPanel browse=new JPanel(new BorderLayout());browse.add(new JScrollPane(roomList));
        JPanel roomActions=new JPanel();roomActions.add(roomName);roomActions.add(capacity);roomActions.add(create);roomActions.add(join);roomActions.add(refresh);browse.add(roomActions,BorderLayout.SOUTH);
        players.setEditable(false);chat.setEditable(false);
        JPanel detail=new JPanel(new BorderLayout());detail.add(new JScrollPane(players));
        JPanel actions=new JPanel(); for(JButton b:new JButton[]{ready,start,leave,end}) actions.add(b);detail.add(actions,BorderLayout.SOUTH);
        JPanel middle=new JPanel(new GridLayout(1,2));middle.add(browse);middle.add(detail);
        JPanel chatPanel=new JPanel(new BorderLayout());chatPanel.add(new JScrollPane(chat));
        JButton send=new JButton("Send");JPanel compose=new JPanel();compose.add(message);compose.add(send);chatPanel.add(compose,BorderLayout.SOUTH);
        tabs.addTab("Chat",chatPanel);tabs.addTab("Kết quả",results);
        JPanel body=new JPanel(new BorderLayout());body.add(middle,BorderLayout.NORTH);body.add(tabs);
        add(connection,BorderLayout.NORTH);add(body);add(status,BorderLayout.SOUTH);
        connect.addActionListener(e -> connect());
        create.addActionListener(e -> command(PacketType.CREATE_ROOM,Map.of("roomName",roomName.getText(),"maxPlayers",capacity.getValue())));
        join.addActionListener(e -> { int index=roomList.getSelectedIndex();if(index>=0 && index<rooms.size()) command(PacketType.JOIN_ROOM,Map.of("roomId",rooms.get(index).roomId())); });
        refresh.addActionListener(e -> command(PacketType.LIST_ROOMS,Map.of()));
        ready.addActionListener(e -> { if(room!=null) command(PacketType.READY,Map.of("ready",!isReady())); });
        start.addActionListener(e -> command(PacketType.START_GAME,Map.of()));
        leave.addActionListener(e -> command(PacketType.LEAVE_ROOM,Map.of()));
        end.addActionListener(e -> endRound());
        send.addActionListener(e -> sendChat());message.addActionListener(e -> sendChat());
        setDefaultCloseOperation(DO_NOTHING_ON_CLOSE);
        addWindowListener(new WindowAdapter(){ @Override public void windowClosing(WindowEvent e) {
            if(game!=null) game.disposeGame();if(client!=null) client.close();dispose();
        }});
        updateButtons();pack();setLocationByPlatform(true);
    }
    private void connect() {
        int number;try { number=Integer.parseInt(port.getText());if(number<1||number>65535) throw new NumberFormatException(); }
        catch(NumberFormatException invalid) { status.setText("Port must be 1..65535");return; }
        if(client!=null) client.close();connect.setEnabled(false);room=null;
        String address=host.getText().strip(),username=name.getText();
        DemoClientConnection next=new DemoClientConnection(SwingUtilities::invokeLater,movement -> {
            resultNames.clear();results.clear();tabs.setSelectedIndex(0);
            if(room!=null)for(var p:room.players())client.context().playerId(p.playerId()).ifPresent(id->resultNames.put(id,p.username()));
            if(game!=null) game.disposeGame();
            game=new MovementGameWindow(movement,client.localId(),() -> {
                game=null;if(isHost()) endRound();else command(PacketType.LEAVE_ROOM,Map.of());
            });game.setVisible(true);
        },text -> { connected=true;status.setText(text);updateButtons(); },error -> {
            connected=false;connect.setEnabled(true);room=null;
            if(game!=null) { game.disposeGame();game=null; }
            status.setText("Disconnected: "+error.getMessage());updateButtons();
        });
        client=next;next.setLobbyListener(this::packet);
        Thread worker=new Thread(() -> { try { next.connect(address,number,username); } catch(Exception ignored) { } },"Lobby-Connect");
        worker.setDaemon(true);worker.start();
    }
    private void packet(Packet packet) {
        switch(packet.getType()) {
            case PacketType.LIST_ROOMS -> {
                rooms=LobbyPacketCodec.decodeRooms(packet);roomRows.clear();
                for(var r:rooms) roomRows.addElement(r.roomName()+"  "+r.players().size()+"/"+r.maxPlayers()+"  "+r.state());
            }
            case PacketType.ROOM_STATE -> {
                room=LobbyPacketCodec.decodeRoom(packet);
                StringBuilder text=new StringBuilder(room.roomName()+" - "+room.state()+"\n");
                for(var p:room.players()) text.append(p.playerId().equals(room.hostId())?"[Host] ":"").append(p.username()).append("  ").append(p.state()).append('\n');
                players.setText(text.toString());
            }
            case PacketType.LEAVE_ROOM -> { room=null;players.setText("");chat.setText(""); }
            case PacketType.CHAT -> { var c=LobbyPacketCodec.decodeChat(packet);if(room!=null&&room.roomId().equals(c.roomId())) { chat.append(c.username()+": "+c.message()+"\n");if(chat.getDocument().getLength()>20000) chat.setText(chat.getText().substring(10000)); } }
            case PacketType.GAME_OVER -> {
                if(game!=null) { game.disposeGame();game=null; }
                results.showResult(client.lastResult(),resultNames);tabs.setSelectedComponent(results);
                status.setText("Round ended. Ready again to replay, or leave for lobby.");
            }
            case PacketType.ERROR -> status.setText("Request rejected: "+SessionPacketCodec.errorCode(packet));
            default -> { }
        }
        updateButtons();
    }
    private void sendChat() { if(room!=null&&!message.getText().isBlank()) { command(PacketType.CHAT,Map.of("message",message.getText()));message.setText(""); } }
    private void command(short type,Map<String,?> body) {
        if(client==null||!connected) return;
        var fields=new LinkedHashMap<String,Object>(body);
        if(room!=null&&(type==PacketType.READY||type==PacketType.START_GAME||type==PacketType.CHAT||type==PacketType.LEAVE_ROOM)) fields.put("roomId",room.roomId());
        if(type==PacketType.CREATE_ROOM) fields.put("mode","STANDARD");
        client.send(LobbyPacketCodec.packet(type,fields));
    }
    private void endRound() {
        if(client==null||client.context()==null) return;
        try { client.send(MatchEnvelopeCodec.wrap(client.context().matchId(),new Packet(PacketType.END_GAME,"{}"))); }
        catch(java.io.IOException error) { status.setText(error.getMessage()); }
    }
    private boolean isHost() { return room!=null&&client!=null&&room.hostId().equals(client.sessionId()); }
    private boolean isReady() { return room!=null&&room.players().stream().anyMatch(p -> p.playerId().equals(client.sessionId())&&p.state().equals("READY")); }
    private void updateButtons() {
        boolean waiting=connected&&room!=null&&room.state().equals("WAITING");
        create.setEnabled(connected&&room==null);join.setEnabled(connected&&room==null);refresh.setEnabled(connected);
        ready.setEnabled(waiting);ready.setText(isReady()?"Unready":"Ready");
        start.setEnabled(waiting&&isHost());leave.setEnabled(connected&&room!=null);
        end.setEnabled(connected&&room!=null&&isHost()&&!waiting);message.setEnabled(connected&&room!=null);
        host.setEnabled(!connected);port.setEnabled(!connected);name.setEnabled(!connected);
    }
}
