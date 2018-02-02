package gov.hhs.cms.bluebutton.server.app.stu3.providers;

import java.util.Optional;

import org.hl7.fhir.dstu3.model.Address;
import org.hl7.fhir.dstu3.model.ExplanationOfBenefit;
import org.hl7.fhir.dstu3.model.ExplanationOfBenefit.BenefitBalanceComponent;
import org.hl7.fhir.dstu3.model.ExplanationOfBenefit.BenefitComponent;
import org.hl7.fhir.dstu3.model.ExplanationOfBenefit.ItemComponent;
import org.hl7.fhir.dstu3.model.Period;
import org.hl7.fhir.dstu3.model.TemporalPrecisionEnum;
import org.hl7.fhir.dstu3.model.UnsignedIntType;
import org.hl7.fhir.dstu3.model.codesystems.BenefitCategory;

import com.justdavis.karl.misc.exceptions.BadCodeMonkeyException;

import gov.hhs.cms.bluebutton.data.model.rif.HHAClaim;
import gov.hhs.cms.bluebutton.data.model.rif.HHAClaimLine;

/**
 * Transforms CCW {@link HHAClaim} instances into FHIR
 * {@link ExplanationOfBenefit} resources.
 */
final class HHAClaimTransformer {
	/**
	 * @param claim
	 *            the CCW {@link HHAClaim} to transform
	 * @return a FHIR {@link ExplanationOfBenefit} resource that represents the
	 *         specified {@link HHAClaim}
	 */
	static ExplanationOfBenefit transform(Object claim) {
		if (!(claim instanceof HHAClaim))
			throw new BadCodeMonkeyException();
		return transformClaim((HHAClaim) claim);
	}

	/**
	 * @param claimGroup
	 *            the CCW {@link HHAClaim} to transform
	 * @return a FHIR {@link ExplanationOfBenefit} resource that represents the
	 *         specified {@link HHAClaim}
	 */
	private static ExplanationOfBenefit transformClaim(HHAClaim claimGroup) {
		ExplanationOfBenefit eob = new ExplanationOfBenefit();

		// Common group level fields between all claim types
		TransformerUtils.mapEobCommonClaimHeaderData(eob, claimGroup.getClaimId(), claimGroup.getBeneficiaryId(),
				ClaimType.HHA, claimGroup.getClaimGroupId().toPlainString(), MedicareSegment.PART_B,
				Optional.of(claimGroup.getDateFrom()), Optional.of(claimGroup.getDateThrough()),
				Optional.of(claimGroup.getPaymentAmount()), claimGroup.getFinalAction());

		// map eob type codes into FHIR
		TransformerUtils.mapEobType(eob, ClaimType.HHA, Optional.of(claimGroup.getNearLineRecordIdCode()), 
				Optional.of(claimGroup.getClaimTypeCode()));

		// set the provider number which is common among several claim types
		TransformerUtils.setProviderNumber(eob, claimGroup.getProviderNumber());

		BenefitBalanceComponent benefitBalances = new BenefitBalanceComponent(
				TransformerUtils.createCodeableConcept(TransformerConstants.CODING_FHIR_BENEFIT_BALANCE,
						BenefitCategory.MEDICAL.toCode()));
		eob.getBenefitBalance().add(benefitBalances);

		// Common group level fields between Inpatient, Outpatient Hospice, HHA and SNF
		TransformerUtils.mapEobCommonGroupInpOutHHAHospiceSNF(eob, claimGroup.getOrganizationNpi(),
				claimGroup.getClaimFacilityTypeCode(), claimGroup.getClaimFrequencyCode(),
				claimGroup.getClaimNonPaymentReasonCode(), claimGroup.getPatientDischargeStatusCode(),
				claimGroup.getClaimServiceClassificationTypeCode(), claimGroup.getClaimPrimaryPayerCode(),
				claimGroup.getAttendingPhysicianNpi(), claimGroup.getTotalChargeAmount(),
				claimGroup.getPrimaryPayerPaidAmount(), claimGroup.getFiscalIntermediaryNumber());

		for (Diagnosis diagnosis : TransformerUtils.extractDiagnoses1Thru12(claimGroup.getDiagnosisPrincipalCode(),
				claimGroup.getDiagnosisPrincipalCodeVersion(), claimGroup.getDiagnosis1Code(),
				claimGroup.getDiagnosis1CodeVersion(), claimGroup.getDiagnosis2Code(),
				claimGroup.getDiagnosis2CodeVersion(), claimGroup.getDiagnosis3Code(),
				claimGroup.getDiagnosis3CodeVersion(), claimGroup.getDiagnosis4Code(),
				claimGroup.getDiagnosis4CodeVersion(), claimGroup.getDiagnosis5Code(),
				claimGroup.getDiagnosis5CodeVersion(), claimGroup.getDiagnosis6Code(),
				claimGroup.getDiagnosis6CodeVersion(), claimGroup.getDiagnosis7Code(),
				claimGroup.getDiagnosis7CodeVersion(), claimGroup.getDiagnosis8Code(),
				claimGroup.getDiagnosis8CodeVersion(), claimGroup.getDiagnosis9Code(),
				claimGroup.getDiagnosis9CodeVersion(), claimGroup.getDiagnosis10Code(),
				claimGroup.getDiagnosis10CodeVersion(), claimGroup.getDiagnosis11Code(),
				claimGroup.getDiagnosis11CodeVersion(), claimGroup.getDiagnosis12Code(),
				claimGroup.getDiagnosis12CodeVersion()))
			TransformerUtils.addDiagnosisCode(eob, diagnosis);

		for (Diagnosis diagnosis : TransformerUtils.extractDiagnoses13Thru25(claimGroup.getDiagnosis13Code(),
				claimGroup.getDiagnosis13CodeVersion(), claimGroup.getDiagnosis14Code(),
				claimGroup.getDiagnosis14CodeVersion(), claimGroup.getDiagnosis15Code(),
				claimGroup.getDiagnosis15CodeVersion(), claimGroup.getDiagnosis16Code(),
				claimGroup.getDiagnosis16CodeVersion(), claimGroup.getDiagnosis17Code(),
				claimGroup.getDiagnosis17CodeVersion(), claimGroup.getDiagnosis18Code(),
				claimGroup.getDiagnosis18CodeVersion(), claimGroup.getDiagnosis19Code(),
				claimGroup.getDiagnosis19CodeVersion(), claimGroup.getDiagnosis20Code(),
				claimGroup.getDiagnosis20CodeVersion(), claimGroup.getDiagnosis21Code(),
				claimGroup.getDiagnosis21CodeVersion(), claimGroup.getDiagnosis22Code(),
				claimGroup.getDiagnosis22CodeVersion(), claimGroup.getDiagnosis23Code(),
				claimGroup.getDiagnosis23CodeVersion(), claimGroup.getDiagnosis24Code(),
				claimGroup.getDiagnosis24CodeVersion(), claimGroup.getDiagnosis25Code(),
				claimGroup.getDiagnosis25CodeVersion()))
			TransformerUtils.addDiagnosisCode(eob, diagnosis);

		for (Diagnosis diagnosis : TransformerUtils.extractExternalDiagnoses1Thru12(
				claimGroup.getDiagnosisExternalFirstCode(), claimGroup.getDiagnosisExternalFirstCodeVersion(),
				claimGroup.getDiagnosisExternal1Code(), claimGroup.getDiagnosisExternal1CodeVersion(),
				claimGroup.getDiagnosisExternal2Code(), claimGroup.getDiagnosisExternal2CodeVersion(),
				claimGroup.getDiagnosisExternal3Code(), claimGroup.getDiagnosisExternal3CodeVersion(),
				claimGroup.getDiagnosisExternal4Code(), claimGroup.getDiagnosisExternal4CodeVersion(),
				claimGroup.getDiagnosisExternal5Code(), claimGroup.getDiagnosisExternal5CodeVersion(),
				claimGroup.getDiagnosisExternal6Code(), claimGroup.getDiagnosisExternal6CodeVersion(),
				claimGroup.getDiagnosisExternal7Code(), claimGroup.getDiagnosisExternal7CodeVersion(),
				claimGroup.getDiagnosisExternal8Code(), claimGroup.getDiagnosisExternal8CodeVersion(),
				claimGroup.getDiagnosisExternal9Code(), claimGroup.getDiagnosisExternal9CodeVersion(),
				claimGroup.getDiagnosisExternal10Code(), claimGroup.getDiagnosisExternal10CodeVersion(),
				claimGroup.getDiagnosisExternal11Code(), claimGroup.getDiagnosisExternal11CodeVersion(),
				claimGroup.getDiagnosisExternal12Code(), claimGroup.getDiagnosisExternal12CodeVersion()))
			TransformerUtils.addDiagnosisCode(eob, diagnosis);

		if (claimGroup.getClaimLUPACode().isPresent()) {
			TransformerUtils.addInformation(eob,
					TransformerUtils.createCodeableConcept(TransformerConstants.CODING_CCW_LOW_UTILIZATION_PAYMENT_ADJUSTMENT,
							String.valueOf(claimGroup.getClaimLUPACode().get())));
		}
		if (claimGroup.getClaimReferralCode().isPresent()) {
			TransformerUtils.addInformation(eob,
					TransformerUtils.createCodeableConcept(TransformerConstants.CODING_CCW_HHA_REFERRAL,
							String.valueOf(claimGroup.getClaimReferralCode().get())));
		}

		BenefitComponent totalVisitCount = new BenefitComponent(
				TransformerUtils.createCodeableConcept(TransformerConstants.CODING_BBAPI_BENEFIT_BALANCE_TYPE,
						TransformerConstants.CODED_BENEFIT_BALANCE_TYPE_VISIT_COUNT));
		totalVisitCount.setUsed(new UnsignedIntType(claimGroup.getTotalVisitCount().intValue()));
		benefitBalances.getFinancial().add(totalVisitCount);

		// Common group level fields between Inpatient, HHA, Hospice and SNF
		TransformerUtils.mapEobCommonGroupInpHHAHospiceSNF(eob, claimGroup.getCareStartDate(),
				Optional.empty(), Optional.empty(), benefitBalances);

		for (HHAClaimLine claimLine : claimGroup.getLines()) {
			ItemComponent item = eob.addItem();
			item.setSequence(claimLine.getLineNumber().intValue());

			TransformerUtils.addExtensionCoding(item, TransformerConstants.CODING_FHIR_ACT_INVOICE_GROUP,
					TransformerConstants.CODING_FHIR_ACT_INVOICE_GROUP,
					TransformerConstants.CODED_ACT_INVOICE_GROUP_CLINICAL_SERVICES_AND_PRODUCTS);

			item.setLocation(new Address().setState((claimGroup.getProviderStateCode())));

			if (claimLine.getRevCntr1stAnsiCd().isPresent()) {
				item.addAdjudication()
						.setCategory(
								TransformerUtils.createCodeableConcept(
										TransformerConstants.CODING_CCW_ADJUDICATION_CATEGORY,
										TransformerConstants.CODED_ADJUDICATION_1ST_ANSI_CD))
						.setReason(TransformerUtils.createCodeableConcept(
								TransformerConstants.CODING_CCW_ADJUDICATION_CATEGORY,
								claimLine.getRevCntr1stAnsiCd().get()));
			}

			// set hcpcs modifier codes for the claim
			TransformerUtils.setHcpcsModifierCodes(item, claimLine.getHcpcsCode(),
					claimLine.getHcpcsInitialModifierCode(), claimLine.getHcpcsSecondModifierCode(), Optional.empty());

			// Common item level fields between Inpatient, Outpatient, HHA, Hospice and SNF
			TransformerUtils.mapEobCommonItemRevenue(item, eob, claimLine.getRevenueCenterCode(),
					claimLine.getRateAmount(),
					claimLine.getTotalChargeAmount(), claimLine.getNonCoveredChargeAmount(), claimLine.getUnitCount(),
					claimLine.getNationalDrugCodeQuantity(), claimLine.getNationalDrugCodeQualifierCode(),
					claimLine.getRevenueCenterRenderingPhysicianNPI());
			
			// Common item level fields between Outpatient, HHA and Hospice
			TransformerUtils.mapEobCommonItemRevenueOutHHAHospice(item, claimLine.getRevenueCenterDate(), claimLine.getPaymentAmount());
			
			// set revenue center status indicator codes for the claim
			TransformerUtils.addExtensionCoding(item.getRevenue(),
					TransformerConstants.CODING_CMS_REVENUE_CENTER_STATUS_INDICATOR_CODE,
					TransformerConstants.CODING_CMS_REVENUE_CENTER_STATUS_INDICATOR_CODE, claimLine.getStatusCode().get());
			
			// Common group level fields between Inpatient, HHA, Hospice and SNF
			TransformerUtils.mapEobCommonGroupInpHHAHospiceSNFCoinsurance(item, claimLine.getDeductibleCoinsuranceCd());

		}
		return eob;
	}

}
