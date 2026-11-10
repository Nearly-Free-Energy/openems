package io.openems.edge.ess.srne.batteryinverter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalTime;

import org.junit.jupiter.api.Test;

import io.openems.edge.bridge.modbus.api.task.Task.ExecuteState;

/**
 * Pure tests of the daily output-priority schedule: the time windows, the
 * configuration validation and the guarded write decisions. The production
 * defaults are SUB (3) from 00:30 to 18:00 and SBU (2) from 18:00 to 00:30.
 */
public class ModeScheduleTest {

	private static final int SBU = 2;
	private static final int SUB = 3;
	private static final int TIMEOUT = 30;
	private static final int COOLDOWN = 60;
	private static final LocalTime DAY = LocalTime.of(10, 0);
	private static final LocalTime EVENING = LocalTime.of(19, 0);

	private static ModeSchedule defaults() {
		return ModeSchedule.fromConfig(true, SUB, SBU, "18:00", "00:30", -1);
	}

	private static ModeSchedule.Step step(ModeSchedule sut, LocalTime now, Integer soc, Integer e204,
			boolean mains) {
		return sut.step(now, soc, e204, true, mains, TIMEOUT, COOLDOWN);
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
		var sut = ModeSchedule.fromConfig(true, SUB, 1, "18:00", "00:30", -1);
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
			assertStep(sut.step(DAY, 50, SBU, false, true, TIMEOUT, COOLDOWN), ModeScheduleState.DISABLED, SUB,
					null);
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
		var sut = defaults();
		assertStep(step(sut, DAY, null, SBU, true), ModeScheduleState.WAITING_FOR_DATA, SUB, null);
		assertStep(step(sut, DAY, -1, SBU, true), ModeScheduleState.WAITING_FOR_DATA, SUB, null);
		assertStep(step(sut, DAY, 101, SBU, true), ModeScheduleState.WAITING_FOR_DATA, SUB, null);
		assertStep(step(sut, DAY, 50, null, true), ModeScheduleState.WAITING_FOR_DATA, SUB, null);
		assertStep(step(sut, null, 50, SBU, true), ModeScheduleState.WAITING_FOR_DATA, null, null);
		// Once everything is readable the write goes out.
		assertStep(step(sut, DAY, 0, SBU, true), ModeScheduleState.WRITE_PENDING, SUB, SUB);
	}

	@Test
	public void testMismatchQueuesOneWrite() {
		var sut = defaults();
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
	public void testWaitsForMains() {
		var sut = defaults();
		for (var i = 0; i < 200; i++) {
			assertStep(step(sut, EVENING, 50, SUB, false), ModeScheduleState.WAITING_FOR_MAINS, SBU, null);
		}
		assertNull(sut.handlerState());
		// Mains is back: the evening write goes out.
		assertStep(step(sut, EVENING, 50, SUB, true), ModeScheduleState.WRITE_PENDING, SBU, SBU);
	}

	@Test
	public void testFailedReadbackBacksOffWithoutRetryStorm() {
		var sut = defaults();
		var writes = 0;
		for (var i = 0; i < 1000; i++) {
			var result = step(sut, DAY, 50, SBU, true);
			if (result.write() != null) {
				writes++;
				// The device accepts the frame but keeps SBU: read-back mismatch.
				sut.onExecute(ExecuteState.OK);
				sut.verify(SBU);
				assertEquals(SafeWriteHandler.State.FAILED, sut.handlerState());
				var failed = step(sut, DAY, 50, SBU, true);
				assertStep(failed, ModeScheduleState.WRITE_FAILED, SUB, null);
				i++;
			} else {
				assertEquals(ModeScheduleState.WRITE_FAILED, result.state());
			}
		}
		// One write per cooldown at most, never one per cycle.
		assertTrue(writes >= 2, "retried: " + writes);
		assertTrue(writes <= 1000 / COOLDOWN + 1, "writes: " + writes);
	}

	@Test
	public void testFailedExecuteBacksOff() {
		var sut = defaults();
		assertStep(step(sut, DAY, 50, SBU, true), ModeScheduleState.WRITE_PENDING, SUB, SUB);
		sut.onExecute(new ExecuteState.Error(new Exception("timeout")));
		assertEquals(SafeWriteHandler.State.FAILED, sut.handlerState());
		for (var i = 1; i < COOLDOWN; i++) {
			assertStep(step(sut, DAY, 50, SBU, true), ModeScheduleState.WRITE_FAILED, SUB, null);
		}
		assertStep(step(sut, DAY, 50, SBU, true), ModeScheduleState.WRITE_PENDING, SUB, SUB);
	}

	@Test
	public void testMissingReadbackTimesOutAndBacksOff() {
		var sut = defaults();
		assertStep(step(sut, DAY, 50, SBU, true), ModeScheduleState.WRITE_PENDING, SUB, SUB);
		sut.onExecute(ExecuteState.OK);
		for (var i = 1; i < TIMEOUT; i++) {
			assertStep(step(sut, DAY, 50, SBU, true), ModeScheduleState.WRITE_PENDING, SUB, null);
		}
		assertStep(step(sut, DAY, 50, SBU, true), ModeScheduleState.WRITE_FAILED, SUB, null);
		assertEquals(SafeWriteHandler.State.FAILED, sut.handlerState());
	}

	@Test
	public void testFailureWithMainsDownWaitsForMainsAfterCooldown() {
		var sut = defaults();
		step(sut, DAY, 50, SBU, true);
		sut.onExecute(new ExecuteState.Error(new Exception("timeout")));
		for (var i = 1; i < COOLDOWN; i++) {
			assertStep(step(sut, DAY, 50, SBU, false), ModeScheduleState.WRITE_FAILED, SUB, null);
		}
		assertStep(step(sut, DAY, 50, SBU, false), ModeScheduleState.WAITING_FOR_MAINS, SUB, null);
	}

	@Test
	public void testOverrideAfterVerifyIsCorrectedAtCooldown() {
		var sut = defaults();
		step(sut, DAY, 50, SBU, true);
		sut.onExecute(ExecuteState.OK);
		sut.verify(SUB);
		assertStep(step(sut, DAY, 50, SUB, true), ModeScheduleState.IN_SYNC, SUB, null);
		// Someone sets SBU on the panel: no burst, one correction after the cooldown.
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
		var sut = defaults();
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
	public void testCallbacksWithoutWriteAreIgnored() {
		var sut = defaults();
		sut.onExecute(ExecuteState.OK);
		sut.verify(SUB);
		assertNull(sut.handlerState());
		assertNull(sut.queuedTarget());
	}
}
