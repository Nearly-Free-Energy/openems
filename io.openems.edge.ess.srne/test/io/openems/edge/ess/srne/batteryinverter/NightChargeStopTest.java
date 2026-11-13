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
		private final NightChargeStop sut = NightChargeStop.fromConfig(STOP_SOC, true, 1, START, STOP);
		private final ScheduleWindow window = new ScheduleWindow(0xE026, 0xE02C, "CHARGE");
		private Integer enable;
		private int writes;
		private boolean failReadback;

		private Device(int enable) {
			this.enable = enable;
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
			this.window.reconcile(START, STOP, this.enable, START, STOP, step.enable());
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
}
