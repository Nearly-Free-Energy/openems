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
import java.time.ZoneOffset;

import org.junit.jupiter.api.Test;

import io.openems.edge.bridge.modbus.api.task.Task.ExecuteState;

/**
 * Pure tests of the daily charge-priority schedule (E20F, decision D138): the
 * night window boundaries, the configuration validation, the gates and the
 * guarded write decisions. Defaults: hybrid (2) from 00:00 to 06:00 local, PV
 * only (3) otherwise.
 */
public class ChargePriorityScheduleTest {

	private static final int HYBRID = ChargePrioritySchedule.HYBRID;
	private static final int PV_ONLY = ChargePrioritySchedule.PV_ONLY;
	private static final int TIMEOUT = 30;
	private static final int COOLDOWN = 60;
	private static final LocalTime DAY = LocalTime.of(10, 0);
	private static final LocalTime NIGHT = LocalTime.of(2, 0);
	private static final ZoneId KAMPALA = ZoneId.of("Africa/Kampala");
	private static final LocalDate DATE = LocalDate.of(2026, 11, 11);

	private static LocalDateTime at(LocalTime time) {
		return time == null ? null : LocalDateTime.of(DATE, time);
	}

	private static ChargePrioritySchedule defaults() {
		return ChargePrioritySchedule.fromConfig(true, HYBRID, PV_ONLY, "00:00", "06:00");
	}

	// A schedule past the startup hold, with mains confirmed and the device in
	// sync by day.
	private static ChargePrioritySchedule primed() {
		var sut = defaults();
		for (var i = 0; i < ModeSchedule.STARTUP_HOLD_CYCLES; i++) {
			assertEquals(ModeScheduleState.IN_SYNC, step(sut, at(DAY), PV_ONLY, true).state());
		}
		return sut;
	}

	// One cycle with one fresh machine-state read (2 mains, 3 on battery).
	private static ChargePrioritySchedule.Step step(ChargePrioritySchedule sut, LocalDateTime now, Integer e20f,
			boolean mains) {
		sut.onMachineStateRead(mains ? 2 : 3);
		return sut.step(now, e20f, true, mains, TIMEOUT, COOLDOWN);
	}

	private static void assertStep(ChargePrioritySchedule.Step step, ModeScheduleState state, Integer target,
			Integer write) {
		assertEquals(state, step.state());
		assertEquals(target, step.target());
		assertEquals(write, step.write());
	}

	// Plays the device for the queued write: the bridge takes it, executes it and
	// reads back the given value.
	private static void device(ChargePrioritySchedule sut, int readBack) {
		assertNotNull(sut.writeElement().getNextWriteValueAndReset());
		sut.onExecute(ExecuteState.OK);
		sut.verify(readBack);
	}

	@Test
	public void testBoundariesLocal() {
		var sut = defaults();
		assertTrue(sut.isActive());
		assertEquals(PV_ONLY, sut.targetAt(LocalTime.of(23, 59)));
		assertEquals(PV_ONLY, sut.targetAt(LocalTime.of(23, 59, 59)));
		assertEquals(HYBRID, sut.targetAt(LocalTime.MIDNIGHT)); // night starts exactly at 00:00
		assertEquals(HYBRID, sut.targetAt(LocalTime.of(5, 59)));
		assertEquals(HYBRID, sut.targetAt(LocalTime.of(5, 59, 59)));
		assertEquals(PV_ONLY, sut.targetAt(LocalTime.of(6, 0))); // day starts exactly at 06:00
		assertEquals(PV_ONLY, sut.targetAt(LocalTime.of(18, 0)));
		assertEquals(PV_ONLY, sut.targetAt(LocalTime.of(23, 5))); // 11 Nov 2026 23:05 incident
		assertNull(sut.targetAt(null));
	}

	@Test
	public void testNightWindowAcrossMidnight() {
		var sut = ChargePrioritySchedule.fromConfig(true, HYBRID, PV_ONLY, "22:00", "05:00");
		assertTrue(sut.isActive());
		assertEquals(PV_ONLY, sut.targetAt(LocalTime.of(21, 59)));
		assertEquals(HYBRID, sut.targetAt(LocalTime.of(22, 0)));
		assertEquals(HYBRID, sut.targetAt(LocalTime.MIDNIGHT));
		assertEquals(HYBRID, sut.targetAt(LocalTime.of(4, 59)));
		assertEquals(PV_ONLY, sut.targetAt(LocalTime.of(5, 0)));
	}

	@Test
	public void testConfigValidation() {
		assertNull(defaults().getInvalidReason());
		for (var bad : new int[] { -1, 4 }) {
			assertNotNull(ChargePrioritySchedule.fromConfig(true, bad, PV_ONLY, "00:00", "06:00").getInvalidReason());
			assertNotNull(ChargePrioritySchedule.fromConfig(true, HYBRID, bad, "00:00", "06:00").getInvalidReason());
		}
		assertNull(ChargePrioritySchedule.fromConfig(true, 1, 0, "00:00", "06:00").getInvalidReason());
		assertNotNull(ChargePrioritySchedule.fromConfig(true, HYBRID, HYBRID, "00:00", "06:00").getInvalidReason());
		for (var bad : new String[] { null, "", "0:00", "24:00", "06:60", "06-00", "night" }) {
			assertNotNull(ChargePrioritySchedule.fromConfig(true, HYBRID, PV_ONLY, bad, "06:00").getInvalidReason(),
					"" + bad);
			assertNotNull(ChargePrioritySchedule.fromConfig(true, HYBRID, PV_ONLY, "00:00", bad).getInvalidReason(),
					"" + bad);
		}
		assertNotNull(ChargePrioritySchedule.fromConfig(true, HYBRID, PV_ONLY, "06:00", "06:00").getInvalidReason());
		var invalid = ChargePrioritySchedule.fromConfig(true, HYBRID, HYBRID, "00:00", "06:00");
		assertFalse(invalid.isActive());
		assertStep(step(invalid, at(NIGHT), PV_ONLY, true), ModeScheduleState.CONFIG_INVALID, null, null);
	}

	@Test
	public void testDisabledByDefault() {
		// Disabled: never invalid, never active, never writes, whatever is configured.
		var sut = ChargePrioritySchedule.fromConfig(false, HYBRID, HYBRID, "bad", null);
		assertFalse(sut.isEnabled());
		assertFalse(sut.isActive());
		assertNull(sut.getInvalidReason());
		for (var i = 0; i < ModeSchedule.STARTUP_HOLD_CYCLES + 10; i++) {
			assertStep(step(sut, at(NIGHT), PV_ONLY, true), ModeScheduleState.DISABLED, null, null);
		}
		assertNull(sut.writeElement().getNextWriteValueAndReset());
	}

	@Test
	public void testControlDisabledNeverWrites() {
		var sut = primed();
		for (var i = 0; i < 10; i++) {
			sut.onMachineStateRead(2);
			assertStep(sut.step(at(NIGHT), PV_ONLY, false, true, TIMEOUT, COOLDOWN), ModeScheduleState.DISABLED, HYBRID,
					null);
		}
		assertNull(sut.writeElement().getNextWriteValueAndReset());
	}

	@Test
	public void testKampalaIsDstFree() {
		// Africa/Kampala is UTC+3 all year: the window never shifts.
		for (var month = 1; month <= 12; month++) {
			var instant = LocalDateTime.of(2027, month, 15, 12, 0).toInstant(ZoneOffset.UTC);
			assertEquals(ZoneOffset.ofHours(3), KAMPALA.getRules().getOffset(instant));
			assertTrue(
					KAMPALA.getRules().isValidOffset(LocalDateTime.of(2027, month, 15, 0, 0), ZoneOffset.ofHours(3)));
		}
	}

	@Test
	public void testBoundariesThroughTheClockGate() {
		var sut = defaults();
		// 20:59Z is 23:59 Kampala; settle the clock gate first.
		var t = Instant.parse("2026-11-11T20:59:00Z");
		for (var i = 0; i < ModeSchedule.CLOCK_SETTLE_CYCLES; i++) {
			assertNull(sut.plausibleTime(t, KAMPALA));
		}
		assertEquals(PV_ONLY, sut.targetAt(sut.plausibleTime(t, KAMPALA).toLocalTime()));
		t = Instant.parse("2026-11-11T21:00:00Z"); // 00:00 local, 12 Nov
		assertEquals(HYBRID, sut.targetAt(sut.plausibleTime(t, KAMPALA).toLocalTime()));
		// Step through the night in 1-minute steps (within the clock-step limit).
		while (t.isBefore(Instant.parse("2026-11-12T02:59:00Z"))) {
			t = t.plusSeconds(60);
			assertNotNull(sut.plausibleTime(t, KAMPALA));
		}
		assertEquals(LocalTime.of(5, 59), sut.plausibleTime(t, KAMPALA).toLocalTime());
		assertEquals(HYBRID, sut.targetAt(sut.plausibleTime(t, KAMPALA).toLocalTime()));
		t = t.plusSeconds(60); // 06:00 local
		assertEquals(PV_ONLY, sut.targetAt(sut.plausibleTime(t, KAMPALA).toLocalTime()));
	}

	@Test
	public void testImplausibleClockNeverWrites() {
		var sut = primed();
		// An unknown time (clock gate closed) writes nothing, even with E20F off
		// target.
		for (var i = 0; i < 100; i++) {
			assertStep(step(sut, null, HYBRID, true), ModeScheduleState.WAITING_FOR_DATA, null, null);
		}
		assertNull(sut.plausibleTime(Instant.EPOCH, KAMPALA));
		assertNull(sut.writeElement().getNextWriteValueAndReset());
	}

	@Test
	public void testUnreadableE20fNeverWrites() {
		var sut = primed();
		assertStep(step(sut, at(NIGHT), null, true), ModeScheduleState.WAITING_FOR_DATA, HYBRID, null);
		assertNull(sut.writeElement().getNextWriteValueAndReset());
	}

	@Test
	public void testTwoWritesADayOnlyOnChange() {
		var sut = primed();
		// 23:59: day value, in sync, nothing written.
		assertStep(step(sut, LocalDateTime.of(DATE, LocalTime.of(23, 59)), PV_ONLY, true), ModeScheduleState.IN_SYNC,
				PV_ONLY, null);
		// 00:00: the night value is written in the first cycle of the window.
		var midnight = LocalDateTime.of(DATE.plusDays(1), LocalTime.MIDNIGHT);
		assertStep(step(sut, midnight, PV_ONLY, true), ModeScheduleState.WRITE_PENDING, HYBRID, HYBRID);
		device(sut, HYBRID);
		assertStep(step(sut, midnight.plusSeconds(1), HYBRID, true), ModeScheduleState.IN_SYNC, HYBRID, null);
		assertEquals(SafeWriteHandler.State.VERIFIED, sut.handlerState());
		// All night in sync: no further write.
		for (var t = midnight.plusMinutes(1); t.toLocalTime().isBefore(LocalTime.of(5, 59, 1)); t = t.plusMinutes(1)) {
			assertStep(step(sut, t, HYBRID, true), ModeScheduleState.IN_SYNC, HYBRID, null);
		}
		// 06:00: back to the day value.
		var six = LocalDateTime.of(DATE.plusDays(1), LocalTime.of(6, 0));
		assertStep(step(sut, six, HYBRID, true), ModeScheduleState.WRITE_PENDING, PV_ONLY, PV_ONLY);
		device(sut, PV_ONLY);
		for (var t = six.plusSeconds(1); t.toLocalTime().isBefore(LocalTime.of(23, 0)); t = t.plusMinutes(1)) {
			assertStep(step(sut, t, PV_ONLY, true), ModeScheduleState.IN_SYNC, PV_ONLY, null);
		}
		assertEquals(2, sut.writesTodayForTest(DATE.plusDays(1)));
		assertNull(sut.writeElement().getNextWriteValueAndReset());
	}

	@Test
	public void testMachineStateGateHoldsTheMidnightWrite() {
		var sut = primed();
		var midnight = LocalDateTime.of(DATE.plusDays(1), LocalTime.MIDNIGHT);
		// SBU on battery (state 3) at 00:00: the night write waits for mains.
		for (var i = 0; i < 100; i++) {
			assertStep(step(sut, midnight.plusSeconds(i), PV_ONLY, false), ModeScheduleState.WAITING_FOR_MAINS, HYBRID,
					null);
		}
		// Mains (state 2) needs three fresh reads in a row.
		var t = midnight.plusMinutes(30);
		assertStep(step(sut, t, PV_ONLY, true), ModeScheduleState.WAITING_FOR_MAINS, HYBRID, null);
		assertStep(step(sut, t, PV_ONLY, true), ModeScheduleState.WAITING_FOR_MAINS, HYBRID, null);
		assertStep(step(sut, t, PV_ONLY, true), ModeScheduleState.WRITE_PENDING, HYBRID, HYBRID);
	}

	@Test
	public void testMachineStateGateHoldsTheDayWriteUntilMains() {
		var sut = primed();
		var night = LocalDateTime.of(DATE.plusDays(1), LocalTime.of(5, 59));
		assertStep(step(sut, night, PV_ONLY, true), ModeScheduleState.WRITE_PENDING, HYBRID, HYBRID);
		device(sut, HYBRID);
		assertStep(step(sut, night, HYBRID, true), ModeScheduleState.IN_SYNC, HYBRID, null);
		// 06:00 on battery: the day value waits; on battery the mains does not charge.
		var six = night.plusMinutes(1);
		assertStep(step(sut, six, HYBRID, false), ModeScheduleState.WAITING_FOR_MAINS, PV_ONLY, null);
		// Back on the line (e.g. SoC at switchToLineSoc): written once confirmed.
		step(sut, six.plusMinutes(1), HYBRID, true);
		step(sut, six.plusMinutes(1), HYBRID, true);
		assertStep(step(sut, six.plusMinutes(1), HYBRID, true), ModeScheduleState.WRITE_PENDING, PV_ONLY, PV_ONLY);
	}

	@Test
	public void testMainsLossWithdrawsQueuedWrite() {
		var sut = primed();
		assertStep(step(sut, at(NIGHT), PV_ONLY, true), ModeScheduleState.WRITE_PENDING, HYBRID, HYBRID);
		var lost = step(sut, at(NIGHT), PV_ONLY, false);
		assertEquals(ModeScheduleState.WAITING_FOR_MAINS, lost.state());
		assertTrue(lost.withdraw());
		assertNull(sut.handlerState());
		assertNull(sut.writeElement().getNextWriteValueAndReset());
	}

	@Test
	public void testStartupHold() {
		var sut = defaults();
		// Mains already confirmed, so only the hold keeps the write back.
		sut.onMachineStateRead(2);
		sut.onMachineStateRead(2);
		for (var i = 1; i < ModeSchedule.STARTUP_HOLD_CYCLES; i++) {
			assertStep(step(sut, at(NIGHT), PV_ONLY, true), ModeScheduleState.STARTUP_HOLD, HYBRID, null);
		}
		assertStep(step(sut, at(NIGHT), PV_ONLY, true), ModeScheduleState.WRITE_PENDING, HYBRID, HYBRID);
	}

	@Test
	public void testReadbackMismatchBacksOffAndLatches() {
		var sut = primed();
		var t = at(NIGHT);
		for (var attempt = 1; attempt <= ModeSchedule.MAX_ATTEMPTS; attempt++) {
			assertStep(step(sut, t, PV_ONLY, true), ModeScheduleState.WRITE_PENDING, HYBRID, HYBRID);
			device(sut, PV_ONLY); // the device keeps the old value
			assertEquals(SafeWriteHandler.State.FAILED, sut.handlerState());
			var backoff = ModeSchedule.backoffCycles(attempt, COOLDOWN);
			for (var i = 1; i < backoff; i++) {
				assertStep(step(sut, t, PV_ONLY, true), ModeScheduleState.WRITE_FAILED, HYBRID, null);
			}
			assertEquals(attempt, sut.failuresInWindow());
			if (attempt == ModeSchedule.MAX_ATTEMPTS) {
				break;
			}
		}
		// Budget spent: latched for the rest of the window.
		for (var i = 0; i < 5000; i++) {
			assertStep(step(sut, t, PV_ONLY, true), ModeScheduleState.WRITE_FAILED, HYBRID, null);
		}
		assertNull(sut.writeElement().getNextWriteValueAndReset());
		// A forward crossing of 06:00 refills the budget; the day value is in sync.
		var before = LocalDateTime.of(DATE, LocalTime.of(5, 59, 59));
		step(sut, before, PV_ONLY, true);
		assertStep(step(sut, before.plusSeconds(1), PV_ONLY, true), ModeScheduleState.IN_SYNC, PV_ONLY, null);
		assertEquals(0, sut.failuresInWindow());
	}

	@Test
	public void testReadbackTimeoutAndExecuteErrorCountAsFailures() {
		var sut = primed();
		var t = at(NIGHT);
		assertStep(step(sut, t, PV_ONLY, true), ModeScheduleState.WRITE_PENDING, HYBRID, HYBRID);
		assertNotNull(sut.writeElement().getNextWriteValueAndReset());
		sut.onExecute(ExecuteState.OK);
		// No fresh read-back within the timeout.
		for (var i = 1; i < TIMEOUT; i++) {
			assertStep(step(sut, t, PV_ONLY, true), ModeScheduleState.WRITE_PENDING, HYBRID, null);
		}
		assertStep(step(sut, t, PV_ONLY, true), ModeScheduleState.WRITE_FAILED, HYBRID, null);
		assertEquals(1, sut.failuresInWindow());
		for (var i = 2; i < ModeSchedule.backoffCycles(1, COOLDOWN); i++) {
			assertStep(step(sut, t, PV_ONLY, true), ModeScheduleState.WRITE_FAILED, HYBRID, null);
		}
		assertStep(step(sut, t, PV_ONLY, true), ModeScheduleState.WRITE_PENDING, HYBRID, HYBRID);
		assertNotNull(sut.writeElement().getNextWriteValueAndReset());
		sut.onExecute(new ExecuteState.Error(new Exception("timeout")));
		assertStep(step(sut, t, PV_ONLY, true), ModeScheduleState.WRITE_FAILED, HYBRID, null);
		assertEquals(2, sut.failuresInWindow());
	}

	@Test
	public void testOverrideIsReCorrectedThenHeld() {
		var sut = primed();
		var t = at(NIGHT);
		assertStep(step(sut, t, PV_ONLY, true), ModeScheduleState.WRITE_PENDING, HYBRID, HYBRID);
		device(sut, HYBRID);
		assertStep(step(sut, t, HYBRID, true), ModeScheduleState.IN_SYNC, HYBRID, null);
		for (var n = 1; n <= ChargePrioritySchedule.MAX_OVERRIDES_PER_WINDOW; n++) {
			// Someone sets E20F on the panel: re-corrected after the backoff.
			var backoff = ModeSchedule.backoffCycles(n, COOLDOWN);
			var wrote = false;
			for (var i = 0; i <= backoff && !wrote; i++) {
				wrote = step(sut, t, PV_ONLY, true).write() != null;
			}
			assertTrue(wrote);
			device(sut, HYBRID);
			assertStep(step(sut, t, HYBRID, true), ModeScheduleState.IN_SYNC, HYBRID, null);
		}
		for (var i = 0; i < 2000; i++) {
			assertStep(step(sut, t, PV_ONLY, true), ModeScheduleState.OVERRIDDEN, HYBRID, null);
		}
	}

	@Test
	public void testDailyWriteCeiling() {
		var sut = primed();
		// Clock jumps back and forth across the window (no budget refill on a jump):
		// each flip is a verified write until the daily ceiling holds the value.
		var night = at(NIGHT);
		var day = at(DAY);
		var value = PV_ONLY;
		for (var n = 0; n < ChargePrioritySchedule.MAX_DAILY_WRITES; n++) {
			var now = n % 2 == 0 ? night : day;
			var target = n % 2 == 0 ? HYBRID : PV_ONLY;
			assertStep(step(sut, now, value, true), ModeScheduleState.WRITE_PENDING, target, target);
			device(sut, target);
			value = target;
			assertStep(step(sut, now, value, true), ModeScheduleState.IN_SYNC, target, null);
		}
		assertEquals(ChargePrioritySchedule.MAX_DAILY_WRITES, sut.writesTodayForTest(DATE));
		assertStep(step(sut, night, value, true), ModeScheduleState.DAILY_WRITE_LIMIT, HYBRID, null);
		// The next local day has a fresh ceiling.
		assertStep(step(sut, night.plusDays(1), value, true), ModeScheduleState.WRITE_PENDING, HYBRID, HYBRID);
	}

	@Test
	public void testWindowChangeWithdrawsQueuedWrite() {
		var sut = primed();
		var before = LocalDateTime.of(DATE, LocalTime.of(5, 59, 59));
		assertStep(step(sut, before, PV_ONLY, true), ModeScheduleState.WRITE_PENDING, HYBRID, HYBRID);
		// 06:00 arrives before the bridge took the night write: it is withdrawn and the
		// device (already at the day value) is in sync.
		assertStep(step(sut, before.plusSeconds(1), PV_ONLY, true), ModeScheduleState.IN_SYNC, PV_ONLY, null);
		assertNull(sut.writeElement().getNextWriteValueAndReset());
	}

	@Test
	public void testCoversTimer() {
		var sut = defaults();
		assertTrue(sut.coversTimer(LocalTime.MIDNIGHT, LocalTime.of(6, 0)));
		assertTrue(sut.coversTimer(LocalTime.of(1, 0), LocalTime.of(5, 0)));
		assertFalse(sut.coversTimer(LocalTime.of(23, 0), LocalTime.of(5, 0)));
		assertFalse(sut.coversTimer(LocalTime.MIDNIGHT, LocalTime.of(6, 30)));
		assertFalse(sut.coversTimer(LocalTime.of(5, 0), LocalTime.of(1, 0)));
		assertFalse(sut.coversTimer(LocalTime.of(18, 0), LocalTime.of(22, 0)));
	}
}
