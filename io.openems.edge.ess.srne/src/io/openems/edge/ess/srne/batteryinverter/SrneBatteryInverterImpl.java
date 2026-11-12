package io.openems.edge.ess.srne.batteryinverter;

import static io.openems.edge.bridge.modbus.api.ElementToChannelConverter.SCALE_FACTOR_2;
import static org.osgi.service.component.annotations.ReferenceCardinality.MANDATORY;
import static org.osgi.service.component.annotations.ReferencePolicy.STATIC;
import static org.osgi.service.component.annotations.ReferencePolicyOption.GREEDY;

import java.time.Clock;
import java.time.DateTimeException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import org.osgi.service.component.ComponentContext;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.ConfigurationPolicy;
import org.osgi.service.component.annotations.Deactivate;
import org.osgi.service.component.annotations.Reference;
import org.osgi.service.event.Event;
import org.osgi.service.event.EventHandler;
import org.osgi.service.event.propertytypes.EventTopics;
import org.osgi.service.metatype.annotations.Designate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.openems.common.exceptions.OpenemsError.OpenemsNamedException;
import io.openems.common.exceptions.OpenemsException;
import io.openems.common.referencetarget.GenerateTargetsFromReferences;
import io.openems.edge.battery.api.Battery;
import io.openems.edge.batteryinverter.api.ManagedSymmetricBatteryInverter;
import io.openems.edge.batteryinverter.api.OffGridBatteryInverter;
import io.openems.edge.batteryinverter.api.SymmetricBatteryInverter;
import io.openems.edge.bridge.modbus.api.AbstractOpenemsModbusComponent;
import io.openems.edge.bridge.modbus.api.BridgeModbus;
import io.openems.edge.bridge.modbus.api.ModbusComponent;
import io.openems.edge.bridge.modbus.api.ModbusProtocol;
import io.openems.edge.bridge.modbus.api.element.SignedWordElement;
import io.openems.edge.bridge.modbus.api.element.UnsignedWordElement;
import io.openems.edge.bridge.modbus.api.task.FC3ReadRegistersTask;
import io.openems.edge.bridge.modbus.api.task.FC16WriteRegistersTask;
import io.openems.edge.common.channel.IntegerReadChannel;
import io.openems.edge.common.channel.value.Value;
import io.openems.edge.common.component.ComponentManager;
import io.openems.edge.common.component.OpenemsComponent;
import io.openems.edge.common.event.EdgeEventConstants;
import io.openems.edge.common.startstop.StartStop;
import io.openems.edge.common.startstop.StartStoppable;
import io.openems.edge.common.taskmanager.Priority;
import io.openems.edge.ess.srne.batteryinverter.statemachine.StateMachine;
import io.openems.edge.ess.srne.batteryinverter.statemachine.StateMachine.State;
import io.openems.edge.ess.srne.common.Srne;
import io.openems.edge.ess.srne.common.enums.MachineState;

@Designate(ocd = Config.class, factory = true)
@Component(//
		name = "Srne.BatteryInverter", //
		immediate = true, //
		configurationPolicy = ConfigurationPolicy.REQUIRE //
)
@EventTopics({ //
		EdgeEventConstants.TOPIC_CYCLE_AFTER_PROCESS_IMAGE //
})
@GenerateTargetsFromReferences("Modbus")
public class SrneBatteryInverterImpl extends AbstractOpenemsModbusComponent
		implements SrneBatteryInverter, Srne, OffGridBatteryInverter, ManagedSymmetricBatteryInverter,
		SymmetricBatteryInverter, ModbusComponent, OpenemsComponent, StartStoppable, EventHandler {
	private static final int READBACK_TIMEOUT_CYCLES = 30;
	private static final String DEFAULT_SCHEDULE_ZONE = "Africa/Kampala";
	// A failed discharge-window write is retried after a cooldown and never given
	// up on until the target changes. A failed suppress (enable=0) is retried fast
	// for MAX_FAST_SUPPRESS_RETRIES attempts, then at the slow cadence; a failed
	// restore (enable=1) always uses the slow cadence.
	private static final int FAILED_RETRY_COOLDOWN_CYCLES = 60;
	private static final int SUPPRESS_RETRY_COOLDOWN_CYCLES = 8;
	private static final int MAX_FAST_SUPPRESS_RETRIES = 3;
	private static final int MAX_RULE_SOC = 99;
	private final Logger log = LoggerFactory.getLogger(SrneBatteryInverterImpl.class);

	private final AtomicReference<TargetGridMode> targetGridMode = new AtomicReference<>(TargetGridMode.GO_ON_GRID);
	private final AtomicReference<StartStop> startStopTarget = new AtomicReference<>(StartStop.UNDEFINED);
	private final UnsignedWordElement dischargeCutoffSocWrite = new UnsignedWordElement(0xE00F);
	private final UnsignedWordElement stopChargeCurrentWrite = new UnsignedWordElement(0xE01C);
	private final UnsignedWordElement stopChargeSocWrite = new UnsignedWordElement(0xE01D);
	private final UnsignedWordElement lowSocAlarmWrite = new UnsignedWordElement(0xE01E);
	private final UnsignedWordElement switchToLineSocWrite = new UnsignedWordElement(0xE01F);
	private final UnsignedWordElement switchToBatterySocWrite = new UnsignedWordElement(0xE020);
	private final UnsignedWordElement acChargeCurrentLimitWrite = new UnsignedWordElement(0xE205);
	private final UnsignedWordElement maxChargeCurrentLimitWrite = new UnsignedWordElement(0xE20A);
	// JUSTIFICATION-A3: output priority (E204, source transfer) + BMS-comms enable
	// (E215) added to the guarded write allow-list (#71) so SBU mode + BMS-SoC switching
	// are settable from OpenEMS. Same scalar guarded path as the SoC/current settings.
	// E204 mapping (SRNE manual, confirmed on gw-pi2 v29, microgrid-gateway #97,
	// 4 Nov 2026): 0 SOL, 1 UTI, 2 SBU, 3 SUB.
	private final UnsignedWordElement outputPriorityWrite = new UnsignedWordElement(0xE204);
	private final UnsignedWordElement bmsCommunicationWrite = new UnsignedWordElement(0xE215);
	// Timed-charge stop SoC of charge window 1 (E03B). Its read and write tasks are
	// added only when chargeWindow1StopSoc is configured, so default traffic is unchanged.
	private final UnsignedWordElement chargeWindow1StopSocWrite = new UnsignedWordElement(0xE03B);
	private final SafeWriteHandler[] writeHandlers = { new SafeWriteHandler(), new SafeWriteHandler(),
			new SafeWriteHandler(), new SafeWriteHandler(), new SafeWriteHandler(), new SafeWriteHandler(),
			new SafeWriteHandler(), new SafeWriteHandler(), new SafeWriteHandler(), new SafeWriteHandler(),
			new SafeWriteHandler() };
	/*
	 * TOU schedule windows for arbitrage. Each window is a coherent (start, stop,
	 * enable) unit: the contiguous start/stop pair (charge 0xE026/0xE027, discharge
	 * 0xE02D/0xE02E, encoded hour*256+min) is written as two single-register FC16
	 * requests (a two-register write was mis-stored on gw-pi2) and read-back
	 * verified; only then is the enable flag (charge 0xE02C, discharge
	 * 0xE033) written. A failure never arms the schedule. See ScheduleWindow.
	 */
	private final ScheduleWindow chargeWindow = new ScheduleWindow(0xE026, 0xE02C, "CHARGE");
	private final ScheduleWindow dischargeWindow = new ScheduleWindow(0xE02D, 0xE033, "DISCHARGE");
	private Config config;
	private DischargeGuard dischargeGuard = new DischargeGuard(-1, -1);
	private ModeSchedule modeSchedule = ModeSchedule.fromConfig(false, ModeSchedule.SUB, ModeSchedule.SBU, null,
			null, -1);
	private ModeScheduleState lastModeScheduleState;
	private ZoneId scheduleZone = ZoneId.of(DEFAULT_SCHEDULE_ZONE);
	private Integer appliedDischargeEnable;
	private int failedCycles;
	private int suppressRetries;
	private boolean driftCorrectedUnsuppressed;
	private int driftCooldown;
	private boolean mismatchLogged;
	// One-shot audit flag: log at most once that the output-priority write is held
	// pending its prerequisites (reconcile runs every cycle).
	private boolean outputPriorityHeldLogged;

	@Override
	@Reference(//
			policy = STATIC, policyOption = GREEDY, cardinality = MANDATORY, //
			target = "(&(id=${config.modbus_id})(enabled=true))")
	protected void setModbus(BridgeModbus modbus) {
		super.setModbus(modbus);
	}

	@Reference
	private ComponentManager componentManager;

	// Deliberately not a ClockProvider: OSGi would register this component as the
	// edge's clock source and the component manager would call back into it forever.
	private Clock clock() {
		return this.componentManager.getClock();
	}

	public SrneBatteryInverterImpl() {
		super(//
				OpenemsComponent.ChannelId.values(), //
				ModbusComponent.ChannelId.values(), //
				SymmetricBatteryInverter.ChannelId.values(), //
				ManagedSymmetricBatteryInverter.ChannelId.values(), //
				StartStoppable.ChannelId.values(), //
				OffGridBatteryInverter.ChannelId.values(), //
				Srne.ChannelId.values(), //
				SrneBatteryInverter.ChannelId.values() //
		);
	}

	@Activate
	private void activate(ComponentContext context, Config config) throws OpenemsException {
		this.config = config;
		this.dischargeGuard = this.createDischargeGuard(config);
		this.modeSchedule = ModeSchedule.fromConfig(config.modeScheduleEnabled(), config.daytimePriority(),
				config.eveningPriority(), config.eveningStart(), config.daytimeStart(), config.outputPriority(),
				config.daytimeSbuAboveSoc(), config.daytimeSbuBelowSoc(), config.minDwellMinutes(),
				config.gridPresentMinVoltage(), config.eveningReserveSoc(), config.eveningReserveFrom());
		if (this.modeSchedule.getInvalidReason() != null) {
			this.logError(this.log, "Invalid mode schedule: " + this.modeSchedule.getInvalidReason()
					+ "; the schedule stays off");
		}
		/*
		 * There is intentionally no @Modified method. A settings configuration update
		 * causes DS to replace this component instance, giving every setting a fresh
		 * SafeWriteHandler / ScheduleWindow. VERIFIED, DONE and FAILED therefore remain
		 * terminal for one configured request and cannot trigger repeated writes in the
		 * same activation.
		 */
		super.activate(context, config.id(), config.alias(), config.enabled(), config.modbusUnitId());
		if (this.dischargeGuard.isActive() || this.modeSchedule.isActive()) {
			this.scheduleZone = this.parseScheduleZone(config.scheduleTimeZone());
		}
		if (this.dischargeGuard.isActive()) {
			this.warnIfRulesInert(config);
		}
		if (this.modeSchedule.isActive() && !config.controlEnabled()) {
			this.logWarn(this.log, "Mode schedule is enabled but inert: it needs controlEnabled=true");
		}
		this._setMaxApparentPower(config.maxApparentPower());
		this.getMachineStateChannel().onSetNextValue(ignore -> this.updateLifecycle());
		// Fresh machine-state reads for the mode schedule's mains confirmation.
		this.getMachineStateChannel().onSetNextValue(ignore -> {
			MachineState read = this.getMachineStateChannel().getNextValue().asEnum();
			this.modeSchedule.onMachineStateRead(
					read == null || read == MachineState.UNDEFINED ? null : Integer.valueOf(read.getValue()));
		});
		// Fresh grid-input voltage reads for the on-battery SUB path.
		this.registerGridVoltage(1, SrneBatteryInverter.ChannelId.GRID_VOLTAGE_L1);
		this.registerGridVoltage(2, SrneBatteryInverter.ChannelId.GRID_VOLTAGE_L2);
		this.registerGridVoltage(3, SrneBatteryInverter.ChannelId.GRID_VOLTAGE_L3);

		/*
		 * Validated on the ASP48120SH3: SRNE battery current is positive while
		 * discharging and negative while charging. This matches the OpenEMS
		 * battery-inverter power sign convention.
		 */
		final Consumer<Value<Integer>> updateActivePower = ignore -> {
			var voltage = this.getBatteryVoltageChannel().getNextValue().get();
			var current = this.getBatteryCurrentChannel().getNextValue().get();
			if (voltage == null || current == null) {
				this._setActivePower(null);
				return;
			}
			this._setActivePower((int) Math.round(voltage * (double) current / 1_000_000D));
		};
		this.getBatteryVoltageChannel().onSetNextValue(updateActivePower);
		this.getBatteryCurrentChannel().onSetNextValue(updateActivePower);

		this.registerReadback(0, SrneBatteryInverter.ChannelId.DISCHARGE_CUTOFF_SOC);
		this.registerReadback(1, SrneBatteryInverter.ChannelId.STOP_CHARGE_CURRENT);
		this.registerReadback(2, SrneBatteryInverter.ChannelId.STOP_CHARGE_SOC);
		this.registerReadback(3, SrneBatteryInverter.ChannelId.LOW_SOC_ALARM);
		this.registerReadback(4, SrneBatteryInverter.ChannelId.SWITCH_TO_LINE_SOC);
		this.registerReadback(5, SrneBatteryInverter.ChannelId.SWITCH_TO_BATTERY_SOC);
		this.registerReadback(6, SrneBatteryInverter.ChannelId.AC_CHARGE_CURRENT_LIMIT);
		this.registerReadback(7, SrneBatteryInverter.ChannelId.MAX_CHARGE_CURRENT_LIMIT);
		this.registerReadback(8, SrneBatteryInverter.ChannelId.OUTPUT_PRIORITY);
		this.registerReadback(9, SrneBatteryInverter.ChannelId.BMS_COMMUNICATION);
		this.registerReadback(10, SrneBatteryInverter.ChannelId.CHARGE_WINDOW_1_STOP_SOC);
		((IntegerReadChannel) this.channel(SrneBatteryInverter.ChannelId.OUTPUT_PRIORITY))
				.onSetNextValue(value -> this.modeSchedule.verify(value.get()));
		this.registerWindowReadback(this.chargeWindow, //
				SrneBatteryInverter.ChannelId.CHARGE_WINDOW_1_START, //
				SrneBatteryInverter.ChannelId.CHARGE_WINDOW_1_STOP, //
				SrneBatteryInverter.ChannelId.CHARGE_SCHEDULE_ENABLE);
		this.registerWindowReadback(this.dischargeWindow, //
				SrneBatteryInverter.ChannelId.DISCHARGE_WINDOW_1_START, //
				SrneBatteryInverter.ChannelId.DISCHARGE_WINDOW_1_STOP, //
				SrneBatteryInverter.ChannelId.DISCHARGE_SCHEDULE_ENABLE);
	}

	private void registerGridVoltage(int phase, SrneBatteryInverter.ChannelId channelId) {
		((IntegerReadChannel) this.channel(channelId))
				.onSetNextValue(value -> this.modeSchedule.onGridVoltageRead(phase, value.get()));
	}

	private void registerReadback(int index, SrneBatteryInverter.ChannelId channelId) {
		((IntegerReadChannel) this.channel(channelId)).onSetNextValue(value -> {
			var handler = this.writeHandlers[index];
			var previousState = handler.getState();
			handler.verify(value.get());
			if (previousState != handler.getState()) {
				this.logInfo(this.log, "Settings write [" + channelId.name() + "] state [" + handler.getState()
						+ "] after readback [" + value.get() + "]");
			}
			this.channel(SrneBatteryInverter.ChannelId.SAFE_WRITE_STATE).setNextValue(this.aggregateWriteState());
		});
	}

	// JUSTIFICATION-A3: coherent-window read-back wiring for the TOU schedule
	// feature (#67, PR #24). The window start/stop/enable each verify a distinct
	// register of one ScheduleWindow, so a single indexed registerReadback cannot
	// express it; this mirrors that helper for the three-register unit.
	private void registerWindowReadback(ScheduleWindow window, SrneBatteryInverter.ChannelId startChannel,
			SrneBatteryInverter.ChannelId stopChannel, SrneBatteryInverter.ChannelId enableChannel) {
		((IntegerReadChannel) this.channel(startChannel)).onSetNextValue(value -> {
			var previousState = window.getState();
			window.verifyStart(value.get());
			this.logWindowTransition(window, startChannel, previousState, value.get());
		});
		((IntegerReadChannel) this.channel(stopChannel)).onSetNextValue(value -> {
			var previousState = window.getState();
			window.verifyStop(value.get());
			this.logWindowTransition(window, stopChannel, previousState, value.get());
		});
		((IntegerReadChannel) this.channel(enableChannel)).onSetNextValue(value -> {
			var previousState = window.getState();
			window.verifyEnable(value.get());
			this.logWindowTransition(window, enableChannel, previousState, value.get());
		});
	}

	private void logWindowTransition(ScheduleWindow window, SrneBatteryInverter.ChannelId channelId,
			ScheduleWindow.State previousState, Integer readback) {
		if (previousState != window.getState()) {
			this.logInfo(this.log, "Schedule write [" + channelId.name() + "] state [" + window.getState()
					+ "] after readback [" + readback + "]");
		}
		this.channel(SrneBatteryInverter.ChannelId.SAFE_WRITE_STATE).setNextValue(this.aggregateWriteState());
	}

	@Override
	public void handleEvent(Event event) {
		if (!this.isEnabled()) {
			return;
		}
		switch (event.getTopic()) {
		case EdgeEventConstants.TOPIC_CYCLE_AFTER_PROCESS_IMAGE -> this.reconcileSafeSettings();
		}
	}

	@Override
	@Deactivate
	protected void deactivate() {
		super.deactivate();
	}

	private void updateLifecycle() {
		MachineState machineState = this.getMachineStateChannel().getNextValue().asEnum();
		var state = StateMachine.fromMachineState(machineState);
		this.channel(SrneBatteryInverter.ChannelId.STATE_MACHINE).setNextValue(state);
		this._setGridMode(StateMachine.toGridMode(state));
		switch (state) {
		case ON_GRID, OFF_GRID, TRANSITIONING:
			this._setInverterState(true);
			this._setStartStop(StartStop.START);
			break;
		case STOPPED, FAULT:
			this._setInverterState(false);
			this._setStartStop(StartStop.STOP);
			break;
		case UNDEFINED, STARTING:
			this._setInverterState(null);
			this._setStartStop(StartStop.UNDEFINED);
			break;
		}
	}

	@Override
	protected ModbusProtocol defineModbusProtocol() {
		var protocol = new ModbusProtocol(this, //
				new FC3ReadRegistersTask(0xE00F, Priority.LOW, //
						m(SrneBatteryInverter.ChannelId.DISCHARGE_CUTOFF_SOC,
								new UnsignedWordElement(0xE00F))), //
				// Read-only battery-voltage protection thresholds (see the channel docs).
				// Separate tasks, so the proven E00F read (safe-write verification) is
				// never coupled to them. NOTE: a read that the device rejects marks the
				// whole component defective for a few cycles (all its tasks skipped), so
				// these must be confirmed to answer on the unit before deploying.
				new FC3ReadRegistersTask(0xE00B, Priority.LOW, //
						m(SrneBatteryInverter.ChannelId.OVER_DISCHARGE_RETURN_VOLTAGE,
								new UnsignedWordElement(0xE00B), SCALE_FACTOR_2), //
						m(SrneBatteryInverter.ChannelId.UNDER_VOLTAGE_WARNING_VOLTAGE,
								new UnsignedWordElement(0xE00C), SCALE_FACTOR_2), //
						m(SrneBatteryInverter.ChannelId.OVER_DISCHARGE_VOLTAGE,
								new UnsignedWordElement(0xE00D), SCALE_FACTOR_2), //
						m(SrneBatteryInverter.ChannelId.LIMITED_DISCHARGE_VOLTAGE,
								new UnsignedWordElement(0xE00E), SCALE_FACTOR_2)), //
				new FC3ReadRegistersTask(0xE010, Priority.LOW, //
						m(SrneBatteryInverter.ChannelId.OVER_DISCHARGE_DELAY, new UnsignedWordElement(0xE010))), //
				new FC3ReadRegistersTask(0xE01C, Priority.LOW, //
						m(SrneBatteryInverter.ChannelId.STOP_CHARGE_CURRENT, new UnsignedWordElement(0xE01C),
								SCALE_FACTOR_2), //
						m(SrneBatteryInverter.ChannelId.STOP_CHARGE_SOC, new UnsignedWordElement(0xE01D)), //
						m(SrneBatteryInverter.ChannelId.LOW_SOC_ALARM, new UnsignedWordElement(0xE01E)), //
						m(SrneBatteryInverter.ChannelId.SWITCH_TO_LINE_SOC, new UnsignedWordElement(0xE01F)), //
						m(SrneBatteryInverter.ChannelId.SWITCH_TO_BATTERY_SOC,
								new UnsignedWordElement(0xE020))), //
				new FC3ReadRegistersTask(0xE204, Priority.LOW, //
						m(SrneBatteryInverter.ChannelId.OUTPUT_PRIORITY, new UnsignedWordElement(0xE204)), //
						m(SrneBatteryInverter.ChannelId.AC_CHARGE_CURRENT_LIMIT,
								new UnsignedWordElement(0xE205), SCALE_FACTOR_2)), //
				new FC3ReadRegistersTask(0xE20A, Priority.LOW, //
						m(SrneBatteryInverter.ChannelId.MAX_CHARGE_CURRENT_LIMIT,
								new UnsignedWordElement(0xE20A), SCALE_FACTOR_2)), //
				new FC3ReadRegistersTask(0xE215, Priority.LOW, //
						m(SrneBatteryInverter.ChannelId.BMS_COMMUNICATION, new UnsignedWordElement(0xE215))), //
				new FC3ReadRegistersTask(0xE026, Priority.LOW, //
						m(SrneBatteryInverter.ChannelId.CHARGE_WINDOW_1_START, new UnsignedWordElement(0xE026)), //
						m(SrneBatteryInverter.ChannelId.CHARGE_WINDOW_1_STOP, new UnsignedWordElement(0xE027))), //
				// Read-only schedule sections 2/3 (see the channel docs); separate tasks so
				// the window/enable reads used for schedule verification stay decoupled.
				new FC3ReadRegistersTask(0xE028, Priority.LOW, //
						m(SrneBatteryInverter.ChannelId.CHARGE_WINDOW_2_START, new UnsignedWordElement(0xE028)), //
						m(SrneBatteryInverter.ChannelId.CHARGE_WINDOW_2_STOP, new UnsignedWordElement(0xE029)), //
						m(SrneBatteryInverter.ChannelId.CHARGE_WINDOW_3_START, new UnsignedWordElement(0xE02A)), //
						m(SrneBatteryInverter.ChannelId.CHARGE_WINDOW_3_STOP, new UnsignedWordElement(0xE02B))), //
				new FC3ReadRegistersTask(0xE02F, Priority.LOW, //
						m(SrneBatteryInverter.ChannelId.DISCHARGE_WINDOW_2_START, new UnsignedWordElement(0xE02F)), //
						m(SrneBatteryInverter.ChannelId.DISCHARGE_WINDOW_2_STOP, new UnsignedWordElement(0xE030)), //
						m(SrneBatteryInverter.ChannelId.DISCHARGE_WINDOW_3_START, new UnsignedWordElement(0xE031)), //
						m(SrneBatteryInverter.ChannelId.DISCHARGE_WINDOW_3_STOP, new UnsignedWordElement(0xE032))), //
				new FC3ReadRegistersTask(0xE02C, Priority.LOW, //
						m(SrneBatteryInverter.ChannelId.CHARGE_SCHEDULE_ENABLE, new UnsignedWordElement(0xE02C)), //
						m(SrneBatteryInverter.ChannelId.DISCHARGE_WINDOW_1_START, new UnsignedWordElement(0xE02D)), //
						m(SrneBatteryInverter.ChannelId.DISCHARGE_WINDOW_1_STOP, new UnsignedWordElement(0xE02E))), //
				new FC3ReadRegistersTask(0xE033, Priority.LOW, //
						m(SrneBatteryInverter.ChannelId.DISCHARGE_SCHEDULE_ENABLE, new UnsignedWordElement(0xE033)), //
						// 0xE034-0xE036 is the inverter real-time clock (year/month, day/hour,
						// minute/second, each encoded high*256+low). Read-only here so
						// commissioning can verify the clock before any schedule is trusted.
						m(SrneBatteryInverter.ChannelId.RTC_YEAR_MONTH, new UnsignedWordElement(0xE034)), //
						m(SrneBatteryInverter.ChannelId.RTC_DAY_HOUR, new UnsignedWordElement(0xE035)), //
						m(SrneBatteryInverter.ChannelId.RTC_MINUTE_SECOND, new UnsignedWordElement(0xE036))), //
				new FC3ReadRegistersTask(0x0101, Priority.HIGH, //
						m(SrneBatteryInverter.ChannelId.BATTERY_VOLTAGE, new UnsignedWordElement(0x0101),
								SCALE_FACTOR_2), //
						m(SrneBatteryInverter.ChannelId.BATTERY_CURRENT, new SignedWordElement(0x0102),
								SCALE_FACTOR_2)), //
				new FC3ReadRegistersTask(0x0210, Priority.HIGH, //
						m(SrneBatteryInverter.ChannelId.MACHINE_STATE, new UnsignedWordElement(0x0210))), //
				new FC16WriteRegistersTask(this.writeHandlers[0]::onExecute, 0xE00F, this.dischargeCutoffSocWrite), //
				new FC16WriteRegistersTask(this.writeHandlers[1]::onExecute, 0xE01C, this.stopChargeCurrentWrite), //
				new FC16WriteRegistersTask(this.writeHandlers[2]::onExecute, 0xE01D, this.stopChargeSocWrite), //
				new FC16WriteRegistersTask(this.writeHandlers[3]::onExecute, 0xE01E, this.lowSocAlarmWrite), //
				new FC16WriteRegistersTask(this.writeHandlers[4]::onExecute, 0xE01F, this.switchToLineSocWrite), //
				new FC16WriteRegistersTask(this.writeHandlers[5]::onExecute, 0xE020, this.switchToBatterySocWrite), //
				new FC16WriteRegistersTask(this.writeHandlers[6]::onExecute, 0xE205,
						this.acChargeCurrentLimitWrite), //
				new FC16WriteRegistersTask(this.writeHandlers[7]::onExecute, 0xE20A,
						this.maxChargeCurrentLimitWrite), //
				new FC16WriteRegistersTask(this.writeHandlers[8]::onExecute, 0xE204, this.outputPriorityWrite), //
				new FC16WriteRegistersTask(this.writeHandlers[9]::onExecute, 0xE215, this.bmsCommunicationWrite), //
				// Start and stop each get their OWN single-register task: the SRNE mis-stored
				// a two-register FC16 here (see ScheduleWindow), and one task would merge the
				// contiguous elements into one frame. The enable is a separate write, last.
				new FC16WriteRegistersTask(this.chargeWindow::onStartExecute, 0xE026,
						this.chargeWindow.startWriteElement()), //
				new FC16WriteRegistersTask(this.chargeWindow::onStopExecute, 0xE027,
						this.chargeWindow.stopWriteElement()), //
				new FC16WriteRegistersTask(this.chargeWindow::onEnableExecute, 0xE02C,
						this.chargeWindow.enableWriteElement()), //
				new FC16WriteRegistersTask(this.dischargeWindow::onStartExecute, 0xE02D,
						this.dischargeWindow.startWriteElement()), //
				new FC16WriteRegistersTask(this.dischargeWindow::onStopExecute, 0xE02E,
						this.dischargeWindow.stopWriteElement()), //
				new FC16WriteRegistersTask(this.dischargeWindow::onEnableExecute, 0xE033,
						this.dischargeWindow.enableWriteElement()));
		// SoC for the discharge rules and the mode schedule: the same register 0x0100
		// that SrneEss maps to SymmetricEss.SOC. Only read when one of them is
		// configured, so the default bus traffic is unchanged.
		if (this.dischargeGuard.isActive() || this.modeSchedule.isActive()) {
			protocol.addTask(new FC3ReadRegistersTask(0x0100, Priority.HIGH, //
					m(SrneBatteryInverter.ChannelId.BATTERY_SOC, new UnsignedWordElement(0x0100))));
		}
		// Charge window 1 stop SoC (E03B): read and written only when configured. A read
		// the device rejects marks the whole component defective, so unmanaged edges
		// never touch this register.
		if (this.isChargeWindow1StopSocManaged()) {
			protocol.addTask(new FC3ReadRegistersTask(0xE03B, Priority.LOW, //
					m(SrneBatteryInverter.ChannelId.CHARGE_WINDOW_1_STOP_SOC, new UnsignedWordElement(0xE03B))));
			protocol.addTask(new FC16WriteRegistersTask(this.writeHandlers[10]::onExecute, 0xE03B,
					this.chargeWindow1StopSocWrite));
		}
		// The mode schedule's own E204 write task exists only while the schedule is
		// active, so the default task list is unchanged.
		// Grid-input voltages for the mode schedule's SUB write on battery: the SRNE's
		// own AC-input sense (the same registers Srne.Grid-Meter reads, validated by a
		// live grid-off/grid-on test). Read only while that path is enabled.
		if (this.modeSchedule.isActive() && this.modeSchedule.isGridPresentPathEnabled()) {
			protocol.addTask(new FC3ReadRegistersTask(0x0213, Priority.HIGH, //
					m(SrneBatteryInverter.ChannelId.GRID_VOLTAGE_L1, new UnsignedWordElement(0x0213),
							SCALE_FACTOR_2)));
			protocol.addTask(new FC3ReadRegistersTask(0x022A, Priority.HIGH, //
					m(SrneBatteryInverter.ChannelId.GRID_VOLTAGE_L2, new UnsignedWordElement(0x022A),
							SCALE_FACTOR_2), //
					m(SrneBatteryInverter.ChannelId.GRID_VOLTAGE_L3, new UnsignedWordElement(0x022B),
							SCALE_FACTOR_2)));
		}
		if (this.modeSchedule.isActive()) {
			// Its own E204 element and task, so its one-shot handlers never touch the
			// static outputPriority handler (index 8); only one of the two can own E204.
			protocol.addTask(new FC16WriteRegistersTask(this.modeSchedule::onExecute, 0xE204,
					this.modeSchedule.writeElement()));
		}
		return protocol;
	}

	@Override
	public void setTargetGridMode(TargetGridMode targetGridMode) {
		this.targetGridMode.set(targetGridMode);
	}

	@Override
	public void setStartStop(StartStop value) throws OpenemsNamedException {
		this.startStopTarget.set(value);
	}

	@Override
	public void run(Battery battery, int setActivePower, int setReactivePower) throws OpenemsNamedException {
		this.updateLifecycle();
	}

	/**
	 * Continuous max charge/discharge current of the SR-SE10B (205Ah LFP) is 100A;
	 * 120A is only the 3-second peak (datasheet SRNE_SE series V1.5). Current-limit
	 * settings are capped at the continuous rating so a configured limit can never
	 * exceed what the battery allows.
	 */
	private static final int MAX_BATTERY_CURRENT_A = 100;

	private void reconcileSafeSettings() {
		for (var handler : this.writeHandlers) {
			handler.onCycle(READBACK_TIMEOUT_CYCLES);
		}
		this.chargeWindow.onCycle(READBACK_TIMEOUT_CYCLES);
		// Only the rules-managed discharge window is retried, so only it may give up on
		// a write that stays queued; every other window behaves as without the rules.
		this.dischargeWindow.setQueuedTimeout(this.config != null && this.dischargeRulesApply());
		this.dischargeWindow.onCycle(READBACK_TIMEOUT_CYCLES);
		this.updateDischargeSuppression();
		this.channel(SrneBatteryInverter.ChannelId.SAFE_WRITE_STATE).setNextValue(this.aggregateWriteState());
		this.reconcileModeSchedule();
		if (this.config == null || !this.config.controlEnabled()) {
			return;
		}
		if (!this.isMachineStateVerified()) {
			this.reconcileDisarmUnverified();
			return;
		}

		this.reconcile(0, SrneBatteryInverter.ChannelId.DISCHARGE_CUTOFF_SOC,
				this.config.dischargeCutoffSoc(), 0, 100, 1, this.dischargeCutoffSocWrite);
		this.reconcile(1, SrneBatteryInverter.ChannelId.STOP_CHARGE_CURRENT,
				this.config.stopChargeCurrent(), 0, MAX_BATTERY_CURRENT_A, 10, this.stopChargeCurrentWrite);
		this.reconcile(2, SrneBatteryInverter.ChannelId.STOP_CHARGE_SOC,
				this.config.stopChargeSoc(), 0, 100, 1, this.stopChargeSocWrite);
		this.reconcile(3, SrneBatteryInverter.ChannelId.LOW_SOC_ALARM,
				this.config.lowSocAlarm(), 0, 100, 1, this.lowSocAlarmWrite);
		// An unset device reads 4096, which differs from every valid target, so a
		// configured target queues exactly one write and 4096 never verifies.
		this.reconcile(10, SrneBatteryInverter.ChannelId.CHARGE_WINDOW_1_STOP_SOC,
				this.config.chargeWindow1StopSoc(), 10, 100, 1, this.chargeWindow1StopSocWrite);
		this.reconcile(6, SrneBatteryInverter.ChannelId.AC_CHARGE_CURRENT_LIMIT,
				this.config.acChargeCurrentLimit(), 0, MAX_BATTERY_CURRENT_A, 10, this.acChargeCurrentLimitWrite);
		this.reconcile(7, SrneBatteryInverter.ChannelId.MAX_CHARGE_CURRENT_LIMIT,
				this.config.maxChargeCurrentLimit(), 0, MAX_BATTERY_CURRENT_A, 10, this.maxChargeCurrentLimitWrite);
		// Source transfer: the reserve band (E01F/E020), BMS mode (E215) and output
		// priority (E204) are reconciled together as one coherent, validated operation.
		this.reconcileSourceTransfer();
		this.reconcileWindow(this.chargeWindow, //
				SrneBatteryInverter.ChannelId.CHARGE_WINDOW_1_START, //
				SrneBatteryInverter.ChannelId.CHARGE_WINDOW_1_STOP, //
				SrneBatteryInverter.ChannelId.CHARGE_SCHEDULE_ENABLE, //
				this.config.chargeWindow1Start(), this.config.chargeWindow1Stop(), this.config.chargeScheduleEnable());
		this.reconcileWindow(this.dischargeWindow, //
				SrneBatteryInverter.ChannelId.DISCHARGE_WINDOW_1_START, //
				SrneBatteryInverter.ChannelId.DISCHARGE_WINDOW_1_STOP, //
				SrneBatteryInverter.ChannelId.DISCHARGE_SCHEDULE_ENABLE, //
				this.config.dischargeWindow1Start(), this.config.dischargeWindow1Stop(),
				this.effectiveDischargeEnable());
		this.channel(SrneBatteryInverter.ChannelId.SAFE_WRITE_STATE).setNextValue(this.aggregateWriteState());
	}

	/**
	 * Daily output-priority schedule. Runs every cycle so its state channel stays
	 * current; a write is queued only with controlEnabled, readable SoC/E204/clock
	 * and machine state 2 (mains), i.e. the same gate as every settings write.
	 */
	private void reconcileModeSchedule() {
		if (this.config == null) {
			return;
		}
		// Inputs are read only while the schedule is active; otherwise step() returns
		// DISABLED or CONFIG_INVALID without looking at them.
		final var active = this.modeSchedule.isActive();
		final LocalDateTime now = active
				? this.modeSchedule.plausibleTime(this.instantOrNull(), this.scheduleZone)
				: null;
		final var soc = active ? this.readValue(SrneBatteryInverter.ChannelId.BATTERY_SOC) : null;
		final var outputPriority = active ? this.readValue(SrneBatteryInverter.ChannelId.OUTPUT_PRIORITY) : null;
		final var mains = active && this.isMachineStateVerified();
		final var sbuAllowed = active && this.sbuPreconditionsMet();
		var step = this.modeSchedule.step(now, soc, outputPriority, this.config.controlEnabled(), mains, sbuAllowed,
				READBACK_TIMEOUT_CYCLES, FAILED_RETRY_COOLDOWN_CYCLES);
		if (step.withdraw()) {
			this.logInfo(this.log, "Mode schedule: mains lost, withdrew the queued E204 write");
		}
		if (step.write() != null) {
			this.logInfo(this.log,
					"Mode schedule: queued E204 write from [" + outputPriority + "] to [" + step.write() + "]");
		}
		if (step.state() != this.lastModeScheduleState) {
			var message = "Mode schedule state [" + step.state() + "], target [" + step.target() + "], E204 ["
					+ outputPriority + "]";
			if (step.state() == ModeScheduleState.WRITE_FAILED || step.state() == ModeScheduleState.CONFIG_INVALID) {
				this.logWarn(this.log, message);
			} else {
				this.logInfo(this.log, message);
			}
			this.lastModeScheduleState = step.state();
		}
		this.channel(SrneBatteryInverter.ChannelId.MODE_SCHEDULE_STATE).setNextValue(step.state());
		this.channel(SrneBatteryInverter.ChannelId.MODE_SCHEDULE_TARGET_PRIORITY).setNextValue(step.target());
		this.channel(SrneBatteryInverter.ChannelId.MODE_SCHEDULE_REASON)
				.setNextValue(active ? this.modeSchedule.reason() : null);
	}

	// The plausibility of the time is judged by ModeSchedule.plausibleTime; this
	// only guards a missing component manager.
	private Instant instantOrNull() {
		try {
			return this.clock().instant();
		} catch (RuntimeException e) {
			return null;
		}
	}

	/**
	 * The verified machine-state gate shared by every settings write: state 2
	 * (mains/bypass), the only live-validated state.
	 *
	 * @return true if the machine state is verified
	 */
	private boolean isMachineStateVerified() {
		MachineState machineState = this.getMachineStateChannel().value().asEnum();
		return machineState != null && machineState.isVerified();
	}

	/**
	 * The SBU preconditions of a static outputPriority arm, applied to the
	 * schedule's SBU write: the reserve band and BMS mode are configured, valid and
	 * coherent, and each is settled on the device.
	 *
	 * @return true if SBU may be written
	 */
	private boolean sbuPreconditionsMet() {
		var line = this.config.switchToLineSoc();
		var battery = this.config.switchToBatterySoc();
		var bms = this.config.bmsCommunication();
		return sourceTransferArmProblem(ModeSchedule.SBU, line, battery, bms) == null
				&& this.sourceTransferPrerequisitesSettled(line, battery, bms);
	}

	// Package-private for tests: the dummy bridge does not execute writes, so tests
	// play the device through the same execute callback the write task uses.
	ModeSchedule modeScheduleForTest() {
		return this.modeSchedule;
	}

	// Package-private for tests: takes the queued write exactly as the bridge does
	// (the dummy bridge never consumes writes) and returns whether one was pending.
	boolean takeModeScheduleWriteForTest() {
		return this.modeSchedule.writeElement().getNextWriteValueAndReset() != null;
	}

	// Only the disarm of the discharge schedule bypasses the verified machine-state
	// gate: E033 is a plain holding-register write and disarming only reduces the
	// exposure. Arming, restoring and every window write still wait for state 2. A
	// queued arm is withdrawn here too, so a latch cannot leave it waiting for state 2.
	private void reconcileDisarmUnverified() {
		if (!this.dischargeRulesApply()) {
			return;
		}
		if (this.effectiveDischargeEnable() != 0) {
			return;
		}
		// A settled step that drifted back to armed is re-disarmed at the drift rate.
		var settled = this.dischargeWindow.isWaitingForVerifiedState();
		if (settled && this.driftCooldown > 0) {
			return;
		}
		var message = this.dischargeWindow
				.disarmOnly(this.readValue(SrneBatteryInverter.ChannelId.DISCHARGE_SCHEDULE_ENABLE));
		if (message != null) {
			if (settled) {
				this.driftCooldown = FAILED_RETRY_COOLDOWN_CYCLES;
			}
			this.logWarn(this.log, message + "; machine state is not verified, disarm only");
		}
	}

	// Fail open: an out-of-range threshold or an incoherent pair turns the affected
	// rule(s) off, so a typo can never suppress every night unattended.
	private DischargeGuard createDischargeGuard(Config config) {
		var startMinSoc = this.validRuleThreshold("dischargeStartMinSoc", config.dischargeStartMinSoc());
		var floorSoc = this.validRuleThreshold("dischargeFloorSoc", config.dischargeFloorSoc());
		if (startMinSoc >= 0 && floorSoc >= 0 && floorSoc >= startMinSoc) {
			this.logError(this.log, "dischargeFloorSoc [" + floorSoc + "] must be below dischargeStartMinSoc ["
					+ startMinSoc + "]; the start rule is off, the floor rule stays on");
			return new DischargeGuard(-1, floorSoc);
		}
		return new DischargeGuard(startMinSoc, floorSoc);
	}

	private int validRuleThreshold(String name, int value) {
		if (value == -1 || value >= 0 && value <= MAX_RULE_SOC) {
			return value;
		}
		this.logError(this.log, "Invalid " + name + " [" + value + "]; must be 0.." + MAX_RULE_SOC
				+ " or -1 (off). This rule is off");
		return -1;
	}

	private void warnIfRulesInert(Config config) {
		if (!config.controlEnabled() || config.dischargeScheduleEnable() != 1 || config.dischargeWindow1Start() < 0
				|| config.dischargeWindow1Stop() < 0) {
			this.logWarn(this.log, "Discharge rules are configured but inert: they need controlEnabled=true, "
					+ "dischargeScheduleEnable=1 and a discharge window");
		}
	}

	private ZoneId parseScheduleZone(String zone) {
		try {
			return ZoneId.of(zone);
		} catch (DateTimeException | NullPointerException e) {
			this.logError(this.log, "Invalid scheduleTimeZone [" + zone + "]; using " + DEFAULT_SCHEDULE_ZONE);
			return ZoneId.of(DEFAULT_SCHEDULE_ZONE);
		}
	}

	/**
	 * Evaluates the start-threshold and floor rules. Runs every cycle regardless of
	 * the machine state so the decision stays current; writes happen only in the
	 * gated reconcile, i.e. deferred during a grid outage. With control disabled
	 * nothing is evaluated or published, because nothing would be written.
	 */
	private void updateDischargeSuppression() {
		if (this.config == null || !this.config.controlEnabled() || !this.dischargeRulesApply()) {
			return;
		}
		var now = LocalDateTime.now(this.clock().withZone(this.scheduleZone));
		final var reason = this.dischargeGuard.evaluate(now, this.config.dischargeWindow1Start(),
				this.config.dischargeWindow1Stop(), this.readValue(SrneBatteryInverter.ChannelId.BATTERY_SOC),
				this.readValue(SrneBatteryInverter.ChannelId.DISCHARGE_SCHEDULE_ENABLE));
		if (this.dischargeGuard.pollClockStepWarning()) {
			this.logWarn(this.log, "Local time stepped by more than " + DischargeGuard.CLOCK_STEP_MINUTES
					+ " minutes between cycles; holding the discharge decision for this cycle");
		}
		if (this.dischargeGuard.pollUnusableSocWarning()) {
			this.logWarn(this.log, "Battery SoC is unknown or outside 0..100; no rule can latch, and a disarmed "
					+ "schedule is not armed until a usable SoC is read (at most " + DischargeGuard.HOLD_CAP_MINUTES
					+ " minutes, then it fails open)");
		}
		if (this.dischargeGuard.pollWindowEndWithoutSocWarning()) {
			this.logWarn(this.log, "The discharge window ended without a usable battery SoC; the schedule was "
					+ "not armed by this component during the window");
		}
		if (this.dischargeGuard.pollHoldCapWarning()) {
			this.logWarn(this.log, "No usable battery SoC for " + DischargeGuard.HOLD_CAP_MINUTES
					+ " minutes in the discharge window; no longer holding the arm back (fail open)");
		}
		var deviceEnable = this.readValue(SrneBatteryInverter.ChannelId.DISCHARGE_SCHEDULE_ENABLE);
		this.channel(SrneBatteryInverter.ChannelId.DISCHARGE_SUPPRESSION_REASON).setNextValue(reason);
		// Report what the device is in, not only what the driver decided: an unknown
		// enable register reads as unknown, and a failed write shows as a mismatch.
		this.channel(SrneBatteryInverter.ChannelId.DISCHARGE_SUPPRESSED)
				.setNextValue(deviceEnable == null ? null : deviceEnable == 0);
		var mismatch = this.dischargeWindow.getState() == ScheduleWindow.State.FAILED && deviceEnable != null
				&& (deviceEnable == 0) != (reason != DischargeSuppressionReason.NONE);
		if (mismatch && !this.mismatchLogged) {
			this.logWarn(this.log, "Discharge schedule write failed: device enable [" + deviceEnable
					+ "] does not match the decision [" + reason + "]; retrying");
		}
		this.mismatchLogged = mismatch;
	}

	private boolean dischargeRulesApply() {
		return this.dischargeGuard.isActive() && this.config.dischargeScheduleEnable() == 1
				&& this.config.dischargeWindow1Start() >= 0 && this.config.dischargeWindow1Stop() >= 0;
	}

	/**
	 * The enable target handed to the verified-write path: the configured value,
	 * or 0 while the discharge is suppressed. A settled or failed window is
	 * re-opened when the target changes so the change goes through the same
	 * verified write; a failed window is also retried after a cooldown without
	 * limit (a failed suppress quickly at first, then slowly), and a
	 * drifted enable register is corrected once per window.
	 *
	 * @return the enable target for the discharge window
	 */
	private int effectiveDischargeEnable() {
		var configured = this.config.dischargeScheduleEnable();
		if (!this.dischargeRulesApply()) {
			return configured;
		}
		var suppressed = this.dischargeGuard.evaluateCurrent() != DischargeSuppressionReason.NONE;
		int effective = suppressed ? 0 : configured;
		if (!suppressed && this.dischargeGuard.isHoldingArm()
				&& Integer.valueOf(0).equals(this.readValue(SrneBatteryInverter.ChannelId.DISCHARGE_SCHEDULE_ENABLE))) {
			// A candidate is unconfirmed or SoC is not yet known: never newly arm.
			effective = 0;
		}
		if (this.appliedDischargeEnable == null) {
			this.appliedDischargeEnable = effective;
		} else if (this.appliedDischargeEnable != effective && (this.dischargeWindow.getState() == ScheduleWindow.State.IDLE
				|| this.dischargeWindow.isSequenceInProgress() || this.dischargeWindow.reopen())) {
			// A running sequence follows the live target too, so an arm not yet written is
			// turned into a disarm; the value reaches the window every cycle.
			this.logInfo(this.log, "Discharge schedule enable target changed to [" + effective + "]");
			this.appliedDischargeEnable = effective;
			this.suppressRetries = 0;
			this.failedCycles = 0;
			if (effective == 0) {
				this.driftCooldown = 0;
			}
		}
		if (!this.dischargeGuard.isInsideWindow() && !suppressed) {
			this.driftCorrectedUnsuppressed = false;
		}
		this.retryOrCorrect(this.appliedDischargeEnable);
		return this.appliedDischargeEnable;
	}

	private void retryOrCorrect(int target) {
		var state = this.dischargeWindow.getState();
		if (this.driftCooldown > 0) {
			this.driftCooldown--;
		}
		if (state == ScheduleWindow.State.DONE) {
			this.suppressRetries = 0;
			this.failedCycles = 0;
			var actual = this.readValue(SrneBatteryInverter.ChannelId.DISCHARGE_SCHEDULE_ENABLE);
			if (this.dischargeGuard.isInsideWindow() && actual != null && actual != target) {
				// While suppressed the correction is unlimited but rate-limited; otherwise
				// it is allowed once per window.
				var suppressed = target == 0;
				var due = suppressed ? this.driftCooldown == 0 : !this.driftCorrectedUnsuppressed;
				if (due && this.dischargeWindow.reopen()) {
					this.driftCorrectedUnsuppressed |= !suppressed;
					this.driftCooldown = FAILED_RETRY_COOLDOWN_CYCLES;
					this.logWarn(this.log, "Discharge schedule enable drifted to [" + actual + "], target [" + target
							+ "]; correcting" + (suppressed ? "" : " once"));
				}
			}
			return;
		}
		if (!this.dischargeWindow.isRetryableFailure()) {
			this.failedCycles = 0;
			return;
		}
		var fast = target == 0 && this.suppressRetries < MAX_FAST_SUPPRESS_RETRIES;
		if (++this.failedCycles < (fast ? SUPPRESS_RETRY_COOLDOWN_CYCLES : FAILED_RETRY_COOLDOWN_CYCLES)) {
			return;
		}
		if (this.dischargeWindow.reopen()) {
			this.failedCycles = 0;
			this.suppressRetries += target == 0 ? 1 : 0;
			this.logWarn(this.log, "Retrying failed discharge schedule write, target [" + target + "]");
		}
	}

	private void reconcile(int index, SrneBatteryInverter.ChannelId channelId, int configuredTarget, int min, int max,
			int rawMultiplier, UnsignedWordElement writeElement) {
		if (configuredTarget < 0) {
			return;
		}
		var handler = this.writeHandlers[index];
		Integer actual = ((IntegerReadChannel) this.channel(channelId)).value().get();
		if (configuredTarget < min || configuredTarget > max) {
			if (handler.reject()) {
				this.logWarn(this.log, "Rejected out-of-range setting [" + channelId.name() + "] value ["
						+ configuredTarget + "]; validated range is [" + min + ".." + max + "]");
			}
			return;
		}
		int target = configuredTarget;
		int channelTarget = target * (rawMultiplier == 10 ? 1_000 : 1);
		if (handler.queueIfChanged(actual, channelTarget)) {
			this.logInfo(this.log, "Queued one-shot settings write [" + channelId.name() + "] from [" + actual
					+ "] to [" + channelTarget + "]");
			writeElement.setNextWriteValue(target * rawMultiplier);
		}
	}

	// JUSTIFICATION-A3: guarded reconcile for output priority (#71). Output priority =
	// SBU transfers the load onto the battery - it is effectively the "arm" for battery
	// discharge. Mirroring the schedule feature's verified-then-enable, it is written
	// only once its safety prerequisites (the reserve-floor / return SoC band and BMS
	// comms) have read-back verified, so a failed or still-pending floor write can never
	// arm battery discharge against a wrong reserve. Cannot use the flat reconcile()
	// because of this cross-setting gate.
	// JUSTIFICATION-A3: coherent source-transfer reconcile (#71, PR #25 review). Output
	// priority = SBU transfers the load onto the battery; its reserve band (E01F/E020)
	// and BMS mode (E215) are safety prerequisites. When an arm is requested the WHOLE
	// set is validated (present, individually bounded, coherent) BEFORE any of it is
	// written, so a rejected arm never leaves a bad reserve band or partial writes on the
	// live device. When no arm is requested the band and BMS remain independent settings.
	private void reconcileSourceTransfer() {
		var line = this.config.switchToLineSoc();
		var battery = this.config.switchToBatterySoc();
		var bms = this.config.bmsCommunication();
		var arm = this.config.outputPriority();
		if (arm < 0) {
			// A bad reserve band is dangerous whenever the unit is in SBU (its normal
			// backup posture, settable from the panel), so an incoherent band is never
			// written even when OpenEMS is not arming: reject both thresholds if both are
			// configured and inverted. BMS remains an independent write.
			if (line >= 0 && battery >= 0 && line >= battery) {
				this.rejectBand(line, battery);
			} else {
				this.reconcile(4, SrneBatteryInverter.ChannelId.SWITCH_TO_LINE_SOC, line, 0, 100, 1,
						this.switchToLineSocWrite);
				this.reconcile(5, SrneBatteryInverter.ChannelId.SWITCH_TO_BATTERY_SOC, battery, 0, 100, 1,
						this.switchToBatterySocWrite);
			}
			this.reconcile(9, SrneBatteryInverter.ChannelId.BMS_COMMUNICATION, bms, 0, 2, 1,
					this.bmsCommunicationWrite);
			return;
		}
		// Arming: validate the whole set up front and write NOTHING on failure.
		var problem = sourceTransferArmProblem(arm, line, battery, bms);
		if (problem != null) {
			this.rejectOutputPriority(problem);
			return;
		}
		// Whole set valid: reconcile the band + BMS, then arm once all three are settled.
		this.reconcile(4, SrneBatteryInverter.ChannelId.SWITCH_TO_LINE_SOC, line, 0, 100, 1,
				this.switchToLineSocWrite);
		this.reconcile(5, SrneBatteryInverter.ChannelId.SWITCH_TO_BATTERY_SOC, battery, 0, 100, 1,
				this.switchToBatterySocWrite);
		this.reconcile(9, SrneBatteryInverter.ChannelId.BMS_COMMUNICATION, bms, 0, 2, 1, this.bmsCommunicationWrite);
		if (!this.sourceTransferPrerequisitesSettled(line, battery, bms)) {
			if (!this.outputPriorityHeldLogged) {
				this.logInfo(this.log, "Output-priority (SBU) write held pending its SoC-band / BMS prerequisites");
				this.outputPriorityHeldLogged = true;
			}
			return;
		}
		this.reconcile(8, SrneBatteryInverter.ChannelId.OUTPUT_PRIORITY, arm, 0, 3, 1, this.outputPriorityWrite);
	}

	/**
	 * Validates the source-transfer set for an output-priority arm before anything
	 * is written: the arm in range, the reserve band and BMS mode all configured,
	 * in range, and the band coherent. Shared by the static arm and the mode
	 * schedule's SBU write.
	 *
	 * @param arm     the output-priority target (&gt;= 0)
	 * @param line    switchToLineSoc
	 * @param battery switchToBatterySoc
	 * @param bms     bmsCommunication
	 * @return null if valid, otherwise the reason
	 */
	static String sourceTransferArmProblem(int arm, int line, int battery, int bms) {
		if (arm > 3) {
			return "output priority [" + arm + "] out of range 0..3";
		}
		if (line < 0 || battery < 0 || bms < 0) {
			return "requires switchToLineSoc, switchToBatterySoc and bmsCommunication to all be configured";
		}
		if (line > 100 || battery > 100 || bms > 2) {
			return "reserve band / BMS out of range (SoC 0..100, BMS 0..2)";
		}
		if (line >= battery) {
			return "reserve band invalid: switchToLineSoc [" + line + "] must be below switchToBatterySoc [" + battery
					+ "]";
		}
		return null;
	}

	private boolean sourceTransferPrerequisitesSettled(int line, int battery, int bms) {
		return isPrerequisiteSettled(line, this.writeHandlers[4].getState(),
				this.readValue(SrneBatteryInverter.ChannelId.SWITCH_TO_LINE_SOC))
				&& isPrerequisiteSettled(battery, this.writeHandlers[5].getState(),
						this.readValue(SrneBatteryInverter.ChannelId.SWITCH_TO_BATTERY_SOC))
				&& isPrerequisiteSettled(bms, this.writeHandlers[9].getState(),
						this.readValue(SrneBatteryInverter.ChannelId.BMS_COMMUNICATION));
	}

	private boolean isChargeWindow1StopSocManaged() {
		return this.config != null && this.config.chargeWindow1StopSoc() >= 0;
	}

	UnsignedWordElement chargeWindow1StopSocWriteForTest() {
		return this.chargeWindow1StopSocWrite;
	}

	SafeWriteHandler chargeWindow1StopSocHandlerForTest() {
		return this.writeHandlers[10];
	}

	// Package-private for tests: lets a test assert that a reserve-band / BMS write did
	// NOT queue on a rejected arm (state stays IDLE), not just that the aggregate failed.
	SafeWriteHandler.State writeHandlerStateForTest(int index) {
		return this.writeHandlers[index].getState();
	}

	// Package-private for tests: the dummy bridge cannot echo a written register, so
	// tests pin the enable value that was queued.
	Integer dischargeQueuedEnableForTest() {
		return this.dischargeWindow.queuedEnable();
	}

	io.openems.edge.common.channel.BooleanReadChannel getDischargeSuppressedChannelForTest() {
		return this.channel(SrneBatteryInverter.ChannelId.DISCHARGE_SUPPRESSED);
	}

	io.openems.edge.common.channel.Channel<DischargeSuppressionReason> getDischargeSuppressionReasonChannelForTest() {
		return this.channel(SrneBatteryInverter.ChannelId.DISCHARGE_SUPPRESSION_REASON);
	}

	Integer getBatterySocForTest() {
		return this.readValue(SrneBatteryInverter.ChannelId.BATTERY_SOC);
	}

	boolean driftCorrectedUnsuppressedForTest() {
		return this.driftCorrectedUnsuppressed;
	}

	ScheduleWindow chargeWindowForTest() {
		return this.chargeWindow;
	}

	ScheduleWindow dischargeWindowForTest() {
		return this.dischargeWindow;
	}

	ScheduleWindow.State dischargeWindowStateForTest() {
		return this.dischargeWindow.getState();
	}

	private void rejectOutputPriority(String reason) {
		if (this.writeHandlers[8].reject()) {
			this.logWarn(this.log, "Rejected output-priority arm: " + reason);
		}
	}

	private void rejectBand(int line, int battery) {
		var rejectedLine = this.writeHandlers[4].reject();
		var rejectedBattery = this.writeHandlers[5].reject();
		if (rejectedLine || rejectedBattery) {
			this.logWarn(this.log, "Rejected reserve-band write: switchToLineSoc [" + line
					+ "] must be below switchToBatterySoc [" + battery + "]");
		}
	}

	private Integer readValue(SrneBatteryInverter.ChannelId channelId) {
		return ((IntegerReadChannel) this.channel(channelId)).value().get();
	}

	/**
	 * Whether a configured output-priority prerequisite is settled and it is therefore
	 * safe to arm battery-priority output. Settled means the handler is at rest - either
	 * IDLE (already correct on the device, so it never queued) or VERIFIED (written and
	 * confirmed this activation) - AND the device CURRENTLY reads the configured target.
	 * A queued, awaiting or failed write is never settled even if the read momentarily
	 * matches, and a verified value that has since drifted (or is unknown) is not settled
	 * either. Callers guarantee the target is configured (&gt;= 0).
	 *
	 * <p>Requires unscaled prerequisites (rawMultiplier 1) so the raw channel read and
	 * the config target are the same space; all current prerequisites (E01F/E020/E215)
	 * are unscaled. A scaled register must not be added as a prerequisite without also
	 * scaling this comparison.
	 *
	 * @param configuredTarget the prerequisite's configured target
	 * @param state            the prerequisite write handler's current state
	 * @param actual           the prerequisite register's current read value, or null
	 * @return true if it is safe to proceed with the output-priority write
	 */
	static boolean isPrerequisiteSettled(int configuredTarget, SafeWriteHandler.State state, Integer actual) {
		if (state != SafeWriteHandler.State.IDLE && state != SafeWriteHandler.State.VERIFIED) {
			return false;
		}
		return actual != null && actual == configuredTarget;
	}

	// JUSTIFICATION-A3: guarded reconcile for a coherent TOU schedule window (#67,
	// PR #24). Reads the three device registers for the window and hands them, with
	// the configured targets, to the ScheduleWindow state machine which validates
	// the pair, writes the pair as two single-register requests and only then enables. Not a wrapper around
	// the scalar reconcile(): the pair validation + verified-then-enable ordering
	// cannot be expressed by the single-register path.
	private void reconcileWindow(ScheduleWindow window, SrneBatteryInverter.ChannelId startChannel,
			SrneBatteryInverter.ChannelId stopChannel, SrneBatteryInverter.ChannelId enableChannel, int cfgStart,
			int cfgStop, int cfgEnable) {
		Integer actualStart = ((IntegerReadChannel) this.channel(startChannel)).value().get();
		Integer actualStop = ((IntegerReadChannel) this.channel(stopChannel)).value().get();
		Integer actualEnable = ((IntegerReadChannel) this.channel(enableChannel)).value().get();
		var message = window.reconcile(actualStart, actualStop, actualEnable, cfgStart, cfgStop, cfgEnable);
		if (message != null) {
			if (window.getState() == ScheduleWindow.State.FAILED) {
				this.logWarn(this.log, message);
			} else {
				this.logInfo(this.log, message);
			}
		}
	}

	// JUSTIFICATION-A3: thin static delegates kept so existing unit tests and any
	// callers resolve time validation/formatting through the component; the
	// implementation now lives in ScheduleWindow with the rest of the schedule
	// logic.
	/**
	 * Whether a raw schedule value is a valid encoded time (hour*256+min, hour
	 * 0..23, minute 0..59).
	 *
	 * @param encoded the raw register value
	 * @return true if it decodes to a valid time
	 */
	static boolean isValidEncodedTime(int encoded) {
		return ScheduleWindow.isValidEncodedTime(encoded);
	}

	/**
	 * Formats an encoded schedule time (hour*256+min) as HH:mm for logging.
	 *
	 * @param encoded the raw register value
	 * @return the HH:mm string
	 */
	static String formatEncodedTime(int encoded) {
		return ScheduleWindow.formatEncodedTime(encoded);
	}

	private SafeWriteHandler.State aggregateWriteState() {
		var result = SafeWriteHandler.State.IDLE;
		for (var handler : this.writeHandlers) {
			var state = handler.getState();
			if (writeStatePrecedence(state) > writeStatePrecedence(result)) {
				result = state;
			}
		}
		for (var windowState : new SafeWriteHandler.State[] {
				scheduleWindowAsWriteState(this.chargeWindow.getState()),
				scheduleWindowAsWriteState(this.dischargeWindow.getState()) }) {
			if (writeStatePrecedence(windowState) > writeStatePrecedence(result)) {
				result = windowState;
			}
		}
		return result;
	}

	/**
	 * Projects a {@link ScheduleWindow.State} onto the shared
	 * {@link SafeWriteHandler.State} scale so both write paths aggregate into the
	 * single {@code SAFE_WRITE_STATE} channel.
	 *
	 * @param state the schedule-window state
	 * @return the equivalent settings-write state
	 */
	static SafeWriteHandler.State scheduleWindowAsWriteState(ScheduleWindow.State state) {
		return switch (state) {
		case FAILED -> SafeWriteHandler.State.FAILED;
		// The intermediate *_VERIFIED states are in-progress (more of the coherent
		// operation is still pending), so they must not read as the terminal VERIFIED.
		case DISABLE_AWAITING_READBACK, WINDOW_AWAITING_READBACK, ENABLE_AWAITING_READBACK, DISABLE_VERIFIED,
				WINDOW_VERIFIED ->
			SafeWriteHandler.State.AWAITING_READBACK;
		case DISABLE_QUEUED, WINDOW_QUEUED, ENABLE_QUEUED -> SafeWriteHandler.State.QUEUED;
		case DONE -> SafeWriteHandler.State.VERIFIED;
		case IDLE -> SafeWriteHandler.State.IDLE;
		case UNDEFINED -> SafeWriteHandler.State.UNDEFINED;
		};
	}

	static int writeStatePrecedence(SafeWriteHandler.State state) {
		return switch (state) {
		case FAILED -> 5;
		case AWAITING_READBACK -> 4;
		case QUEUED -> 3;
		case VERIFIED -> 2;
		case IDLE -> 1;
		case UNDEFINED -> 0;
		};
	}

	@Override
	public int getPowerPrecision() {
		return 1;
	}

	@Override
	public boolean isManaged() {
		return false;
	}

	@Override
	public boolean isOffGridPossible() {
		return true;
	}

	@Override
	public String debugLog() {
		return "State:" + this.getMachineStateChannel().value().asString() //
				+ "|Grid:" + this.getGridModeChannel().value().asString() //
				+ "|P:" + this.getActivePower().asString();
	}
}
