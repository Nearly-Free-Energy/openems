package io.openems.edge.ess.srne.charger;

import static io.openems.edge.ess.srne.SrneConstants.DEFAULT_UNIT_ID;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

import io.openems.edge.bridge.modbus.test.DummyModbusBridge;
import io.openems.edge.common.test.AbstractComponentTest.TestCase;
import io.openems.edge.common.test.ComponentTest;
import io.openems.edge.ess.dccharger.api.EssDcCharger;

public class SrneChargerImplTest {

	private static ComponentTest activate(DummyModbusBridge bridge) throws Exception {
		return new ComponentTest(new SrneChargerImpl()) //
				.addReference("setModbus", bridge) //
				.activate(MyConfig.create() //
						.setId("charger0") //
						.setModbusId("modbus0") //
						.setModbusUnitId(DEFAULT_UNIT_ID) //
						.build());
	}

	private static DummyModbusBridge bridge(int pv1V, int pv1A, int pv1W, int pv2V, int pv2A, int pv2W,
			int todayX10) {
		return new DummyModbusBridge("modbus0") //
				.withRegisters(0x0107, //
						pv1V, pv1A, pv1W, //
						/* 0x010A-0x010E: not PV input */ 0, 0, 0, 0, 0, //
						pv2V, pv2A, pv2W) //
				.withRegisters(0xF02F, todayX10);
	}

	@Test
	public void testLiveMiddayReadOnPv1() throws Exception {
		// gw-pi2, 27 Oct 2026 09:41 EAT: 366.0 V, 1.2 A, 470 W; PV2 not connected;
		// 0.5 kWh today
		activate(bridge(3_660, 12, 470, 0, 0, 0, 5)) //
				.next(new TestCase(), 4) //
				.next(new TestCase() //
						.output(SrneCharger.ChannelId.PV1_VOLTAGE, 366_000) //
						.output(SrneCharger.ChannelId.PV1_CURRENT, 1_200) //
						.output(SrneCharger.ChannelId.PV1_POWER, 470) //
						.output(SrneCharger.ChannelId.PV2_VOLTAGE, 0) //
						.output(SrneCharger.ChannelId.PV2_CURRENT, 0) //
						.output(SrneCharger.ChannelId.PV2_POWER, 0) //
						.output(SrneCharger.ChannelId.PV_ENERGY_TODAY, 500) //
						.output(EssDcCharger.ChannelId.ACTUAL_POWER, 470) //
						.output(EssDcCharger.ChannelId.VOLTAGE, 366_000) //
						.output(EssDcCharger.ChannelId.CURRENT, 1_200) //
						.output(EssDcCharger.ChannelId.ACTUAL_ENERGY, null)) //
				.deactivate();
	}

	@Test
	public void testBothMpptsAreSummed() throws Exception {
		// 1500 W + 2000 W; 6.5 kWh today
		activate(bridge(3_600, 42, 1_500, 3_500, 57, 2_000, 65)) //
				.next(new TestCase(), 4) //
				.next(new TestCase() //
						.output(SrneCharger.ChannelId.PV2_VOLTAGE, 350_000) //
						.output(SrneCharger.ChannelId.PV2_CURRENT, 5_700) //
						.output(SrneCharger.ChannelId.PV2_POWER, 2_000) //
						.output(SrneCharger.ChannelId.PV_ENERGY_TODAY, 6_500) //
						.output(EssDcCharger.ChannelId.ACTUAL_POWER, 3_500) //
						.output(EssDcCharger.ChannelId.CURRENT, 9_900) //
						.output(EssDcCharger.ChannelId.VOLTAGE, 360_000) //
						.output(EssDcCharger.ChannelId.ACTUAL_ENERGY, null)) //
				.deactivate();
	}

	@Test
	public void testNightReadsZeroWatt() throws Exception {
		activate(bridge(0, 0, 0, 0, 0, 0, 0)) //
				.next(new TestCase(), 4) //
				.next(new TestCase() //
						.output(EssDcCharger.ChannelId.ACTUAL_POWER, 0) //
						.output(EssDcCharger.ChannelId.CURRENT, 0) //
						.output(SrneCharger.ChannelId.PV_ENERGY_TODAY, 0) //
						.output(EssDcCharger.ChannelId.ACTUAL_ENERGY, null)) //
				.deactivate();
	}

	@Test
	public void testInvalidRegistersGiveNoValue() throws Exception {
		// 0xFFFF on PV1 power must not become 65535 W, and must null the total
		activate(bridge(3_660, 0xFFFF, 0xFFFF, 0, 0, 0, 0xFFFF)) //
				.next(new TestCase(), 4) //
				.next(new TestCase() //
						.output(SrneCharger.ChannelId.PV1_VOLTAGE, 366_000) //
						.output(SrneCharger.ChannelId.PV1_CURRENT, null) //
						.output(SrneCharger.ChannelId.PV1_POWER, null) //
						.output(SrneCharger.ChannelId.PV_ENERGY_TODAY, null) //
						.output(EssDcCharger.ChannelId.ACTUAL_POWER, null) //
						.output(EssDcCharger.ChannelId.CURRENT, null) //
						.output(EssDcCharger.ChannelId.ACTUAL_ENERGY, null)) //
				.deactivate();
	}

	@Test
	public void testNoModbusDataGivesNoValue() throws Exception {
		activate(new DummyModbusBridge("modbus0")) //
				.next(new TestCase(), 4) //
				.next(new TestCase() //
						.output(EssDcCharger.ChannelId.ACTUAL_POWER, null) //
						.output(EssDcCharger.ChannelId.ACTUAL_ENERGY, null)) //
				.deactivate();
	}

	@Test
	public void testSumOrNull() {
		assertEquals(Integer.valueOf(3_500), SrneChargerImpl.sumOrNull(1_500, 2_000));
		assertEquals(Integer.valueOf(0), SrneChargerImpl.sumOrNull(0, 0));
		assertNull(SrneChargerImpl.sumOrNull(null, 2_000));
		assertNull(SrneChargerImpl.sumOrNull(1_500, null));
	}
}
