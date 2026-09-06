package cn.woshiikun_1145.mcmod.choco.cstmm.data;

import java.util.UUID;

public class PlayerProfile {
    private UUID playerUuid;
    private String playerName;
    private int totalKills;
    private int totalDeaths;
    private int totalMatches;
    private int totalWins;
    private int penaltyDeaths;

    public PlayerProfile() {}

    public PlayerProfile(UUID playerUuid, String playerName) {
        this.playerUuid = playerUuid;
        this.playerName = playerName;
        this.totalKills = 0;
        this.totalDeaths = 0;
        this.totalMatches = 0;
        this.totalWins = 0;
        this.penaltyDeaths = 0;
    }

    public UUID getPlayerUuid() { return playerUuid; }
    public String getPlayerName() { return playerName; }
    public int getTotalKills() { return totalKills; }
    public int getTotalDeaths() { return totalDeaths; }
    public int getTotalMatches() { return totalMatches; }
    public int getTotalWins() { return totalWins; }
    public int getPenaltyDeaths() { return penaltyDeaths; }

    public void setPlayerUuid(UUID playerUuid) { this.playerUuid = playerUuid; }
    public void setPlayerName(String playerName) { this.playerName = playerName; }
    public void setTotalKills(int totalKills) { this.totalKills = totalKills; }
    public void setTotalDeaths(int totalDeaths) { this.totalDeaths = totalDeaths; }
    public void setTotalMatches(int totalMatches) { this.totalMatches = totalMatches; }
    public void setTotalWins(int totalWins) { this.totalWins = totalWins; }
    public void setPenaltyDeaths(int penaltyDeaths) { this.penaltyDeaths = penaltyDeaths; }

    public void addKills(int amount) { this.totalKills += amount; }
    public void addDeaths(int amount) { this.totalDeaths += amount; }
    public void addPenaltyDeaths(int amount) { this.penaltyDeaths += amount; }
    public void addMatch() { this.totalMatches++; }
    public void addWin() { this.totalWins++; }

    public double getKD() {
        int deaths = totalDeaths == 0 ? 1 : totalDeaths;
        return (double) totalKills / deaths;
    }

    public String getKDString() {
        return String.format("%.2f", getKD());
    }
}