package io.openems.edge.ess.srne.ess;

import static io.openems.edge.ess.srne.SrneConstants.DEFAULT_UNIT_ID;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import io.openems.edge.bridge.modbus.test.DummyModbusBridge;
import io.openems.edge.common.test.ComponentTest;
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
						.build()) //
				.deactivate();
	}

	@Test
	public void testValidatedMachineState() {
		assertEquals(2, MachineState.RUNNING_MAINS_BYPASS.getValue());
		assertTrue(MachineState.RUNNING_MAINS_BYPASS.isVerified());
	}
}
