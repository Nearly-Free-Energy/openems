package io.openems.edge.ess.srne.batteryinverter;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.format.ResolverStyle;

import io.openems.edge.bridge.modbus.api.task.Task.ExecuteState;

/**
 * Daily output-priority schedule for E204 (0 SOL, 1 UTI, 2 SBU, 3 SUB).
 *
 * <p>
 * The evening priority applies from {@code eveningStart} (inclusive) until
 * {@code daytimeStart} (exclusive), crossing midnight when needed; the daytime
 * priority applies the rest of the day.
 *
 * <p>
 * Optional daytime SoC rule ({@code daytimeSbuAboveSoc} /
 * {@code daytimeSbuBelowSoc}): in the daytime window the target becomes the
 * evening priority (SBU) once SoC is at or above the upper threshold, and the
 * daytime priority (SUB) again once SoC is below the lower one (hysteresis,
 * starting in the daytime priority each day). In SUB the SRNE curtails PV once
 * the battery is nearly full, so a full battery should run SBU. An unreadable
 * SoC keeps the current target. The evening window is unchanged.
 *
 * <p>
 * Optional evening reserve ({@code eveningReserveSoc} from
 * {@code eveningReserveFrom} until {@code eveningStart}): while SoC is below
 * the reserve the target is the daytime priority (SUB). With
 * {@code eveningReserveHold} the daytime priority is also kept once the reserve
 * is reached, until {@code eveningStart}, instead of returning to the SoC rule
 * (which would run SBU and drain the reserve before the peak).
 *
 * <p>
 * Writes go through a {@link SafeWriteHandler} (one per write: a handler is
 * one-shot), so each write is read-back verified.
 *
 * <p>
 * Fail-safe:
 * <ul>
 * <li>nothing is written while the SoC, E204 or the local time is unknown; the
 * time counts as known only once it is plausible (see
 * {@link #plausibleTime}),</li>
 * <li>a write is queued only while the machine state is verified (state 2,
 * mains) and the last {@link #MAINS_CONFIRM_READS} fresh reads of it were all
 * verified; a write still queued when mains is lost is withdrawn if the bridge
 * has not taken it yet ({@link GuardedWordElement}), otherwise it stays in
 * flight and counts as an attempt,</li>
 * <li>no write in the first {@link #STARTUP_HOLD_CYCLES} cycles after
 * activation, so a crash/restart loop cannot spend more than one write per hold
 * period,</li>
 * <li>SBU is written only when the caller confirms the SBU preconditions (the
 * same reserve-band / BMS checks as a static outputPriority arm); SUB needs
 * none,</li>
 * <li>a failed write (execute error, wrong or missing read-back) leaves the
 * device as it is; retries back off exponentially and at most
 * {@link #MAX_ATTEMPTS} failed writes are allowed per window (successful writes
 * do not count); the budget is refilled only when the local time crosses a
 * boundary forward in a small step (a clock jump or a target flip does not
 * refill it), and WRITE_FAILED is latched until then,</li>
 * <li>at most {@link #MAX_DAILY_WRITES} successful writes per local day
 * (EEPROM), then the mode is held (DAILY_WRITE_LIMIT),</li>
 * <li>a SoC-driven switch waits until {@code minDwellMinutes} after the last
 * verified switch (MIN_DWELL); window switches at the boundaries do not,</li>
 * <li>a write that verified but is later overridden (panel, app, firmware) is
 * re-corrected with exponential backoff, at most
 * {@link #MAX_OVERRIDES_PER_WINDOW} times per window and
 * {@link #MAX_OVERRIDES_PER_DAY} times per day; then OVERRIDDEN is held until
 * the next boundary or a SoC-driven target change,</li>
 * <li>an invalid configuration turns the schedule off.</li>
 * </ul>
 *
 * <p>
 * Thread-safety: {@link #step} and {@link #plausibleTime} run on the Edge cycle
 * thread; {@link #onExecute} and {@link #verify} run on the Modbus bridge
 * thread and only touch the (synchronized) current handler.
 */
final class ModeSchedule {

	static final int SBU = 2;
	static final int SUB = 3;

	/** Failed writes allowed between two schedule boundaries. */
	static final int MAX_ATTEMPTS = 5;
	/** Successful E204 writes allowed per local day. */
	static final int MAX_DAILY_WRITES = 12;
	/** Re-corrections of an overridden E204 allowed per window (same target). */
	static final int MAX_OVERRIDES_PER_WINDOW = 3;
	/**
	 * Re-corrections allowed per local day, leaving headroom in the daily ceiling
	 * for the window and SoC-driven switches.
	 */
	static final int MAX_OVERRIDES_PER_DAY = 6;
	/** Default minimum dwell after a verified switch, in minutes. */
	static final int DEFAULT_MIN_DWELL_MINUTES = 10;
	/** Consecutive fresh verified machine-state reads needed before a write. */
	static final int MAINS_CONFIRM_READS = 3;
	/**
	 * Machine state while running on battery with SBU. The protocol enum labels 3
	 * "soft start", but gw-pi2 field data (v32 notes, 6 Nov 2026 reading) shows 3
	 * while the unit runs on battery after leaving the line at E020.
	 */
	static final int ON_BATTERY_STATE = 3;
	/**
	 * Default for gridPresentMinVoltage: -1, the SUB-on-battery path is off until a
	 * gateway enables it after one supervised live state-3 SUB write.
	 */
	static final int DEFAULT_GRID_PRESENT_MIN_VOLTAGE = -1;
	/** Default start of the evening-reserve window ("HH:MM", local). */
	static final String DEFAULT_EVENING_RESERVE_FROM = "14:00";
	/** Cycles after activation without any schedule write (about 5 minutes). */
	static final int STARTUP_HOLD_CYCLES = 300;
	/**
	 * Earliest plausible wall-clock time (build-time floor). gw-pi2 has no RTC: a
	 * time before this is an unset clock.
	 */
	static final Instant MIN_PLAUSIBLE_TIME = Instant.parse("2026-11-01T00:00:00Z");
	/**
	 * Cycles the clock must run steadily (no backward step, no forward jump of more
	 * than {@link DischargeGuard#CLOCK_STEP_MINUTES} minutes) after start or after
	 * a step before its time is used. An NTP correction after a reboot is such a
	 * step and restarts the count.
	 */
	static final int CLOCK_SETTLE_CYCLES = 60;

	private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("HH:mm")
			.withResolverStyle(ResolverStyle.STRICT);
	private static final Duration MAX_CLOCK_STEP = Duration.ofMinutes(DischargeGuard.CLOCK_STEP_MINUTES);

	/**
	 * One evaluation result.
	 *
	 * @param state    the schedule state for this cycle
	 * @param target   the target priority for this time of day, or null if unknown
	 * @param write    the value to write to E204 this cycle, or null for no write
	 * @param withdraw true if a queued, not yet executed write must be dropped
	 */
	record Step(ModeScheduleState state, Integer target, Integer write, boolean withdraw) {

		Step(ModeScheduleState state, Integer target, Integer write) {
			this(state, target, write, false);
		}
	}

	private final boolean enabled;
	// Set once while building (fromConfig), never changed afterwards.
	private String invalidReason;
	private int eveningReserveSoc = -1;
	private LocalTime eveningReserveFrom;
	private boolean eveningReserveHold;
	private final int daytimePriority;
	private final int eveningPriority;
	private final LocalTime eveningStart;
	private final LocalTime daytimeStart;
	private final int sbuAboveSoc;
	private final int sbuBelowSoc;
	private final Duration minDwell;
	private final int gridPresentMinMillivolts;

	private volatile SafeWriteHandler handler;
	private Integer handlerTarget;
	private int cyclesSinceDone;
	private final GuardedWordElement writeElement = new GuardedWordElement(0xE204);
	private int failures;
	private boolean outcomeCounted;
	private boolean highSoc;
	private boolean reserveActive;
	private boolean reserveHeld;
	private boolean socStateSeeded;
	private LocalDateTime activatedAt;
	private LocalDateTime queuedAt;
	private ModeScheduleReason reason;
	private LocalDateTime lastSwitchAt;
	private Integer lastSwitchTarget;
	private LocalDate writesDate;
	private int writesToday;
	private Integer overrideTarget;
	private int overrides;
	private LocalDate overridesDate;
	private int overridesToday;
	private volatile int freshMainsReads;
	private volatile int freshBatteryReads;
	private volatile int gridAboveL1;
	private volatile int gridAboveL2;
	private volatile int gridAboveL3;
	private int cycles;
	private LocalTime lastNow;
	private Instant lastInstant;
	private int steadyClockCycles;

	private ModeSchedule(boolean enabled, String invalidReason, int daytimePriority, int eveningPriority,
			LocalTime eveningStart, LocalTime daytimeStart, int sbuAboveSoc, int sbuBelowSoc, int minDwellMinutes,
			int gridPresentMinVoltage) {
		this.enabled = enabled;
		this.invalidReason = invalidReason;
		this.daytimePriority = daytimePriority;
		this.eveningPriority = eveningPriority;
		this.eveningStart = eveningStart;
		this.daytimeStart = daytimeStart;
		this.sbuAboveSoc = sbuAboveSoc;
		this.sbuBelowSoc = sbuBelowSoc;
		this.minDwell = Duration.ofMinutes(Math.max(minDwellMinutes, 0));
		this.gridPresentMinMillivolts = gridPresentMinVoltage < 0 ? -1 : gridPresentMinVoltage * 1000;
	}

	/**
	 * Builds and validates a schedule without the daytime SoC rule.
	 *
	 * @param enabled              modeScheduleEnabled
	 * @param daytimePriority      E204 value by day (2 or 3)
	 * @param eveningPriority      E204 value in the evening (2 or 3, not the
	 *                             daytime value)
	 * @param eveningStart         "HH:MM" start of the evening priority
	 * @param daytimeStart         "HH:MM" start of the daytime priority
	 * @param staticOutputPriority the static outputPriority target (-1 = unset)
	 * @return the schedule; check {@link #getInvalidReason()}
	 */
	static ModeSchedule fromConfig(boolean enabled, int daytimePriority, int eveningPriority, String eveningStart,
			String daytimeStart, int staticOutputPriority) {
		return fromConfig(enabled, daytimePriority, eveningPriority, eveningStart, daytimeStart, staticOutputPriority,
				-1, -1, DEFAULT_MIN_DWELL_MINUTES);
	}

	/**
	 * Builds and validates a schedule from configuration values.
	 *
	 * @param enabled              modeScheduleEnabled
	 * @param daytimePriority      E204 value by day (2 or 3)
	 * @param eveningPriority      E204 value in the evening (2 or 3, not the
	 *                             daytime value)
	 * @param eveningStart         "HH:MM" start of the evening priority
	 * @param daytimeStart         "HH:MM" start of the daytime priority
	 * @param staticOutputPriority the static outputPriority target (-1 = unset)
	 * @param sbuAboveSoc          daytimeSbuAboveSoc (-1 = SoC rule off)
	 * @param sbuBelowSoc          daytimeSbuBelowSoc (-1 = SoC rule off)
	 * @param minDwellMinutes      minimum dwell after a verified switch
	 * @return the schedule; check {@link #getInvalidReason()}
	 */
	static ModeSchedule fromConfig(boolean enabled, int daytimePriority, int eveningPriority, String eveningStart,
			String daytimeStart, int staticOutputPriority, int sbuAboveSoc, int sbuBelowSoc, int minDwellMinutes) {
		return fromConfig(enabled, daytimePriority, eveningPriority, eveningStart, daytimeStart, staticOutputPriority,
				sbuAboveSoc, sbuBelowSoc, minDwellMinutes, DEFAULT_GRID_PRESENT_MIN_VOLTAGE);
	}

	/**
	 * Builds and validates a schedule from configuration values.
	 *
	 * @param enabled               modeScheduleEnabled
	 * @param daytimePriority       E204 value by day (2 or 3)
	 * @param eveningPriority       E204 value in the evening (2 or 3, not the
	 *                              daytime value)
	 * @param eveningStart          "HH:MM" start of the evening priority
	 * @param daytimeStart          "HH:MM" start of the daytime priority
	 * @param staticOutputPriority  the static outputPriority target (-1 = unset)
	 * @param sbuAboveSoc           daytimeSbuAboveSoc (-1 = SoC rule off)
	 * @param sbuBelowSoc           daytimeSbuBelowSoc (-1 = SoC rule off)
	 * @param minDwellMinutes       minimum dwell after a verified switch
	 * @param gridPresentMinVoltage grid voltage (V) on every phase that counts as
	 *                              grid present for a SUB write on battery; -1 =
	 *                              SUB writes only in state 2
	 * @return the schedule; check {@link #getInvalidReason()}
	 */
	static ModeSchedule fromConfig(boolean enabled, int daytimePriority, int eveningPriority, String eveningStart,
			String daytimeStart, int staticOutputPriority, int sbuAboveSoc, int sbuBelowSoc, int minDwellMinutes,
			int gridPresentMinVoltage) {
		return fromConfig(enabled, daytimePriority, eveningPriority, eveningStart, daytimeStart, staticOutputPriority,
				sbuAboveSoc, sbuBelowSoc, minDwellMinutes, gridPresentMinVoltage, -1, DEFAULT_EVENING_RESERVE_FROM);
	}

	/**
	 * Builds and validates a schedule from configuration values, including the
	 * evening reserve.
	 *
	 * @param enabled               modeScheduleEnabled
	 * @param daytimePriority       E204 value by day (2 or 3)
	 * @param eveningPriority       E204 value in the evening (2 or 3, not the
	 *                              daytime value)
	 * @param eveningStart          "HH:MM" start of the evening priority
	 * @param daytimeStart          "HH:MM" start of the daytime priority
	 * @param staticOutputPriority  the static outputPriority target (-1 = unset)
	 * @param sbuAboveSoc           daytimeSbuAboveSoc (-1 = SoC rule off)
	 * @param sbuBelowSoc           daytimeSbuBelowSoc (-1 = SoC rule off)
	 * @param minDwellMinutes       minimum dwell after a verified switch
	 * @param gridPresentMinVoltage grid voltage (V) for a SUB write on battery; -1
	 *                              = SUB writes only in state 2
	 * @param eveningReserveSoc     eveningReserveSoc (-1 = off, else 20..100)
	 * @param eveningReserveFrom    "HH:MM" start of the reserve window, which ends
	 *                              at eveningStart
	 * @return the schedule; check {@link #getInvalidReason()}
	 */
	static ModeSchedule fromConfig(boolean enabled, int daytimePriority, int eveningPriority, String eveningStart,
			String daytimeStart, int staticOutputPriority, int sbuAboveSoc, int sbuBelowSoc, int minDwellMinutes,
			int gridPresentMinVoltage, int eveningReserveSoc, String eveningReserveFrom) {
		return fromConfig(enabled, daytimePriority, eveningPriority, eveningStart, daytimeStart, staticOutputPriority,
				sbuAboveSoc, sbuBelowSoc, minDwellMinutes, gridPresentMinVoltage, eveningReserveSoc, eveningReserveFrom,
				false);
	}

	/**
	 * Builds and validates a schedule from configuration values, including the
	 * evening reserve and its hold.
	 *
	 * @param enabled               modeScheduleEnabled
	 * @param daytimePriority       E204 value by day (2 or 3)
	 * @param eveningPriority       E204 value in the evening (2 or 3, not the
	 *                              daytime value)
	 * @param eveningStart          "HH:MM" start of the evening priority
	 * @param daytimeStart          "HH:MM" start of the daytime priority
	 * @param staticOutputPriority  the static outputPriority target (-1 = unset)
	 * @param sbuAboveSoc           daytimeSbuAboveSoc (-1 = SoC rule off)
	 * @param sbuBelowSoc           daytimeSbuBelowSoc (-1 = SoC rule off)
	 * @param minDwellMinutes       minimum dwell after a verified switch
	 * @param gridPresentMinVoltage grid voltage (V) for a SUB write on battery; -1
	 *                              = SUB writes only in state 2
	 * @param eveningReserveSoc     eveningReserveSoc (-1 = off, else 20..100)
	 * @param eveningReserveFrom    "HH:MM" start of the reserve window, which ends
	 *                              at eveningStart
	 * @param eveningReserveHold    keep the daytime priority until eveningStart
	 *                              once the reserve is reached (inert while the
	 *                              reserve is off)
	 * @return the schedule; check {@link #getInvalidReason()}
	 */
	static ModeSchedule fromConfig(boolean enabled, int daytimePriority, int eveningPriority, String eveningStart,
			String daytimeStart, int staticOutputPriority, int sbuAboveSoc, int sbuBelowSoc, int minDwellMinutes,
			int gridPresentMinVoltage, int eveningReserveSoc, String eveningReserveFrom, boolean eveningReserveHold) {
		var schedule = build(enabled, daytimePriority, eveningPriority, eveningStart, daytimeStart,
				staticOutputPriority, sbuAboveSoc, sbuBelowSoc, minDwellMinutes, gridPresentMinVoltage);
		if (!enabled || schedule.invalidReason != null || eveningReserveSoc == -1) {
			return schedule;
		}
		var from = parseTime(eveningReserveFrom);
		if (eveningReserveSoc < 20 || eveningReserveSoc > 100) {
			schedule.invalidReason = "eveningReserveSoc [" + eveningReserveSoc + "] must be 20..100 or -1";
		} else if (from == null) {
			schedule.invalidReason = "eveningReserveFrom [" + eveningReserveFrom + "] must be HH:MM";
		} else if (schedule.isEvening(from) || from.equals(schedule.eveningStart)) {
			schedule.invalidReason = "eveningReserveFrom [" + eveningReserveFrom
					+ "] must be in the daytime window, before eveningStart";
		} else {
			schedule.eveningReserveSoc = eveningReserveSoc;
			schedule.eveningReserveFrom = from;
			schedule.eveningReserveHold = eveningReserveHold;
		}
		return schedule;
	}

	private static ModeSchedule build(boolean enabled, int daytimePriority, int eveningPriority, String eveningStart,
			String daytimeStart, int staticOutputPriority, int sbuAboveSoc, int sbuBelowSoc, int minDwellMinutes,
			int gridPresentMinVoltage) {
		if (!enabled) {
			return new ModeSchedule(false, null, daytimePriority, eveningPriority, null, null, -1, -1, minDwellMinutes,
					-1);
		}
		var evening = parseTime(eveningStart);
		var daytime = parseTime(daytimeStart);
		String reason = null;
		if (!isAllowedPriority(daytimePriority) || !isAllowedPriority(eveningPriority)) {
			reason = "daytimePriority [" + daytimePriority + "] and eveningPriority [" + eveningPriority
					+ "] must be 2 (SBU) or 3 (SUB)";
		} else if (daytimePriority == eveningPriority) {
			reason = "daytimePriority and eveningPriority must differ [" + daytimePriority + "]";
		} else if (evening == null || daytime == null) {
			reason = "eveningStart [" + eveningStart + "] and daytimeStart [" + daytimeStart + "] must be HH:MM";
		} else if (evening.equals(daytime)) {
			reason = "eveningStart and daytimeStart must differ";
		} else if (staticOutputPriority != -1) {
			reason = "outputPriority [" + staticOutputPriority
					+ "] must be -1 while the mode schedule is enabled (one owner of E204)";
		} else if (minDwellMinutes < 0 || minDwellMinutes > 1440) {
			reason = "minDwellMinutes [" + minDwellMinutes + "] must be 0..1440";
		} else if (gridPresentMinVoltage != -1 && (gridPresentMinVoltage < 100 || gridPresentMinVoltage > 260)) {
			reason = "gridPresentMinVoltage [" + gridPresentMinVoltage + "] must be 100..260 V or -1";
		} else {
			reason = validateSocRule(eveningPriority, sbuAboveSoc, sbuBelowSoc);
		}
		return new ModeSchedule(true, reason, daytimePriority, eveningPriority, evening, daytime, sbuAboveSoc,
				sbuBelowSoc, minDwellMinutes, gridPresentMinVoltage);
	}

	private static String validateSocRule(int eveningPriority, int above, int below) {
		if (above == -1 && below == -1) {
			return null;
		}
		if (above == -1 || below == -1) {
			return "daytimeSbuAboveSoc [" + above + "] and daytimeSbuBelowSoc [" + below
					+ "] must both be set or both be -1";
		}
		if (above < 10 || above > 100 || below < 10 || below > 100) {
			return "daytimeSbuAboveSoc [" + above + "] and daytimeSbuBelowSoc [" + below + "] must be 10..100";
		}
		if (below >= above) {
			return "daytimeSbuBelowSoc [" + below + "] must be below daytimeSbuAboveSoc [" + above + "]";
		}
		if (eveningPriority != SBU) {
			return "the daytime SoC rule needs eveningPriority 2 (SBU)";
		}
		return null;
	}

	boolean isEveningReserveActive() {
		return this.eveningReserveSoc >= 0;
	}

	boolean isEveningReserveHoldActive() {
		return this.isEveningReserveActive() && this.eveningReserveHold;
	}

	/**
	 * Whether a local time is in the evening-reserve window, from
	 * eveningReserveFrom (inclusive) to eveningStart (exclusive).
	 *
	 * @param time the local time
	 * @return true inside the window; always false while the reserve is off
	 */
	boolean isInReserveWindow(LocalTime time) {
		if (!this.isEveningReserveActive()) {
			return false;
		}
		if (this.eveningReserveFrom.isBefore(this.eveningStart)) {
			return !time.isBefore(this.eveningReserveFrom) && time.isBefore(this.eveningStart);
		}
		return !time.isBefore(this.eveningReserveFrom) || time.isBefore(this.eveningStart);
	}

	boolean isSocRuleActive() {
		return this.sbuAboveSoc >= 0 && this.sbuBelowSoc >= 0;
	}

	ModeScheduleReason reason() {
		return this.reason;
	}

	static boolean isAllowedPriority(int priority) {
		return priority == SBU || priority == SUB;
	}

	static LocalTime parseTime(String value) {
		if (value == null) {
			return null;
		}
		try {
			return LocalTime.parse(value.trim(), TIME_FORMAT);
		} catch (DateTimeParseException e) {
			return null;
		}
	}

	/**
	 * Whether the schedule is enabled and valid, i.e. may write.
	 *
	 * @return true if active
	 */
	boolean isActive() {
		return this.enabled && this.invalidReason == null;
	}

	boolean isEnabled() {
		return this.enabled;
	}

	String getInvalidReason() {
		return this.invalidReason;
	}

	/**
	 * The E204 write element of the schedule's own write task.
	 *
	 * @return the element
	 */
	GuardedWordElement writeElement() {
		return this.writeElement;
	}

	/**
	 * Called on every fresh read of the machine state (the channel's
	 * onSetNextValue), from the Modbus bridge thread. Counts consecutive verified
	 * reads, so a cached value can never count twice.
	 *
	 * @param verified whether this read is the verified state 2
	 */
	void onMachineStateRead(boolean verified) {
		this.onMachineStateRead(verified ? 2 : null);
	}

	/**
	 * Called on every fresh read of the machine state with its raw value. Counts
	 * consecutive reads of state 2 (mains) and of {@link #ON_BATTERY_STATE}.
	 *
	 * @param state the raw machine state, or null if unreadable
	 */
	void onMachineStateRead(Integer state) {
		var mainsRead = state != null && state == 2;
		var batteryRead = state != null && state == ON_BATTERY_STATE;
		this.freshMainsReads = mainsRead ? Math.min(this.freshMainsReads + 1, MAINS_CONFIRM_READS) : 0;
		this.freshBatteryReads = batteryRead ? Math.min(this.freshBatteryReads + 1, MAINS_CONFIRM_READS) : 0;
	}

	/**
	 * Called on every fresh read of a grid-input phase voltage (Modbus bridge
	 * thread). Counts consecutive reads above {@code gridPresentMinVoltage} per
	 * phase.
	 *
	 * @param phase      1, 2 or 3
	 * @param millivolts the voltage in mV, or null if unreadable
	 */
	void onGridVoltageRead(int phase, Integer millivolts) {
		var above = this.gridPresentMinMillivolts >= 0 && millivolts != null
				&& millivolts > this.gridPresentMinMillivolts;
		switch (phase) {
		case 1 -> this.gridAboveL1 = above ? Math.min(this.gridAboveL1 + 1, MAINS_CONFIRM_READS) : 0;
		case 2 -> this.gridAboveL2 = above ? Math.min(this.gridAboveL2 + 1, MAINS_CONFIRM_READS) : 0;
		case 3 -> this.gridAboveL3 = above ? Math.min(this.gridAboveL3 + 1, MAINS_CONFIRM_READS) : 0;
		default -> {
		}
		}
	}

	boolean isGridPresentPathEnabled() {
		return this.gridPresentMinMillivolts >= 0;
	}

	// The on-battery SUB path is open: the last reads were the on-battery state and
	// every phase above the grid-present voltage, `reads` times in a row.
	private boolean onBatteryWithGrid(int reads) {
		return this.isGridPresentPathEnabled() && this.freshBatteryReads >= reads && this.gridAboveL1 >= reads
				&& this.gridAboveL2 >= reads && this.gridAboveL3 >= reads;
	}

	/**
	 * Whether the local time moved forward across a boundary since the last cycle,
	 * in a step no larger than the clock-step limit.
	 *
	 * @param previous the previous local time, or null
	 * @param now      the current local time
	 * @return true on a forward boundary crossing
	 */
	boolean crossedBoundaryForward(LocalTime previous, LocalTime now) {
		if (previous == null) {
			return false;
		}
		var step = forwardNanos(previous, now);
		if (step == 0 || step > MAX_CLOCK_STEP.toNanos()) {
			return false;
		}
		for (var boundary : new LocalTime[] { this.eveningStart, this.daytimeStart }) {
			var toBoundary = forwardNanos(previous, boundary);
			if (toBoundary > 0 && toBoundary <= step) {
				return true;
			}
		}
		return false;
	}

	private static long forwardNanos(LocalTime from, LocalTime to) {
		var day = Duration.ofDays(1).toNanos();
		return ((to.toNanoOfDay() - from.toNanoOfDay()) % day + day) % day;
	}

	/**
	 * Clock plausibility gate, fed once per cycle. Returns the local time only if
	 * the instant is at or after {@link #MIN_PLAUSIBLE_TIME} and the clock has run
	 * steadily for {@link #CLOCK_SETTLE_CYCLES} cycles; otherwise null (unknown).
	 *
	 * @param instant the edge clock instant, or null if unreadable
	 * @param zone    the schedule time zone
	 * @return the plausible local date-time, or null
	 */
	LocalDateTime plausibleTime(Instant instant, ZoneId zone) {
		if (instant == null || instant.isBefore(MIN_PLAUSIBLE_TIME)) {
			this.lastInstant = null;
			this.steadyClockCycles = 0;
			return null;
		}
		if (this.lastInstant != null) {
			var delta = Duration.between(this.lastInstant, instant);
			if (delta.isNegative() || delta.compareTo(MAX_CLOCK_STEP) > 0) {
				this.steadyClockCycles = 0;
			} else if (this.steadyClockCycles < CLOCK_SETTLE_CYCLES) {
				this.steadyClockCycles++;
			}
		}
		this.lastInstant = instant;
		if (this.steadyClockCycles < CLOCK_SETTLE_CYCLES) {
			return null;
		}
		return LocalDateTime.ofInstant(instant, zone);
	}

	/**
	 * Whether a local time falls in the evening window.
	 *
	 * @param time the local time
	 * @return true from eveningStart (inclusive) to daytimeStart (exclusive)
	 */
	boolean isEvening(LocalTime time) {
		if (this.eveningStart.isBefore(this.daytimeStart)) {
			return !time.isBefore(this.eveningStart) && time.isBefore(this.daytimeStart);
		}
		return !time.isBefore(this.eveningStart) || time.isBefore(this.daytimeStart);
	}

	/**
	 * The target E204 value at a local time.
	 *
	 * @param time the local time, or null if unknown
	 * @return the target priority, or null if inactive or the time is unknown
	 */
	Integer targetAt(LocalTime time) {
		if (!this.isActive() || time == null) {
			return null;
		}
		return this.isEvening(time) ? this.eveningPriority : this.daytimePriority;
	}

	/**
	 * Cycles to wait after the given number of failed writes: the base cooldown,
	 * doubled for each further failure.
	 *
	 * @param attempts failed writes in this window (at least 1)
	 * @param cooldown base cooldown in cycles
	 * @return the backoff in cycles
	 */
	static int backoffCycles(int attempts, int cooldown) {
		var shift = Math.min(Math.max(attempts - 1, 0), MAX_ATTEMPTS);
		return cooldown << shift;
	}

	/**
	 * The target for this cycle, applying the daytime SoC rule (with hysteresis) on
	 * top of the window. Updates {@link #reason()}.
	 *
	 * <p>
	 * The hysteresis state is not persisted, so after an activation in the daytime
	 * it is seeded once from the device: a device already in the evening priority
	 * (SBU) with SoC at or above the lower threshold stays high, otherwise the
	 * upper threshold decides. A restart in the band therefore never writes SUB
	 * over a running SBU.
	 *
	 * @param now            the local date-time, or null if unknown
	 * @param soc            the battery SoC, or null if unreadable
	 * @param outputPriority the E204 read-back, or null if unreadable
	 * @return the target priority, or null if the time is unknown
	 */
	Integer targetFor(LocalDateTime now, Integer soc, Integer outputPriority) {
		if (now == null) {
			this.reason = null;
			return null;
		}
		var time = now.toLocalTime();
		var socUsable = soc != null && soc >= 0 && soc <= 100;
		if (this.isEvening(time)) {
			// Each day starts again in the daytime priority.
			this.highSoc = false;
			this.socStateSeeded = true;
			this.reserveActive = false;
			this.reserveHeld = false;
			this.reason = ModeScheduleReason.WINDOW;
			return this.targetAt(time);
		}
		if (!this.isInReserveWindow(time)) {
			this.reserveActive = false;
			this.reserveHeld = false;
		} else if (socUsable) {
			// An unreadable SoC keeps the reserve decision.
			this.reserveActive = soc < this.eveningReserveSoc;
			// Hold: once the reserve is reached it is kept until eveningStart, so the
			// houses stay on the grid instead of draining the reserve before the peak.
			this.reserveHeld |= this.eveningReserveHold && !this.reserveActive;
		}
		if (this.reserveActive || this.reserveHeld) {
			// Evening reserve: the daytime priority, so the sun charges the battery for
			// the peak. The SoC rule has to reach its upper threshold again afterwards.
			this.highSoc = false;
			this.socStateSeeded = true;
			this.reason = this.reserveActive ? ModeScheduleReason.EVENING_RESERVE
					: ModeScheduleReason.EVENING_RESERVE_HOLD;
			return this.daytimePriority;
		}
		if (!this.isSocRuleActive()) {
			this.reason = ModeScheduleReason.WINDOW;
			return this.targetAt(time);
		}
		if (!this.socStateSeeded && socUsable && outputPriority != null) {
			this.highSoc = (outputPriority == this.eveningPriority && soc >= this.sbuBelowSoc)
					|| soc >= this.sbuAboveSoc;
			this.socStateSeeded = true;
		} else if (this.socStateSeeded && socUsable) {
			if (!this.highSoc && soc >= this.sbuAboveSoc) {
				this.highSoc = true;
			} else if (this.highSoc && soc < this.sbuBelowSoc) {
				this.highSoc = false;
			}
		}
		// An unreadable SoC keeps the current target.
		this.reason = this.highSoc ? ModeScheduleReason.HIGH_SOC : ModeScheduleReason.LOW_SOC;
		return this.highSoc ? this.eveningPriority : this.daytimePriority;
	}

	/**
	 * Evaluates one cycle. A write is armed on {@link #writeElement()} here; the
	 * returned write is informational. The outcome of a write reaches this schedule
	 * through {@link #onExecute} and {@link #verify}.
	 *
	 * @param now             plausible local date-time, or null if unknown
	 * @param soc             battery SoC, or null if unreadable
	 * @param outputPriority  E204 read-back, or null if unreadable
	 * @param controlEnabled  the settings-write safety gate
	 * @param mains           whether the verified machine-state gate holds now;
	 *                        fresh reads are counted by {@link #onMachineStateRead}
	 * @param sbuAllowed      whether the SBU preconditions (reserve band, BMS) are
	 *                        met
	 * @param readbackTimeout cycles allowed for a fresh read-back
	 * @param cooldown        base cycles to wait after a failed or overridden write
	 * @return the result for this cycle
	 */
	Step step(LocalDateTime now, Integer soc, Integer outputPriority, boolean controlEnabled, boolean mains,
			boolean sbuAllowed, int readbackTimeout, int cooldown) {
		if (!this.enabled) {
			return new Step(ModeScheduleState.DISABLED, null, null);
		}
		if (this.invalidReason != null) {
			return new Step(ModeScheduleState.CONFIG_INVALID, null, null);
		}
		if (this.cycles < STARTUP_HOLD_CYCLES) {
			this.cycles++;
		}
		var current = this.handler;
		SafeWriteHandler.State handlerState = null;
		if (current != null) {
			current.onCycle(readbackTimeout);
			handlerState = current.getState();
			if ((handlerState == SafeWriteHandler.State.VERIFIED || handlerState == SafeWriteHandler.State.FAILED)
					&& this.cyclesSinceDone < Integer.MAX_VALUE) {
				this.cyclesSinceDone++;
			}
			this.countOutcome(handlerState, now);
		}
		if (this.activatedAt == null && now != null) {
			this.activatedAt = now;
		}
		var target = this.targetFor(now, soc, outputPriority);
		if (target != null && !target.equals(this.overrideTarget)) {
			// A new target (boundary or SoC-driven) gets a fresh override budget.
			this.overrideTarget = target;
			this.overrides = 0;
		}
		if (now != null && this.crossedBoundaryForward(this.lastNow, now.toLocalTime())) {
			// A boundary was crossed forward: the new window gets fresh budgets.
			this.failures = 0;
			this.overrides = 0;
		}
		this.lastNow = now == null ? null : now.toLocalTime();
		if (!controlEnabled) {
			return new Step(ModeScheduleState.DISABLED, target, null);
		}
		var gateStillOpen = mains || (Integer.valueOf(SUB).equals(this.handlerTarget) && this.onBatteryWithGrid(1));
		if (handlerState == SafeWriteHandler.State.QUEUED && !gateStillOpen && this.writeElement.withdraw()) {
			// The gate it was queued under is gone (mains lost, or for a SUB write on
			// battery the grid is gone) before the bridge took the write: withdrawn,
			// re-queued once the gate is confirmed again. If the bridge already took it,
			// the write is in flight and the handler is kept.
			this.handler = null;
			return new Step(ModeScheduleState.WAITING_FOR_MAINS, target, null, true);
		}
		if (handlerState == SafeWriteHandler.State.QUEUED && (target == null || !target.equals(this.handlerTarget))
				&& this.writeElement.withdraw()) {
			// The time became unknown or the target changed before the bridge took the
			// write: withdraw it and evaluate the current target in this cycle.
			this.handler = null;
			handlerState = null;
		}
		if (handlerState == SafeWriteHandler.State.QUEUED || handlerState == SafeWriteHandler.State.AWAITING_READBACK) {
			return new Step(ModeScheduleState.WRITE_PENDING, target, null);
		}
		if (target == null) {
			return new Step(ModeScheduleState.WAITING_FOR_DATA, null, null);
		}
		var sameTarget = target.equals(this.handlerTarget);
		if (handlerState == SafeWriteHandler.State.FAILED && sameTarget && this.failures > 0
				&& this.cyclesSinceDone < backoffCycles(this.failures, cooldown)) {
			return new Step(ModeScheduleState.WRITE_FAILED, target, null);
		}
		if (outputPriority == null || soc == null || soc < 0 || soc > 100) {
			return new Step(ModeScheduleState.WAITING_FOR_DATA, target, null);
		}
		if (outputPriority.intValue() == target.intValue()) {
			// The last handler is kept, so the cooldown still limits a re-correction if
			// the device is overridden again right after.
			return new Step(ModeScheduleState.IN_SYNC, target, null);
		}
		if (this.failures >= MAX_ATTEMPTS) {
			// Failure budget for this window spent: latched until the next boundary.
			return new Step(ModeScheduleState.WRITE_FAILED, target, null);
		}
		// Verified, but the read-back differs: stale or overridden (panel, app,
		// firmware). A re-correction backs off exponentially and is capped per window
		// and per day, so an override cannot spend the daily ceiling.
		var reCorrection = handlerState == SafeWriteHandler.State.VERIFIED && sameTarget;
		if (reCorrection) {
			if (this.overrides >= MAX_OVERRIDES_PER_WINDOW
					|| this.overridesOn(now.toLocalDate()) >= MAX_OVERRIDES_PER_DAY) {
				return new Step(ModeScheduleState.OVERRIDDEN, target, null);
			}
			if (this.cyclesSinceDone < backoffCycles(this.overrides + 1, cooldown)) {
				return new Step(ModeScheduleState.WRITE_PENDING, target, null);
			}
		}
		if (this.isDwelling(now, target)) {
			return new Step(ModeScheduleState.MIN_DWELL, target, null);
		}
		if (this.writesOn(now.toLocalDate()) >= MAX_DAILY_WRITES) {
			return new Step(ModeScheduleState.DAILY_WRITE_LIMIT, target, null);
		}
		if (target == SBU && !sbuAllowed) {
			return new Step(ModeScheduleState.BLOCKED_SBU_PRECONDITIONS, target, null);
		}
		// SBU (and any write) on mains: state 2, confirmed by fresh reads. SUB, the
		// direction back to the grid, also on battery (state 3) when the grid voltage
		// is present on every phase, confirmed by fresh reads.
		var mainsConfirmed = mains && this.freshMainsReads >= MAINS_CONFIRM_READS;
		var subOnBattery = target == SUB && this.onBatteryWithGrid(MAINS_CONFIRM_READS);
		if (!mainsConfirmed && !subOnBattery) {
			return new Step(ModeScheduleState.WAITING_FOR_MAINS, target, null);
		}
		if (this.cycles < STARTUP_HOLD_CYCLES) {
			return new Step(ModeScheduleState.STARTUP_HOLD, target, null);
		}
		var next = new SafeWriteHandler();
		next.queueIfChanged(outputPriority, target);
		this.handlerTarget = target;
		this.cyclesSinceDone = 0;
		this.outcomeCounted = false;
		this.queuedAt = now;
		if (reCorrection) {
			this.overrides++;
			this.overridesToday = this.overridesOn(now.toLocalDate()) + 1;
			this.overridesDate = now.toLocalDate();
		}
		this.handler = next;
		this.writeElement.arm(target);
		return new Step(ModeScheduleState.WRITE_PENDING, target, target);
	}

	// Counts each write's outcome once: a failure against the window's failure
	// budget, a success against the daily ceiling (and as the last switch).
	private void countOutcome(SafeWriteHandler.State state, LocalDateTime now) {
		if (this.outcomeCounted) {
			return;
		}
		if (state == SafeWriteHandler.State.FAILED) {
			this.failures++;
			this.outcomeCounted = true;
		} else if (state == SafeWriteHandler.State.VERIFIED) {
			this.outcomeCounted = true;
			// While the clock is gated, count against the time the write was queued.
			var at = now != null ? now : this.queuedAt;
			if (at != null) {
				var date = at.toLocalDate();
				this.writesToday = this.writesOn(date) + 1;
				this.writesDate = date;
				if (!this.handlerTarget.equals(this.lastSwitchTarget)) {
					this.lastSwitchAt = at;
					this.lastSwitchTarget = this.handlerTarget;
				}
			}
		}
	}

	private int overridesOn(LocalDate date) {
		return date.equals(this.overridesDate) ? this.overridesToday : 0;
	}

	private int writesOn(LocalDate date) {
		return date.equals(this.writesDate) ? this.writesToday : 0;
	}

	// The minimum dwell applies to SoC-driven switches only; the window switches at
	// the boundaries happen on time. Before the first verified switch it runs from
	// the activation, so a restart loop cannot switch faster than the dwell. A
	// clock that moved backwards never holds.
	private boolean isDwelling(LocalDateTime now, Integer target) {
		if (this.reason == ModeScheduleReason.WINDOW) {
			return false;
		}
		var since = this.lastSwitchAt != null ? this.lastSwitchAt : this.activatedAt;
		if (since == null || (this.lastSwitchAt != null && target.equals(this.lastSwitchTarget))) {
			return false;
		}
		return !now.isBefore(since) && now.isBefore(since.plus(this.minDwell));
	}

	/**
	 * Execute callback of the E204 write task.
	 *
	 * @param executeState the outcome of the write
	 */
	void onExecute(ExecuteState executeState) {
		var current = this.handler;
		if (current != null) {
			current.onExecute(executeState);
		}
	}

	/**
	 * Read-back of E204.
	 *
	 * @param actual the value read, or null
	 */
	void verify(Integer actual) {
		var current = this.handler;
		if (current != null) {
			current.verify(actual);
		}
	}

	/**
	 * The value of the write queued and not yet executed.
	 *
	 * @return the queued target, or null if no write is queued
	 */
	Integer queuedTarget() {
		var current = this.handler;
		return current != null && current.getState() == SafeWriteHandler.State.QUEUED ? this.handlerTarget : null;
	}

	SafeWriteHandler.State handlerState() {
		var current = this.handler;
		return current == null ? null : current.getState();
	}

	int failuresInWindow() {
		return this.failures;
	}

	int writesTodayForTest(LocalDate date) {
		return this.writesOn(date);
	}
}
