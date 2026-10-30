package com.bombit.client.ui.game;

import com.bombit.common.dto.*;
import java.awt.Color;

/** Cùng roster authoritative cho màu, nhãn và hướng dẫn; không suy đội từ playerId. */
public final class MatchPresentation {
    private MatchPresentation(){}
    public static TeamId team(MatchStartMetadata metadata,int playerId){
        if(metadata==null||!metadata.configuration().isTeamMatch())return null;
        return metadata.players().stream().filter(p->p.playerId()==playerId).map(MatchStartMetadata.Member::team).findFirst().orElse(null);
    }
    public static String tag(MatchStartMetadata metadata,int playerId){var team=team(metadata,playerId);return "P"+playerId+(team==null?"":" · "+team);}
    public static Color color(MatchStartMetadata metadata,int playerId){
        var team=team(metadata,playerId);if(team!=null)return ShadowHudPanel.teamColor(team);
        return switch(Math.floorMod(playerId-1,4)){case 0->new Color(0x38,0xBD,0xF8);case 1->new Color(0xF4,0x3F,0x5E);case 2->new Color(0x22,0xC5,0x5E);default->new Color(0xF5,0x9E,0x0B);};
    }
    public static String rules(MatchStartMetadata metadata){
        if(metadata!=null&&metadata.configuration().gameMode()==GameMode.SHADOW_RELAY)return ShadowHudPanel.RULES;
        boolean paradox=metadata!=null&&metadata.configuration().gameMode()==GameMode.PARADOX;
        boolean teams=metadata!=null&&metadata.configuration().isTeamMatch();
        return "<html><b>"+(paradox?"PARADOX":"CLASSIC")+(teams?" — Đấu đội 2–2":" — Đấu cá nhân 2–4 người")+"</b><br>"
                +"Mũi tên: di chuyển · SPACE: đặt bom · "+(paradox?"R: rewind · ":"")+"F1: luật chơi.<br>"
                +(teams?"Đội A/B cố định suốt trận. Có sát thương đồng đội, kể cả tự gây sát thương.<br>"
                        +"Đội đối phương hết người sống thì thắng. Hai đội hết người cùng tick thì hòa.<br>"
                        +"Hết giờ mà cả hai đội còn người sống thì hòa.<br>":"Người sống sót cuối cùng thắng; hết giờ mà nhiều người còn sống thì hòa.<br>")
                +(paradox?"Mỗi người có 1 lượt đảo toàn world về 40 tick trước (2 giây ở 20 Hz).<br>"
                        +"Cần đủ lịch sử; chỉ người sống, đang kết nối mới kích hoạt được. Cooldown toàn trận 3 giây.<br>"
                        +"Bom/fuse, map, item và mạng sống được khôi phục; đội và lượt đã dùng giữ nguyên.<br>"
                        +"Đồng hồ trận không quay lại. Kết quả đã chốt không thể rewind.<br>"
                        +"Sau khi đồng bộ timeline mới, nhấn lại phím để tiếp tục.":"")+"</html>";
    }
}
