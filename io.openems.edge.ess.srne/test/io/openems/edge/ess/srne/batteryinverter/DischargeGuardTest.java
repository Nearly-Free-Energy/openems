package io.openems.edge.ess.srne.batteryinverter;

import static io.openems.edge.ess.srne.batteryinverter.DischargeSuppressionReason.FLOOR_REACHED;
import static io.openems.edge.ess.srne.batteryinverter.DischargeSuppressionReason.LOW_START;
import static io.openems.edge.ess.srne.batteryinverter.DischargeSuppressionReason.NONE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalTime;

import org.junit.jupiter.api.Test;

public class DischargeGuardTest {

	private static final int START = 18 * 256; // 18:00
	private static final int STOP = 23 * 256 + 59; // 23:59

	private static LocalTime at(int hour, int minute) {
		return LocalTime.of(hour, minute);
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
		assertEquals(NONE, guard.evaluate(at(17, 59), START, STOP, 60));
		assertEquals(LOW_START, guard.evaluate(at(18, 0), START, STOP, 74));
		// Held even if SoC recovers (e.g. PV/grid charge) for the rest of the window.
		assertEquals(LOW_START, guard.evaluate(at(20, 0), START, STOP, 90));
		assertEquals(NONE, guard.evaluate(at(23, 59), START, STOP, 90));
	}

	@Test
	public void testStartThresholdIsInclusiveOfEqualSoc() {
		var guard = new DischargeGuard(75, -1);
		assertEquals(NONE, guard.evaluate(at(18, 0), START, STOP, 75));
	}

	@Test
	public void testFloorMidWindowThenRestoredAfterWindow() {
		var guard = new DischargeGuard(75, 45);
		assertEquals(NONE, guard.evaluate(at(18, 0), START, STOP, 80));
		assertEquals(NONE, guard.evaluate(at(20, 0), START, STOP, 46));
		// Start threshold is not re-applied once passed, so normal discharge below it is fine.
		assertEquals(FLOOR_REACHED, guard.evaluate(at(21, 0), START, STOP, 45));
		assertEquals(FLOOR_REACHED, guard.evaluate(at(22, 0), START, STOP, 50));
		assertEquals(NONE, guard.evaluate(at(23, 59), START, STOP, 50));
		// Next night runs again.
		assertEquals(NONE, guard.evaluate(at(18, 0), START, STOP, 80));
	}

	@Test
	public void testRestartMidWindowDecidesFromCurrentState() {
		assertEquals(FLOOR_REACHED, new DischargeGuard(75, 45).evaluate(at(21, 0), START, STOP, 40));
		assertEquals(LOW_START, new DischargeGuard(75, 45).evaluate(at(21, 0), START, STOP, 60));
		assertEquals(NONE, new DischargeGuard(75, 45).evaluate(at(21, 0), START, STOP, 80));
		// Same input, same decision.
		var guard = new DischargeGuard(75, 45);
		assertEquals(FLOOR_REACHED, guard.evaluate(at(21, 0), START, STOP, 40));
		assertEquals(FLOOR_REACHED, guard.evaluate(at(21, 1), START, STOP, 40));
	}

	@Test
	public void testUnknownSocChangesNothing() {
		var guard = new DischargeGuard(75, 45);
		assertEquals(NONE, guard.evaluate(at(19, 0), START, STOP, null));
		assertEquals(FLOOR_REACHED, guard.evaluate(at(19, 1), START, STOP, 10));
		assertEquals(FLOOR_REACHED, guard.evaluate(at(19, 2), START, STOP, null));
	}

	@Test
	public void testFloorOnlyAndStartOnly() {
		assertEquals(NONE, new DischargeGuard(-1, 45).evaluate(at(19, 0), START, STOP, 50));
		assertEquals(FLOOR_REACHED, new DischargeGuard(-1, 45).evaluate(at(19, 0), START, STOP, 45));
		assertEquals(NONE, new DischargeGuard(75, -1).evaluate(at(19, 0), START, STOP, 80));
		assertEquals(LOW_START, new DischargeGuard(75, -1).evaluate(at(19, 0), START, STOP, 10));
	}
}
