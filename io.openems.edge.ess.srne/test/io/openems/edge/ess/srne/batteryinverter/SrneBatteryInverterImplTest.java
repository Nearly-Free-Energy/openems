package io.openems.edge.ess.srne.batteryinverter;

import static io.openems.edge.ess.srne.SrneConstants.DEFAULT_UNIT_ID;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import io.openems.edge.batteryinverter.api.OffGridBatteryInverter;
import io.openems.edge.batteryinverter.api.SymmetricBatteryInverter;
import io.openems.edge.bridge.modbus.test.DummyModbusBridge;
import io.openems.edge.common.startstop.StartStop;
import io.openems.edge.common.startstop.StartStoppable;
import io.openems.edge.common.sum.GridMode;
import io.openems.edge.common.test.AbstractComponentTest.TestCase;
import io.openems.edge.common.test.ComponentTest;
import io.openems.edge.ess.srne.batteryinverter.statemachine.StateMachine;
import io.openems.edge.ess.srne.batteryinverter.statemachine.StateMachine.State;
import io.openems.edge.ess.srne.common.enums.MachineState;

public class SrneBatteryInverterImplTest {

	@Test
	public void testReadValidatedRunningState() throws Exception {
		var sut = new SrneBatteryInverterImpl();
		new ComponentTest(sut) //
				.addReference("setModbus", new DummyModbusBridge("modbus0") //
						.withRegisters(0x0101, //
								/* BATTERY_VOLTAGE: 52.4 V */ 524, //
								/* BATTERY_CURRENT: +12.3 A (discharging) */ 123) //
						.withRegister(0x0210, /* MACHINE_STATE */ 2)) //
				.activate(MyConfig.create() //
						.setId("batteryInverter0") //
						.setModbusId("modbus0") //
						.setModbusUnitId(DEFAULT_UNIT_ID) //
						.setMaxApparentPower(12_000) //
						.build()) //
				.next(new TestCase(), 3) //
				.next(new TestCase() //
						.output(SrneBatteryInverter.ChannelId.MACHINE_STATE, MachineState.RUNNING_MAINS_BYPASS) //
						.output(SrneBatteryInverter.ChannelId.STATE_MACHINE, State.ON_GRID) //
						.output(SymmetricBatteryInverter.ChannelId.GRID_MODE, GridMode.ON_GRID) //
						.output(SrneBatteryInverter.ChannelId.BATTERY_VOLTAGE, 52_400) //
						.output(SrneBatteryInverter.ChannelId.BATTERY_CURRENT, 12_300) //
						.output(SymmetricBatteryInverter.ChannelId.ACTIVE_POWER, 645) //
						.output(SymmetricBatteryInverter.ChannelId.MAX_APPARENT_POWER, 12_000) //
						.output(OffGridBatteryInverter.ChannelId.INVERTER_STATE, true) //
						.output(StartStoppable.ChannelId.START_STOP, StartStop.START)) //
				.deactivate();
	}

	@Test
	public void testStateMappings() {
		assertEquals(State.ON_GRID, StateMachine.fromMachineState(MachineState.RUNNING_MAINS_BYPASS));
		assertEquals(GridMode.ON_GRID, StateMachine.toGridMode(State.ON_GRID));
		assertEquals(State.OFF_GRID, StateMachine.fromMachineState(MachineState.INVERTER_POWERED));
		assertEquals(GridMode.OFF_GRID, StateMachine.toGridMode(State.OFF_GRID));
		assertEquals(State.TRANSITIONING, StateMachine.fromMachineState(MachineState.MAINS_TO_INVERTER));
		assertEquals(GridMode.UNDEFINED, StateMachine.toGridMode(State.TRANSITIONING));
		assertEquals(State.FAULT, StateMachine.fromMachineState(MachineState.FAULT));
		assertEquals(GridMode.UNDEFINED, StateMachine.toGridMode(State.FAULT));
		assertEquals(State.UNDEFINED, StateMachine.fromMachineState(null));
	}

	@Test
	public void testSettingsWriteQueuesWithoutGenericManagedEss() throws Exception {
		var sut = new SrneBatteryInverterImpl();
		new ComponentTest(sut) //
				.addReference("setModbus", new DummyModbusBridge("modbus0") //
						.withRegister(0xE00F, 10) //
						.withRegisters(0xE01C, 20, 95, 15, 20, 80) //
						.withRegister(0xE205, 20) //
						.withRegister(0xE20A, 40) //
						.withRegisters(0x0101, 524, 0) //
						.withRegister(0x0210, MachineState.RUNNING_MAINS_BYPASS.getValue())) //
				.activate(MyConfig.create() //
						.setId("batteryInverter0") //
						.setModbusId("modbus0") //
						.setModbusUnitId(DEFAULT_UNIT_ID) //
						.setMaxApparentPower(12_000) //
						.setControlEnabled(true) //
						.setLowSocAlarm(16) //
						.build()) //
				.next(new TestCase(), 8) //
				.next(new TestCase() //
						.output(SrneBatteryInverter.ChannelId.LOW_SOC_ALARM, 15) //
						.output(SrneBatteryInverter.ChannelId.SAFE_WRITE_STATE, SafeWriteHandler.State.QUEUED)) //
				// The reconcile trigger fires every cycle; extra cycles must NOT queue a
				// second or changed write - it stays a single one-shot QUEUED.
				.next(new TestCase(), 5) //
				.next(new TestCase() //
						.output(SrneBatteryInverter.ChannelId.LOW_SOC_ALARM, 15) //
						.output(SrneBatteryInverter.ChannelId.SAFE_WRITE_STATE, SafeWriteHandler.State.QUEUED)) //
				.deactivate();
	}

	// JUSTIFICATION-A3: new negative tests for the two safety gates (default-off and
	// machine-state-verified), added because the reconcile trigger now fires every
	// cycle in all topologies. Not a wrapper around existing coverage.
	@Test
	public void testControlDisabledNeverQueues() throws Exception {
		// Default-off guard: even with a target that differs from the actual reading
		// and the inverter in the verified state, control disabled must never queue a
		// write. SAFE_WRITE_STATE stays IDLE across many cycles.
		var sut = new SrneBatteryInverterImpl();
		new ComponentTest(sut) //
				.addReference("setModbus", new DummyModbusBridge("modbus0") //
						.withRegister(0xE00F, 10) //
						.withRegisters(0xE01C, 20, 95, 15, 20, 80) //
						.withRegister(0xE205, 20) //
						.withRegister(0xE20A, 40) //
						.withRegisters(0x0101, 524, 0) //
						.withRegister(0x0210, MachineState.RUNNING_MAINS_BYPASS.getValue())) //
				.activate(MyConfig.create() //
						.setId("batteryInverter0") //
						.setModbusId("modbus0") //
						.setModbusUnitId(DEFAULT_UNIT_ID) //
						.setMaxApparentPower(12_000) //
						.setControlEnabled(false) //
						.setLowSocAlarm(16) //
						.build()) //
				.next(new TestCase(), 12) //
				.next(new TestCase() //
						.output(SrneBatteryInverter.ChannelId.SAFE_WRITE_STATE, SafeWriteHandler.State.IDLE)) //
				.deactivate();
	}

	@Test
	public void testUnverifiedMachineStateNeverQueues() throws Exception {
		// Machine-state gate: control enabled and a differing target, but the inverter
		// is not in the verified RUNNING_MAINS_BYPASS state, so no write is queued.
		// SAFE_WRITE_STATE stays IDLE across many cycles.
		var sut = new SrneBatteryInverterImpl();
		new ComponentTest(sut) //
				.addReference("setModbus", new DummyModbusBridge("modbus0") //
						.withRegister(0xE00F, 10) //
						.withRegisters(0xE01C, 20, 95, 15, 20, 80) //
						.withRegister(0xE205, 20) //
						.withRegister(0xE20A, 40) //
						.withRegisters(0x0101, 524, 0) //
						.withRegister(0x0210, MachineState.INVERTER_POWERED.getValue())) //
				.activate(MyConfig.create() //
						.setId("batteryInverter0") //
						.setModbusId("modbus0") //
						.setModbusUnitId(DEFAULT_UNIT_ID) //
						.setMaxApparentPower(12_000) //
						.setControlEnabled(true) //
						.setLowSocAlarm(16) //
						.build()) //
				.next(new TestCase(), 12) //
				.next(new TestCase() //
						.output(SrneBatteryInverter.ChannelId.SAFE_WRITE_STATE, SafeWriteHandler.State.IDLE)) //
				.deactivate();
	}

	// JUSTIFICATION-A3: tests for the new TOU schedule-window write path (#67) -
	// pure time encoding/validation plus the guarded queue and the invalid-time
	// reject. New behaviour, not a wrapper around existing coverage.
	@Test
	public void testEncodedTimeValidationAndFormatting() {
		assertTrue(SrneBatteryInverterImpl.isValidEncodedTime(0)); // 00:00
		assertTrue(SrneBatteryInverterImpl.isValidEncodedTime(1536)); // 06:00
		assertTrue(SrneBatteryInverterImpl.isValidEncodedTime(4608)); // 18:00
		assertTrue(SrneBatteryInverterImpl.isValidEncodedTime(5947)); // 23:59
		assertFalse(SrneBatteryInverterImpl.isValidEncodedTime(60)); // 00:60 minute out of range
		assertFalse(SrneBatteryInverterImpl.isValidEncodedTime(6144)); // 24:00 hour out of range
		assertFalse(SrneBatteryInverterImpl.isValidEncodedTime(-1));
		assertFalse(SrneBatteryInverterImpl.isValidEncodedTime(0x1_0000));
		assertEquals("00:00", SrneBatteryInverterImpl.formatEncodedTime(0));
		assertEquals("06:00", SrneBatteryInverterImpl.formatEncodedTime(1536));
		assertEquals("18:00", SrneBatteryInverterImpl.formatEncodedTime(4608));
		assertEquals("23:59", SrneBatteryInverterImpl.formatEncodedTime(5947));
	}

	@Test
	public void testScheduleWindowWriteQueues() throws Exception {
		// A differing discharge-window target (18:00) queues one guarded schedule write.
		var sut = new SrneBatteryInverterImpl();
		new ComponentTest(sut) //
				.addReference("setModbus", new DummyModbusBridge("modbus0") //
						.withRegister(0xE00F, 10) //
						.withRegisters(0xE01C, 20, 95, 15, 20, 80) //
						.withRegister(0xE205, 20) //
						.withRegister(0xE20A, 40) //
						.withRegisters(0xE026, 0, 1536) // charge window 00:00-06:00
						.withRegisters(0xE02D, 0, 0) // discharge window currently off
						.withRegisters(0x0101, 524, 0) //
						.withRegister(0x0210, MachineState.RUNNING_MAINS_BYPASS.getValue())) //
				.activate(MyConfig.create() //
						.setId("batteryInverter0") //
						.setModbusId("modbus0") //
						.setModbusUnitId(DEFAULT_UNIT_ID) //
						.setMaxApparentPower(12_000) //
						.setControlEnabled(true) //
						.setDischargeWindow1Start(4608) // 18:00
						.build()) //
				.next(new TestCase(), 8) //
				.next(new TestCase() //
						.output(SrneBatteryInverter.ChannelId.DISCHARGE_WINDOW_1_START, 0) //
						.output(SrneBatteryInverter.ChannelId.SAFE_WRITE_STATE, SafeWriteHandler.State.QUEUED)) //
				// Every cycle re-runs reconcile; it must stay a single one-shot QUEUED.
				.next(new TestCase(), 5) //
				.next(new TestCase() //
						.output(SrneBatteryInverter.ChannelId.SAFE_WRITE_STATE, SafeWriteHandler.State.QUEUED)) //
				.deactivate();
	}

	@Test
	public void testScheduleInvalidTimeRejected() throws Exception {
		// A garbage encoded time (hour 25) is rejected before any write is queued.
		var sut = new SrneBatteryInverterImpl();
		new ComponentTest(sut) //
				.addReference("setModbus", new DummyModbusBridge("modbus0") //
						.withRegister(0xE00F, 10) //
						.withRegisters(0xE01C, 20, 95, 15, 20, 80) //
						.withRegister(0xE205, 20) //
						.withRegister(0xE20A, 40) //
						.withRegisters(0xE026, 0, 1536) //
						.withRegisters(0xE02D, 0, 0) //
						.withRegisters(0x0101, 524, 0) //
						.withRegister(0x0210, MachineState.RUNNING_MAINS_BYPASS.getValue())) //
				.activate(MyConfig.create() //
						.setId("batteryInverter0") //
						.setModbusId("modbus0") //
						.setModbusUnitId(DEFAULT_UNIT_ID) //
						.setMaxApparentPower(12_000) //
						.setControlEnabled(true) //
						.setDischargeWindow1Start(6400) // hour 25 -> invalid
						.build()) //
				.next(new TestCase(), 8) //
				.next(new TestCase() //
						.output(SrneBatteryInverter.ChannelId.SAFE_WRITE_STATE, SafeWriteHandler.State.FAILED)) //
				.deactivate();
	}
}
