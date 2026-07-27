package io.openems.edge.ess.srne.batteryinverter;

import org.osgi.service.metatype.annotations.AttributeDefinition;
import org.osgi.service.metatype.annotations.ObjectClassDefinition;

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

	String webconsole_configurationFactory_nameHint() default "SRNE Battery-Inverter [{id}]";
}
