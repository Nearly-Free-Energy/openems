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
}
