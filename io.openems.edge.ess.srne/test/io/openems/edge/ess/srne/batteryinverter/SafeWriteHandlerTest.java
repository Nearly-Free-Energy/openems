package io.openems.edge.ess.srne.batteryinverter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import io.openems.edge.bridge.modbus.api.task.Task.ExecuteState;

public class SafeWriteHandlerTest {

	@Test
	void unchangedValueDoesNotWrite() {
		var sut = new SafeWriteHandler();
		assertFalse(sut.queueIfChanged(10, 10));
		assertEquals(SafeWriteHandler.State.IDLE, sut.getState());
	}

	@Test
	void successfulWriteRequiresMatchingReadback() {
		var sut = new SafeWriteHandler();
		assertTrue(sut.queueIfChanged(10, 20));
		sut.onExecute(ExecuteState.OK);
		assertEquals(SafeWriteHandler.State.AWAITING_READBACK, sut.getState());
		sut.verify(20);
		assertEquals(SafeWriteHandler.State.VERIFIED, sut.getState());
		assertFalse(sut.queueIfChanged(10, 20));
	}

	@Test
	void remainsPendingUntilFreshReadbackArrives() {
		var sut = new SafeWriteHandler();
		assertTrue(sut.queueIfChanged(10, 20));
		sut.onExecute(ExecuteState.OK);

		// Merely reconciling against the cached value must not verify or fail the write.
		assertFalse(sut.queueIfChanged(10, 20));
		assertEquals(SafeWriteHandler.State.AWAITING_READBACK, sut.getState());

		sut.verify(20);
		assertEquals(SafeWriteHandler.State.VERIFIED, sut.getState());
	}

	@Test
	void mismatchFailsWithoutRetry() {
		var sut = new SafeWriteHandler();
		assertTrue(sut.queueIfChanged(10, 20));
		sut.onExecute(ExecuteState.OK);
		sut.verify(15);
		assertEquals(SafeWriteHandler.State.FAILED, sut.getState());
		assertFalse(sut.queueIfChanged(15, 20));
	}

	@Test
	void missingReadbackTimesOutWithoutRetry() {
		var sut = new SafeWriteHandler();
		assertTrue(sut.queueIfChanged(10, 20));
		sut.onExecute(ExecuteState.OK);
		for (var i = 1; i < 30; i++) {
			sut.onCycle(30);
			assertEquals(SafeWriteHandler.State.AWAITING_READBACK, sut.getState());
		}
		sut.onCycle(30);
		assertEquals(SafeWriteHandler.State.FAILED, sut.getState());
		assertFalse(sut.queueIfChanged(10, 20));
	}

	@Test
	void rejectedTargetNeverQueuesAWrite() {
		var sut = new SafeWriteHandler();
		assertTrue(sut.reject());
		assertEquals(SafeWriteHandler.State.FAILED, sut.getState());
		assertFalse(sut.reject());
		assertFalse(sut.queueIfChanged(10, 20));
	}

	@Test
	void newActivationStartsWithFreshHandler() {
		var previousActivation = new SafeWriteHandler();
		assertTrue(previousActivation.queueIfChanged(10, 20));
		previousActivation.onExecute(ExecuteState.OK);
		previousActivation.verify(20);
		assertEquals(SafeWriteHandler.State.VERIFIED, previousActivation.getState());

		var nextActivation = new SafeWriteHandler();
		assertEquals(SafeWriteHandler.State.IDLE, nextActivation.getState());
		assertTrue(nextActivation.queueIfChanged(20, 30));
	}

	@Test
	void aggregatePrecedenceIsIndependentOfEnumOrder() {
		assertTrue(SrneBatteryInverterImpl.writeStatePrecedence(SafeWriteHandler.State.FAILED) //
				> SrneBatteryInverterImpl.writeStatePrecedence(SafeWriteHandler.State.AWAITING_READBACK));
		assertTrue(SrneBatteryInverterImpl.writeStatePrecedence(SafeWriteHandler.State.AWAITING_READBACK) //
				> SrneBatteryInverterImpl.writeStatePrecedence(SafeWriteHandler.State.QUEUED));
		assertTrue(SrneBatteryInverterImpl.writeStatePrecedence(SafeWriteHandler.State.QUEUED) //
				> SrneBatteryInverterImpl.writeStatePrecedence(SafeWriteHandler.State.VERIFIED));
	}
}
