package io.openems.edge.ess.srne.batteryinverter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
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

	// A schedule past the startup hold, with mains confirmed and the device in sync
	// by day.
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
		return stepIn(sut, now, e20f, mains ? 2 : 3);
	}

	// One cycle with one fresh read of the given raw machine state.
	private static ChargePrioritySchedule.Step stepIn(ChargePrioritySchedule sut, LocalDateTime now, Integer e20f,
			int machineState) {
		sut.onMachineStateRead(machineState);
		return sut.step(now, e20f, true, machineState == 2, ChargePrioritySchedule.isRunningState(machineState),
				TIMEOUT, COOLDOWN);
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
		assertNull(ChargePrioritySchedule.fromConfig(true, 1, PV_ONLY, "00:00", "06:00").getInvalidReason());
		// The day value must be PV only: it gets the safe-direction write path.
		for (var notSafe : new int[] { 0, 1, 2 }) {
			assertNotNull(
					ChargePrioritySchedule.fromConfig(true, PV_ONLY, notSafe, "00:00", "06:00").getInvalidReason(),
					"day " + notSafe);
		}
		// Enabled without restore-when-off: invalid, so the pairing is enforced.
		var unpaired = ChargePrioritySchedule.fromConfig(true, HYBRID, PV_ONLY, "00:00", "06:00", false);
		assertNotNull(unpaired.getInvalidReason());
		assertEquals(Integer.valueOf(PV_ONLY), unpaired.holdValue());
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
		assertTrue(invalid.managesRegister());
		assertEquals(Integer.valueOf(PV_ONLY), invalid.holdValue());
		assertStep(step(invalid, at(NIGHT), PV_ONLY, true), ModeScheduleState.CONFIG_INVALID, PV_ONLY, null);
	}

	@Test
	public void testInvalidConfigHoldsPvOnly() {
		// A typo at runtime while E20F is hybrid: the safe value is written, at any
		// time of day and on battery, and the state keeps showing CONFIG_INVALID.
		var sut = ChargePrioritySchedule.fromConfig(true, HYBRID, PV_ONLY, "00:00", "6:00");
		assertNotNull(sut.getInvalidReason());
		for (var i = 1; i < ChargePrioritySchedule.DAY_STARTUP_HOLD_CYCLES; i++) {
			assertNull(step(sut, at(NIGHT), HYBRID, false).write());
		}
		assertStep(step(sut, at(NIGHT), HYBRID, false), ModeScheduleState.WRITE_PENDING, PV_ONLY, PV_ONLY);
		device(sut, PV_ONLY);
		assertStep(step(sut, at(NIGHT), PV_ONLY, false), ModeScheduleState.CONFIG_INVALID, PV_ONLY, null);
		// Even with an unknown clock.
		assertStep(step(sut, null, PV_ONLY, false), ModeScheduleState.CONFIG_INVALID, PV_ONLY, null);
		assertFalse(sut.isNightLate());
	}

	@Test
	public void testDisabledWithRestoreHoldsDayValue() {
		var sut = ChargePrioritySchedule.fromConfig(false, HYBRID, PV_ONLY, "00:00", "06:00", true);
		assertFalse(sut.isActive());
		assertTrue(sut.managesRegister());
		for (var i = 1; i < ChargePrioritySchedule.DAY_STARTUP_HOLD_CYCLES; i++) {
			assertNull(step(sut, at(NIGHT), HYBRID, true).write());
		}
		// Turned off in the night window with hybrid on the device: back to PV only.
		assertStep(step(sut, at(NIGHT), HYBRID, true), ModeScheduleState.WRITE_PENDING, PV_ONLY, PV_ONLY);
		device(sut, PV_ONLY);
		assertStep(step(sut, at(NIGHT), PV_ONLY, true), ModeScheduleState.DISABLED, PV_ONLY, null);
		// An out-of-range day value restores PV only.
		assertEquals(Integer.valueOf(PV_ONLY),
				ChargePrioritySchedule.fromConfig(false, HYBRID, 7, "00:00", "06:00", true).holdValue());
		// Without restore: E20F is not managed at all.
		var plain = ChargePrioritySchedule.fromConfig(false, HYBRID, PV_ONLY, "00:00", "06:00", false);
		assertFalse(plain.managesRegister());
		assertNull(plain.holdValue());
	}

	@Test
	public void testSafeValueMissingReasons() {
		var sut = primed();
		assertNull(sut.safeValueMissingReason());
		for (var i = 0; i < ChargePrioritySchedule.SAFE_MISMATCH_ALARM_CYCLES; i++) {
			stepIn(sut, at(DAY), HYBRID, 10);
		}
		assertTrue(sut.safeValueMissingReason().contains("has not read"));
	}

	@Test
	public void testSafeValueMissingAlarm() {
		var sut = primed();
		// Fault state by day with hybrid on the device: no write is possible.
		for (var i = 1; i < ChargePrioritySchedule.SAFE_MISMATCH_ALARM_CYCLES; i++) {
			stepIn(sut, at(DAY), HYBRID, 10);
			assertFalse(sut.isSafeValueMissing());
		}
		stepIn(sut, at(DAY), HYBRID, 10);
		assertTrue(sut.isSafeValueMissing());
		// In sync again: cleared.
		stepIn(sut, at(DAY), PV_ONLY, 10);
		assertFalse(sut.isSafeValueMissing());
	}

	@Test
	public void testFreshScheduleWithNeverKnownClock() {
		// Reboot before NTP: the clock has never been plausible since activation (no
		// priming with a known date). The fallback write is queued, verified and
		// counted, and the alarm is evaluated every cycle without a known date.
		var sut = defaults();
		for (var i = 1; i < ChargePrioritySchedule.UNKNOWN_CLOCK_FALLBACK_CYCLES; i++) {
			assertStep(step(sut, null, HYBRID, true), ModeScheduleState.WAITING_FOR_DATA, null, null);
			assertFalse(sut.isSafeValueMissing());
		}
		assertStep(step(sut, null, HYBRID, true), ModeScheduleState.WRITE_PENDING, PV_ONLY, PV_ONLY);
		assertFalse(sut.isSafeValueMissing());
		device(sut, PV_ONLY);
		assertStep(step(sut, null, PV_ONLY, true), ModeScheduleState.IN_SYNC, PV_ONLY, null);
		assertEquals(SafeWriteHandler.State.VERIFIED, sut.handlerState());
		assertNull(sut.safeValueMissingReason());
		// An override with the clock still unknown is re-corrected (date-less
		// re-correction path) and the alarm keeps evaluating.
		var wrote = false;
		for (var i = 0; i <= ChargePrioritySchedule.DAY_ALARM_BACKOFF_CYCLES && !wrote; i++) {
			wrote = step(sut, null, HYBRID, false).write() != null;
			sut.isSafeValueMissing();
		}
		assertTrue(wrote);
		device(sut, PV_ONLY);
		assertStep(step(sut, null, PV_ONLY, false), ModeScheduleState.IN_SYNC, PV_ONLY, null);
	}

	@Test
	public void testPerDayCountersTreatNullDateAsEmptyDay() {
		var sut = defaults();
		assertEquals(0, sut.writesTodayForTest(null));
		assertEquals(0, sut.dayWritesTodayForTest(null));
		for (var i = 0; i < ChargePrioritySchedule.UNKNOWN_CLOCK_FALLBACK_CYCLES; i++) {
			step(sut, null, HYBRID, true);
		}
		device(sut, PV_ONLY);
		step(sut, null, PV_ONLY, true);
		assertEquals(1, sut.dayWritesTodayForTest(LocalDate.MIN));
		assertEquals(0, sut.dayWritesTodayForTest(null));
	}

	@Test
	public void testHoldModeWithNeverKnownClock() {
		// Invalid config and off-with-restore both hold the safe value from the first
		// cycle, without any known date.
		for (var sut : new ChargePrioritySchedule[] {
				ChargePrioritySchedule.fromConfig(true, HYBRID, PV_ONLY, "00:00", "6:00"),
				ChargePrioritySchedule.fromConfig(false, HYBRID, PV_ONLY, "00:00", "06:00", true) }) {
			for (var i = 0; i < 10; i++) {
				// E20F unread, then already at the safe value: the alarm is evaluated.
				step(sut, null, null, true);
				assertFalse(sut.isSafeValueMissing());
				step(sut, null, PV_ONLY, true);
				assertFalse(sut.isSafeValueMissing());
			}
			var wrote = false;
			for (var i = 0; i < ChargePrioritySchedule.DAY_STARTUP_HOLD_CYCLES + 1 && !wrote; i++) {
				wrote = step(sut, null, HYBRID, false).write() != null;
			}
			assertTrue(wrote);
			device(sut, PV_ONLY);
			step(sut, null, PV_ONLY, false);
			assertEquals(SafeWriteHandler.State.VERIFIED, sut.handlerState());
			assertEquals(1, sut.dayWritesTodayForTest(LocalDate.MIN));
		}
	}

	@Test
	public void testUnknownClockFallbackOnBattery() {
		var sut = primed();
		for (var i = 1; i < ChargePrioritySchedule.UNKNOWN_CLOCK_FALLBACK_CYCLES; i++) {
			assertStep(step(sut, null, HYBRID, false), ModeScheduleState.WAITING_FOR_DATA, null, null);
		}
		assertStep(step(sut, null, HYBRID, false), ModeScheduleState.WRITE_PENDING, PV_ONLY, PV_ONLY);
	}

	@Test
	public void testUnknownClockFallbackAfterNightLatch() {
		var sut = primed();
		var t = at(NIGHT);
		// Latch the night failure budget.
		for (var attempt = 1; attempt <= ModeSchedule.MAX_ATTEMPTS; attempt++) {
			var wrote = false;
			for (var i = 0; i <= ModeSchedule.backoffCycles(attempt, COOLDOWN) && !wrote; i++) {
				wrote = step(sut, t, PV_ONLY, true).write() != null;
			}
			assertTrue(wrote);
			device(sut, PV_ONLY);
		}
		for (var i = 0; i < 2000; i++) {
			assertNull(step(sut, t, HYBRID, true).write());
		}
		assertEquals(ModeSchedule.MAX_ATTEMPTS, sut.failuresInWindow());
		// The clock is lost (no forward crossing, so the failures carry over): the
		// day-value fallback is not held back by the night latch.
		var wrote = false;
		for (var i = 0; i < ChargePrioritySchedule.UNKNOWN_CLOCK_FALLBACK_CYCLES + 500 && !wrote; i++) {
			wrote = step(sut, null, HYBRID, true).write() != null;
		}
		assertTrue(wrote);
	}

	@Test
	public void testQueuedDayWriteWithdrawnOnFlipToNight() {
		var sut = primed();
		var before = LocalDateTime.of(DATE, LocalTime.of(23, 59, 59));
		assertStep(step(sut, before, HYBRID, true), ModeScheduleState.WRITE_PENDING, PV_ONLY, PV_ONLY);
		// 00:00 arrives before the bridge took it; the device already has hybrid.
		assertStep(step(sut, before.plusSeconds(1), HYBRID, true), ModeScheduleState.IN_SYNC, HYBRID, null);
		assertNull(sut.writeElement().getNextWriteValueAndReset());
	}

	@Test
	public void testDisabledByDefault() {
		// Disabled: never invalid, never active, never writes, whatever is configured.
		var sut = ChargePrioritySchedule.fromConfig(false, HYBRID, HYBRID, "bad", null, false);
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
			assertStep(sut.step(at(NIGHT), PV_ONLY, false, true, true, TIMEOUT, COOLDOWN), ModeScheduleState.DISABLED,
					HYBRID, null);
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
		// One night write and one day write: two a day.
		assertEquals(1, sut.writesTodayForTest(DATE.plusDays(1)));
		assertEquals(1, sut.dayWritesTodayForTest(DATE.plusDays(1)));
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
	public void testDayWriteGoesOutOnBattery() {
		var sut = primed();
		var night = LocalDateTime.of(DATE.plusDays(1), LocalTime.of(5, 59));
		assertStep(step(sut, night, PV_ONLY, true), ModeScheduleState.WRITE_PENDING, HYBRID, HYBRID);
		device(sut, HYBRID);
		assertStep(step(sut, night, HYBRID, true), ModeScheduleState.IN_SYNC, HYBRID, null);
		// 06:00 in SBU on battery (state 3): the day value (safe direction) is written
		// right away, so it is in place before a later transfer to the line.
		var six = night.plusMinutes(1);
		assertStep(step(sut, six, HYBRID, false), ModeScheduleState.WRITE_PENDING, PV_ONLY, PV_ONLY);
		device(sut, PV_ONLY);
		assertStep(step(sut, six, PV_ONLY, false), ModeScheduleState.IN_SYNC, PV_ONLY, null);
	}

	@Test
	public void testNightWriteNeverGoesOutOnBattery() {
		var sut = primed();
		for (var i = 0; i < 1000; i++) {
			assertStep(step(sut, at(NIGHT), PV_ONLY, false), ModeScheduleState.WAITING_FOR_MAINS, HYBRID, null);
		}
		assertNull(sut.writeElement().getNextWriteValueAndReset());
		// A missing night value is not the safe-value alarm (it shows as night-late).
		assertFalse(sut.isSafeValueMissing());
	}

	@Test
	public void testDayWriteWaitsOutsideTheRunningStates() {
		var sut = primed();
		// Fault (10): no write in either direction.
		for (var i = 0; i < 100; i++) {
			assertStep(stepIn(sut, at(DAY), HYBRID, 10), ModeScheduleState.WAITING_FOR_MAINS, PV_ONLY, null);
		}
		// Running on battery again: three fresh state-3 reads, then the write.
		assertStep(stepIn(sut, at(DAY), HYBRID, 3), ModeScheduleState.WAITING_FOR_MAINS, PV_ONLY, null);
		assertStep(stepIn(sut, at(DAY), HYBRID, 3), ModeScheduleState.WAITING_FOR_MAINS, PV_ONLY, null);
		assertStep(stepIn(sut, at(DAY), HYBRID, 3), ModeScheduleState.WRITE_PENDING, PV_ONLY, PV_ONLY);
		// The queued day write is withdrawn on a fault before the bridge took it.
		var fault = stepIn(sut, at(DAY), HYBRID, 10);
		assertEquals(ModeScheduleState.WAITING_FOR_MAINS, fault.state());
		assertTrue(fault.withdraw());
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
	public void testDayStartupHoldIsShort() {
		var sut = defaults();
		sut.onMachineStateRead(3);
		sut.onMachineStateRead(3);
		for (var i = 1; i < ChargePrioritySchedule.DAY_STARTUP_HOLD_CYCLES; i++) {
			assertStep(stepIn(sut, at(DAY), HYBRID, 3), ModeScheduleState.STARTUP_HOLD, PV_ONLY, null);
		}
		assertStep(stepIn(sut, at(DAY), HYBRID, 3), ModeScheduleState.WRITE_PENDING, PV_ONLY, PV_ONLY);
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
	public void testDailyWriteCeilings() {
		var sut = primed();
		// Clock jumps back and forth across the window (no budget refill on a jump):
		// each flip is a verified write; the night value has its own ceiling.
		var night = at(NIGHT);
		var day = at(DAY);
		var value = PV_ONLY;
		for (var n = 0; n < 2 * ChargePrioritySchedule.MAX_DAILY_WRITES; n++) {
			var now = n % 2 == 0 ? night : day;
			var target = n % 2 == 0 ? HYBRID : PV_ONLY;
			assertStep(step(sut, now, value, true), ModeScheduleState.WRITE_PENDING, target, target);
			device(sut, target);
			value = target;
			assertStep(step(sut, now, value, true), ModeScheduleState.IN_SYNC, target, null);
		}
		assertEquals(ChargePrioritySchedule.MAX_DAILY_WRITES, sut.writesTodayForTest(DATE));
		assertEquals(ChargePrioritySchedule.MAX_DAILY_WRITES, sut.dayWritesTodayForTest(DATE));
		assertStep(step(sut, night, value, true), ModeScheduleState.DAILY_WRITE_LIMIT, HYBRID, null);
		// The next local day has a fresh ceiling.
		assertStep(step(sut, night.plusDays(1), value, true), ModeScheduleState.WRITE_PENDING, HYBRID, HYBRID);
	}

	@Test
	public void testDayOverridesHaveNoCapAndRaiseTheAlarm() {
		var sut = primed();
		var t = at(DAY);
		// Someone keeps setting hybrid on the panel by day: corrected every time,
		// beyond the night override caps and the former day cap of 24 writes.
		for (var n = 1; n <= 30; n++) {
			var wrote = false;
			var cycles = 0;
			for (var i = 0; i <= ChargePrioritySchedule.DAY_ALARM_BACKOFF_CYCLES && !wrote; i++) {
				var step = step(sut, t, HYBRID, true);
				cycles++;
				assertNotEquals(ModeScheduleState.OVERRIDDEN, step.state());
				assertNotEquals(ModeScheduleState.DAILY_WRITE_LIMIT, step.state());
				wrote = step.write() != null;
			}
			assertTrue(wrote, "correction " + n);
			if (n - 1 > ChargePrioritySchedule.SAFE_OVERRIDE_ALARM) {
				// Alarm raised: re-corrected at the slower cadence (settings memory).
				assertTrue(cycles >= ChargePrioritySchedule.DAY_ALARM_BACKOFF_CYCLES - 1, "cycles " + cycles);
			}
			device(sut, PV_ONLY);
			assertStep(step(sut, t, PV_ONLY, true), ModeScheduleState.IN_SYNC, PV_ONLY, null);
			// The first write is not an override. The alarm holds from the
			// SAFE_OVERRIDE_ALARM-th re-correction on, even while in sync: the
			// override is persistent and the grid charges between corrections.
			assertEquals(n - 1 >= ChargePrioritySchedule.SAFE_OVERRIDE_ALARM, sut.isSafeValueMissing(),
					"correction " + n);
		}
		assertEquals(30, sut.dayWritesTodayForTest(DATE));
		// Night window: the night value is due, no alarm; its budget is untouched.
		assertStep(step(sut, at(NIGHT), PV_ONLY, true), ModeScheduleState.WRITE_PENDING, HYBRID, HYBRID);
		assertFalse(sut.isSafeValueMissing());
		// The next local day starts without the alarm.
		device(sut, HYBRID);
		assertStep(step(sut, at(DAY).plusDays(1), PV_ONLY, true), ModeScheduleState.IN_SYNC, PV_ONLY, null);
		assertFalse(sut.isSafeValueMissing());
	}

	@Test
	public void testDayFailuresNeverLatch() {
		var sut = primed();
		var t = at(DAY);
		for (var attempt = 1; attempt <= ModeSchedule.MAX_ATTEMPTS + 5; attempt++) {
			assertStep(step(sut, t, HYBRID, true), ModeScheduleState.WRITE_PENDING, PV_ONLY, PV_ONLY);
			device(sut, HYBRID); // read-back keeps hybrid: failed
			var backoff = ModeSchedule.backoffCycles(Math.min(attempt, ChargePrioritySchedule.DAY_MAX_BACKOFF_ATTEMPTS),
					COOLDOWN);
			for (var i = 1; i < backoff; i++) {
				assertStep(step(sut, t, HYBRID, true), ModeScheduleState.WRITE_FAILED, PV_ONLY, null);
			}
			assertEquals(attempt, sut.failuresInWindow());
		}
		// Retried at most every 480 cycles, never latched.
		assertEquals(480, ModeSchedule.backoffCycles(ChargePrioritySchedule.DAY_MAX_BACKOFF_ATTEMPTS, COOLDOWN));
	}

	@Test
	public void testUnknownClockFallsBackToDayValue() {
		var sut = primed();
		// gw-pi2 reboots with no NTP while E20F is hybrid: after the fallback period
		// the day value is written, so the grid cannot charge at an unknown time.
		for (var i = 1; i < ChargePrioritySchedule.UNKNOWN_CLOCK_FALLBACK_CYCLES; i++) {
			assertStep(step(sut, null, HYBRID, true), ModeScheduleState.WAITING_FOR_DATA, null, null);
		}
		assertStep(step(sut, null, HYBRID, true), ModeScheduleState.WRITE_PENDING, PV_ONLY, PV_ONLY);
		device(sut, PV_ONLY);
		assertStep(step(sut, null, PV_ONLY, true), ModeScheduleState.IN_SYNC, PV_ONLY, null);
		assertEquals(1, sut.dayWritesTodayForTest(DATE));
		// Once the clock is known again the window decides.
		assertStep(step(sut, at(NIGHT), PV_ONLY, true), ModeScheduleState.WRITE_PENDING, HYBRID, HYBRID);
	}

	@Test
	public void testDayLatchAtSixNeverHappens() {
		var sut = primed();
		var night = LocalDateTime.of(DATE.plusDays(1), LocalTime.of(5, 59));
		assertStep(step(sut, night, PV_ONLY, true), ModeScheduleState.WRITE_PENDING, HYBRID, HYBRID);
		device(sut, HYBRID);
		step(sut, night, HYBRID, true);
		// Bus errors at 06:00: every day write fails, many more times than the night
		// budget, and the schedule keeps retrying.
		var t = night.plusMinutes(1);
		var writes = 0;
		for (var i = 0; i < 20_000; i++) {
			var step = step(sut, t, HYBRID, true);
			assertEquals(PV_ONLY, step.target());
			if (step.write() != null) {
				writes++;
				assertNotNull(sut.writeElement().getNextWriteValueAndReset());
				sut.onExecute(new ExecuteState.Error(new Exception("bus")));
			}
		}
		assertTrue(writes > ModeSchedule.MAX_ATTEMPTS + 20, "writes " + writes);
	}

	@Test
	public void testNightLate() {
		var sut = primed();
		var midnight = LocalDateTime.of(DATE.plusDays(1), LocalTime.MIDNIGHT);
		step(sut, midnight, PV_ONLY, false);
		assertFalse(sut.isNightLate());
		step(sut, midnight.plusMinutes(9), PV_ONLY, false);
		assertFalse(sut.isNightLate());
		step(sut, midnight.plusMinutes(10), PV_ONLY, false);
		assertTrue(sut.isNightLate());
		// The night value arrives: no longer late.
		step(sut, midnight.plusMinutes(11), HYBRID, true);
		assertFalse(sut.isNightLate());
		// Never late by day.
		step(sut, at(DAY), PV_ONLY, true);
		assertFalse(sut.isNightLate());
	}

	@Test
	public void testTimerStartMargin() {
		var sut = defaults();
		assertFalse(sut.timerStartHasMargin(LocalTime.MIDNIGHT));
		assertFalse(sut.timerStartHasMargin(LocalTime.of(0, 4, 59)));
		assertTrue(sut.timerStartHasMargin(LocalTime.of(0, 5)));
		assertTrue(sut.timerStartHasMargin(LocalTime.of(1, 0)));
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
