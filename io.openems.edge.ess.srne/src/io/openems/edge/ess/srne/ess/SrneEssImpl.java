package io.openems.edge.ess.srne.ess;

import static io.openems.edge.bridge.modbus.api.ElementToChannelConverter.SCALE_FACTOR_2;
import static org.osgi.service.component.annotations.ReferenceCardinality.MANDATORY;
import static org.osgi.service.component.annotations.ReferencePolicy.STATIC;
import static org.osgi.service.component.annotations.ReferencePolicyOption.GREEDY;

import java.util.function.Consumer;

import org.osgi.service.component.ComponentContext;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.ConfigurationPolicy;
import org.osgi.service.component.annotations.Deactivate;
import org.osgi.service.component.annotations.Reference;
import org.osgi.service.metatype.annotations.Designate;

import io.openems.common.exceptions.OpenemsException;
import io.openems.common.referencetarget.GenerateTargetsFromReferences;
import io.openems.edge.bridge.modbus.api.AbstractOpenemsModbusComponent;
import io.openems.edge.bridge.modbus.api.BridgeModbus;
import io.openems.edge.bridge.modbus.api.ModbusComponent;
import io.openems.edge.bridge.modbus.api.ModbusProtocol;
import io.openems.edge.bridge.modbus.api.element.DummyRegisterElement;
import io.openems.edge.bridge.modbus.api.element.SignedWordElement;
import io.openems.edge.bridge.modbus.api.element.UnsignedWordElement;
import io.openems.edge.bridge.modbus.api.task.FC3ReadRegistersTask;
import io.openems.edge.common.channel.value.Value;
import io.openems.edge.common.component.OpenemsComponent;
import io.openems.edge.common.taskmanager.Priority;
import io.openems.edge.ess.api.HybridEss;
import io.openems.edge.ess.api.SymmetricEss;
import io.openems.edge.ess.srne.common.Srne;

@Designate(ocd = Config.class, factory = true)
@Component(//
		name = "Srne.Ess", //
		immediate = true, //
		configurationPolicy = ConfigurationPolicy.REQUIRE //
)
@GenerateTargetsFromReferences("Modbus")
public class SrneEssImpl extends AbstractOpenemsModbusComponent
		implements SrneEss, Srne, HybridEss, SymmetricEss, ModbusComponent, OpenemsComponent {

	@Override
	@Reference(//
			policy = STATIC, policyOption = GREEDY, cardinality = MANDATORY, //
			target = "(&(id=${config.modbus_id})(enabled=true))")
	protected void setModbus(BridgeModbus modbus) {
		super.setModbus(modbus);
	}

	public SrneEssImpl() {
		super(//
				OpenemsComponent.ChannelId.values(), //
				ModbusComponent.ChannelId.values(), //
				SymmetricEss.ChannelId.values(), //
				HybridEss.ChannelId.values(), //
				Srne.ChannelId.values(), //
				SrneEss.ChannelId.values() //
		);
	}

	@Activate
	private void activate(ComponentContext context, Config config) throws OpenemsException {
		super.activate(context, config.id(), config.alias(), config.enabled(), config.modbusUnitId());
		this._setCapacity(config.capacity());
		this._setMaxApparentPower(config.maxApparentPower());

		/*
		 * Validated on the ASP48120SH3: SRNE reports battery current as positive
		 * while discharging and negative while charging. This matches the OpenEMS
		 * active-power sign convention.
		 */
		final Consumer<Value<Integer>> updateActivePower = ignore -> {
			var voltage = this.getBatteryVoltageChannel().getNextValue().get();
			var current = this.getBatteryCurrentChannel().getNextValue().get();
			if (voltage == null || current == null) {
				this._setActivePower(null);
				this._setDcDischargePower(null);
				return;
			}
			var activePower = (int) Math.round(voltage * (double) current / 1_000_000D);
			this._setActivePower(activePower);
			this._setDcDischargePower(activePower);
		};
		this.getBatteryVoltageChannel().onSetNextValue(updateActivePower);
		this.getBatteryCurrentChannel().onSetNextValue(updateActivePower);
	}

	@Override
	@Deactivate
	protected void deactivate() {
		super.deactivate();
	}

	@Override
	protected ModbusProtocol defineModbusProtocol() {
		return new ModbusProtocol(this, //
				new FC3ReadRegistersTask(0x0100, Priority.HIGH, //
						m(SymmetricEss.ChannelId.SOC, new UnsignedWordElement(0x0100)), //
						m(SrneEss.ChannelId.BATTERY_VOLTAGE, new UnsignedWordElement(0x0101), SCALE_FACTOR_2), //
						m(SrneEss.ChannelId.BATTERY_CURRENT, new SignedWordElement(0x0102), SCALE_FACTOR_2), //
						new DummyRegisterElement(0x0103)));
	}

	@Override
	public String debugLog() {
		return "SoC:" + this.getSoc().asString() //
				+ "|L:" + this.getActivePower().asString() //
				+ "|DC:" + this.getDcDischargePower().asString();
	}

	@Override
	public Integer getSurplusPower() {
		return null;
	}
}
