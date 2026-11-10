package io.openems.edge.ess.srne.charger;

import static io.openems.edge.bridge.modbus.api.ElementToChannelConverter.SCALE_FACTOR_2;
import static io.openems.edge.bridge.modbus.api.ElementToChannelConverter.SET_NULL_FOR_DEFAULT;
import static io.openems.edge.bridge.modbus.api.ElementToChannelConverter.chain;
import static org.osgi.service.component.annotations.ReferenceCardinality.MANDATORY;
import static org.osgi.service.component.annotations.ReferencePolicy.STATIC;
import static org.osgi.service.component.annotations.ReferencePolicyOption.GREEDY;

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
import io.openems.edge.bridge.modbus.api.ElementToChannelConverter;
import io.openems.edge.bridge.modbus.api.ModbusComponent;
import io.openems.edge.bridge.modbus.api.ModbusProtocol;
import io.openems.edge.bridge.modbus.api.element.DummyRegisterElement;
import io.openems.edge.bridge.modbus.api.element.UnsignedWordElement;
import io.openems.edge.bridge.modbus.api.task.FC3ReadRegistersTask;
import io.openems.edge.common.component.OpenemsComponent;
import io.openems.edge.common.taskmanager.Priority;
import io.openems.edge.ess.dccharger.api.EssDcCharger;
import io.openems.edge.ess.srne.common.Srne;

/**
 * Read-only PV (MPPT) input of the SRNE ASP48120SH3, exposed as an
 * {@link EssDcCharger} so that {@code _sum} fills the DC production channels.
 *
 * <p>
 * This is its own Modbus component (same device and Unit-ID as the ESS) on
 * purpose: if the inverter rejects one of the PV addresses, only this
 * component backs off and the battery reads of the ESS keep running.
 */
@Designate(ocd = Config.class, factory = true)
@Component(//
		name = "Srne.Charger", //
		immediate = true, //
		configurationPolicy = ConfigurationPolicy.REQUIRE //
)
@GenerateTargetsFromReferences("Modbus")
public class SrneChargerImpl extends AbstractOpenemsModbusComponent
		implements SrneCharger, Srne, EssDcCharger, ModbusComponent, OpenemsComponent {

	/** A word register reading 0xFFFF is treated as "no value". */
	private static final ElementToChannelConverter WORD_DIRECT = SET_NULL_FOR_DEFAULT(0xFFFF);
	/** 0.1 unit to milli-unit (V to mV, A to mA, kWh to Wh), 0xFFFF = no value. */
	private static final ElementToChannelConverter WORD_SCALE_2 = chain(SET_NULL_FOR_DEFAULT(0xFFFF),
			SCALE_FACTOR_2);

	@Override
	@Reference(//
			policy = STATIC, policyOption = GREEDY, cardinality = MANDATORY, //
			target = "(&(id=${config.modbus_id})(enabled=true))")
	protected void setModbus(BridgeModbus modbus) {
		super.setModbus(modbus);
	}

	public SrneChargerImpl() {
		super(//
				OpenemsComponent.ChannelId.values(), //
				ModbusComponent.ChannelId.values(), //
				EssDcCharger.ChannelId.values(), //
				Srne.ChannelId.values(), //
				SrneCharger.ChannelId.values() //
		);

		// ActualPower = PV1 + PV2; Current = PV1 + PV2; Voltage = PV1 (the string)
		this.getPv1PowerChannel().onSetNextValue(ignore -> this.updateActualPower());
		this.getPv2PowerChannel().onSetNextValue(ignore -> this.updateActualPower());
		this.getPv1CurrentChannel().onSetNextValue(ignore -> this.updateCurrent());
		this.getPv2CurrentChannel().onSetNextValue(ignore -> this.updateCurrent());
		this.getPv1VoltageChannel().onSetNextValue(value -> this._setVoltage(value.get()));
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

	private void updateActualPower() {
		this._setActualPower(sumOrNull(//
				this.getPv1PowerChannel().getNextValue().get(), //
				this.getPv2PowerChannel().getNextValue().get()));
	}

	private void updateCurrent() {
		this._setCurrent(sumOrNull(//
				this.getPv1CurrentChannel().getNextValue().get(), //
				this.getPv2CurrentChannel().getNextValue().get()));
	}

	/**
	 * Adds two MPPT values. Unlike {@code IntUtils.sumInteger} a missing input
	 * gives {@code null}, so a failed read is never reported as 0 W.
	 *
	 * @param mppt1 the MPPT 1 value
	 * @param mppt2 the MPPT 2 value
	 * @return the sum, or null if either value is null
	 */
	protected static Integer sumOrNull(Integer mppt1, Integer mppt2) {
		if (mppt1 == null || mppt2 == null) {
			return null;
		}
		return mppt1 + mppt2;
	}

	@Override
	protected ModbusProtocol defineModbusProtocol() {
		return new ModbusProtocol(this, //
				/*
				 * PV input, SRNE protocol V1.96 P01. Live-read on gw-pi2 (27 Oct 2026, 09:41
				 * EAT): 0x0107-0x0109 = 3660/12/470 (366.0 V, 1.2 A, 470 W); PV2
				 * 0x010F-0x0111 answers 0 (not connected). One request for the whole block;
				 * 0x010A-0x010E are not PV input values (0x010E is total charge power, PV +
				 * mains).
				 */
				new FC3ReadRegistersTask(0x0107, Priority.HIGH, //
						m(SrneCharger.ChannelId.PV1_VOLTAGE, new UnsignedWordElement(0x0107), WORD_SCALE_2), //
						m(SrneCharger.ChannelId.PV1_CURRENT, new UnsignedWordElement(0x0108), WORD_SCALE_2), //
						m(SrneCharger.ChannelId.PV1_POWER, new UnsignedWordElement(0x0109), WORD_DIRECT), //
						new DummyRegisterElement(0x010A, 0x010E), //
						m(SrneCharger.ChannelId.PV2_VOLTAGE, new UnsignedWordElement(0x010F), WORD_SCALE_2), //
						m(SrneCharger.ChannelId.PV2_CURRENT, new UnsignedWordElement(0x0110), WORD_SCALE_2), //
						m(SrneCharger.ChannelId.PV2_POWER, new UnsignedWordElement(0x0111), WORD_DIRECT)), //
				/*
				 * PV energy today, SRNE protocol V1.96 P08: 0xF02F, 0.1 kWh. Answered live on
				 * gw-pi2 (27 Oct 2026). A single-register LOW task. Note: the Modbus bridge
				 * backs off per component, so a rejected 0xF02F would also pause the PV power
				 * task above (but never the ESS battery reads).
				 *
				 * ActualEnergy is deliberately left unset (null): the accumulated counter
				 * 0xF038-0xF039 is not validated live yet (follow-up PR), and power is not
				 * integrated.
				 */
				new FC3ReadRegistersTask(0xF02F, Priority.LOW, //
						m(SrneCharger.ChannelId.PV_ENERGY_TODAY, new UnsignedWordElement(0xF02F), WORD_SCALE_2)));
	}

	@Override
	public String debugLog() {
		return "L:" + this.getActualPower().asString();
	}
}
