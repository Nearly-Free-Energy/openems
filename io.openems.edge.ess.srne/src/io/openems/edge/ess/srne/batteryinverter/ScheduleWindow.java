package io.openems.edge.ess.srne.batteryinverter;

import io.openems.common.types.OptionsEnum;
import io.openems.edge.bridge.modbus.api.element.UnsignedWordElement;
import io.openems.edge.bridge.modbus.api.task.Task.ExecuteState;

/**
 * Coordinates one coherent time-of-use schedule-window write.
 *
 * <p>A schedule window is the (start, stop) time pair plus its enable flag. The
 * two time registers are contiguous, so they are written together as a single
 * atomic FC16 block: the inverter never sees a half-updated window. The enable
 * flag is sequenced by direction of safety:
 *
 * <ul>
 * <li><b>Disarm first</b>: when {@code enable=0} is requested it is written
 * before anything else, independent of the window - disabling always moves the
 * inverter to the safe state, so a window typo or a failed window write can never
 * block or delay it.
 * <li><b>Arm last</b>: {@code enable=1} is written only after BOTH time registers
 * have been read back and verified, and only when a complete window is
 * configured. Any validation failure, execute error or read-back mismatch drives
 * the window to {@code FAILED} and the enable flag is never raised, so a mis-set
 * window can never leave a schedule armed.
 * </ul>
 *
 * <p>Nothing is ever retried automatically; a fresh instance is created on every
 * component activation, exactly like {@link SafeWriteHandler}.
 *
 * <p>Cross-midnight windows (start &gt;= stop) are rejected: the SRNE encodes
 * time as hour*256+min with a maximum of 23:59 (5947) and no defined midnight
 * wrap, so "to midnight" must be expressed as 23:59.
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

	// Package-private accessors so tests can pin the exact values queued for the
	// write (the dummy bridge cannot echo a written register).
	Integer queuedStart() {
		return this.targetStart;
	}

	Integer queuedStop() {
		return this.targetStop;
	}

	Integer queuedEnable() {
		return this.targetEnable;
	}

	/**
	 * Drives the coherent window write forward by one reconcile step. Acts only from
	 * the actionable states ({@code IDLE}, {@code DISABLE_VERIFIED},
	 * {@code WINDOW_VERIFIED}); all other states are transient or terminal and
	 * ignored.
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
		case IDLE -> this.reconcileFromIdle(actualStart, actualStop, actualEnable, cfgStart, cfgStop, cfgEnable);
		case DISABLE_VERIFIED -> this.enterWindowPhase(actualStart, actualStop, actualEnable, cfgStart, cfgStop,
				cfgEnable);
		case WINDOW_VERIFIED -> this.reconcileArm(actualEnable, cfgEnable);
		default -> null;
		};
	}

	private String reconcileFromIdle(Integer actualStart, Integer actualStop, Integer actualEnable, int cfgStart,
			int cfgStop, int cfgEnable) {
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
		// Disarm has priority and is independent of the window: disabling always moves
		// the inverter to the safe state, so it must never be blocked or deferred by a
		// window change or an invalid window.
		if (cfgEnable == 0) {
			if (actualEnable == null) {
				return null;
			}
			if (!actualEnable.equals(0)) {
				this.targetEnable = 0;
				this.enableWrite.setNextWriteValue(0);
				this.state = State.DISABLE_QUEUED;
				return "Queued one-shot [" + this.label + "] schedule disable (before any window change)";
			}
			// Already disabled: safe to (re)program the window if one is configured.
			return this.enterWindowPhase(actualStart, actualStop, actualEnable, cfgStart, cfgStop, cfgEnable);
		}
		// Arming requires a complete window this component can set and verify.
		if (cfgEnable == 1 && (cfgStart < 0 || cfgStop < 0)) {
			this.state = State.FAILED;
			return "Rejected [" + this.label + "] schedule enable=1 without a complete window";
		}
		return this.enterWindowPhase(actualStart, actualStop, actualEnable, cfgStart, cfgStop, cfgEnable);
	}

	private String enterWindowPhase(Integer actualStart, Integer actualStop, Integer actualEnable, int cfgStart,
			int cfgStop, int cfgEnable) {
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
		if (windowManaged) {
			// Wait for a fresh read-back of both time registers before deciding: acting on
			// an unknown window could arm a schedule that was never verified.
			if (actualStart == null || actualStop == null) {
				return null;
			}
			// Write the window pair atomically when it differs from the device.
			if (!actualStart.equals(cfgStart) || !actualStop.equals(cfgStop)) {
				this.targetStart = cfgStart;
				this.targetStop = cfgStop;
				this.startWrite.setNextWriteValue(cfgStart);
				this.stopWrite.setNextWriteValue(cfgStop);
				this.state = State.WINDOW_QUEUED;
				return "Queued one-shot [" + this.label + "] window write to [" + formatEncodedTime(cfgStart) + ".."
						+ formatEncodedTime(cfgStop) + "]";
			}
			// Window already matches the device: treat as verified.
			this.startVerified = true;
			this.stopVerified = true;
		}
		this.state = State.WINDOW_VERIFIED;
		return this.reconcileArm(actualEnable, cfgEnable);
	}

	private String reconcileArm(Integer actualEnable, int cfgEnable) {
		// Only arming (enable=1) is written here; enable=0 was handled disarm-first.
		if (cfgEnable == 1) {
			if (actualEnable == null) {
				return null;
			}
			if (!actualEnable.equals(1)) {
				this.targetEnable = 1;
				this.enableWrite.setNextWriteValue(1);
				this.state = State.ENABLE_QUEUED;
				return "Queued one-shot [" + this.label + "] schedule enable=1 (after window verified)";
			}
		}
		this.state = State.DONE;
		return null;
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
			// disarm verified -> continue to the window phase (reprogram if configured)
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
