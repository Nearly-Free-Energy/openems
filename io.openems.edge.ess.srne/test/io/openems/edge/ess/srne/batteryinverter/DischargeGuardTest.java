package io.openems.edge.ess.srne.batteryinverter;

import static io.openems.edge.ess.srne.batteryinverter.DischargeSuppressionReason.FLOOR_REACHED;
import static io.openems.edge.ess.srne.batteryinverter.DischargeSuppressionReason.LOW_START;
import static io.openems.edge.ess.srne.batteryinverter.DischargeSuppressionReason.NONE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDateTime;

import org.junit.jupiter.api.Test;

public class DischargeGuardTest {

	private static final int START = 21 * 256; // 21:00
	private static final int STOP = 23 * 256; // 23:00

	private static LocalDateTime at(int hour, int minute) {
		return LocalDateTime.of(2026, 1, 10, hour, minute, 0);
	}

	private static LocalDateTime at(int hour, int minute, int second) {
		return LocalDateTime.of(2026, 1, 10, hour, minute, second);
	}

	private static LocalDateTime nextDay(int hour, int minute) {
		return LocalDateTime.of(2026, 1, 11, hour, minute, 0);
	}

	// One evaluation per minute (never a clock step), returns the last decision.
	private static DischargeSuppressionReason run(DischargeGuard guard, LocalDateTime from, LocalDateTime to,
			Integer soc) {
		DischargeSuppressionReason result = null;
		for (var t = from; !t.isAfter(to); t = t.plusMinutes(1)) {
			result = guard.evaluate(t, START, STOP, soc);
		}
		return result;
	}

	@Test
	public void testInactiveWhenBothOff() {
		assertFalse(new DischargeGuard(-1, -1).isActive());
		assertTrue(new DischargeGuard(75, -1).isActive());
		assertTrue(new DischargeGuard(-1, 45).isActive());
	}

	@Test
	public void testLowSocAtStartSuppressesUntilWindowEnds() {
		var guard = new DischargeGuard(75, 45);
		assertEquals(NONE, guard.evaluate(at(20, 59), START, STOP, 60));
		assertEquals(NONE, guard.evaluate(at(21, 0, 0), START, STOP, 74)); // first sighting only
		assertEquals(LOW_START, guard.evaluate(at(21, 0, 1), START, STOP, 74));
		// Held even if SoC recovers (e.g. PV/grid charge) for the rest of the window.
		assertEquals(LOW_START, run(guard, at(21, 2), at(22, 59), 90));
		assertEquals(NONE, guard.evaluate(at(23, 0), START, STOP, 90));
	}

	@Test
	public void testStartThresholdIsInclusiveOfEqualSoc() {
		assertEquals(NONE, run(new DischargeGuard(75, -1), at(21, 0), at(21, 2), 75));
	}

	@Test
	public void testStartRuleExpiresAfterFirstMinutes() {
		// Restart or config change after the start rule expired: only the floor applies.
		assertEquals(NONE, run(new DischargeGuard(75, 45), at(21, 10), at(21, 12), 60));
		assertEquals(NONE, run(new DischargeGuard(75, 45), at(22, 30), at(22, 32), 60));
		assertEquals(LOW_START, run(new DischargeGuard(75, 45), at(21, 8), at(21, 9), 60));
		assertEquals(FLOOR_REACHED, run(new DischargeGuard(75, 45), at(22, 30), at(22, 31), 45));
	}

	@Test
	public void testStartPassedThenDipDoesNotSuppress() {
		var guard = new DischargeGuard(75, 45);
		assertEquals(NONE, run(guard, at(21, 0), at(21, 1), 80));
		// Normal discharge drops SoC below the start threshold within the same window.
		assertEquals(NONE, run(guard, at(21, 2), at(21, 20), 70));
	}

	@Test
	public void testFloorMidWindowThenRestoredAfterWindow() {
		var guard = new DischargeGuard(75, 45);
		assertEquals(NONE, run(guard, at(21, 0), at(21, 29), 80));
		assertEquals(NONE, run(guard, at(21, 30), at(21, 59), 46));
		assertEquals(FLOOR_REACHED, run(guard, at(22, 0), at(22, 1), 45));
		assertEquals(FLOOR_REACHED, run(guard, at(22, 2), at(22, 59), 50));
		assertEquals(NONE, guard.evaluate(at(23, 0), START, STOP, 50));
		// Next night runs again.
		assertEquals(NONE, run(guard, at(23, 1), nextDay(20, 59), 80));
		assertEquals(NONE, run(guard, nextDay(21, 0), nextDay(21, 1), 80));
	}

	@Test
	public void testRestartMidWindowDecidesFromCurrentState() {
		assertEquals(FLOOR_REACHED, run(new DischargeGuard(75, 45), at(21, 0), at(21, 1), 40));
		assertEquals(LOW_START, run(new DischargeGuard(75, 45), at(21, 0), at(21, 1), 60));
		assertEquals(NONE, run(new DischargeGuard(75, 45), at(21, 0), at(21, 1), 80));
		assertEquals(NONE, run(new DischargeGuard(75, 45), at(21, 20), at(21, 21), 60));
	}

	@Test
	public void testUnknownSocChangesNothingAndWarnsOncePerWindow() {
		var guard = new DischargeGuard(75, 45);
		assertEquals(NONE, run(guard, at(21, 0), at(21, 2), null));
		assertTrue(guard.pollUnusableSocWarning());
		assertFalse(guard.pollUnusableSocWarning());
		assertEquals(FLOOR_REACHED, run(guard, at(21, 3), at(21, 4), 10));
		assertEquals(FLOOR_REACHED, run(guard, at(21, 5), at(21, 6), null));
		assertFalse(guard.pollUnusableSocWarning());
		// A new window warns again.
		run(guard, at(21, 7), nextDay(20, 59), 60);
		assertEquals(NONE, run(guard, nextDay(21, 0), nextDay(21, 1), null));
		assertTrue(guard.pollUnusableSocWarning());
	}

	@Test
	public void testOutOfRangeSocIsIgnored() {
		var guard = new DischargeGuard(75, 45);
		assertEquals(NONE, run(guard, at(21, 0), at(21, 1), 65535));
		assertEquals(NONE, run(guard, at(21, 2), at(21, 3), -1));
		assertEquals(NONE, run(guard, at(21, 4), at(21, 5), 101));
		assertTrue(guard.pollUnusableSocWarning());
		// Not a latch: a real reading still decides afterwards.
		assertEquals(LOW_START, run(guard, at(21, 6), at(21, 7), 60));
	}

	@Test
	public void testSocZeroNeedsTwoConsecutiveReadings() {
		var guard = new DischargeGuard(75, -1);
		assertEquals(NONE, guard.evaluate(at(21, 0, 0), START, STOP, 0));
		assertEquals(NONE, guard.evaluate(at(21, 0, 1), START, STOP, 80)); // BMS read recovered
		assertEquals(NONE, guard.evaluate(at(21, 0, 2), START, STOP, 0));
		assertEquals(NONE, guard.evaluate(at(21, 0, 3), START, STOP, 80));
		// The recovered reading passed the start check, so the night runs.
		assertEquals(NONE, run(guard, at(21, 1), at(21, 2), 0));
		// The same holds for the floor rule.
		var floorGuard = new DischargeGuard(-1, 45);
		assertEquals(NONE, floorGuard.evaluate(at(22, 0, 0), START, STOP, 0));
		assertEquals(NONE, floorGuard.evaluate(at(22, 0, 1), START, STOP, 70));
		assertEquals(FLOOR_REACHED, run(floorGuard, at(22, 1), at(22, 2), 0));
	}

	@Test
	public void testClockStepHoldsLastDecision() {
		var guard = new DischargeGuard(75, 45);
		assertEquals(FLOOR_REACHED, run(guard, at(22, 0), at(22, 1), 40));
		assertFalse(guard.pollClockStepWarning());
		// Stale clock jumps past the window end: the step evaluation holds the decision.
		assertEquals(FLOOR_REACHED, guard.evaluate(at(23, 30), START, STOP, 40));
		assertTrue(guard.pollClockStepWarning());
		assertFalse(guard.pollClockStepWarning());
		// Consecutive consistent evaluations then apply normally.
		assertEquals(NONE, guard.evaluate(at(23, 30, 1), START, STOP, 40));
	}

	@Test
	public void testClockStepBackwardHolds() {
		var guard = new DischargeGuard(-1, 45);
		assertEquals(NONE, run(guard, at(22, 0), at(22, 1), 60));
		assertEquals(NONE, guard.evaluate(at(20, 0), START, STOP, 60));
		assertTrue(guard.pollClockStepWarning());
		assertTrue(guard.isInsideWindow()); // the held evaluation did not change anything
		assertEquals(NONE, guard.evaluate(at(20, 0, 1), START, STOP, 60));
		assertFalse(guard.isInsideWindow());
	}

	@Test
	public void testSmallClockDriftIsNotAStep() {
		var guard = new DischargeGuard(-1, 45);
		guard.evaluate(at(22, 0), START, STOP, 60);
		assertEquals(NONE, guard.evaluate(at(22, DischargeGuard.CLOCK_STEP_MINUTES), START, STOP, 60));
		assertFalse(guard.pollClockStepWarning());
	}

	@Test
	public void testStartRuleSuppressesAtMostOncePerDate() {
		var guard = new DischargeGuard(75, 45);
		assertEquals(LOW_START, run(guard, at(21, 0), at(21, 1), 60));
		assertEquals(NONE, run(guard, at(21, 2), at(23, 0), 60));
		// Clock flaps back into the first minutes of the window the same date.
		guard.evaluate(at(21, 2), START, STOP, 60); // the step itself is held
		assertEquals(NONE, run(guard, at(21, 2), at(21, 4), 60));
		// The floor rule is never blocked by the dwell.
		assertEquals(FLOOR_REACHED, run(guard, at(21, 5), at(21, 6), 40));
		// The next date starts afresh.
		assertEquals(NONE, run(guard, at(21, 7), nextDay(20, 59), 60));
		assertEquals(LOW_START, run(guard, nextDay(21, 0), nextDay(21, 1), 60));
	}

	@Test
	public void testFloorOnlyAndStartOnly() {
		assertEquals(NONE, run(new DischargeGuard(-1, 45), at(22, 0), at(22, 1), 50));
		assertEquals(FLOOR_REACHED, run(new DischargeGuard(-1, 45), at(22, 0), at(22, 1), 45));
		assertEquals(NONE, run(new DischargeGuard(75, -1), at(22, 0), at(22, 1), 10));
		assertEquals(NONE, run(new DischargeGuard(75, -1), at(21, 0), at(21, 1), 80));
		assertEquals(LOW_START, run(new DischargeGuard(75, -1), at(21, 0), at(21, 1), 10));
	}

	@Test
	public void testHoldWithoutUsableSocIsCappedAndReleasedOnce() {
		var guard = new DischargeGuard(75, 45);
		assertFalse(guard.isHoldingArm()); // outside the window
		// One evaluation per minute from 21:00 without any SoC.
		for (var minute = 0; minute < DischargeGuard.HOLD_CAP_MINUTES; minute++) {
			guard.evaluate(at(21, minute), START, STOP, null);
			assertTrue(guard.isHoldingArm(), "minute " + minute);
			assertFalse(guard.pollHoldCapWarning());
		}
		// The cap is reached 10 minutes after window entry: fail open, warn exactly once.
		guard.evaluate(at(21, DischargeGuard.HOLD_CAP_MINUTES), START, STOP, null);
		assertFalse(guard.isHoldingArm());
		assertTrue(guard.pollHoldCapWarning());
		guard.evaluate(at(21, DischargeGuard.HOLD_CAP_MINUTES + 1), START, STOP, null);
		assertFalse(guard.isHoldingArm());
		assertFalse(guard.pollHoldCapWarning());
	}

	@Test
	public void testUsableSocBeforeTheCapEndsTheHoldWithoutWarning() {
		var guard = new DischargeGuard(75, 45);
		run(guard, at(21, 0), at(21, 5), null);
		assertTrue(guard.isHoldingArm());
		guard.evaluate(at(21, 6), START, STOP, 90);
		assertFalse(guard.isHoldingArm());
		run(guard, at(21, 7), at(21, 30), null);
		assertFalse(guard.pollHoldCapWarning());
	}

	@Test
	public void testClockStepIsNotCountedTowardsTheHoldCap() {
		var guard = new DischargeGuard(75, 45);
		guard.evaluate(at(21, 0), START, STOP, null);
		// A forward step of 30 minutes is held for that evaluation and adds nothing.
		guard.evaluate(at(21, 30), START, STOP, null);
		assertTrue(guard.isHoldingArm());
		guard.evaluate(at(21, 31), START, STOP, null);
		assertTrue(guard.isHoldingArm());
		run(guard, at(21, 32), at(21, 39), null);
		assertTrue(guard.isHoldingArm());
		run(guard, at(21, 40), at(21, 42), null);
		assertFalse(guard.isHoldingArm());
	}
}
