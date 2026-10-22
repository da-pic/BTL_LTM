package com.bombit.server.game.runtime;

/** Configurable demo rules, independent of fuse/range/quota configuration. */
public record MatchRules(long durationMillis,double dropChance,int maxBombs,int maxRange,double maxSpeed,double speedIncrement,
                         int initialLives,int maxLives) {
    /** Explicit legacy/test profile with one life. */
    public MatchRules(long durationMillis,double dropChance,int maxBombs,int maxRange,double maxSpeed,double speedIncrement){
        this(durationMillis,dropChance,maxBombs,maxRange,maxSpeed,speedIncrement,1,99);
    }
    public MatchRules {
        if(durationMillis<=0||durationMillis>86_400_000||!Double.isFinite(dropChance)||dropChance<0||dropChance>1
                ||maxBombs<=0||maxRange<=0||!Double.isFinite(maxSpeed)||maxSpeed<=0
                ||!Double.isFinite(speedIncrement)||speedIncrement<=0||initialLives<1||maxLives<initialLives)throw new IllegalArgumentException("Invalid match rules");
    }
    public static MatchRules demo() {return new MatchRules(Long.getLong("bombit.match.durationMillis",180000L),
            Double.parseDouble(System.getProperty("bombit.item.dropChance","0.75")),
            Integer.getInteger("bombit.item.maxBombs",5),Integer.getInteger("bombit.item.maxRange",8),
            Double.parseDouble(System.getProperty("bombit.item.maxSpeed","144")),
            Double.parseDouble(System.getProperty("bombit.item.speedIncrement","8")),
            Integer.getInteger("bombit.match.initialLives",3),Integer.getInteger("bombit.item.maxLives",99));}
}
