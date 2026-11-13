package io.openems.edge.ess.srne.batteryinverter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;

import org.junit.jupiter.api.Test;

import io.openems.edge.bridge.modbus.api.task.Task.ExecuteState;
import io.openems.edge.ess.srne.batteryinverter.NightChargeStop.State;

/**
 * Pure tests of the night charge stop against a real charge
 * {@link ScheduleWindow}. The window is the Sezibwa one, 00:00-06:00, with a
 * stop SoC of 50 %. A small device model plays the charge enable register
 * (E02C): it executes the queued enable write and reads back what it stored, or
 * a wrong value when a read-back failure is injected.
 */
public class NightChargeStopTest {

	private static final int START = 0; // 00:00
	private static final int STOP = 1536; // 06:00
	private static final int STOP_SOC = 50;
	private static final LocalDate NIGHT = LocalDate.of(2026, 11, 13);

	private static LocalDateTime at(LocalDate date, int hour, int minute) {
		return LocalDateTime.of(date, LocalTime.of(hour, minute));
	}

	private static LocalDateTime at(int hour, int minute) {
		return at(NIGHT, hour, minute);
	}

	/** Plays the charge window and its enable register. */
	private static final class Device {
		private final int start;
		private final int stop;
		private final NightChargeStop sut;
		private final ScheduleWindow window = new ScheduleWindow(0xE026, 0xE02C, "CHARGE");
		private Integer enable;
		private int writes;
		private boolean failReadback;

		private Device(int enable) {
			this(enable, START, STOP);
		}

		private Device(int enable, int start, int stop) {
			this.enable = enable;
			this.start = start;
			this.stop = stop;
			this.sut = NightChargeStop.fromConfig(STOP_SOC, true, 1, start, stop);
		}

		// One cycle per minute from `from` to `to` (inclusive): a steady clock.
		private NightChargeStop.Step walk(LocalDateTime from, LocalDateTime to) {
			NightChargeStop.Step step = null;
			for (var t = from; !t.isAfter(to); t = t.plusMinutes(1)) {
				step = this.cycle(t);
			}
			return step;
		}

		private void soc(int soc, int reads) {
			for (var i = 0; i < reads; i++) {
				this.sut.onSocRead(soc);
			}
		}

		// One cycle: the rule, then the window reconcile with the rule's target, then
		// the bus executes a queued enable write and reads the register back.
		private NightChargeStop.Step cycle(LocalDateTime now) {
			var step = this.sut.step(now, this.enable, this.window);
			this.window.reconcile(this.start, this.stop, this.enable, this.start, this.stop, step.enable());
			this.window.onCycle(30);
			var queued = this.window.enableWriteElement().getNextWriteValueAndReset();
			if (queued != null) {
				this.writes++;
				this.window.onEnableExecute(ExecuteState.OK);
				var written = (Integer) queued[0].getValue();
				this.enable = this.failReadback ? 1 - written : written;
				this.window.verifyEnable(this.enable);
			}
			return step;
		}

		private NightChargeStop.Step cycles(LocalDateTime now, int count) {
			NightChargeStop.Step step = null;
			for (var i = 0; i < count; i++) {
				step = this.cycle(now);
			}
			return step;
		}
	}

	@Test
	public void testValidationAndOffByDefault() {
		assertFalse(NightChargeStop.OFF.isActive());
		assertFalse(NightChargeStop.fromConfig(-1, true, 1, START, STOP).isActive());
		var off = NightChargeStop.OFF.step(at(3, 0), 1, new ScheduleWindow(0xE026, 0xE02C, "CHARGE"));
		assertEquals(1, off.enable());
		assertEquals(State.OFF, off.state());
		for (var valid : new int[] { -1, 10, 50, 100 }) {
			assertNull(NightChargeStop.invalidReason(valid), "" + valid);
		}
		for (var invalid : new int[] { -2, 0, 9, 101, 4096 }) {
			assertNotNull(NightChargeStop.invalidReason(invalid), "" + invalid);
			assertFalse(NightChargeStop.fromConfig(invalid, true, 1, START, STOP).isActive());
		}
		assertNull(NightChargeStop.inertReason(true, 1, START, STOP));
		assertNotNull(NightChargeStop.inertReason(false, 1, START, STOP));
		for (var enable : new int[] { -1, 0 }) {
			assertNotNull(NightChargeStop.inertReason(true, enable, START, STOP));
			assertFalse(NightChargeStop.fromConfig(STOP_SOC, true, enable, START, STOP).isActive());
		}
		assertNotNull(NightChargeStop.inertReason(true, 1, -1, -1));
		assertNotNull(NightChargeStop.inertReason(true, 1, STOP, START));
		assertTrue(NightChargeStop.fromConfig(STOP_SOC, true, 1, START, STOP).isActive());
	}

	@Test
	public void testWindowBoundaries() {
		var sut = NightChargeStop.fromConfig(STOP_SOC, true, 1, START, STOP);
		assertTrue(sut.isInsideWindow(LocalTime.MIDNIGHT));
		assertTrue(sut.isInsideWindow(LocalTime.of(5, 59, 59)));
		assertFalse(sut.isInsideWindow(LocalTime.of(6, 0)));
		assertFalse(sut.isInsideWindow(LocalTime.of(23, 59, 59)));
	}

	@Test
	public void testStopsTheChargeAtTheSocAfterFreshReads() {
		// 13 Nov at Sezibwa: charged from 11 % to 99 % although E03B was 50.
		var device = new Device(1);
		device.soc(11, 3);
		assertEquals(State.CHARGING, device.cycle(at(0, 0)).state());
		device.soc(49, 3);
		assertEquals(State.CHARGING, device.cycles(at(2, 0), 5).state());
		assertEquals(0, device.writes);
		// Boundary: 50 % counts, but only after three fresh reads.
		device.soc(50, 2);
		var step = device.cycle(at(2, 10));
		assertEquals(State.CHARGING, step.state());
		assertEquals(1, step.enable());
		device.soc(50, 1);
		step = device.cycle(at(2, 11));
		assertEquals(State.STOPPED, step.state());
		assertEquals(0, step.enable());
		assertNotNull(step.message());
		// Disarmed with read-back: exactly one write, the window is settled.
		device.cycles(at(2, 12), 3);
		assertEquals(0, device.enable);
		assertEquals(1, device.writes);
		assertEquals(ScheduleWindow.State.DONE, device.window.getState());
		assertEquals(1, device.sut.writesTodayForTest(NIGHT));
	}

	@Test
	public void testAStaleReadBelowTheStopRestartsTheCount() {
		var device = new Device(1);
		device.soc(50, 2);
		device.soc(49, 1);
		device.soc(50, 2);
		assertEquals(State.CHARGING, device.cycle(at(3, 0)).state());
		// An unreadable SoC also restarts it.
		device.sut.onSocRead(null);
		device.soc(50, 2);
		assertEquals(State.CHARGING, device.cycle(at(3, 1)).state());
		assertEquals(0, device.writes);
	}

	@Test
	public void testNoRearmInTheSameWindowThenRearmAfterItAndTheNextNightCharges() {
		var device = new Device(1);
		device.soc(55, 3);
		device.cycles(at(1, 0), 3);
		assertEquals(0, device.enable);
		// Hysteresis: the SoC falls far below the stop, still no re-arm in this window.
		device.soc(20, 3);
		assertEquals(State.STOPPED, device.cycles(at(4, 0), 3).state());
		assertEquals(State.STOPPED, device.cycles(at(5, 59), 3).state());
		// The window is over, but the re-arm waits for the delay.
		assertEquals(State.STOPPED, device.cycles(at(6, 0), 3).state());
		assertEquals(State.STOPPED, device.cycles(at(6, 14), 3).state());
		assertEquals(0, device.enable);
		assertEquals(1, device.writes);
		// 06:15: re-armed through the verified path.
		var step = device.cycle(at(6, 15));
		assertEquals(State.OUTSIDE_WINDOW, step.state());
		assertEquals(1, step.enable());
		assertNotNull(step.message());
		device.cycles(at(6, 16), 3);
		assertEquals(1, device.enable);
		assertEquals(2, device.writes);
		assertEquals(ScheduleWindow.State.DONE, device.window.getState());
		// The next night charges again and stops again.
		var next = NIGHT.plusDays(1);
		device.soc(20, 3);
		assertEquals(State.CHARGING, device.cycles(at(next, 0, 0), 3).state());
		assertEquals(2, device.writes);
		device.soc(50, 3);
		assertEquals(State.STOPPED, device.cycles(at(next, 3, 30), 3).state());
		assertEquals(0, device.enable);
		assertEquals(3, device.writes);
		assertEquals(1, device.sut.writesTodayForTest(next));
	}

	@Test
	public void testHighSocOutsideTheWindowWritesNothing() {
		var device = new Device(1);
		device.soc(100, 3);
		assertEquals(State.OUTSIDE_WINDOW, device.cycles(at(6, 0), 3).state());
		assertEquals(State.OUTSIDE_WINDOW, device.cycles(at(14, 0), 3).state());
		assertEquals(State.OUTSIDE_WINDOW, device.cycles(at(23, 59), 3).state());
		assertEquals(0, device.writes);
		assertEquals(1, device.enable);
	}

	@Test
	public void testRestartInTheWindowDoesNotResumeTheCharge() {
		// A new instance (restart) at 03:00 finds the device disarmed after a stop.
		var device = new Device(0);
		device.soc(48, 3); // inside the hysteresis band (45..49)
		var step = device.cycles(at(3, 0), 3);
		assertEquals(State.HELD_DISARMED, step.state());
		assertEquals(0, step.enable());
		assertEquals(0, device.writes);
		// Clearly below the stop: arming in the window is allowed again.
		device.soc(44, 3);
		step = device.cycles(at(3, 5), 3);
		assertEquals(State.CHARGING, step.state());
		assertEquals(1, device.enable);
		assertEquals(1, device.writes);
	}

	@Test
	public void testRestartAfterTheWindowWaitsForTheDelayThenRearms() {
		var device = new Device(0);
		device.soc(60, 3);
		assertEquals(State.HELD_DISARMED, device.cycles(at(6, 5), 3).state());
		assertEquals(0, device.writes);
		// Right after activation a disarmed device is not armed (the clock may be
		// stale); after the arm hold it is.
		assertEquals(State.HELD_DISARMED, device.cycles(at(6, 15), NightChargeStop.ARM_HOLD_CYCLES - 4).state());
		assertEquals(0, device.writes);
		assertEquals(State.OUTSIDE_WINDOW, device.cycles(at(6, 15), 3).state());
		assertEquals(1, device.enable);
		assertEquals(1, device.writes);
	}

	@Test
	public void testUnknownTimeHoldsTheDecision() {
		var device = new Device(1);
		device.soc(60, 3);
		device.cycles(at(2, 0), 3);
		assertEquals(0, device.enable);
		var step = device.cycle(null);
		assertEquals(State.WAITING_FOR_DATA, step.state());
		assertEquals(0, step.enable());
		assertEquals(1, device.writes);
	}

	@Test
	public void testReadbackFailureIsRetriedAfterTheCooldown() {
		var device = new Device(1);
		device.failReadback = true;
		device.soc(60, 3);
		device.cycle(at(2, 0));
		assertEquals(ScheduleWindow.State.FAILED, device.window.getState());
		assertEquals(1, device.enable);
		var step = device.cycle(at(2, 0));
		assertEquals(State.WRITE_FAILED, step.state());
		assertEquals(0, step.enable());
		assertEquals(1, device.writes);
		// No retry before the cooldown.
		device.cycles(at(2, 1), NightChargeStop.RETRY_COOLDOWN_CYCLES - 2);
		assertEquals(1, device.writes);
		// The retry succeeds.
		device.failReadback = false;
		device.cycles(at(2, 2), 3);
		assertEquals(2, device.writes);
		assertEquals(0, device.enable);
		assertEquals(1, device.sut.failuresForTest());
		assertEquals(State.STOPPED, device.cycle(at(2, 3)).state());
	}

	@Test
	public void testFailedStopRetriesStopAtTheDailyWriteCeiling() {
		var device = new Device(1);
		device.failReadback = true;
		device.soc(60, 3);
		device.cycles(at(1, 0), NightChargeStop.RETRY_COOLDOWN_CYCLES * (NightChargeStop.MAX_DAILY_WRITES + 2));
		assertEquals(NightChargeStop.MAX_DAILY_WRITES, device.writes);
		assertEquals(State.DAILY_WRITE_LIMIT, device.cycle(at(1, 30)).state());
		// The device is left as it was: armed, the charge goes on.
		assertEquals(1, device.enable);
	}

	@Test
	public void testFailedRearmUsesTheFailureBudgetNotTheCeiling() {
		var device = new Device(1);
		device.soc(60, 3);
		device.cycles(at(1, 0), 3);
		assertEquals(0, device.enable);
		// Every re-arm read-back fails.
		device.failReadback = true;
		device.cycle(at(6, 14));
		device.cycles(at(6, 15), NightChargeStop.RETRY_COOLDOWN_CYCLES * (NightChargeStop.MAX_FAILURES + 3));
		assertEquals(1 + 1 + NightChargeStop.MAX_FAILURES, device.writes);
		assertEquals(NightChargeStop.MAX_FAILURES, device.sut.failuresForTest());
		assertEquals(State.WRITE_FAILED, device.cycle(at(7, 0)).state());
		// Next night, still disarmed, battery low: the new day refills the budget and
		// the re-arm is retried, so a failed re-arm never loses the night charge for
		// good.
		device.failReadback = false;
		device.soc(20, 3);
		var step = device.cycles(at(NIGHT.plusDays(1), 0, 5), NightChargeStop.RETRY_COOLDOWN_CYCLES + 3);
		assertEquals(State.CHARGING, step.state());
		assertEquals(1, device.enable);
	}

	@Test
	public void testClockJumpPastTheRearmTimeDoesNotRearmInTheWindow() {
		// Review scenario A: stopped at 03:00, then the edge clock steps to 07:00.
		var device = new Device(1);
		device.soc(60, 3);
		device.cycles(at(3, 0), 3);
		assertEquals(0, device.enable);
		var step = device.cycle(at(7, 0));
		assertEquals(State.STOPPED, step.state());
		assertEquals(0, step.enable());
		// Steady at the stepped time for less than the confirmation: still stopped.
		assertEquals(State.STOPPED, device.walk(at(7, 0), at(8, 59)).state());
		assertEquals(0, device.enable);
		// Corrected back into the window: still stopped, no second write.
		assertEquals(State.STOPPED, device.cycles(at(3, 10), 3).state());
		assertEquals(1, device.writes);
		// The real window end, crossed in small steps: re-armed at 06:15.
		assertEquals(State.STOPPED, device.walk(at(3, 11), at(6, 14)).state());
		assertEquals(State.OUTSIDE_WINDOW, device.cycle(at(6, 15)).state());
		device.cycles(at(6, 15), 2);
		assertEquals(1, device.enable);
		assertEquals(2, device.writes);
	}

	@Test
	public void testSteppedClockRearmsAfterTheConfirmation() {
		// A forward step that was a real correction: re-armed after REARM_CONFIRM.
		var device = new Device(1);
		device.soc(60, 3);
		device.cycles(at(3, 0), 3);
		var confirmed = at(7, 0).plus(NightChargeStop.REARM_CONFIRM);
		assertEquals(State.STOPPED, device.walk(at(7, 0), confirmed.minusMinutes(1)).state());
		assertEquals(0, device.enable);
		assertEquals(State.OUTSIDE_WINDOW, device.cycle(confirmed).state());
		device.cycles(confirmed, 2);
		assertEquals(1, device.enable);
	}

	@Test
	public void testBackwardStepAfterTheRearmStopsAgain() {
		var device = new Device(1);
		device.soc(60, 3);
		device.cycles(at(3, 0), 3);
		device.walk(at(6, 0), at(6, 16));
		assertEquals(1, device.enable);
		assertEquals(2, device.writes);
		// The clock steps back into the window with the battery full: stopped again
		// (the safe direction), then re-armed once the window end is crossed again.
		assertEquals(State.STOPPED, device.cycles(at(5, 0), 3).state());
		assertEquals(0, device.enable);
		assertEquals(State.OUTSIDE_WINDOW, device.walk(at(5, 1), at(6, 15)).state());
		device.cycles(at(6, 15), 2);
		assertEquals(1, device.enable);
		assertEquals(4, device.writes);
	}

	@Test
	public void testPlausibleTimeGate() {
		var sut = NightChargeStop.fromConfig(STOP_SOC, true, 1, START, STOP);
		var zone = java.time.ZoneId.of("Africa/Kampala");
		assertNull(sut.plausibleTime(null, zone));
		assertNull(sut.plausibleTime(java.time.Instant.parse("2026-10-31T23:59:00Z"), zone));
		var t = java.time.Instant.parse("2026-11-13T00:00:00Z");
		for (var i = 0; i < ModeSchedule.CLOCK_SETTLE_CYCLES; i++) {
			assertNull(sut.plausibleTime(t = t.plusSeconds(1), zone));
		}
		assertNotNull(sut.plausibleTime(t = t.plusSeconds(1), zone));
		// A forward jump past the step limit restarts the settle count.
		assertNull(sut.plausibleTime(t = t.plusSeconds(4 * 3600), zone));
		for (var i = 0; i < ModeSchedule.CLOCK_SETTLE_CYCLES - 1; i++) {
			assertNull(sut.plausibleTime(t = t.plusSeconds(1), zone));
		}
		assertNotNull(sut.plausibleTime(t = t.plusSeconds(1), zone));
		// So does a backward step.
		assertNull(sut.plausibleTime(t.minusSeconds(60), zone));
	}

	@Test
	public void testRestartOnAStaleClockDoesNotArm() {
		// Review scenario B: restart at real 03:00 after a stop, the clock still reads
		// yesterday 22:00 (plausible, outside the window and the hold span).
		var device = new Device(0);
		device.soc(48, 3);
		var step = device.cycles(at(NIGHT.minusDays(1), 22, 0), 30);
		assertEquals(State.HELD_DISARMED, step.state());
		assertEquals(0, step.enable());
		// NTP corrects to 03:00: inside the hold span, still held.
		assertEquals(State.HELD_DISARMED, device.cycles(at(3, 0), 30).state());
		assertEquals(0, device.writes);
	}

	@Test
	public void testUnknownTimeFailsOpenAfterTheCap() {
		var device = new Device(1);
		device.soc(60, 3);
		device.cycles(at(3, 0), 3);
		assertEquals(0, device.enable);
		var step = device.cycles(null, NightChargeStop.UNKNOWN_TIME_CAP_CYCLES);
		assertEquals(State.WAITING_FOR_DATA, step.state());
		assertEquals(0, step.enable());
		assertEquals(0, device.enable);
		step = device.cycle(null);
		assertEquals(1, step.enable());
		assertNotNull(step.message());
		device.cycles(null, 2);
		assertEquals(1, device.enable);
	}

	@Test
	public void testFailedRearmThenNextNightInTheHysteresisBandStaysOff() {
		var device = new Device(1);
		device.soc(60, 3);
		device.cycles(at(1, 0), 3);
		device.failReadback = true;
		device.walk(at(6, 0), at(7, 0));
		assertEquals(0, device.enable);
		device.failReadback = false;
		// Next night at 47 % (between stop - 5 and the stop): held off, no write. The
		// battery is already near the stop SoC, so nothing is lost.
		device.soc(47, 3);
		var writes = device.writes;
		var step = device.cycles(at(NIGHT.plusDays(1), 0, 30), NightChargeStop.RETRY_COOLDOWN_CYCLES + 3);
		assertEquals(State.HELD_DISARMED, step.state());
		assertEquals(0, step.enable());
		assertEquals(writes, device.writes);
	}

	@Test
	public void testDailyLimitThenRearmWithTheDeviceStillArmedWritesNothing() {
		var device = new Device(1);
		device.failReadback = true;
		device.soc(60, 3);
		device.walk(at(0, 0), at(5, 0));
		assertEquals(NightChargeStop.MAX_DAILY_WRITES, device.writes);
		assertEquals(State.DAILY_WRITE_LIMIT, device.cycle(at(5, 1)).state());
		assertEquals(1, device.enable);
		device.failReadback = false;
		// 06:15: the target returns to 1, which the device already reads: no write.
		device.walk(at(5, 2), at(6, 20));
		assertEquals(NightChargeStop.MAX_DAILY_WRITES, device.writes);
		assertEquals(1, device.enable);
		assertEquals(State.OUTSIDE_WINDOW, device.cycle(at(6, 21)).state());
	}

	@Test
	public void testRearmTimeAfterMidnightForALateWindow() {
		// Window 20:00-23:50: the re-arm time is 00:05 the next day.
		var late = new Device(1, 20 * 256, 23 * 256 + 50);
		late.soc(60, 3);
		late.cycles(at(22, 0), 3);
		assertEquals(0, late.enable);
		var next = NIGHT.plusDays(1);
		assertEquals(State.STOPPED, late.walk(at(23, 40), at(next, 0, 4)).state());
		assertEquals(State.OUTSIDE_WINDOW, late.cycle(at(next, 0, 5)).state());
		late.cycles(at(next, 0, 5), 2);
		assertEquals(1, late.enable);

		// After a restart (past the arm hold), a device read disarmed at 00:02 is in
		// yesterday's hold span: held until 00:05.
		var restarted = new Device(0, 20 * 256, 23 * 256 + 50);
		restarted.soc(48, 3);
		restarted.cycles(at(21, 0), NightChargeStop.ARM_HOLD_CYCLES);
		assertEquals(State.HELD_DISARMED, restarted.cycles(at(next, 0, 2), 3).state());
		assertEquals(0, restarted.writes);
		assertEquals(State.OUTSIDE_WINDOW, restarted.cycles(at(next, 0, 6), 3).state());
		assertEquals(1, restarted.enable);
	}

	@Test
	public void testOutageSpanningTheRearmTimeRearmsAtOnceWhenMainsReturns() {
		// step() runs only behind the machine-state gate; the clock gate runs every
		// cycle and sees the re-arm time crossed in small steps during the outage.
		var device = new Device(1);
		var zone = java.time.ZoneId.of("Africa/Kampala");
		var t = at(2, 0).atZone(zone).toInstant();
		for (var i = 0; i <= ModeSchedule.CLOCK_SETTLE_CYCLES; i++) {
			device.sut.plausibleTime(t = t.plusSeconds(1), zone);
		}
		device.soc(60, 3);
		var now = device.sut.plausibleTime(t = t.plusSeconds(1), zone);
		device.cycles(now, 3);
		assertEquals(0, device.enable);
		// Grid lost at 02:01: only the clock gate runs, one minute per cycle, until
		// 00:30 the next night.
		LocalDateTime seen = null;
		while (t.isBefore(at(NIGHT.plusDays(1), 0, 30).atZone(zone).toInstant())) {
			seen = device.sut.plausibleTime(t = t.plusSeconds(60), zone);
		}
		assertNotNull(seen);
		// Mains back: the latch was released at 06:15, so the next night charges now.
		device.soc(20, 3);
		var step = device.cycles(seen, 3);
		assertEquals(State.CHARGING, step.state());
		assertEquals(1, device.enable);
	}
}
