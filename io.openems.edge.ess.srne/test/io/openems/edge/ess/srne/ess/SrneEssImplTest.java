package io.openems.edge.ess.srne.ess;

import static io.openems.edge.ess.srne.SrneConstants.DEFAULT_UNIT_ID;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import io.openems.edge.bridge.modbus.test.DummyModbusBridge;
import io.openems.edge.common.test.AbstractComponentTest.TestCase;
import io.openems.edge.common.test.ComponentTest;
import io.openems.edge.ess.api.HybridEss;
import io.openems.edge.ess.api.SymmetricEss;
import io.openems.edge.ess.srne.common.enums.MachineState;

public class SrneEssImplTest {

	@Test
	public void testActivation() throws Exception {
		var sut = new SrneEssImpl();
		new ComponentTest(sut) //
				.addReference("setModbus", new DummyModbusBridge("modbus0")) //
				.activate(MyConfig.create() //
						.setId("ess0") //
						.setModbusId("modbus0") //
						.setModbusUnitId(DEFAULT_UNIT_ID) //
						.setCapacity(20_000) //
						.setMaxApparentPower(12_000) //
						.build()) //
				.next(new TestCase() //
						.output(SymmetricEss.ChannelId.CAPACITY, 20_000) //
						.output(SymmetricEss.ChannelId.MAX_APPARENT_POWER, 12_000)) //
				.deactivate();
	}

	@Test
	public void testReadValidatedEssRegisters() throws Exception {
		new ComponentTest(new SrneEssImpl()) //
				.addReference("setModbus", new DummyModbusBridge("modbus0") //
						.withRegisters(0x0100, //
								/* SOC */ 76, //
								/* BATTERY_VOLTAGE: 52.4 V */ 524, //
								/* BATTERY_CURRENT: -12.3 A */ -123, //
								/* unused device temperature */ 0) //
						.withRegister(0x021B, 3_450)) //
				.activate(MyConfig.create() //
						.setId("ess0") //
						.setModbusId("modbus0") //
						.setModbusUnitId(DEFAULT_UNIT_ID) //
						.setCapacity(20_000) //
						.setMaxApparentPower(12_000) //
						.build()) //
				.next(new TestCase(), 4) //
				.next(new TestCase() //
						.output(SymmetricEss.ChannelId.SOC, 76) //
						.output(SrneEss.ChannelId.BATTERY_VOLTAGE, 52_400) //
						.output(SrneEss.ChannelId.BATTERY_CURRENT, -12_300) //
						.output(HybridEss.ChannelId.DC_DISCHARGE_POWER, 645) //
						.output(SymmetricEss.ChannelId.ACTIVE_POWER, 3_450)) //
				.deactivate();
	}

	@Test
	public void testValidatedMachineState() {
		assertEquals(2, MachineState.RUNNING_MAINS_BYPASS.getValue());
		assertTrue(MachineState.RUNNING_MAINS_BYPASS.isVerified());
	}
}
