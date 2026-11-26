package dev.chargepilot.app;

final class PowerCycle {
    static final long DISCONNECT_MS = 10_000;
    static final long NONE = -1;
    enum Action { NONE, START, STOP }

    private boolean active;
    private boolean wired;
    private long deadline = NONE;

    PowerCycle(boolean wasActive, boolean hasPower, long now, long connectionDelay,
               long savedDisconnect) {
        active = wasActive;
        wired = hasPower;
        if (wired && !active) deadline = now + connectionDelay;
        if (!wired && active) {
            deadline = savedDisconnect >= 0 && savedDisconnect <= now
                    ? savedDisconnect + DISCONNECT_MS : now + DISCONNECT_MS;
        }
    }

    void update(boolean hasPower, long now, long connectionDelay) {
        if (wired == hasPower) return;
        wired = hasPower;
        if (wired == active) {
            deadline = NONE;
        } else {
            deadline = now + (wired ? connectionDelay : DISCONNECT_MS);
        }
    }

    Action ready(long now, boolean canRun) {
        if (!canRun || deadline == NONE || now < deadline) return Action.NONE;
        deadline = NONE;
        active = wired;
        return active ? Action.START : Action.STOP;
    }

    boolean isActive() { return active; }
    boolean isWired() { return wired; }
    long deadline() { return deadline; }
}
