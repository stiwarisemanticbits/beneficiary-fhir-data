package gov.cms.bfd.server.war.r4.providers;

import static java.util.Objects.requireNonNull;

import com.codahale.metrics.MetricRegistry;
import com.codahale.metrics.Timer;
import com.newrelic.api.agent.Trace;
import gov.cms.bfd.data.npi.lookup.NPIOrgLookup;
import gov.cms.bfd.model.codebook.data.CcwCodebookVariable;
import gov.cms.bfd.model.rif.HHAClaim;
import gov.cms.bfd.model.rif.HHAClaimLine;
import gov.cms.bfd.server.war.commons.C4BBInstutionalClaimSubtypes;
import gov.cms.bfd.server.war.commons.ClaimType;
import gov.cms.bfd.server.war.commons.MedicareSegment;
import gov.cms.bfd.server.war.commons.ProfileConstants;
import gov.cms.bfd.server.war.commons.carin.C4BBClaimProfessionalAndNonClinicianCareTeamRole;
import gov.cms.bfd.server.war.commons.carin.C4BBOrganizationIdentifierType;
import gov.cms.bfd.server.war.commons.carin.C4BBPractitionerIdentifierType;
import gov.cms.bfd.sharedutils.exceptions.BadCodeMonkeyException;
import java.util.Arrays;
import java.util.Map;
import java.util.Optional;
import org.hl7.fhir.r4.model.ExplanationOfBenefit;
import org.hl7.fhir.r4.model.ExplanationOfBenefit.ItemComponent;
import org.hl7.fhir.r4.model.Quantity;
import org.springframework.stereotype.Component;

/** Transforms CCW {@link HHAClaim} instances into FHIR {@link ExplanationOfBenefit} resources. */
@Component
final class HHAClaimTransformerV2 implements ClaimTransformerInterfaceV2 {

  /** The Metric registry. */
  private final MetricRegistry metricRegistry;

  /** The {@link NPIOrgLookup} is to provide what npi Org Name to Lookup to return. */
  private final NPIOrgLookup npiOrgLookup;

  /**
   * Instantiates a new transformer.
   *
   * <p>Spring will wire this into a singleton bean during the initial component scan, and it will
   * be injected properly into places that need it, so this constructor should only be explicitly
   * called by tests.
   *
   * @param metricRegistry the metric registry
   * @param npiOrgLookup the npi org lookup
   */
  public HHAClaimTransformerV2(MetricRegistry metricRegistry, NPIOrgLookup npiOrgLookup) {
    this.metricRegistry = requireNonNull(metricRegistry);
    this.npiOrgLookup = requireNonNull(npiOrgLookup);
  }

  /**
   * Transforms a {@link HHAClaim} into a FHIR {@link ExplanationOfBenefit}.
   *
   * @param claim the {@link Object} to use
   * @param includeTaxNumber exists to satisfy {@link ClaimTransformerInterfaceV2}; ignored.
   * @return a FHIR {@link ExplanationOfBenefit} resource.
   */
  @Trace
  @Override
  public ExplanationOfBenefit transform(Object claim, boolean includeTaxNumber) {
    if (!(claim instanceof HHAClaim)) {
      throw new BadCodeMonkeyException();
    }
    ExplanationOfBenefit eob = null;
    try (Timer.Context timer =
        metricRegistry
            .timer(MetricRegistry.name(HHAClaimTransformerV2.class.getSimpleName(), "transform"))
            .time()) {
      eob = transformClaim((HHAClaim) claim);
    }
    return eob;
  }

  /**
   * Transforms a specified {@link HHAClaim} into a FHIR {@link ExplanationOfBenefit}.
   *
   * @param claimGroup the CCW {@link HHAClaim} to transform
   * @return a FHIR {@link ExplanationOfBenefit} resource that represents the specified {@link
   *     HHAClaim}
   */
  private ExplanationOfBenefit transformClaim(HHAClaim claimGroup) {
    ExplanationOfBenefit eob = new ExplanationOfBenefit();

    // Required values not directly mapped
    eob.getMeta().addProfile(ProfileConstants.C4BB_EOB_NONCLINICIAN_PROFILE_URL);

    // Common group level fields between all claim types
    // Claim Type + Claim ID => ExplanationOfBenefit.id
    // CLM_ID => ExplanationOfBenefit.identifier
    // CLM_GRP_ID => ExplanationOfBenefit.identifier
    // BENE_ID + Coverage Type => ExplanationOfBenefit.insurance.coverage
    // BENE_ID => ExplanationOfBenefit.patient (reference)
    // FINAL_ACTION => ExplanationOfBenefit.status
    // CLM_FROM_DT => ExplanationOfBenefit.billablePeriod.start
    // CLM_THRU_DT => ExplanationOfBenefit.billablePeriod.end
    // CLM_PMT_AMT => ExplanationOfBenefit.payment.amount
    TransformerUtilsV2.mapEobCommonClaimHeaderData(
        eob,
        claimGroup.getClaimId(),
        claimGroup.getBeneficiaryId(),
        ClaimType.HHA,
        String.valueOf(claimGroup.getClaimGroupId()),
        MedicareSegment.PART_B,
        Optional.of(claimGroup.getDateFrom()),
        Optional.of(claimGroup.getDateThrough()),
        Optional.of(claimGroup.getPaymentAmount()),
        claimGroup.getFinalAction());

    // NCH_WKLY_PROC_DT => ExplanationOfBenefit.supportinginfo.timingDate
    TransformerUtilsV2.addInformation(
        eob,
        TransformerUtilsV2.createInformationRecievedDateSlice(
            eob,
            CcwCodebookVariable.NCH_WKLY_PROC_DT,
            Optional.of(claimGroup.getWeeklyProcessDate())));

    // map eob type codes into FHIR
    // NCH_CLM_TYPE_CD => ExplanationOfBenefit.type.coding
    // EOB Type => ExplanationOfBenefit.type.coding
    // Claim Type => ExplanationOfBenefit.type.coding
    // NCH_NEAR_LINE_REC_IDENT_CD => ExplanationOfBenefit.extension
    TransformerUtilsV2.mapEobType(
        eob,
        ClaimType.HHA,
        Optional.of(claimGroup.getNearLineRecordIdCode()),
        Optional.of(claimGroup.getClaimTypeCode()));

    // PRVDR_NUM => ExplanationOfBenefit.provider.identifier
    TransformerUtilsV2.addProviderSlice(
        eob,
        C4BBOrganizationIdentifierType.PRN,
        claimGroup.getProviderNumber(),
        claimGroup.getLastUpdated());

    // Common group level fields between Inpatient, Outpatient Hospice, HHA and SNF
    // ORG_NPI_NUM => ExplanationOfBenefit.provider
    // ORG NPI NUM Display => ExplanationOfBenefit.organization.display
    // CLM_FAC_TYPE_CD => ExplanationOfBenefit.facility.extension
    // CLM_FREQ_CD => ExplanationOfBenefit.supportingInfo
    // CLM_MDCR_NON_PMT_RSN_CD => ExplanationOfBenefit.extension
    // PTNT_DSCHRG_STUS_CD => ExplanationOfBenefit.supportingInfo
    // CLM_SRVC_CLSFCTN_TYPE_CD => ExplanationOfBenefit.extension
    // NCH_PRMRY_PYR_CD => ExplanationOfBenefit.supportingInfo
    // CLM_TOT_CHRG_AMT => ExplanationOfBenefit.total.amount
    // NCH_PRMRY_PYR_CLM_PD_AMT => ExplanationOfBenefit.benefitBalance.financial
    // FI_DOC_CLM_CNTL_NUM => ExplanationOfBenefit.extension
    // FI_CLM_PROC_DT => ExplanationOfBenefit.extension
    // C4BBInstutionalClaimSubtypes.Inpatient for HHA Claims
    // CLAIM_QUERY_CODE => ExplanationOfBenefit.billablePeriod.extension
    TransformerUtilsV2.mapEobCommonGroupInpOutHHAHospiceSNF(
        eob,
        claimGroup.getOrganizationNpi(),
        npiOrgLookup.retrieveNPIOrgDisplay(claimGroup.getOrganizationNpi()),
        claimGroup.getClaimFacilityTypeCode(),
        claimGroup.getClaimFrequencyCode(),
        claimGroup.getClaimNonPaymentReasonCode(),
        claimGroup.getPatientDischargeStatusCode(),
        claimGroup.getClaimServiceClassificationTypeCode(),
        claimGroup.getClaimPrimaryPayerCode(),
        claimGroup.getTotalChargeAmount(),
        claimGroup.getPrimaryPayerPaidAmount(),
        claimGroup.getFiscalIntermediaryNumber(),
        claimGroup.getLastUpdated(),
        claimGroup.getFiDocumentClaimControlNumber(),
        claimGroup.getFiscalIntermediaryClaimProcessDate(),
        C4BBInstutionalClaimSubtypes.Inpatient,
        claimGroup.getClaimQueryCode());

    // CLM_PPS_IND_CODE => ExplanationOfBenefit.supportingInfo
    TransformerUtilsV2.addInformationWithCode(
        eob,
        CcwCodebookVariable.CLM_PPS_IND_CD,
        CcwCodebookVariable.CLM_PPS_IND_CD,
        claimGroup.getProspectivePaymentCode());

    // Handle Diagnosis
    // PRNCPAL_DGNS_CD => diagnosis.diagnosisCodeableConcept
    // PRNCPAL_DGNS_VRSN_CD => diagnosis.diagnosisCodeableConcept
    // ICD_DGNS_CD(1-25) => diagnosis.diagnosisCodeableConcept
    // ICD_DGNS_VRSN_CD(1-25) => diagnosis.diagnosisCodeableConcept
    // FST_DGNS_E_CD => diagnosis.diagnosisCodeableConcept
    // FST_DGNS_E_VRSN_CD => diagnosis.diagnosisCodeableConcept
    // ICD_DGNS_E_CD(1-12) => diagnosis.diagnosisCodeableConcept
    // ICD_DGNS_E_VRSN_CD(1-12) => diagnosis.diagnosisCodeableConcept
    DiagnosisUtilV2.extractDiagnoses(
            claimGroup.getDiagnosisCodes(), claimGroup.getDiagnosisCodeVersions(), Map.of())
        .stream()
        .forEach(diagnosis -> DiagnosisUtilV2.addDiagnosisCode(eob, diagnosis, ClaimType.HHA));

    // Map care team
    // AT_PHYSN_NPI => ExplanationOfBenefit.careTeam.provider
    // AT_PHYSN_UPIN => ExplanationOfBenefit.careTeam.provider
    TransformerUtilsV2.mapCareTeam(
        eob,
        claimGroup.getAttendingPhysicianNpi(),
        Optional.empty(),
        Optional.empty(),
        claimGroup.getAttendingPhysicianUpin(),
        Optional.empty(),
        Optional.empty());

    // CLM_HHA_LUPA_IND_CD => ExplanationOfBenefit.supportinginfo.code
    claimGroup
        .getClaimLUPACode()
        .ifPresent(
            c ->
                TransformerUtilsV2.addInformationWithCode(
                    eob,
                    CcwCodebookVariable.CLM_HHA_LUPA_IND_CD,
                    CcwCodebookVariable.CLM_HHA_LUPA_IND_CD,
                    c));

    // CLM_HHA_RFRL_CD => ExplanationOfBenefit.supportinginfo.code
    claimGroup
        .getClaimReferralCode()
        .ifPresent(
            c ->
                TransformerUtilsV2.addInformationWithCode(
                    eob,
                    CcwCodebookVariable.CLM_HHA_RFRL_CD,
                    CcwCodebookVariable.CLM_HHA_RFRL_CD,
                    c));

    // CLM_HHA_TOT_VISIT_CNT => ExplanationOfBenefit.supportinginfo.value[x]
    TransformerUtilsV2.addInformation(eob, CcwCodebookVariable.CLM_HHA_TOT_VISIT_CNT)
        .setValue(new Quantity(claimGroup.getTotalVisitCount().intValue()));

    // CLM_ADMSN_DT => ExplanationOfBenefit.supportingInfo:admissionperiod
    TransformerUtilsV2.addInformation(
        eob,
        TransformerUtilsV2.createInformationAdmPeriodSlice(
            eob, claimGroup.getCareStartDate(), Optional.empty()));

    for (HHAClaimLine line : claimGroup.getLines()) {
      ItemComponent item = eob.addItem();

      // Override the default sequence
      // CLM_LINE_NUM => item.sequence
      item.setSequence(line.getLineNumber());

      // PRVDR_STATE_CD => item.location
      TransformerUtilsV2.addLocationState(item, claimGroup.getProviderStateCode());

      // HCPCS_CD => ExplanationOfBenefit.item.productOrService
      // HCPCS_1ST_MDFR_CD => ExplanationOfBenefit.item.modifier
      // HCPCS_2ND_MDFR_CD => ExplanationOfBenefit.item.modifier
      TransformerUtilsV2.mapHcpcs(
          eob,
          item,
          line.getHcpcsCode(),
          Optional.empty(),
          Arrays.asList(line.getHcpcsInitialModifierCode(), line.getHcpcsSecondModifierCode()));

      // REV_CNTR_1ST_ANSI_CD => ExplanationOfBenefit.item.adjudication
      TransformerUtilsV2.addAdjudication(
          item,
          TransformerUtilsV2.createAdjudicationDenialReasonSlice(
              eob, CcwCodebookVariable.REV_CNTR_1ST_ANSI_CD, line.getRevCntr1stAnsiCd()));

      // REV_CNTR => ExplanationOfBenefit.item.revenue
      // REV_CNTR_RATE_AMT => ExplanationOfBenefit.item.adjudication
      // REV_CNTR_TOT_CHRG_AMT => ExplanationOfBenefit.item.adjudication
      // REV_CNTR_NCVRD_CHRG_AMT => ExplanationOfBenefit.item.adjudication
      // REV_CNTR_NDC_QTY => ExplanationOfBenefit.item.quantity
      // REV_CNTR_NDC_QTY_QLFR_CD => ExplanationOfBenefit.modifier
      // REV_CNTR_UNIT_CNT => ExplanationOfBenefit.item.extension.valueQuantity
      TransformerUtilsV2.mapEobCommonItemRevenue(
          item,
          eob,
          line.getRevenueCenterCode(),
          line.getRateAmount(),
          line.getTotalChargeAmount(),
          Optional.of(line.getNonCoveredChargeAmount()),
          line.getNationalDrugCodeQuantity(),
          line.getNationalDrugCodeQualifierCode(),
          line.getUnitCount());

      // Common item level fields between Outpatient, HHA and Hospice
      // REV_CNTR_DT => ExplanationOfBenefit.item.servicedDate
      // REV_CNTR_PMT_AMT_AMT => ExplanationOfBenefit.item.adjudication
      TransformerUtilsV2.mapEobCommonItemRevenueOutHHAHospice(
          item, line.getRevenueCenterDate(), line.getPaymentAmount());

      // REV_CNTR_DDCTBL_COINSRNC_CD => item.revenue
      TransformerUtilsV2.addItemRevenue(
          item,
          eob,
          CcwCodebookVariable.REV_CNTR_DDCTBL_COINSRNC_CD,
          line.getDeductibleCoinsuranceCd());

      // RNDRNG_PHYSN_UPIN => ExplanationOfBenefit.careTeam.provider
      TransformerUtilsV2.addCareTeamMember(
          eob,
          item,
          C4BBPractitionerIdentifierType.UPIN,
          C4BBClaimProfessionalAndNonClinicianCareTeamRole.PERFORMING,
          line.getRevenueCenterRenderingPhysicianUPIN());

      // RNDRNG_PHYSN_NPI => ExplanationOfBenefit.careTeam.provider
      TransformerUtilsV2.addCareTeamMember(
          eob,
          item,
          C4BBPractitionerIdentifierType.NPI,
          C4BBClaimProfessionalAndNonClinicianCareTeamRole.PERFORMING,
          line.getRevenueCenterRenderingPhysicianNPI());

      // REV_CNTR_STUS_IND_CD => ExplanationOfBenefit.item.revenue.extension
      TransformerUtilsV2.mapEobCommonItemRevenueStatusCode(item, eob, line.getStatusCode());
    }

    TransformerUtilsV2.setLastUpdated(eob, claimGroup.getLastUpdated());

    return eob;
  }
}
