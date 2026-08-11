package io.openems.edge.ess.srne.batteryinverter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import io.openems.edge.bridge.modbus.api.task.Task.ExecuteState;

/**
 * Deep lifecycle tests for the coherent schedule-window write. Component tests
 * only reach {@code QUEUED} (the dummy bridge does not execute writes), so the
 * full write -&gt; execute -&gt; read-back -&gt; verify path, partial-failure,
 * timeout and the verified-then-enable ordering are exercised here at the unit
 * level, driving the state machine directly.
 */
public class ScheduleWindowTest {

	// Discharge window addresses on the SRNE: 0xE02D start, 0xE02E stop, 0xE033
	// enable. 18:00 = 4608, 23:59 = 5947, 06:00 = 1536.
	private static ScheduleWindow newDischargeWindow() {
		return new ScheduleWindow(0xE02D, 0xE033, "DISCHARGE");
	}

	@Test
	void fullCycleVerifiesWindowThenEnablesLast() {
		var sut = newDischargeWindow();

		// Device is off (0,0) and disabled (0); operator wants 18:00-23:59 enabled.
		var message = sut.reconcile(0, 0, 0, 4608, 5947, 1);
		assertNotNull(message);
		assertEquals(ScheduleWindow.State.WINDOW_QUEUED, sut.getState());
		// The exact pair values are queued (start=18:00, stop=23:59), not swapped.
		assertEquals(Integer.valueOf(4608), sut.queuedStart());
		assertEquals(Integer.valueOf(5947), sut.queuedStop());
		// The start+stop pair is queued together (atomic FC16 block).
		assertNotNull(sut.startWriteElement().getNextWriteValueAndReset());
		assertNotNull(sut.stopWriteElement().getNextWriteValueAndReset());
		// Enable is NOT queued yet - it is always written after the window verifies.
		assertNull(sut.enableWriteElement().getNextWriteValueAndReset());

		sut.onWindowExecute(ExecuteState.OK);
		assertEquals(ScheduleWindow.State.WINDOW_AWAITING_READBACK, sut.getState());

		// Fresh read-back of both registers matches the target -> window verified.
		sut.verifyStart(4608);
		assertEquals(ScheduleWindow.State.WINDOW_AWAITING_READBACK, sut.getState());
		sut.verifyStop(5947);
		assertEquals(ScheduleWindow.State.WINDOW_VERIFIED, sut.getState());

		// Only now does reconcile queue the enable flag.
		sut.reconcile(4608, 5947, 0, 4608, 5947, 1);
		assertEquals(ScheduleWindow.State.ENABLE_QUEUED, sut.getState());
		assertEquals(Integer.valueOf(1), sut.queuedEnable());
		assertNotNull(sut.enableWriteElement().getNextWriteValueAndReset());

		sut.onEnableExecute(ExecuteState.OK);
		assertEquals(ScheduleWindow.State.ENABLE_AWAITING_READBACK, sut.getState());
		sut.verifyEnable(1);
		assertEquals(ScheduleWindow.State.DONE, sut.getState());
	}

	@Test
	void enableIsNeverQueuedWhileWindowStillPending() {
		var sut = newDischargeWindow();
		sut.reconcile(0, 0, 0, 4608, 5947, 1);
		sut.onWindowExecute(ExecuteState.OK);
		assertEquals(ScheduleWindow.State.WINDOW_AWAITING_READBACK, sut.getState());

		// Reconciling again while the window read-back is still pending must do nothing
		// and must never queue the enable flag.
		assertNull(sut.reconcile(0, 0, 0, 4608, 5947, 1));
		assertEquals(ScheduleWindow.State.WINDOW_AWAITING_READBACK, sut.getState());
		assertNull(sut.enableWriteElement().getNextWriteValueAndReset());
	}

	@Test
	void partialReadbackFailureNeverEnables() {
		var sut = newDischargeWindow();
		sut.reconcile(0, 0, 0, 4608, 5947, 1);
		sut.onWindowExecute(ExecuteState.OK);

		// Start reads back correctly, stop does not: the whole window fails and the
		// enable flag is never touched, so the schedule stays disabled (safe).
		sut.verifyStart(4608);
		sut.verifyStop(1234);
		assertEquals(ScheduleWindow.State.FAILED, sut.getState());

		assertNull(sut.reconcile(4608, 1234, 0, 4608, 5947, 1));
		assertEquals(ScheduleWindow.State.FAILED, sut.getState());
		assertNull(sut.enableWriteElement().getNextWriteValueAndReset());
	}

	@Test
	void windowExecuteErrorFailsWithoutRetry() {
		var sut = newDischargeWindow();
		sut.reconcile(0, 0, 0, 4608, 5947, 1);
		sut.onWindowExecute(new ExecuteState.Error(new RuntimeException("bus error")));
		assertEquals(ScheduleWindow.State.FAILED, sut.getState());
		// No automatic retry.
		assertNull(sut.reconcile(0, 0, 0, 4608, 5947, 1));
		assertEquals(ScheduleWindow.State.FAILED, sut.getState());
	}

	@Test
	void missingWindowReadbackTimesOut() {
		var sut = newDischargeWindow();
		sut.reconcile(0, 0, 0, 4608, 5947, 1);
		sut.onWindowExecute(ExecuteState.OK);
		for (var i = 1; i < 30; i++) {
			sut.onCycle(30);
			assertEquals(ScheduleWindow.State.WINDOW_AWAITING_READBACK, sut.getState());
		}
		sut.onCycle(30);
		assertEquals(ScheduleWindow.State.FAILED, sut.getState());
	}

	@Test
	void queuedWindowDoesNotTimeOutBeforeExecute() {
		var sut = newDischargeWindow();
		sut.reconcile(0, 0, 0, 4608, 5947, 1);
		// onCycle only bounds the read-back wait; a queued-but-not-executed write must
		// not time out.
		for (var i = 0; i < 60; i++) {
			sut.onCycle(30);
		}
		assertEquals(ScheduleWindow.State.WINDOW_QUEUED, sut.getState());
	}

	@Test
	void crossMidnightWindowIsRejected() {
		var sut = newDischargeWindow();
		// 18:00 -> 06:00 crosses midnight (start >= stop); undefined on the SRNE.
		assertNotNull(sut.reconcile(0, 0, 0, 4608, 1536, 1));
		assertEquals(ScheduleWindow.State.FAILED, sut.getState());
		assertNull(sut.startWriteElement().getNextWriteValueAndReset());
		assertNull(sut.enableWriteElement().getNextWriteValueAndReset());
	}

	@Test
	void zeroLengthWindowIsRejected() {
		var sut = newDischargeWindow();
		assertNotNull(sut.reconcile(0, 0, 0, 4608, 4608, 1));
		assertEquals(ScheduleWindow.State.FAILED, sut.getState());
	}

	@Test
	void incompleteWindowIsRejected() {
		var sut = newDischargeWindow();
		// Start configured, stop left at -1.
		assertNotNull(sut.reconcile(0, 0, 0, 4608, -1, -1));
		assertEquals(ScheduleWindow.State.FAILED, sut.getState());
		assertNull(sut.startWriteElement().getNextWriteValueAndReset());
	}

	@Test
	void enableWithoutConfiguredWindowIsRejected() {
		var sut = newDischargeWindow();
		// enable=1 but no window configured: cannot arm a schedule we did not verify.
		assertNotNull(sut.reconcile(0, 0, 0, -1, -1, 1));
		assertEquals(ScheduleWindow.State.FAILED, sut.getState());
		assertNull(sut.enableWriteElement().getNextWriteValueAndReset());
	}

	// JUSTIFICATION-A3: new coverage for the disarm-first ordering and the enable
	// range guard added in the PR #24 revision (reviewer findings). These are new
	// behaviours, not a wrapper around existing assertions.
	@Test
	void disableOnlyWritesEnableWithoutWindow() {
		var sut = newDischargeWindow();
		// Times unmanaged, enable=0 to turn an existing schedule off: always safe and
		// written straight away (disarm-first), no window touched.
		sut.reconcile(4608, 5947, 1, -1, -1, 0);
		assertEquals(ScheduleWindow.State.DISABLE_QUEUED, sut.getState());
		assertEquals(Integer.valueOf(0), sut.queuedEnable());
		assertNull(sut.startWriteElement().getNextWriteValueAndReset());
		assertNotNull(sut.enableWriteElement().getNextWriteValueAndReset());
	}

	@Test
	void enableOutOfRangeIsRejected() {
		var sut = newDischargeWindow();
		// enable=2 is not a boolean flag; reject before any register is written.
		assertNotNull(sut.reconcile(0, 0, 0, 4608, 5947, 2));
		assertEquals(ScheduleWindow.State.FAILED, sut.getState());
		assertNull(sut.enableWriteElement().getNextWriteValueAndReset());
		assertNull(sut.startWriteElement().getNextWriteValueAndReset());
	}

	@Test
	void disableIsWrittenFirstThenWindowReprogrammed() {
		var sut = newDischargeWindow();
		// Armed at 00:00-06:00; operator sets a new window 18:00-23:59 AND enable=0.
		// The disable must be written FIRST, before the window is touched, so a failing
		// window write can never leave the schedule armed.
		sut.reconcile(0, 1536, 1, 4608, 5947, 0);
		assertEquals(ScheduleWindow.State.DISABLE_QUEUED, sut.getState());
		assertEquals(Integer.valueOf(0), sut.queuedEnable());
		assertNull(sut.startWriteElement().getNextWriteValueAndReset()); // window untouched
		assertNotNull(sut.enableWriteElement().getNextWriteValueAndReset());

		sut.onEnableExecute(ExecuteState.OK);
		assertEquals(ScheduleWindow.State.DISABLE_AWAITING_READBACK, sut.getState());
		sut.verifyEnable(0);
		assertEquals(ScheduleWindow.State.DISABLE_VERIFIED, sut.getState());

		// Device now reads disabled; the window is reprogrammed.
		sut.reconcile(0, 1536, 0, 4608, 5947, 0);
		assertEquals(ScheduleWindow.State.WINDOW_QUEUED, sut.getState());
		assertNotNull(sut.startWriteElement().getNextWriteValueAndReset());
		assertNotNull(sut.stopWriteElement().getNextWriteValueAndReset());
		sut.onWindowExecute(ExecuteState.OK);
		sut.verifyStart(4608);
		sut.verifyStop(5947);
		assertEquals(ScheduleWindow.State.WINDOW_VERIFIED, sut.getState());

		// enable stays 0: the schedule is never re-armed.
		sut.reconcile(4608, 5947, 0, 4608, 5947, 0);
		assertEquals(ScheduleWindow.State.DONE, sut.getState());
		assertNull(sut.enableWriteElement().getNextWriteValueAndReset());
	}

	@Test
	void matchingWindowSkipsStraightToEnable() {
		var sut = newDischargeWindow();
		// Device already at 18:00-23:59 but disabled; operator wants it enabled. No
		// window write is needed - go straight to the enable step.
		sut.reconcile(4608, 5947, 0, 4608, 5947, 1);
		assertEquals(ScheduleWindow.State.ENABLE_QUEUED, sut.getState());
		assertNull(sut.startWriteElement().getNextWriteValueAndReset());
		assertNull(sut.stopWriteElement().getNextWriteValueAndReset());
		assertNotNull(sut.enableWriteElement().getNextWriteValueAndReset());
	}

	@Test
	void fullyMatchingScheduleIsDoneWithoutWrites() {
		var sut = newDischargeWindow();
		// Window and enable already match the target: nothing to write.
		assertNull(sut.reconcile(4608, 5947, 1, 4608, 5947, 1));
		assertEquals(ScheduleWindow.State.DONE, sut.getState());
		assertNull(sut.startWriteElement().getNextWriteValueAndReset());
		assertNull(sut.enableWriteElement().getNextWriteValueAndReset());
	}

	@Test
	void unconfiguredWindowStaysIdle() {
		var sut = newDischargeWindow();
		assertNull(sut.reconcile(0, 0, 0, -1, -1, -1));
		assertEquals(ScheduleWindow.State.IDLE, sut.getState());
		assertNull(sut.startWriteElement().getNextWriteValueAndReset());
		assertNull(sut.enableWriteElement().getNextWriteValueAndReset());
	}

	@Test
	void changedWindowOnArmedDeviceDisarmsFirstThenRearms() {
		var sut = newDischargeWindow();
		// Device is ARMED (enable=1) with an old window; operator sets a new window and
		// keeps it enabled. The window must NOT be written while armed: disarm first.
		sut.reconcile(0, 0, 1, 4608, 5947, 1);
		assertEquals(ScheduleWindow.State.DISABLE_QUEUED, sut.getState());
		assertEquals(Integer.valueOf(0), sut.queuedEnable());
		assertNull(sut.startWriteElement().getNextWriteValueAndReset()); // window untouched
		assertNotNull(sut.enableWriteElement().getNextWriteValueAndReset());

		sut.onEnableExecute(ExecuteState.OK);
		sut.verifyEnable(0);
		assertEquals(ScheduleWindow.State.DISABLE_VERIFIED, sut.getState());

		// Now disarmed -> the window pair is written atomically.
		sut.reconcile(0, 0, 0, 4608, 5947, 1);
		assertEquals(ScheduleWindow.State.WINDOW_QUEUED, sut.getState());
		assertEquals(Integer.valueOf(4608), sut.queuedStart());
		assertEquals(Integer.valueOf(5947), sut.queuedStop());
		sut.startWriteElement().getNextWriteValueAndReset();
		sut.stopWriteElement().getNextWriteValueAndReset();
		sut.onWindowExecute(ExecuteState.OK);
		sut.verifyStart(4608);
		sut.verifyStop(5947);
		assertEquals(ScheduleWindow.State.WINDOW_VERIFIED, sut.getState());

		// Finally re-armed, last.
		sut.reconcile(4608, 5947, 0, 4608, 5947, 1);
		assertEquals(ScheduleWindow.State.ENABLE_QUEUED, sut.getState());
		assertEquals(Integer.valueOf(1), sut.queuedEnable());
		sut.onEnableExecute(ExecuteState.OK);
		sut.verifyEnable(1);
		assertEquals(ScheduleWindow.State.DONE, sut.getState());
	}

	// JUSTIFICATION-A3: new invariant-robustness test (re-review advisory) proving the
	// window is never written if the device enable flaps back to 1 mid-sequence.
	@Test
	void flappedEnableAtDisableVerifiedReDisarmsNeverWritesWindow() {
		var sut = newDischargeWindow();
		// Reach DISABLE_VERIFIED (armed device + changed window).
		sut.reconcile(0, 0, 1, 4608, 5947, 1);
		assertEquals(ScheduleWindow.State.DISABLE_QUEUED, sut.getState());
		sut.enableWriteElement().getNextWriteValueAndReset();
		sut.onEnableExecute(ExecuteState.OK);
		sut.verifyEnable(0);
		assertEquals(ScheduleWindow.State.DISABLE_VERIFIED, sut.getState());

		// The device enable flaps back to 1 before the window write. The invariant must
		// hold: re-disarm, never write the window while armed.
		sut.reconcile(0, 0, 1, 4608, 5947, 1);
		assertEquals(ScheduleWindow.State.DISABLE_QUEUED, sut.getState());
		assertEquals(Integer.valueOf(0), sut.queuedEnable());
		assertNull(sut.startWriteElement().getNextWriteValueAndReset());
		assertNull(sut.stopWriteElement().getNextWriteValueAndReset());
	}

	@Test
	void changedWindowOnArmedDeviceWithUnmanagedEnableRestoresArmed() {
		var sut = newDischargeWindow();
		// Device is ARMED (enable=1); operator changes only the window and leaves enable
		// at -1. Must disarm, rewrite, then RESTORE the captured armed state - never
		// mutate a live enabled window, and never silently leave it disabled.
		sut.reconcile(0, 0, 1, 4608, 5947, -1);
		assertEquals(ScheduleWindow.State.DISABLE_QUEUED, sut.getState());
		assertEquals(Integer.valueOf(1), sut.desiredEnable()); // captured original armed state
		assertNull(sut.startWriteElement().getNextWriteValueAndReset()); // window untouched

		sut.onEnableExecute(ExecuteState.OK);
		sut.verifyEnable(0);
		assertEquals(ScheduleWindow.State.DISABLE_VERIFIED, sut.getState());

		sut.reconcile(0, 0, 0, 4608, 5947, -1);
		assertEquals(ScheduleWindow.State.WINDOW_QUEUED, sut.getState());
		sut.startWriteElement().getNextWriteValueAndReset();
		sut.stopWriteElement().getNextWriteValueAndReset();
		sut.onWindowExecute(ExecuteState.OK);
		sut.verifyStart(4608);
		sut.verifyStop(5947);
		assertEquals(ScheduleWindow.State.WINDOW_VERIFIED, sut.getState());

		// Restored to armed (captured enable=1), not left disabled.
		sut.reconcile(4608, 5947, 0, 4608, 5947, -1);
		assertEquals(ScheduleWindow.State.ENABLE_QUEUED, sut.getState());
		assertEquals(Integer.valueOf(1), sut.queuedEnable());
		sut.onEnableExecute(ExecuteState.OK);
		sut.verifyEnable(1);
		assertEquals(ScheduleWindow.State.DONE, sut.getState());
	}

	@Test
	void timeAndWindowValidation() {
		assertTrue(ScheduleWindow.isValidEncodedTime(0)); // 00:00
		assertTrue(ScheduleWindow.isValidEncodedTime(5947)); // 23:59
		assertFalse(ScheduleWindow.isValidEncodedTime(60)); // 00:60
		assertFalse(ScheduleWindow.isValidEncodedTime(6144)); // 24:00
		assertFalse(ScheduleWindow.isValidEncodedTime(-1));

		assertTrue(ScheduleWindow.isValidWindow(0, 1536)); // 00:00-06:00
		assertTrue(ScheduleWindow.isValidWindow(4608, 5947)); // 18:00-23:59
		assertFalse(ScheduleWindow.isValidWindow(4608, 1536)); // cross-midnight
		assertFalse(ScheduleWindow.isValidWindow(4608, 4608)); // zero length
		assertFalse(ScheduleWindow.isValidWindow(6400, 5947)); // invalid start (hour 25)

		assertEquals("18:00", ScheduleWindow.formatEncodedTime(4608));
		assertEquals("23:59", ScheduleWindow.formatEncodedTime(5947));
	}
}
