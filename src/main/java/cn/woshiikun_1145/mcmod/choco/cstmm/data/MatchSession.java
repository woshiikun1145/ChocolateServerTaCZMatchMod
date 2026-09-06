package cn.woshiikun_1145.mcmod.choco.cstmm.data;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

public class MatchSession {
    private final String sessionId;
    private final String mapName;
    private final Set<UUID> redPlayers;
    private final Set<UUID> bluePlayers;
    private int redKills;
    private int blueKills;
    private int remainingSeconds;
    private GamePhase phase;
    private int prepCounter;
    private boolean isCompetitive;
    private long startTime;
    private int overtimeCount;
    private final java.util.Map<UUID, Integer> boundaryWarningTime;

    public MatchSession(String mapName) {
        this.sessionId = UUID.randomUUID().toString();
        this.mapName = mapName;
        this.redPlayers = new HashSet<>();
        this.bluePlayers = new HashSet<>();
        this.redKills = 0;
        this.blueKills = 0;
        this.remainingSeconds = 0;
        this.phase = GamePhase.IDLE;
        this.prepCounter = 0;
        this.isCompetitive = false;
        this.startTime = System.currentTimeMillis();
        this.boundaryWarningTime = new java.util.HashMap<>();
        this.overtimeCount = 0;
    }

    public String getSessionId() { return sessionId; }
    public String getMapName() { return mapName; }
    public Set<UUID> getRedPlayers() { return redPlayers; }
    public Set<UUID> getBluePlayers() { return bluePlayers; }
    public int getRedKills() { return redKills; }
    public int getBlueKills() { return blueKills; }
    public int getRemainingSeconds() { return remainingSeconds; }
    public GamePhase getPhase() { return phase; }
    public int getPrepCounter() { return prepCounter; }
    public boolean isCompetitive() { return isCompetitive; }
    public long getStartTime() { return startTime; }
    public int getOvertimeCount() { return overtimeCount; }

    public void setRedKills(int redKills) { this.redKills = redKills; }
    public void setBlueKills(int blueKills) { this.blueKills = blueKills; }
    public void setRemainingSeconds(int remainingSeconds) { this.remainingSeconds = remainingSeconds; }
    public void setPhase(GamePhase phase) { this.phase = phase; }
    public void setPrepCounter(int prepCounter) { this.prepCounter = prepCounter; }
    public void setCompetitive(boolean competitive) { isCompetitive = competitive; }
    public void setStartTime(long startTime) { this.startTime = startTime; }
    public void setOvertimeCount(int overtimeCount) { this.overtimeCount = overtimeCount; }

    public void addRedKills(int amount) { this.redKills += amount; }
    public void addBlueKills(int amount) { this.blueKills += amount; }

    public int getTotalPlayers() {
        return redPlayers.size() + bluePlayers.size();
    }

    public boolean isPlayerInGame(UUID playerUuid) {
        return redPlayers.contains(playerUuid) || bluePlayers.contains(playerUuid);
    }

    public int getPlayerTeam(UUID playerUuid) {
        if (redPlayers.contains(playerUuid)) return 1;
        if (bluePlayers.contains(playerUuid)) return 2;
        return 0;
    }

    public Set<UUID> getAllPlayers() {
        Set<UUID> all = new HashSet<>();
        all.addAll(redPlayers);
        all.addAll(bluePlayers);
        return all;
    }

    public int getBoundaryWarningTime(UUID playerUuid) {
        return boundaryWarningTime.getOrDefault(playerUuid, 0);
    }

    public void setBoundaryWarningTime(UUID playerUuid, int seconds) {
        boundaryWarningTime.put(playerUuid, seconds);
    }

    public void resetBoundaryWarningTime(UUID playerUuid) {
        boundaryWarningTime.remove(playerUuid);
    }

    public long getElapsedMillis() {
        return System.currentTimeMillis() - startTime;
    }

    public enum GamePhase {
        IDLE,
        PREPARING,
        FIGHTING,
        ENDED
    }
}