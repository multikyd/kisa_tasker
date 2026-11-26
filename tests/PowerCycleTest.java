package dev.chargepilot.app;

public final class PowerCycleTest {
    public static void main(String[] args) {
        PowerCycle cycle = new PowerCycle(false, false, 0, 3000, -1);
        expect(cycle, 100_000, PowerCycle.Action.NONE);
        cycle.update(true, 100_000, 3000);
        expect(cycle, 102_999, PowerCycle.Action.NONE);
        expect(cycle, 103_000, PowerCycle.Action.START);

        cycle.update(false, 110_000, 3000);
        cycle.update(false, 115_000, 3000);
        if (cycle.deadline() != 120_000) throw new AssertionError("Duplicate loss moved cutoff");
        expect(cycle, 119_998, PowerCycle.Action.NONE);
        cycle.update(true, 119_999, 3000);
        expect(cycle, 120_000, PowerCycle.Action.NONE);
        expect(cycle, 123_000, PowerCycle.Action.NONE);

        cycle.update(false, 130_000, 3000);
        expect(cycle, 139_999, PowerCycle.Action.NONE);
        expect(cycle, 140_000, PowerCycle.Action.STOP);
        expect(cycle, 155_000, PowerCycle.Action.NONE);
        cycle.update(true, 160_000, 3000);
        cycle.update(false, 161_000, 3000);
        expect(cycle, 171_000, PowerCycle.Action.NONE);

        PowerCycle restored = new PowerCycle(true, false, 15_000, 3000, 8_000);
        expect(restored, 17_999, PowerCycle.Action.NONE);
        expect(restored, 18_000, PowerCycle.Action.STOP);
        PowerCycle afterBoot = new PowerCycle(false, true, 10_000, 3000, -1);
        if (afterBoot.ready(13_000, false) != PowerCycle.Action.NONE) {
            throw new AssertionError("Tried to act without privileges");
        }
        expect(afterBoot, 18_000, PowerCycle.Action.START);

        PowerCycle interrupted = new PowerCycle(true, true, 0, 3000, -1);
        interrupted.update(false, 1_000, 3000);
        if (interrupted.ready(11_000, false) != PowerCycle.Action.NONE) {
            throw new AssertionError("Tried to stop without privileges");
        }
        interrupted.update(true, 11_000, 3000);
        expect(interrupted, 13_999, PowerCycle.Action.NONE);
        if (interrupted.ready(14_000, false) != PowerCycle.Action.NONE) {
            throw new AssertionError("Tried to start without privileges");
        }
        expect(interrupted, 15_000, PowerCycle.Action.START);

        PowerCycle quickRecovery = new PowerCycle(true, true, 0, 3000, -1);
        quickRecovery.update(false, 1_000, 3000);
        quickRecovery.update(true, 10_999, 3000);
        expect(quickRecovery, 18_000, PowerCycle.Action.NONE);
        System.out.println("PowerCycle tests passed");
    }

    private static void expect(PowerCycle cycle, long now, PowerCycle.Action expected) {
        PowerCycle.Action actual = cycle.ready(now, true);
        if (actual != expected) throw new AssertionError(now + " " + expected + " != " + actual);
    }
}
