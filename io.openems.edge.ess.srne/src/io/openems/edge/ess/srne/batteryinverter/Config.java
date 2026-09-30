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

	// Output priority (source transfer) and BMS-comms enable. Raw mode numbers; the
	// value->mode mapping (which value is SBU, etc.) is unverified in the manuals and
	// must be confirmed on the unit before use. -1 leaves unchanged. See #71.
	@AttributeDefinition(name = "Output priority mode", description = "Raw target for E204 (source transfer, e.g. SBU); range 0..3; confirm the value->mode mapping on the unit; -1 leaves unchanged.")
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
	@AttributeDefinition(name = "Discharge start min SoC", description = "Skip the discharge window if SoC is below this percent at or after window start; -1 = off.")
	int dischargeStartMinSoc() default -1;

	@AttributeDefinition(name = "Discharge floor SoC", description = "Stop discharging for the rest of the window once SoC is at or below this percent; -1 = off.")
	int dischargeFloorSoc() default -1;

	@AttributeDefinition(name = "Schedule time zone", description = "Time zone used to evaluate the discharge window against the edge clock.")
	String scheduleTimeZone() default "Africa/Kampala";

	String webconsole_configurationFactory_nameHint() default "SRNE Battery-Inverter [{id}]";
}
