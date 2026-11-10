package io.openems.edge.ess.srne.batteryinverter;

import org.osgi.service.metatype.annotations.AttributeDefinition;
import org.osgi.service.metatype.annotations.ObjectClassDefinition;
import org.osgi.service.metatype.annotations.Option;

import io.openems.edge.common.startstop.StartStopConfig;
import io.openems.edge.ess.srne.SrneConstants;

@ObjectClassDefinition(//
		name = "SRNE Battery-Inverter", //
		description = "Implements the off-grid battery-inverter nature of the SRNE ASP48120SH3.")
@interface Config {

	@AttributeDefinition(name = "Component-ID", description = "Unique ID of this Component")
	String id() default "batteryInverter0";

	@AttributeDefinition(name = "Alias", description = "Human-readable name of this Component; defaults to Component-ID")
	String alias() default "";

	@AttributeDefinition(name = "Is enabled?", description = "Is this Component enabled?")
	boolean enabled() default true;

	@AttributeDefinition(name = "Start/stop behaviour?", description = "Should this Component be forced to start or stop?")
	StartStopConfig startStop() default StartStopConfig.AUTO;

	@AttributeDefinition(name = "Modbus-ID", description = "ID of Modbus bridge.")
	String modbus_id() default "modbus0";

	@AttributeDefinition(name = "Modbus Unit-ID", description = "The Unit-ID of the Modbus device.")
	int modbusUnitId() default SrneConstants.DEFAULT_UNIT_ID;

	@AttributeDefinition(name = "Maximum apparent power", description = "Maximum inverter apparent power in [VA].")
	int maxApparentPower() default 12_000;

	@AttributeDefinition(name = "Enable settings writes", description = "Safety gate for FC16 settings writes. Disabled by default.")
	boolean controlEnabled() default false;

	@AttributeDefinition(name = "Discharge cutoff SoC", description = "Target for E00F in percent; -1 leaves unchanged.")
	int dischargeCutoffSoc() default -1;

	@AttributeDefinition(name = "Stop-charge current", description = "Target for E01C in amperes; -1 leaves unchanged.")
	int stopChargeCurrent() default -1;

	@AttributeDefinition(name = "Stop-charge SoC", description = "Target for E01D in percent; -1 leaves unchanged.")
	int stopChargeSoc() default -1;

	@AttributeDefinition(name = "Low-SoC alarm", description = "Target for E01E in percent; -1 leaves unchanged.")
	int lowSocAlarm() default -1;

	@AttributeDefinition(name = "Switch to line SoC", description = "Target for E01F in percent; -1 leaves unchanged.")
	int switchToLineSoc() default -1;

	@AttributeDefinition(name = "Switch to battery SoC", description = "Target for E020 in percent; -1 leaves unchanged.")
	int switchToBatterySoc() default -1;

	@AttributeDefinition(name = "AC charge-current limit", description = "Target for E205 in amperes; -1 leaves unchanged.")
	int acChargeCurrentLimit() default -1;

	@AttributeDefinition(name = "Maximum charge-current limit", description = "Target for E20A in amperes; -1 leaves unchanged.")
	int maxChargeCurrentLimit() default -1;

	// Output priority (source transfer) and BMS-comms enable. Raw mode numbers. E204:
	// 0 SOL, 1 UTI, 2 SBU, 3 SUB (SRNE manual; confirmed on gw-pi2 v29,
	// microgrid-gateway #97, 4 Nov 2026). -1 leaves unchanged. See #71.
	@AttributeDefinition(name = "Output priority mode", description = "Raw target for E204 (source transfer): 0 SOL, 1 UTI, 2 SBU, 3 SUB; -1 leaves unchanged. Must be -1 when the daily mode schedule is enabled (one owner of E204), otherwise the schedule reports CONFIG_INVALID and stays off.")
	int outputPriority() default -1;

	@AttributeDefinition(name = "BMS communication enable", description = "Raw target for E215 (BMS comms); range 0..2; confirm the value->mode mapping on the unit; -1 leaves unchanged.")
	int bmsCommunication() default -1;

	// TOU schedule windows for time-of-use arbitrage. Value is encoded hour*256+min
	// (e.g. 00:00 = 0, 06:00 = 1536, 18:00 = 4608, 23:59 = 5947). -1 leaves the
	// register unchanged. Charge off-peak, discharge at peak.
	@AttributeDefinition(name = "Charge window 1 start", description = "Target for E026, encoded hour*256+min; -1 leaves unchanged.")
	int chargeWindow1Start() default -1;

	@AttributeDefinition(name = "Charge window 1 stop", description = "Target for E027, encoded hour*256+min; -1 leaves unchanged.")
	int chargeWindow1Stop() default -1;

	// Timed-charge stop SoC for charge window 1 (E03B, protocol V1.94+). The night
	// charge in window 1 stops once the battery reaches this SoC, so a wide off-peak
	// window (e.g. 00:00-06:00) charges in whatever minutes the grid is present.
	// -1 leaves the register unmanaged (and unread). Valid 10..100.
	@AttributeDefinition(name = "Charge window 1 stop SoC", description = "Target for E03B (timed-charge stop SoC of charge window 1) in percent; range 10..100; -1 leaves unchanged and does not read the register.")
	int chargeWindow1StopSoc() default -1;

	@AttributeDefinition(name = "Discharge window 1 start", description = "Target for E02D, encoded hour*256+min; -1 leaves unchanged.")
	int dischargeWindow1Start() default -1;

	@AttributeDefinition(name = "Discharge window 1 stop", description = "Target for E02E, encoded hour*256+min; -1 leaves unchanged.")
	int dischargeWindow1Stop() default -1;

	// Schedule enable flags. Written last, only after the matching window pair is
	// read-back verified, so a mis-set window can never leave a schedule armed. 0
	// disables, 1 enables, -1 leaves unchanged. enable=1 requires a configured window.
	@AttributeDefinition(name = "Charge schedule enable", description = "Target for E02C; enables the charge time-schedule; leave unchanged by default.", options = {
			@Option(label = "Leave unchanged", value = "-1"), //
			@Option(label = "Disabled", value = "0"), //
			@Option(label = "Enabled", value = "1") })
	int chargeScheduleEnable() default -1;

	@AttributeDefinition(name = "Discharge schedule enable", description = "Target for E033; enables the discharge time-schedule; leave unchanged by default.", options = {
			@Option(label = "Leave unchanged", value = "-1"), //
			@Option(label = "Disabled", value = "0"), //
			@Option(label = "Enabled", value = "1") })
	int dischargeScheduleEnable() default -1;

	// Automatic discharge rules, evaluated against the edge clock (the inverter RTC
	// is not readable). -1 disables a rule; both -1 keeps today's behaviour.
	@AttributeDefinition(name = "Discharge start min SoC", description = "Skip the discharge window if SoC is below this percent at or after window start; range 0..99 and above the floor; -1 = off.")
	int dischargeStartMinSoc() default -1;

	@AttributeDefinition(name = "Discharge floor SoC", description = "Stop discharging for the rest of the window once SoC is at or below this percent; range 0..99 and below the start threshold; -1 = off.")
	int dischargeFloorSoc() default -1;

	// Daily output-priority schedule (E204: 0 SOL, 1 UTI, 2 SBU, 3 SUB). Evening
	// priority from eveningStart until daytimeStart (crossing midnight), daytime
	// priority otherwise. Writes are read-back verified and need controlEnabled and
	// machine state 2 (mains). Off by default.
	@AttributeDefinition(name = "Mode schedule enabled", description = "Switch E204 between a daytime and an evening output priority every day. Needs controlEnabled; outputPriority must be -1. Off by default.")
	boolean modeScheduleEnabled() default false;

	@AttributeDefinition(name = "Daytime output priority", description = "E204 value from daytimeStart to eveningStart: 3 = SUB, 2 = SBU (only 2 or 3 allowed).", options = {
			@Option(label = "SBU", value = "2"), //
			@Option(label = "SUB", value = "3") })
	int daytimePriority() default 3;

	@AttributeDefinition(name = "Evening output priority", description = "E204 value from eveningStart to daytimeStart: 2 = SBU, 3 = SUB (only 2 or 3 allowed, and not the daytime value). SBU is written only when switchToLineSoc, switchToBatterySoc and bmsCommunication are configured, coherent and settled.", options = {
			@Option(label = "SBU", value = "2"), //
			@Option(label = "SUB", value = "3") })
	int eveningPriority() default 2;

	@AttributeDefinition(name = "Evening start", description = "Local time HH:MM when the evening output priority starts.")
	String eveningStart() default "18:00";

	@AttributeDefinition(name = "Daytime start", description = "Local time HH:MM when the daytime output priority starts; must differ from eveningStart.")
	String daytimeStart() default "00:30";

	// Daytime SoC rule: in SUB the SRNE curtails PV once the battery is nearly full,
	// so the daytime target becomes SBU above an upper SoC and SUB again below a
	// lower one (hysteresis). Both -1 = off (default).
	@AttributeDefinition(name = "Daytime SBU above SoC", description = "In the daytime window, switch to the evening priority (SBU) once SoC is at or above this percent; 10..100 and above daytimeSbuBelowSoc; -1 = off (set both or neither).")
	int daytimeSbuAboveSoc() default -1;

	@AttributeDefinition(name = "Daytime SBU below SoC", description = "In the daytime window, return to the daytime priority (SUB) once SoC is below this percent; 10..100 and below daytimeSbuAboveSoc; -1 = off (set both or neither).")
	int daytimeSbuBelowSoc() default -1;

	@AttributeDefinition(name = "Minimum dwell minutes", description = "A SoC-driven mode switch waits at least this many minutes after the last verified switch; 0..1440.")
	int minDwellMinutes() default 10;

	@AttributeDefinition(name = "Grid present min voltage", description = "Mode schedule: a SUB write (back to the grid) is also allowed while the unit runs on battery (machine state 3) if the grid-input voltage is above this on all three phases for 3 fresh reads; 100..260 V (e.g. 180). Default -1 = off: SUB writes only in state 2 and no grid-voltage reads. Enable only after one supervised live state-3 SUB write. SBU writes always need state 2.")
	int gridPresentMinVoltage() default -1;

	@AttributeDefinition(name = "Schedule time zone", description = "Time zone used to evaluate the discharge window and the mode schedule against the edge clock.")
	String scheduleTimeZone() default "Africa/Kampala";

	String webconsole_configurationFactory_nameHint() default "SRNE Battery-Inverter [{id}]";
}
