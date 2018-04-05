package gov.hhs.cms.bluebutton.fhirstress.backend;

import org.apache.jmeter.protocol.java.sampler.JavaSamplerContext;

import org.hl7.fhir.dstu3.model.ExplanationOfBenefit;
import org.hl7.fhir.dstu3.model.Patient;

import gov.hhs.cms.bluebutton.fhirstress.utils.BenefitIdMgr;

/**
 * This JMeter sampler will run a search for a random FHIR {@link Patient} and
 * then retrieve that {@link Patient} and all of their
 * {@link ExplanationOfBenefit}s.
 */
public final class RetrievePatients extends CustomSamplerClient {
	private BenefitIdMgr bim;

	/**
	 * @see org.apache.jmeter.protocol.java.sampler.AbstractJavaSamplerClient#setupTest(org.apache.jmeter.protocol.java.sampler.JavaSamplerContext)
	 */
	@Override
	public void setupTest(JavaSamplerContext context) {
		super.setupTest(context);
		bim = new BenefitIdMgr(1, 1, 10000, "201400000", "%05d");
	}

	/**
	 * Test Implementation
	 */
	@Override
	protected void executeTest() {
		// Removed the Rif parser to speed things up
		// if (this.parser.hasNext())
		// {
		// RifEntry entry = this.parser.next();

		// query a patient record
		client.read(Patient.class, bim.nextId());
		// }
	}
}
