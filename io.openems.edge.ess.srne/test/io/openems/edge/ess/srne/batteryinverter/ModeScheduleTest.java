package io.openems.edge.ess.srne.batteryinverter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
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
 * production defaults are SUB (3) from 00:30 to 18:00 and SBU (2) from 18:00 to
 * 00:30.
 */
public class ModeScheduleTest {

	private static final int SBU = 2;
	private static final int SUB = 3;
	private static final int TIMEOUT = 30;
	private static final int COOLDOWN = 60;
	private static final LocalTime DAY = LocalTime.of(10, 0);
	private static final LocalTime EVENING = LocalTime.of(19, 0);
	private static final ZoneId KAMPALA = ZoneId.of("Africa/Kampala");
	private static final LocalDate DATE = LocalDate.of(2026, 11, 10);

	private static LocalDateTime at(LocalTime time) {
		return time == null ? null : LocalDateTime.of(DATE, time);
	}

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
	private static ModeSchedule.Step step(ModeSchedule sut, LocalTime now, Integer soc, Integer e204, boolean mains) {
		sut.onMachineStateRead(mains);
		return sut.step(at(now), soc, e204, true, mains, true, TIMEOUT, COOLDOWN);
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
		// One owner of E204: a static outputPriority target conflicts with the
		// schedule.
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
			assertStep(sut.step(at(DAY), 50, SBU, false, true, true, TIMEOUT, COOLDOWN), ModeScheduleState.DISABLED,
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
		assertEquals(LocalTime.of(10, 1), sut.plausibleTime(t, KAMPALA).toLocalTime());
		// A fixed (not advancing) clock is still steady.
		assertEquals(LocalTime.of(10, 1), sut.plausibleTime(t, KAMPALA).toLocalTime());
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
			assertStep(sut.step(at(EVENING), 50, SUB, true, true, true, TIMEOUT, COOLDOWN),
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
		// Whatever value the bridge read outside the lock, the frame carries the armed
		// one.
		var raw = element.valueToRaw(SBU);
		assertEquals(1, raw.length);
		assertEquals(SUB, raw[0].getValue());
		assertNull(element.valueToRaw(SBU)); // consumed once
	}

	@Test
	public void testWithdrawnWriteIsANoOpThroughTheRealFc16Task() {
		var element = new GuardedWordElement(0xE204);
		var outcome = new ArrayList<ExecuteState>();
		final var task = new FC16WriteRegistersTask(outcome::add, 0xE204, element);
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
		assertEquals(0, unknown.failuresInWindow());
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
		assertEquals(0, sut.failuresInWindow());
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
		assertEquals(0, sut.failuresInWindow());
		var lost = step(sut, DAY, 50, SBU, false);
		assertStep(lost, ModeScheduleState.WAITING_FOR_MAINS, SUB, null);
		assertTrue(lost.withdraw());
		assertNull(sut.queuedTarget());
		assertEquals(0, sut.failuresInWindow()); // a withdrawn write is not a failure
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
			assertStep(sut.step(at(EVENING), 50, SUB, true, true, false, TIMEOUT, COOLDOWN),
					ModeScheduleState.BLOCKED_SBU_PRECONDITIONS, SBU, null);
		}
		assertNull(sut.handlerState());
		// Preconditions met: SBU goes out.
		assertStep(sut.step(at(EVENING), 50, SUB, true, true, true, TIMEOUT, COOLDOWN), ModeScheduleState.WRITE_PENDING,
				SBU, SBU);

		// By day SUB is written without them.
		var day = primed();
		assertStep(day.step(at(DAY), 50, SBU, true, true, false, TIMEOUT, COOLDOWN), ModeScheduleState.WRITE_PENDING,
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
		assertEquals(0, sut.failuresInWindow());
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
		assertEquals(ModeSchedule.MAX_ATTEMPTS, sut.failuresInWindow());
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

	// ---- Daytime SoC rule (SBU above 95 %, back to SUB below 85 %) ----

	// With the SUB-on-battery path enabled at 180 V (it is off by default).
	private static ModeSchedule socSchedule() {
		return ModeSchedule.fromConfig(true, SUB, SBU, "18:00", "00:30", -1, 95, 85, 10, 180);
	}

	private static ModeSchedule primedSoc() {
		var sut = socSchedule();
		for (var i = 0; i < ModeSchedule.STARTUP_HOLD_CYCLES; i++) {
			assertEquals(ModeScheduleState.IN_SYNC, step(sut, DAY, 50, SUB, true).state());
		}
		return sut;
	}

	private static ModeSchedule.Step socStep(ModeSchedule sut, LocalDateTime now, Integer soc, int e204,
			boolean sbuAllowed) {
		sut.onMachineStateRead(true);
		return sut.step(now, soc, e204, true, true, sbuAllowed, TIMEOUT, COOLDOWN);
	}

	// Plays a verified write and lets the schedule count it one second later.
	private static void verified(ModeSchedule sut, LocalDateTime now, int value, int soc) {
		sut.onExecute(ExecuteState.OK);
		sut.verify(value);
		assertEquals(ModeScheduleState.IN_SYNC, socStep(sut, now.plusSeconds(1), soc, value, true).state());
	}

	@Test
	public void testSocRuleValidation() {
		assertNull(socSchedule().getInvalidReason());
		assertTrue(socSchedule().isSocRuleActive());
		assertFalse(defaults().isSocRuleActive());
		assertNotNull(ModeSchedule.fromConfig(true, SUB, SBU, "18:00", "00:30", -1, 95, -1, 10).getInvalidReason());
		assertNotNull(ModeSchedule.fromConfig(true, SUB, SBU, "18:00", "00:30", -1, -1, 85, 10).getInvalidReason());
		assertNotNull(ModeSchedule.fromConfig(true, SUB, SBU, "18:00", "00:30", -1, 101, 85, 10).getInvalidReason());
		assertNotNull(ModeSchedule.fromConfig(true, SUB, SBU, "18:00", "00:30", -1, 95, 9, 10).getInvalidReason());
		assertNotNull(ModeSchedule.fromConfig(true, SUB, SBU, "18:00", "00:30", -1, 85, 85, 10).getInvalidReason());
		assertNotNull(ModeSchedule.fromConfig(true, SUB, SBU, "18:00", "00:30", -1, 85, 95, 10).getInvalidReason());
		assertNotNull(ModeSchedule.fromConfig(true, SBU, SUB, "18:00", "00:30", -1, 95, 85, 10).getInvalidReason());
		assertNotNull(ModeSchedule.fromConfig(true, SUB, SBU, "18:00", "00:30", -1, 95, 85, -1).getInvalidReason());
		assertNull(ModeSchedule.fromConfig(true, SUB, SBU, "18:00", "00:30", -1, 100, 10, 0).getInvalidReason());
	}

	@Test
	public void testSocRuleOffKeepsTheWindowTarget() {
		var sut = defaults();
		for (var soc : new int[] { 0, 50, 95, 100 }) {
			assertEquals(SUB, sut.targetFor(at(DAY), soc, SBU));
			assertEquals(ModeScheduleReason.WINDOW, sut.reason());
			assertEquals(SBU, sut.targetFor(at(EVENING), soc, SUB));
		}
	}

	@Test
	public void testHysteresisBothDirections() {
		var sut = primedSoc();
		var t = at(LocalTime.of(11, 0));
		assertStep(socStep(sut, t, 94, SUB, true), ModeScheduleState.IN_SYNC, SUB, null);
		assertEquals(ModeScheduleReason.LOW_SOC, sut.reason());
		// At the upper threshold: SBU.
		assertStep(socStep(sut, t.plusSeconds(10), 95, SUB, true), ModeScheduleState.WRITE_PENDING, SBU, SBU);
		assertEquals(ModeScheduleReason.HIGH_SOC, sut.reason());
		verified(sut, t.plusSeconds(10), SBU, 95);
		// Between the thresholds the SBU target is held.
		assertStep(socStep(sut, t.plusMinutes(20), 85, SBU, true), ModeScheduleState.IN_SYNC, SBU, null);
		// Below the lower threshold: back to SUB.
		assertStep(socStep(sut, t.plusMinutes(21), 84, SBU, true), ModeScheduleState.WRITE_PENDING, SUB, SUB);
		assertEquals(ModeScheduleReason.LOW_SOC, sut.reason());
		verified(sut, t.plusMinutes(21), SUB, 84);
		// Between the thresholds again: SUB is held.
		assertStep(socStep(sut, t.plusMinutes(40), 94, SUB, true), ModeScheduleState.IN_SYNC, SUB, null);
	}

	@Test
	public void testSocUnreadableKeepsTheTargetAndNeverWrites() {
		var sut = primedSoc();
		var t = at(LocalTime.of(11, 0));
		assertEquals(SBU, socStep(sut, t, 96, SUB, true).target());
		verified(sut, t, SBU, 96);
		for (var soc : new Integer[] { null, -1, 101 }) {
			assertStep(socStep(sut, t.plusMinutes(30), soc, SUB, true), ModeScheduleState.WAITING_FOR_DATA, SBU, null);
			assertEquals(ModeScheduleReason.HIGH_SOC, sut.reason());
		}
	}

	@Test
	public void testSocDrivenSbuNeedsThePreconditions() {
		var sut = primedSoc();
		var t = at(LocalTime.of(11, 0));
		assertStep(socStep(sut, t, 96, SUB, false), ModeScheduleState.BLOCKED_SBU_PRECONDITIONS, SBU, null);
		assertNull(sut.handlerState());
	}

	@Test
	public void testMinimumDwellAfterASocSwitch() {
		var sut = primedSoc();
		var t = at(LocalTime.of(11, 0));
		assertEquals(SBU, socStep(sut, t, 96, SUB, true).write());
		verified(sut, t, SBU, 96); // the switch is counted at t + 1 s
		var switched = t.plusSeconds(1);
		assertStep(socStep(sut, switched.plusMinutes(5), 80, SBU, true), ModeScheduleState.MIN_DWELL, SUB, null);
		assertStep(socStep(sut, switched.plusMinutes(9).plusSeconds(59), 80, SBU, true), ModeScheduleState.MIN_DWELL,
				SUB, null);
		assertStep(socStep(sut, switched.plusMinutes(10), 80, SBU, true), ModeScheduleState.WRITE_PENDING, SUB, SUB);
	}

	@Test
	public void testWindowSwitchIsNotHeldByTheDwell() {
		var sut = primedSoc();
		var t = at(LocalTime.of(17, 50));
		assertEquals(SBU, socStep(sut, t, 96, SUB, true).write());
		verified(sut, t, SBU, 96);
		assertEquals(SUB, socStep(sut, t.plusMinutes(1), 80, SBU, true).target()); // dwelling
		assertEquals(SUB, socStep(sut, t.plusMinutes(2), 80, SBU, true).target());
		// 18:00 is a window switch: evening SBU, the device already is SBU.
		assertStep(socStep(sut, at(LocalTime.of(18, 0)), 80, SBU, true), ModeScheduleState.IN_SYNC, SBU, null);
		assertEquals(ModeScheduleReason.WINDOW, sut.reason());
	}

	@Test
	public void testEveningUnchangedAndNextDayStartsInTheDaytimePriority() {
		var sut = primedSoc();
		for (var soc : new int[] { 10, 50, 100 }) {
			assertEquals(SBU, socStep(sut, at(EVENING), soc, SBU, true).target());
			assertEquals(ModeScheduleReason.WINDOW, sut.reason());
		}
		// Next morning at 90 % (between the thresholds): the daytime priority.
		var morning = LocalDateTime.of(DATE.plusDays(1), LocalTime.of(8, 0));
		assertEquals(SUB, socStep(sut, morning, 90, SBU, true).target());
		assertEquals(ModeScheduleReason.LOW_SOC, sut.reason());
	}

	@Test
	public void testSuccessfulSwitchesDoNotUseTheFailureBudgetAndHitTheDailyCeiling() {
		var sut = primedSoc();
		var device = SUB;
		var t = at(LocalTime.of(8, 0));
		var writes = 0;
		for (var k = 0; k < ModeSchedule.MAX_DAILY_WRITES + 1; k++) {
			t = t.plusMinutes(11);
			var soc = k % 2 == 0 ? 96 : 80;
			var result = socStep(sut, t, soc, device, true);
			if (k == ModeSchedule.MAX_DAILY_WRITES) {
				assertStep(result, ModeScheduleState.DAILY_WRITE_LIMIT, device == SUB ? SBU : SUB, null);
				break;
			}
			assertNotNull(result.write(), "switch " + k + " state " + result.state());
			device = result.write();
			verified(sut, t, device, soc);
			writes++;
		}
		assertEquals(ModeSchedule.MAX_DAILY_WRITES, writes);
		assertEquals(ModeSchedule.MAX_DAILY_WRITES, sut.writesTodayForTest(DATE));
		assertEquals(0, sut.failuresInWindow());
		// The next local day has a fresh ceiling.
		var nextDay = LocalDateTime.of(DATE.plusDays(1), LocalTime.of(10, 0));
		assertNotNull(socStep(sut, nextDay, 96, SUB, true).write());
	}

	@Test
	public void testRestartInTheBandKeepsTheDeviceMode() {
		// Device already in SBU at 90 % (between 85 and 95): stays SBU, never writes.
		var sbu = socSchedule();
		var t = at(LocalTime.of(14, 0));
		for (var i = 0; i < ModeSchedule.STARTUP_HOLD_CYCLES + 50; i++) {
			assertStep(socStep(sbu, t, 90, SBU, true), ModeScheduleState.IN_SYNC, SBU, null);
		}
		assertEquals(ModeScheduleReason.HIGH_SOC, sbu.reason());
		// Device in SUB at 90 %: stays SUB.
		var sub = socSchedule();
		for (var i = 0; i < ModeSchedule.STARTUP_HOLD_CYCLES + 50; i++) {
			assertStep(socStep(sub, t, 90, SUB, true), ModeScheduleState.IN_SYNC, SUB, null);
		}
		assertEquals(ModeScheduleReason.LOW_SOC, sub.reason());
		// Device in SBU but SoC below the lower threshold: back to SUB.
		var low = socSchedule();
		assertEquals(SUB, socStep(low, t, 80, SBU, true).target());
	}

	@Test
	public void testWriteVerifiedWhileTheClockIsGatedIsCounted() {
		var sut = primedSoc();
		var t = at(LocalTime.of(11, 0));
		assertEquals(SBU, socStep(sut, t, 96, SUB, true).write());
		sut.onExecute(ExecuteState.OK);
		sut.verify(SBU);
		// The clock is gated (unknown) when the outcome is counted.
		sut.onMachineStateRead(true);
		assertEquals(ModeScheduleState.WAITING_FOR_DATA,
				sut.step(null, 96, SBU, true, true, true, TIMEOUT, COOLDOWN).state());
		assertEquals(1, sut.writesTodayForTest(DATE));
		// The dwell runs from the queue time.
		assertStep(socStep(sut, t.plusMinutes(5), 80, SBU, true), ModeScheduleState.MIN_DWELL, SUB, null);
		assertStep(socStep(sut, t.plusMinutes(10), 80, SBU, true), ModeScheduleState.WRITE_PENDING, SUB, SUB);
	}

	@Test
	public void testFirstSocSwitchAfterActivationWaitsTheDwell() {
		var sut = socSchedule();
		var t = at(LocalTime.of(14, 0));
		ModeSchedule.Step result = null;
		for (var i = 0; i < ModeSchedule.STARTUP_HOLD_CYCLES + 10; i++) {
			result = socStep(sut, t, 96, SUB, true);
			assertNull(result.write());
		}
		assertStep(result, ModeScheduleState.MIN_DWELL, SBU, null);
		assertStep(socStep(sut, t.plusMinutes(10), 96, SUB, true), ModeScheduleState.WRITE_PENDING, SBU, SBU);
	}

	// A device that reverts E204 to `other` right after every verified write.
	// Returns
	// the cycle index of each write made in `cycles` cycles at a fixed time.
	private static List<Integer> fight(ModeSchedule sut, LocalTime time, int target, int other, int cycles) {
		List<Integer> writes = new ArrayList<>();
		for (var i = 0; i < cycles; i++) {
			var result = step(sut, time, 50, other, true);
			if (result.write() != null) {
				assertEquals(target, result.write());
				sut.onExecute(ExecuteState.OK);
				sut.verify(target);
				writes.add(i);
			}
		}
		return writes;
	}

	@Test
	public void testRepeatedOverrideBacksOffAndHoldsPerWindow() {
		var sut = primed();
		var writes = fight(sut, LocalTime.of(10, 0), SUB, SBU, 2000);
		// The switch, then 3 re-corrections 60 / 120 / 240 cycles apart.
		assertEquals(1 + ModeSchedule.MAX_OVERRIDES_PER_WINDOW, writes.size());
		for (var n = 1; n < writes.size(); n++) {
			assertEquals(COOLDOWN << (n - 1), writes.get(n) - writes.get(n - 1));
		}
		assertStep(step(sut, LocalTime.of(10, 0), 50, SBU, true), ModeScheduleState.OVERRIDDEN, SUB, null);
		assertStep(step(sut, LocalTime.of(17, 59, 59), 50, SBU, true), ModeScheduleState.OVERRIDDEN, SUB, null);
		// The next boundary: a fresh override budget.
		assertEquals(1 + ModeSchedule.MAX_OVERRIDES_PER_WINDOW, fight(sut, LocalTime.of(18, 0), SBU, SUB, 2000).size());
		assertEquals(2 * (1 + ModeSchedule.MAX_OVERRIDES_PER_WINDOW), sut.writesTodayForTest(DATE));
	}

	@Test
	public void testOverridesCannotSpendTheDailyCeiling() {
		var sut = primed();
		assertEquals(4, fight(sut, LocalTime.of(0, 10), SBU, SUB, 2000).size()); // night window
		step(sut, LocalTime.of(0, 29, 59), 50, SUB, true);
		assertEquals(4, fight(sut, LocalTime.of(0, 30), SUB, SBU, 2000).size()); // day window
		step(sut, LocalTime.of(17, 59, 59), 50, SBU, true);
		// Evening: the window switch still goes out, but no more re-corrections today.
		assertEquals(1, fight(sut, LocalTime.of(18, 0), SBU, SUB, 2000).size());
		assertStep(step(sut, LocalTime.of(18, 0), 50, SUB, true), ModeScheduleState.OVERRIDDEN, SBU, null);
		assertTrue(sut.writesTodayForTest(DATE) < ModeSchedule.MAX_DAILY_WRITES);
	}

	// ---- SUB write on battery (state 3) when the grid is present ----

	private static final int GRID_UP = 230_000; // mV

	private static ModeSchedule.Step batteryStep(ModeSchedule sut, LocalDateTime now, int soc, int e204, int l1, int l2,
			int l3) {
		sut.onMachineStateRead(Integer.valueOf(ModeSchedule.ON_BATTERY_STATE));
		sut.onGridVoltageRead(1, l1);
		sut.onGridVoltageRead(2, l2);
		sut.onGridVoltageRead(3, l3);
		return sut.step(now, soc, e204, true, false, true, TIMEOUT, COOLDOWN);
	}

	// SBU written at 96 %, verified; returns the time of the switch.
	private static LocalDateTime switchedToSbu(ModeSchedule sut) {
		var t = at(LocalTime.of(11, 0));
		assertEquals(SBU, socStep(sut, t, 96, SUB, true).write());
		verified(sut, t, SBU, 96);
		return t;
	}

	@Test
	public void testSubWriteGoesOutOnBatteryWithTheGridPresent() {
		var sut = primedSoc();
		var later = switchedToSbu(sut).plusMinutes(30);
		// The unit left the line at E020 (state 3); SoC fell below 85 %.
		for (var i = 1; i < ModeSchedule.MAINS_CONFIRM_READS; i++) {
			assertStep(batteryStep(sut, later, 80, SBU, GRID_UP, GRID_UP, GRID_UP), ModeScheduleState.WAITING_FOR_MAINS,
					SUB, null);
		}
		assertStep(batteryStep(sut, later, 80, SBU, GRID_UP, GRID_UP, GRID_UP), ModeScheduleState.WRITE_PENDING, SUB,
				SUB);
		// The grid drops before the bridge took it: withdrawn.
		var lost = batteryStep(sut, later, 80, SBU, 0, 0, 0);
		assertStep(lost, ModeScheduleState.WAITING_FOR_MAINS, SUB, null);
		assertTrue(lost.withdraw());
	}

	@Test
	public void testSubWriteOnBatteryWaitsWithoutTheGrid() {
		var sut = primedSoc();
		var later = switchedToSbu(sut).plusMinutes(30);
		for (var i = 0; i < 50; i++) {
			assertStep(batteryStep(sut, later, 80, SBU, 0, 0, 0), ModeScheduleState.WAITING_FOR_MAINS, SUB, null);
			// One phase below the threshold is not "grid present" either.
			assertStep(batteryStep(sut, later, 80, SBU, GRID_UP, GRID_UP, 150_000), ModeScheduleState.WAITING_FOR_MAINS,
					SUB, null);
		}
		assertNull(sut.queuedTarget());
	}

	@Test
	public void testSbuWriteOnBatteryStaysOnTheStateTwoGate() {
		var sut = primedSoc();
		var t = at(LocalTime.of(11, 0));
		for (var i = 0; i < 50; i++) {
			assertStep(batteryStep(sut, t, 96, SUB, GRID_UP, GRID_UP, GRID_UP), ModeScheduleState.WAITING_FOR_MAINS,
					SBU, null);
		}
	}

	@Test
	public void testSubOnBatteryPathIsOffByDefault() {
		assertFalse(defaults().isGridPresentPathEnabled());
		assertFalse(
				ModeSchedule.fromConfig(true, SUB, SBU, "18:00", "00:30", -1, 95, 85, 10).isGridPresentPathEnabled());
		// Window switch to SUB while the unit runs on battery with the grid up: waits
		// for
		// mains as before.
		var sut = defaults();
		for (var i = 0; i < ModeSchedule.STARTUP_HOLD_CYCLES; i++) {
			step(sut, DAY, 50, SUB, true);
		}
		for (var i = 0; i < 50; i++) {
			assertStep(batteryStep(sut, at(LocalTime.of(0, 40)), 80, SBU, GRID_UP, GRID_UP, GRID_UP),
					ModeScheduleState.WAITING_FOR_MAINS, SUB, null);
		}
	}

	@Test
	public void testSubOnBatteryPathCanBeTurnedOff() {
		var sut = ModeSchedule.fromConfig(true, SUB, SBU, "18:00", "00:30", -1, 95, 85, 10, -1);
		assertFalse(sut.isGridPresentPathEnabled());
		for (var i = 0; i < ModeSchedule.STARTUP_HOLD_CYCLES; i++) {
			step(sut, DAY, 50, SUB, true);
		}
		var t = at(LocalTime.of(11, 0));
		for (var i = 0; i < 50; i++) {
			assertStep(batteryStep(sut, t, 80, SBU, GRID_UP, GRID_UP, GRID_UP), ModeScheduleState.WAITING_FOR_MAINS,
					SUB, null);
		}
	}

	@Test
	public void testGridPresentMinVoltageValidation() {
		assertNull(ModeSchedule.fromConfig(true, SUB, SBU, "18:00", "00:30", -1, -1, -1, 10, 180).getInvalidReason());
		assertNull(ModeSchedule.fromConfig(true, SUB, SBU, "18:00", "00:30", -1, -1, -1, 10, -1).getInvalidReason());
		assertNotNull(ModeSchedule.fromConfig(true, SUB, SBU, "18:00", "00:30", -1, -1, -1, 10, 99).getInvalidReason());
		assertNotNull(
				ModeSchedule.fromConfig(true, SUB, SBU, "18:00", "00:30", -1, -1, -1, 10, 261).getInvalidReason());
	}

	// ---- Evening reserve (SoC rule 95 / 85, reserve 90 % from 14:00 to 18:00)
	// ----

	private static ModeSchedule reserveSchedule() {
		return ModeSchedule.fromConfig(true, SUB, SBU, "18:00", "00:30", -1, 95, 85, 10, -1, 90, "14:00");
	}

	// Past the startup hold and the dwell from activation (at 10:00), device in SBU
	// at
	// 88 % (seeded high).
	private static ModeSchedule primedReserve() {
		var sut = reserveSchedule();
		for (var i = 0; i < ModeSchedule.STARTUP_HOLD_CYCLES; i++) {
			assertEquals(ModeScheduleState.IN_SYNC, step(sut, DAY, 88, SBU, true).state());
		}
		assertEquals(ModeScheduleReason.HIGH_SOC, sut.reason());
		return sut;
	}

	@Test
	public void testEveningReserveValidation() {
		assertNull(reserveSchedule().getInvalidReason());
		assertTrue(reserveSchedule().isEveningReserveActive());
		assertFalse(socSchedule().isEveningReserveActive());
		assertFalse(defaults().isEveningReserveActive());
		for (var bad : new int[] { 0, 19, 101 }) {
			assertNotNull(ModeSchedule.fromConfig(true, SUB, SBU, "18:00", "00:30", -1, 95, 85, 10, -1, bad, "14:00")
					.getInvalidReason(), "" + bad);
		}
		for (var bad : new String[] { null, "2pm", "18:00", "19:00", "00:10" }) {
			assertNotNull(ModeSchedule.fromConfig(true, SUB, SBU, "18:00", "00:30", -1, 95, 85, 10, -1, 90, bad)
					.getInvalidReason(), "" + bad);
		}
		assertNull(ModeSchedule.fromConfig(true, SUB, SBU, "18:00", "00:30", -1, 95, 85, 10, -1, 20, "00:30")
				.getInvalidReason());
		assertNull(ModeSchedule.fromConfig(true, SUB, SBU, "18:00", "00:30", -1, 95, 85, 10, -1, -1, "nonsense")
				.getInvalidReason());
	}

	@Test
	public void testReserveWindowBoundaries() {
		var sut = reserveSchedule();
		assertFalse(sut.isInReserveWindow(LocalTime.of(13, 59, 59)));
		assertTrue(sut.isInReserveWindow(LocalTime.of(14, 0)));
		assertTrue(sut.isInReserveWindow(LocalTime.of(17, 59, 59)));
		assertFalse(sut.isInReserveWindow(LocalTime.of(18, 0)));
		assertFalse(sut.isInReserveWindow(LocalTime.of(0, 0)));
		assertFalse(socSchedule().isInReserveWindow(LocalTime.of(15, 0)));

		var primed = primedReserve();
		assertEquals(SBU, primed.targetFor(at(LocalTime.of(13, 59, 59)), 88, SBU));
		assertEquals(ModeScheduleReason.HIGH_SOC, primed.reason());
		assertEquals(SUB, primed.targetFor(at(LocalTime.of(14, 0)), 88, SBU));
		assertEquals(ModeScheduleReason.EVENING_RESERVE, primed.reason());
		assertEquals(SUB, primed.targetFor(at(LocalTime.of(17, 59, 59)), 88, SUB));
		assertEquals(ModeScheduleReason.EVENING_RESERVE, primed.reason());
		assertEquals(SBU, primed.targetFor(at(LocalTime.of(18, 0)), 88, SUB));
		assertEquals(ModeScheduleReason.WINDOW, primed.reason());
	}

	@Test
	public void testReserveAndTheSocRuleHysteresis() {
		var sut = primedReserve();
		var t = at(LocalTime.of(15, 0));
		// Below the reserve: SUB, even though the SoC rule was high.
		assertEquals(SUB, sut.targetFor(t, 89, SBU));
		assertEquals(ModeScheduleReason.EVENING_RESERVE, sut.reason());
		// Unreadable SoC keeps the reserve decision.
		assertEquals(SUB, sut.targetFor(t, null, SUB));
		assertEquals(ModeScheduleReason.EVENING_RESERVE, sut.reason());
		// Back at the reserve: the normal SoC rule, which must reach 95 % again.
		assertEquals(SUB, sut.targetFor(t, 90, SUB));
		assertEquals(ModeScheduleReason.LOW_SOC, sut.reason());
		assertEquals(SUB, sut.targetFor(t, 94, SUB));
		assertEquals(SBU, sut.targetFor(t, 95, SUB));
		assertEquals(ModeScheduleReason.HIGH_SOC, sut.reason());
		// In the band above the reserve the SoC rule holds SBU.
		assertEquals(SBU, sut.targetFor(t, 91, SBU));
		// Dropping below the reserve overrides it again.
		assertEquals(SUB, sut.targetFor(t, 89, SBU));
		assertEquals(ModeScheduleReason.EVENING_RESERVE, sut.reason());
	}

	@Test
	public void testReserveOffByDefaultKeepsTheSocRule() {
		var sut = primedSoc();
		var t = at(LocalTime.of(15, 0));
		assertEquals(SBU, sut.targetFor(t, 96, SUB));
		for (var soc : new int[] { 89, 86, 85 }) {
			assertEquals(SBU, sut.targetFor(t, soc, SBU));
			assertEquals(ModeScheduleReason.HIGH_SOC, sut.reason());
		}
	}

	@Test
	public void testReserveWritesSubAtTwoAndTheEveningSwitchIsNotHeld() {
		var sut = primedReserve();
		// 14:00 at 88 %: the reserve switch to SUB goes out (SoC-driven, the dwell from
		// activation at 10:00 has passed).
		var two = at(LocalTime.of(14, 0));
		assertStep(socStep(sut, two, 88, SBU, true), ModeScheduleState.WRITE_PENDING, SUB, SUB);
		verified(sut, two, SUB, 88);
		// 17:59: still below the reserve, SUB held.
		assertStep(socStep(sut, at(LocalTime.of(17, 59)), 89, SUB, true), ModeScheduleState.IN_SYNC, SUB, null);
		// 18:00: the evening switch goes out at once, not held by the dwell.
		assertStep(socStep(sut, at(LocalTime.of(18, 0)), 89, SUB, true), ModeScheduleState.WRITE_PENDING, SBU, SBU);
		assertEquals(ModeScheduleReason.WINDOW, sut.reason());
	}

	@Test
	public void testReserveSwitchIsSocDrivenAndWaitsTheDwell() {
		var sut = primedReserve();
		var t = at(LocalTime.of(15, 0));
		// 95 %: SBU (from SUB) at 15:00.
		assertStep(socStep(sut, t, 95, SUB, true), ModeScheduleState.WRITE_PENDING, SBU, SBU);
		verified(sut, t, SBU, 95);
		// Five minutes later at 89 %: the reserve wants SUB but waits the dwell.
		assertStep(socStep(sut, t.plusMinutes(5), 89, SBU, true), ModeScheduleState.MIN_DWELL, SUB, null);
		assertEquals(ModeScheduleReason.EVENING_RESERVE, sut.reason());
		assertStep(socStep(sut, t.plusMinutes(11), 89, SBU, true), ModeScheduleState.WRITE_PENDING, SUB, SUB);
	}

	// ---- Evening reserve hold (reserve 90 % from 14:00, hold on) ----

	private static ModeSchedule holdSchedule() {
		return ModeSchedule.fromConfig(true, SUB, SBU, "18:00", "00:30", -1, 95, 85, 10, -1, 90, "14:00", true);
	}

	private static ModeSchedule primedHold() {
		var sut = holdSchedule();
		for (var i = 0; i < ModeSchedule.STARTUP_HOLD_CYCLES; i++) {
			assertEquals(ModeScheduleState.IN_SYNC, step(sut, DAY, 88, SBU, true).state());
		}
		assertEquals(ModeScheduleReason.HIGH_SOC, sut.reason());
		return sut;
	}

	@Test
	public void testReserveHoldValidationAndDefaults() {
		assertNull(holdSchedule().getInvalidReason());
		assertTrue(holdSchedule().isEveningReserveHoldActive());
		// Off by default, also through the overload without the flag.
		assertFalse(reserveSchedule().isEveningReserveHoldActive());
		assertFalse(ModeSchedule.fromConfig(true, SUB, SBU, "18:00", "00:30", -1, 95, 85, 10, -1, 90, "14:00", false)
				.isEveningReserveHoldActive());
		// Without a reserve the hold is inert, never invalid.
		var inert = ModeSchedule.fromConfig(true, SUB, SBU, "18:00", "00:30", -1, 95, 85, 10, -1, -1, "14:00", true);
		assertNull(inert.getInvalidReason());
		assertFalse(inert.isEveningReserveHoldActive());
		var disabled = ModeSchedule.fromConfig(false, SUB, SBU, "18:00", "00:30", -1, 95, 85, 10, -1, 90, "14:00",
				true);
		assertFalse(disabled.isActive());
		assertFalse(disabled.isEveningReserveHoldActive());
	}

	@Test
	public void testReserveHoldKeepsSubUntilEveningStartThenEveningSbu() {
		var sut = primedHold();
		// 12 Nov at Sezibwa: the reserve was reached in the afternoon. Without the hold
		// the SoC rule went back to SBU at 95 % and the battery drained before 18:00.
		assertEquals(SBU, sut.targetFor(at(LocalTime.of(13, 59, 59)), 88, SBU));
		assertEquals(ModeScheduleReason.HIGH_SOC, sut.reason());
		assertEquals(SUB, sut.targetFor(at(LocalTime.of(14, 0)), 88, SBU));
		assertEquals(ModeScheduleReason.EVENING_RESERVE, sut.reason());
		// Reserve reached (boundary: exactly 90 %): held, not the SoC rule.
		assertEquals(SUB, sut.targetFor(at(LocalTime.of(15, 0)), 90, SUB));
		assertEquals(ModeScheduleReason.EVENING_RESERVE_HOLD, sut.reason());
		for (var soc : new int[] { 95, 100, 91 }) {
			assertEquals(SUB, sut.targetFor(at(LocalTime.of(16, 0)), soc, SUB), "soc " + soc);
			assertEquals(ModeScheduleReason.EVENING_RESERVE_HOLD, sut.reason());
		}
		// An unreadable SoC keeps the hold.
		assertEquals(SUB, sut.targetFor(at(LocalTime.of(16, 30)), null, SUB));
		assertEquals(ModeScheduleReason.EVENING_RESERVE_HOLD, sut.reason());
		// Below the reserve again: the reserve itself (same target); above: still held.
		assertEquals(SUB, sut.targetFor(at(LocalTime.of(17, 0)), 89, SUB));
		assertEquals(ModeScheduleReason.EVENING_RESERVE, sut.reason());
		assertEquals(SUB, sut.targetFor(at(LocalTime.of(17, 30)), 96, SUB));
		assertEquals(ModeScheduleReason.EVENING_RESERVE_HOLD, sut.reason());
		assertEquals(SUB, sut.targetFor(at(LocalTime.of(17, 59, 59)), 96, SUB));
		assertEquals(ModeScheduleReason.EVENING_RESERVE_HOLD, sut.reason());
		// eveningStart: the evening priority.
		assertEquals(SBU, sut.targetFor(at(LocalTime.of(18, 0)), 96, SUB));
		assertEquals(ModeScheduleReason.WINDOW, sut.reason());
		// Next day before the reserve window: the SoC rule again, the hold is reset.
		var nextDay = LocalDateTime.of(DATE.plusDays(1), LocalTime.of(13, 0));
		assertEquals(SBU, sut.targetFor(nextDay, 96, SBU));
		assertEquals(ModeScheduleReason.HIGH_SOC, sut.reason());
		assertEquals(SBU, sut.targetFor(nextDay.plusMinutes(30), 90, SBU));
		assertEquals(ModeScheduleReason.HIGH_SOC, sut.reason());
		// Entering the reserve window already at the reserve: held at once.
		assertEquals(SUB, sut.targetFor(nextDay.plusHours(1), 96, SBU));
		assertEquals(ModeScheduleReason.EVENING_RESERVE_HOLD, sut.reason());
	}

	@Test
	public void testReserveHoldWritesNothingWhenReachedAndEveningSwitchIsNotHeld() {
		var sut = primedHold();
		var two = at(LocalTime.of(14, 0));
		assertStep(socStep(sut, two, 88, SBU, true), ModeScheduleState.WRITE_PENDING, SUB, SUB);
		verified(sut, two, SUB, 88);
		// Reserve reached at 16:00 and full at 17:00: SUB stays, no write.
		assertStep(socStep(sut, at(LocalTime.of(16, 0)), 90, SUB, true), ModeScheduleState.IN_SYNC, SUB, null);
		assertEquals(ModeScheduleReason.EVENING_RESERVE_HOLD, sut.reason());
		assertStep(socStep(sut, at(LocalTime.of(17, 0)), 100, SUB, true), ModeScheduleState.IN_SYNC, SUB, null);
		assertStep(socStep(sut, at(LocalTime.of(17, 59)), 100, SUB, true), ModeScheduleState.IN_SYNC, SUB, null);
		// 18:00: the evening SBU goes out at once, not held by the dwell.
		assertStep(socStep(sut, at(LocalTime.of(18, 0)), 100, SUB, true), ModeScheduleState.WRITE_PENDING, SBU, SBU);
		assertEquals(ModeScheduleReason.WINDOW, sut.reason());
	}

	@Test
	public void testReserveHoldSwitchRespectsDwellAndMainsGate() {
		var sut = primedHold();
		// 13:55 at 96 %: SBU by the SoC rule, verified.
		var t = at(LocalTime.of(13, 55));
		assertStep(socStep(sut, t, 96, SUB, true), ModeScheduleState.WRITE_PENDING, SBU, SBU);
		verified(sut, t, SBU, 96);
		// 14:00: the hold wants SUB, but the switch is SoC-driven and waits the dwell.
		assertStep(socStep(sut, at(LocalTime.of(14, 0)), 96, SBU, true), ModeScheduleState.MIN_DWELL, SUB, null);
		assertEquals(ModeScheduleReason.EVENING_RESERVE_HOLD, sut.reason());
		// After the dwell, without mains: no write.
		sut.onMachineStateRead(false);
		assertStep(sut.step(at(LocalTime.of(14, 6)), 96, SBU, true, false, true, TIMEOUT, COOLDOWN),
				ModeScheduleState.WAITING_FOR_MAINS, SUB, null);
		// Mains confirmed again: the SUB write goes out.
		for (var i = 0; i < ModeSchedule.MAINS_CONFIRM_READS - 1; i++) {
			socStep(sut, at(LocalTime.of(14, 7)), 96, SBU, true);
		}
		assertStep(socStep(sut, at(LocalTime.of(14, 7)), 96, SBU, true), ModeScheduleState.WRITE_PENDING, SUB, SUB);
		// Control disabled: never a write.
		var off = primedHold();
		assertStep(off.step(at(LocalTime.of(15, 0)), 96, SBU, false, true, true, TIMEOUT, COOLDOWN),
				ModeScheduleState.DISABLED, SUB, null);
	}
}
