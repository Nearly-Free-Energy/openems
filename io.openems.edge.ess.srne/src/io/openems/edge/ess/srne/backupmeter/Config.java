package io.openems.edge.ess.srne.backupmeter;

import org.osgi.service.metatype.annotations.AttributeDefinition;
import org.osgi.service.metatype.annotations.ObjectClassDefinition;

import io.openems.edge.ess.srne.SrneConstants;

@ObjectClassDefinition(//
		name = "SRNE Backup-Meter", //
		description = "Reads backup-output measurements from the SRNE ASP48120SH3.")
@interface Config {

	@AttributeDefinition(name = "Component-ID", description = "Unique ID of this Component")
	String id() default "meter2";

	@AttributeDefinition(name = "Alias", description = "Human-readable name of this Component; defaults to Component-ID")
	String alias() default "";

	@AttributeDefinition(name = "Is enabled?", description = "Is this Component enabled?")
	boolean enabled() default true;

	@AttributeDefinition(name = "Modbus-ID", description = "ID of Modbus bridge.")
	String modbus_id() default "modbus0";

	@AttributeDefinition(name = "Modbus Unit-ID", description = "The Unit-ID of the Modbus device.")
	int modbusUnitId() default SrneConstants.DEFAULT_UNIT_ID;

	String webconsole_configurationFactory_nameHint() default "SRNE Backup-Meter [{id}]";
}
