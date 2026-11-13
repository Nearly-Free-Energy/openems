package io.openems.edge.ess.srne.batteryinverter;

import static io.openems.edge.ess.srne.SrneConstants.DEFAULT_UNIT_ID;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
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
 * Daily output-priority schedule through the real component, with a
 * controllable clock. Africa/Kampala is UTC+3: 07:00Z is 10:00 local (daytime,
 * SUB) and 16:00Z is 19:00 local (evening, SBU). The dummy bridge does not
 * execute writes, so a test plays the device by invoking the same execute
 * callback the write task uses and then presenting the register read-back. The
 * schedule writes nothing for {@link ModeSchedule#STARTUP_HOLD_CYCLES} cycles
 * after activation, and the clock gate needs
 * {@link ModeSchedule#CLOCK_SETTLE_CYCLES} steady cycles after a jump.
 */
public class SrneBatteryInverterModeScheduleTest {

	private static final int SBU = 2;
	private static final int SUB = 3;
	private static final int CYCLES = 16; // >= number of LOW read tasks
	// Past the startup hold (which also covers the clock settle period).
	private static final int START_CYCLES = ModeSchedule.STARTUP_HOLD_CYCLES + CYCLES;
	private static final int SETTLE_CYCLES = ModeSchedule.CLOCK_SETTLE_CYCLES + CYCLES;
	private static final String DAY = "2026-11-10T07:00:00Z";
	private static final String EVENING = "2026-11-10T16:00:00Z";
	// Reserve band / BMS as configured for the SBU preconditions.
	private static final int LINE_SOC = 20;
	private static final int BATTERY_SOC = 80;
	private static final int BMS = 1;

	private static TimeLeapClock clockAt(String utc) {
		return new TimeLeapClock(Instant.parse(utc), ZoneOffset.UTC);
	}

	// Device with the reserve band (E01F/E020) and BMS mode (E215) at the
	// configured values, so the SBU preconditions are settled.
	private static DummyModbusBridge bridge(int outputPriority, MachineState state) {
		return new DummyModbusBridge("modbus0") //
				.withRegisters(0x0100, 50) //
				.withRegisters(0x0101, 524, 0) //
				.withRegisters(0xE01C, 20, 95, 15, LINE_SOC, BATTERY_SOC) //
				.withRegisters(0xE204, outputPriority, 200) //
				.withRegister(0xE215, BMS) //
				.withRegister(0x0210, state.getValue());
	}

	private static MyConfig.Builder config() {
		return MyConfig.create() //
				.setId("batteryInverter0") //
				.setModbusId("modbus0") //
				.setModbusUnitId(DEFAULT_UNIT_ID) //
				.setMaxApparentPower(12_000) //
				.setControlEnabled(true) //
				.setSwitchToLineSoc(LINE_SOC) //
				.setSwitchToBatterySoc(BATTERY_SOC) //
				.setBmsCommunication(BMS) //
				.setModeScheduleEnabled(true);
	}

	private static ComponentTest start(SrneBatteryInverterImpl sut, DummyModbusBridge bridge, TimeLeapClock clock,
			MyConfig config) throws Exception {
		return new ComponentTest(sut) //
				.addReference("setModbus", bridge) //
				.addReference("componentManager", new DummyComponentManager(clock)) //
				.activate(config) //
				.next(new TestCase(), START_CYCLES);
	}

	private static ComponentTest start(SrneBatteryInverterImpl sut, DummyModbusBridge bridge, String utc,
			MyConfig config) throws Exception {
		return start(sut, bridge, clockAt(utc), config);
	}

	private static ModeScheduleState state(SrneBatteryInverterImpl sut) {
		Channel<ModeScheduleState> channel = sut.channel(SrneBatteryInverter.ChannelId.MODE_SCHEDULE_STATE);
		return channel.value().asEnum();
	}

	private static Integer target(SrneBatteryInverterImpl sut) {
		Channel<Integer> channel = sut.channel(SrneBatteryInverter.ChannelId.MODE_SCHEDULE_TARGET_PRIORITY);
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

	private static int countWriteTasks(SrneBatteryInverterImpl sut, int address) {
		var count = 0;
		for (var task : sut.defineModbusProtocol().getTaskManager().getTasks()) {
			if (task instanceof WriteTask && task.getStartAddress() == address) {
				count++;
			}
		}
		return count;
	}

	// Plays the device for the queued E204 write: execute callback, then read-back.
	private static void deviceWrites(SrneBatteryInverterImpl sut, DummyModbusBridge bridge, ComponentTest test,
			int value) throws Exception {
		assertTrue(sut.takeModeScheduleWriteForTest());
		sut.modeScheduleForTest().onExecute(ExecuteState.OK);
		bridge.withRegisters(0xE204, value, 200);
		test.next(new TestCase(), CYCLES);
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
		assertEquals(ModeScheduleReason.UNDEFINED, reason(sut)); // never set
		assertNull(sut.modeScheduleForTest().queuedTarget());
		assertFalse(sut.takeModeScheduleWriteForTest());
		// Default bus traffic unchanged: no SoC read and no second E204 write task.
		assertFalse(hasTask(sut, false, 0x0100));
		assertEquals(1, countWriteTasks(sut, 0xE204));
	}

	@Test
	public void testDaytimeWriteQueuedAndVerified() throws Exception {
		var sut = new SrneBatteryInverterImpl();
		final var bridge = bridge(SBU, MachineState.RUNNING_MAINS_BYPASS);
		final var test = start(sut, bridge, DAY, config().build());
		assertTrue(hasTask(sut, false, 0x0100));
		assertEquals(2, countWriteTasks(sut, 0xE204));
		assertEquals(ModeScheduleState.WRITE_PENDING, state(sut));
		assertEquals(SUB, target(sut));
		assertEquals(SUB, sut.modeScheduleForTest().queuedTarget());

		deviceWrites(sut, bridge, test, SUB);
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
		final var bridge = bridge(SUB, MachineState.INVERTER_POWERED);
		final var test = start(sut, bridge, EVENING, config().build());
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
	public void testMainsBlipNeverWrites() throws Exception {
		var sut = new SrneBatteryInverterImpl();
		final var bridge = bridge(SUB, MachineState.INVERTER_POWERED);
		final var test = start(sut, bridge, EVENING, config().build());
		// State 2 across exactly one fresh read of 0x0210, then back on battery.
		final var reads = new int[1];
		sut.getMachineStateChannel().onSetNextValue(ignore -> reads[0]++);
		bridge.withRegister(0x0210, MachineState.RUNNING_MAINS_BYPASS.getValue());
		final var before = reads[0];
		for (var i = 0; i < CYCLES && reads[0] == before; i++) {
			test.next(new TestCase());
		}
		assertEquals(before + 1, reads[0]);
		bridge.withRegister(0x0210, MachineState.INVERTER_POWERED.getValue());
		test.next(new TestCase(), CYCLES);
		assertEquals(ModeScheduleState.WAITING_FOR_MAINS, state(sut));
		assertNull(sut.modeScheduleForTest().handlerState());
		assertFalse(sut.takeModeScheduleWriteForTest());
	}

	@Test
	public void testMainsLossWhileQueuedWithdrawsTheWrite() throws Exception {
		var sut = new SrneBatteryInverterImpl();
		final var bridge = bridge(SBU, MachineState.RUNNING_MAINS_BYPASS);
		final var test = start(sut, bridge, DAY, config().build());
		assertEquals(SUB, sut.modeScheduleForTest().queuedTarget());

		// Grid cut before the bridge executed the write: it is withdrawn.
		bridge.withRegister(0x0210, MachineState.INVERTER_POWERED.getValue());
		test.next(new TestCase(), CYCLES);
		assertEquals(ModeScheduleState.WAITING_FOR_MAINS, state(sut));
		assertNull(sut.modeScheduleForTest().queuedTarget());
		assertFalse(sut.takeModeScheduleWriteForTest());

		// Mains back: queued again.
		bridge.withRegister(0x0210, MachineState.RUNNING_MAINS_BYPASS.getValue());
		test.next(new TestCase(), CYCLES);
		assertEquals(SUB, sut.modeScheduleForTest().queuedTarget());
		assertTrue(sut.takeModeScheduleWriteForTest());
	}

	@Test
	public void testStartupHoldAfterActivation() throws Exception {
		var sut = new SrneBatteryInverterImpl();
		new ComponentTest(sut) //
				.addReference("setModbus", bridge(SBU, MachineState.RUNNING_MAINS_BYPASS)) //
				.addReference("componentManager", new DummyComponentManager(clockAt(DAY))) //
				.activate(config().build()) //
				.next(new TestCase(), ModeSchedule.STARTUP_HOLD_CYCLES - 10);
		assertEquals(ModeScheduleState.STARTUP_HOLD, state(sut));
		assertFalse(sut.takeModeScheduleWriteForTest());
	}

	@Test
	public void testMainsLossAfterBridgeTookTheWriteKeepsItInFlight() throws Exception {
		var sut = new SrneBatteryInverterImpl();
		final var bridge = bridge(SBU, MachineState.RUNNING_MAINS_BYPASS);
		final var test = start(sut, bridge, DAY, config().build());
		// The bridge takes the value, then mains drops before the execute callback.
		assertTrue(sut.takeModeScheduleWriteForTest());
		bridge.withRegister(0x0210, MachineState.INVERTER_POWERED.getValue());
		test.next(new TestCase(), CYCLES);
		assertEquals(SafeWriteHandler.State.QUEUED, sut.modeScheduleForTest().handlerState());
		assertEquals(0, sut.modeScheduleForTest().failuresInWindow());
		assertEquals(ModeScheduleState.WRITE_PENDING, state(sut));
	}

	@Test
	public void testLeapsAcrossBothBoundaries() throws Exception {
		var sut = new SrneBatteryInverterImpl();
		final var clock = clockAt("2026-11-10T14:59:00Z"); // 17:59 local
		final var bridge = bridge(SUB, MachineState.RUNNING_MAINS_BYPASS);
		final var test = start(sut, bridge, clock, config().build());
		assertEquals(ModeScheduleState.IN_SYNC, state(sut));
		assertEquals(SUB, target(sut));

		// 18:00: one minute later (no clock-gate reset), SBU is written.
		clock.leap(1, ChronoUnit.MINUTES);
		test.next(new TestCase(), CYCLES);
		assertEquals(SBU, target(sut));
		assertEquals(SBU, sut.modeScheduleForTest().queuedTarget());
		deviceWrites(sut, bridge, test, SBU);
		assertEquals(ModeScheduleState.IN_SYNC, state(sut));

		// 00:30 next day: a 6.5 h jump restarts the clock gate, then SUB is written.
		clock.leap(Duration.ofHours(6).plusMinutes(30).toMinutes(), ChronoUnit.MINUTES);
		test.next(new TestCase(), CYCLES);
		assertEquals(ModeScheduleState.WAITING_FOR_DATA, state(sut));
		test.next(new TestCase(), SETTLE_CYCLES);
		assertEquals(SUB, target(sut));
		assertEquals(SUB, sut.modeScheduleForTest().queuedTarget());
		deviceWrites(sut, bridge, test, SUB);
		assertEquals(ModeScheduleState.IN_SYNC, state(sut));
	}

	@Test
	public void testStaleClockStartWaitsForPlausibleTime() throws Exception {
		var sut = new SrneBatteryInverterImpl();
		final var clock = new TimeLeapClock(Instant.EPOCH, ZoneOffset.UTC); // no RTC, not yet synced
		final var test = start(sut, bridge(SBU, MachineState.RUNNING_MAINS_BYPASS), clock, config().build());
		test.next(new TestCase(), START_CYCLES);
		assertEquals(ModeScheduleState.WAITING_FOR_DATA, state(sut));
		assertNull(target(sut));
		assertFalse(sut.takeModeScheduleWriteForTest());

		// NTP sets the clock: still unknown until it has run steadily.
		clock.leap(Duration.between(Instant.EPOCH, Instant.parse(DAY)).toSeconds(), ChronoUnit.SECONDS);
		test.next(new TestCase(), CYCLES);
		assertEquals(ModeScheduleState.WAITING_FOR_DATA, state(sut));
		assertFalse(sut.takeModeScheduleWriteForTest());
		test.next(new TestCase(), START_CYCLES);
		assertEquals(SUB, target(sut));
		assertEquals(SUB, sut.modeScheduleForTest().queuedTarget());
	}

	@Test
	public void testSbuBlockedWithoutPreconditions() throws Exception {
		var sut = new SrneBatteryInverterImpl();
		final var config = config() //
				.setSwitchToLineSoc(-1) //
				.setSwitchToBatterySoc(-1) //
				.setBmsCommunication(-1) //
				.build();
		start(sut, bridge(SUB, MachineState.RUNNING_MAINS_BYPASS), EVENING, config) //
				.next(new TestCase(), CYCLES);
		assertEquals(ModeScheduleState.BLOCKED_SBU_PRECONDITIONS, state(sut));
		assertEquals(SBU, target(sut));
		assertNull(sut.modeScheduleForTest().handlerState());
		assertFalse(sut.takeModeScheduleWriteForTest());
	}

	@Test
	public void testSbuBlockedWhenBandIncoherent() throws Exception {
		var sut = new SrneBatteryInverterImpl();
		final var config = config() //
				.setSwitchToLineSoc(BATTERY_SOC) //
				.setSwitchToBatterySoc(LINE_SOC) //
				.build();
		start(sut, bridge(SUB, MachineState.RUNNING_MAINS_BYPASS), EVENING, config) //
				.next(new TestCase(), CYCLES);
		assertEquals(ModeScheduleState.BLOCKED_SBU_PRECONDITIONS, state(sut));
		assertFalse(sut.takeModeScheduleWriteForTest());
	}

	@Test
	public void testSubNeedsNoPreconditions() throws Exception {
		var sut = new SrneBatteryInverterImpl();
		final var config = config() //
				.setSwitchToLineSoc(-1) //
				.setSwitchToBatterySoc(-1) //
				.setBmsCommunication(-1) //
				.build();
		start(sut, bridge(SBU, MachineState.RUNNING_MAINS_BYPASS), DAY, config);
		assertEquals(ModeScheduleState.WRITE_PENDING, state(sut));
		assertEquals(SUB, sut.modeScheduleForTest().queuedTarget());
	}

	@Test
	public void testFailedReadbackReportsWriteFailed() throws Exception {
		var sut = new SrneBatteryInverterImpl();
		final var bridge = bridge(SBU, MachineState.RUNNING_MAINS_BYPASS);
		final var test = start(sut, bridge, DAY, config().build());
		assertTrue(sut.takeModeScheduleWriteForTest());
		// The frame is accepted but the device keeps SBU.
		sut.modeScheduleForTest().onExecute(ExecuteState.OK);
		test.next(new TestCase(), CYCLES);
		assertEquals(SafeWriteHandler.State.FAILED, sut.modeScheduleForTest().handlerState());
		assertEquals(ModeScheduleState.WRITE_FAILED, state(sut));
		// No retry storm: nothing new is queued during the backoff.
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
	public void testInvalidConfigValues() throws Exception {
		for (var config : new MyConfig[] { config().setEveningStart("25:00").build(),
				config().setDaytimePriority(SBU).setEveningPriority(SBU).build() }) {
			var sut = new SrneBatteryInverterImpl();
			start(sut, bridge(SBU, MachineState.RUNNING_MAINS_BYPASS), DAY, config) //
					.next(new TestCase(), CYCLES);
			assertEquals(ModeScheduleState.CONFIG_INVALID, state(sut));
			assertFalse(sut.takeModeScheduleWriteForTest());
		}
	}

	private static ModeScheduleReason reason(SrneBatteryInverterImpl sut) {
		Channel<ModeScheduleReason> channel = sut.channel(SrneBatteryInverter.ChannelId.MODE_SCHEDULE_REASON);
		return channel.value().asEnum();
	}

	@Test
	public void testDaytimeHighSocQueuesSbu() throws Exception {
		var sut = new SrneBatteryInverterImpl();
		final var bridge = bridge(SUB, MachineState.RUNNING_MAINS_BYPASS) //
				.withRegisters(0x0100, 96);
		final var clock = clockAt(DAY);
		final var test = start(sut, bridge, clock,
				config().setDaytimeSbuAboveSoc(95).setDaytimeSbuBelowSoc(85).build());
		assertEquals(SBU, target(sut));
		assertEquals(ModeScheduleReason.HIGH_SOC, reason(sut));
		// The first SoC-driven switch after activation waits the minimum dwell.
		assertEquals(ModeScheduleState.MIN_DWELL, state(sut));
		for (var i = 0; i < 3; i++) {
			clock.leap(4, ChronoUnit.MINUTES); // small steps: no clock-gate reset
			test.next(new TestCase(), CYCLES);
		}
		assertEquals(SBU, sut.modeScheduleForTest().queuedTarget());
	}

	@Test
	public void testDaytimeLowSocKeepsSub() throws Exception {
		var sut = new SrneBatteryInverterImpl();
		start(sut, bridge(SUB, MachineState.RUNNING_MAINS_BYPASS), DAY,
				config().setDaytimeSbuAboveSoc(95).setDaytimeSbuBelowSoc(85).build());
		assertEquals(ModeScheduleState.IN_SYNC, state(sut));
		assertEquals(ModeScheduleReason.LOW_SOC, reason(sut));
	}

	@Test
	public void testSocRuleOffReportsWindowReason() throws Exception {
		var sut = new SrneBatteryInverterImpl();
		final var bridge = bridge(SUB, MachineState.RUNNING_MAINS_BYPASS) //
				.withRegisters(0x0100, 100);
		start(sut, bridge, DAY, config().build());
		assertEquals(ModeScheduleState.IN_SYNC, state(sut));
		assertEquals(SUB, target(sut));
		assertEquals(ModeScheduleReason.WINDOW, reason(sut));
	}

	@Test
	public void testInvalidSocRuleMakesConfigInvalid() throws Exception {
		var sut = new SrneBatteryInverterImpl();
		start(sut, bridge(SUB, MachineState.RUNNING_MAINS_BYPASS), DAY,
				config().setDaytimeSbuAboveSoc(85).setDaytimeSbuBelowSoc(95).build());
		assertEquals(ModeScheduleState.CONFIG_INVALID, state(sut));
		assertFalse(sut.takeModeScheduleWriteForTest());
	}

	// Aaron's case: SBU written at high SoC, the unit leaves the line (state 3),
	// SoC falls below daytimeSbuBelowSoc. With the grid present on all phases the
	// SUB write goes out; with the grid absent it does not.
	private static void sbuThenOnBatteryBelowThreshold(boolean gridPresent, int gridPresentMinVoltage)
			throws Exception {
		var sut = new SrneBatteryInverterImpl();
		final var clock = clockAt(DAY);
		final var bridge = bridge(SUB, MachineState.RUNNING_MAINS_BYPASS) //
				.withRegisters(0x0100, 96) //
				.withRegister(0x0213, 2300) // 230.0 V
				.withRegisters(0x022A, 2300, 2300);
		final var test = start(sut, bridge, clock, config().setDaytimeSbuAboveSoc(95).setDaytimeSbuBelowSoc(85)
				.setGridPresentMinVoltage(gridPresentMinVoltage).build());
		for (var i = 0; i < 3; i++) {
			clock.leap(4, ChronoUnit.MINUTES); // past the dwell from activation
			test.next(new TestCase(), CYCLES);
		}
		assertEquals(SBU, sut.modeScheduleForTest().queuedTarget());
		deviceWrites(sut, bridge, test, SBU);
		assertEquals(ModeScheduleState.IN_SYNC, state(sut));

		// The unit leaves the line at E020 and runs on battery; SoC falls to 80 %.
		bridge.withRegister(0x0210, 3) //
				.withRegisters(0x0100, 80);
		if (!gridPresent) {
			bridge.withRegister(0x0213, 0).withRegisters(0x022A, 0, 0);
		}
		for (var i = 0; i < 3; i++) {
			clock.leap(4, ChronoUnit.MINUTES); // past the dwell from the SBU switch
			test.next(new TestCase(), CYCLES);
		}
		assertEquals(SUB, target(sut));
		if (gridPresent && gridPresentMinVoltage > 0) {
			assertTrue(hasTask(sut, false, 0x0213));
			assertEquals(SUB, sut.modeScheduleForTest().queuedTarget());
			assertTrue(sut.takeModeScheduleWriteForTest());
		} else {
			assertEquals(ModeScheduleState.WAITING_FOR_MAINS, state(sut));
			assertNull(sut.modeScheduleForTest().queuedTarget());
			assertFalse(sut.takeModeScheduleWriteForTest());
			assertEquals(gridPresentMinVoltage > 0, hasTask(sut, false, 0x0213));
		}
	}

	@Test
	public void testSubWriteOnBatteryWithGridPresent() throws Exception {
		sbuThenOnBatteryBelowThreshold(true, 180);
	}

	@Test
	public void testSubOnBatteryOffByDefaultWaitsForMains() throws Exception {
		// Default -1: no grid-voltage reads, the state-3 SUB target waits for mains.
		sbuThenOnBatteryBelowThreshold(true, -1);
	}

	@Test
	public void testNoSubWriteOnBatteryWithoutGrid() throws Exception {
		sbuThenOnBatteryBelowThreshold(false, 180);
	}

	@Test
	public void testEveningReserveWritesSub() throws Exception {
		var sut = new SrneBatteryInverterImpl();
		final var clock = clockAt("2026-11-10T12:00:00Z"); // 15:00 local, in the reserve window
		final var bridge = bridge(SBU, MachineState.RUNNING_MAINS_BYPASS) //
				.withRegisters(0x0100, 88);
		final var test = start(sut, bridge, clock,
				config().setDaytimeSbuAboveSoc(95).setDaytimeSbuBelowSoc(85).setEveningReserveSoc(90).build());
		assertEquals(SUB, target(sut));
		assertEquals(ModeScheduleReason.EVENING_RESERVE, reason(sut));
		for (var i = 0; i < 3; i++) {
			clock.leap(4, ChronoUnit.MINUTES); // past the dwell from activation
			test.next(new TestCase(), CYCLES);
		}
		assertEquals(SUB, sut.modeScheduleForTest().queuedTarget());
	}

	@Test
	public void testEveningReserveOffByDefault() throws Exception {
		var sut = new SrneBatteryInverterImpl();
		final var bridge = bridge(SBU, MachineState.RUNNING_MAINS_BYPASS) //
				.withRegisters(0x0100, 88);
		start(sut, bridge, "2026-11-10T12:00:00Z",
				config().setDaytimeSbuAboveSoc(95).setDaytimeSbuBelowSoc(85).build());
		assertEquals(ModeScheduleState.IN_SYNC, state(sut));
		assertEquals(SBU, target(sut));
		assertEquals(ModeScheduleReason.HIGH_SOC, reason(sut));
	}

	@Test
	public void testEveningReserveHoldThroughConfigThenEveningSbu() throws Exception {
		var sut = new SrneBatteryInverterImpl();
		final var clock = clockAt("2026-11-12T13:00:00Z"); // 16:00 local, in the reserve window
		final var bridge = bridge(SUB, MachineState.RUNNING_MAINS_BYPASS) //
				.withRegisters(0x0100, 96);
		final var test = start(sut, bridge, clock, config().setDaytimeSbuAboveSoc(95).setDaytimeSbuBelowSoc(85)
				.setEveningReserveSoc(90).setEveningReserveHold(true).build());
		// Above the reserve and above daytimeSbuAboveSoc: held in SUB, nothing written.
		assertEquals(SUB, target(sut));
		assertEquals(ModeScheduleReason.EVENING_RESERVE_HOLD, reason(sut));
		assertEquals(ModeScheduleState.IN_SYNC, state(sut));
		assertFalse(sut.takeModeScheduleWriteForTest());
		// 18:00 local: the evening SBU is written.
		clock.leap(2, ChronoUnit.HOURS);
		test.next(new TestCase(), SETTLE_CYCLES);
		assertEquals(SBU, target(sut));
		assertEquals(ModeScheduleReason.WINDOW, reason(sut));
		assertEquals(SBU, sut.modeScheduleForTest().queuedTarget());
	}
}
