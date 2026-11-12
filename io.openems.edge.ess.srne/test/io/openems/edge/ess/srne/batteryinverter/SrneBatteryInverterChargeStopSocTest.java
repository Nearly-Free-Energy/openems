package io.openems.edge.ess.srne.batteryinverter;

import static io.openems.edge.ess.srne.SrneConstants.DEFAULT_UNIT_ID;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import io.openems.edge.bridge.modbus.api.task.Task.ExecuteState;
import io.openems.edge.bridge.modbus.api.task.WriteTask;
import io.openems.edge.bridge.modbus.test.DummyModbusBridge;
import io.openems.edge.common.test.AbstractComponentTest.TestCase;
import io.openems.edge.common.test.ComponentTest;
import io.openems.edge.ess.srne.common.enums.MachineState;

/**
 * Timed-charge stop SoC of charge window 1 (E03B). The dummy bridge does not
 * execute writes, so a test plays the device by invoking the same execute
 * callback the write task uses and then presenting the register read-back.
 */
public class SrneBatteryInverterChargeStopSocTest {

	private static final int E03B = 0xE03B;
	private static final int UNSET = 4096; // protocol default marker: never set
	private static final int CYCLES = 16; // >= number of LOW read tasks

	private static DummyModbusBridge bridge(int e03b, MachineState state) {
		return new DummyModbusBridge("modbus0") //
				.withRegister(0xE00F, 10) //
				.withRegisters(0xE01C, 20, 95, 15, 20, 80) //
				.withRegister(0xE205, 20) //
				.withRegister(0xE20A, 40) //
				.withRegister(E03B, e03b) //
				.withRegisters(0x0101, 524, 0) //
				.withRegister(0x0210, state.getValue());
	}

	private static MyConfig config(boolean controlEnabled, int stopSoc) {
		return MyConfig.create() //
				.setId("batteryInverter0") //
				.setModbusId("modbus0") //
				.setModbusUnitId(DEFAULT_UNIT_ID) //
				.setMaxApparentPower(12_000) //
				.setControlEnabled(controlEnabled) //
				.setChargeWindow1StopSoc(stopSoc) //
				.build();
	}

	private static ComponentTest start(SrneBatteryInverterImpl sut, DummyModbusBridge bridge, MyConfig config)
			throws Exception {
		return new ComponentTest(sut) //
				.addReference("setModbus", bridge) //
				.activate(config) //
				.next(new TestCase(), CYCLES);
	}

	private static boolean hasTask(SrneBatteryInverterImpl sut, boolean write, int address) {
		for (var task : sut.defineModbusProtocol().getTaskManager().getTasks()) {
			if (task instanceof WriteTask == write && task.getStartAddress() == address) {
				return true;
			}
		}
		return false;
	}

	private static Integer pendingWrite(SrneBatteryInverterImpl sut) {
		var pending = sut.chargeWindow1StopSocWriteForTest().getNextWriteValueAndReset();
		return pending == null ? null : pending[0].getValue();
	}

	@Test
	public void testUnmanagedReadsAndWritesNothing() throws Exception {
		var sut = new SrneBatteryInverterImpl();
		start(sut, bridge(UNSET, MachineState.RUNNING_MAINS_BYPASS), config(true, -1)) //
				.next(new TestCase() //
						.output(SrneBatteryInverter.ChannelId.SAFE_WRITE_STATE, SafeWriteHandler.State.IDLE));
		// -1: no E03B read and no E03B write task, so default bus traffic is unchanged.
		assertFalse(hasTask(sut, false, E03B));
		assertFalse(hasTask(sut, true, E03B));
		assertEquals(SafeWriteHandler.State.IDLE, sut.chargeWindow1StopSocHandlerForTest().getState());
		assertNull(pendingWrite(sut));
	}

	@Test
	public void testWriteQueuedAndVerifiedByReadback() throws Exception {
		var sut = new SrneBatteryInverterImpl();
		final var bridge = bridge(UNSET, MachineState.RUNNING_MAINS_BYPASS);
		final var test = start(sut, bridge, config(true, 50));
		assertTrue(hasTask(sut, false, E03B));
		assertTrue(hasTask(sut, true, E03B));
		// 4096 (never set) differs from every valid target, so one write of 50 is queued.
		assertEquals(SafeWriteHandler.State.QUEUED, sut.chargeWindow1StopSocHandlerForTest().getState());
		assertEquals(50, pendingWrite(sut));

		// Device accepts the write and reads back 50.
		sut.chargeWindow1StopSocHandlerForTest().onExecute(ExecuteState.OK);
		bridge.withRegister(E03B, 50);
		test.next(new TestCase(), CYCLES) //
				.next(new TestCase() //
						.output(SrneBatteryInverter.ChannelId.CHARGE_WINDOW_1_STOP_SOC, 50) //
						.output(SrneBatteryInverter.ChannelId.SAFE_WRITE_STATE, SafeWriteHandler.State.VERIFIED));
		assertEquals(SafeWriteHandler.State.VERIFIED, sut.chargeWindow1StopSocHandlerForTest().getState());
		// One-shot: nothing more is queued once verified.
		test.next(new TestCase(), CYCLES);
		assertNull(pendingWrite(sut));
		test.deactivate();
	}

	@Test
	public void testUnsetMarkerNeverVerifies() throws Exception {
		var sut = new SrneBatteryInverterImpl();
		final var bridge = bridge(UNSET, MachineState.RUNNING_MAINS_BYPASS);
		final var test = start(sut, bridge, config(true, 50));
		assertEquals(50, pendingWrite(sut));
		sut.chargeWindow1StopSocHandlerForTest().onExecute(ExecuteState.OK);
		// The device still reads 4096 after the write: FAILED, and never retried.
		test.next(new TestCase(), CYCLES);
		assertEquals(SafeWriteHandler.State.FAILED, sut.chargeWindow1StopSocHandlerForTest().getState());
		test.next(new TestCase(), CYCLES);
		assertNull(pendingWrite(sut));
		test.deactivate();
	}

	@Test
	public void testAlreadyAtTargetWritesNothing() throws Exception {
		var sut = new SrneBatteryInverterImpl();
		start(sut, bridge(50, MachineState.RUNNING_MAINS_BYPASS), config(true, 50)) //
				.next(new TestCase(), CYCLES) //
				.deactivate();
		assertEquals(SafeWriteHandler.State.IDLE, sut.chargeWindow1StopSocHandlerForTest().getState());
		assertNull(pendingWrite(sut));
	}

	@Test
	public void testOutOfRangeRejected() throws Exception {
		for (var invalid : new int[] { 0, 9, 101, 4096 }) {
			var sut = new SrneBatteryInverterImpl();
			start(sut, bridge(UNSET, MachineState.RUNNING_MAINS_BYPASS), config(true, invalid)) //
					.next(new TestCase() //
							.output(SrneBatteryInverter.ChannelId.SAFE_WRITE_STATE, SafeWriteHandler.State.FAILED)) //
					.deactivate();
			assertEquals(SafeWriteHandler.State.FAILED, sut.chargeWindow1StopSocHandlerForTest().getState(),
					"value " + invalid);
			assertNull(pendingWrite(sut), "value " + invalid);
		}
	}

	@Test
	public void testRangeBoundsAccepted() throws Exception {
		for (var valid : new int[] { 10, 100 }) {
			var sut = new SrneBatteryInverterImpl();
			start(sut, bridge(UNSET, MachineState.RUNNING_MAINS_BYPASS), config(true, valid)) //
					.deactivate();
			assertEquals(SafeWriteHandler.State.QUEUED, sut.chargeWindow1StopSocHandlerForTest().getState(),
					"value " + valid);
			assertEquals(valid, pendingWrite(sut));
		}
	}

	@Test
	public void testControlDisabledNeverQueues() throws Exception {
		var sut = new SrneBatteryInverterImpl();
		start(sut, bridge(UNSET, MachineState.RUNNING_MAINS_BYPASS), config(false, 50)) //
				.next(new TestCase(), CYCLES) //
				.next(new TestCase() //
						.output(SrneBatteryInverter.ChannelId.CHARGE_WINDOW_1_STOP_SOC, UNSET) //
						.output(SrneBatteryInverter.ChannelId.SAFE_WRITE_STATE, SafeWriteHandler.State.IDLE)) //
				.deactivate();
		assertEquals(SafeWriteHandler.State.IDLE, sut.chargeWindow1StopSocHandlerForTest().getState());
		assertNull(pendingWrite(sut));
	}

	@Test
	public void testUnverifiedMachineStateNeverQueues() throws Exception {
		var sut = new SrneBatteryInverterImpl();
		final var bridge = bridge(UNSET, MachineState.INVERTER_POWERED);
		final var test = start(sut, bridge, config(true, 50)) //
				.next(new TestCase(), CYCLES);
		assertEquals(SafeWriteHandler.State.IDLE, sut.chargeWindow1StopSocHandlerForTest().getState());
		assertNull(pendingWrite(sut));

		// Grid back (machine state 2): the write is queued.
		bridge.withRegister(0x0210, MachineState.RUNNING_MAINS_BYPASS.getValue());
		test.next(new TestCase(), CYCLES);
		assertEquals(SafeWriteHandler.State.QUEUED, sut.chargeWindow1StopSocHandlerForTest().getState());
		assertEquals(50, pendingWrite(sut));
		test.deactivate();
	}
}
