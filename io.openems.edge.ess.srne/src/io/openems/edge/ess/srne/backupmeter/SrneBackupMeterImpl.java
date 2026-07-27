package io.openems.edge.ess.srne.backupmeter;

import static io.openems.edge.bridge.modbus.api.ElementToChannelConverter.SCALE_FACTOR_1;
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
import io.openems.common.types.MeterType;
import io.openems.edge.bridge.modbus.api.AbstractOpenemsModbusComponent;
import io.openems.edge.bridge.modbus.api.BridgeModbus;
import io.openems.edge.bridge.modbus.api.ModbusComponent;
import io.openems.edge.bridge.modbus.api.ModbusProtocol;
import io.openems.edge.bridge.modbus.api.element.UnsignedWordElement;
import io.openems.edge.bridge.modbus.api.task.FC3ReadRegistersTask;
import io.openems.edge.common.channel.IntegerReadChannel;
import io.openems.edge.common.channel.value.Value;
import io.openems.edge.common.component.OpenemsComponent;
import io.openems.edge.common.taskmanager.Priority;
import io.openems.edge.ess.srne.common.Srne;
import io.openems.edge.meter.api.ElectricityMeter;

@Designate(ocd = Config.class, factory = true)
@Component(//
		name = "Srne.Backup-Meter", //
		immediate = true, //
		configurationPolicy = ConfigurationPolicy.REQUIRE, //
		property = "type=CONSUMPTION_METERED" //
)
@GenerateTargetsFromReferences("Modbus")
public class SrneBackupMeterImpl extends AbstractOpenemsModbusComponent
		implements SrneBackupMeter, Srne, ElectricityMeter, ModbusComponent, OpenemsComponent {

	@Override
	@Reference(//
			policy = STATIC, policyOption = GREEDY, cardinality = MANDATORY, //
			target = "(&(id=${config.modbus_id})(enabled=true))")
	protected void setModbus(BridgeModbus modbus) {
		super.setModbus(modbus);
	}

	public SrneBackupMeterImpl() {
		super(//
				OpenemsComponent.ChannelId.values(), //
				ModbusComponent.ChannelId.values(), //
				ElectricityMeter.ChannelId.values(), //
				Srne.ChannelId.values(), //
				SrneBackupMeter.ChannelId.values() //
		);

		ElectricityMeter.calculateAverageVoltageFromPhases(this);
		ElectricityMeter.calculateSumCurrentFromPhases(this);
		ElectricityMeter.calculateSumActivePowerFromPhases(this);
		ElectricityMeter.calculateSumReactivePowerFromPhases(this);

		var apparentPowerL1 = this.<IntegerReadChannel>channel(SrneBackupMeter.ChannelId.APPARENT_POWER_L1);
		final Consumer<Value<Integer>> calculateReactivePowerL1 = ignore -> {
			var apparentPower = apparentPowerL1.getNextValue().get();
			var activePower = this.getActivePowerL1Channel().getNextValue().get();
			if (apparentPower == null || activePower == null) {
				this._setReactivePowerL1(null);
				return;
			}
			var reactivePowerSquared = (double) apparentPower * apparentPower - (double) activePower * activePower;
			this._setReactivePowerL1((int) Math.round(Math.sqrt(Math.max(0D, reactivePowerSquared))));
		};
		apparentPowerL1.onSetNextValue(calculateReactivePowerL1);
		this.getActivePowerL1Channel().onSetNextValue(calculateReactivePowerL1);
	}

	@Activate
	private void activate(ComponentContext context, Config config) throws OpenemsException {
		super.activate(context, config.id(), config.alias(), config.enabled(), config.modbusUnitId());
	}

	@Override
	@Deactivate
	protected void deactivate() {
		super.deactivate();
	}

	@Override
	protected ModbusProtocol defineModbusProtocol() {
		return new ModbusProtocol(this, //
				new FC3ReadRegistersTask(0x0216, Priority.HIGH, //
						m(ElectricityMeter.ChannelId.VOLTAGE_L1, new UnsignedWordElement(0x0216), SCALE_FACTOR_2), //
						m(ElectricityMeter.ChannelId.CURRENT_L1, new UnsignedWordElement(0x0217), SCALE_FACTOR_2), //
						m(ElectricityMeter.ChannelId.FREQUENCY, new UnsignedWordElement(0x0218), SCALE_FACTOR_1)), //
				new FC3ReadRegistersTask(0x022C, Priority.HIGH, //
						m(ElectricityMeter.ChannelId.VOLTAGE_L2, new UnsignedWordElement(0x022C), SCALE_FACTOR_2), //
						m(ElectricityMeter.ChannelId.VOLTAGE_L3, new UnsignedWordElement(0x022D), SCALE_FACTOR_2), //
						m(ElectricityMeter.ChannelId.CURRENT_L2, new UnsignedWordElement(0x022E), SCALE_FACTOR_2), //
						m(ElectricityMeter.ChannelId.CURRENT_L3, new UnsignedWordElement(0x022F), SCALE_FACTOR_2)), //
				new FC3ReadRegistersTask(0x021B, Priority.HIGH, //
						m(ElectricityMeter.ChannelId.ACTIVE_POWER_L1, new UnsignedWordElement(0x021B)), //
						m(SrneBackupMeter.ChannelId.APPARENT_POWER_L1, new UnsignedWordElement(0x021C))), //
				new FC3ReadRegistersTask(0x0232, Priority.HIGH, //
						m(ElectricityMeter.ChannelId.ACTIVE_POWER_L2, new UnsignedWordElement(0x0232)), //
						m(ElectricityMeter.ChannelId.ACTIVE_POWER_L3, new UnsignedWordElement(0x0233)), //
						m(ElectricityMeter.ChannelId.REACTIVE_POWER_L2, new UnsignedWordElement(0x0234)), //
						m(ElectricityMeter.ChannelId.REACTIVE_POWER_L3, new UnsignedWordElement(0x0235))));
	}

	@Override
	public MeterType getMeterType() {
		return MeterType.CONSUMPTION_METERED;
	}

	@Override
	public String debugLog() {
		return "V:" + this.getVoltage().asString() //
				+ "|I:" + this.getCurrent().asString() //
				+ "|P:" + this.getActivePower().asString() //
				+ "|Q:" + this.getReactivePower().asString() //
				+ "|f:" + this.getFrequency().asString();
	}
}
