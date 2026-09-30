package io.openems.edge.ess.srne.batteryinverter;

import static io.openems.edge.ess.srne.SrneConstants.DEFAULT_UNIT_ID;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.List;
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
import io.openems.edge.ess.srne.batteryinverter.ScheduleWindow.State;
import io.openems.edge.ess.srne.common.enums.MachineState;

/**
 * Discharge start-threshold / floor rules through the real component with a
 * controllable clock and SoC. The window is the production one, 21:00-23:00 in
 * Africa/Kampala (UTC+3), so 18:05Z is 21:05 local. The dummy bridge does not
 * execute writes, so a test plays the device by invoking the same execute
 * callback the write task uses and then presenting the register read-back.
 */
public class SrneBatteryInverterDischargeRulesTest {

	private static final int START = 5376; // 21:00
	private static final int STOP = 5888; // 23:00
	private static final int CYCLES = 16; // >= number of LOW read tasks
	private static final int RETRY_CYCLES = 65; // > the retry cooldown

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

	private static ComponentTest start(SrneBatteryInverterImpl sut, DummyModbusBridge bridge, TimeLeapClock clock,
			MyConfig config) throws Exception {
		return new ComponentTest(sut) //
				.addReference("setModbus", bridge) //
				.addReference("componentManager", new DummyComponentManager(clock)) //
				.activate(config);
	}

	private static void assertSuppression(SrneBatteryInverterImpl sut, Boolean suppressed,
			DischargeSuppressionReason reason) {
		assertEquals(suppressed, sut.getDischargeSuppressedChannelForTest().value().get());
		assertEquals(reason, sut.getDischargeSuppressionReasonChannelForTest().value().asEnum());
	}

	private static SafeWriteHandler.State safeWriteState(SrneBatteryInverterImpl sut) {
		Channel<SafeWriteHandler.State> channel = sut.channel(SrneBatteryInverter.ChannelId.SAFE_WRITE_STATE);
		return channel.value().asEnum();
	}

	// Plays the device for one queued E033 write: execute callback, then read-back.
	private static void deviceWritesEnable(SrneBatteryInverterImpl sut, DummyModbusBridge bridge, ComponentTest test,
			int value) throws Exception {
		sut.dischargeWindowForTest().onEnableExecute(ExecuteState.OK);
		bridge.withRegisters(0xE033, value);
		test.next(new TestCase(), CYCLES);
	}

	@Test
	public void testSkipAtStartWhenSocLow() throws Exception {
		var sut = new SrneBatteryInverterImpl();
		var bridge = bridge(60, MachineState.RUNNING_MAINS_BYPASS);
		var test = start(sut, bridge, clockAt("2026-01-10T18:05:00Z"), config(75, 45).build()) //
				.next(new TestCase(), CYCLES);
		// Decided, queued, but the device is not yet disabled.
		assertSuppression(sut, false, DischargeSuppressionReason.LOW_START);
		assertEquals(0, sut.dischargeQueuedEnableForTest());
		deviceWritesEnable(sut, bridge, test, 0);
		assertEquals(State.DONE, sut.dischargeWindowStateForTest());
		assertSuppression(sut, true, DischargeSuppressionReason.LOW_START);
	}

	@Test
	public void testNoSkipWhenSocAtThreshold() throws Exception {
		var sut = new SrneBatteryInverterImpl();
		start(sut, bridge(75, MachineState.RUNNING_MAINS_BYPASS), clockAt("2026-01-10T18:05:00Z"),
				config(75, 45).build()) //
				.next(new TestCase(), CYCLES);
		assertSuppression(sut, false, DischargeSuppressionReason.NONE);
		assertNull(sut.dischargeQueuedEnableForTest());
		assertEquals(State.DONE, sut.dischargeWindowStateForTest());
	}

	@Test
	public void testFloorMidWindowThenRestoreAfterWindow() throws Exception {
		var sut = new SrneBatteryInverterImpl();
		var clock = clockAt("2026-01-10T18:05:00Z"); // 21:05 local
		var bridge = bridge(80, MachineState.RUNNING_MAINS_BYPASS);
		var test = start(sut, bridge, clock, config(75, 45).build()) //
				.next(new TestCase(), CYCLES);
		assertSuppression(sut, false, DischargeSuppressionReason.NONE);
		assertNull(sut.dischargeQueuedEnableForTest());

		// Mid-window the SoC falls to the floor: disarm is queued.
		test.next(new TestCase().timeleap(clock, 1, ChronoUnit.HOURS));
		bridge.withRegisters(0x0100, 45);
		test.next(new TestCase(), CYCLES);
		assertSuppression(sut, false, DischargeSuppressionReason.FLOOR_REACHED);
		assertEquals(0, sut.dischargeQueuedEnableForTest());

		deviceWritesEnable(sut, bridge, test, 0);
		assertEquals(State.DONE, sut.dischargeWindowStateForTest());
		assertSuppression(sut, true, DischargeSuppressionReason.FLOOR_REACHED);

		// Window over (00:05 next day local): suppression clears, enable restored to 1.
		test.next(new TestCase().timeleap(clock, 2, ChronoUnit.HOURS));
		test.next(new TestCase(), CYCLES);
		assertSuppression(sut, true, DischargeSuppressionReason.NONE); // device still 0 until restored
		assertEquals(1, sut.dischargeQueuedEnableForTest());
		deviceWritesEnable(sut, bridge, test, 1);
		assertEquals(State.DONE, sut.dischargeWindowStateForTest());
		assertSuppression(sut, false, DischargeSuppressionReason.NONE);
	}

	@Test
	public void testRestartAtWindowStartReachesSameDecision() throws Exception {
		for (var soc : new int[] { 40, 60 }) {
			var reason = soc == 40 ? DischargeSuppressionReason.FLOOR_REACHED : DischargeSuppressionReason.LOW_START;
			// Two fresh instances (a restart) see the same state and decide the same.
			for (var i = 0; i < 2; i++) {
				var sut = new SrneBatteryInverterImpl();
				start(sut, bridge(soc, MachineState.RUNNING_MAINS_BYPASS), clockAt("2026-01-10T18:00:00Z"),
						config(75, 45).build()) // 21:00 local
						.next(new TestCase(), CYCLES);
				assertSuppression(sut, false, reason);
				assertEquals(0, sut.dischargeQueuedEnableForTest());
			}
		}
	}

	@Test
	public void testRestartMidWindowAfterStartRuleExpiryDoesNotLatchLowStart() throws Exception {
		// 21:30 local, SoC below the start threshold after normal discharge, above floor.
		var sut = new SrneBatteryInverterImpl();
		start(sut, bridge(60, MachineState.RUNNING_MAINS_BYPASS), clockAt("2026-01-10T18:30:00Z"),
				config(75, 45).build()) //
				.next(new TestCase(), CYCLES);
		assertSuppression(sut, false, DischargeSuppressionReason.NONE);
		assertNull(sut.dischargeQueuedEnableForTest());
		assertEquals(State.DONE, sut.dischargeWindowStateForTest());

		// The floor rule still applies after a restart.
		var floored = new SrneBatteryInverterImpl();
		start(floored, bridge(40, MachineState.RUNNING_MAINS_BYPASS), clockAt("2026-01-10T18:30:00Z"),
				config(75, 45).build()) //
				.next(new TestCase(), CYCLES);
		assertSuppression(floored, false, DischargeSuppressionReason.FLOOR_REACHED);
		assertEquals(0, floored.dischargeQueuedEnableForTest());
	}

	@Test
	public void testOutageOnlyLetsTheDisarmThroughUntilStateTwoReturns() throws Exception {
		var sut = new SrneBatteryInverterImpl();
		final var bridge = bridge(60, MachineState.INVERTER_POWERED);
		final var test = start(sut, bridge, clockAt("2026-01-10T18:05:00Z"), config(75, 45).build()) //
				.next(new TestCase(), CYCLES);
		// The disarm is the only write that bypasses the verified-state gate.
		assertSuppression(sut, false, DischargeSuppressionReason.LOW_START);
		assertEquals(0, sut.dischargeQueuedEnableForTest());
		assertEquals(State.DISABLE_QUEUED, sut.dischargeWindowStateForTest());
		deviceWritesEnable(sut, bridge, test, 0);
		assertEquals(State.DISABLE_VERIFIED, sut.dischargeWindowStateForTest());

		// Verified again: the sequence completes normally.
		bridge.withRegister(0x0210, MachineState.RUNNING_MAINS_BYPASS.getValue());
		test.next(new TestCase(), CYCLES);
		assertEquals(State.DONE, sut.dischargeWindowStateForTest());
	}

	@Test
	public void testUnverifiedStateNeverArmsOrWritesTheWindow() throws Exception {
		var sut = new SrneBatteryInverterImpl();
		final var bridge = new DummyModbusBridge("modbus0") //
				.withRegisters(0xE02C, 0, 0, 0) //
				.withRegisters(0xE033, 0, 0, 0, 0) //
				.withRegisters(0x0100, 80) //
				.withRegisters(0x0101, 524, 0) //
				.withRegister(0x0210, MachineState.INVERTER_POWERED.getValue());
		start(sut, bridge, clockAt("2026-01-10T18:05:00Z"), config(75, 45).build()) //
				.next(new TestCase(), CYCLES);
		assertNull(sut.dischargeQueuedEnableForTest());
		assertEquals(State.IDLE, sut.dischargeWindowStateForTest());
	}

	@Test
	public void testFloorDisarmGoesThroughForMachineStatesFiveAndSeven() throws Exception {
		for (var state : new MachineState[] { MachineState.INVERTER_POWERED, MachineState.MAINS_TO_INVERTER }) {
			var sut = new SrneBatteryInverterImpl();
			start(sut, bridge(40, state), clockAt("2026-01-10T18:05:00Z"), config(-1, 45).build()) //
					.next(new TestCase(), CYCLES);
			assertSuppression(sut, false, DischargeSuppressionReason.FLOOR_REACHED);
			assertEquals(0, sut.dischargeQueuedEnableForTest(), state.name());
			assertEquals(State.DISABLE_QUEUED, sut.dischargeWindowStateForTest(), state.name());
		}
	}

	@Test
	public void testRestartDisarmedAtOrBelowFloorNeverQueuesAnArm() throws Exception {
		for (var soc : new int[] { 45, 30 }) {
			var sut = new SrneBatteryInverterImpl();
			// Restart inside the window: the device already reads enable=0.
			var bridge = bridge(soc, MachineState.RUNNING_MAINS_BYPASS).withRegisters(0xE033, 0);
			var test = start(sut, bridge, clockAt("2026-01-10T18:05:00Z"), config(75, 45).build());
			for (var i = 0; i < 4; i++) {
				test.next(new TestCase(), CYCLES);
				assertNull(sut.dischargeQueuedEnableForTest(), "soc " + soc);
			}
			assertEquals(State.DONE, sut.dischargeWindowStateForTest());
			assertSuppression(sut, true, DischargeSuppressionReason.FLOOR_REACHED);
		}
	}

	@Test
	public void testUnknownSocAtFirstReconcileDoesNotArmUntilSocIsKnown() throws Exception {
		var sut = new SrneBatteryInverterImpl();
		var bridge = new DummyModbusBridge("modbus0") //
				.withRegisters(0xE02C, 0, START, STOP) //
				.withRegisters(0xE033, 0, 0, 0, 0) //
				.withRegisters(0x0101, 524, 0) //
				.withRegister(0x0210, MachineState.RUNNING_MAINS_BYPASS.getValue());
		final var test = start(sut, bridge, clockAt("2026-01-10T18:05:00Z"), config(75, 45).build()) //
				.next(new TestCase(), CYCLES);
		assertNull(sut.getBatterySocForTest());
		assertNull(sut.dischargeQueuedEnableForTest());
		assertEquals(State.DONE, sut.dischargeWindowStateForTest());

		// A usable, healthy SoC lets the configured arm through.
		bridge.withRegisters(0x0100, 80);
		test.next(new TestCase(), CYCLES);
		assertEquals(1, sut.dischargeQueuedEnableForTest());
	}

	@Test
	public void testUnreadableSocDoesNotSkipTheNightForever() throws Exception {
		var sut = new SrneBatteryInverterImpl();
		var clock = clockAt("2026-01-10T18:05:00Z");
		var bridge = new DummyModbusBridge("modbus0") //
				.withRegisters(0xE02C, 0, START, STOP) //
				.withRegisters(0xE033, 0, 0, 0, 0) //
				.withRegisters(0x0101, 524, 0) //
				.withRegister(0x0210, MachineState.RUNNING_MAINS_BYPASS.getValue());
		final var test = start(sut, bridge, clock, config(75, 45).build()) //
				.next(new TestCase(), CYCLES);
		assertNull(sut.dischargeQueuedEnableForTest());
		// Still unreadable inside the cap: held back.
		test.next(new TestCase().timeleap(clock, 4, ChronoUnit.MINUTES));
		test.next(new TestCase(), CYCLES);
		test.next(new TestCase().timeleap(clock, 4, ChronoUnit.MINUTES));
		test.next(new TestCase(), CYCLES);
		assertNull(sut.dischargeQueuedEnableForTest());
		// Past the cap without any SoC: fail open and arm as configured.
		test.next(new TestCase().timeleap(clock, 4, ChronoUnit.MINUTES));
		test.next(new TestCase(), CYCLES);
		assertEquals(1, sut.dischargeQueuedEnableForTest());
	}

	@Test
	public void testFloorNotBelowStartKeepsTheFloorAndDropsTheStartRule() throws Exception {
		for (var pair : new int[][] { { 50, 50 }, { 45, 75 } }) {
			var atFloor = new SrneBatteryInverterImpl();
			start(atFloor, bridge(pair[1], MachineState.RUNNING_MAINS_BYPASS), clockAt("2026-01-10T18:05:00Z"),
					config(pair[0], pair[1]).build()) //
					.next(new TestCase(), CYCLES);
			assertSuppression(atFloor, false, DischargeSuppressionReason.FLOOR_REACHED);
			assertEquals(0, atFloor.dischargeQueuedEnableForTest());

			// Above the floor the (dropped) start rule must not suppress.
			var above = new SrneBatteryInverterImpl();
			start(above, bridge(pair[1] + 1, MachineState.RUNNING_MAINS_BYPASS), clockAt("2026-01-10T18:05:00Z"),
					config(pair[0], pair[1]).build()) //
					.next(new TestCase(), CYCLES);
			assertSuppression(above, false, DischargeSuppressionReason.NONE);
			assertNull(above.dischargeQueuedEnableForTest());
		}
	}

	@Test
	public void testDriftBackToArmedAfterFloorLatchIsCorrectedRepeatedly() throws Exception {
		var sut = new SrneBatteryInverterImpl();
		var bridge = bridge(40, MachineState.RUNNING_MAINS_BYPASS);
		var test = start(sut, bridge, clockAt("2026-01-10T18:05:00Z"), config(-1, 45).build()) //
				.next(new TestCase(), CYCLES);
		deviceWritesEnable(sut, bridge, test, 0);
		assertEquals(State.DONE, sut.dischargeWindowStateForTest());

		// The register drifts back to 1: corrected, and again after the next drift.
		for (var round = 0; round < 3; round++) {
			bridge.withRegisters(0xE033, 1);
			test.next(new TestCase(), RETRY_CYCLES);
			assertEquals(State.DISABLE_QUEUED, sut.dischargeWindowStateForTest(), "round " + round);
			assertEquals(0, sut.dischargeQueuedEnableForTest());
			deviceWritesEnable(sut, bridge, test, 0);
			assertEquals(State.DONE, sut.dischargeWindowStateForTest(), "round " + round);
		}
		// Corrections while suppressed never consume the single unsuppressed correction.
		assertFalse(sut.driftCorrectedUnsuppressedForTest());
	}

	@Test
	public void testQueuedSuppressThatIsNeverExecutedFailsAndIsRetried() throws Exception {
		var sut = new SrneBatteryInverterImpl();
		var bridge = bridge(40, MachineState.RUNNING_MAINS_BYPASS);
		var test = start(sut, bridge, clockAt("2026-01-10T18:05:00Z"), config(-1, 45).build()) //
				.next(new TestCase(), CYCLES);
		assertEquals(State.DISABLE_QUEUED, sut.dischargeWindowStateForTest());
		// The bridge never executes the write: it must fail and then be queued again.
		var sawFailed = false;
		for (var i = 0; i < 60 && !sawFailed; i++) {
			test.next(new TestCase());
			sawFailed = sut.dischargeWindowStateForTest() == State.FAILED;
		}
		assertTrue(sawFailed);
		test.next(new TestCase(), RETRY_CYCLES);
		assertEquals(0, sut.dischargeQueuedEnableForTest());
		assertTrue(sut.dischargeWindowStateForTest() != State.IDLE);
	}

	// Task list of the component without any rule, in registration order. The rules
	// only ever add the 0x0100 SoC read; with both rules off it must be absent.
	private static final List<String> BASELINE_TASKS = List.of(
			"FC3ReadRegistersTask@57359", "FC3ReadRegistersTask@57355", "FC3ReadRegistersTask@57360",
			"FC3ReadRegistersTask@57372", "FC3ReadRegistersTask@57860", "FC3ReadRegistersTask@57866",
			"FC3ReadRegistersTask@57877", "FC3ReadRegistersTask@57382", "FC3ReadRegistersTask@57384",
			"FC3ReadRegistersTask@57391", "FC3ReadRegistersTask@57388", "FC3ReadRegistersTask@57395",
			"FC3ReadRegistersTask@257", "FC3ReadRegistersTask@528", "FC16WriteRegistersTask@57359",
			"FC16WriteRegistersTask@57372", "FC16WriteRegistersTask@57373", "FC16WriteRegistersTask@57374",
			"FC16WriteRegistersTask@57375", "FC16WriteRegistersTask@57376", "FC16WriteRegistersTask@57861",
			"FC16WriteRegistersTask@57866", "FC16WriteRegistersTask@57860", "FC16WriteRegistersTask@57877",
			"FC16WriteRegistersTask@57382", "FC16WriteRegistersTask@57383", "FC16WriteRegistersTask@57388",
			"FC16WriteRegistersTask@57389", "FC16WriteRegistersTask@57390", "FC16WriteRegistersTask@57395");

	private static List<String> taskList(SrneBatteryInverterImpl sut) throws Exception {
		return sut.defineModbusProtocol().getTaskManager().getTasks().stream()
				.map(t -> t.getClass().getSimpleName() + "@" + t.getStartAddress()).toList();
	}

	@Test
	public void testPropertiesOffKeepsBehaviourAndTrafficUnchanged() throws Exception {
		var sut = new SrneBatteryInverterImpl();
		start(sut, bridge(10, MachineState.RUNNING_MAINS_BYPASS), clockAt("2026-01-10T18:05:00Z"),
				config(-1, -1).build()) //
				.next(new TestCase(), CYCLES);
		assertEquals(BASELINE_TASKS, taskList(sut));
		assertNull(sut.getDischargeSuppressedChannelForTest().value().get());
		assertEquals(DischargeSuppressionReason.UNDEFINED,
				sut.getDischargeSuppressionReasonChannelForTest().value().asEnum());
		assertNull(sut.dischargeQueuedEnableForTest());
		assertEquals(State.DONE, sut.dischargeWindowStateForTest());
		// Nothing is pending on any write element (identical write traffic: none).
		assertNull(sut.dischargeWindowForTest().enableWriteElement().getNextWriteValueAndReset());
		assertNull(sut.dischargeWindowForTest().startWriteElement().getNextWriteValueAndReset());
		assertNull(sut.dischargeWindowForTest().stopWriteElement().getNextWriteValueAndReset());
	}

	@Test
	public void testPropertiesOffStillManagesConfiguredEnableExactlyAsBefore() throws Exception {
		// Device disabled while the config says enabled: baseline queues enable=1, even
		// at a SoC and time a rule would have suppressed.
		var sut = new SrneBatteryInverterImpl();
		var bridge = bridge(10, MachineState.RUNNING_MAINS_BYPASS).withRegisters(0xE033, 0);
		start(sut, bridge, clockAt("2026-01-10T18:05:00Z"), config(-1, -1).build()) //
				.next(new TestCase(), CYCLES);
		assertEquals(BASELINE_TASKS, taskList(sut));
		assertEquals(1, sut.dischargeQueuedEnableForTest());
		assertEquals(State.ENABLE_QUEUED, sut.dischargeWindowStateForTest());
	}

	@Test
	public void testRulesOffDischargeWindowWriteStaysQueuedWhileTheLinkIsSlow() throws Exception {
		var sut = new SrneBatteryInverterImpl();
		var bridge = bridge(10, MachineState.RUNNING_MAINS_BYPASS).withRegisters(0xE033, 0);
		var test = start(sut, bridge, clockAt("2026-01-10T18:05:00Z"), config(-1, -1).build()) //
				.next(new TestCase(), CYCLES);
		assertEquals(State.ENABLE_QUEUED, sut.dischargeWindowStateForTest());
		// Never executed for far longer than the queued timeout: no failure, no withdrawal.
		test.next(new TestCase(), 3 * RETRY_CYCLES);
		assertEquals(State.ENABLE_QUEUED, sut.dischargeWindowStateForTest());
	}

	@Test
	public void testChargeWindowWriteStaysQueuedWhileTheLinkIsSlow() throws Exception {
		var sut = new SrneBatteryInverterImpl();
		var bridge = bridge(80, MachineState.RUNNING_MAINS_BYPASS) //
				.withRegisters(0xE026, 0, 0);
		var config = config(75, 45).setChargeWindow1Start(START).setChargeWindow1Stop(STOP)
				.setChargeScheduleEnable(0).build();
		var test = start(sut, bridge, clockAt("2026-01-10T18:05:00Z"), config) //
				.next(new TestCase(), CYCLES);
		assertEquals(ScheduleWindow.State.WINDOW_QUEUED, sut.chargeWindowForTest().getState());
		test.next(new TestCase(), 3 * RETRY_CYCLES);
		assertEquals(ScheduleWindow.State.WINDOW_QUEUED, sut.chargeWindowForTest().getState());
	}

	@Test
	public void testRulesOnlyAddTheSocRead() throws Exception {
		var sut = new SrneBatteryInverterImpl();
		start(sut, bridge(80, MachineState.RUNNING_MAINS_BYPASS), clockAt("2026-01-10T18:05:00Z"),
				config(75, -1).build());
		var tasks = new java.util.ArrayList<>(taskList(sut));
		assertTrue(tasks.remove("FC3ReadRegistersTask@256"));
		assertEquals(BASELINE_TASKS, tasks);
	}

	@Test
	public void testFailedRestoreIsRetriedUntilItSucceeds() throws Exception {
		var sut = new SrneBatteryInverterImpl();
		var clock = clockAt("2026-01-10T18:05:00Z");
		var bridge = bridge(80, MachineState.RUNNING_MAINS_BYPASS);
		var test = start(sut, bridge, clock, config(75, 45).build()) //
				.next(new TestCase(), CYCLES);
		test.next(new TestCase().timeleap(clock, 1, ChronoUnit.HOURS));
		bridge.withRegisters(0x0100, 45);
		test.next(new TestCase(), CYCLES);
		deviceWritesEnable(sut, bridge, test, 0);
		assertSuppression(sut, true, DischargeSuppressionReason.FLOOR_REACHED);

		// Window over: the restore is queued, but the write fails.
		test.next(new TestCase().timeleap(clock, 2, ChronoUnit.HOURS));
		test.next(new TestCase(), CYCLES);
		assertEquals(1, sut.dischargeQueuedEnableForTest());
		sut.dischargeWindowForTest().onEnableExecute(new ExecuteState.Error(new RuntimeException("bus error")));
		test.next(new TestCase(), 2);
		assertEquals(State.FAILED, sut.dischargeWindowStateForTest());
		assertEquals(SafeWriteHandler.State.FAILED, safeWriteState(sut));
		// The channel reports the device (still disabled), not the decision (allow).
		assertSuppression(sut, true, DischargeSuppressionReason.NONE);

		// After the cooldown the restore is attempted again through the verified path.
		test.next(new TestCase(), RETRY_CYCLES);
		assertEquals(State.ENABLE_QUEUED, sut.dischargeWindowStateForTest());
		assertEquals(1, sut.dischargeQueuedEnableForTest());
		deviceWritesEnable(sut, bridge, test, 1);
		assertEquals(State.DONE, sut.dischargeWindowStateForTest());
		assertSuppression(sut, false, DischargeSuppressionReason.NONE);
		assertFalse(safeWriteState(sut) == SafeWriteHandler.State.FAILED);
	}

	@Test
	public void testFailedSuppressKeepsRetryingPastTheFastCapUntilItSucceeds() throws Exception {
		var sut = new SrneBatteryInverterImpl();
		var clock = clockAt("2026-01-10T18:05:00Z");
		var bridge = bridge(80, MachineState.RUNNING_MAINS_BYPASS);
		var test = start(sut, bridge, clock, config(75, 45).build()) //
				.next(new TestCase(), CYCLES);
		test.next(new TestCase().timeleap(clock, 1, ChronoUnit.HOURS));
		bridge.withRegisters(0x0100, 45);
		test.next(new TestCase(), CYCLES);
		assertEquals(State.DISABLE_QUEUED, sut.dischargeWindowStateForTest());

		// Fast cadence: each failed suppress is retried within a short cooldown.
		for (var i = 0; i < 3; i++) {
			sut.dischargeWindowForTest().onEnableExecute(new ExecuteState.Error(new RuntimeException("bus error")));
			test.next(new TestCase(), 2);
			assertEquals(State.FAILED, sut.dischargeWindowStateForTest());
			test.next(new TestCase(), CYCLES);
			assertEquals(State.DISABLE_QUEUED, sut.dischargeWindowStateForTest());
		}
		// Past the fast cap the retry continues at the slow cadence, never stops.
		for (var i = 0; i < 3; i++) {
			sut.dischargeWindowForTest().onEnableExecute(new ExecuteState.Error(new RuntimeException("bus error")));
			test.next(new TestCase(), CYCLES);
			assertEquals(State.FAILED, sut.dischargeWindowStateForTest());
			test.next(new TestCase(), RETRY_CYCLES);
			assertEquals(State.DISABLE_QUEUED, sut.dischargeWindowStateForTest());
		}
		deviceWritesEnable(sut, bridge, test, 0);
		assertEquals(State.DONE, sut.dischargeWindowStateForTest());
		assertSuppression(sut, true, DischargeSuppressionReason.FLOOR_REACHED);
	}

	@Test
	public void testTargetFlipMidWriteWaitsForTheSequenceThenApplies() throws Exception {
		var sut = new SrneBatteryInverterImpl();
		var clock = clockAt("2026-01-10T18:05:00Z");
		var bridge = bridge(80, MachineState.RUNNING_MAINS_BYPASS);
		var test = start(sut, bridge, clock, config(75, 45).build()) //
				.next(new TestCase(), CYCLES);
		test.next(new TestCase().timeleap(clock, 1, ChronoUnit.HOURS));
		bridge.withRegisters(0x0100, 45);
		test.next(new TestCase(), CYCLES);
		assertEquals(State.DISABLE_QUEUED, sut.dischargeWindowStateForTest());

		// The window ends while the disarm is still in flight: reopen is refused, the
		// running sequence is not disturbed.
		test.next(new TestCase().timeleap(clock, 2, ChronoUnit.HOURS));
		test.next(new TestCase(), 8); // stays below the queued-write timeout
		assertSuppression(sut, false, DischargeSuppressionReason.NONE);
		assertEquals(State.DISABLE_QUEUED, sut.dischargeWindowStateForTest());
		assertEquals(0, sut.dischargeQueuedEnableForTest());

		// Once the sequence settles the flip is applied through the verified path.
		deviceWritesEnable(sut, bridge, test, 0);
		test.next(new TestCase(), CYCLES);
		assertEquals(State.ENABLE_QUEUED, sut.dischargeWindowStateForTest());
		assertEquals(1, sut.dischargeQueuedEnableForTest());
	}

	// Armed device whose window differs from the config, so an arm sequence
	// (disarm, window write, re-arm) is needed.
	private static DummyModbusBridge staleWindowBridge(int soc) {
		return new DummyModbusBridge("modbus0") //
				.withRegisters(0xE02C, 0, START - 256, STOP) //
				.withRegisters(0xE033, 1, 0, 0, 0) //
				.withRegisters(0x0100, soc) //
				.withRegisters(0x0101, 524, 0) //
				.withRegister(0x0210, MachineState.RUNNING_MAINS_BYPASS.getValue());
	}

	private static void deviceWritesWindow(SrneBatteryInverterImpl sut, DummyModbusBridge bridge, ComponentTest test)
			throws Exception {
		sut.dischargeWindowForTest().onStartExecute(ExecuteState.OK);
		sut.dischargeWindowForTest().onStopExecute(ExecuteState.OK);
		bridge.withRegisters(0xE02D, START, STOP);
		test.next(new TestCase(), CYCLES);
	}

	@Test
	public void testLatchWhileWindowIsBeingWrittenNeverQueuesTheArm() throws Exception {
		var sut = new SrneBatteryInverterImpl();
		var bridge = staleWindowBridge(80);
		var test = start(sut, bridge, clockAt("2026-01-10T18:05:00Z"), config(75, 45).build()) //
				.next(new TestCase(), CYCLES);
		assertEquals(State.DISABLE_QUEUED, sut.dischargeWindowStateForTest());
		deviceWritesEnable(sut, bridge, test, 0);
		assertEquals(State.WINDOW_QUEUED, sut.dischargeWindowStateForTest());

		// The floor is reached while the window is being written.
		bridge.withRegisters(0x0100, 40);
		test.next(new TestCase(), CYCLES);
		assertSuppression(sut, true, DischargeSuppressionReason.FLOOR_REACHED);
		deviceWritesWindow(sut, bridge, test);
		test.next(new TestCase(), CYCLES);

		assertEquals(State.DONE, sut.dischargeWindowStateForTest());
		assertEquals(0, sut.dischargeQueuedEnableForTest());
		assertEquals(0, sut.dischargeWindowForTest().desiredEnable());
		// The dummy bridge never consumes the earlier disarm; what matters is that no 1 is pending.
		var pending = sut.dischargeWindowForTest().enableWriteElement().getNextWriteValueAndReset();
		assertTrue(pending == null || pending[0].getValue() == 0);
	}

	@Test
	public void testLatchWhileTheArmIsQueuedWithdrawsIt() throws Exception {
		var sut = new SrneBatteryInverterImpl();
		var bridge = staleWindowBridge(80);
		var test = start(sut, bridge, clockAt("2026-01-10T18:05:00Z"), config(75, 45).build()) //
				.next(new TestCase(), CYCLES);
		deviceWritesEnable(sut, bridge, test, 0);
		deviceWritesWindow(sut, bridge, test);
		assertEquals(State.ENABLE_QUEUED, sut.dischargeWindowStateForTest());
		assertEquals(1, sut.dischargeQueuedEnableForTest());

		bridge.withRegisters(0x0100, 40);
		test.next(new TestCase(), CYCLES);

		assertEquals(State.DONE, sut.dischargeWindowStateForTest());
		assertEquals(0, sut.dischargeWindowForTest().desiredEnable());
		assertNull(sut.dischargeWindowForTest().enableWriteElement().getNextWriteValueAndReset());
		assertSuppression(sut, true, DischargeSuppressionReason.FLOOR_REACHED);
	}

	@Test
	public void testLatchAfterTheArmWasWrittenDisarmsAgain() throws Exception {
		var sut = new SrneBatteryInverterImpl();
		var bridge = staleWindowBridge(80);
		var test = start(sut, bridge, clockAt("2026-01-10T18:05:00Z"), config(75, 45).build()) //
				.next(new TestCase(), CYCLES);
		deviceWritesEnable(sut, bridge, test, 0);
		deviceWritesWindow(sut, bridge, test);
		assertEquals(State.ENABLE_QUEUED, sut.dischargeWindowStateForTest());

		// The arm went out just before the floor was reached.
		sut.dischargeWindowForTest().onEnableExecute(ExecuteState.OK);
		bridge.withRegisters(0xE033, 1);
		bridge.withRegisters(0x0100, 40);
		test.next(new TestCase(), CYCLES);

		assertEquals(State.DISABLE_QUEUED, sut.dischargeWindowStateForTest());
		assertEquals(0, sut.dischargeQueuedEnableForTest());
		deviceWritesEnable(sut, bridge, test, 0);
		assertEquals(State.DONE, sut.dischargeWindowStateForTest());
		assertSuppression(sut, true, DischargeSuppressionReason.FLOOR_REACHED);
	}

	@Test
	public void testArmedAfterFailOpenIsDisarmedOnceTheFloorLatches() throws Exception {
		var sut = new SrneBatteryInverterImpl();
		// Armed device, SoC unreadable at first; the hold cap fails open.
		var bridge = new DummyModbusBridge("modbus0") //
				.withRegisters(0xE02C, 0, START, STOP) //
				.withRegisters(0xE033, 1, 0, 0, 0) //
				.withRegisters(0x0101, 524, 0) //
				.withRegister(0x0210, MachineState.RUNNING_MAINS_BYPASS.getValue());
		var clock = clockAt("2026-01-10T18:05:00Z");
		var test = start(sut, bridge, clock, config(75, 45).build()) //
				.next(new TestCase(), CYCLES);
		test.next(new TestCase().timeleap(clock, 11, ChronoUnit.MINUTES));
		test.next(new TestCase(), CYCLES);
		assertEquals(State.DONE, sut.dischargeWindowStateForTest());
		assertNull(sut.dischargeQueuedEnableForTest());

		// The SoC becomes readable and is below the floor: armed device is disarmed.
		bridge.withRegisters(0x0100, 40);
		test.next(new TestCase(), CYCLES);
		assertEquals(0, sut.dischargeQueuedEnableForTest());
		deviceWritesEnable(sut, bridge, test, 0);
		assertEquals(State.DONE, sut.dischargeWindowStateForTest());
		assertSuppression(sut, true, DischargeSuppressionReason.FLOOR_REACHED);
	}

	@Test
	public void testUnknownSocChangesNothing() throws Exception {
		// 0x0100 is not present on the dummy device: the read yields no value.
		var sut = new SrneBatteryInverterImpl();
		var bridge = new DummyModbusBridge("modbus0") //
				.withRegisters(0xE02C, 0, START, STOP) //
				.withRegisters(0xE033, 1, 0, 0, 0) //
				.withRegisters(0x0101, 524, 0) //
				.withRegister(0x0210, MachineState.RUNNING_MAINS_BYPASS.getValue());
		start(sut, bridge, clockAt("2026-01-10T18:05:00Z"), config(75, 45).build()) //
				.next(new TestCase(), CYCLES);
		assertNull(sut.getBatterySocForTest());
		assertSuppression(sut, false, DischargeSuppressionReason.NONE);
		assertNull(sut.dischargeQueuedEnableForTest());
	}

	@Test
	public void testSocOutOfRangeIsIgnored() throws Exception {
		var sut = new SrneBatteryInverterImpl();
		start(sut, bridge(0xFFFF, MachineState.RUNNING_MAINS_BYPASS), clockAt("2026-01-10T18:05:00Z"),
				config(75, 45).build()) //
				.next(new TestCase(), CYCLES);
		assertEquals(65535, sut.getBatterySocForTest());
		assertSuppression(sut, false, DischargeSuppressionReason.NONE);
		assertNull(sut.dischargeQueuedEnableForTest());
		assertEquals(State.DONE, sut.dischargeWindowStateForTest());
	}

	@Test
	public void testSocZeroNeedsConsecutiveReadingsToLatchStart() throws Exception {
		// One failed-read 0, then real readings: no suppression, start check passes.
		var sut = new SrneBatteryInverterImpl();
		var bridge = bridge(0, MachineState.RUNNING_MAINS_BYPASS);
		var test = start(sut, bridge, clockAt("2026-01-10T18:05:00Z"), config(75, -1).build()) //
				.next(new TestCase());
		assertEquals(DischargeSuppressionReason.NONE,
				sut.getDischargeSuppressionReasonChannelForTest().getNextValue().asEnum());
		bridge.withRegisters(0x0100, 80);
		test.next(new TestCase(), CYCLES);
		assertSuppression(sut, false, DischargeSuppressionReason.NONE);
		bridge.withRegisters(0x0100, 0);
		test.next(new TestCase(), CYCLES);
		assertSuppression(sut, false, DischargeSuppressionReason.NONE);
		assertNull(sut.dischargeQueuedEnableForTest());

		// A sustained 0 from the start is a real reading and does latch.
		var sustained = new SrneBatteryInverterImpl();
		start(sustained, bridge(0, MachineState.RUNNING_MAINS_BYPASS), clockAt("2026-01-10T18:05:00Z"),
				config(75, -1).build()) //
				.next(new TestCase(), CYCLES);
		assertSuppression(sustained, false, DischargeSuppressionReason.LOW_START);
		assertEquals(0, sustained.dischargeQueuedEnableForTest());
	}

	@Test
	public void testFloorOnlyConfigThroughComponent() throws Exception {
		var high = new SrneBatteryInverterImpl();
		start(high, bridge(60, MachineState.RUNNING_MAINS_BYPASS), clockAt("2026-01-10T18:05:00Z"),
				config(-1, 45).build()) //
				.next(new TestCase(), CYCLES);
		assertSuppression(high, false, DischargeSuppressionReason.NONE);
		assertNull(high.dischargeQueuedEnableForTest());

		var low = new SrneBatteryInverterImpl();
		var bridge = bridge(45, MachineState.RUNNING_MAINS_BYPASS);
		var test = start(low, bridge, clockAt("2026-01-10T18:05:00Z"), config(-1, 45).build()) //
				.next(new TestCase(), CYCLES);
		assertSuppression(low, false, DischargeSuppressionReason.FLOOR_REACHED);
		assertEquals(0, low.dischargeQueuedEnableForTest());
		deviceWritesEnable(low, bridge, test, 0);
		assertSuppression(low, true, DischargeSuppressionReason.FLOOR_REACHED);
	}

	@Test
	public void testClockStepHoldsTheDecisionForOneCycle() throws Exception {
		var sut = new SrneBatteryInverterImpl();
		var clock = clockAt("2026-01-10T18:05:00Z");
		var bridge = bridge(45, MachineState.RUNNING_MAINS_BYPASS);
		var test = start(sut, bridge, clock, config(-1, 45).build()) //
				.next(new TestCase(), CYCLES);
		assertSuppression(sut, false, DischargeSuppressionReason.FLOOR_REACHED);

		// The clock steps past the window end: that cycle holds, the next one applies.
		test.next(new TestCase().timeleap(clock, 3, ChronoUnit.HOURS));
		assertEquals(DischargeSuppressionReason.FLOOR_REACHED,
				sut.getDischargeSuppressionReasonChannelForTest().getNextValue().asEnum());
		test.next(new TestCase());
		assertEquals(DischargeSuppressionReason.NONE,
				sut.getDischargeSuppressionReasonChannelForTest().getNextValue().asEnum());
	}

	@Test
	public void testInvalidTimeZoneWithRulesOffDoesNotFailActivation() throws Exception {
		var sut = new SrneBatteryInverterImpl();
		start(sut, bridge(80, MachineState.RUNNING_MAINS_BYPASS), clockAt("2026-01-10T18:05:00Z"),
				config(-1, -1).setScheduleTimeZone("Not/AZone").build()) //
				.next(new TestCase(), CYCLES);
		assertEquals(State.DONE, sut.dischargeWindowStateForTest());
		assertNull(sut.getDischargeSuppressedChannelForTest().value().get());
	}

	@Test
	public void testInvalidTimeZoneWithRulesOnFallsBackToKampala() throws Exception {
		var sut = new SrneBatteryInverterImpl();
		// 18:05Z is inside the window only in the fallback zone (21:05 Kampala).
		start(sut, bridge(60, MachineState.RUNNING_MAINS_BYPASS), clockAt("2026-01-10T18:05:00Z"),
				config(75, 45).setScheduleTimeZone("Not/AZone").build()) //
				.next(new TestCase(), CYCLES);
		assertSuppression(sut, false, DischargeSuppressionReason.LOW_START);
		assertEquals(0, sut.dischargeQueuedEnableForTest());
	}

	@Test
	public void testControlDisabledPublishesAndWritesNothing() throws Exception {
		var sut = new SrneBatteryInverterImpl();
		start(sut, bridge(60, MachineState.RUNNING_MAINS_BYPASS), clockAt("2026-01-10T18:05:00Z"),
				config(75, 45).setControlEnabled(false).build()) //
				.next(new TestCase(), CYCLES);
		assertNull(sut.getDischargeSuppressedChannelForTest().value().get());
		assertEquals(DischargeSuppressionReason.UNDEFINED,
				sut.getDischargeSuppressionReasonChannelForTest().value().asEnum());
		assertNull(sut.dischargeQueuedEnableForTest());
		assertEquals(State.IDLE, sut.dischargeWindowStateForTest());
	}

	@Test
	public void testDriftedEnableIsCorrectedOncePerWindow() throws Exception {
		var sut = new SrneBatteryInverterImpl();
		var bridge = bridge(80, MachineState.RUNNING_MAINS_BYPASS);
		var test = start(sut, bridge, clockAt("2026-01-10T18:05:00Z"), config(75, 45).build()) //
				.next(new TestCase(), CYCLES);
		assertEquals(State.DONE, sut.dischargeWindowStateForTest());

		// Someone disables the schedule on the device inside the window.
		bridge.withRegisters(0xE033, 0);
		test.next(new TestCase(), CYCLES);
		assertEquals(State.ENABLE_QUEUED, sut.dischargeWindowStateForTest());
		assertEquals(1, sut.dischargeQueuedEnableForTest());
		deviceWritesEnable(sut, bridge, test, 1);
		assertEquals(State.DONE, sut.dischargeWindowStateForTest());
		assertTrue(sut.driftCorrectedUnsuppressedForTest());

		// A second drift in the same window is not corrected again.
		bridge.withRegisters(0xE033, 0);
		test.next(new TestCase(), RETRY_CYCLES);
		assertEquals(State.DONE, sut.dischargeWindowStateForTest());
	}

	@Test
	public void testDriftCorrectionBudgetResetsForTheNextWindow() throws Exception {
		var sut = new SrneBatteryInverterImpl();
		var clock = clockAt("2026-01-10T18:05:00Z");
		var bridge = bridge(80, MachineState.RUNNING_MAINS_BYPASS);
		var test = start(sut, bridge, clock, config(75, 45).build()) //
				.next(new TestCase(), CYCLES);

		// Window 1: the first drift is corrected, the second one is not.
		bridge.withRegisters(0xE033, 0);
		test.next(new TestCase(), CYCLES);
		assertEquals(State.ENABLE_QUEUED, sut.dischargeWindowStateForTest());
		deviceWritesEnable(sut, bridge, test, 1);
		bridge.withRegisters(0xE033, 0);
		test.next(new TestCase(), RETRY_CYCLES);
		assertEquals(State.DONE, sut.dischargeWindowStateForTest());

		// Window 2 (next evening): the budget is back, the drift is corrected again.
		test.next(new TestCase().timeleap(clock, 3, ChronoUnit.HOURS));
		test.next(new TestCase(), CYCLES);
		test.next(new TestCase().timeleap(clock, 21, ChronoUnit.HOURS));
		test.next(new TestCase(), CYCLES);
		assertEquals(State.ENABLE_QUEUED, sut.dischargeWindowStateForTest());
		assertEquals(1, sut.dischargeQueuedEnableForTest());
	}

	@Test
	public void testRestartInsideStartRuleMinutesRelatchesLowStartFromPartlyDischargedSoc() throws Exception {
		// The night was skipped at 21:00 (device enable 0). A restart at 21:08 with a SoC
		// that has since dropped below the threshold latches LOW_START again.
		var sut = new SrneBatteryInverterImpl();
		var bridge = bridge(60, MachineState.RUNNING_MAINS_BYPASS).withRegisters(0xE033, 0);
		var test = start(sut, bridge, clockAt("2026-01-10T18:08:00Z"), config(75, 45).build()) //
				.next(new TestCase(), CYCLES);
		// The decision is latched after two confirmations; until then the disarmed device
		// is held at 0 and never armed first.
		assertEquals(DischargeSuppressionReason.LOW_START,
				sut.getDischargeSuppressionReasonChannelForTest().value().asEnum());
		assertNull(sut.dischargeQueuedEnableForTest());
		assertEquals(State.DONE, sut.dischargeWindowStateForTest());
		assertSuppression(sut, true, DischargeSuppressionReason.LOW_START);

		// A night that was allowed (SoC 80 at 21:00, enable 1) is skipped by a restart at
		// 21:08 once the SoC has fallen to 74: the documented fail-open trade-off.
		var allowed = new SrneBatteryInverterImpl();
		start(allowed, bridge(74, MachineState.RUNNING_MAINS_BYPASS), clockAt("2026-01-10T18:08:00Z"),
				config(75, 45).build()) //
				.next(new TestCase(), CYCLES);
		assertSuppression(allowed, false, DischargeSuppressionReason.LOW_START);
		assertEquals(0, allowed.dischargeQueuedEnableForTest());
	}

	@Test
	public void testRestartAfterStartRuleExpiryOfALatchedLowStartRestoresTheEnable() throws Exception {
		// Skipped at 21:00 (device enable 0); restart at 21:30: the start rule is over,
		// only the floor applies, so the enable is restored (a night that ends up allowed).
		var sut = new SrneBatteryInverterImpl();
		start(sut, bridge(60, MachineState.RUNNING_MAINS_BYPASS).withRegisters(0xE033, 0),
				clockAt("2026-01-10T18:30:00Z"), config(75, 45).build()) //
				.next(new TestCase(), CYCLES);
		assertSuppression(sut, true, DischargeSuppressionReason.NONE);
		assertEquals(1, sut.dischargeQueuedEnableForTest());
	}

	@Test
	public void testOutOfRangeAndIncoherentThresholdsFailOpen() throws Exception {
		// {startMin, floor}: each row must leave the discharge rules off (or the invalid
		// rule off) so a low SoC never suppresses the night.
		for (var pair : new int[][] { { 100, -1 }, { 150, -1 }, { -1, 100 }, { -1, 120 }, { -5, -1 }, { -1, -7 },
				{ 100, 100 } }) {
			var sut = new SrneBatteryInverterImpl();
			start(sut, bridge(10, MachineState.RUNNING_MAINS_BYPASS), clockAt("2026-01-10T18:05:00Z"),
					config(pair[0], pair[1]).build()) //
					.next(new TestCase(), CYCLES);
			var label = pair[0] + "/" + pair[1];
			assertEquals(BASELINE_TASKS, taskList(sut), label);
			assertNull(sut.getDischargeSuppressedChannelForTest().value().get(), label);
			assertNull(sut.dischargeQueuedEnableForTest(), label);
			assertEquals(State.DONE, sut.dischargeWindowStateForTest(), label);
		}
	}

	@Test
	public void testOneInvalidThresholdOnlyDisablesItself() throws Exception {
		// Floor 100 is invalid, the valid start rule keeps working.
		var sut = new SrneBatteryInverterImpl();
		start(sut, bridge(60, MachineState.RUNNING_MAINS_BYPASS), clockAt("2026-01-10T18:05:00Z"),
				config(75, 100).build()) //
				.next(new TestCase(), CYCLES);
		assertSuppression(sut, false, DischargeSuppressionReason.LOW_START);

		// Start 100 is invalid, the valid floor rule keeps working (and start is not applied).
		var floorOnly = new SrneBatteryInverterImpl();
		start(floorOnly, bridge(60, MachineState.RUNNING_MAINS_BYPASS), clockAt("2026-01-10T18:05:00Z"),
				config(100, 45).build()) //
				.next(new TestCase(), CYCLES);
		assertSuppression(floorOnly, false, DischargeSuppressionReason.NONE);
		var atFloor = new SrneBatteryInverterImpl();
		start(atFloor, bridge(45, MachineState.RUNNING_MAINS_BYPASS), clockAt("2026-01-10T18:05:00Z"),
				config(100, 45).build()) //
				.next(new TestCase(), CYCLES);
		assertSuppression(atFloor, false, DischargeSuppressionReason.FLOOR_REACHED);
	}

	@Test
	public void testInertRulesStillActivate() throws Exception {
		// controlEnabled=false: the rule is inert (a warning is logged on activate) and
		// nothing is published or written.
		var sut = new SrneBatteryInverterImpl();
		start(sut, bridge(10, MachineState.RUNNING_MAINS_BYPASS), clockAt("2026-01-10T18:05:00Z"),
				config(75, 45).setControlEnabled(false).build()) //
				.next(new TestCase(), CYCLES);
		assertNull(sut.getDischargeSuppressedChannelForTest().value().get());
		assertNull(sut.dischargeQueuedEnableForTest());
	}
}
