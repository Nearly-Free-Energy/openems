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
		// A differing discharge window (18:00-23:59) queues one coherent schedule
		// write; both start and stop must be configured together.
		var sut = new SrneBatteryInverterImpl();
		new ComponentTest(sut) //
				.addReference("setModbus", new DummyModbusBridge("modbus0") //
						.withRegister(0xE00F, 10) //
						.withRegisters(0xE01C, 20, 95, 15, 20, 80) //
						.withRegister(0xE205, 20) //
						.withRegister(0xE20A, 40) //
						.withRegisters(0xE026, 0, 1536) // charge window 00:00-06:00
						.withRegisters(0xE02C, 0, 0, 0) // charge enable off; discharge window off
						.withRegisters(0xE033, 0, 0, 0, 0) // discharge enable off; RTC
						.withRegisters(0x0101, 524, 0) //
						.withRegister(0x0210, MachineState.RUNNING_MAINS_BYPASS.getValue())) //
				.activate(MyConfig.create() //
						.setId("batteryInverter0") //
						.setModbusId("modbus0") //
						.setModbusUnitId(DEFAULT_UNIT_ID) //
						.setMaxApparentPower(12_000) //
						.setControlEnabled(true) //
						.setDischargeWindow1Start(4608) // 18:00
						.setDischargeWindow1Stop(5947) // 23:59 (end of day; no 24:00)
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
	public void testScheduleInvalidWindowRejected() throws Exception {
		// A garbage encoded start (hour 25) makes the window invalid; it is rejected
		// before any write is queued, even though a valid stop is given.
		var sut = new SrneBatteryInverterImpl();
		new ComponentTest(sut) //
				.addReference("setModbus", new DummyModbusBridge("modbus0") //
						.withRegister(0xE00F, 10) //
						.withRegisters(0xE01C, 20, 95, 15, 20, 80) //
						.withRegister(0xE205, 20) //
						.withRegister(0xE20A, 40) //
						.withRegisters(0xE026, 0, 1536) //
						.withRegisters(0xE02C, 0, 0, 0) //
						.withRegisters(0xE033, 0, 0, 0, 0) //
						.withRegisters(0x0101, 524, 0) //
						.withRegister(0x0210, MachineState.RUNNING_MAINS_BYPASS.getValue())) //
				.activate(MyConfig.create() //
						.setId("batteryInverter0") //
						.setModbusId("modbus0") //
						.setModbusUnitId(DEFAULT_UNIT_ID) //
						.setMaxApparentPower(12_000) //
						.setControlEnabled(true) //
						.setDischargeWindow1Start(6400) // hour 25 -> invalid
						.setDischargeWindow1Stop(5947) //
						.build()) //
				.next(new TestCase(), 8) //
				.next(new TestCase() //
						.output(SrneBatteryInverter.ChannelId.SAFE_WRITE_STATE, SafeWriteHandler.State.FAILED)) //
				.deactivate();
	}

	@Test
	public void testIncompleteWindowRejected() throws Exception {
		// Only a start is configured (no stop): a half-specified window is rejected
		// and no write is queued.
		var sut = new SrneBatteryInverterImpl();
		new ComponentTest(sut) //
				.addReference("setModbus", new DummyModbusBridge("modbus0") //
						.withRegister(0xE00F, 10) //
						.withRegisters(0xE01C, 20, 95, 15, 20, 80) //
						.withRegister(0xE205, 20) //
						.withRegister(0xE20A, 40) //
						.withRegisters(0xE026, 0, 1536) //
						.withRegisters(0xE02C, 0, 0, 0) //
						.withRegisters(0xE033, 0, 0, 0, 0) //
						.withRegisters(0x0101, 524, 0) //
						.withRegister(0x0210, MachineState.RUNNING_MAINS_BYPASS.getValue())) //
				.activate(MyConfig.create() //
						.setId("batteryInverter0") //
						.setModbusId("modbus0") //
						.setModbusUnitId(DEFAULT_UNIT_ID) //
						.setMaxApparentPower(12_000) //
						.setControlEnabled(true) //
						.setDischargeWindow1Start(4608) // start only, no stop
						.build()) //
				.next(new TestCase(), 8) //
				.next(new TestCase() //
						.output(SrneBatteryInverter.ChannelId.SAFE_WRITE_STATE, SafeWriteHandler.State.FAILED)) //
				.deactivate();
	}

	// JUSTIFICATION-A3: new tests for the output-priority (E204) guarded write added to
	// the allow-list (#71); arming requires a complete, coherent, settled reserve band +
	// BMS, and out-of-range / missing-prerequisite / incoherent-band all reject. New
	// behaviour, not a wrapper around existing coverage.
	@Test
	public void testOutputPriorityWriteQueues() throws Exception {
		// Arming is allowed: the reserve band (E01F=20, E020=80) and BMS (E215=1) are all
		// configured, coherent (20 < 80) and already correct on the device, so output
		// priority queues its one-shot write.
		var sut = new SrneBatteryInverterImpl();
		new ComponentTest(sut) //
				.addReference("setModbus", new DummyModbusBridge("modbus0") //
						.withRegister(0xE00F, 10) //
						.withRegisters(0xE01C, 20, 95, 15, 20, 80) // E01F=20, E020=80
						.withRegisters(0xE204, 1, 20) // output priority = 1, mains charge = 20
						.withRegister(0xE20A, 40) //
						.withRegister(0xE215, 1) // BMS comms enabled
						.withRegisters(0x0101, 524, 0) //
						.withRegister(0x0210, MachineState.RUNNING_MAINS_BYPASS.getValue())) //
				.activate(MyConfig.create() //
						.setId("batteryInverter0") //
						.setModbusId("modbus0") //
						.setModbusUnitId(DEFAULT_UNIT_ID) //
						.setMaxApparentPower(12_000) //
						.setControlEnabled(true) //
						.setSwitchToLineSoc(20) // matches device -> settled
						.setSwitchToBatterySoc(80) // matches device -> settled; band 20 < 80
						.setBmsCommunication(1) // matches device -> settled
						.setOutputPriority(2) // differs from device 1 -> arms
						.build()) //
				.next(new TestCase(), 8) //
				.next(new TestCase() //
						.output(SrneBatteryInverter.ChannelId.OUTPUT_PRIORITY, 1) //
						.output(SrneBatteryInverter.ChannelId.SAFE_WRITE_STATE, SafeWriteHandler.State.QUEUED)) //
				// Every cycle re-runs reconcile; it must stay a single one-shot QUEUED.
				.next(new TestCase(), 5) //
				.next(new TestCase() //
						.output(SrneBatteryInverter.ChannelId.SAFE_WRITE_STATE, SafeWriteHandler.State.QUEUED)) //
				.deactivate();
	}

	@Test
	public void testOutputPriorityOutOfRangeRejected() throws Exception {
		// Exact boundary: 3 is the max accepted mode; 4 must be rejected before any write.
		var sut = new SrneBatteryInverterImpl();
		new ComponentTest(sut) //
				.addReference("setModbus", new DummyModbusBridge("modbus0") //
						.withRegister(0xE00F, 10) //
						.withRegisters(0xE01C, 20, 95, 15, 20, 80) //
						.withRegisters(0xE204, 1, 20) //
						.withRegister(0xE20A, 40) //
						.withRegister(0xE215, 1) //
						.withRegisters(0x0101, 524, 0) //
						.withRegister(0x0210, MachineState.RUNNING_MAINS_BYPASS.getValue())) //
				.activate(MyConfig.create() //
						.setId("batteryInverter0") //
						.setModbusId("modbus0") //
						.setModbusUnitId(DEFAULT_UNIT_ID) //
						.setMaxApparentPower(12_000) //
						.setControlEnabled(true) //
						.setOutputPriority(4) // > 3 -> invalid (exact boundary)
						.build()) //
				.next(new TestCase(), 8) //
				.next(new TestCase() //
						.output(SrneBatteryInverter.ChannelId.SAFE_WRITE_STATE, SafeWriteHandler.State.FAILED)) //
				.deactivate();
	}

	// JUSTIFICATION-A3: coverage for the BMS-comms (E215) guarded write and the
	// output-priority prerequisite gate added under #71 (pre-PR reviewer findings).
	@Test
	public void testBmsCommunicationWriteQueues() throws Exception {
		// A differing BMS-comms target (actual 1, target 2 = CAN, in range 0..2) queues.
		var sut = new SrneBatteryInverterImpl();
		new ComponentTest(sut) //
				.addReference("setModbus", new DummyModbusBridge("modbus0") //
						.withRegister(0xE00F, 10) //
						.withRegisters(0xE01C, 20, 95, 15, 20, 80) //
						.withRegisters(0xE204, 1, 20) //
						.withRegister(0xE20A, 40) //
						.withRegister(0xE215, 1) //
						.withRegisters(0x0101, 524, 0) //
						.withRegister(0x0210, MachineState.RUNNING_MAINS_BYPASS.getValue())) //
				.activate(MyConfig.create() //
						.setId("batteryInverter0") //
						.setModbusId("modbus0") //
						.setModbusUnitId(DEFAULT_UNIT_ID) //
						.setMaxApparentPower(12_000) //
						.setControlEnabled(true) //
						.setBmsCommunication(2) // differs from actual 1, in range (boundary max)
						.build()) //
				.next(new TestCase(), 8) //
				.next(new TestCase() //
						.output(SrneBatteryInverter.ChannelId.BMS_COMMUNICATION, 1) //
						.output(SrneBatteryInverter.ChannelId.SAFE_WRITE_STATE, SafeWriteHandler.State.QUEUED)) //
				.next(new TestCase(), 5) //
				.next(new TestCase() //
						.output(SrneBatteryInverter.ChannelId.SAFE_WRITE_STATE, SafeWriteHandler.State.QUEUED)) //
				.deactivate();
	}

	@Test
	public void testBmsCommunicationOutOfRangeRejected() throws Exception {
		// Exact boundary: 2 is the max accepted; 3 must be rejected before any write.
		var sut = new SrneBatteryInverterImpl();
		new ComponentTest(sut) //
				.addReference("setModbus", new DummyModbusBridge("modbus0") //
						.withRegister(0xE00F, 10) //
						.withRegisters(0xE01C, 20, 95, 15, 20, 80) //
						.withRegisters(0xE204, 1, 20) //
						.withRegister(0xE20A, 40) //
						.withRegister(0xE215, 1) //
						.withRegisters(0x0101, 524, 0) //
						.withRegister(0x0210, MachineState.RUNNING_MAINS_BYPASS.getValue())) //
				.activate(MyConfig.create() //
						.setId("batteryInverter0") //
						.setModbusId("modbus0") //
						.setModbusUnitId(DEFAULT_UNIT_ID) //
						.setMaxApparentPower(12_000) //
						.setControlEnabled(true) //
						.setBmsCommunication(3) // > 2 -> invalid (exact boundary)
						.build()) //
				.next(new TestCase(), 8) //
				.next(new TestCase() //
						.output(SrneBatteryInverter.ChannelId.SAFE_WRITE_STATE, SafeWriteHandler.State.FAILED)) //
				.deactivate();
	}

	@Test
	public void testOutputPriorityPrerequisiteGate() {
		// Settled: at rest (IDLE = already correct, or VERIFIED) AND the device CURRENTLY
		// reads the configured target.
		assertTrue(SrneBatteryInverterImpl.isPrerequisiteSettled(50, SafeWriteHandler.State.IDLE, 50));
		assertTrue(SrneBatteryInverterImpl.isPrerequisiteSettled(50, SafeWriteHandler.State.VERIFIED, 50));
		// Not settled: an in-flight or failed write never settles, even if the read
		// momentarily matches the target.
		assertFalse(SrneBatteryInverterImpl.isPrerequisiteSettled(50, SafeWriteHandler.State.QUEUED, 50));
		assertFalse(SrneBatteryInverterImpl.isPrerequisiteSettled(50, SafeWriteHandler.State.AWAITING_READBACK, 50));
		assertFalse(SrneBatteryInverterImpl.isPrerequisiteSettled(50, SafeWriteHandler.State.FAILED, 50));
		assertFalse(SrneBatteryInverterImpl.isPrerequisiteSettled(50, SafeWriteHandler.State.UNDEFINED, 50));
		// Not settled: verified/at-rest but the device has drifted away or is unknown.
		assertFalse(SrneBatteryInverterImpl.isPrerequisiteSettled(50, SafeWriteHandler.State.VERIFIED, 40));
		assertFalse(SrneBatteryInverterImpl.isPrerequisiteSettled(50, SafeWriteHandler.State.VERIFIED, null));
		assertFalse(SrneBatteryInverterImpl.isPrerequisiteSettled(50, SafeWriteHandler.State.IDLE, 40));
		assertFalse(SrneBatteryInverterImpl.isPrerequisiteSettled(50, SafeWriteHandler.State.IDLE, null));
	}

	@Test
	public void testOutputPriorityRejectedWithoutPrerequisites() throws Exception {
		// Arming with no reserve band / BMS configured must be rejected - never transfer
		// the load onto the battery using unknown existing device settings.
		var sut = new SrneBatteryInverterImpl();
		new ComponentTest(sut) //
				.addReference("setModbus", new DummyModbusBridge("modbus0") //
						.withRegister(0xE00F, 10) //
						.withRegisters(0xE01C, 20, 95, 15, 20, 80) //
						.withRegisters(0xE204, 1, 20) //
						.withRegister(0xE20A, 40) //
						.withRegister(0xE215, 1) //
						.withRegisters(0x0101, 524, 0) //
						.withRegister(0x0210, MachineState.RUNNING_MAINS_BYPASS.getValue())) //
				.activate(MyConfig.create() //
						.setId("batteryInverter0") //
						.setModbusId("modbus0") //
						.setModbusUnitId(DEFAULT_UNIT_ID) //
						.setMaxApparentPower(12_000) //
						.setControlEnabled(true) //
						.setOutputPriority(2) // arm requested, but no prerequisites configured
						.build()) //
				.next(new TestCase(), 8) //
				.next(new TestCase() //
						.output(SrneBatteryInverter.ChannelId.SAFE_WRITE_STATE, SafeWriteHandler.State.FAILED)) //
				.deactivate();
	}

	@Test
	public void testOutputPriorityRejectedWithIncoherentBand() throws Exception {
		// Reserve band must be coherent: switchToLineSoc (mains floor) below
		// switchToBatterySoc (return threshold). An inverted band rejects the arm.
		var sut = new SrneBatteryInverterImpl();
		new ComponentTest(sut) //
				.addReference("setModbus", new DummyModbusBridge("modbus0") //
						.withRegister(0xE00F, 10) //
						.withRegisters(0xE01C, 20, 95, 15, 20, 80) //
						.withRegisters(0xE204, 1, 20) //
						.withRegister(0xE20A, 40) //
						.withRegister(0xE215, 1) //
						.withRegisters(0x0101, 524, 0) //
						.withRegister(0x0210, MachineState.RUNNING_MAINS_BYPASS.getValue())) //
				.activate(MyConfig.create() //
						.setId("batteryInverter0") //
						.setModbusId("modbus0") //
						.setModbusUnitId(DEFAULT_UNIT_ID) //
						.setMaxApparentPower(12_000) //
						.setControlEnabled(true) //
						.setSwitchToLineSoc(80) // floor above the return threshold -> invalid
						.setSwitchToBatterySoc(50) //
						.setBmsCommunication(1) //
						.setOutputPriority(2) //
						.build()) //
				.next(new TestCase(), 8) //
				.next(new TestCase() //
						.output(SrneBatteryInverter.ChannelId.SAFE_WRITE_STATE, SafeWriteHandler.State.FAILED)) //
				.deactivate();
	}

	@Test
	public void testOutputPriorityRejectedWithEqualBand() throws Exception {
		// Exact boundary: line == battery has no hysteresis gap (chattering) and must be
		// rejected too - arming requires strict line < battery.
		var sut = new SrneBatteryInverterImpl();
		new ComponentTest(sut) //
				.addReference("setModbus", new DummyModbusBridge("modbus0") //
						.withRegister(0xE00F, 10) //
						.withRegisters(0xE01C, 20, 95, 15, 20, 80) //
						.withRegisters(0xE204, 1, 20) //
						.withRegister(0xE20A, 40) //
						.withRegister(0xE215, 1) //
						.withRegisters(0x0101, 524, 0) //
						.withRegister(0x0210, MachineState.RUNNING_MAINS_BYPASS.getValue())) //
				.activate(MyConfig.create() //
						.setId("batteryInverter0") //
						.setModbusId("modbus0") //
						.setModbusUnitId(DEFAULT_UNIT_ID) //
						.setMaxApparentPower(12_000) //
						.setControlEnabled(true) //
						.setSwitchToLineSoc(60) // equal to the return threshold -> invalid
						.setSwitchToBatterySoc(60) //
						.setBmsCommunication(1) //
						.setOutputPriority(2) //
						.build()) //
				.next(new TestCase(), 8) //
				.next(new TestCase() //
						.output(SrneBatteryInverter.ChannelId.SAFE_WRITE_STATE, SafeWriteHandler.State.FAILED)) //
				.deactivate();
	}

	// JUSTIFICATION-A3: new test (PR #25 review) proving a rejected arm writes NOTHING to
	// the reserve band / BMS - asserts the handlers stay IDLE, not just aggregate FAILED.
	@Test
	public void testIncoherentBandArmWritesNothingToReserveBand() throws Exception {
		// All three config values differ from the device, so if they were reconciled they
		// would queue; asserting IDLE proves the whole set was validated before any write.
		var sut = new SrneBatteryInverterImpl();
		final var test = new ComponentTest(sut) //
				.addReference("setModbus", new DummyModbusBridge("modbus0") //
						.withRegister(0xE00F, 10) //
						.withRegisters(0xE01C, 20, 95, 15, 20, 80) // device E01F=20, E020=80
						.withRegisters(0xE204, 1, 20) //
						.withRegister(0xE20A, 40) //
						.withRegister(0xE215, 1) // device BMS=1
						.withRegisters(0x0101, 524, 0) //
						.withRegister(0x0210, MachineState.RUNNING_MAINS_BYPASS.getValue())) //
				.activate(MyConfig.create() //
						.setId("batteryInverter0") //
						.setModbusId("modbus0") //
						.setModbusUnitId(DEFAULT_UNIT_ID) //
						.setMaxApparentPower(12_000) //
						.setControlEnabled(true) //
						.setSwitchToLineSoc(90) // valid 0..100, differs from device 20
						.setSwitchToBatterySoc(50) // differs from device 80; band 90 >= 50 -> incoherent
						.setBmsCommunication(2) // differs from device 1
						.setOutputPriority(2) // differs from device 1
						.build()) //
				.next(new TestCase(), 8) //
				.next(new TestCase() //
						.output(SrneBatteryInverter.ChannelId.SAFE_WRITE_STATE, SafeWriteHandler.State.FAILED));
		// No reserve-band / BMS write may have queued; only output priority is FAILED.
		assertEquals(SafeWriteHandler.State.IDLE, sut.writeHandlerStateForTest(4)); // switchToLineSoc
		assertEquals(SafeWriteHandler.State.IDLE, sut.writeHandlerStateForTest(5)); // switchToBatterySoc
		assertEquals(SafeWriteHandler.State.IDLE, sut.writeHandlerStateForTest(9)); // bmsCommunication
		assertEquals(SafeWriteHandler.State.FAILED, sut.writeHandlerStateForTest(8)); // output priority
		test.deactivate();
	}

	// JUSTIFICATION-A3: new test (PR #25 review) - an incoherent band is rejected even
	// with no arm requested, since a bad band is dangerous whenever the unit is in SBU.
	@Test
	public void testIncoherentBandRejectedWithoutArm() throws Exception {
		// Both thresholds differ from the device (would queue if reconciled), band is
		// inverted (90 >= 50), and no outputPriority is set: the band must be rejected.
		var sut = new SrneBatteryInverterImpl();
		final var test = new ComponentTest(sut) //
				.addReference("setModbus", new DummyModbusBridge("modbus0") //
						.withRegister(0xE00F, 10) //
						.withRegisters(0xE01C, 20, 95, 15, 20, 80) // device E01F=20, E020=80
						.withRegisters(0xE204, 1, 20) //
						.withRegister(0xE20A, 40) //
						.withRegister(0xE215, 1) //
						.withRegisters(0x0101, 524, 0) //
						.withRegister(0x0210, MachineState.RUNNING_MAINS_BYPASS.getValue())) //
				.activate(MyConfig.create() //
						.setId("batteryInverter0") //
						.setModbusId("modbus0") //
						.setModbusUnitId(DEFAULT_UNIT_ID) //
						.setMaxApparentPower(12_000) //
						.setControlEnabled(true) //
						.setSwitchToLineSoc(90) // inverted band, no arm requested
						.setSwitchToBatterySoc(50) //
						.build()) //
				.next(new TestCase(), 8) //
				.next(new TestCase() //
						.output(SrneBatteryInverter.ChannelId.SAFE_WRITE_STATE, SafeWriteHandler.State.FAILED));
		// The band is rejected (FAILED), not written, even without an arm.
		assertEquals(SafeWriteHandler.State.FAILED, sut.writeHandlerStateForTest(4)); // switchToLineSoc
		assertEquals(SafeWriteHandler.State.FAILED, sut.writeHandlerStateForTest(5)); // switchToBatterySoc
		test.deactivate();
	}
}
