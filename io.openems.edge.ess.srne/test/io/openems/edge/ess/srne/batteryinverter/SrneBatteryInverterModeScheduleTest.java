package io.openems.edge.ess.srne.batteryinverter;

import static io.openems.edge.ess.srne.SrneConstants.DEFAULT_UNIT_ID;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.time.ZoneOffset;

import org.junit.jupiter.api.Test;

import io.openems.common.test.TimeLeapClock;
import io.openems.edge.bridge.modbus.api.task.Task.ExecuteState;
import io.openems.edge.bridge.modbus.api.task.WriteTask;
import io.openems.edge.bridge.modbus.test.DummyModbusBridge;
import io.openems.edge.common.channel.Channel;
import io.openems.edge.common.test.AbstractComponentTest.TestCase;
import io.openems.edge.common.test.ComponentTest;
import io.openems.edge.common.test.DummyComponentManager;
import io.openems.edge.ess.srne.common.enums.MachineState;

/**
 * Daily output-priority schedule through the real component, with a
 * controllable clock. Africa/Kampala is UTC+3: 07:00Z is 10:00 local (daytime,
 * SUB) and 16:00Z is 19:00 local (evening, SBU). The dummy bridge does not
 * execute writes, so a test plays the device by invoking the same execute
 * callback the write task uses and then presenting the register read-back.
 */
public class SrneBatteryInverterModeScheduleTest {

	private static final int SBU = 2;
	private static final int SUB = 3;
	private static final int CYCLES = 16; // >= number of LOW read tasks
	private static final String DAY = "2026-11-10T07:00:00Z";
	private static final String EVENING = "2026-11-10T16:00:00Z";

	private static TimeLeapClock clockAt(String utc) {
		return new TimeLeapClock(Instant.parse(utc), ZoneOffset.UTC);
	}

	private static DummyModbusBridge bridge(int outputPriority, MachineState state) {
		return new DummyModbusBridge("modbus0") //
				.withRegisters(0x0100, 50) //
				.withRegisters(0x0101, 524, 0) //
				.withRegisters(0xE204, outputPriority, 200) //
				.withRegister(0x0210, state.getValue());
	}

	private static MyConfig.Builder config() {
		return MyConfig.create() //
				.setId("batteryInverter0") //
				.setModbusId("modbus0") //
				.setModbusUnitId(DEFAULT_UNIT_ID) //
				.setMaxApparentPower(12_000) //
				.setControlEnabled(true) //
				.setModeScheduleEnabled(true);
	}

	private static ComponentTest start(SrneBatteryInverterImpl sut, DummyModbusBridge bridge, String utc,
			MyConfig config) throws Exception {
		return new ComponentTest(sut) //
				.addReference("setModbus", bridge) //
				.addReference("componentManager", new DummyComponentManager(clockAt(utc))) //
				.activate(config) //
				.next(new TestCase(), CYCLES);
	}

	private static ModeScheduleState state(SrneBatteryInverterImpl sut) {
		Channel<ModeScheduleState> channel = sut.channel(SrneBatteryInverter.ChannelId.MODE_SCHEDULE_STATE);
		return channel.value().asEnum();
	}

	private static Integer target(SrneBatteryInverterImpl sut) {
		Channel<Integer> channel = sut.channel(SrneBatteryInverter.ChannelId.MODE_SCHEDULE_TARGET_PRIORITY);
		return channel.value().get();
	}

	private static boolean readsSoc(SrneBatteryInverterImpl sut) {
		for (var task : sut.defineModbusProtocol().getTaskManager().getTasks()) {
			if (!(task instanceof WriteTask) && task.getStartAddress() == 0x0100) {
				return true;
			}
		}
		return false;
	}

	@Test
	public void testDefaultIsOff() throws Exception {
		var sut = new SrneBatteryInverterImpl();
		new ComponentTest(sut) //
				.addReference("setModbus", bridge(SBU, MachineState.RUNNING_MAINS_BYPASS)) //
				.activate(config().setModeScheduleEnabled(false).build()) //
				.next(new TestCase(), CYCLES);
		assertEquals(ModeScheduleState.DISABLED, state(sut));
		assertNull(target(sut));
		assertNull(sut.modeScheduleForTest().queuedTarget());
		assertFalse(sut.takeModeScheduleWriteForTest());
		// Default bus traffic unchanged: no SoC read without a rule or the schedule.
		assertFalse(readsSoc(sut));
	}

	@Test
	public void testDaytimeWriteQueuedAndVerified() throws Exception {
		var sut = new SrneBatteryInverterImpl();
		var bridge = bridge(SBU, MachineState.RUNNING_MAINS_BYPASS);
		final var test = start(sut, bridge, DAY, config().build());
		assertTrue(readsSoc(sut));
		assertEquals(ModeScheduleState.WRITE_PENDING, state(sut));
		assertEquals(SUB, target(sut));
		assertEquals(SUB, sut.modeScheduleForTest().queuedTarget());
		assertTrue(sut.takeModeScheduleWriteForTest());

		// The device takes the write and reads back SUB.
		sut.modeScheduleForTest().onExecute(ExecuteState.OK);
		bridge.withRegisters(0xE204, SUB, 200);
		test.next(new TestCase(), CYCLES);
		assertEquals(SafeWriteHandler.State.VERIFIED, sut.modeScheduleForTest().handlerState());
		assertEquals(ModeScheduleState.IN_SYNC, state(sut));
		assertFalse(sut.takeModeScheduleWriteForTest());
		test.deactivate();
	}

	@Test
	public void testInSyncNeverWrites() throws Exception {
		var sut = new SrneBatteryInverterImpl();
		start(sut, bridge(SBU, MachineState.RUNNING_MAINS_BYPASS), EVENING, config().build()) //
				.next(new TestCase(), CYCLES);
		assertEquals(ModeScheduleState.IN_SYNC, state(sut));
		assertEquals(SBU, target(sut));
		assertNull(sut.modeScheduleForTest().handlerState());
		assertFalse(sut.takeModeScheduleWriteForTest());
	}

	@Test
	public void testEveningWriteWaitsForMains() throws Exception {
		var sut = new SrneBatteryInverterImpl();
		var bridge = bridge(SUB, MachineState.INVERTER_POWERED);
		final var test = start(sut, bridge, EVENING, config().build()) //
				.next(new TestCase(), CYCLES);
		assertEquals(ModeScheduleState.WAITING_FOR_MAINS, state(sut));
		assertEquals(SBU, target(sut));
		assertNull(sut.modeScheduleForTest().queuedTarget());
		assertFalse(sut.takeModeScheduleWriteForTest());

		// Mains returns (machine state 2): the write goes out.
		bridge.withRegister(0x0210, MachineState.RUNNING_MAINS_BYPASS.getValue());
		test.next(new TestCase(), CYCLES);
		assertEquals(ModeScheduleState.WRITE_PENDING, state(sut));
		assertEquals(SBU, sut.modeScheduleForTest().queuedTarget());
		assertTrue(sut.takeModeScheduleWriteForTest());
	}

	@Test
	public void testFailedReadbackReportsWriteFailed() throws Exception {
		var sut = new SrneBatteryInverterImpl();
		var bridge = bridge(SBU, MachineState.RUNNING_MAINS_BYPASS);
		final var test = start(sut, bridge, DAY, config().build());
		assertTrue(sut.takeModeScheduleWriteForTest());
		// The frame is accepted but the device keeps SBU.
		sut.modeScheduleForTest().onExecute(ExecuteState.OK);
		test.next(new TestCase(), CYCLES);
		assertEquals(SafeWriteHandler.State.FAILED, sut.modeScheduleForTest().handlerState());
		assertEquals(ModeScheduleState.WRITE_FAILED, state(sut));
		// No retry storm: nothing new is queued during the cooldown.
		test.next(new TestCase(), 30);
		assertEquals(ModeScheduleState.WRITE_FAILED, state(sut));
		assertFalse(sut.takeModeScheduleWriteForTest());
	}

	@Test
	public void testControlDisabledNeverWrites() throws Exception {
		var sut = new SrneBatteryInverterImpl();
		start(sut, bridge(SBU, MachineState.RUNNING_MAINS_BYPASS), DAY, config().setControlEnabled(false).build()) //
				.next(new TestCase(), CYCLES);
		assertEquals(ModeScheduleState.DISABLED, state(sut));
		assertNull(sut.modeScheduleForTest().handlerState());
		assertFalse(sut.takeModeScheduleWriteForTest());
	}

	@Test
	public void testUnreadableSocNeverWrites() throws Exception {
		var sut = new SrneBatteryInverterImpl();
		var bridge = new DummyModbusBridge("modbus0") //
				.withRegisters(0x0101, 524, 0) //
				.withRegisters(0xE204, SBU, 200) //
				.withRegister(0x0210, MachineState.RUNNING_MAINS_BYPASS.getValue());
		start(sut, bridge, DAY, config().build()) //
				.next(new TestCase(), CYCLES);
		assertEquals(ModeScheduleState.WAITING_FOR_DATA, state(sut));
		assertNull(sut.modeScheduleForTest().handlerState());
		assertFalse(sut.takeModeScheduleWriteForTest());
	}

	@Test
	public void testStaticOutputPriorityMakesConfigInvalid() throws Exception {
		var sut = new SrneBatteryInverterImpl();
		start(sut, bridge(SBU, MachineState.RUNNING_MAINS_BYPASS), DAY, config().setOutputPriority(SUB).build()) //
				.next(new TestCase(), CYCLES);
		assertEquals(ModeScheduleState.CONFIG_INVALID, state(sut));
		assertNull(sut.modeScheduleForTest().handlerState());
		assertFalse(sut.takeModeScheduleWriteForTest());
	}

	@Test
	public void testInvalidTimeMakesConfigInvalid() throws Exception {
		var sut = new SrneBatteryInverterImpl();
		start(sut, bridge(SBU, MachineState.RUNNING_MAINS_BYPASS), DAY, config().setEveningStart("25:00").build()) //
				.next(new TestCase(), CYCLES);
		assertEquals(ModeScheduleState.CONFIG_INVALID, state(sut));
		assertFalse(sut.takeModeScheduleWriteForTest());
	}
}
