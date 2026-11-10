package io.openems.edge.ess.srne.batteryinverter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import io.openems.edge.bridge.modbus.api.task.FC16WriteRegistersTask;
import io.openems.edge.bridge.modbus.api.task.Task.ExecuteState;

/**
 * Pure tests of the daily output-priority schedule: the time windows, the
 * configuration validation, the clock gate and the guarded write decisions. The
 * production defaults are SUB (3) from 00:30 to 18:00 and SBU (2) from 18:00
 * to 00:30.
 */
public class ModeScheduleTest {

	private static final int SBU = 2;
	private static final int SUB = 3;
	private static final int TIMEOUT = 30;
	private static final int COOLDOWN = 60;
	private static final LocalTime DAY = LocalTime.of(10, 0);
	private static final LocalTime EVENING = LocalTime.of(19, 0);
	private static final ZoneId KAMPALA = ZoneId.of("Africa/Kampala");

	private static ModeSchedule defaults() {
		return ModeSchedule.fromConfig(true, SUB, SBU, "18:00", "00:30", -1);
	}

	// A schedule past the startup hold, with mains confirmed and the device in sync
	// by day.
	private static ModeSchedule primed() {
		var sut = defaults();
		for (var i = 0; i < ModeSchedule.STARTUP_HOLD_CYCLES; i++) {
			assertEquals(ModeScheduleState.IN_SYNC, step(sut, DAY, 50, SUB, true).state());
		}
		return sut;
	}

	// One cycle with one fresh machine-state read.
	private static ModeSchedule.Step step(ModeSchedule sut, LocalTime now, Integer soc, Integer e204,
			boolean mains) {
		sut.onMachineStateRead(mains);
		return sut.step(now, soc, e204, true, mains, true, TIMEOUT, COOLDOWN);
	}

	private static void assertStep(ModeSchedule.Step step, ModeScheduleState state, Integer target, Integer write) {
		assertEquals(state, step.state());
		assertEquals(target, step.target());
		assertEquals(write, step.write());
	}

	@Test
	public void testDefaultWindowsCrossMidnight() {
		var sut = defaults();
		assertTrue(sut.isActive());
		assertEquals(SUB, sut.targetAt(LocalTime.of(0, 30))); // daytime starts exactly at 00:30
		assertEquals(SUB, sut.targetAt(LocalTime.of(12, 0)));
		assertEquals(SUB, sut.targetAt(LocalTime.of(17, 59)));
		assertEquals(SUB, sut.targetAt(LocalTime.of(17, 59, 59)));
		assertEquals(SBU, sut.targetAt(LocalTime.of(18, 0))); // evening starts exactly at 18:00
		assertEquals(SBU, sut.targetAt(LocalTime.of(23, 59)));
		assertEquals(SBU, sut.targetAt(LocalTime.MIDNIGHT));
		assertEquals(SBU, sut.targetAt(LocalTime.of(0, 29)));
		assertEquals(SBU, sut.targetAt(LocalTime.of(0, 29, 59)));
		assertNull(sut.targetAt(null));
	}

	@Test
	public void testWindowWithoutMidnightCrossing() {
		var sut = ModeSchedule.fromConfig(true, SUB, SBU, "01:00", "05:00", -1);
		assertTrue(sut.isActive());
		assertEquals(SUB, sut.targetAt(LocalTime.of(0, 59)));
		assertEquals(SBU, sut.targetAt(LocalTime.of(1, 0)));
		assertEquals(SBU, sut.targetAt(LocalTime.of(4, 59)));
		assertEquals(SUB, sut.targetAt(LocalTime.of(5, 0)));
		assertEquals(SUB, sut.targetAt(LocalTime.of(23, 0)));
	}

	@Test
	public void testConfigValidation() {
		assertNull(defaults().getInvalidReason());
		// Only SBU (2) and SUB (3) are allowed in this version.
		for (var bad : new int[] { -1, 0, 1, 4 }) {
			assertNotNull(ModeSchedule.fromConfig(true, bad, SBU, "18:00", "00:30", -1).getInvalidReason());
			assertNotNull(ModeSchedule.fromConfig(true, SUB, bad, "18:00", "00:30", -1).getInvalidReason());
		}
		// The two priorities must differ.
		assertNotNull(ModeSchedule.fromConfig(true, SUB, SUB, "18:00", "00:30", -1).getInvalidReason());
		assertNotNull(ModeSchedule.fromConfig(true, SBU, SBU, "18:00", "00:30", -1).getInvalidReason());
		// Times must parse as HH:MM.
		for (var bad : new String[] { null, "", "6:00", "24:00", "18:60", "18-00", "evening" }) {
			assertNotNull(ModeSchedule.fromConfig(true, SUB, SBU, bad, "00:30", -1).getInvalidReason(), "" + bad);
			assertNotNull(ModeSchedule.fromConfig(true, SUB, SBU, "18:00", bad, -1).getInvalidReason(), "" + bad);
		}
		assertNull(ModeSchedule.fromConfig(true, SUB, SBU, " 18:00 ", "00:30", -1).getInvalidReason());
		// The two times must differ.
		assertNotNull(ModeSchedule.fromConfig(true, SUB, SBU, "18:00", "18:00", -1).getInvalidReason());
		// One owner of E204: a static outputPriority target conflicts with the schedule.
		var conflict = ModeSchedule.fromConfig(true, SUB, SBU, "18:00", "00:30", SBU);
		assertNotNull(conflict.getInvalidReason());
		assertFalse(conflict.isActive());
		// Disabled: never invalid, never active, whatever else is configured.
		var disabled = ModeSchedule.fromConfig(false, 0, 7, "x", "y", SBU);
		assertNull(disabled.getInvalidReason());
		assertFalse(disabled.isActive());
	}

	@Test
	public void testInvalidConfigNeverWrites() {
		var sut = ModeSchedule.fromConfig(true, SUB, SUB, "18:00", "00:30", -1);
		for (var i = 0; i < 200; i++) {
			assertStep(step(sut, DAY, 50, SBU, true), ModeScheduleState.CONFIG_INVALID, null, null);
		}
	}

	@Test
	public void testDisabledNeverWrites() {
		var sut = ModeSchedule.fromConfig(false, SUB, SBU, "18:00", "00:30", -1);
		for (var i = 0; i < 200; i++) {
			assertStep(step(sut, DAY, 50, SBU, true), ModeScheduleState.DISABLED, null, null);
		}
	}

	@Test
	public void testControlDisabledNeverWrites() {
		var sut = defaults();
		for (var i = 0; i < 200; i++) {
			assertStep(sut.step(DAY, 50, SBU, false, true, true, TIMEOUT, COOLDOWN), ModeScheduleState.DISABLED,
					SUB, null);
		}
	}

	@Test
	public void testInSyncNeverWrites() {
		var sut = defaults();
		for (var i = 0; i < 200; i++) {
			assertStep(step(sut, DAY, 50, SUB, true), ModeScheduleState.IN_SYNC, SUB, null);
			assertStep(step(sut, EVENING, 50, SBU, true), ModeScheduleState.IN_SYNC, SBU, null);
		}
	}

	@Test
	public void testUnreadableInputsNeverWrite() {
		var sut = primed();
		assertStep(step(sut, DAY, null, SBU, true), ModeScheduleState.WAITING_FOR_DATA, SUB, null);
		assertStep(step(sut, DAY, -1, SBU, true), ModeScheduleState.WAITING_FOR_DATA, SUB, null);
		assertStep(step(sut, DAY, 101, SBU, true), ModeScheduleState.WAITING_FOR_DATA, SUB, null);
		assertStep(step(sut, DAY, 50, null, true), ModeScheduleState.WAITING_FOR_DATA, SUB, null);
		assertStep(step(sut, null, 50, SBU, true), ModeScheduleState.WAITING_FOR_DATA, null, null);
		// Once everything is readable the write goes out.
		assertStep(step(sut, DAY, 0, SBU, true), ModeScheduleState.WRITE_PENDING, SUB, SUB);
	}

	@Test
	public void testClockGateNeedsFloorAndSteadyRun() {
		var sut = defaults();
		var t = Instant.parse("2026-11-10T07:00:00Z"); // 10:00 Kampala
		// First reading and the settle period: unknown.
		for (var i = 0; i < ModeSchedule.CLOCK_SETTLE_CYCLES; i++) {
			assertNull(sut.plausibleTime(t, KAMPALA));
			t = t.plusSeconds(1);
		}
		assertEquals(LocalTime.of(10, 1), sut.plausibleTime(t, KAMPALA));
		// A fixed (not advancing) clock is still steady.
		assertEquals(LocalTime.of(10, 1), sut.plausibleTime(t, KAMPALA));
		// A backward step restarts the settle period.
		t = t.minusSeconds(30);
		assertNull(sut.plausibleTime(t, KAMPALA));
		for (var i = 1; i < ModeSchedule.CLOCK_SETTLE_CYCLES; i++) {
			t = t.plusSeconds(1);
			assertNull(sut.plausibleTime(t, KAMPALA));
		}
		t = t.plusSeconds(1);
		assertNotNull(sut.plausibleTime(t, KAMPALA));
		// A forward jump beyond the clock-step limit (an NTP correction) too.
		t = t.plusSeconds(DischargeGuard.CLOCK_STEP_MINUTES * 60 + 1);
		assertNull(sut.plausibleTime(t, KAMPALA));
		// An unreadable clock is unknown.
		assertNull(sut.plausibleTime(null, KAMPALA));
	}

	@Test
	public void testStaleClockStartIsUnknownUntilNtpAndSettle() {
		var sut = defaults();
		// gw-pi2 has no RTC: after a reboot the clock may start at the epoch.
		var t = Instant.EPOCH;
		for (var i = 0; i < 500; i++) {
			assertNull(sut.plausibleTime(t, KAMPALA));
			t = t.plusSeconds(1);
		}
		// Or at a stale fake-hwclock time; NTP then steps it forward by hours.
		t = Instant.parse("2026-11-08T02:00:00Z");
		for (var i = 0; i < 30; i++) {
			assertNull(sut.plausibleTime(t, KAMPALA));
			t = t.plusSeconds(1);
		}
		t = Instant.parse("2026-11-10T07:00:00Z");
		for (var i = 0; i < ModeSchedule.CLOCK_SETTLE_CYCLES; i++) {
			assertNull(sut.plausibleTime(t, KAMPALA));
			t = t.plusSeconds(1);
		}
		assertNotNull(sut.plausibleTime(t, KAMPALA));
	}

	@Test
	public void testMismatchQueuesOneWrite() {
		var sut = primed();
		assertStep(step(sut, DAY, 50, SBU, true), ModeScheduleState.WRITE_PENDING, SUB, SUB);
		assertEquals(SUB, sut.queuedTarget());
		// Still queued: no second write, however many cycles pass.
		for (var i = 0; i < 200; i++) {
			assertStep(step(sut, DAY, 50, SBU, true), ModeScheduleState.WRITE_PENDING, SUB, null);
		}
		sut.onExecute(ExecuteState.OK);
		assertNull(sut.queuedTarget());
		sut.verify(SUB);
		assertEquals(SafeWriteHandler.State.VERIFIED, sut.handlerState());
		assertStep(step(sut, DAY, 50, SUB, true), ModeScheduleState.IN_SYNC, SUB, null);
	}

	@Test
	public void testWaitsForConfirmedMains() {
		var sut = primed();
		for (var i = 0; i < 200; i++) {
			assertStep(step(sut, EVENING, 50, SUB, false), ModeScheduleState.WAITING_FOR_MAINS, SBU, null);
		}
		assertNull(sut.handlerState());
		// Mains is back: written only once it has held for the confirmation reads.
		for (var i = 1; i < ModeSchedule.MAINS_CONFIRM_READS; i++) {
			assertStep(step(sut, EVENING, 50, SUB, true), ModeScheduleState.WAITING_FOR_MAINS, SBU, null);
		}
		assertStep(step(sut, EVENING, 50, SUB, true), ModeScheduleState.WRITE_PENDING, SBU, SBU);
	}

	@Test
	public void testMainsBlipNeverWrites() {
		var sut = primed();
		for (var i = 0; i < 300; i++) {
			// State 2 for fewer fresh reads than the confirmation, then lost again.
			var mains = i % ModeSchedule.MAINS_CONFIRM_READS != 0;
			assertNull(step(sut, EVENING, 50, SUB, mains).write());
		}
		assertNull(sut.handlerState());
	}

	@Test
	public void testCachedMainsValueCountsAsOneRead() {
		var sut = primed();
		assertEquals(ModeScheduleState.WAITING_FOR_MAINS, step(sut, EVENING, 50, SUB, false).state());
		// One fresh read of state 2, then many cycles on the same cached value.
		sut.onMachineStateRead(true);
		for (var i = 0; i < 50; i++) {
			assertStep(sut.step(EVENING, 50, SUB, true, true, true, TIMEOUT, COOLDOWN),
					ModeScheduleState.WAITING_FOR_MAINS, SBU, null);
		}
		assertNull(sut.handlerState());
	}

	@Test
	public void testStartupHold() {
		var sut = defaults();
		for (var i = 1; i < ModeSchedule.STARTUP_HOLD_CYCLES; i++) {
			var result = step(sut, DAY, 50, SBU, true);
			assertNull(result.write());
			if (i >= ModeSchedule.MAINS_CONFIRM_READS) {
				assertEquals(ModeScheduleState.STARTUP_HOLD, result.state());
			}
		}
		assertStep(step(sut, DAY, 50, SBU, true), ModeScheduleState.WRITE_PENDING, SUB, SUB);
	}

	@Test
	public void testWithdrawnValueNeverBuildsAFrame() {
		var element = new GuardedWordElement(0xE204);
		element.arm(SUB);
		assertTrue(element.withdraw());
		assertNull(element.getNextWriteValueAndReset());
		// The bridge read the value before the withdraw, then converts it: no frame.
		element.arm(SUB);
		assertTrue(element.withdraw());
		assertNull(element.valueToRaw(SUB));
		// Taken first: the withdraw loses.
		element.arm(SUB);
		assertNotNull(element.getNextWriteValueAndReset());
		assertFalse(element.withdraw());
	}

	@Test
	public void testFrameUsesTheArmedValue() {
		var element = new GuardedWordElement(0xE204);
		element.arm(SUB);
		// Whatever value the bridge read outside the lock, the frame carries the armed one.
		var raw = element.valueToRaw(SBU);
		assertEquals(1, raw.length);
		assertEquals(SUB, raw[0].getValue());
		assertNull(element.valueToRaw(SBU)); // consumed once
	}

	@Test
	public void testWithdrawnWriteIsANoOpThroughTheRealFc16Task() {
		var element = new GuardedWordElement(0xE204);
		var outcome = new ArrayList<ExecuteState>();
		var task = new FC16WriteRegistersTask(outcome::add, 0xE204, element);
		element.arm(SUB);
		assertTrue(element.withdraw());
		// The bridge saw a value before the withdraw (simulated): still no frame.
		element.setNextWriteValue(SUB);
		assertEquals(ExecuteState.NO_OP, task.execute(null));
		assertEquals(List.of(ExecuteState.NO_OP), outcome);
		assertNull(element.getNextWriteValueAndReset());
	}

	@Test
	public void testQueuedWriteWithdrawnWhenTargetChangesOrTimeUnknown() {
		var sut = primed();
		assertStep(step(sut, LocalTime.of(17, 59, 59), 50, SBU, true), ModeScheduleState.WRITE_PENDING, SUB, SUB);
		// 18:00 before the bridge took the SUB write: withdrawn, SBU queued instead.
		assertStep(step(sut, LocalTime.of(18, 0), 50, SBU, true), ModeScheduleState.IN_SYNC, SBU, null);
		assertNull(sut.handlerState());
		assertNull(sut.writeElement().getNextWriteValueAndReset());

		var unknown = primed();
		assertStep(step(unknown, DAY, 50, SBU, true), ModeScheduleState.WRITE_PENDING, SUB, SUB);
		assertStep(step(unknown, null, 50, SBU, true), ModeScheduleState.WAITING_FOR_DATA, null, null);
		assertNull(unknown.handlerState());
		assertEquals(0, unknown.attemptsInWindow());
		assertNull(unknown.writeElement().getNextWriteValueAndReset());

		// Already taken by the bridge: kept in flight.
		var taken = primed();
		assertStep(step(taken, DAY, 50, SBU, true), ModeScheduleState.WRITE_PENDING, SUB, SUB);
		assertNotNull(taken.writeElement().getNextWriteValueAndReset());
		assertStep(step(taken, null, 50, SBU, true), ModeScheduleState.WRITE_PENDING, null, null);
		assertEquals(SafeWriteHandler.State.QUEUED, taken.handlerState());
	}

	@Test
	public void testMainsLostAfterTheBridgeTookTheWriteKeepsItInFlight() {
		var sut = primed();
		assertStep(step(sut, DAY, 50, SBU, true), ModeScheduleState.WRITE_PENDING, SUB, SUB);
		// The bridge takes the value, then mains drops before the execute callback.
		assertNotNull(sut.writeElement().getNextWriteValueAndReset());
		var lost = step(sut, DAY, 50, SBU, false);
		assertStep(lost, ModeScheduleState.WRITE_PENDING, SUB, null);
		assertFalse(lost.withdraw());
		assertEquals(SafeWriteHandler.State.QUEUED, sut.handlerState());
		assertEquals(1, sut.attemptsInWindow());
		sut.onExecute(ExecuteState.OK);
		sut.verify(SUB);
		assertEquals(SafeWriteHandler.State.VERIFIED, sut.handlerState());
	}

	@Test
	public void testBoundaryCrossingDetection() {
		var sut = defaults();
		assertTrue(sut.crossedBoundaryForward(LocalTime.of(17, 59, 59), LocalTime.of(18, 0)));
		assertTrue(sut.crossedBoundaryForward(LocalTime.of(17, 56), LocalTime.of(18, 1)));
		assertTrue(sut.crossedBoundaryForward(LocalTime.of(0, 29), LocalTime.of(0, 30)));
		assertFalse(sut.crossedBoundaryForward(LocalTime.of(18, 0), LocalTime.of(18, 0, 1)));
		assertFalse(sut.crossedBoundaryForward(LocalTime.of(17, 55), LocalTime.of(18, 1))); // jump > 5 min
		assertFalse(sut.crossedBoundaryForward(LocalTime.of(18, 1), LocalTime.of(17, 59))); // backward
		assertFalse(sut.crossedBoundaryForward(LocalTime.of(10, 0), LocalTime.of(19, 0))); // jump
		assertFalse(sut.crossedBoundaryForward(null, LocalTime.of(18, 0)));
		assertFalse(sut.crossedBoundaryForward(LocalTime.of(23, 59, 59), LocalTime.of(0, 0, 1))); // not a boundary
	}

	@Test
	public void testMainsLostWhileQueuedWithdrawsTheWrite() {
		var sut = primed();
		assertStep(step(sut, DAY, 50, SBU, true), ModeScheduleState.WRITE_PENDING, SUB, SUB);
		assertEquals(1, sut.attemptsInWindow());
		var lost = step(sut, DAY, 50, SBU, false);
		assertStep(lost, ModeScheduleState.WAITING_FOR_MAINS, SUB, null);
		assertTrue(lost.withdraw());
		assertNull(sut.queuedTarget());
		assertEquals(0, sut.attemptsInWindow()); // a withdrawn write is not an attempt
		for (var i = 1; i < ModeSchedule.MAINS_CONFIRM_READS; i++) {
			assertStep(step(sut, DAY, 50, SBU, true), ModeScheduleState.WAITING_FOR_MAINS, SUB, null);
		}
		assertStep(step(sut, DAY, 50, SBU, true), ModeScheduleState.WRITE_PENDING, SUB, SUB);
	}

	@Test
	public void testSbuNeedsPreconditionsSubDoesNot() {
		var sut = primed();
		// Evening, device in SUB, preconditions not met: blocked, never written.
		for (var i = 0; i < 200; i++) {
			assertStep(sut.step(EVENING, 50, SUB, true, true, false, TIMEOUT, COOLDOWN),
					ModeScheduleState.BLOCKED_SBU_PRECONDITIONS, SBU, null);
		}
		assertNull(sut.handlerState());
		// Preconditions met: SBU goes out.
		assertStep(sut.step(EVENING, 50, SUB, true, true, true, TIMEOUT, COOLDOWN), ModeScheduleState.WRITE_PENDING,
				SBU, SBU);

		// By day SUB is written without them.
		var day = primed();
		assertStep(day.step(DAY, 50, SBU, true, true, false, TIMEOUT, COOLDOWN), ModeScheduleState.WRITE_PENDING,
				SUB, SUB);
	}

	@Test
	public void testFailedWritesBackOffExponentiallyAndLatchPerWindow() {
		var sut = primed();
		List<Integer> writeCycles = new ArrayList<>();
		for (var i = 0; i < 5000; i++) {
			var result = step(sut, DAY, 50, SBU, true);
			if (result.write() != null) {
				writeCycles.add(i);
				// The device accepts the frame but keeps SBU: read-back mismatch.
				sut.onExecute(ExecuteState.OK);
				sut.verify(SBU);
				assertEquals(SafeWriteHandler.State.FAILED, sut.handlerState());
			} else {
				assertEquals(ModeScheduleState.WRITE_FAILED, result.state(), "cycle " + i);
			}
		}
		assertEquals(ModeSchedule.MAX_ATTEMPTS, writeCycles.size());
		for (var n = 1; n < writeCycles.size(); n++) {
			assertEquals(COOLDOWN << (n - 1), writeCycles.get(n) - writeCycles.get(n - 1));
		}
		// A target flip by a clock jump does not refill the budget.
		assertStep(step(sut, EVENING, 50, SUB, true), ModeScheduleState.WRITE_FAILED, SBU, null);
		assertStep(step(sut, LocalTime.of(17, 59, 30), 50, SBU, true), ModeScheduleState.WRITE_FAILED, SUB, null);
		// Crossing 18:00 forward: a fresh budget, written at once.
		assertStep(step(sut, LocalTime.of(18, 0), 50, SUB, true), ModeScheduleState.WRITE_PENDING, SBU, SBU);
		assertEquals(1, sut.attemptsInWindow());
	}

	@Test
	public void testLatchedFailureShowsInSyncIfTheDeviceMatches() {
		var sut = primed();
		for (var i = 0; i < 5000; i++) {
			if (step(sut, DAY, 50, SBU, true).write() != null) {
				sut.onExecute(ExecuteState.OK);
				sut.verify(SBU);
			}
		}
		assertEquals(ModeSchedule.MAX_ATTEMPTS, sut.attemptsInWindow());
		assertStep(step(sut, DAY, 50, SUB, true), ModeScheduleState.IN_SYNC, SUB, null);
		// Overridden again: still latched, no write until the boundary.
		assertStep(step(sut, DAY, 50, SBU, true), ModeScheduleState.WRITE_FAILED, SUB, null);
	}

	@Test
	public void testFailedExecuteBacksOff() {
		var sut = primed();
		assertStep(step(sut, DAY, 50, SBU, true), ModeScheduleState.WRITE_PENDING, SUB, SUB);
		sut.onExecute(new ExecuteState.Error(new Exception("timeout")));
		assertEquals(SafeWriteHandler.State.FAILED, sut.handlerState());
		for (var i = 1; i < COOLDOWN; i++) {
			assertStep(step(sut, DAY, 50, SBU, true), ModeScheduleState.WRITE_FAILED, SUB, null);
		}
		assertStep(step(sut, DAY, 50, SBU, true), ModeScheduleState.WRITE_PENDING, SUB, SUB);
	}

	@Test
	public void testMissingReadbackTimesOut() {
		var sut = primed();
		assertStep(step(sut, DAY, 50, SBU, true), ModeScheduleState.WRITE_PENDING, SUB, SUB);
		sut.onExecute(ExecuteState.OK);
		for (var i = 1; i < TIMEOUT; i++) {
			assertStep(step(sut, DAY, 50, SBU, true), ModeScheduleState.WRITE_PENDING, SUB, null);
		}
		assertStep(step(sut, DAY, 50, SBU, true), ModeScheduleState.WRITE_FAILED, SUB, null);
		assertEquals(SafeWriteHandler.State.FAILED, sut.handlerState());
	}

	@Test
	public void testOverrideAfterVerifyIsCorrectedAfterBackoff() {
		var sut = primed();
		step(sut, DAY, 50, SBU, true);
		sut.onExecute(ExecuteState.OK);
		sut.verify(SUB);
		assertStep(step(sut, DAY, 50, SUB, true), ModeScheduleState.IN_SYNC, SUB, null);
		// Someone sets SBU on the panel: no burst, one correction after the backoff.
		var writes = 0;
		for (var i = 0; i < COOLDOWN + 5; i++) {
			if (step(sut, DAY, 50, SBU, true).write() != null) {
				writes++;
			}
		}
		assertEquals(1, writes);
	}

	@Test
	public void testBoundaryChangeAfterVerifyWritesAtOnce() {
		var sut = primed();
		step(sut, LocalTime.of(17, 59), 50, SBU, true);
		sut.onExecute(ExecuteState.OK);
		sut.verify(SUB);
		assertStep(step(sut, LocalTime.of(17, 59, 30), 50, SUB, true), ModeScheduleState.IN_SYNC, SUB, null);
		// 18:00: the evening target is written in the same cycle.
		assertStep(step(sut, LocalTime.of(18, 0), 50, SUB, true), ModeScheduleState.WRITE_PENDING, SBU, SBU);
		sut.onExecute(ExecuteState.OK);
		sut.verify(SBU);
		assertStep(step(sut, LocalTime.of(18, 0, 1), 50, SBU, true), ModeScheduleState.IN_SYNC, SBU, null);
		// 00:30 next day: back to SUB.
		assertStep(step(sut, LocalTime.of(0, 30), 50, SBU, true), ModeScheduleState.WRITE_PENDING, SUB, SUB);
	}

	@Test
	public void testBackoffDoubles() {
		assertEquals(COOLDOWN, ModeSchedule.backoffCycles(1, COOLDOWN));
		assertEquals(2 * COOLDOWN, ModeSchedule.backoffCycles(2, COOLDOWN));
		assertEquals(4 * COOLDOWN, ModeSchedule.backoffCycles(3, COOLDOWN));
		assertEquals(8 * COOLDOWN, ModeSchedule.backoffCycles(4, COOLDOWN));
	}

	@Test
	public void testCallbacksWithoutWriteAreIgnored() {
		var sut = defaults();
		sut.onExecute(ExecuteState.OK);
		sut.verify(SUB);
		assertNull(sut.handlerState());
		assertNull(sut.queuedTarget());
	}
}
