package io.openems.edge.ess.srne.batteryinverter;

import static io.openems.edge.ess.srne.SrneConstants.DEFAULT_UNIT_ID;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
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
	// The clock gate needs CLOCK_SETTLE_CYCLES steady cycles after start or a step.
	private static final int SETTLE_CYCLES = ModeSchedule.CLOCK_SETTLE_CYCLES + CYCLES;

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
				.next(new TestCase(), SETTLE_CYCLES);
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
		test.next(new TestCase(), SETTLE_CYCLES);
		assertEquals(NightChargeStop.State.STOPPED, state(sut));
		assertEquals(0, sut.chargeWindowForTest().queuedEnable());
		assertEquals(ScheduleWindow.State.DISABLE_QUEUED, sut.chargeWindowForTest().getState());
		deviceWritesEnable(sut, bridge, test, 0);
		assertEquals(ScheduleWindow.State.DONE, sut.chargeWindowForTest().getState());

		// Still in the window at a low SoC: no re-arm.
		bridge.withRegisters(0x0100, 30);
		clock.leap(150, ChronoUnit.MINUTES); // 05:30
		test.next(new TestCase(), SETTLE_CYCLES);
		assertEquals(NightChargeStop.State.STOPPED, state(sut));
		assertEquals(ScheduleWindow.State.DONE, sut.chargeWindowForTest().getState());

		// 06:10, then 06:15 local in a small step: re-armed so the next night charges.
		clock.leap(40, ChronoUnit.MINUTES);
		test.next(new TestCase(), SETTLE_CYCLES);
		assertEquals(NightChargeStop.State.STOPPED, state(sut));
		clock.leap(5, ChronoUnit.MINUTES);
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

	@Test
	public void testClockStepPastTheWindowEndDoesNotRearm() throws Exception {
		var sut = new SrneBatteryInverterImpl();
		final var clock = clockAt("2026-11-13T00:00:00Z"); // 03:00 local
		final var bridge = bridge(60, MachineState.RUNNING_MAINS_BYPASS);
		final var test = start(sut, bridge, clock, config(50));
		assertEquals(NightChargeStop.State.STOPPED, state(sut));
		deviceWritesEnable(sut, bridge, test, 0);

		// The edge clock steps to 07:00 local and settles: still stopped.
		clock.leap(4, ChronoUnit.HOURS);
		test.next(new TestCase(), SETTLE_CYCLES);
		assertEquals(NightChargeStop.State.STOPPED, state(sut));
		assertEquals(ScheduleWindow.State.DONE, sut.chargeWindowForTest().getState());
		assertNull(sut.chargeWindowForTest().enableWriteElement().getNextWriteValueAndReset());
		test.deactivate();
	}

	@Test
	public void testFailedRearmReportsWriteFailed() throws Exception {
		var sut = new SrneBatteryInverterImpl();
		final var clock = clockAt("2026-11-13T02:55:00Z"); // 05:55 local
		final var bridge = bridge(60, MachineState.RUNNING_MAINS_BYPASS);
		final var test = start(sut, bridge, clock, config(50));
		assertEquals(NightChargeStop.State.STOPPED, state(sut));
		deviceWritesEnable(sut, bridge, test, 0);
		bridge.withRegisters(0x0100, 30);

		// 06:15 local in small steps: the re-arm goes out, but the read-back stays 0.
		for (var i = 0; i < 4; i++) {
			clock.leap(5, ChronoUnit.MINUTES);
			test.next(new TestCase(), CYCLES);
		}
		assertEquals(1, sut.chargeWindowForTest().queuedEnable());
		deviceWritesEnable(sut, bridge, test, 0);
		test.next(new TestCase(), CYCLES);
		assertEquals(ScheduleWindow.State.FAILED, sut.chargeWindowForTest().getState());
		assertEquals(NightChargeStop.State.WRITE_FAILED, state(sut));
		test.deactivate();
	}

	@Test
	public void testRearmTimeCrossedDuringAnOutageRearmsWhenMainsReturns() throws Exception {
		var sut = new SrneBatteryInverterImpl();
		final var clock = clockAt("2026-11-13T02:55:00Z"); // 05:55 local
		final var bridge = bridge(60, MachineState.RUNNING_MAINS_BYPASS);
		final var test = start(sut, bridge, clock, config(50));
		assertEquals(NightChargeStop.State.STOPPED, state(sut));
		deviceWritesEnable(sut, bridge, test, 0);

		// Grid lost; the clock runs on in 5-minute steps past 06:15 local.
		bridge.withRegister(0x0210, MachineState.INVERTER_POWERED.getValue());
		bridge.withRegisters(0x0100, 30);
		for (var i = 0; i < 6; i++) {
			clock.leap(5, ChronoUnit.MINUTES);
			test.next(new TestCase(), CYCLES);
		}
		assertEquals(NightChargeStop.State.WAITING_FOR_MAINS, state(sut));
		assertNull(sut.chargeWindowForTest().enableWriteElement().getNextWriteValueAndReset());

		// Mains back at 06:25: the re-arm goes out at once, not 2 hours later.
		bridge.withRegister(0x0210, MachineState.RUNNING_MAINS_BYPASS.getValue());
		test.next(new TestCase(), CYCLES);
		assertEquals(NightChargeStop.State.OUTSIDE_WINDOW, state(sut));
		assertEquals(1, sut.chargeWindowForTest().queuedEnable());
		test.deactivate();
	}

	// Charge timer 00:05-01:30 inside the charge-priority night 00:00-01:35.
	private static final int SHORT_START = 5; // 00:05
	private static final int SHORT_STOP = 286; // 01:30

	private static Integer chargePriorityTarget(SrneBatteryInverterImpl sut) {
		Channel<Integer> channel = sut.channel(SrneBatteryInverter.ChannelId.CHARGE_PRIORITY_SCHEDULE_TARGET);
		return channel.value().get();
	}

	// 00:55 local (21:55Z), SoC 30 %, hybrid on the device, past the startup holds.
	private static ComponentTest startShortNight(SrneBatteryInverterImpl sut, DummyModbusBridge bridge,
			TimeLeapClock clock, int nightChargeStopSoc) throws Exception {
		return new ComponentTest(sut) //
				.addReference("setModbus", bridge) //
				.addReference("componentManager", new DummyComponentManager(clock)) //
				.activate(MyConfig.create() //
						.setId("batteryInverter0") //
						.setModbusId("modbus0") //
						.setModbusUnitId(DEFAULT_UNIT_ID) //
						.setMaxApparentPower(12_000) //
						.setControlEnabled(true) //
						.setChargeWindow1Start(SHORT_START) //
						.setChargeWindow1Stop(SHORT_STOP) //
						.setChargeScheduleEnable(1) //
						.setNightChargeStopSoc(nightChargeStopSoc) //
						.setChargePrioritySchedule(true) //
						.setChargePriorityRestoreWhenOff(true) //
						.setNightChargeFrom("00:00") //
						.setNightChargeTo("01:35") //
						.build()) //
				.next(new TestCase(), ModeSchedule.STARTUP_HOLD_CYCLES + CYCLES);
	}

	private static DummyModbusBridge shortNightBridge() {
		return shortNightBridge(1, 2, 30, MachineState.RUNNING_MAINS_BYPASS);
	}

	private static DummyModbusBridge shortNightBridge(int chargeEnable, int chargePriority, int soc,
			MachineState state) {
		return new DummyModbusBridge("modbus0") //
				.withRegisters(0xE026, SHORT_START, SHORT_STOP) //
				.withRegisters(0xE02C, chargeEnable, 0, 0) //
				.withRegisters(0x0100, soc) //
				.withRegisters(0x0101, 524, 0) //
				.withRegister(0xE20F, chargePriority) //
				.withRegister(0x0210, state.getValue());
	}

	// Steady 5-minute steps (a larger step would close the clock gates).
	private static void leap(TimeLeapClock clock, ComponentTest test, int minutes) throws Exception {
		for (var i = 0; i < minutes / 5; i++) {
			clock.leap(5, ChronoUnit.MINUTES);
			test.next(new TestCase(), CYCLES);
		}
	}

	@Test
	public void testStopEndsTheNightChargePriority() throws Exception {
		var sut = new SrneBatteryInverterImpl();
		final var clock = clockAt("2026-11-12T21:55:00Z");
		final var bridge = shortNightBridge();
		final var test = startShortNight(sut, bridge, clock, 50);
		assertEquals(NightChargeStop.State.CHARGING, state(sut));
		assertEquals(2, chargePriorityTarget(sut));
		assertFalse(sut.takeChargePriorityWriteForTest());

		// 01:05 local, SoC at the stop: E02C is disarmed and E20F goes to PV only now.
		leap(clock, test, 10);
		bridge.withRegisters(0x0100, 50);
		for (var i = 0; i < 4 * CYCLES && state(sut) != NightChargeStop.State.STOPPED; i++) {
			test.next(new TestCase());
		}
		assertEquals(NightChargeStop.State.STOPPED, state(sut));
		deviceWritesEnable(sut, bridge, test, 0);
		assertEquals(NightChargeStop.State.STOPPED, state(sut));
		assertEquals(3, chargePriorityTarget(sut));
		assertTrue(sut.takeChargePriorityWriteForTest());
		sut.chargePriorityScheduleForTest().onExecute(ExecuteState.OK);
		bridge.withRegister(0xE20F, 3);
		test.next(new TestCase(), CYCLES);
		assertEquals(SafeWriteHandler.State.VERIFIED, sut.chargePriorityScheduleForTest().handlerState());

		// SoC far below the stop until the night window ends: no hybrid again.
		bridge.withRegisters(0x0100, 20);
		for (var i = 0; i < 6; i++) {
			leap(clock, test, 5);
			assertEquals(3, chargePriorityTarget(sut));
			assertFalse(sut.takeChargePriorityWriteForTest());
		}
		assertEquals(NightChargeStop.State.STOPPED, state(sut));
		test.deactivate();
	}

	@Test
	public void testStopOffKeepsTheNightChargePriorityUntilTheWindowEnd() throws Exception {
		var sut = new SrneBatteryInverterImpl();
		final var clock = clockAt("2026-11-12T21:55:00Z");
		final var bridge = shortNightBridge();
		final var test = startShortNight(sut, bridge, clock, -1);
		bridge.withRegisters(0x0100, 99);
		// 00:55 to 01:30 local: hybrid stays, nothing is written.
		for (var i = 0; i < 7; i++) {
			leap(clock, test, 5);
			assertEquals(2, chargePriorityTarget(sut));
			assertFalse(sut.takeChargePriorityWriteForTest());
		}
		// 01:35: the day value at the normal window end.
		leap(clock, test, 5);
		assertEquals(3, chargePriorityTarget(sut));
		assertTrue(sut.takeChargePriorityWriteForTest());
		test.deactivate();
	}

	@Test
	public void testRestartAfterTheStopDoesNotRaiseTheNightValue() throws Exception {
		// Restart at 01:10 local after a stop: E02C reads 0, E20F reads 3, the SoC is
		// in the hysteresis band just below the stop, and the unit is on battery (the
		// settings gate is closed).
		var sut = new SrneBatteryInverterImpl();
		final var clock = clockAt("2026-11-12T22:10:00Z");
		final var bridge = shortNightBridge(0, 3, 48, MachineState.SOFT_START);
		final var test = startShortNight(sut, bridge, clock, 50);
		assertEquals(2, chargePriorityTarget(sut));
		assertFalse(sut.takeChargePriorityWriteForTest());
		// Mains returns: the stop holds the device disarmed and is confirmed as a stop;
		// the night value is never written in between.
		bridge.withRegister(0x0210, MachineState.RUNNING_MAINS_BYPASS.getValue());
		for (var i = 0; i < NightChargeStop.HELD_CONFIRM_CYCLES + 2 * CYCLES; i++) {
			test.next(new TestCase());
			assertFalse(sut.takeChargePriorityWriteForTest(), "cycle " + i);
		}
		assertEquals(NightChargeStop.State.HELD_DISARMED, state(sut));
		assertEquals(3, chargePriorityTarget(sut));
		test.deactivate();
	}

	@Test
	public void testFailedDayWriteAfterTheStopDoesNotBlockTheOtherSettings() throws Exception {
		var sut = new SrneBatteryInverterImpl();
		final var clock = clockAt("2026-11-12T21:55:00Z");
		final var bridge = shortNightBridge();
		final var test = startShortNight(sut, bridge, clock, 50);
		leap(clock, test, 10);
		bridge.withRegisters(0x0100, 50);
		for (var i = 0; i < 4 * CYCLES && state(sut) != NightChargeStop.State.STOPPED; i++) {
			test.next(new TestCase());
		}
		deviceWritesEnable(sut, bridge, test, 0);
		// The day write fails on the bus.
		assertTrue(sut.takeChargePriorityWriteForTest());
		sut.chargePriorityScheduleForTest().onExecute(new ExecuteState.Error(new IllegalStateException("test")));
		test.next(new TestCase(), CYCLES);
		assertEquals(SafeWriteHandler.State.FAILED, sut.chargePriorityScheduleForTest().handlerState());
		// E20F has its own handler: the shared settings-write state is not FAILED.
		Channel<SafeWriteHandler.State> writeState = sut.channel(SrneBatteryInverter.ChannelId.SAFE_WRITE_STATE);
		assertNotEquals(SafeWriteHandler.State.FAILED, writeState.value().asEnum());
		// Retried after the backoff, still the day value.
		var retried = false;
		for (var i = 0; i < 600 && !retried; i++) {
			test.next(new TestCase());
			retried = sut.takeChargePriorityWriteForTest();
		}
		assertTrue(retried);
		assertEquals(3, chargePriorityTarget(sut));
		test.deactivate();
	}
}
