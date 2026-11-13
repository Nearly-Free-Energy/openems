package io.openems.edge.ess.srne.batteryinverter;

import static io.openems.edge.ess.srne.SrneConstants.DEFAULT_UNIT_ID;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;

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
 * Night charge stop through the real component with a controllable clock and
 * SoC. The charge window is 00:00-06:00 in Africa/Kampala (UTC+3), so 21:30Z is
 * 00:30 local. The dummy bridge does not execute writes, so a test plays the
 * device by invoking the same execute callback the write task uses and then
 * presenting the register read-back.
 */
public class SrneBatteryInverterNightChargeStopTest {

	private static final int START = 0; // 00:00
	private static final int STOP = 1536; // 06:00
	private static final int CYCLES = 16; // >= number of LOW read tasks

	private static TimeLeapClock clockAt(String utc) {
		return new TimeLeapClock(Instant.parse(utc), ZoneOffset.UTC);
	}

	// Device with the charge window correct and the charge schedule armed.
	private static DummyModbusBridge bridge(int soc, MachineState state) {
		return new DummyModbusBridge("modbus0") //
				.withRegisters(0xE026, START, STOP) //
				.withRegisters(0xE02C, 1, 0, 0) //
				.withRegisters(0x0100, soc) //
				.withRegisters(0x0101, 524, 0) //
				.withRegister(0x0210, state.getValue());
	}

	private static MyConfig config(int nightChargeStopSoc) {
		return MyConfig.create() //
				.setId("batteryInverter0") //
				.setModbusId("modbus0") //
				.setModbusUnitId(DEFAULT_UNIT_ID) //
				.setMaxApparentPower(12_000) //
				.setControlEnabled(true) //
				.setChargeWindow1Start(START) //
				.setChargeWindow1Stop(STOP) //
				.setChargeScheduleEnable(1) //
				.setNightChargeStopSoc(nightChargeStopSoc) //
				.build();
	}

	private static ComponentTest start(SrneBatteryInverterImpl sut, DummyModbusBridge bridge, TimeLeapClock clock,
			MyConfig config) throws Exception {
		return new ComponentTest(sut) //
				.addReference("setModbus", bridge) //
				.addReference("componentManager", new DummyComponentManager(clock)) //
				.activate(config) //
				.next(new TestCase(), CYCLES);
	}

	private static NightChargeStop.State state(SrneBatteryInverterImpl sut) {
		Channel<NightChargeStop.State> channel = sut.channel(SrneBatteryInverter.ChannelId.NIGHT_CHARGE_STOP_STATE);
		return channel.value().asEnum();
	}

	private static boolean hasReadTask(SrneBatteryInverterImpl sut, int address) {
		for (var task : sut.defineModbusProtocol().getTaskManager().getTasks()) {
			if (!(task instanceof WriteTask) && task.getStartAddress() == address) {
				return true;
			}
		}
		return false;
	}

	// Plays the device for one queued E02C write: execute callback, then read-back.
	private static void deviceWritesEnable(SrneBatteryInverterImpl sut, DummyModbusBridge bridge, ComponentTest test,
			int value) throws Exception {
		sut.chargeWindowForTest().enableWriteElement().getNextWriteValueAndReset();
		sut.chargeWindowForTest().onEnableExecute(ExecuteState.OK);
		bridge.withRegisters(0xE02C, value, 0, 0);
		test.next(new TestCase(), CYCLES);
	}

	@Test
	public void testOffByDefaultReadsNoSocAndWritesNothing() throws Exception {
		var sut = new SrneBatteryInverterImpl();
		start(sut, bridge(99, MachineState.RUNNING_MAINS_BYPASS), clockAt("2026-11-12T23:00:00Z"), config(-1)) //
				.next(new TestCase(), CYCLES);
		assertFalse(sut.nightChargeStopForTest().isActive());
		assertFalse(hasReadTask(sut, 0x0100));
		assertEquals(NightChargeStop.State.UNDEFINED, state(sut));
		assertNull(sut.chargeWindowForTest().queuedEnable());
		assertEquals(ScheduleWindow.State.DONE, sut.chargeWindowForTest().getState());
	}

	@Test
	public void testInvalidOrInertIsOff() throws Exception {
		for (var invalid : new int[] { 0, 9, 101 }) {
			var sut = new SrneBatteryInverterImpl();
			start(sut, bridge(99, MachineState.RUNNING_MAINS_BYPASS), clockAt("2026-11-12T23:00:00Z"), config(invalid));
			assertFalse(sut.nightChargeStopForTest().isActive(), "" + invalid);
			assertNull(sut.chargeWindowForTest().queuedEnable(), "" + invalid);
		}
		var sut = new SrneBatteryInverterImpl();
		start(sut, bridge(99, MachineState.RUNNING_MAINS_BYPASS), clockAt("2026-11-12T23:00:00Z"),
				MyConfig.create().setId("batteryInverter0").setModbusId("modbus0").setModbusUnitId(DEFAULT_UNIT_ID)
						.setMaxApparentPower(12_000).setControlEnabled(true).setChargeWindow1Start(START)
						.setChargeWindow1Stop(STOP).setNightChargeStopSoc(50).build());
		assertFalse(sut.nightChargeStopForTest().isActive());
	}

	@Test
	public void testStopsInTheWindowAndRearmsAfterIt() throws Exception {
		var sut = new SrneBatteryInverterImpl();
		final var clock = clockAt("2026-11-12T21:30:00Z"); // 00:30 local
		final var bridge = bridge(11, MachineState.RUNNING_MAINS_BYPASS);
		final var test = start(sut, bridge, clock, config(50));
		assertTrue(hasReadTask(sut, 0x0100));
		assertEquals(NightChargeStop.State.CHARGING, state(sut));
		assertNull(sut.chargeWindowForTest().queuedEnable());

		// 03:00 local, SoC at the stop: the charge schedule is disarmed (E02C = 0).
		clock.leap(150, ChronoUnit.MINUTES);
		bridge.withRegisters(0x0100, 50);
		test.next(new TestCase(), CYCLES);
		assertEquals(NightChargeStop.State.STOPPED, state(sut));
		assertEquals(0, sut.chargeWindowForTest().queuedEnable());
		assertEquals(ScheduleWindow.State.DISABLE_QUEUED, sut.chargeWindowForTest().getState());
		deviceWritesEnable(sut, bridge, test, 0);
		assertEquals(ScheduleWindow.State.DONE, sut.chargeWindowForTest().getState());

		// Still in the window at a low SoC: no re-arm.
		bridge.withRegisters(0x0100, 30);
		clock.leap(150, ChronoUnit.MINUTES); // 05:30
		test.next(new TestCase(), CYCLES);
		assertEquals(NightChargeStop.State.STOPPED, state(sut));
		assertEquals(ScheduleWindow.State.DONE, sut.chargeWindowForTest().getState());

		// 06:15 local: re-armed so the next night charges.
		clock.leap(45, ChronoUnit.MINUTES);
		test.next(new TestCase(), CYCLES);
		assertEquals(NightChargeStop.State.OUTSIDE_WINDOW, state(sut));
		assertEquals(1, sut.chargeWindowForTest().queuedEnable());
		deviceWritesEnable(sut, bridge, test, 1);
		assertEquals(ScheduleWindow.State.DONE, sut.chargeWindowForTest().getState());
		test.deactivate();
	}

	@Test
	public void testUnverifiedMachineStateWritesNothing() throws Exception {
		var sut = new SrneBatteryInverterImpl();
		final var bridge = bridge(80, MachineState.INVERTER_POWERED);
		final var test = start(sut, bridge, clockAt("2026-11-12T23:00:00Z"), config(50)) //
				.next(new TestCase(), CYCLES);
		assertEquals(NightChargeStop.State.WAITING_FOR_MAINS, state(sut));
		assertNull(sut.chargeWindowForTest().queuedEnable());

		// Mains back: the stop goes out.
		bridge.withRegister(0x0210, MachineState.RUNNING_MAINS_BYPASS.getValue());
		test.next(new TestCase(), CYCLES);
		assertEquals(NightChargeStop.State.STOPPED, state(sut));
		assertEquals(0, sut.chargeWindowForTest().queuedEnable());
		test.deactivate();
	}
}
