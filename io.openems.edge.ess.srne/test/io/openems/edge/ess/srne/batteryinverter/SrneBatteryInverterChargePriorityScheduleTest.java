package io.openems.edge.ess.srne.batteryinverter;

import static io.openems.edge.ess.srne.SrneConstants.DEFAULT_UNIT_ID;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.time.LocalTime;
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
 * Charge-priority schedule (E20F) through the real component. Africa/Kampala is
 * UTC+3: 23:00Z is 02:00 local (night window, hybrid) and 07:00Z is 10:00 local
 * (day, PV only). The dummy bridge does not execute writes, so a test plays the
 * device through the write task's execute callback and the register read-back.
 */
public class SrneBatteryInverterChargePriorityScheduleTest {

	private static final int HYBRID = 2;
	private static final int PV_ONLY = 3;
	private static final int CYCLES = 16; // >= number of LOW read tasks
	private static final int START_CYCLES = ModeSchedule.STARTUP_HOLD_CYCLES + CYCLES;
	private static final String DAY = "2026-11-11T07:00:00Z";
	private static final String NIGHT = "2026-11-11T23:00:00Z";

	private static DummyModbusBridge bridge(int chargePriority, MachineState state) {
		return new DummyModbusBridge("modbus0") //
				.withRegisters(0x0101, 524, 0) //
				.withRegister(0xE20F, chargePriority) //
				.withRegister(0x0210, state.getValue());
	}

	private static MyConfig.Builder config() {
		return MyConfig.create() //
				.setId("batteryInverter0") //
				.setModbusId("modbus0") //
				.setModbusUnitId(DEFAULT_UNIT_ID) //
				.setMaxApparentPower(12_000) //
				.setControlEnabled(true) //
				.setChargePrioritySchedule(true) //
				.setChargePriorityRestoreWhenOff(true);
	}

	private static ComponentTest start(SrneBatteryInverterImpl sut, DummyModbusBridge bridge, String utc,
			MyConfig config) throws Exception {
		return new ComponentTest(sut) //
				.addReference("setModbus", bridge) //
				.addReference("componentManager",
						new DummyComponentManager(new TimeLeapClock(Instant.parse(utc), ZoneOffset.UTC))) //
				.activate(config) //
				.next(new TestCase(), START_CYCLES);
	}

	private static ModeScheduleState state(SrneBatteryInverterImpl sut) {
		Channel<ModeScheduleState> channel = sut.channel(SrneBatteryInverter.ChannelId.CHARGE_PRIORITY_SCHEDULE_STATE);
		return channel.value().asEnum();
	}

	private static Boolean safeValueMissing(SrneBatteryInverterImpl sut) {
		Channel<Boolean> channel = sut.channel(SrneBatteryInverter.ChannelId.CHARGE_PRIORITY_SAFE_VALUE_MISSING);
		return channel.getNextValue().get();
	}

	private static ModeScheduleState nextState(SrneBatteryInverterImpl sut) {
		Channel<ModeScheduleState> channel = sut.channel(SrneBatteryInverter.ChannelId.CHARGE_PRIORITY_SCHEDULE_STATE);
		return channel.getNextValue().asEnum();
	}

	private static Integer target(SrneBatteryInverterImpl sut) {
		Channel<Integer> channel = sut.channel(SrneBatteryInverter.ChannelId.CHARGE_PRIORITY_SCHEDULE_TARGET);
		return channel.value().get();
	}

	private static boolean hasTask(SrneBatteryInverterImpl sut, boolean write, int address) {
		for (var task : sut.defineModbusProtocol().getTaskManager().getTasks()) {
			if (task instanceof WriteTask == write && task.getStartAddress() == address) {
				return true;
			}
		}
		return false;
	}

	@Test
	public void testDefaultIsOffAndDoesNotTouchE20f() throws Exception {
		var sut = new SrneBatteryInverterImpl();
		new ComponentTest(sut) //
				.addReference("setModbus", bridge(HYBRID, MachineState.RUNNING_MAINS_BYPASS)) //
				.activate(config().setChargePrioritySchedule(false).setChargePriorityRestoreWhenOff(false).build()) //
				.next(new TestCase(), CYCLES);
		assertEquals(ModeScheduleState.DISABLED, state(sut));
		assertNull(target(sut));
		assertFalse(sut.chargePriorityScheduleForTest().isActive());
		assertFalse(sut.takeChargePriorityWriteForTest());
		// Default bus traffic unchanged: E20F is neither read nor written.
		assertFalse(hasTask(sut, false, 0xE20F));
		assertFalse(hasTask(sut, true, 0xE20F));
	}

	@Test
	public void testInvalidConfigHoldsPvOnly() throws Exception {
		var sut = new SrneBatteryInverterImpl();
		final var bridge = bridge(HYBRID, MachineState.RUNNING_MAINS_BYPASS);
		final var test = start(sut, bridge, NIGHT, config().setDayChargePriority(HYBRID).build());
		assertTrue(hasTask(sut, true, 0xE20F));
		assertEquals(PV_ONLY, target(sut));
		assertTrue(sut.takeChargePriorityWriteForTest());
		sut.chargePriorityScheduleForTest().onExecute(ExecuteState.OK);
		bridge.withRegister(0xE20F, PV_ONLY);
		test.next(new TestCase(), CYCLES);
		assertEquals(ModeScheduleState.CONFIG_INVALID, state(sut));
		assertFalse(sut.takeChargePriorityWriteForTest());
	}

	@Test
	public void testDisabledWithRestoreHoldsDayValue() throws Exception {
		// Turned off in the night window with hybrid on the device.
		var sut = new SrneBatteryInverterImpl();
		final var bridge = bridge(HYBRID, MachineState.SOFT_START);
		final var test = start(sut, bridge, NIGHT,
				config().setChargePrioritySchedule(false).setChargePriorityRestoreWhenOff(true).build());
		assertTrue(hasTask(sut, false, 0xE20F));
		assertEquals(PV_ONLY, target(sut));
		assertTrue(sut.takeChargePriorityWriteForTest());
		sut.chargePriorityScheduleForTest().onExecute(ExecuteState.OK);
		bridge.withRegister(0xE20F, PV_ONLY);
		test.next(new TestCase(), CYCLES);
		assertEquals(SafeWriteHandler.State.VERIFIED, sut.chargePriorityScheduleForTest().handlerState());
		assertEquals(ModeScheduleState.DISABLED, state(sut));
		assertFalse(sut.takeChargePriorityWriteForTest());
	}

	@Test
	public void testEnabledWithoutRestoreIsInvalidAndHoldsPvOnly() throws Exception {
		var sut = new SrneBatteryInverterImpl();
		final var bridge = bridge(HYBRID, MachineState.RUNNING_MAINS_BYPASS);
		final var test = start(sut, bridge, NIGHT, config().setChargePriorityRestoreWhenOff(false).build());
		assertNotNull(sut.chargePriorityScheduleForTest().getInvalidReason());
		assertEquals(PV_ONLY, target(sut));
		assertTrue(sut.takeChargePriorityWriteForTest());
		sut.chargePriorityScheduleForTest().onExecute(ExecuteState.OK);
		bridge.withRegister(0xE20F, PV_ONLY);
		test.next(new TestCase(), CYCLES);
		assertEquals(ModeScheduleState.CONFIG_INVALID, state(sut));
	}

	@Test
	public void testPersistentOverrideReachesTheAlarmChannel() throws Exception {
		var sut = new SrneBatteryInverterImpl();
		final var bridge = bridge(PV_ONLY, MachineState.RUNNING_MAINS_BYPASS);
		final var test = start(sut, bridge, DAY, config().build());
		final Channel<Boolean> missing = sut.channel(SrneBatteryInverter.ChannelId.CHARGE_PRIORITY_SAFE_VALUE_MISSING);
		// First write (not an override), then overrides on the panel, each corrected.
		bridge.withRegister(0xE20F, HYBRID);
		for (var n = 0; n <= ChargePrioritySchedule.SAFE_OVERRIDE_ALARM; n++) {
			var taken = false;
			for (var i = 0; i < 600 && !taken; i++) {
				test.next(new TestCase());
				taken = sut.takeChargePriorityWriteForTest();
			}
			assertTrue(taken, "write " + n);
			sut.chargePriorityScheduleForTest().onExecute(ExecuteState.OK);
			bridge.withRegister(0xE20F, PV_ONLY);
			test.next(new TestCase(), CYCLES);
			bridge.withRegister(0xE20F, HYBRID);
		}
		bridge.withRegister(0xE20F, PV_ONLY);
		test.next(new TestCase(), CYCLES);
		assertEquals(Boolean.TRUE, missing.value().get());
	}

	@Test
	public void testNightWriteQueuedAndVerified() throws Exception {
		var sut = new SrneBatteryInverterImpl();
		final var bridge = bridge(PV_ONLY, MachineState.RUNNING_MAINS_BYPASS);
		final var test = start(sut, bridge, NIGHT, config().build());
		assertTrue(hasTask(sut, false, 0xE20F));
		assertTrue(hasTask(sut, true, 0xE20F));
		assertEquals(ModeScheduleState.WRITE_PENDING, state(sut));
		assertEquals(HYBRID, target(sut));

		assertTrue(sut.takeChargePriorityWriteForTest());
		sut.chargePriorityScheduleForTest().onExecute(ExecuteState.OK);
		bridge.withRegister(0xE20F, HYBRID);
		test.next(new TestCase(), CYCLES);
		assertEquals(SafeWriteHandler.State.VERIFIED, sut.chargePriorityScheduleForTest().handlerState());
		assertEquals(ModeScheduleState.IN_SYNC, state(sut));
		assertFalse(sut.takeChargePriorityWriteForTest());
	}

	@Test
	public void testReadbackMismatchFails() throws Exception {
		var sut = new SrneBatteryInverterImpl();
		final var bridge = bridge(PV_ONLY, MachineState.RUNNING_MAINS_BYPASS);
		final var test = start(sut, bridge, NIGHT, config().build());
		assertTrue(sut.takeChargePriorityWriteForTest());
		sut.chargePriorityScheduleForTest().onExecute(ExecuteState.OK);
		// The device keeps PV only: the write fails and backs off.
		test.next(new TestCase(), CYCLES);
		assertEquals(SafeWriteHandler.State.FAILED, sut.chargePriorityScheduleForTest().handlerState());
		assertEquals(ModeScheduleState.WRITE_FAILED, state(sut));
		assertFalse(sut.takeChargePriorityWriteForTest());
	}

	@Test
	public void testDayInSyncNeverWrites() throws Exception {
		var sut = new SrneBatteryInverterImpl();
		start(sut, bridge(PV_ONLY, MachineState.RUNNING_MAINS_BYPASS), DAY, config().build()) //
				.next(new TestCase(), CYCLES);
		assertEquals(ModeScheduleState.IN_SYNC, state(sut));
		assertEquals(PV_ONLY, target(sut));
		assertNull(sut.chargePriorityScheduleForTest().handlerState());
		assertFalse(sut.takeChargePriorityWriteForTest());
	}

	@Test
	public void testDayWriteOnBattery() throws Exception {
		// SBU on battery (state 3) by day with hybrid set: the day value goes out
		// without waiting for the line, so a transfer at E01F finds PV only.
		// SOFT_START is the raw state 3 that the unit reports on battery.
		assertEquals(ModeSchedule.ON_BATTERY_STATE, MachineState.SOFT_START.getValue());
		var sut = new SrneBatteryInverterImpl();
		start(sut, bridge(HYBRID, MachineState.SOFT_START), DAY, config().build());
		assertEquals(ModeScheduleState.WRITE_PENDING, state(sut));
		assertEquals(PV_ONLY, target(sut));
		assertTrue(sut.takeChargePriorityWriteForTest());
	}

	@Test
	public void testNightWriteWaitsForMains() throws Exception {
		var sut = new SrneBatteryInverterImpl();
		final var bridge = bridge(PV_ONLY, MachineState.SOFT_START);
		final var test = start(sut, bridge, NIGHT, config().build());
		assertEquals(ModeScheduleState.WAITING_FOR_MAINS, state(sut));
		assertEquals(HYBRID, target(sut));
		assertFalse(sut.takeChargePriorityWriteForTest());

		bridge.withRegister(0x0210, MachineState.RUNNING_MAINS_BYPASS.getValue());
		test.next(new TestCase(), CYCLES);
		assertEquals(ModeScheduleState.WRITE_PENDING, state(sut));
		assertTrue(sut.takeChargePriorityWriteForTest());
	}

	@Test
	public void testFaultBlocksBothDirections() throws Exception {
		var sut = new SrneBatteryInverterImpl();
		final var test = start(sut, bridge(HYBRID, MachineState.FAULT), DAY, config().build());
		assertEquals(ModeScheduleState.WAITING_FOR_MAINS, state(sut));
		assertFalse(sut.takeChargePriorityWriteForTest());
		// Hybrid stays by day: the safe-value alarm is raised after 10 minutes.
		test.next(new TestCase(), ChargePrioritySchedule.SAFE_MISMATCH_ALARM_CYCLES);
		Channel<Boolean> missing = sut.channel(SrneBatteryInverter.ChannelId.CHARGE_PRIORITY_SAFE_VALUE_MISSING);
		assertEquals(Boolean.TRUE, missing.value().get());
	}

	@Test
	public void testBoundariesThroughTheTaskList() throws Exception {
		var sut = new SrneBatteryInverterImpl();
		final var bridge = bridge(PV_ONLY, MachineState.RUNNING_MAINS_BYPASS);
		// 20:58Z is 23:58 Kampala.
		final var clock = new TimeLeapClock(Instant.parse("2026-11-11T20:58:00Z"), ZoneOffset.UTC);
		final var test = new ComponentTest(sut) //
				.addReference("setModbus", bridge) //
				.addReference("componentManager", new DummyComponentManager(clock)) //
				.activate(config().build()) //
				.next(new TestCase(), START_CYCLES);
		assertEquals(ModeScheduleState.IN_SYNC, state(sut));
		// 23:59:00 and 23:59:59: no write before 00:00:00.
		clock.leap(60, ChronoUnit.SECONDS);
		test.next(new TestCase(), CYCLES);
		clock.leap(59, ChronoUnit.SECONDS);
		test.next(new TestCase(), CYCLES);
		assertEquals(PV_ONLY, target(sut));
		assertFalse(sut.takeChargePriorityWriteForTest());
		// 00:00:00: the night value is queued in that cycle.
		clock.leap(1, ChronoUnit.SECONDS);
		test.next(new TestCase());
		assertTrue(sut.takeChargePriorityWriteForTest());
		assertEquals(Integer.valueOf(HYBRID), sut.chargePriorityScheduleForTest().targetAt(LocalTime.MIDNIGHT));
		sut.chargePriorityScheduleForTest().onExecute(ExecuteState.OK);
		bridge.withRegister(0xE20F, HYBRID);
		test.next(new TestCase(), CYCLES);
		assertEquals(ModeScheduleState.IN_SYNC, state(sut));
		// Through the night in 5-minute steps (a steady clock) to 05:59:59.
		for (var i = 0; i < 71; i++) {
			clock.leap(5, ChronoUnit.MINUTES);
			test.next(new TestCase());
		}
		clock.leap(4 * 60 + 59, ChronoUnit.SECONDS);
		test.next(new TestCase(), CYCLES);
		assertEquals(HYBRID, target(sut));
		assertEquals(ModeScheduleState.IN_SYNC, state(sut));
		assertFalse(sut.takeChargePriorityWriteForTest());
		// 06:00:00: the day value is queued in that cycle.
		clock.leap(1, ChronoUnit.SECONDS);
		test.next(new TestCase());
		assertTrue(sut.takeChargePriorityWriteForTest());
		test.next(new TestCase());
		assertEquals(PV_ONLY, target(sut));
	}

	@Test
	public void testNeverKnownClockKeepsTheOtherSettingsRunning() throws Exception {
		// Reboot before NTP: the edge clock stays before the plausibility floor, so the
		// schedule never knows the time. The day-value fallback still goes out and the
		// rest of the settings reconcile (here the E03B stop SoC) is not cut short.
		var sut = new SrneBatteryInverterImpl();
		final var bridge = bridge(HYBRID, MachineState.RUNNING_MAINS_BYPASS).withRegister(0xE03B, 4096);
		final var clock = new TimeLeapClock(Instant.parse("2026-10-01T00:00:00Z"), ZoneOffset.UTC);
		final var test = new ComponentTest(sut) //
				.addReference("setModbus", bridge) //
				.addReference("componentManager", new DummyComponentManager(clock)) //
				.activate(config().setChargeWindow1StopSoc(50).build()) //
				.next(new TestCase(), START_CYCLES);
		assertEquals(SafeWriteHandler.State.QUEUED, sut.chargeWindow1StopSocHandlerForTest().getState());
		assertEquals(PV_ONLY, target(sut));
		assertTrue(sut.takeChargePriorityWriteForTest());
		sut.chargePriorityScheduleForTest().onExecute(ExecuteState.OK);
		bridge.withRegister(0xE20F, PV_ONLY);
		test.next(new TestCase(), CYCLES);
		assertEquals(SafeWriteHandler.State.VERIFIED, sut.chargePriorityScheduleForTest().handlerState());
		assertEquals(ModeScheduleState.IN_SYNC, state(sut));
	}

	@Test
	public void testHoldModeWithNeverKnownClock() throws Exception {
		var sut = new SrneBatteryInverterImpl();
		final var bridge = bridge(HYBRID, MachineState.SOFT_START);
		final var clock = new TimeLeapClock(Instant.parse("2026-10-01T00:00:00Z"), ZoneOffset.UTC);
		final var test = new ComponentTest(sut) //
				.addReference("setModbus", bridge) //
				.addReference("componentManager", new DummyComponentManager(clock)) //
				.activate(config().setChargePrioritySchedule(false).build()) //
				.next(new TestCase(), CYCLES + ChargePrioritySchedule.DAY_STARTUP_HOLD_CYCLES);
		assertTrue(sut.takeChargePriorityWriteForTest());
		sut.chargePriorityScheduleForTest().onExecute(ExecuteState.OK);
		bridge.withRegister(0xE20F, PV_ONLY);
		test.next(new TestCase(), CYCLES);
		assertEquals(ModeScheduleState.DISABLED, state(sut));
	}

	@Test
	public void testFailingScheduleDoesNotStopTheOtherSettings() throws Exception {
		// One schedule throwing every cycle must not skip the other schedule, the
		// scalar settings (here the E03B stop SoC) or the TOU windows.
		var sut = new SrneBatteryInverterImpl();
		final var bridge = bridge(HYBRID, MachineState.RUNNING_MAINS_BYPASS).withRegister(0xE03B, 4096);
		final var clock = new TimeLeapClock(Instant.parse(DAY), ZoneOffset.UTC);
		Runnable failing = () -> {
			throw new IllegalStateException("injected");
		};
		final var test = new ComponentTest(sut) //
				.addReference("setModbus", bridge) //
				.addReference("componentManager", new DummyComponentManager(clock)) //
				.activate(config().setChargeWindow1StopSoc(50).build());
		sut.setScheduleReconcilesForTest(failing, null);
		test.next(new TestCase(), START_CYCLES);
		assertEquals(SafeWriteHandler.State.QUEUED, sut.chargeWindow1StopSocHandlerForTest().getState());
		assertEquals(PV_ONLY, target(sut));
		assertEquals(ModeScheduleState.WRITE_PENDING, state(sut));
		assertEquals(Boolean.FALSE, safeValueMissing(sut));

		var sut2 = new SrneBatteryInverterImpl();
		final var bridge2 = bridge(HYBRID, MachineState.RUNNING_MAINS_BYPASS).withRegister(0xE03B, 4096);
		final var test2 = new ComponentTest(sut2) //
				.addReference("setModbus", bridge2) //
				.addReference("componentManager",
						new DummyComponentManager(new TimeLeapClock(Instant.parse(DAY), ZoneOffset.UTC))) //
				.activate(config().setChargeWindow1StopSoc(50).build());
		sut2.setScheduleReconcilesForTest(null, failing);
		test2.next(new TestCase(), START_CYCLES);
		assertEquals(SafeWriteHandler.State.QUEUED, sut2.chargeWindow1StopSocHandlerForTest().getState());
		assertNull(target(sut2));
		assertFalse(sut2.takeChargePriorityWriteForTest());
		// Never silent: the safe-value alarm holds and the state reads unknown.
		assertEquals(Boolean.TRUE, safeValueMissing(sut2));
		assertEquals(ModeScheduleState.UNDEFINED, state(sut2));

		// Once the schedule stops failing it picks up again (after its startup hold),
		// and the state and alarm are its own again.
		sut2.setScheduleReconcilesForTest(null, null);
		test2.next(new TestCase(), START_CYCLES);
		assertEquals(PV_ONLY, target(sut2));
		assertEquals(ModeScheduleState.WRITE_PENDING, state(sut2));
		assertEquals(Boolean.FALSE, safeValueMissing(sut2));
		assertTrue(sut2.takeChargePriorityWriteForTest());

		// A failure that alternates with clean cycles raises the alarm on every
		// failing cycle and clears it on every clean one.
		var flip = new boolean[1];
		sut2.setScheduleReconcilesForTest(null, () -> {
			flip[0] = !flip[0];
			if (flip[0]) {
				throw new IllegalStateException("flapping");
			}
			sut2.reconcileChargePriorityForTest();
		});
		test2.next(new TestCase());
		assertEquals(Boolean.TRUE, safeValueMissing(sut2));
		assertEquals(ModeScheduleState.UNDEFINED, nextState(sut2));
		test2.next(new TestCase());
		assertEquals(Boolean.FALSE, safeValueMissing(sut2));
		assertNotEquals(ModeScheduleState.UNDEFINED, nextState(sut2));
		// Counted, not reset by the clean cycles: START_CYCLES from activation plus
		// one.
		assertEquals(START_CYCLES + 1, sut2.chargePriorityFailuresForTest());
	}

	@Test
	public void testNightLateChannel() throws Exception {
		var sut = new SrneBatteryInverterImpl();
		final var clock = new TimeLeapClock(Instant.parse(NIGHT), ZoneOffset.UTC);
		final var test = new ComponentTest(sut) //
				.addReference("setModbus", bridge(PV_ONLY, MachineState.SOFT_START)) //
				.addReference("componentManager", new DummyComponentManager(clock)) //
				.activate(config().build()) //
				.next(new TestCase(), START_CYCLES);
		Channel<Boolean> late = sut.channel(SrneBatteryInverter.ChannelId.CHARGE_PRIORITY_NIGHT_LATE);
		assertEquals(Boolean.FALSE, late.value().get());
		// Steady clock steps (a jump of more than 5 minutes would close the clock
		// gate).
		for (var i = 0; i < ChargePrioritySchedule.NIGHT_LATE_MINUTES; i++) {
			clock.leap(1, ChronoUnit.MINUTES);
			test.next(new TestCase());
		}
		test.next(new TestCase());
		assertEquals(Boolean.TRUE, late.value().get());
	}

	@Test
	public void testControlDisabledNeverWrites() throws Exception {
		var sut = new SrneBatteryInverterImpl();
		start(sut, bridge(PV_ONLY, MachineState.RUNNING_MAINS_BYPASS), NIGHT,
				config().setControlEnabled(false).build());
		assertEquals(ModeScheduleState.DISABLED, state(sut));
		assertFalse(sut.takeChargePriorityWriteForTest());
	}
}
