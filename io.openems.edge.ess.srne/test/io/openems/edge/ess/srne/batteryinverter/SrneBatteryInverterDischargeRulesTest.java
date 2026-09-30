package io.openems.edge.ess.srne.batteryinverter;

import static io.openems.edge.ess.srne.SrneConstants.DEFAULT_UNIT_ID;
import static org.junit.jupiter.api.Assertions.assertEquals;
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
import io.openems.edge.common.test.AbstractComponentTest.TestCase;
import io.openems.edge.common.test.ComponentTest;
import io.openems.edge.common.test.DummyComponentManager;
import io.openems.edge.ess.srne.batteryinverter.ScheduleWindow.State;
import io.openems.edge.ess.srne.common.enums.MachineState;

/**
 * Discharge start-threshold / floor rules through the real component with a
 * controllable clock and SoC. The window is 18:00-23:59 in Africa/Kampala
 * (UTC+3), so 15:05Z is 18:05 local.
 */
public class SrneBatteryInverterDischargeRulesTest {

	private static final int START = 4608; // 18:00
	private static final int STOP = 5947; // 23:59
	private static final int CYCLES = 16; // >= number of LOW read tasks

	private static TimeLeapClock clockAt(String utc) {
		return new TimeLeapClock(Instant.parse(utc), ZoneOffset.UTC);
	}

	// Device with the window already correct and the schedule armed.
	private static DummyModbusBridge bridge(int soc, MachineState state) {
		return new DummyModbusBridge("modbus0") //
				.withRegisters(0xE02C, 0, START, STOP) //
				.withRegisters(0xE033, 1, 0, 0, 0) //
				.withRegisters(0x0100, soc) //
				.withRegisters(0x0101, 524, 0) //
				.withRegister(0x0210, state.getValue());
	}

	private static MyConfig.Builder config(int startMin, int floor) {
		return MyConfig.create() //
				.setId("batteryInverter0") //
				.setModbusId("modbus0") //
				.setModbusUnitId(DEFAULT_UNIT_ID) //
				.setMaxApparentPower(12_000) //
				.setControlEnabled(true) //
				.setDischargeWindow1Start(START) //
				.setDischargeWindow1Stop(STOP) //
				.setDischargeScheduleEnable(1) //
				.setDischargeStartMinSoc(startMin) //
				.setDischargeFloorSoc(floor);
	}

	private static void assertSuppression(SrneBatteryInverterImpl sut, boolean suppressed,
			DischargeSuppressionReason reason) {
		assertEquals(suppressed, sut.getDischargeSuppressedChannelForTest().value().get());
		assertEquals(reason, sut.getDischargeSuppressionReasonChannelForTest().value().asEnum());
	}

	@Test
	public void testSkipAtStartWhenSocLow() throws Exception {
		var sut = new SrneBatteryInverterImpl();
		var clock = clockAt("2026-01-10T15:05:00Z");
		new ComponentTest(sut) //
				.addReference("setModbus", bridge(60, MachineState.RUNNING_MAINS_BYPASS)) //
				.addReference("componentManager", new DummyComponentManager(clock)) //
				.activate(config(75, 45).build()) //
				.next(new TestCase(), CYCLES);
		assertSuppression(sut, true, DischargeSuppressionReason.LOW_START);
		assertEquals(0, sut.dischargeQueuedEnableForTest());
	}

	@Test
	public void testNoSkipWhenSocAtThreshold() throws Exception {
		var sut = new SrneBatteryInverterImpl();
		var clock = clockAt("2026-01-10T15:05:00Z");
		new ComponentTest(sut) //
				.addReference("setModbus", bridge(75, MachineState.RUNNING_MAINS_BYPASS)) //
				.addReference("componentManager", new DummyComponentManager(clock)) //
				.activate(config(75, 45).build()) //
				.next(new TestCase(), CYCLES);
		assertSuppression(sut, false, DischargeSuppressionReason.NONE);
		assertNull(sut.dischargeQueuedEnableForTest());
		assertEquals(State.DONE, sut.dischargeWindowStateForTest());
	}

	@Test
	public void testFloorMidWindowThenRestoreAfterWindow() throws Exception {
		var sut = new SrneBatteryInverterImpl();
		var clock = clockAt("2026-01-10T15:05:00Z"); // 18:05 local
		var bridge = bridge(80, MachineState.RUNNING_MAINS_BYPASS);
		var test = new ComponentTest(sut) //
				.addReference("setModbus", bridge) //
				.addReference("componentManager", new DummyComponentManager(clock)) //
				.activate(config(75, 45).build()) //
				.next(new TestCase(), CYCLES);
		assertSuppression(sut, false, DischargeSuppressionReason.NONE);
		assertNull(sut.dischargeQueuedEnableForTest());

		// Mid-window the SoC falls to the floor: disarm is queued.
		test.next(new TestCase().timeleap(clock, 2, ChronoUnit.HOURS));
		bridge.withRegisters(0x0100, 45);
		test.next(new TestCase(), CYCLES);
		assertSuppression(sut, true, DischargeSuppressionReason.FLOOR_REACHED);
		assertEquals(0, sut.dischargeQueuedEnableForTest());

		// The dummy bridge does not execute writes, so play the device: the write
		// executes, then E033 reads back 0. The disarm is verified and held.
		sut.dischargeWindowForTest().onEnableExecute(ExecuteState.OK);
		bridge.withRegisters(0xE033, 0);
		test.next(new TestCase(), CYCLES);
		assertEquals(State.DONE, sut.dischargeWindowStateForTest());
		assertSuppression(sut, true, DischargeSuppressionReason.FLOOR_REACHED);

		// Window over (02:05 next day local): suppression clears, enable restored to 1.
		test.next(new TestCase().timeleap(clock, 6, ChronoUnit.HOURS));
		test.next(new TestCase(), CYCLES);
		assertSuppression(sut, false, DischargeSuppressionReason.NONE);
		assertEquals(1, sut.dischargeQueuedEnableForTest());
		sut.dischargeWindowForTest().onEnableExecute(ExecuteState.OK);
		bridge.withRegisters(0xE033, 1);
		test.next(new TestCase(), CYCLES);
		assertEquals(State.DONE, sut.dischargeWindowStateForTest());
	}

	@Test
	public void testRestartMidWindowReachesSameDecision() throws Exception {
		for (var soc : new int[] { 40, 60 }) {
			var reason = soc == 40 ? DischargeSuppressionReason.FLOOR_REACHED : DischargeSuppressionReason.LOW_START;
			// Two fresh instances (a restart) see the same state and decide the same.
			for (var i = 0; i < 2; i++) {
				var sut = new SrneBatteryInverterImpl();
				new ComponentTest(sut) //
						.addReference("setModbus", bridge(soc, MachineState.RUNNING_MAINS_BYPASS)) //
						.addReference("componentManager",
								new DummyComponentManager(clockAt("2026-01-10T18:00:00Z"))) // 21:00 local
						.activate(config(75, 45).build()) //
						.next(new TestCase(), CYCLES);
				assertSuppression(sut, true, reason);
				assertEquals(0, sut.dischargeQueuedEnableForTest());
			}
		}
	}

	@Test
	public void testOutageDefersWriteUntilStateTwoReturns() throws Exception {
		var sut = new SrneBatteryInverterImpl();
		var clock = clockAt("2026-01-10T15:05:00Z");
		final var bridge = bridge(60, MachineState.INVERTER_POWERED);
		final var test = new ComponentTest(sut) //
				.addReference("setModbus", bridge) //
				.addReference("componentManager", new DummyComponentManager(clock)) //
				.activate(config(75, 45).build()) //
				.next(new TestCase(), CYCLES);
		// Decision is visible, but nothing is written during the outage.
		assertSuppression(sut, true, DischargeSuppressionReason.LOW_START);
		assertNull(sut.dischargeQueuedEnableForTest());
		assertEquals(State.IDLE, sut.dischargeWindowStateForTest());

		bridge.withRegister(0x0210, MachineState.RUNNING_MAINS_BYPASS.getValue());
		test.next(new TestCase(), CYCLES);
		assertEquals(0, sut.dischargeQueuedEnableForTest());
	}

	@Test
	public void testPropertiesOffKeepsBehaviourAndTrafficUnchanged() throws Exception {
		var sut = new SrneBatteryInverterImpl();
		var clock = clockAt("2026-01-10T15:05:00Z");
		new ComponentTest(sut) //
				.addReference("setModbus", bridge(10, MachineState.RUNNING_MAINS_BYPASS)) //
				.addReference("componentManager", new DummyComponentManager(clock)) //
				.activate(config(-1, -1).build()) //
				.next(new TestCase(), CYCLES);
		assertNull(sut.getDischargeSuppressedChannelForTest().value().get());
		assertNull(sut.dischargeQueuedEnableForTest());
		assertEquals(State.DONE, sut.dischargeWindowStateForTest());
		// The SoC register 0x0100 is not read at all.
		for (var task : sut.defineModbusProtocol().getTaskManager().getTasks()) {
			assertTrue(task instanceof WriteTask || task.getStartAddress() != 0x0100);
		}
	}
}
