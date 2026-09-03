package io.openems.edge.ess.srne.batteryinverter;

import io.openems.common.types.OptionsEnum;
import io.openems.edge.bridge.modbus.api.element.UnsignedWordElement;
import io.openems.edge.bridge.modbus.api.task.Task.ExecuteState;

/**
 * Coordinates one coherent time-of-use schedule-window write.
 *
 * <p>A schedule window is the (start, stop) time pair plus its enable flag. The
 * two time registers are contiguous, so they are written together as a single
 * atomic FC16 block: the inverter never sees a half-updated window.
 *
 * <p>The whole operation is governed by one safety invariant: <b>the enable
 * register must read 0 on the device whenever the window registers are
 * written</b>. So any change to a currently-armed window is sequenced as
 * disarm &rarr; verify-disabled &rarr; atomic window write &rarr; verify-window
 * &rarr; re-arm. A live enabled window is never mutated in place; a failed or
 * mismatched write leaves the schedule disabled (the safe resting state) and is
 * never retried automatically.
 *
 * <p>The desired end enable state is captured once: {@code enable=0}/{@code 1}
 * are taken from config, while {@code enable=-1} (unmanaged) captures whatever
 * the device currently reads, so a window change on an armed device is disarmed,
 * rewritten and then restored to its original armed state. {@code enable=1}
 * requires a complete configured window; {@code enable} outside 0/1 is rejected.
 *
 * <p>Cross-midnight windows (start &gt;= stop) are rejected: the SRNE encodes
 * time as hour*256+min with a maximum of 23:59 (5947) and no defined midnight
 * wrap, so "to midnight" must be expressed as 23:59.
 *
 * <p>A fresh instance is created on every component activation, exactly like
 * {@link SafeWriteHandler}.
 *
 * <p>Thread-safety: {@code reconcile} runs on the Edge cycle thread while
 * {@code onWindowExecute}/{@code onEnableExecute} and the {@code verify*}
 * callbacks run on the Modbus bridge worker thread. All mutable state is guarded
 * by intrinsic locking so transitions are atomic and visible across threads.
 */
final class ScheduleWindow {

	public enum State implements OptionsEnum {
		UNDEFINED(-1), //
		IDLE(0), //
		DISABLE_QUEUED(1), //
		DISABLE_AWAITING_READBACK(2), //
		DISABLE_VERIFIED(3), //
		WINDOW_QUEUED(4), //
		WINDOW_AWAITING_READBACK(5), //
		WINDOW_VERIFIED(6), //
		ENABLE_QUEUED(7), //
		ENABLE_AWAITING_READBACK(8), //
		DONE(9), //
		FAILED(10);

		private final int value;

		private State(int value) {
			this.value = value;
		}

		@Override
		public int getValue() {
			return this.value;
		}

		@Override
		public String getName() {
			return this.name();
		}

		@Override
		public OptionsEnum getUndefined() {
			return UNDEFINED;
		}
	}

	private final String label;
	private final UnsignedWordElement startWrite;
	private final UnsignedWordElement stopWrite;
	private final UnsignedWordElement enableWrite;

	private State state = State.IDLE;
	private Integer targetStart;
	private Integer targetStop;
	private Integer targetEnable;
	private Integer desiredEnable;
	private boolean startVerified;
	private boolean stopVerified;
	private int awaitingReadbackCycles;

	ScheduleWindow(int startAddress, int enableAddress, String label) {
		this.label = label;
		this.startWrite = new UnsignedWordElement(startAddress);
		this.stopWrite = new UnsignedWordElement(startAddress + 1);
		this.enableWrite = new UnsignedWordElement(enableAddress);
	}

	UnsignedWordElement startWriteElement() {
		return this.startWrite;
	}

	UnsignedWordElement stopWriteElement() {
		return this.stopWrite;
	}

	UnsignedWordElement enableWriteElement() {
		return this.enableWrite;
	}

	// Package-private accessors so tests can pin the exact values queued for a
	// write (the dummy bridge cannot echo a written register) and the captured
	// end-state.
	Integer queuedStart() {
		return this.targetStart;
	}

	Integer queuedStop() {
		return this.targetStop;
	}

	Integer queuedEnable() {
		return this.targetEnable;
	}

	Integer desiredEnable() {
		return this.desiredEnable;
	}

	/**
	 * Drives the coherent window write forward by one reconcile step. Acts only from
	 * the actionable states ({@code IDLE}, {@code DISABLE_VERIFIED},
	 * {@code WINDOW_VERIFIED}); all other states are transient or terminal and
	 * ignored. Each step performs at most one register write and the whole
	 * operation converges toward the desired (window, enable) end state while never
	 * writing the window registers unless the device reads {@code enable == 0}.
	 *
	 * @param actualStart  the read-back start register, or null if unknown
	 * @param actualStop   the read-back stop register, or null if unknown
	 * @param actualEnable the read-back enable register, or null if unknown
	 * @param cfgStart     the configured start time, or -1 to leave unchanged
	 * @param cfgStop      the configured stop time, or -1 to leave unchanged
	 * @param cfgEnable    the configured enable flag (0/1), or -1 to leave unchanged
	 * @return a one-shot audit message for the transition just made, or null
	 */
	public synchronized String reconcile(Integer actualStart, Integer actualStop, Integer actualEnable, int cfgStart,
			int cfgStop, int cfgEnable) {
		return switch (this.state) {
		case IDLE -> this.start(actualStart, actualStop, actualEnable, cfgStart, cfgStop, cfgEnable);
		case DISABLE_VERIFIED, WINDOW_VERIFIED -> this.advance(actualStart, actualStop, actualEnable, cfgStart, cfgStop);
		default -> null;
		};
	}

	// Validates the request, captures the desired end enable state once, then takes
	// the first convergence step.
	private String start(Integer actualStart, Integer actualStop, Integer actualEnable, int cfgStart, int cfgStop,
			int cfgEnable) {
		// Unconfigured: nothing to manage.
		if (cfgStart < 0 && cfgStop < 0 && cfgEnable < 0) {
			return null;
		}
		// The enable flag is a boolean register; refuse anything but 0/1 so a typo can
		// never write a garbage value to the schedule-enable register.
		if (cfgEnable < -1 || cfgEnable > 1) {
			this.state = State.FAILED;
			return "Rejected [" + this.label + "] schedule enable [" + cfgEnable + "]; must be 0 or 1";
		}
		// A window is the start+stop pair; both must be configured together so a
		// half-specified window is never written.
		if ((cfgStart < 0) != (cfgStop < 0)) {
			this.state = State.FAILED;
			return "Rejected [" + this.label + "] schedule: window needs both start and stop set together";
		}
		var windowManaged = cfgStart >= 0;
		if (windowManaged && !isValidWindow(cfgStart, cfgStop)) {
			this.state = State.FAILED;
			return "Rejected [" + this.label + "] schedule window [" + cfgStart + "," + cfgStop
					+ "]; must be valid times with start < stop (encode end-of-day as 23:59)";
		}
		// Arming requires a complete window this component can set and verify.
		if (cfgEnable == 1 && !windowManaged) {
			this.state = State.FAILED;
			return "Rejected [" + this.label + "] schedule enable=1 without a complete window";
		}
		// Need the current enable (to respect the invariant and capture the end state)
		// and, when a window is managed, the current window before deciding anything.
		if (actualEnable == null || (windowManaged && (actualStart == null || actualStop == null))) {
			return null;
		}
		// Capture the desired end enable state ONCE: an explicit 0/1 is the target;
		// -1 (unmanaged) keeps whatever the device currently is, so a window change on
		// an armed device is disarmed, rewritten and restored to armed.
		this.desiredEnable = cfgEnable >= 0 ? Integer.valueOf(cfgEnable) : actualEnable;
		return this.advance(actualStart, actualStop, actualEnable, cfgStart, cfgStop);
	}

	// Takes the single next convergence step toward the desired (window, enable) end
	// state, enforcing the invariant that the window is written only while the device
	// reads enable == 0.
	private String advance(Integer actualStart, Integer actualStop, Integer actualEnable, int cfgStart, int cfgStop) {
		if (actualEnable == null) {
			return null;
		}
		var windowManaged = cfgStart >= 0;
		if (windowManaged && (actualStart == null || actualStop == null)) {
			return null;
		}
		var windowMatches = !windowManaged || (actualStart.equals(cfgStart) && actualStop.equals(cfgStop));
		if (!windowMatches) {
			// Invariant: never write the window while the schedule is armed. Disarm first.
			if (!actualEnable.equals(0)) {
				return this.queueEnable(0);
			}
			// Device is disarmed: write the start/stop pair atomically.
			this.targetStart = cfgStart;
			this.targetStop = cfgStop;
			this.startVerified = false;
			this.stopVerified = false;
			this.startWrite.setNextWriteValue(cfgStart);
			this.stopWrite.setNextWriteValue(cfgStop);
			this.state = State.WINDOW_QUEUED;
			return "Queued one-shot [" + this.label + "] window write to [" + formatEncodedTime(cfgStart) + ".."
					+ formatEncodedTime(cfgStop) + "]";
		}
		// Window is correct on the device: converge the enable flag to the end state.
		if (!actualEnable.equals(this.desiredEnable)) {
			return this.queueEnable(this.desiredEnable);
		}
		this.state = State.DONE;
		return null;
	}

	private String queueEnable(int value) {
		this.targetEnable = value;
		this.enableWrite.setNextWriteValue(value);
		this.state = value == 0 ? State.DISABLE_QUEUED : State.ENABLE_QUEUED;
		return "Queued one-shot [" + this.label + "] schedule enable=" + value
				+ (value == 0 ? " (disarm before window change)" : " (after window verified)");
	}

	public synchronized void onWindowExecute(ExecuteState executeState) {
		if (this.state != State.WINDOW_QUEUED || executeState == ExecuteState.NO_OP) {
			return;
		}
		this.awaitingReadbackCycles = 0;
		this.state = executeState == ExecuteState.OK ? State.WINDOW_AWAITING_READBACK : State.FAILED;
	}

	public synchronized void onEnableExecute(ExecuteState executeState) {
		if (executeState == ExecuteState.NO_OP) {
			return;
		}
		switch (this.state) {
		case DISABLE_QUEUED -> {
			this.awaitingReadbackCycles = 0;
			this.state = executeState == ExecuteState.OK ? State.DISABLE_AWAITING_READBACK : State.FAILED;
		}
		case ENABLE_QUEUED -> {
			this.awaitingReadbackCycles = 0;
			this.state = executeState == ExecuteState.OK ? State.ENABLE_AWAITING_READBACK : State.FAILED;
		}
		default -> {
			// not awaiting an enable execute
		}
		}
	}

	public synchronized void verifyStart(Integer actual) {
		if (this.state != State.WINDOW_AWAITING_READBACK || actual == null) {
			return;
		}
		if (!actual.equals(this.targetStart)) {
			this.state = State.FAILED;
			return;
		}
		this.startVerified = true;
		if (this.stopVerified) {
			this.state = State.WINDOW_VERIFIED;
		}
	}

	public synchronized void verifyStop(Integer actual) {
		if (this.state != State.WINDOW_AWAITING_READBACK || actual == null) {
			return;
		}
		if (!actual.equals(this.targetStop)) {
			this.state = State.FAILED;
			return;
		}
		this.stopVerified = true;
		if (this.startVerified) {
			this.state = State.WINDOW_VERIFIED;
		}
	}

	public synchronized void verifyEnable(Integer actual) {
		if (actual == null) {
			return;
		}
		switch (this.state) {
		case DISABLE_AWAITING_READBACK ->
			// disarm verified -> re-enter convergence (window write / re-arm / done)
			this.state = actual.equals(this.targetEnable) ? State.DISABLE_VERIFIED : State.FAILED;
		case ENABLE_AWAITING_READBACK -> this.state = actual.equals(this.targetEnable) ? State.DONE : State.FAILED;
		default -> {
			// not awaiting an enable read-back
		}
		}
	}

	/**
	 * Advances the bounded read-back wait without ever retrying a write.
	 *
	 * @param timeoutCycles number of Edge cycles allowed for a fresh read-back
	 */
	public synchronized void onCycle(int timeoutCycles) {
		switch (this.state) {
		case DISABLE_AWAITING_READBACK, WINDOW_AWAITING_READBACK, ENABLE_AWAITING_READBACK -> {
			if (++this.awaitingReadbackCycles >= timeoutCycles) {
				this.state = State.FAILED;
			}
		}
		default -> {
			// only the read-back waits are bounded
		}
		}
	}

	public synchronized State getState() {
		return this.state;
	}

	/**
	 * Whether a raw schedule value is a valid encoded time (hour*256+min, hour
	 * 0..23, minute 0..59).
	 *
	 * @param encoded the raw register value
	 * @return true if it decodes to a valid time
	 */
	static boolean isValidEncodedTime(int encoded) {
		if (encoded < 0 || encoded > 0xFFFF) {
			return false;
		}
		var hour = encoded >> 8;
		var minute = encoded & 0xFF;
		return hour <= 23 && minute <= 59;
	}

	/**
	 * Whether (start, stop) is a valid within-day window: both valid times and start
	 * strictly before stop. Cross-midnight (start &gt;= stop) is rejected because the
	 * SRNE has no defined midnight wrap and cannot encode 24:00, so a window that
	 * must run to midnight has to stop at 23:59.
	 *
	 * @param start the encoded start time
	 * @param stop  the encoded stop time
	 * @return true if the pair is a valid within-day window
	 */
	static boolean isValidWindow(int start, int stop) {
		return isValidEncodedTime(start) && isValidEncodedTime(stop) && start < stop;
	}

	/**
	 * Formats an encoded schedule time (hour*256+min) as HH:mm for logging.
	 *
	 * @param encoded the raw register value
	 * @return the HH:mm string
	 */
	static String formatEncodedTime(int encoded) {
		return String.format("%02d:%02d", encoded >> 8, encoded & 0xFF);
	}
}
