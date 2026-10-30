package com.bombit.client.lobby;

import com.bombit.client.app.GameClientConnection;
import com.bombit.client.ui.game.MovementGameWindow;
import com.bombit.client.ui.UiTheme;
import com.bombit.client.ui.component.PlayerSlotPanel;
import com.bombit.client.ui.component.MapSelectionPicker;
import com.bombit.client.ui.component.ModeSelectionPanel;
import com.bombit.client.ui.screen.*;
import com.bombit.common.protocol.*;
import com.bombit.common.dto.MapSelection;
import com.bombit.common.dto.*;
import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.table.DefaultTableModel;
import java.awt.*;
import java.awt.event.*;
import java.util.*;

/** Coordinates client state and navigation; screen layout lives under client.ui. */
public final class LobbyWindow extends JFrame {
    private static final String LOGIN="LOGIN",BROWSE="BROWSE",ROOM="ROOM",RESULTS="RESULTS";
    private final JTextField host=new JTextField(12),port=new JTextField(5),name=new JTextField(16);
    private final JTextField roomName=new JTextField("Phòng của tôi",18),message=new JTextField();
    private final JSpinner capacity=new JSpinner(new SpinnerNumberModel(4,2,4,1));
    private final MapSelectionPicker mapSelection=new MapSelectionPicker();
    private final JButton connect=button("KẾT NỐI MÁY CHỦ",new Color(0x38,0xBD,0xF8));
    private final JButton create=button("＋ TẠO PHÒNG MỚI",new Color(0xA8,0x55,0xF7));
    private final JButton join=button("VÀO PHÒNG ĐANG CHỌN",new Color(0x38,0xBD,0xF8));
    private final JButton refresh=button("LÀM MỚI DANH SÁCH",new Color(100,116,139));
    private final JButton ready=button("SẴN SÀNG",new Color(0x22,0xC5,0x5E));
    private final JButton start=button("BẮT ĐẦU TRẬN",new Color(0xF5,0x9E,0x0B));
    private final JButton leave=button("RỜI PHÒNG",new Color(100,116,139));
    private final JButton end=button("KẾT THÚC TRẬN",new Color(0xF4,0x3F,0x5E));
    private final JButton send=UiTheme.arcadeButton("GỬI CHAT",true);
    private final JLabel loginStatus=new JLabel("Nhập tên rồi kết nối để vào sảnh chính",SwingConstants.CENTER);
    private final JLabel status=new JLabel("●  Đang chờ",SwingConstants.LEFT),browseServerInfo=new JLabel(),roomServerInfo=new JLabel();
    private final JLabel connectionDetails=new JLabel(),roomTitle=new JLabel("PHÒNG CHỜ"),roomMeta=new JLabel();
    private final DefaultTableModel roomRows=new DefaultTableModel(new Object[]{"MÃ","TÊN PHÒNG","CHỦ PHÒNG","NGƯỜI CHƠI","BẢN ĐỒ","TRẠNG THÁI","CHẾ ĐỘ"},0){@Override public boolean isCellEditable(int r,int c){return false;}};
    private final JTable roomTable=new JTable(roomRows);
    private final JPanel playerCards=UiTheme.panel(new GridLayout(2,2,12,12));
    private final JTextArea chat=new JTextArea();
    private final MatchResultsPanel resultPanel=new MatchResultsPanel();
    private final CardLayout screens=new CardLayout();
    private final JPanel screenRoot=new JPanel(screens);
    private final JLabel notificationText=new JLabel();
    private final JPanel notificationBar=new UiTheme.RoundedPanel(new BorderLayout(),new Color(127,29,29),14);
    private final javax.swing.Timer notificationTimer=new javax.swing.Timer(4500,e->notificationBar.setVisible(false));
    private java.util.List<LobbyPacketCodec.RoomView> rooms=java.util.List.of();
    private LobbyPacketCodec.RoomView room;
    private RoomStateView roomV3;
    private final ModeSelectionPanel createMode=new ModeSelectionPanel(mode->syncCreateCapacity(),format->syncCreateCapacity());
    private final ModeSelectionPanel roomMode=new ModeSelectionPanel(this::changeMode,format->changeMode(roomModeValue()));
    private GameMode roomModeValue(){return roomMode.selectedMode();}
    private static String matchLabel(MatchConfiguration c){return ModeSelectionPanel.label(c.gameMode())+" · "+(c.isTeamMatch()?"Đội 2–2":"Cá nhân");}
    private void syncCreateCapacity(){boolean teams=createMode.selectedFormat()==MatchFormat.TEAM_2V2;if(teams)capacity.setValue(4);capacity.setEnabled(!teams);}
    private java.util.List<ModeDescriptor> availableModes=java.util.List.of();
    private GameClientConnection client;
    private MovementGameWindow game;
    private boolean connected,showingResults,updatingMapSelection;
    private final Map<Integer,String> resultNames=new HashMap<>();

    public LobbyWindow(String initialHost,int initialPort,String initialName){
        super("Bomb It Multiplayer");UiTheme.install();host.setText(initialHost);port.setText(String.valueOf(initialPort));name.setText(initialName);
        screenRoot.add(new LoginScreenPanel(host,port,name,connect,loginStatus),LOGIN);
        screenRoot.add(new LobbyBrowserPanel(host,port,roomName,capacity,roomTable,refresh,create,join,status,browseServerInfo,connectionDetails,createMode),BROWSE);
        screenRoot.add(new WaitingRoomPanel(roomServerInfo,roomTitle,roomMeta,mapSelection,leave,playerCards,chat,message,send,end,ready,start,roomMode),ROOM);
        screenRoot.add(new ResultsScreenPanel(resultPanel,this::returnToLobby,this::returnToRoom),RESULTS);
        setContentPane(screenRoot);installNotificationBar();wire();refreshPlayers();
        setDefaultCloseOperation(DO_NOTHING_ON_CLOSE);addWindowListener(new WindowAdapter(){@Override public void windowClosing(WindowEvent e){if(game!=null)game.disposeGame();if(client!=null)client.close();dispose();}});
        pack();setSize(1180,760);setMinimumSize(new Dimension(1060,700));setLocationRelativeTo(null);updateButtons();
    }

    private void wire(){
        connect.addActionListener(e->connect());name.addActionListener(e->connect.doClick());
        create.addActionListener(e->command(PacketType.CREATE_ROOM,Map.of("roomName",roomName.getText(),"maxPlayers",capacity.getValue())));
        join.addActionListener(e->joinSelected());refresh.addActionListener(e->command(PacketType.LIST_ROOMS,Map.of()));
        roomTable.getSelectionModel().addListSelectionListener(e->updateButtons());roomTable.addMouseListener(new MouseAdapter(){@Override public void mouseClicked(MouseEvent e){if(e.getClickCount()==2)joinSelected();}});
        ready.addActionListener(e->{if(room!=null)command(PacketType.READY,Map.of("ready",!isReady()));});start.addActionListener(e->command(PacketType.START_GAME,Map.of()));
        leave.addActionListener(e->command(PacketType.LEAVE_ROOM,Map.of()));end.addActionListener(e->endRound());send.addActionListener(e->sendChat());message.addActionListener(e->sendChat());
        mapSelection.onSelectionChanged(selected->{if(!updatingMapSelection&&room!=null&&isHost())command(PacketType.SET_MAP_SELECTION,Map.of("mapSelection",selected.name()));});
    }
    private void returnToLobby(){showingResults=false;if(room!=null)command(PacketType.LEAVE_ROOM,Map.of());else screens.show(screenRoot,BROWSE);}
    private void returnToRoom(){showingResults=false;screens.show(screenRoot,ROOM);}
    private void joinSelected(){int row=roomTable.getSelectedRow();if(row>=0&&row<rooms.size())command(PacketType.JOIN_ROOM,Map.of("roomId",rooms.get(row).roomId()));}

    private void connect(){
        int number;try{number=Integer.parseInt(port.getText());if(number<1||number>65535)throw new NumberFormatException();}catch(NumberFormatException e){setStatus("Cổng phải nằm trong khoảng 1–65535",false);return;}
        String address=host.getText().strip(),username=name.getText().strip();if(username.isBlank()){setStatus("Vui lòng nhập tên người chơi",false);name.requestFocusInWindow();return;}
        if(client!=null)client.close();connected=false;showingResults=false;room=null;roomV3=null;
        connect.setEnabled(false);setStatus("Đang kết nối...",true);
        var owner=new java.util.concurrent.atomic.AtomicReference<GameClientConnection>();
        GameClientConnection next=GameClientConnection.current(SwingUtilities::invokeLater,movement->{
            if(client!=owner.get())return;
            showingResults=false;resultNames.clear();resultPanel.clear();if(room!=null)for(var p:room.players())client.context().playerId(p.playerId()).ifPresent(id->resultNames.put(id,p.username()));
            if(game!=null)game.disposeGame();
            game=new MovementGameWindow(movement,client.localId(),client.context().mapTheme(),()->{
                game=null;
                if(client.isReconnecting()){client.close();return;}
                if(isHost())endRound();else command(PacketType.LEAVE_ROOM,Map.of());
            });game.setVisible(true);
        },text->{
            if(client!=owner.get())return;
            setStatus(text,true);String info="●  "+username+"  •  "+address+":"+number;
            browseServerInfo.setText(info);roomServerInfo.setText(info);
            connectionDetails.setText("Host: "+address+"     •     Port: "+number+"     •     Tên nhân vật: "+username);updateButtons();
        },error->{
            if(client!=owner.get())return;
            connected=false;showingResults=false;connect.setEnabled(true);room=null;
            if(game!=null){game.disposeGame();game=null;}
            setStatus("Mất kết nối: "+error.getMessage(),false);screens.show(screenRoot,LOGIN);updateButtons();
        });
        owner.set(next);client=next;next.setAutoReconnect(true);
        next.setLobbyListener(p->{
            if(client!=next)return;
            if(p.getType()==PacketType.ROOM_STATE)syncMapSelection(legacyView(MetadataV3Codec.decodeRoom(p)));packet(p);
        });
        // Callback của kết nối cũ không được sửa màn hình của lần Connect mới.
        // Giữ cửa sổ/controller trong recovery; chỉ mở thao tác sau full đã được áp.
        next.setRecoveryStateListener(state->{
            if(client!=next)return;
            boolean newlyConnected=!connected;
            connected=state!=GameClientConnection.RecoveryState.CLOSED&&state!=GameClientConnection.RecoveryState.CONNECTING;
            if(state==GameClientConnection.RecoveryState.CLOSED){
                room=null;if(game!=null){game.disposeGame();game=null;}screens.show(screenRoot,LOGIN);
            }else{
                if(newlyConnected&&state==GameClientConnection.RecoveryState.CONNECTED
                        &&next.context()==null&&!showingResults&&room==null)screens.show(screenRoot,BROWSE);
                if(game!=null)game.setRecoveryStatus(next.isReconnecting()?"Đang phục hồi phiên — tạm khóa di chuyển và đặt bom...":null);
            }
            updateButtons();
        });
        Thread worker=new Thread(()->{try{next.connect(address,number,username);}catch(Exception ignored){ }},"Lobby-Connect");worker.setDaemon(true);worker.start();
    }

    private void syncMapSelection(LobbyPacketCodec.RoomView state){
        updatingMapSelection=true;
        try{mapSelection.setSelection(state.mapSelection());mapSelection.setEnabled(state.state().equals("WAITING")&&client!=null&&state.hostId().equals(client.sessionId()));}
        finally{updatingMapSelection=false;}
    }

    private void packet(Packet packet){switch(packet.getType()){
        case PacketType.LIST_ROOMS->{var response=MetadataV3Codec.decodeRooms(packet);availableModes=response.modes();createMode.setModes(availableModes);roomMode.setModes(availableModes);if(roomV3!=null)roomMode.showConfiguration(roomV3.configuration());rooms=response.rooms().stream().map(LobbyWindow::legacyView).toList();roomRows.setRowCount(0);for(var view:response.rooms()){var r=legacyView(view);String hostName=r.players().stream().filter(p->p.playerId().equals(r.hostId())).map(LobbyPacketCodec.Player::username).findFirst().orElse("—");roomRows.addRow(new Object[]{shortId(r.roomId()),r.roomName(),hostName,r.players().size()+"/"+r.maxPlayers(),r.mapSelection().label(),stateName(r.state()),matchLabel(view.configuration())});}}
        case PacketType.ROOM_STATE->{roomV3=MetadataV3Codec.decodeRoom(packet);room=legacyView(roomV3);roomMode.showConfiguration(roomV3.configuration());roomTitle.setText("PHÒNG CHỜ: "+room.roomName());roomMeta.setText(shortId(room.roomId())+"  •  "+room.players().size()+"/"+room.maxPlayers()+" người  •  "+matchLabel(roomV3.configuration())+"  •  "+stateName(room.state()));refreshPlayers();if(!showingResults)screens.show(screenRoot,ROOM);}
        case PacketType.LEAVE_ROOM->{showingResults=false;room=null;roomV3=null;chat.setText("");screens.show(screenRoot,BROWSE);command(PacketType.LIST_ROOMS,Map.of());}
        case PacketType.CHAT->{var c=JsonV3.fields(JsonV3.object(packet.getJsonPayload(),4096),"roomId","sessionId","username","message");if(room!=null&&room.roomId().equals(c.get("roomId"))){chat.append(c.get("username")+":  "+c.get("message")+"\n");chat.setCaretPosition(chat.getDocument().getLength());}}
        case PacketType.GAME_OVER->{if(game!=null){game.disposeGame();game=null;}if(client.lastShadowResult()!=null)resultPanel.showTeamResult(client.lastShadowResult(),client.metadata().players(),client.localId());else if(client.lastTeamResult()!=null)resultPanel.showTeamResult(client.lastTeamResult(),client.metadata().players(),client.localId());else resultPanel.showResult(client.lastResult(),resultNames,client.localId());showingResults=true;screens.show(screenRoot,RESULTS);toFront();requestFocus();setStatus("Trận đấu đã kết thúc",true);}
        case PacketType.ERROR->{String code=SessionV3Codec.errorCode(packet);if(!("NOT_PLAYING".equals(code)&&showingResults))setStatus(errorMessage(code),false);}default->{}}
        updateButtons();
    }

    private void refreshPlayers(){playerCards.removeAll();if(room!=null)for(var p:room.players())playerCards.add(playerCard(p,p.playerId().equals(room.hostId())));for(int i=room==null?0:room.players().size();i<4;i++){JPanel empty=new UiTheme.RoundedPanel(new GridBagLayout(),new Color(15,23,41),14);JLabel waiting=new JLabel("＋  Đang chờ người chơi");waiting.setForeground(UiTheme.MUTED);empty.add(waiting);playerCards.add(empty);}playerCards.revalidate();playerCards.repaint();}
    private JPanel playerCard(LobbyPacketCodec.Player p,boolean hostPlayer){
        boolean local=client!=null&&p.playerId().equals(client.sessionId());
        var team=roomV3==null||!roomV3.configuration().isTeamMatch()?null:roomV3.players().stream()
                .filter(m->m.sessionId().equals(p.playerId())).map(RoomStateView.Member::team).findFirst().orElse(null);
        String role=(team==null?"":"Đội "+team+" · ")+(hostPlayer?"Chủ phòng":"Thành viên");
        Color color=team==null?playerSlotColor(room==null?0:room.players().indexOf(p)):com.bombit.client.ui.game.ShadowHudPanel.teamColor(team);
        return new PlayerSlotPanel(p.username()+(local?" (Bạn)":""),role,p.state().equals("READY"),color);
    }
    private static Color playerSlotColor(int index){return switch(Math.floorMod(index,4)){case 0->new Color(0x38,0xBD,0xF8);case 1->new Color(0xF4,0x3F,0x5E);case 2->new Color(0x22,0xC5,0x5E);default->new Color(0xF5,0x9E,0x0B);};}
    private String shortId(String id){return "#"+id.substring(0,Math.min(8,id.length())).toUpperCase(Locale.ROOT);}
    private void sendChat(){if(room!=null&&!message.getText().isBlank()){command(PacketType.CHAT,Map.of("message",message.getText().strip()));message.setText("");}}
    private void command(short type,Map<String,?> body){if(client==null||!connected||client.isReconnecting()||client.isClosed())return;var fields=new LinkedHashMap<String,Object>(body);if(roomV3!=null&&(type==PacketType.READY||type==PacketType.START_GAME||type==PacketType.SET_MAP_SELECTION||type==PacketType.CHAT||type==PacketType.LEAVE_ROOM))fields.put("roomId",roomV3.roomId());
        if(roomV3!=null&&(type==PacketType.READY||type==PacketType.START_GAME))fields.put("configurationRevision",roomV3.configurationRevision());
        if(type==PacketType.CREATE_ROOM){fields.put("gameMode",createMode.selectedMode().name());fields.put("matchFormat",createMode.selectedFormat().name());fields.put("mapSelection","RANDOM");}client.send(JsonV3.packet(type,fields));}
    private void endRound(){if(client==null||client.context()==null||client.isReconnecting()||client.isClosed())return;try{client.send(client.matchCommand(new Packet(PacketType.END_GAME,"{}")));}catch(java.io.IOException e){setStatus(e.getMessage(),false);}}
    private void changeMode(GameMode mode){if(roomV3==null||!isHost()||client==null)return;var descriptor=availableModes.stream().filter(m->m.gameMode()==mode&&m.available()).findFirst().orElse(null);if(descriptor==null)return;
        String preset=switch(mode){case CLASSIC->"CLASSIC_SERVER_DEFAULT";case SHADOW_RELAY->"SHADOW_RELAY_V1";case PARADOX->"PARADOX_V1";case CIRCUIT->throw new IllegalArgumentException("Circuit unavailable");};
        client.send(JsonV3.packet(PacketType.SET_MATCH_CONFIGURATION,JsonV3.body("roomId",roomV3.roomId(),"expectedRevision",roomV3.configurationRevision(),"configuration",JsonV3.body("gameMode",mode.name(),"rulesVersion",descriptor.rulesVersion(),"presetId",preset,"matchFormat",roomMode.selectedFormat().name()))));}
    private static LobbyPacketCodec.RoomView legacyView(RoomStateView r){return new LobbyPacketCodec.RoomView(r.roomId(),r.roomName(),r.hostId(),r.state(),r.maxPlayers(),r.mapSelection(),r.players().stream().map(p->new LobbyPacketCodec.Player(p.sessionId(),p.username(),p.state())).toList());}
    private boolean isHost(){return room!=null&&client!=null&&room.hostId().equals(client.sessionId());}
    private boolean isReady(){return room!=null&&room.players().stream().anyMatch(p->p.playerId().equals(client.sessionId())&&p.state().equals("READY"));}
    private void updateButtons(){
        boolean usable=connected&&client!=null&&!client.isReconnecting()&&!client.isClosed();
        boolean waiting=usable&&room!=null&&room.state().equals("WAITING");
        create.setEnabled(usable&&room==null);join.setEnabled(usable&&room==null&&roomTable.getSelectedRow()>=0);
        refresh.setEnabled(usable);ready.setEnabled(waiting);ready.setText(isReady()?"HỦY SẴN SÀNG":"SẴN SÀNG");
        start.setEnabled(waiting&&isHost()&&room.players().size()>=2
                &&(roomV3==null||!roomV3.configuration().isTeamMatch()||room.players().size()==4)
                &&room.players().stream().allMatch(p->p.playerId().equals(room.hostId())||p.state().equals("READY")));leave.setEnabled(usable&&room!=null);end.setEnabled(usable&&room!=null&&isHost()&&!waiting);
        createMode.setChangeAllowed(usable&&room==null);roomMode.setChangeAllowed(waiting&&isHost());ready.setEnabled(waiting&&!isHost());
        message.setEnabled(usable&&room!=null);send.setEnabled(usable&&room!=null);mapSelection.setEnabled(waiting&&isHost());
        connect.setEnabled(client==null||client.isClosed());host.setEnabled(!connected);port.setEnabled(!connected);name.setEnabled(!connected);
    }
    private String stateName(String state){return switch(state){case "WAITING"->"Đang chờ";case "READY"->"Sẵn sàng";case "PLAYING"->"Đang đấu";case "CONNECTED"->"Chưa sẵn sàng";default->state;};}

    private void installNotificationBar(){notificationBar.setBorder(new EmptyBorder(12,18,12,18));notificationText.setForeground(Color.WHITE);notificationText.setFont(UiTheme.BODY.deriveFont(Font.BOLD,13f));notificationBar.add(notificationText);notificationBar.setVisible(false);getLayeredPane().add(notificationBar,JLayeredPane.POPUP_LAYER);addComponentListener(new ComponentAdapter(){@Override public void componentResized(ComponentEvent e){positionNotification();}});notificationTimer.setRepeats(false);}
    private void positionNotification(){int width=Math.min(660,Math.max(320,getWidth()-48));notificationBar.setBounds((getWidth()-width)/2,18,width,46);}
    private void showErrorBar(String text){notificationText.setText("⚠  "+text);positionNotification();notificationBar.setVisible(true);notificationBar.repaint();notificationTimer.restart();}
    private String errorMessage(String code){return switch(code){case "ROOM_FULL"->"Phòng đã đầy, không thể tham gia.";case "ROOM_PLAYING"->"Phòng đang chơi, vui lòng chờ trận đấu kết thúc.";case "NOT_READY"->"Chưa thể bắt đầu: vẫn còn thành viên chưa sẵn sàng.";case "NOT_PLAYING"->"Lệnh trận đấu đã bị bỏ qua vì trận đấu đã kết thúc hoặc chưa bắt đầu.";case "NOT_HOST"->"Chỉ chủ phòng mới có thể thực hiện thao tác này.";case "ROOM_NOT_FOUND"->"Phòng không còn tồn tại.";case "ALREADY_IN_ROOM"->"Bạn đang ở trong một phòng khác.";case "NOT_IN_ROOM"->"Bạn không còn ở trong phòng này.";case "ROOM_LIMIT"->"Máy chủ đã đạt giới hạn số phòng.";case "RATE_LIMITED"->"Bạn thao tác quá nhanh, vui lòng thử lại.";case "CHAT_QUEUE_FULL","INPUT_QUEUE_FULL"->"Máy chủ đang bận, vui lòng thử lại.";case "CONFIGURATION_STALE"->"Cấu hình phòng đã thay đổi. Vui lòng xác nhận sẵn sàng lại.";case "MODE_NOT_SUPPORTED","MODE_UNAVAILABLE"->"Chế độ này chưa khả dụng trên máy chủ.";case "CLIENT_CAPABILITY_MISMATCH","UNSUPPORTED_PROTOCOL","UNSUPPORTED_SNAPSHOT"->"Cần dùng cùng phiên bản game với máy chủ.";case "MATCH_START_FAILED"->"Không thể khởi động trận đấu.";default->"Yêu cầu bị từ chối ("+code+").";};}
    private void setStatus(String text,boolean ok){status.setText((ok?"●  ":"⚠  ")+text);status.setForeground(ok?UiTheme.ACCENT:UiTheme.DANGER);loginStatus.setText(text);loginStatus.setForeground(ok?UiTheme.ACCENT:UiTheme.DANGER);if(!ok)showErrorBar(text);}
    private static JButton button(String text,Color color){return UiTheme.arcadeButton(text,color);}
}
