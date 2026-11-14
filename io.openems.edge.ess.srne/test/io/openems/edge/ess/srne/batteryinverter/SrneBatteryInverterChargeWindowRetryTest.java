package io.openems.edge.ess.srne.batteryinverter;

import static io.openems.edge.ess.srne.SrneConstants.DEFAULT_UNIT_ID;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.osgi.service.event.Event;

import io.openems.common.test.TimeLeapClock;
import io.openems.edge.bridge.modbus.api.task.Task.ExecuteState;
import io.openems.edge.bridge.modbus.test.DummyModbusBridge;
import io.openems.edge.common.channel.Channel;
import io.openems.edge.common.event.EdgeEventConstants;
import io.openems.edge.common.test.AbstractComponentTest.TestCase;
import io.openems.edge.common.test.ComponentTest;
import io.openems.edge.common.test.DummyComponentManager;
import io.openems.edge.ess.srne.batteryinverter.ScheduleWindow.State;
import io.openems.edge.ess.srne.common.enums.MachineState;

/**
 * Retry of the charge time-of-use window after a runtime failure, through the
 * real component. Reproduces the site change of 11 Nov 2026: the configured
 * window moves from 00:00-00:40 to 00:00-06:00 on an armed device. The dummy
 * bridge does not execute writes, so a test plays the device by invoking the
 * write task's execute callback and then presenting the register read-back.
 */
public class SrneBatteryInverterChargeWindowRetryTest {

	private static final int START = 0; // 00:00
	private static final int OLD_STOP = 40; // 00:40
	private static final int STOP = 1536; // 06:00
	private static final int CYCLES = 16; // >= number of LOW read tasks
	// Queued timeout plus the longest backoff plus a full read round.
	private static final int MAX_RETRY_CYCLES = 30 + 480 + CYCLES + 4;

	// 18:11 in Africa/Kampala (UTC+3).
	private static TimeLeapClock clock() {
		return new TimeLeapClock(Instant.parse("2026-11-11T15:11:00Z"), ZoneOffset.UTC);
	}

	// Armed device holding the old 00:00-00:40 charge window.
	private static DummyModbusBridge bridge() {
		return new DummyModbusBridge("modbus0") //
				.withRegisters(0xE026, START, OLD_STOP) //
				.withRegisters(0xE02C, 1, 0, 0) //
				.withRegisters(0xE033, 0, 0, 0, 0) //
				.withRegisters(0x0101, 524, 0) //
				.withRegister(0x0210, MachineState.RUNNING_MAINS_BYPASS.getValue());
	}

	private static MyConfig.Builder config() {
		return MyConfig.create() //
				.setId("batteryInverter0") //
				.setModbusId("modbus0") //
				.setModbusUnitId(DEFAULT_UNIT_ID) //
				.setMaxApparentPower(12_000) //
				.setControlEnabled(true) //
				.setChargeWindow1Start(START) //
				.setChargeWindow1Stop(STOP) //
				.setChargeScheduleEnable(1);
	}

	private static ComponentTest start(SrneBatteryInverterImpl sut, DummyModbusBridge bridge, TimeLeapClock clock,
			MyConfig config) throws Exception {
		return new ComponentTest(sut) //
				.addReference("setModbus", bridge) //
				.addReference("componentManager", new DummyComponentManager(clock)) //
				.activate(config);
	}

	// Steps one cycle at a time; returns the cycles needed to reach the state, or
	// -1.
	private static int cyclesUntil(ComponentTest test, SrneBatteryInverterImpl sut, State state, int max)
			throws Exception {
		for (var i = 1; i <= max; i++) {
			test.next(new TestCase());
			if (sut.chargeWindowForTest().getState() == state) {
				return i;
			}
		}
		return -1;
	}

	private static State chargeWindowStateChannel(SrneBatteryInverterImpl sut) {
		Channel<State> channel = sut.channel(SrneBatteryInverter.ChannelId.CHARGE_WINDOW_STATE);
		return channel.value().asEnum();
	}

	@Test
	public void testStuckInQueuedFailsThenRetriesToDone() throws Exception {
		var sut = new SrneBatteryInverterImpl();
		var bridge = bridge();
		var test = start(sut, bridge, clock(), config().build()) //
				.next(new TestCase(), CYCLES);
		var window = sut.chargeWindowForTest();
		assertEquals(State.DISABLE_QUEUED, window.getState());

		// The disarm is never executed: bounded to FAILED by the queued timeout and
		// withdrawn, then retried after the cooldown.
		assertTrue(cyclesUntil(test, sut, State.FAILED, 31) > 0);
		assertNull(window.enableWriteElement().getNextWriteValueAndReset());
		test.next(new TestCase());
		assertEquals(State.FAILED, chargeWindowStateChannel(sut));
		assertTrue(cyclesUntil(test, sut, State.DISABLE_QUEUED, MAX_RETRY_CYCLES) > 0);
		assertEquals(1, sut.chargeRetriesTodayForTest());
		assertEquals(0, window.queuedEnable());

		// Disarm lands and reads back 0: only now the window is queued. The bridge
		// takes the queued value before it executes (the dummy bridge does not).
		assertEquals(0, window.enableWriteElement().getNextWriteValueAndReset()[0].getValue());
		window.onEnableExecute(ExecuteState.OK);
		bridge.withRegisters(0xE02C, 0);
		assertTrue(cyclesUntil(test, sut, State.WINDOW_QUEUED, CYCLES) > 0);
		assertEquals(STOP, window.queuedStop());
		assertNull(window.enableWriteElement().getNextWriteValueAndReset());

		// Window lands and reads back correctly: only now the arm is queued.
		assertEquals(START, window.startWriteElement().getNextWriteValueAndReset()[0].getValue());
		assertEquals(STOP, window.stopWriteElement().getNextWriteValueAndReset()[0].getValue());
		window.onStartExecute(ExecuteState.OK);
		window.onStopExecute(ExecuteState.OK);
		bridge.withRegisters(0xE026, START, STOP);
		assertTrue(cyclesUntil(test, sut, State.ENABLE_QUEUED, CYCLES) > 0);
		assertEquals(1, window.queuedEnable());

		window.onEnableExecute(ExecuteState.OK);
		bridge.withRegisters(0xE02C, 1);
		assertTrue(cyclesUntil(test, sut, State.DONE, CYCLES) > 0);
		test.next(new TestCase());
		assertEquals(State.DONE, chargeWindowStateChannel(sut));
		assertEquals(1, sut.chargeRetriesTodayForTest());
	}

	@Test
	public void testExecuteErrorAfterTheDisarmLandedIsRetried() throws Exception {
		// Candidate site path 1: the device applies the disarm, but the write reports
		// an error (e.g. a response timeout), so DISABLE_QUEUED fails silently.
		var sut = new SrneBatteryInverterImpl();
		var bridge = bridge();
		final var test = start(sut, bridge, clock(), config().build()) //
				.next(new TestCase(), CYCLES);
		var window = sut.chargeWindowForTest();
		// The bridge takes the queued disarm before executing it.
		assertEquals(0, window.enableWriteElement().getNextWriteValueAndReset()[0].getValue());
		window.onEnableExecute(new ExecuteState.Error(new RuntimeException("response timeout")));
		bridge.withRegisters(0xE02C, 0);
		assertEquals(State.FAILED, window.getState());
		assertTrue(window.isRetryableFailure());
		// Nothing is left queued while the window waits for its retry.
		test.next(new TestCase(), 2);
		assertEquals(State.FAILED, window.getState());
		assertNull(window.enableWriteElement().getNextWriteValueAndReset());

		// The retry reads enable=0 afresh and goes straight to the window write.
		assertTrue(cyclesUntil(test, sut, State.WINDOW_QUEUED, MAX_RETRY_CYCLES) > 0);
		assertEquals(1, sut.chargeRetriesTodayForTest());
		assertEquals(STOP, sut.chargeWindowForTest().queuedStop());
	}

	@Test
	public void testReadbackTimeoutAfterExecutedDisarmIsRetried() throws Exception {
		// Candidate site path 2: the disarm executes and lands, but its read-back does
		// not arrive within the timeout, so DISABLE_AWAITING_READBACK fails silently.
		var sut = new SrneBatteryInverterImpl();
		var bridge = bridge();
		final var test = start(sut, bridge, clock(), config().build()) //
				.next(new TestCase(), CYCLES);
		var window = sut.chargeWindowForTest();
		assertEquals(0, window.enableWriteElement().getNextWriteValueAndReset()[0].getValue());
		window.onEnableExecute(ExecuteState.OK);
		assertEquals(State.DISABLE_AWAITING_READBACK, window.getState());
		bridge.withRegisters(0xE02C, 0);

		// Run the component's cycle handler without a bridge cycle: no read-back
		// arrives.
		var cycle = new Event(EdgeEventConstants.TOPIC_CYCLE_AFTER_PROCESS_IMAGE, Map.of());
		for (var i = 0; i < 30; i++) {
			assertEquals(State.DISABLE_AWAITING_READBACK, window.getState(), "cycle " + i);
			sut.handleEvent(cycle);
		}
		assertEquals(State.FAILED, window.getState());
		assertTrue(window.isRetryableFailure());
		assertNull(window.enableWriteElement().getNextWriteValueAndReset());

		// Reads resume: the retry reads enable=0 afresh and goes on to the window
		// write.
		assertTrue(cyclesUntil(test, sut, State.WINDOW_QUEUED, MAX_RETRY_CYCLES) > 0);
		assertEquals(1, sut.chargeRetriesTodayForTest());
		assertEquals(STOP, window.queuedStop());
	}

	@Test
	public void testRetriesOnlyCountWhileTheMachineStateIsVerified() throws Exception {
		var sut = new SrneBatteryInverterImpl();
		var bridge = bridge();
		final var test = start(sut, bridge, clock(), config().build()) //
				.next(new TestCase(), CYCLES);
		var window = sut.chargeWindowForTest();
		window.onEnableExecute(new ExecuteState.Error(new RuntimeException("bus error")));
		assertEquals(State.FAILED, window.getState());

		// Grid outage: the gated reconcile is unreachable, so no retry is spent.
		bridge.withRegister(0x0210, MachineState.INVERTER_POWERED.getValue());
		test.next(new TestCase(), 2 * MAX_RETRY_CYCLES);
		assertEquals(State.FAILED, window.getState());
		assertEquals(0, sut.chargeRetriesTodayForTest());

		// Mains back: the retry runs.
		bridge.withRegister(0x0210, MachineState.RUNNING_MAINS_BYPASS.getValue());
		assertTrue(cyclesUntil(test, sut, State.DISABLE_QUEUED, MAX_RETRY_CYCLES) > 0);
		assertEquals(1, sut.chargeRetriesTodayForTest());
	}

	@Test
	public void testRetryWaitsForANewEnableBusReadBeforeAnyWindowWrite() throws Exception {
		// Guards the wiring the fresh-read gate relies on: only a bus read of E02C may
		// release a retried sequence, never the cached channel value.
		var sut = new SrneBatteryInverterImpl();
		var bridge = bridge();
		final var test = start(sut, bridge, clock(), config().build()) //
				.next(new TestCase(), CYCLES);
		var window = sut.chargeWindowForTest();

		// Disarm verified (the channel now caches enable=0), window write queued, and
		// then the window write fails.
		window.onEnableExecute(ExecuteState.OK);
		bridge.withRegisters(0xE02C, 0);
		assertTrue(cyclesUntil(test, sut, State.WINDOW_QUEUED, CYCLES) > 0);
		window.onStartExecute(new ExecuteState.Error(new RuntimeException("bus error")));
		assertEquals(State.FAILED, window.getState());

		// The device is re-armed, but no bus read follows: only the component's cycle
		// handler runs, through the retry cooldown and well past it.
		bridge.withRegisters(0xE02C, 1);
		var cycle = new Event(EdgeEventConstants.TOPIC_CYCLE_AFTER_PROCESS_IMAGE, Map.of());
		for (var i = 0; i < 3 * SrneBatteryInverterImpl.chargeRetryCooldownCycles(0); i++) {
			sut.handleEvent(cycle);
			assertNull(window.startWriteElement().getNextWriteValueAndReset(), "window start written, cycle " + i);
			assertNull(window.stopWriteElement().getNextWriteValueAndReset(), "window stop written, cycle " + i);
		}
		// Retried, but held: the cached enable=0 predates the retry.
		assertEquals(1, sut.chargeRetriesTodayForTest());
		assertEquals(State.IDLE, window.getState());

		// A bus read of E02C=1 releases it, and the retry disarms first.
		assertTrue(cyclesUntil(test, sut, State.DISABLE_QUEUED, CYCLES + 2) > 0);
		assertEquals(0, window.queuedEnable());
		assertNull(window.startWriteElement().getNextWriteValueAndReset());
	}

	@Test
	public void testConfigRejectionIsNeverRetried() throws Exception {
		var sut = new SrneBatteryInverterImpl();
		// Cross-midnight window (start >= stop) is a configuration rejection.
		var test = start(sut, bridge(), clock(), config() //
				.setChargeWindow1Start(STOP) //
				.setChargeWindow1Stop(START) //
				.build()) //
				.next(new TestCase(), CYCLES);
		var window = sut.chargeWindowForTest();
		assertEquals(State.FAILED, window.getState());

		test.next(new TestCase(), 2 * MAX_RETRY_CYCLES);
		assertEquals(State.FAILED, window.getState());
		assertEquals(0, sut.chargeRetriesTodayForTest());
		assertNull(window.enableWriteElement().getNextWriteValueAndReset());
		assertNull(window.startWriteElement().getNextWriteValueAndReset());
		assertNull(window.stopWriteElement().getNextWriteValueAndReset());
	}

	@Test
	public void testUnmanagedEnableIsNotRetried() throws Exception {
		// With chargeScheduleEnable=-1 the end state would be captured from the 0 a
		// failed disarm left behind, so the window is not retried.
		var sut = new SrneBatteryInverterImpl();
		var test = start(sut, bridge(), clock(), config().setChargeScheduleEnable(-1).build()) //
				.next(new TestCase(), CYCLES);
		var window = sut.chargeWindowForTest();
		window.onEnableExecute(new ExecuteState.Error(new RuntimeException("bus error")));
		test.next(new TestCase(), MAX_RETRY_CYCLES);
		assertEquals(State.FAILED, window.getState());
		assertEquals(0, sut.chargeRetriesTodayForTest());
	}

	@Test
	public void testRetriesAreCappedPerDay() throws Exception {
		var sut = new SrneBatteryInverterImpl();
		var clock = clock();
		var test = start(sut, bridge(), clock, config().build()) //
				.next(new TestCase(), CYCLES);

		// Every attempt's disarm stays unexecuted and times out.
		for (var attempt = 1; attempt <= SrneBatteryInverterImpl.MAX_CHARGE_RETRIES_PER_DAY; attempt++) {
			assertTrue(cyclesUntil(test, sut, State.FAILED, 31) > 0, "attempt " + attempt);
			assertTrue(cyclesUntil(test, sut, State.DISABLE_QUEUED, MAX_RETRY_CYCLES) > 0, "attempt " + attempt);
			assertEquals(attempt, sut.chargeRetriesTodayForTest());
		}
		assertTrue(cyclesUntil(test, sut, State.FAILED, 31) > 0);

		// Capped: no further attempt on the same day, and nothing left queued.
		test.next(new TestCase(), 3 * MAX_RETRY_CYCLES);
		var window = sut.chargeWindowForTest();
		assertEquals(State.FAILED, window.getState());
		assertEquals(SrneBatteryInverterImpl.MAX_CHARGE_RETRIES_PER_DAY, sut.chargeRetriesTodayForTest());
		assertNull(window.enableWriteElement().getNextWriteValueAndReset());

		// 23:59 local (20:59Z): still the same local day, still capped.
		clock.leap(Duration.between(clock.instant(), Instant.parse("2026-11-11T20:59:00Z")).toMinutes(),
				ChronoUnit.MINUTES);
		test.next(new TestCase(), MAX_RETRY_CYCLES);
		assertEquals(State.FAILED, window.getState());
		assertEquals(SrneBatteryInverterImpl.MAX_CHARGE_RETRIES_PER_DAY, sut.chargeRetriesTodayForTest());

		// 00:01 local (21:01Z, still 11 Nov in UTC): the count starts again, so the day
		// is taken in the schedule zone, not in UTC.
		clock.leap(2, ChronoUnit.MINUTES);
		assertTrue(cyclesUntil(test, sut, State.DISABLE_QUEUED, MAX_RETRY_CYCLES) > 0);
		assertEquals(1, sut.chargeRetriesTodayForTest());
	}

	@Test
	public void testRetryBackoff() {
		assertEquals(60, SrneBatteryInverterImpl.chargeRetryCooldownCycles(0));
		assertEquals(120, SrneBatteryInverterImpl.chargeRetryCooldownCycles(1));
		assertEquals(240, SrneBatteryInverterImpl.chargeRetryCooldownCycles(2));
		assertEquals(480, SrneBatteryInverterImpl.chargeRetryCooldownCycles(3));
		assertEquals(480, SrneBatteryInverterImpl.chargeRetryCooldownCycles(5));
	}

	@Test
	public void testRetryNeverWritesTheWindowWhileEnableReadsOne() throws Exception {
		var sut = new SrneBatteryInverterImpl();
		var bridge = bridge();
		var test = start(sut, bridge, clock(), config().build()) //
				.next(new TestCase(), CYCLES);
		var window = sut.chargeWindowForTest();

		// Disarm verified, window write queued, then the window write fails.
		window.onEnableExecute(ExecuteState.OK);
		bridge.withRegisters(0xE02C, 0);
		assertTrue(cyclesUntil(test, sut, State.WINDOW_QUEUED, CYCLES) > 0);
		window.onStartExecute(new ExecuteState.Error(new RuntimeException("bus error")));
		assertEquals(State.FAILED, window.getState());
		assertNull(window.startWriteElement().getNextWriteValueAndReset());
		assertNull(window.stopWriteElement().getNextWriteValueAndReset());

		// The device is armed again (e.g. from the panel) while the window is failed.
		// Whatever the cached read says, the retry must disarm first.
		bridge.withRegisters(0xE02C, 1);
		for (var i = 0; i < MAX_RETRY_CYCLES && window.getState() != State.DISABLE_QUEUED; i++) {
			test.next(new TestCase());
			assertNull(window.startWriteElement().getNextWriteValueAndReset(), "window start written while armed");
			assertNull(window.stopWriteElement().getNextWriteValueAndReset(), "window stop written while armed");
		}
		assertEquals(State.DISABLE_QUEUED, window.getState());
		assertEquals(0, window.queuedEnable());
		assertEquals(1, sut.chargeRetriesTodayForTest());
	}
}
