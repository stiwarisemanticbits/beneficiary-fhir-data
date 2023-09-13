package gov.cms.bfd.server.war.stu3.providers;

import ca.uhn.fhir.model.api.TemporalPrecisionEnum;
import ca.uhn.fhir.model.primitive.IdDt;
import ca.uhn.fhir.parser.DataFormatException;
import ca.uhn.fhir.rest.api.Constants;
import ca.uhn.fhir.rest.api.server.RequestDetails;
import ca.uhn.fhir.rest.param.ParamPrefixEnum;
import ca.uhn.fhir.rest.server.exceptions.InvalidRequestException;
import com.google.common.base.Strings;
import gov.cms.bfd.model.codebook.data.CcwCodebookMissingVariable;
import gov.cms.bfd.model.codebook.data.CcwCodebookVariable;
import gov.cms.bfd.model.codebook.model.CcwCodebookInterface;
import gov.cms.bfd.model.codebook.model.Value;
import gov.cms.bfd.model.codebook.model.Variable;
import gov.cms.bfd.model.rif.entities.Beneficiary;
import gov.cms.bfd.model.rif.entities.CarrierClaim;
import gov.cms.bfd.model.rif.entities.CarrierClaimColumn;
import gov.cms.bfd.model.rif.entities.CarrierClaimLine;
import gov.cms.bfd.model.rif.entities.DMEClaim;
import gov.cms.bfd.model.rif.entities.DMEClaimColumn;
import gov.cms.bfd.model.rif.entities.DMEClaimLine;
import gov.cms.bfd.model.rif.entities.HHAClaim;
import gov.cms.bfd.model.rif.entities.HHAClaimColumn;
import gov.cms.bfd.model.rif.entities.HHAClaimLine;
import gov.cms.bfd.model.rif.entities.HospiceClaim;
import gov.cms.bfd.model.rif.entities.HospiceClaimColumn;
import gov.cms.bfd.model.rif.entities.HospiceClaimLine;
import gov.cms.bfd.model.rif.entities.InpatientClaim;
import gov.cms.bfd.model.rif.entities.InpatientClaimColumn;
import gov.cms.bfd.model.rif.entities.InpatientClaimLine;
import gov.cms.bfd.model.rif.entities.OutpatientClaim;
import gov.cms.bfd.model.rif.entities.OutpatientClaimColumn;
import gov.cms.bfd.model.rif.entities.OutpatientClaimLine;
import gov.cms.bfd.model.rif.entities.SNFClaim;
import gov.cms.bfd.model.rif.entities.SNFClaimColumn;
import gov.cms.bfd.model.rif.entities.SNFClaimLine;
import gov.cms.bfd.model.rif.parse.InvalidRifValueException;
import gov.cms.bfd.server.sharedutils.BfdMDC;
import gov.cms.bfd.server.war.commons.CCWProcedure;
import gov.cms.bfd.server.war.commons.CCWUtils;
import gov.cms.bfd.server.war.commons.ClaimType;
import gov.cms.bfd.server.war.commons.Diagnosis;
import gov.cms.bfd.server.war.commons.Diagnosis.DiagnosisLabel;
import gov.cms.bfd.server.war.commons.IdentifierType;
import gov.cms.bfd.server.war.commons.LinkBuilder;
import gov.cms.bfd.server.war.commons.LoggingUtils;
import gov.cms.bfd.server.war.commons.MedicareSegment;
import gov.cms.bfd.server.war.commons.OffsetLinkBuilder;
import gov.cms.bfd.server.war.commons.QueryUtils;
import gov.cms.bfd.server.war.commons.TransformerConstants;
import gov.cms.bfd.server.war.stu3.providers.BeneficiaryTransformer.CurrencyIdentifier;
import gov.cms.bfd.sharedutils.exceptions.BadCodeMonkeyException;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.io.UnsupportedEncodingException;
import java.math.BigDecimal;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import javax.annotation.Nullable;
import org.hl7.fhir.dstu3.model.Bundle;
import org.hl7.fhir.dstu3.model.Bundle.BundleEntryComponent;
import org.hl7.fhir.dstu3.model.CodeableConcept;
import org.hl7.fhir.dstu3.model.Coding;
import org.hl7.fhir.dstu3.model.Coverage;
import org.hl7.fhir.dstu3.model.DateType;
import org.hl7.fhir.dstu3.model.DomainResource;
import org.hl7.fhir.dstu3.model.ExplanationOfBenefit;
import org.hl7.fhir.dstu3.model.ExplanationOfBenefit.AdjudicationComponent;
import org.hl7.fhir.dstu3.model.ExplanationOfBenefit.BenefitBalanceComponent;
import org.hl7.fhir.dstu3.model.ExplanationOfBenefit.BenefitComponent;
import org.hl7.fhir.dstu3.model.ExplanationOfBenefit.CareTeamComponent;
import org.hl7.fhir.dstu3.model.ExplanationOfBenefit.DiagnosisComponent;
import org.hl7.fhir.dstu3.model.ExplanationOfBenefit.ExplanationOfBenefitStatus;
import org.hl7.fhir.dstu3.model.ExplanationOfBenefit.ItemComponent;
import org.hl7.fhir.dstu3.model.ExplanationOfBenefit.ProcedureComponent;
import org.hl7.fhir.dstu3.model.ExplanationOfBenefit.SupportingInformationComponent;
import org.hl7.fhir.dstu3.model.Extension;
import org.hl7.fhir.dstu3.model.Identifier;
import org.hl7.fhir.dstu3.model.Money;
import org.hl7.fhir.dstu3.model.Observation;
import org.hl7.fhir.dstu3.model.Observation.ObservationStatus;
import org.hl7.fhir.dstu3.model.Patient;
import org.hl7.fhir.dstu3.model.Period;
import org.hl7.fhir.dstu3.model.Practitioner;
import org.hl7.fhir.dstu3.model.Quantity;
import org.hl7.fhir.dstu3.model.Reference;
import org.hl7.fhir.dstu3.model.ReferralRequest;
import org.hl7.fhir.dstu3.model.ReferralRequest.ReferralRequestRequesterComponent;
import org.hl7.fhir.dstu3.model.ReferralRequest.ReferralRequestStatus;
import org.hl7.fhir.dstu3.model.Resource;
import org.hl7.fhir.dstu3.model.SimpleQuantity;
import org.hl7.fhir.dstu3.model.UnsignedIntType;
import org.hl7.fhir.dstu3.model.codesystems.BenefitCategory;
import org.hl7.fhir.dstu3.model.codesystems.ClaimCareteamrole;
import org.hl7.fhir.instance.model.api.IAnyResource;
import org.hl7.fhir.instance.model.api.IBaseExtension;
import org.hl7.fhir.instance.model.api.IBaseHasExtensions;
import org.hl7.fhir.instance.model.api.IBaseResource;
import org.hl7.fhir.r4.model.Organization;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Contains shared methods used to transform CCW JPA entities (e.g. {@link Beneficiary}) into FHIR
 * resources (e.g. {@link Patient}).
 */
public final class TransformerUtils {
  private static final Logger LOGGER = LoggerFactory.getLogger(TransformerUtils.class);

  /**
   * Tracks the {@link CcwCodebookInterface} that have already had code lookup failures due to
   * missing {@link Value} matches. Why track this? To ensure that we don't spam log events for
   * failed lookups over and over and over. This was needed to fix CBBF-162, where those log events
   * were flooding our logs and filling up the drive.
   *
   * @see TransformerUtils#calculateCodingDisplay(IAnyResource, CcwCodebookInterface, String)
   */
  private static final Set<CcwCodebookInterface> codebookLookupMissingFailures = new HashSet<>();

  /**
   * Tracks the {@link CcwCodebookInterface} that have already had code lookup failures due to
   * duplicate {@link Value} matches. Why track this? To ensure that we don't spam log events for
   * failed lookups over and over and over. This was needed to fix CBBF-162, where those log events
   * were flooding our logs and filling up the drive.
   *
   * @see TransformerUtils#calculateCodingDisplay(IAnyResource, CcwCodebookInterface, String)
   */
  private static final Set<CcwCodebookInterface> codebookLookupDuplicateFailures = new HashSet<>();

  /** Tracks the icd codes that have already had code lookup failures. */
  private static final Set<String> icdCodeLookupMissingFailures = new HashSet<>();

  /** Stores the diagnosis ICD codes and their display values. */
  private static Map<String, String> icdMap = null;

  /** Stores the procedure codes and their display values. */
  private static Map<String, String> procedureMap = null;

  /** Tracks the procedure codes that have already had code lookup failures. */
  private static final Set<String> procedureLookupMissingFailures = new HashSet<>();

  /** Stores the NPI codes and their display values. */
  private static Map<String, String> npiMap = null;

  /** Tracks the NPI codes that have already had code lookup failures. */
  private static final Set<String> npiCodeLookupMissingFailures = new HashSet<>();

  /**
   * Adds an adjudication total to the specified {@link ExplanationOfBenefit}.
   *
   * @param eob the {@link ExplanationOfBenefit} that the adjudication total should be part of
   * @param categoryVariable the {@link CcwCodebookInterface} to map to the adjudication's <code>
   *          category</code>
   * @param amountValue the {@link Money#getValue()} for the adjudication total
   */
  static void addAdjudicationTotal(
      ExplanationOfBenefit eob,
      CcwCodebookInterface categoryVariable,
      Optional<? extends Number> amountValue) {
    /*
     * TODO Once we switch to STU4 (expected >= Q3 2018), remap these to the new
     * `ExplanationOfBenefit.total` field. In anticipation of that, the
     * CcwCodebookVariable param
     * here is named `category`: right now it's used for the `Extension.url` but can
     * be changed to
     * `ExplanationOfBenefit.total.category` once this mapping is moved to STU4.
     */

    String extensionUrl = CCWUtils.calculateVariableReferenceUrl(categoryVariable);
    Money adjudicationTotalAmount = createMoney(amountValue);
    Extension adjudicationTotalEextension = new Extension(extensionUrl, adjudicationTotalAmount);

    eob.addExtension(adjudicationTotalEextension);
  }

  /**
   * Adds an adjudication total to the specified {@link ExplanationOfBenefit}.
   *
   * @param eob the {@link ExplanationOfBenefit} that the adjudication total should be part of
   * @param categoryVariable the {@link CcwCodebookInterface} to map to the adjudication's <code>
   *          category</code>
   * @param totalAmountValue the {@link Money#getValue()} for the adjudication total
   */
  static void addAdjudicationTotal(
      ExplanationOfBenefit eob, CcwCodebookInterface categoryVariable, Number totalAmountValue) {
    addAdjudicationTotal(eob, categoryVariable, Optional.of(totalAmountValue));
  }

  /**
   * Creates a new {@link Money} from the specified amount value.
   *
   * @param amountValue the value to use for {@link Money#getValue()}
   * @return a new {@link Money} instance, with the specified {@link Money#getValue()}
   */
  static Money createMoney(Optional<? extends Number> amountValue) {
    if (amountValue.isEmpty()) throw new IllegalArgumentException();

    Money money = new Money();
    money.setSystem(TransformerConstants.CODING_MONEY);
    money.setCode(TransformerConstants.CODED_MONEY_USD);

    if (amountValue.get() instanceof BigDecimal) money.setValue((BigDecimal) amountValue.get());
    else throw new BadCodeMonkeyException();

    return money;
  }

  /**
   * Creates a new {@link Money} from the specified amount value.
   *
   * @param amountValue the value to use for {@link Money#getValue()}
   * @return a new {@link Money} instance, with the specified {@link Money#getValue()}
   */
  static Money createMoney(Number amountValue) {
    return createMoney(Optional.of(amountValue));
  }

  /**
   * Adds a benefit balance financial component to the specified {@link ExplanationOfBenefit}.
   *
   * @param eob the {@link ExplanationOfBenefit} that the {@link BenefitComponent} should be part of
   * @param benefitCategory the {@link BenefitCategory} (see {@link
   *     BenefitBalanceComponent#getCategory()}) for the {@link BenefitBalanceComponent} that the
   *     new {@link BenefitComponent} should be part of
   * @param financialType the {@link CcwCodebookInterface} to map to {@link
   *     BenefitComponent#getType()}
   * @return the new {@link BenefitBalanceComponent}, which will have already been added to the
   *     appropriate {@link ExplanationOfBenefit#getBenefitBalance()} entry
   */
  static BenefitComponent addBenefitBalanceFinancial(
      ExplanationOfBenefit eob,
      BenefitCategory benefitCategory,
      CcwCodebookInterface financialType) {
    BenefitBalanceComponent eobPrimaryBenefitBalance =
        findOrAddBenefitBalance(eob, benefitCategory);

    CodeableConcept financialTypeConcept =
        TransformerUtils.createCodeableConcept(
            TransformerConstants.CODING_BBAPI_BENEFIT_BALANCE_TYPE,
            CCWUtils.calculateVariableReferenceUrl(financialType));
    financialTypeConcept.getCodingFirstRep().setDisplay(financialType.getVariable().getLabel());

    BenefitComponent financialEntry = new BenefitComponent(financialTypeConcept);
    eobPrimaryBenefitBalance.getFinancial().add(financialEntry);

    return financialEntry;
  }

  /**
   * Finds or adds a benefit balance component to the specified {@link ExplanationOfBenefit}.
   *
   * @param eob the {@link ExplanationOfBenefit} that the {@link BenefitComponent} should be part of
   * @param benefitCategory the {@link BenefitCategory} to map to {@link
   *     BenefitBalanceComponent#getCategory()}
   * @return the already-existing {@link BenefitBalanceComponent} that matches the specified
   *     parameters, or a new one
   */
  private static BenefitBalanceComponent findOrAddBenefitBalance(
      ExplanationOfBenefit eob, BenefitCategory benefitCategory) {
    Optional<BenefitBalanceComponent> matchingBenefitBalance =
        eob.getBenefitBalance().stream()
            .filter(
                bb ->
                    isCodeInConcept(
                        bb.getCategory(), benefitCategory.getSystem(), benefitCategory.toCode()))
            .findAny();
    if (matchingBenefitBalance.isPresent()) return matchingBenefitBalance.get();

    CodeableConcept benefitCategoryConcept = new CodeableConcept();
    benefitCategoryConcept
        .addCoding()
        .setSystem(benefitCategory.getSystem())
        .setCode(benefitCategory.toCode())
        .setDisplay(benefitCategory.getDisplay());
    BenefitBalanceComponent newBenefitBalance = new BenefitBalanceComponent(benefitCategoryConcept);
    eob.addBenefitBalance(newBenefitBalance);
    return newBenefitBalance;
  }

  /**
   * Ensures that the specified {@link ExplanationOfBenefit} has the specified {@link
   * CareTeamComponent}, and links the specified {@link ItemComponent} to that {@link
   * CareTeamComponent} (via {@link ItemComponent#addCareTeamLinkId(int)}).
   *
   * @param eob the {@link ExplanationOfBenefit} that the {@link CareTeamComponent} should be part
   *     of
   * @param eobItem the {@link ItemComponent} that should be linked to the {@link CareTeamComponent}
   * @param practitionerIdSystem the {@link Identifier#getSystem()} of the practitioner to reference
   *     in {@link CareTeamComponent#getProvider()}
   * @param practitionerIdValue the {@link Identifier#getValue()} of the practitioner to reference
   *     in {@link CareTeamComponent#getProvider()}
   * @param careTeamRole the {@link ClaimCareteamrole} to use for the {@link
   *     CareTeamComponent#getRole()}
   * @return the {@link CareTeamComponent} that was created/linked
   */
  static CareTeamComponent addCareTeamPractitioner(
      ExplanationOfBenefit eob,
      ItemComponent eobItem,
      String practitionerIdSystem,
      String practitionerIdValue,
      ClaimCareteamrole careTeamRole) {
    // Try to find a matching pre-existing entry.
    CareTeamComponent careTeamEntry =
        eob.getCareTeam().stream()
            .filter(ctc -> ctc.getProvider().hasIdentifier())
            .filter(
                ctc ->
                    practitionerIdSystem.equals(ctc.getProvider().getIdentifier().getSystem())
                        && practitionerIdValue.equals(ctc.getProvider().getIdentifier().getValue()))
            .filter(ctc -> ctc.hasRole())
            .filter(
                ctc ->
                    careTeamRole.toCode().equals(ctc.getRole().getCodingFirstRep().getCode())
                        && careTeamRole
                            .getSystem()
                            .equals(ctc.getRole().getCodingFirstRep().getSystem()))
            .findAny()
            .orElse(null);

    // If no match was found, add one to the EOB.
    if (careTeamEntry == null) {
      careTeamEntry = eob.addCareTeam();
      careTeamEntry.setSequence(eob.getCareTeam().size() + 1);
      careTeamEntry.setProvider(
          createIdentifierReference(practitionerIdSystem, practitionerIdValue));

      CodeableConcept careTeamRoleConcept =
          createCodeableConcept(ClaimCareteamrole.OTHER.getSystem(), careTeamRole.toCode());
      careTeamRoleConcept.getCodingFirstRep().setDisplay(careTeamRole.getDisplay());
      careTeamEntry.setRole(careTeamRoleConcept);
    }

    // care team entry is at eob level so no need to create item link id
    if (eobItem == null) {
      return careTeamEntry;
    }

    // Link the EOB.item to the care team entry (if it isn't already).
    final int careTeamEntrySequence = careTeamEntry.getSequence();
    if (eobItem.getCareTeamLinkId().stream()
        .noneMatch(id -> id.getValue() == careTeamEntrySequence)) {
      eobItem.addCareTeamLinkId(careTeamEntrySequence);
    }

    return careTeamEntry;
  }

  /**
   * Add diagnosis code to the specified {@link ExplanationOfBenefit}.
   *
   * @param eob the {@link ExplanationOfBenefit} to (possibly) modify
   * @param diagnosis the {@link Diagnosis} to add, if it's not already present
   * @return the {@link DiagnosisComponent#getSequence()} of the existing or newly-added entry
   */
  static int addDiagnosisCode(ExplanationOfBenefit eob, Diagnosis diagnosis) {
    Optional<DiagnosisComponent> existingDiagnosis =
        eob.getDiagnosis().stream()
            .filter(d -> d.getDiagnosis() instanceof CodeableConcept)
            .filter(d -> diagnosis.isContainedIn((CodeableConcept) d.getDiagnosis()))
            .findAny();
    if (existingDiagnosis.isPresent())
      return existingDiagnosis.get().getSequenceElement().getValue();

    DiagnosisComponent diagnosisComponent =
        new DiagnosisComponent().setSequence(eob.getDiagnosis().size() + 1);
    diagnosisComponent.setDiagnosis(diagnosis.toCodeableConcept());

    for (DiagnosisLabel diagnosisLabel : diagnosis.getLabels()) {
      CodeableConcept diagnosisTypeConcept =
          createCodeableConcept(diagnosisLabel.getSystem(), diagnosisLabel.toCode());
      diagnosisTypeConcept.getCodingFirstRep().setDisplay(diagnosisLabel.getDisplay());
      diagnosisComponent.addType(diagnosisTypeConcept);
    }
    if (diagnosis.getPresentOnAdmission().isPresent()
        && diagnosis.getPresentOnAdmissionCode().isPresent()) {
      diagnosisComponent.addExtension(
          createExtensionCoding(
              eob, diagnosis.getPresentOnAdmissionCode().get(), diagnosis.getPresentOnAdmission()));
    }

    eob.getDiagnosis().add(diagnosisComponent);
    return diagnosisComponent.getSequenceElement().getValue();
  }

  /**
   * Adds a {@link Diagnosis} to the provided {@link ExplanationOfBenefit} and modifies the {@link
   * ItemComponent} with a sequence number identifier.
   *
   * @param eob the {@link ExplanationOfBenefit} that the specified {@link ItemComponent} is a child
   *     of
   * @param item the {@link ItemComponent} to add an {@link ItemComponent#getDiagnosisLinkId()}
   *     entry to
   * @param diagnosis the {@link Diagnosis} to add a link for
   */
  static void addDiagnosisLink(ExplanationOfBenefit eob, ItemComponent item, Diagnosis diagnosis) {
    int diagnosisSequence = addDiagnosisCode(eob, diagnosis);
    item.addDiagnosisLinkId(diagnosisSequence);
  }

  /**
   * Adds an {@link Extension} to the specified {@link DomainResource}. {@link Extension#getValue()}
   * will be set to a {@link CodeableConcept} containing a single {@link Coding}, with the specified
   * system and code.
   *
   * <p>Data Architecture Note: The {@link CodeableConcept} might seem extraneous -- why not just
   * add the {@link Coding} directly to the {@link Extension}? The main reason for doing it this way
   * is consistency: this is what FHIR seems to do everywhere.
   *
   * @param fhirElement the FHIR element to add the {@link Extension} to
   * @param extensionUrl the {@link Extension#getUrl()} to use
   * @param codingSystem the {@link Coding#getSystem()} to use
   * @param codingDisplay the {@link Coding#getDisplay()} to use
   * @param codingCode the {@link Coding#getCode()} to use
   */
  static void addExtensionCoding(
      IBaseHasExtensions fhirElement,
      String extensionUrl,
      String codingSystem,
      String codingDisplay,
      String codingCode) {
    IBaseExtension<?, ?> extension = fhirElement.addExtension();
    extension.setUrl(extensionUrl);
    if (codingDisplay == null)
      extension.setValue(new Coding().setSystem(codingSystem).setCode(codingCode));
    else
      extension.setValue(
          new Coding().setSystem(codingSystem).setCode(codingCode).setDisplay(codingDisplay));
  }

  /**
   * Adds an {@link Extension} to the specified {@link DomainResource}. {@link Extension#getValue()}
   * will be set to a {@link CodeableConcept} containing a single {@link Coding}, with the specified
   * system and code.
   *
   * <p>Data Architecture Note: The {@link CodeableConcept} might seem extraneous -- why not just
   * add the {@link Coding} directly to the {@link Extension}? The main reason for doing it this way
   * is consistency: this is what FHIR seems to do everywhere.
   *
   * @param fhirElement the FHIR element to add the {@link Extension} to
   * @param extensionUrl the {@link Extension#getUrl()} to use
   * @param codingSystem the {@link Coding#getSystem()} to use
   * @param codingDisplay the {@link Coding#getDisplay()} to use
   * @param codingCode the {@link Coding#getCode()} to use
   */
  static void addExtensionCoding(
      IBaseHasExtensions fhirElement,
      String extensionUrl,
      String codingSystem,
      Optional<String> codingDisplay,
      String codingCode) {
    addExtensionCoding(
        fhirElement, extensionUrl, codingSystem, codingDisplay.orElse(null), codingCode);
  }

  /**
   * Adds an {@link Extension} to the specified {@link DomainResource}. {@link Extension#getValue()}
   * will be set to a {@link Quantity} with the specified system and value.
   *
   * @param fhirElement the FHIR element to add the {@link Extension} to
   * @param extensionUrl the {@link Extension#getUrl()} to use
   * @param quantitySystem the {@link Quantity#getSystem()} to use
   * @param quantityValue the {@link Quantity#getValue()} to use
   */
  static void addExtensionValueQuantity(
      IBaseHasExtensions fhirElement,
      String extensionUrl,
      String quantitySystem,
      BigDecimal quantityValue) {
    IBaseExtension<?, ?> extension = fhirElement.addExtension();
    extension.setUrl(extensionUrl);
    extension.setValue(new Quantity().setSystem(extensionUrl).setValue(quantityValue));

    // CodeableConcept codeableConcept = new CodeableConcept();
    // extension.setValue(codeableConcept);
    //
    // Coding coding = codeableConcept.addCoding();
    // coding.setSystem(codingSystem).setCode(codingCode);
  }

  /**
   * Adds an {@link Extension} to the specified {@link DomainResource}. {@link Extension#getValue()}
   * will be set to a {@link Identifier} with the specified url, system, and value.
   *
   * @param fhirElement the FHIR element to add the {@link Extension} to
   * @param extensionUrl the {@link Extension#getUrl()} to use
   * @param extensionSystem the {@link Identifier#getSystem()} to use
   * @param extensionValue the {@link Identifier#getValue()} to use
   */
  static void addExtensionValueIdentifier(
      IBaseHasExtensions fhirElement,
      String extensionUrl,
      String extensionSystem,
      String extensionValue) {
    IBaseExtension<?, ?> extension = fhirElement.addExtension();
    extension.setUrl(extensionUrl);

    Identifier valueIdentifier = new Identifier();
    valueIdentifier.setSystem(extensionSystem).setValue(extensionValue);

    extension.setValue(valueIdentifier);
  }

  /**
   * Returns a new {@link SupportingInformationComponent} that has been added to the specified
   * {@link ExplanationOfBenefit}.
   *
   * @param eob the {@link ExplanationOfBenefit} to modify
   * @param categoryVariable {@link CcwCodebookInterface} to map to {@link
   *     SupportingInformationComponent#getCategory()}
   * @return the newly-added {@link SupportingInformationComponent} entry
   */
  static SupportingInformationComponent addInformation(
      ExplanationOfBenefit eob, CcwCodebookInterface categoryVariable) {
    int maxSequence = eob.getInformation().stream().mapToInt(i -> i.getSequence()).max().orElse(0);

    SupportingInformationComponent infoComponent = new SupportingInformationComponent();
    infoComponent.setSequence(maxSequence + 1);
    infoComponent.setCategory(
        createCodeableConceptForFieldId(
            eob, TransformerConstants.CODING_BBAPI_INFORMATION_CATEGORY, categoryVariable));
    eob.getInformation().add(infoComponent);

    return infoComponent;
  }

  /**
   * Returns a new {@link SupportingInformationComponent} that has been added to the specified
   * {@link ExplanationOfBenefit}. Unlike {@link #addInformation}, this also sets the {@link
   * SupportingInformationComponent#getCode()} based on the values provided.
   *
   * @param eob the {@link ExplanationOfBenefit} to modify
   * @param categoryVariable {@link CcwCodebookInterface} to map to {@link
   *     SupportingInformationComponent#getCategory()}
   * @param codeSystemVariable the {@link CcwCodebookInterface} to map to the {@link
   *     Coding#getSystem()} used in the {@link SupportingInformationComponent#getCode()}
   * @param codeValue the value to map to the {@link Coding#getCode()} used in the {@link
   *     SupportingInformationComponent#getCode()}
   * @return the newly-added {@link SupportingInformationComponent} entry
   */
  static SupportingInformationComponent addInformationWithCode(
      ExplanationOfBenefit eob,
      CcwCodebookInterface categoryVariable,
      CcwCodebookInterface codeSystemVariable,
      Optional<?> codeValue) {
    SupportingInformationComponent infoComponent = addInformation(eob, categoryVariable);

    CodeableConcept infoCode =
        new CodeableConcept().addCoding(createCoding(eob, codeSystemVariable, codeValue));
    infoComponent.setCode(infoCode);

    return infoComponent;
  }

  /**
   * Returns a new {@link SupportingInformationComponent} that has been added to the specified
   * {@link ExplanationOfBenefit}. Unlike {@link #addInformationWithCode}, this also sets the {@link
   * SupportingInformationComponent#getCode()} based on the values provided.
   *
   * @param eob the {@link ExplanationOfBenefit} to modify
   * @param categoryVariable {@link CcwCodebookInterface} to map to {@link
   *     SupportingInformationComponent#getCategory()}
   * @param codeSystemVariable the {@link CcwCodebookInterface} to map to the {@link
   *     Coding#getSystem()} used in the {@link SupportingInformationComponent#getCode()}
   * @param codeValue the value to map to the {@link Coding#getCode()} used in the {@link
   *     SupportingInformationComponent#getCode()}
   * @return the newly-added {@link SupportingInformationComponent} entry
   */
  static SupportingInformationComponent addInformationWithCode(
      ExplanationOfBenefit eob,
      CcwCodebookInterface categoryVariable,
      CcwCodebookInterface codeSystemVariable,
      Object codeValue) {
    return addInformationWithCode(
        eob, categoryVariable, codeSystemVariable, Optional.of(codeValue));
  }

  /**
   * Adds a procedure code to the specified {@link ExplanationOfBenefit} if it does not exist.
   *
   * @param eob the {@link ExplanationOfBenefit} to (possibly) modify
   * @param procedure the procedure
   * @return the {@link ProcedureComponent#getSequence()} of the existing or newly-added entry
   */
  static int addProcedureCode(ExplanationOfBenefit eob, CCWProcedure procedure) {

    Optional<ProcedureComponent> existingProcedure =
        eob.getProcedure().stream()
            .filter(pc -> pc.getProcedure() instanceof CodeableConcept)
            .filter(
                pc ->
                    isCodeInConcept(
                        (CodeableConcept) pc.getProcedure(),
                        procedure.getFhirSystem(),
                        procedure.getCode()))
            .findAny();
    if (existingProcedure.isPresent())
      return existingProcedure.get().getSequenceElement().getValue();

    ProcedureComponent procedureComponent =
        new ProcedureComponent().setSequence(eob.getProcedure().size() + 1);
    procedureComponent.setProcedure(
        createCodeableConcept(
            procedure.getFhirSystem(),
            null,
            retrieveProcedureCodeDisplay(procedure.getCode()),
            procedure.getCode()));
    if (procedure.getProcedureDate().isPresent()) {
      procedureComponent.setDate(convertToDate(procedure.getProcedureDate().get()));
    }

    eob.getProcedure().add(procedureComponent);
    return procedureComponent.getSequenceElement().getValue();
  }

  /**
   * Builds an id for an {@link ExplanationOfBenefit}.
   *
   * <p>Internally BFD treats claimId as a Long (db bigint); however, within FHIR, an Identifier has
   * a data type of StringType that does not constrain itself to numeric. So this convenience method
   * will continue to exist as a means to create a {@link ExplanationOfBenefit#getId()} whose claim
   * ID is not numeric. This non-numeric handling may be used in integration tests to trigger {@link
   * ca.uhn.fhir.rest.server.exceptions.InvalidRequestException}.
   *
   * @param claimType the {@link ClaimType} to compute an {@link ExplanationOfBenefit#getId()} for
   * @param claimId the <code>claimId</code> field value (e.g. from {@link
   *     CarrierClaim#getClaimId()}) to compute an {@link ExplanationOfBenefit#getId()} for
   * @return the {@link ExplanationOfBenefit#getId()} value to use for the specified <code>claimId
   *     </code> value
   */
  public static String buildEobId(ClaimType claimType, String claimId) {
    return String.format("%s-%s", claimType.name().toLowerCase(), claimId);
  }

  /**
   * Builds an id for an {@link ExplanationOfBenefit}.
   *
   * @param claimType the {@link ClaimType} to compute an {@link ExplanationOfBenefit#getId()} for
   * @param claimId the <code>claimId</code> field value (e.g. from {@link
   *     CarrierClaim#getClaimId()}) to compute an {@link ExplanationOfBenefit#getId()} for
   * @return the {@link ExplanationOfBenefit#getId()} value to use for the specified <code>
   *     claimId     </code> value
   */
  public static String buildEobId(ClaimType claimType, Long claimId) {
    return String.format("%s-%d", claimType.name().toLowerCase(), claimId);
  }

  /**
   * Gets the unprefixed claim id.
   *
   * @param eob the {@link ExplanationOfBenefit} to extract the id from
   * @return the <code>claimId</code> field value (e.g. from {@link CarrierClaim#getClaimId()})
   */
  static String getUnprefixedClaimId(ExplanationOfBenefit eob) {
    for (Identifier i : eob.getIdentifier()) {
      if (i.getSystem().contains("clm_id") || i.getSystem().contains("pde_id")) {
        return i.getValue();
      }
    }

    throw new BadCodeMonkeyException("A claim ID was expected but none was found.");
  }

  /**
   * Gets the claim type from the specified {@link ExplanationOfBenefit}.
   *
   * @param eob the {@link ExplanationOfBenefit} to extract the claim type from
   * @return the {@link ClaimType}
   */
  static ClaimType getClaimType(ExplanationOfBenefit eob) {
    String type =
        eob.getType().getCoding().stream()
            .filter(c -> c.getSystem().equals(TransformerConstants.CODING_SYSTEM_BBAPI_EOB_TYPE))
            .findFirst()
            .get()
            .getCode();
    return ClaimType.valueOf(type);
  }

  /**
   * Builds a patient id from a {@link Beneficiary}.
   *
   * @param beneficiary the {@link Beneficiary} to calculate the {@link Patient#getId()} value for
   * @return the {@link Patient#getId()} value that will be used for the specified {@link
   *     Beneficiary}
   */
  public static IdDt buildPatientId(Beneficiary beneficiary) {
    return buildPatientId(beneficiary.getBeneficiaryId());
  }

  /**
   * Builds a patient id from a beneficiary id.
   *
   * @param beneficiaryId the {@link Beneficiary#getBeneficiaryId()} to calculate the {@link
   *     Patient#getId()} value for
   * @return the {@link Patient#getId()} value that will be used for the specified {@link
   *     Beneficiary}
   */
  public static IdDt buildPatientId(Long beneficiaryId) {
    return new IdDt(Patient.class.getSimpleName(), String.valueOf(beneficiaryId));
  }

  /**
   * Builds a patient id from a {@link Beneficiary} and a {@link MedicareSegment}.
   *
   * @param medicareSegment the {@link MedicareSegment} to compute a {@link Coverage#getId()} for
   * @param beneficiary the {@link Beneficiary} to compute a {@link Coverage#getId()} for
   * @return the {@link Coverage#getId()} value to use for the specified values
   */
  public static IdDt buildCoverageId(MedicareSegment medicareSegment, Beneficiary beneficiary) {
    return buildCoverageId(medicareSegment, beneficiary.getBeneficiaryId());
  }

  /**
   * Builds a patient id from a beneficiary id and a {@link MedicareSegment}.
   *
   * @param medicareSegment the {@link MedicareSegment} to compute a {@link Coverage#getId()} for
   * @param beneficiaryId the {@link Beneficiary#getBeneficiaryId()} value to compute a {@link
   *     Coverage#getId()} for
   * @return the {@link Coverage#getId()} value to use for the specified values
   */
  public static IdDt buildCoverageId(MedicareSegment medicareSegment, Long beneficiaryId) {
    return new IdDt(
        Coverage.class.getSimpleName(),
        String.format("%s-%d", medicareSegment.getUrlPrefix(), beneficiaryId));
  }

  /**
   * Internally BFD treats beneficiaryId as a Long (db bigint); however, within FHIR, an {@link
   * ca.uhn.fhir.model.primitive.IdDt} does not constrain itself to numeric. So this convenience
   * method will continue to exist as a means to create a non-numeric IdDt. This non-numeric
   * handling may be used in integration tests to trigger {@link
   * ca.uhn.fhir.rest.server.exceptions.InvalidRequestException}.
   *
   * @param medicareSegment the {@link MedicareSegment} to compute a {@link Coverage#getId()} for
   * @param beneficiaryId the {@link Beneficiary#getBeneficiaryId()} value to compute a {@link
   *     Coverage#getId()} for
   * @return the {@link Coverage#getId()} value to use for the specified values
   */
  public static IdDt buildCoverageId(MedicareSegment medicareSegment, String beneficiaryId) {
    return new IdDt(
        Coverage.class.getSimpleName(),
        String.format("%s-%s", medicareSegment.getUrlPrefix(), beneficiaryId));
  }

  /**
   * Converts a {@link LocalDate} to a {@link Date} using the system timezone.
   *
   * <p>We use the system TZ here to ensure that the date doesn't shift at all, as FHIR will just
   * use this as an unzoned Date (I think, and if not, it's almost certainly using the same TZ as
   * this system).
   *
   * @param localDate the {@link LocalDate} to convert
   * @return a {@link Date} version of the specified {@link LocalDate}
   */
  static Date convertToDate(LocalDate localDate) {
    return Date.from(localDate.atStartOfDay(ZoneId.systemDefault()).toInstant());
  }

  /**
   * Creates a {@link CodeableConcept} from the specified system and code.
   *
   * @param codingSystem the {@link Coding#getSystem()} to use
   * @param codingCode the {@link Coding#getCode()} to use
   * @return a {@link CodeableConcept} with the specified {@link Coding}
   */
  static CodeableConcept createCodeableConcept(String codingSystem, String codingCode) {
    return createCodeableConcept(codingSystem, null, null, codingCode);
  }

  /**
   * Creates a {@link CodeableConcept} from the specified system, display, and code.
   *
   * @param codingSystem the {@link Coding#getSystem()} to use
   * @param codingVersion the {@link Coding#getVersion()} to use
   * @param codingDisplay the {@link Coding#getDisplay()} to use
   * @param codingCode the {@link Coding#getCode()} to use
   * @return a {@link CodeableConcept} with the specified {@link Coding}
   */
  static CodeableConcept createCodeableConcept(
      String codingSystem, String codingVersion, String codingDisplay, String codingCode) {
    CodeableConcept codeableConcept = new CodeableConcept();
    Coding coding = codeableConcept.addCoding().setSystem(codingSystem).setCode(codingCode);
    if (codingVersion != null) coding.setVersion(codingVersion);
    if (codingDisplay != null) coding.setDisplay(codingDisplay);
    return codeableConcept;
  }

  /**
   * Used for creating Identifier references for Organizations and Facilities.
   *
   * @param identifierSystem the {@link Identifier#getSystem()} to use in {@link
   *     Reference#getIdentifier()}
   * @param identifierValue the {@link Identifier#getValue()} to use in {@link
   *     Reference#getIdentifier()}
   * @return a {@link Reference} with the specified {@link Identifier}
   */
  static Reference createIdentifierReference(String identifierSystem, String identifierValue) {

    return new Reference()
        .setIdentifier(new Identifier().setSystem(identifierSystem).setValue(identifierValue))
        .setDisplay(retrieveNpiCodeDisplay(identifierValue));
  }

  /**
   * Setting identifier reference when npiorgdisplay is passed in.
   *
   * @param identifierSystem the {@link Identifier#getSystem()} to use in {@link
   *     Reference#getIdentifier()}
   * @param identifierValue the {@link Identifier#getValue()} to use in {@link
   *     Reference#getIdentifier()}
   * @param npiOrgDisplay the {@link Identifier#getValue()} to use in {@link
   *     Reference#getIdentifier()}
   * @return a {@link Reference} with the specified {@link Identifier}
   */
  static Reference createIdentifierReference(
      String identifierSystem, String identifierValue, Optional<String> npiOrgDisplay) {
    return new Reference()
        .setIdentifier(new Identifier().setSystem(identifierSystem).setValue(identifierValue))
        .setDisplay(npiOrgDisplay.orElse(null));
  }

  /**
   * Used for creating Identifier references for Organizations and Facilities.
   *
   * @param identifierType the {@link IdentifierType}
   * @param identifierValue the {@link Identifier#getValue()} to use in {@link
   *     Reference#getIdentifier()}
   * @return a {@link Reference} with the specified {@link Identifier}
   */
  static Reference createIdentifierReference(
      IdentifierType identifierType, String identifierValue) {

    Reference reference = new Reference();
    Coding coding =
        new Coding()
            .setSystem(identifierType.getSystem())
            .setCode(identifierType.getCode())
            .setDisplay(identifierType.getDisplay());
    List<Coding> codingList = new ArrayList<Coding>();
    codingList.add(coding);

    CodeableConcept codeableConcept = new CodeableConcept().setCoding(codingList);
    return reference
        .setIdentifier(
            new Identifier()
                .setSystem(identifierType.getSystem())
                .setValue(identifierValue)
                .setType(codeableConcept))
        .setDisplay(retrieveNpiCodeDisplay(identifierValue));
  }

  /**
   * Creates a reference to the cms organization.
   *
   * @return a Reference to the {@link Organization} for CMS, which will only be valid if
   *     upsertSharedData has been run
   */
  static Reference createReferenceToCms() {
    return new Reference("Organization?name=" + urlEncode(TransformerConstants.COVERAGE_ISSUER));
  }

  /**
   * Checks if the specified combination of system and code exists as a Coding within the supplied
   * {@link CodeableConcept}.
   *
   * @param concept the {@link CodeableConcept} to check
   * @param codingSystem the {@link Coding#getSystem()} to match
   * @param codingCode the {@link Coding#getCode()} to match
   * @return <code>true</code> if the specified {@link CodeableConcept} contains the specified
   *     {@link Coding}, <code>false</code> if it does not
   */
  static boolean isCodeInConcept(CodeableConcept concept, String codingSystem, String codingCode) {
    return isCodeInConcept(concept, codingSystem, null, codingCode);
  }

  /**
   * Checks if the specified combination of version, system, and code exists as a Coding within the
   * supplied {@link CodeableConcept}.
   *
   * @param concept the {@link CodeableConcept} to check
   * @param codingSystem the {@link Coding#getSystem()} to match
   * @param codingVersion the coding version
   * @param codingCode the {@link Coding#getCode()} to match
   * @return <code>true</code> if the specified {@link CodeableConcept} contains the specified
   *     {@link Coding}, <code>false</code> if it does not
   */
  static boolean isCodeInConcept(
      CodeableConcept concept, String codingSystem, String codingVersion, String codingCode) {
    return concept.getCoding().stream()
        .anyMatch(
            c -> {
              if (!codingSystem.equals(c.getSystem())) return false;
              if (codingVersion != null && !codingVersion.equals(c.getVersion())) return false;
              return codingCode.equals(c.getCode());
            });
  }

  /**
   * Creates a new identifier.
   *
   * @param ccwVariable the {@link CcwCodebookInterface} being mapped
   * @param identifierValue the value to use for {@link Identifier#getValue()} for the resulting
   *     {@link Identifier}
   * @return the output {@link Extension}, with {@link Extension#getValue()} set to represent the
   *     specified input values
   */
  static Extension createExtensionIdentifier(
      CcwCodebookInterface ccwVariable, Optional<String> identifierValue) {
    if (identifierValue.isEmpty()) throw new IllegalArgumentException();

    Identifier identifier = createIdentifier(ccwVariable, identifierValue.get());

    String extensionUrl = CCWUtils.calculateVariableReferenceUrl(ccwVariable);
    Extension extension = new Extension(extensionUrl, identifier);

    return extension;
  }

  /**
   * Creates a new identifier.
   *
   * @param ccwVariable the {@link CcwCodebookInterface} being mapped
   * @param identifierValue the value to use for {@link Identifier#getValue()} for the resulting
   *     {@link Identifier}
   * @return the output {@link Extension}, with {@link Extension#getValue()} set to represent the
   *     specified input values
   */
  static Extension createExtensionIdentifier(
      CcwCodebookInterface ccwVariable, String identifierValue) {
    return createExtensionIdentifier(ccwVariable, Optional.of(identifierValue));
  }

  /**
   * Creates a new identifier.
   *
   * @param ccwVariable the {@link CcwCodebookInterface} being mapped
   * @param identifierValue the value to use for {@link Identifier#getValue()} for the resulting
   *     {@link Identifier}
   * @return the output {@link Identifier}
   */
  static Identifier createIdentifier(CcwCodebookInterface ccwVariable, String identifierValue) {
    if (identifierValue == null) throw new IllegalArgumentException();

    Identifier identifier =
        new Identifier()
            .setSystem(CCWUtils.calculateVariableReferenceUrl(ccwVariable))
            .setValue(identifierValue);
    return identifier;
  }

  /**
   * Creates a new identifier.
   *
   * @param systemUrl the url being mapped
   * @param identifierValue the value to use for {@link Identifier#getValue()} for the resulting
   *     {@link Identifier}
   * @return the output {@link Identifier}
   */
  static Identifier createIdentifier(String systemUrl, String identifierValue) {
    if (identifierValue == null) throw new IllegalArgumentException();

    Identifier identifier = new Identifier().setSystem(systemUrl).setValue(identifierValue);
    return identifier;
  }

  /**
   * Helper function to create the valueDate for the specified {@link Extension}.
   *
   * @param ccwVariable the {@link CcwCodebookInterface} being mapped
   * @param dateYear the value to use for {@link Coding#getCode()} for the resulting {@link Coding}
   * @return the output {@link Extension}, with {@link Extension#getValue()} set to represent the
   *     specified input values
   */
  static Extension createExtensionDate(
      CcwCodebookInterface ccwVariable, Optional<BigDecimal> dateYear) {

    Extension extension = null;
    if (dateYear.isEmpty()) {
      throw new NoSuchElementException();
    }
    try {
      String stringDate = String.format("%04d", dateYear.get().intValue());
      DateType dateYearValue = new DateType(stringDate);
      String extensionUrl = CCWUtils.calculateVariableReferenceUrl(ccwVariable);
      extension = new Extension(extensionUrl, dateYearValue);
    } catch (DataFormatException e) {
      throw new InvalidRifValueException(
          String.format("Unable to create DateType with reference year: '%s'.", dateYear.get()), e);
    }
    return extension;
  }

  /**
   * Creates an extension for a ccw variable with the specified quantity.
   *
   * @param ccwVariable the {@link CcwCodebookInterface} being mapped
   * @param quantityValue the value to use for {@link Coding#getCode()} for the resulting {@link
   *     Coding}
   * @return the output {@link Extension}, with {@link Extension#getValue()} set to represent the
   *     specified input values
   */
  static Extension createExtensionQuantity(
      CcwCodebookInterface ccwVariable, Optional<? extends Number> quantityValue) {
    if (!quantityValue.isPresent()) throw new IllegalArgumentException();

    Quantity quantity;
    if (quantityValue.get() instanceof BigDecimal)
      quantity = new Quantity().setValue((BigDecimal) quantityValue.get());
    else throw new BadCodeMonkeyException();

    String extensionUrl = CCWUtils.calculateVariableReferenceUrl(ccwVariable);
    Extension extension = new Extension(extensionUrl, quantity);

    return extension;
  }

  /**
   * Creates an extension for a ccw variable with the specified quantity.
   *
   * @param ccwVariable the {@link CcwCodebookInterface} being mapped
   * @param quantityValue the value to use for {@link Coding#getCode()} for the resulting {@link
   *     Coding}
   * @return the output {@link Extension}, with {@link Extension#getValue()} set to represent the
   *     specified input values
   */
  static Extension createExtensionQuantity(CcwCodebookInterface ccwVariable, Number quantityValue) {
    return createExtensionQuantity(ccwVariable, Optional.of(quantityValue));
  }

  /**
   * Sets the {@link Quantity} fields related to the unit for the amount: {@link
   * Quantity#getSystem()}, {@link Quantity#getCode()}, and {@link Quantity#getUnit()}.
   *
   * @param ccwVariable the {@link CcwCodebookInterface} for the unit coding
   * @param unitCode the value to use for {@link Quantity#getCode()}
   * @param rootResource the root FHIR {@link IAnyResource} that is being mapped
   * @param quantity the {@link Quantity} to modify
   */
  static void setQuantityUnitInfo(
      CcwCodebookInterface ccwVariable,
      Optional<?> unitCode,
      IAnyResource rootResource,
      Quantity quantity) {
    if (!unitCode.isPresent()) return;

    quantity.setSystem(CCWUtils.calculateVariableReferenceUrl(ccwVariable));

    String unitCodeString;
    if (unitCode.get() instanceof String) unitCodeString = (String) unitCode.get();
    else if (unitCode.get() instanceof Character)
      unitCodeString = ((Character) unitCode.get()).toString();
    else throw new IllegalArgumentException();

    quantity.setCode(unitCodeString);

    Optional<String> unit = calculateCodingDisplay(rootResource, ccwVariable, unitCodeString);
    if (unit.isPresent()) quantity.setUnit(unit.get());
  }

  /**
   * Creates an extension coding for the specified ccw variable and code.
   *
   * @param rootResource the root FHIR {@link IAnyResource} that the resultant {@link Extension}
   *     will be contained in
   * @param ccwVariable the {@link CcwCodebookInterface} being coded
   * @param code the value to use for {@link Coding#getCode()} for the resulting {@link Coding}
   * @return the output {@link Extension}, with {@link Extension#getValue()} set to a new {@link
   *     Coding} to represent the specified input values
   */
  static Extension createExtensionCoding(
      IAnyResource rootResource, CcwCodebookInterface ccwVariable, Optional<?> code) {
    if (code.isEmpty()) throw new IllegalArgumentException();

    Coding coding = createCoding(rootResource, ccwVariable, code.get());

    String extensionUrl = CCWUtils.calculateVariableReferenceUrl(ccwVariable);

    return new Extension(extensionUrl, coding);
  }

  /**
   * Creates an extension coding for the specified ccw variable, year-month, and code.
   *
   * @param rootResource the root FHIR {@link IAnyResource} that the resultant {@link Extension}
   *     will be contained in
   * @param ccwVariable the {@link CcwCodebookInterface} being coded
   * @param yearMonth the year month
   * @param code the value to use for {@link Coding#getCode()} for the resulting {@link Coding}
   * @return the output {@link Extension}, with {@link Extension#getValue()} set to a new {@link
   *     Coding} to represent the specified input values
   */
  static Extension createExtensionCoding(
      IAnyResource rootResource,
      CcwCodebookInterface ccwVariable,
      String yearMonth,
      Optional<?> code) {
    if (!code.isPresent()) throw new IllegalArgumentException();

    Coding coding = createCoding(rootResource, ccwVariable, yearMonth, code.get());

    String extensionUrl =
        String.format("%s/%s", CCWUtils.calculateVariableReferenceUrl(ccwVariable), yearMonth);
    Extension extension = new Extension(extensionUrl, coding);

    return extension;
  }

  /**
   * Creates an extension coding for the specified ccw variable and code.
   *
   * @param rootResource the root FHIR {@link IAnyResource} that the resultant {@link Extension}
   *     will be contained in
   * @param ccwVariable the {@link CcwCodebookVariable} being coded
   * @param code the value to use for {@link Coding#getCode()} for the resulting {@link Coding}
   * @return the output {@link Extension}, with {@link Extension#getValue()} set to a new {@link
   *     Coding} to represent the specified input values
   */
  static Extension createExtensionCoding(
      IAnyResource rootResource, CcwCodebookInterface ccwVariable, Object code) {
    // Jumping through hoops to cope with overloaded method:
    Optional<?> codeOptional = code instanceof Optional ? (Optional<?>) code : Optional.of(code);
    return createExtensionCoding(rootResource, ccwVariable, codeOptional);
  }

  /**
   * Creates a {@link CodeableConcept} from the specified ccw variable and code.
   *
   * @param rootResource the root FHIR {@link IAnyResource} that the resultant {@link
   *     CodeableConcept} will be contained in
   * @param ccwVariable the {@link CcwCodebookInterface} being coded
   * @param code the value to use for {@link Coding#getCode()} for the resulting (single) {@link
   *     Coding}, wrapped within the resulting {@link CodeableConcept}
   * @return the output {@link CodeableConcept} for the specified input values
   */
  static CodeableConcept createCodeableConcept(
      IAnyResource rootResource, CcwCodebookInterface ccwVariable, Optional<?> code) {
    if (!code.isPresent()) throw new IllegalArgumentException();

    Coding coding = createCoding(rootResource, ccwVariable, code.get());

    CodeableConcept concept = new CodeableConcept();
    concept.addCoding(coding);

    return concept;
  }

  /**
   * Creates a {@link CodeableConcept} from the specified ccw variable and code.
   *
   * @param rootResource the root FHIR {@link IAnyResource} that the resultant {@link
   *     CodeableConcept} will be contained in
   * @param ccwVariable the {@link CcwCodebookInterface} being coded
   * @param code the value to use for {@link Coding#getCode()} for the resulting (single) {@link
   *     Coding}, wrapped within the resulting {@link CodeableConcept}
   * @return the output {@link CodeableConcept} for the specified input values
   */
  static CodeableConcept createCodeableConcept(
      IAnyResource rootResource, CcwCodebookInterface ccwVariable, Object code) {
    // Jumping through hoops to cope with overloaded method:
    Optional<?> codeOptional = code instanceof Optional ? (Optional<?>) code : Optional.of(code);
    return createCodeableConcept(rootResource, ccwVariable, codeOptional);
  }

  /**
   * Unlike {@link #createCodeableConcept(IAnyResource, CcwCodebookInterface, Optional)}, this
   * method creates a {@link CodeableConcept} that's intended for use as a field ID/discriminator:
   * the {@link Variable#getId()} will be used for the {@link Coding#getCode()}, rather than the
   * {@link Coding#getSystem()}.
   *
   * @param rootResource the root FHIR {@link IAnyResource} that the resultant {@link
   *     CodeableConcept} will be contained in
   * @param codingSystem the {@link Coding#getSystem()} to use
   * @param ccwVariable the {@link CcwCodebookInterface} being coded
   * @return the output {@link CodeableConcept} for the specified input values
   */
  private static CodeableConcept createCodeableConceptForFieldId(
      IAnyResource rootResource, String codingSystem, CcwCodebookInterface ccwVariable) {
    String code = CCWUtils.calculateVariableReferenceUrl(ccwVariable);
    Coding coding = new Coding(codingSystem, code, ccwVariable.getVariable().getLabel());

    return new CodeableConcept().addCoding(coding);
  }

  /**
   * Creates a coding.
   *
   * @param rootResource the root FHIR {@link IAnyResource} that the resultant {@link Coding} will
   *     be contained in
   * @param ccwVariable the {@link CcwCodebookInterface} being coded
   * @param code the value to use for {@link Coding#getCode()}
   * @return the output {@link Coding} for the specified input values
   */
  private static Coding createCoding(
      IAnyResource rootResource, CcwCodebookInterface ccwVariable, Object code) {
    /*
     * The code parameter is an Object to avoid needing multiple copies of this and
     * related methods.
     * This if-else block is the price to be paid for that, though.
     */
    String codeString;
    if (code instanceof Character) codeString = ((Character) code).toString();
    else if (code instanceof String) codeString = code.toString().trim();
    else throw new BadCodeMonkeyException("Unsupported: " + code);

    String system = CCWUtils.calculateVariableReferenceUrl(ccwVariable);

    String display;
    if (ccwVariable.getVariable().getValueGroups().isPresent())
      display = calculateCodingDisplay(rootResource, ccwVariable, codeString).orElse(null);
    else display = null;

    return new Coding(system, codeString, display);
  }

  /**
   * Creates a coding.
   *
   * @param rootResource the root FHIR {@link IAnyResource} that the resultant {@link Coding} will
   *     be contained in
   * @param ccwVariable the {@link CcwCodebookVariable} being coded
   * @param yearMonth the value to use for {@link String} for yearMonth
   * @param code the value to use for {@link Coding#getCode()}
   * @return the output {@link Coding} for the specified input values
   */
  private static Coding createCoding(
      IAnyResource rootResource, CcwCodebookInterface ccwVariable, String yearMonth, Object code) {
    /*
     * The code parameter is an Object to avoid needing multiple copies of this and
     * related methods.
     * This if-else block is the price to be paid for that, though.
     */
    String codeString;
    if (code instanceof Character) codeString = ((Character) code).toString();
    else if (code instanceof String) codeString = code.toString().trim();
    else throw new BadCodeMonkeyException("Unsupported: " + code);

    String system = CCWUtils.calculateVariableReferenceUrl(ccwVariable);

    String display;
    if (ccwVariable.getVariable().getValueGroups().isPresent())
      display = calculateCodingDisplay(rootResource, ccwVariable, codeString).orElse(null);
    else display = null;

    return new Coding(system, codeString, display);
  }

  /**
   * Creates a coding.
   *
   * @param rootResource the root FHIR {@link IAnyResource} that the resultant {@link Coding} will
   *     be contained in
   * @param ccwVariable the {@link CcwCodebookInterface} being coded
   * @param code the value to use for {@link Coding#getCode()}
   * @return the output {@link Coding} for the specified input values
   */
  private static Coding createCoding(
      IAnyResource rootResource, CcwCodebookInterface ccwVariable, Optional<?> code) {
    return createCoding(rootResource, ccwVariable, code.get());
  }

  /**
   * Creates an adjudication category codeable concept.
   *
   * @param ccwVariable the {@link CcwCodebookInterface} being mapped
   * @return the {@link AdjudicationComponent#getCategory()} {@link CodeableConcept} to use for the
   *     specified {@link CcwCodebookInterface}
   */
  static CodeableConcept createAdjudicationCategory(CcwCodebookInterface ccwVariable) {
    /*
     * Adjudication.category is mapped a bit differently than other
     * Codings/CodeableConcepts: they
     * all share the same Coding.system and use the CcwCodebookVariable reference
     * URL as their
     * Coding.code. This looks weird, but makes it easy for API developers to find
     * more information
     * about what the specific adjudication they're looking at means.
     */

    String conceptCode = CCWUtils.calculateVariableReferenceUrl(ccwVariable);
    CodeableConcept categoryConcept =
        createCodeableConcept(TransformerConstants.CODING_CCW_ADJUDICATION_CATEGORY, conceptCode);
    categoryConcept.getCodingFirstRep().setDisplay(ccwVariable.getVariable().getLabel());
    return categoryConcept;
  }

  /**
   * Creates an adjudication component with a reason code.
   *
   * @param rootResource the root FHIR {@link IAnyResource} that the resultant {@link
   *     AdjudicationComponent} will be contained in
   * @param ccwVariable the {@link CcwCodebookInterface} being coded
   * @param reasonCode the value to use for the {@link AdjudicationComponent#getReason()}'s {@link
   *     Coding#getCode()} for the resulting {@link Coding}
   * @return the output {@link AdjudicationComponent} for the specified input values
   */
  static AdjudicationComponent createAdjudicationWithReason(
      IAnyResource rootResource, CcwCodebookInterface ccwVariable, Object reasonCode) {
    // Cheating here, since they use the same URL.
    String categoryConceptCode = CCWUtils.calculateVariableReferenceUrl(ccwVariable);

    CodeableConcept category =
        createCodeableConcept(
            TransformerConstants.CODING_CCW_ADJUDICATION_CATEGORY, categoryConceptCode);
    category.getCodingFirstRep().setDisplay(ccwVariable.getVariable().getLabel());

    AdjudicationComponent adjudication = new AdjudicationComponent(category);
    adjudication.setReason(createCodeableConcept(rootResource, ccwVariable, reasonCode));

    return adjudication;
  }

  /**
   * Calculates the {@link Coding#getDisplay()} value to use for the specified {@link
   * CcwCodebookInterface} and {@link Coding#getCode()}, or {@link Optional#empty()} if no matching
   * display value could be determined.
   *
   * @param rootResource the root FHIR {@link IAnyResource} that the resultant {@link Coding} will
   *     be contained in
   * @param ccwVariable the {@link CcwCodebookInterface} being coded
   * @param code the FHIR {@link Coding#getCode()} value to determine a corresponding {@link
   *     Coding#getDisplay()} value for
   * @return the {@link Coding#getDisplay()} value to use for the specified {@link
   *     CcwCodebookInterface} and {@link Coding#getCode()}, or {@link Optional#empty()} if no
   *     matching display value could be determined
   */
  private static Optional<String> calculateCodingDisplay(
      IAnyResource rootResource, CcwCodebookInterface ccwVariable, String code) {
    if (rootResource == null) throw new IllegalArgumentException();
    if (ccwVariable == null) throw new IllegalArgumentException();
    if (code == null) throw new IllegalArgumentException();
    if (!ccwVariable.getVariable().getValueGroups().isPresent())
      throw new BadCodeMonkeyException("No display values for Variable: " + ccwVariable);

    /*
     * We know that the specified CCW Variable is coded, but there's no guarantee
     * that the Coding's
     * code matches one of the known/allowed Variable values: data is messy. When
     * that happens, we
     * log the event and return normally. The log event will at least allow for
     * further
     * investigation, if warranted. Also, there's a chance that the CCW Variable
     * data itself is
     * messy, and that the Coding's code matches more than one value -- we just log
     * those events,
     * too.
     */
    List<Value> matchingVariableValues =
        ccwVariable.getVariable().getValueGroups().get().stream()
            .flatMap(g -> g.getValues().stream())
            .filter(v -> v.getCode().equals(code))
            .collect(Collectors.toList());
    if (matchingVariableValues.size() == 1) {
      return Optional.of(matchingVariableValues.get(0).getDescription());
    } else if (matchingVariableValues.isEmpty()) {
      if (!codebookLookupMissingFailures.contains(ccwVariable)) {
        // Note: The race condition here (from concurrent requests) is harmless.
        codebookLookupMissingFailures.add(ccwVariable);
        if (ccwVariable instanceof CcwCodebookVariable) {
          LOGGER.info(
              "No display value match found for {}.{} in resource '{}/{}'.",
              CcwCodebookVariable.class.getSimpleName(),
              ccwVariable.name(),
              rootResource.getClass().getSimpleName(),
              rootResource.getId());
        } else {
          LOGGER.info(
              "No display value match found for {}.{} in resource '{}/{}'.",
              CcwCodebookMissingVariable.class.getSimpleName(),
              ccwVariable.name(),
              rootResource.getClass().getSimpleName(),
              rootResource.getId());
        }
      }
      return Optional.empty();
    } else if (matchingVariableValues.size() > 1) {
      if (!codebookLookupDuplicateFailures.contains(ccwVariable)) {
        // Note: The race condition here (from concurrent requests) is harmless.
        codebookLookupDuplicateFailures.add(ccwVariable);
        if (ccwVariable instanceof CcwCodebookVariable) {
          LOGGER.info(
              "Multiple display value matches found for {}.{} in resource '{}/{}'.",
              CcwCodebookVariable.class.getSimpleName(),
              ccwVariable.name(),
              rootResource.getClass().getSimpleName(),
              rootResource.getId());
        } else {
          LOGGER.info(
              "Multiple display value matches found for {}.{} in resource '{}/{}'.",
              CcwCodebookMissingVariable.class.getSimpleName(),
              ccwVariable.name(),
              rootResource.getClass().getSimpleName(),
              rootResource.getId());
        }
      }
      return Optional.empty();
    } else {
      throw new BadCodeMonkeyException();
    }
  }

  /**
   * Creates a {@link Reference} to a coverage resource.
   *
   * @param beneficiaryPatientId the bene ID value for the {@link Coverage#getBeneficiary()} value
   *     to match
   * @param coverageType the {@link MedicareSegment} value to match
   * @return a {@link Reference} to the {@link Coverage} resource
   */
  static Reference referenceCoverage(Long beneficiaryPatientId, MedicareSegment coverageType) {
    return new Reference(buildCoverageId(coverageType, beneficiaryPatientId));
  }

  /**
   * Creates a new {@link Reference} from the specified patient id.
   *
   * @param patientId the bene id value for the beneficiary to match
   * @return a {@link Reference} to the {@link Patient} resource that matches the specified
   *     parameters
   */
  static Reference referencePatient(Long patientId) {
    return new Reference(String.format("Patient/%d", patientId));
  }

  /**
   * Creates a new {@link Reference} from the specified {@link Beneficiary}.
   *
   * @param beneficiary the {@link Beneficiary} to generate a {@link Patient} {@link Reference} for
   * @return a {@link Reference} to the {@link Patient} resource for the specified {@link
   *     Beneficiary}
   */
  static Reference referencePatient(Beneficiary beneficiary) {
    return referencePatient(beneficiary.getBeneficiaryId());
  }

  /**
   * Creates a new {@link Reference} from the specified {@link Practitioner}.
   *
   * @param practitionerNpi the {@link Practitioner#getIdentifier()} value to match (where {@link
   *     Identifier#getSystem()} is {@value TransformerConstants#CODING_NPI_US})
   * @return a {@link Reference} to the {@link Practitioner} resource that matches the specified
   *     parameters
   */
  static Reference referencePractitioner(String practitionerNpi) {
    return createIdentifierReference(TransformerConstants.CODING_NPI_US, practitionerNpi);
  }

  /**
   * Sets the period end.
   *
   * @param period the {@link Period} to adjust
   * @param date the {@link LocalDate} to set the {@link Period#getEnd()} value with/to
   */
  static void setPeriodEnd(Period period, LocalDate date) {
    period.setEnd(convertToDate(date), TemporalPrecisionEnum.DAY);
  }

  /**
   * Sets the period start.
   *
   * @param period the {@link Period} to adjust
   * @param date the {@link LocalDate} to set the {@link Period#getStart()} value with/to
   */
  static void setPeriodStart(Period period, LocalDate date) {
    period.setStart(convertToDate(date), TemporalPrecisionEnum.DAY);
  }

  /**
   * Creates a url-encoded version of the specified text.
   *
   * @param urlText the URL or URL portion to be encoded
   * @return a URL-encoded version of the specified text
   */
  static String urlEncode(String urlText) {
    try {
      return URLEncoder.encode(urlText, StandardCharsets.UTF_8.name());
    } catch (UnsupportedEncodingException e) {
      throw new BadCodeMonkeyException(e);
    }
  }

  /**
   * Validate the from/thru dates to ensure the from date is before or the same as the thru date.
   *
   * @param dateFrom start date {@link LocalDate}
   * @param dateThrough through date {@link LocalDate} to verify
   */
  static void validatePeriodDates(LocalDate dateFrom, LocalDate dateThrough) {
    if (dateFrom == null) return;
    if (dateThrough == null) return;
    // FIXME see CBBD-236 (ETL service fails on some Hospice claims "From
    // date is after the Through Date")
    // We are seeing this scenario in production where the from date is
    // after the through date so we are just logging the error for now.
    if (dateFrom.isAfter(dateThrough))
      LOGGER.debug(
          String.format(
              "Error - From Date '%s' is after the Through Date '%s'", dateFrom, dateThrough));
  }

  /**
   * Validate the from/thru dates to ensure the from date is before or the same as the thru date.
   *
   * @param dateFrom the date from
   * @param dateThrough the date through
   */
  static void validatePeriodDates(Optional<LocalDate> dateFrom, Optional<LocalDate> dateThrough) {
    if (!dateFrom.isPresent()) return;
    if (!dateThrough.isPresent()) return;
    validatePeriodDates(dateFrom.get(), dateThrough.get());
  }

  /**
   * Adds field values to the benefit balance component that are common between the Inpatient and
   * SNF claim types.
   *
   * @param eob the {@link ExplanationOfBenefit} to map the fields into
   * @param coinsuranceDayCount BENE_TOT_COINSRNC_DAYS_CNT: a {@link BigDecimal} shared field
   *     representing the coinsurance day count for the claim
   * @param nonUtilizationDayCount CLM_NON_UTLZTN_DAYS_CNT: a {@link BigDecimal} shared field
   *     representing the non-utilization day count for the claim
   * @param deductibleAmount NCH_BENE_IP_DDCTBL_AMT: a {@link BigDecimal} shared field representing
   *     the deductible amount for the claim
   * @param partACoinsuranceLiabilityAmount NCH_BENE_PTA_COINSRNC_LBLTY_AM: a {@link BigDecimal}
   *     shared field representing the part A coinsurance amount for the claim
   * @param bloodPintsFurnishedQty NCH_BLOOD_PNTS_FRNSHD_QTY: a {@link BigDecimal} shared field
   *     representing the blood pints furnished quantity for the claim
   * @param noncoveredCharge NCH_IP_NCVRD_CHRG_AMT: a {@link BigDecimal} shared field representing
   *     the non-covered charge for the claim
   * @param totalDeductionAmount NCH_IP_TOT_DDCTN_AMT: a {@link BigDecimal} shared field
   *     representing the total deduction amount for the claim
   * @param claimPPSCapitalDisproportionateShareAmt CLM_PPS_CPTL_DSPRPRTNT_SHR_AMT: an {@link
   *     Optional}&lt;{@link BigDecimal}&gt; shared field representing the claim PPS capital
   *     disproportionate share amount for the claim
   * @param claimPPSCapitalExceptionAmount CLM_PPS_CPTL_EXCPTN_AMT: an {@link Optional}&lt;{@link
   *     BigDecimal}&gt; shared field representing the claim PPS capital exception amount for the
   *     claim
   * @param claimPPSCapitalFSPAmount CLM_PPS_CPTL_FSP_AMT: an {@link Optional}&lt;{@link
   *     BigDecimal}&gt; shared field representing the claim PPS capital FSP amount for the claim
   * @param claimPPSCapitalIMEAmount CLM_PPS_CPTL_IME_AMT: an {@link Optional}&lt;{@link
   *     BigDecimal}&gt; shared field representing the claim PPS capital IME amount for the claim
   * @param claimPPSCapitalOutlierAmount CLM_PPS_CPTL_OUTLIER_AMT: an {@link Optional}&lt;{@link
   *     BigDecimal}&gt; shared field representing the claim PPS capital outlier amount for the
   *     claim
   * @param claimPPSOldCapitalHoldHarmlessAmount CLM_PPS_OLD_CPTL_HLD_HRMLS_AMT: an {@link
   *     Optional}&lt;{@link BigDecimal}&gt; shared field representing the claim PPS old capital
   *     hold harmless amount for the claim
   */
  static void addCommonGroupInpatientSNF(
      ExplanationOfBenefit eob,
      BigDecimal coinsuranceDayCount,
      BigDecimal nonUtilizationDayCount,
      BigDecimal deductibleAmount,
      BigDecimal partACoinsuranceLiabilityAmount,
      BigDecimal bloodPintsFurnishedQty,
      BigDecimal noncoveredCharge,
      BigDecimal totalDeductionAmount,
      Optional<BigDecimal> claimPPSCapitalDisproportionateShareAmt,
      Optional<BigDecimal> claimPPSCapitalExceptionAmount,
      Optional<BigDecimal> claimPPSCapitalFSPAmount,
      Optional<BigDecimal> claimPPSCapitalIMEAmount,
      Optional<BigDecimal> claimPPSCapitalOutlierAmount,
      Optional<BigDecimal> claimPPSOldCapitalHoldHarmlessAmount) {
    BenefitComponent beneTotCoinsrncDaysCntFinancial =
        addBenefitBalanceFinancial(
            eob, BenefitCategory.MEDICAL, CcwCodebookVariable.BENE_TOT_COINSRNC_DAYS_CNT);
    beneTotCoinsrncDaysCntFinancial.setUsed(
        new UnsignedIntType(coinsuranceDayCount.intValueExact()));

    BenefitComponent clmNonUtlztnDaysCntFinancial =
        addBenefitBalanceFinancial(
            eob, BenefitCategory.MEDICAL, CcwCodebookVariable.CLM_NON_UTLZTN_DAYS_CNT);
    clmNonUtlztnDaysCntFinancial.setUsed(
        new UnsignedIntType(nonUtilizationDayCount.intValueExact()));

    addAdjudicationTotal(eob, CcwCodebookVariable.NCH_BENE_IP_DDCTBL_AMT, deductibleAmount);
    addAdjudicationTotal(
        eob, CcwCodebookVariable.NCH_BENE_PTA_COINSRNC_LBLTY_AMT, partACoinsuranceLiabilityAmount);

    SupportingInformationComponent nchBloodPntsFrnshdQtyInfo =
        addInformation(eob, CcwCodebookVariable.NCH_BLOOD_PNTS_FRNSHD_QTY);
    Quantity bloodPintsQuantity = new Quantity();
    bloodPintsQuantity.setValue(bloodPintsFurnishedQty);
    bloodPintsQuantity
        .setSystem(TransformerConstants.CODING_SYSTEM_UCUM)
        .setCode(TransformerConstants.CODING_SYSTEM_UCUM_PINT_CODE)
        .setUnit(TransformerConstants.CODING_SYSTEM_UCUM_PINT_DISPLAY);
    nchBloodPntsFrnshdQtyInfo.setValue(bloodPintsQuantity);

    addAdjudicationTotal(eob, CcwCodebookVariable.NCH_IP_NCVRD_CHRG_AMT, noncoveredCharge);
    addAdjudicationTotal(eob, CcwCodebookVariable.NCH_IP_TOT_DDCTN_AMT, totalDeductionAmount);

    if (claimPPSCapitalDisproportionateShareAmt.isPresent()) {
      addAdjudicationTotal(
          eob,
          CcwCodebookVariable.CLM_PPS_CPTL_DSPRPRTNT_SHR_AMT,
          claimPPSCapitalDisproportionateShareAmt);
    }

    if (claimPPSCapitalExceptionAmount.isPresent()) {
      addAdjudicationTotal(
          eob, CcwCodebookVariable.CLM_PPS_CPTL_EXCPTN_AMT, claimPPSCapitalExceptionAmount);
    }

    if (claimPPSCapitalFSPAmount.isPresent()) {
      addAdjudicationTotal(eob, CcwCodebookVariable.CLM_PPS_CPTL_FSP_AMT, claimPPSCapitalFSPAmount);
    }

    if (claimPPSCapitalIMEAmount.isPresent()) {
      addAdjudicationTotal(eob, CcwCodebookVariable.CLM_PPS_CPTL_IME_AMT, claimPPSCapitalIMEAmount);
    }

    if (claimPPSCapitalOutlierAmount.isPresent()) {
      addAdjudicationTotal(
          eob, CcwCodebookVariable.CLM_PPS_CPTL_OUTLIER_AMT, claimPPSCapitalOutlierAmount);
    }

    if (claimPPSOldCapitalHoldHarmlessAmount.isPresent()) {
      addAdjudicationTotal(
          eob,
          CcwCodebookVariable.CLM_PPS_OLD_CPTL_HLD_HRMLS_AMT,
          claimPPSOldCapitalHoldHarmlessAmount);
    }
  }

  /**
   * Adds EOB information to fields that are common between the Inpatient and SNF claim types.
   *
   * @param eob the {@link ExplanationOfBenefit} that fields will be added to by this method
   * @param admissionTypeCd CLM_IP_ADMSN_TYPE_CD: a {@link Character} shared field representing the
   *     admission type cd for the claim
   * @param sourceAdmissionCd CLM_SRC_IP_ADMSN_CD: an {@link Optional}&lt;{@link Character}&gt;
   *     shared field representing the source admission cd for the claim
   * @param noncoveredStayFromDate NCH_VRFD_NCVRD_STAY_FROM_DT: an {@link Optional}&lt;{@link
   *     LocalDate}&gt; shared field representing the non-covered stay from date for the claim
   * @param noncoveredStayThroughDate NCH_VRFD_NCVRD_STAY_THRU_DT: an {@link Optional}&lt;{@link
   *     LocalDate}&gt; shared field representing the non-covered stay through date for the claim
   * @param coveredCareThroughDate NCH_ACTV_OR_CVRD_LVL_CARE_THRU: an {@link Optional}&lt;{@link
   *     LocalDate}&gt; shared field representing the covered stay through date for the claim
   * @param medicareBenefitsExhaustedDate NCH_BENE_MDCR_BNFTS_EXHTD_DT_I: an {@link
   *     Optional}&lt;{@link LocalDate}&gt; shared field representing the medicare benefits
   *     exhausted date for the claim
   * @param diagnosisRelatedGroupCd CLM_DRG_CD: an {@link Optional}&lt;{@link String}&gt; shared
   *     field representing the non-covered stay from date for the claim
   */
  static void addCommonEobInformationInpatientSNF(
      ExplanationOfBenefit eob,
      Character admissionTypeCd,
      Optional<Character> sourceAdmissionCd,
      Optional<LocalDate> noncoveredStayFromDate,
      Optional<LocalDate> noncoveredStayThroughDate,
      Optional<LocalDate> coveredCareThroughDate,
      Optional<LocalDate> medicareBenefitsExhaustedDate,
      Optional<String> diagnosisRelatedGroupCd) {

    // admissionTypeCd
    addInformationWithCode(
        eob,
        CcwCodebookVariable.CLM_IP_ADMSN_TYPE_CD,
        CcwCodebookVariable.CLM_IP_ADMSN_TYPE_CD,
        admissionTypeCd);

    // sourceAdmissionCd
    if (sourceAdmissionCd.isPresent()) {
      addInformationWithCode(
          eob,
          CcwCodebookVariable.CLM_SRC_IP_ADMSN_CD,
          CcwCodebookVariable.CLM_SRC_IP_ADMSN_CD,
          sourceAdmissionCd);
    }

    // noncoveredStayFromDate & noncoveredStayThroughDate
    if (noncoveredStayFromDate.isPresent() || noncoveredStayThroughDate.isPresent()) {
      TransformerUtils.validatePeriodDates(noncoveredStayFromDate, noncoveredStayThroughDate);
      SupportingInformationComponent nchVrfdNcvrdStayInfo =
          TransformerUtils.addInformation(eob, CcwCodebookVariable.NCH_VRFD_NCVRD_STAY_FROM_DT);
      Period nchVrfdNcvrdStayPeriod = new Period();
      if (noncoveredStayFromDate.isPresent())
        nchVrfdNcvrdStayPeriod.setStart(
            TransformerUtils.convertToDate((noncoveredStayFromDate.get())),
            TemporalPrecisionEnum.DAY);
      if (noncoveredStayThroughDate.isPresent())
        nchVrfdNcvrdStayPeriod.setEnd(
            TransformerUtils.convertToDate((noncoveredStayThroughDate.get())),
            TemporalPrecisionEnum.DAY);
      nchVrfdNcvrdStayInfo.setTiming(nchVrfdNcvrdStayPeriod);
    }

    // coveredCareThroughDate
    if (coveredCareThroughDate.isPresent()) {
      SupportingInformationComponent nchActvOrCvrdLvlCareThruInfo =
          TransformerUtils.addInformation(eob, CcwCodebookVariable.NCH_ACTV_OR_CVRD_LVL_CARE_THRU);
      nchActvOrCvrdLvlCareThruInfo.setTiming(
          new DateType(TransformerUtils.convertToDate(coveredCareThroughDate.get())));
    }

    // medicareBenefitsExhaustedDate
    if (medicareBenefitsExhaustedDate.isPresent()) {
      SupportingInformationComponent nchBeneMdcrBnftsExhtdDtIInfo =
          TransformerUtils.addInformation(eob, CcwCodebookVariable.NCH_BENE_MDCR_BNFTS_EXHTD_DT_I);
      nchBeneMdcrBnftsExhtdDtIInfo.setTiming(
          new DateType(TransformerUtils.convertToDate(medicareBenefitsExhaustedDate.get())));
    }

    // diagnosisRelatedGroupCd
    if (diagnosisRelatedGroupCd.isPresent()) {
      /*
       * FIXME This is an invalid DiagnosisComponent, since it's missing a (required)
       * ICD code.
       * Instead, stick the DRG on the claim's primary/first diagnosis. SamhsaMatcher
       * uses this
       * field so if this is updated you'll need to update that as well.
       */
      int maxSequence = eob.getDiagnosis().stream().mapToInt(i -> i.getSequence()).max().orElse(0);
      eob.addDiagnosis()
          .setPackageCode(
              createCodeableConcept(eob, CcwCodebookVariable.CLM_DRG_CD, diagnosisRelatedGroupCd))
          .setSequence(maxSequence + 1);
    }
  }

  /**
   * Maps a blue button claim type to a FHIR claim type.
   *
   * @param eob the {@link CodeableConcept} that will get remapped
   * @param blueButtonClaimType the blue button {@link ClaimType} we are mapping from
   * @param ccwNearLineRecordIdCode if present, the blue button near line id code {@link
   *     Optional}&lt;{@link Character}&gt; gets remapped to a ccw record id code
   * @param ccwClaimTypeCode if present, the blue button claim type code {@link Optional}&lt;{@link
   *     String}&gt; gets remapped to a nch claim type code
   */
  static void mapEobType(
      ExplanationOfBenefit eob,
      ClaimType blueButtonClaimType,
      Optional<Character> ccwNearLineRecordIdCode,
      Optional<String> ccwClaimTypeCode) {

    // map blue button claim type code into a nch claim type
    if (ccwClaimTypeCode.isPresent()) {
      eob.getType()
          .addCoding(createCoding(eob, CcwCodebookVariable.NCH_CLM_TYPE_CD, ccwClaimTypeCode));
    }

    // This Coding MUST always be present as it's the only one we can definitely map
    // for all 8 of our claim types.
    eob.getType()
        .addCoding()
        .setSystem(TransformerConstants.CODING_SYSTEM_BBAPI_EOB_TYPE)
        .setCode(blueButtonClaimType.name());

    // Map a Coding for FHIR's ClaimType coding system, if we can.
    org.hl7.fhir.dstu3.model.codesystems.ClaimType fhirClaimType;
    switch (blueButtonClaimType) {
      case CARRIER:
      case OUTPATIENT:
        fhirClaimType = org.hl7.fhir.dstu3.model.codesystems.ClaimType.PROFESSIONAL;
        break;

      case INPATIENT:
      case HOSPICE:
      case SNF:
        fhirClaimType = org.hl7.fhir.dstu3.model.codesystems.ClaimType.INSTITUTIONAL;
        break;

      case PDE:
        fhirClaimType = org.hl7.fhir.dstu3.model.codesystems.ClaimType.PHARMACY;
        break;

      case HHA:
      case DME:
        fhirClaimType = null;
        // FUTURE these blue button claim types currently have no equivalent
        // CODING_FHIR_CLAIM_TYPE mapping
        break;

      default:
        // unknown claim type
        throw new BadCodeMonkeyException();
    }
    if (fhirClaimType != null)
      eob.getType()
          .addCoding(
              new Coding(
                  fhirClaimType.getSystem(), fhirClaimType.toCode(), fhirClaimType.getDisplay()));

    // map blue button near line record id to a ccw record id code
    if (ccwNearLineRecordIdCode.isPresent()) {
      eob.getType()
          .addCoding(
              createCoding(
                  eob, CcwCodebookVariable.NCH_NEAR_LINE_REC_IDENT_CD, ccwNearLineRecordIdCode));
    }
  }

  /**
   * Transforms the common group level header fields between all claim types.
   *
   * @param eob the {@link ExplanationOfBenefit} to modify
   * @param claimId CLM_ID
   * @param beneficiaryId BENE_ID
   * @param claimType {@link ClaimType} to process
   * @param claimGroupId CLM_GRP_ID
   * @param coverageType {@link MedicareSegment}
   * @param dateFrom CLM_FROM_DT || SRVC_DT (For Part D Events)
   * @param dateThrough CLM_THRU_DT || SRVC_DT (For Part D Events)
   * @param paymentAmount CLM_PMT_AMT
   * @param finalAction FINAL_ACTION
   */
  static void mapEobCommonClaimHeaderData(
      ExplanationOfBenefit eob,
      Long claimId,
      Long beneficiaryId,
      ClaimType claimType,
      String claimGroupId,
      MedicareSegment coverageType,
      Optional<LocalDate> dateFrom,
      Optional<LocalDate> dateThrough,
      Optional<BigDecimal> paymentAmount,
      char finalAction) {

    eob.setId(buildEobId(claimType, claimId));

    if (claimType.equals(ClaimType.PDE))
      eob.addIdentifier(createIdentifier(CcwCodebookVariable.PDE_ID, String.valueOf(claimId)));
    else eob.addIdentifier(createIdentifier(CcwCodebookVariable.CLM_ID, String.valueOf(claimId)));

    eob.addIdentifier()
        .setSystem(TransformerConstants.IDENTIFIER_SYSTEM_BBAPI_CLAIM_GROUP_ID)
        .setValue(claimGroupId);

    eob.getInsurance().setCoverage(referenceCoverage(beneficiaryId, coverageType));
    eob.setPatient(referencePatient(beneficiaryId));
    switch (finalAction) {
      case 'F':
        eob.setStatus(ExplanationOfBenefitStatus.ACTIVE);
        break;
      case 'N':
        eob.setStatus(ExplanationOfBenefitStatus.CANCELLED);
        break;
      default:
        // unknown final action value
        throw new BadCodeMonkeyException();
    }

    if (dateFrom.isPresent()) {
      validatePeriodDates(dateFrom, dateThrough);
      setPeriodStart(eob.getBillablePeriod(), dateFrom.get());
      setPeriodEnd(eob.getBillablePeriod(), dateThrough.get());
    }

    if (paymentAmount.isPresent()) {
      eob.getPayment().setAmount(createMoney(paymentAmount));
    }
  }

  /**
   * Maps the eob weekly process date.
   *
   * @param eob the eob to add the information to
   * @param weeklyProcessLocalDate the weekly process local date
   */
  static void mapEobWeeklyProcessDate(ExplanationOfBenefit eob, LocalDate weeklyProcessLocalDate) {
    TransformerUtils.addInformation(eob, CcwCodebookVariable.NCH_WKLY_PROC_DT)
        .setTiming(new DateType(TransformerUtils.convertToDate(weeklyProcessLocalDate)));
  }

  /**
   * Transforms the common group level data elements between the {@link CarrierClaim} and {@link
   * DMEClaim}* claim types to FHIR. The method parameter fields from {@link CarrierClaim} and
   * {@link DMEClaim}* are listed below and their corresponding RIF CCW fields (denoted in all CAPS
   * below from {@link CarrierClaimColumn} and {@link DMEClaimColumn}).
   *
   * @param eob the {@link ExplanationOfBenefit} to modify
   * @param beneficiaryId BEME_ID,
   * @param carrierNumber CARR_NUM,
   * @param clinicalTrialNumber CLM_CLNCL_TRIL_NUM,
   * @param beneficiaryPartBDeductAmount CARR_CLM_CASH_DDCTBL_APLD_AMT,
   * @param paymentDenialCode CARR_CLM_PMT_DNL_CD,
   * @param referringPhysicianNpi RFR_PHYSN_NPI
   * @param providerAssignmentIndicator CARR_CLM_PRVDR_ASGNMT_IND_SW,
   * @param providerPaymentAmount NCH_CLM_PRVDR_PMT_AMT,
   * @param beneficiaryPaymentAmount NCH_CLM_BENE_PMT_AMT,
   * @param submittedChargeAmount NCH_CARR_CLM_SBMTD_CHRG_AMT,
   * @param allowedChargeAmount NCH_CARR_CLM_ALOWD_AMT,
   * @param claimDispositionCode CLM_DISP_CD
   * @param claimCarrierControlNumber CARR_CLM_CNTL_NUM
   */
  static void mapEobCommonGroupCarrierDME(
      ExplanationOfBenefit eob,
      Long beneficiaryId,
      String carrierNumber,
      Optional<String> clinicalTrialNumber,
      BigDecimal beneficiaryPartBDeductAmount,
      String paymentDenialCode,
      Optional<String> referringPhysicianNpi,
      Optional<Character> providerAssignmentIndicator,
      BigDecimal providerPaymentAmount,
      BigDecimal beneficiaryPaymentAmount,
      BigDecimal submittedChargeAmount,
      BigDecimal allowedChargeAmount,
      String claimDispositionCode,
      Optional<String> claimCarrierControlNumber) {

    eob.addExtension(createExtensionIdentifier(CcwCodebookVariable.CARR_NUM, carrierNumber));

    // Carrier Claim Control Number
    if (claimCarrierControlNumber.isPresent()) {
      eob.addExtension(
          createExtensionIdentifier(
              CcwCodebookMissingVariable.CARR_CLM_CNTL_NUM, claimCarrierControlNumber.get()));
    }

    eob.addExtension(
        createExtensionCoding(eob, CcwCodebookVariable.CARR_CLM_PMT_DNL_CD, paymentDenialCode));

    // Claim Disposition Code
    eob.setDisposition(claimDispositionCode);

    /*
     * Referrals are represented as contained resources, since they share the
     * lifecycle and identity
     * of their containing EOB.
     */
    if (referringPhysicianNpi.isPresent()) {
      ReferralRequest referral = new ReferralRequest();
      referral.setStatus(ReferralRequestStatus.COMPLETED);
      referral.setSubject(referencePatient(beneficiaryId));
      referral.setRequester(
          new ReferralRequestRequesterComponent(
              referencePractitioner(referringPhysicianNpi.get())));
      referral.addRecipient(referencePractitioner(referringPhysicianNpi.get()));
      // Set the ReferralRequest as a contained resource in the EOB:
      eob.setReferral(new Reference(referral));
    }

    if (providerAssignmentIndicator.isPresent()) {
      eob.addExtension(
          createExtensionCoding(eob, CcwCodebookVariable.ASGMNTCD, providerAssignmentIndicator));
    }

    if (clinicalTrialNumber.isPresent()) {
      eob.addExtension(
          createExtensionIdentifier(CcwCodebookVariable.CLM_CLNCL_TRIL_NUM, clinicalTrialNumber));
    }

    addAdjudicationTotal(
        eob, CcwCodebookVariable.CARR_CLM_CASH_DDCTBL_APLD_AMT, beneficiaryPartBDeductAmount);
    addAdjudicationTotal(eob, CcwCodebookVariable.NCH_CLM_PRVDR_PMT_AMT, providerPaymentAmount);
    addAdjudicationTotal(eob, CcwCodebookVariable.NCH_CLM_BENE_PMT_AMT, beneficiaryPaymentAmount);
    addAdjudicationTotal(
        eob, CcwCodebookVariable.NCH_CARR_CLM_SBMTD_CHRG_AMT, submittedChargeAmount);
    addAdjudicationTotal(eob, CcwCodebookVariable.NCH_CARR_CLM_ALOWD_AMT, allowedChargeAmount);
  }

  /**
   * Transforms the common item level data elements between the {@link CarrierClaimLine} and {@link
   * DMEClaimLine}* claim types to FHIR. The method parameter fields from {@link CarrierClaimLine}
   * and {@link DMEClaimLine} are listed below and their corresponding RIF CCW fields (denoted in
   * all CAPS below from {@link CarrierClaimColumn} and {@link DMEClaimColumn}).
   *
   * @param item the {@link ItemComponent} to modify
   * @param eob the {@link ExplanationOfBenefit} to modify
   * @param claimId CLM_ID,
   * @param serviceCount LINE_SRVC_CNT,
   * @param placeOfServiceCode LINE_PLACE_OF_SRVC_CD,
   * @param firstExpenseDate LINE_1ST_EXPNS_DT,
   * @param lastExpenseDate LINE_LAST_EXPNS_DT,
   * @param beneficiaryPaymentAmount LINE_BENE_PMT_AMT,
   * @param providerPaymentAmount LINE_PRVDR_PMT_AMT,
   * @param beneficiaryPartBDeductAmount LINE_BENE_PTB_DDCTBL_AMT,
   * @param primaryPayerCode LINE_BENE_PRMRY_PYR_CD,
   * @param primaryPayerPaidAmount LINE_BENE_PRMRY_PYR_PD_AMT,
   * @param betosCode BETOS_CD,
   * @param paymentAmount LINE_NCH_PMT_AMT,
   * @param paymentCode LINE_PMT_80_100_CD,
   * @param coinsuranceAmount LINE_COINSRNC_AMT,
   * @param submittedChargeAmount LINE_SBMTD_CHRG_AMT,
   * @param allowedChargeAmount LINE_ALOWD_CHRG_AMT,
   * @param processingIndicatorCode LINE_PRCSG_IND_CD,
   * @param serviceDeductibleCode LINE_SERVICE_DEDUCTIBLE,
   * @param diagnosisCode LINE_ICD_DGNS_CD,
   * @param diagnosisCodeVersion LINE_ICD_DGNS_VRSN_CD,
   * @param hctHgbTestTypeCode LINE_HCT_HGB_TYPE_CD
   * @param hctHgbTestResult LINE_HCT_HGB_RSLT_NUM,
   * @param cmsServiceTypeCode LINE_CMS_TYPE_SRVC_CD,
   * @param nationalDrugCode LINE_NDC_CD,
   * @param drugCodeName the drug code name
   * @return the {@link ItemComponent}
   */
  static ItemComponent mapEobCommonItemCarrierDME(
      ItemComponent item,
      ExplanationOfBenefit eob,
      Long claimId,
      BigDecimal serviceCount,
      String placeOfServiceCode,
      Optional<LocalDate> firstExpenseDate,
      Optional<LocalDate> lastExpenseDate,
      BigDecimal beneficiaryPaymentAmount,
      BigDecimal providerPaymentAmount,
      BigDecimal beneficiaryPartBDeductAmount,
      Optional<Character> primaryPayerCode,
      BigDecimal primaryPayerPaidAmount,
      Optional<String> betosCode,
      BigDecimal paymentAmount,
      Optional<Character> paymentCode,
      BigDecimal coinsuranceAmount,
      BigDecimal submittedChargeAmount,
      BigDecimal allowedChargeAmount,
      Optional<String> processingIndicatorCode,
      Optional<Character> serviceDeductibleCode,
      Optional<String> diagnosisCode,
      Optional<Character> diagnosisCodeVersion,
      Optional<String> hctHgbTestTypeCode,
      BigDecimal hctHgbTestResult,
      char cmsServiceTypeCode,
      Optional<String> nationalDrugCode,
      String drugCodeName) {

    SimpleQuantity serviceCnt = new SimpleQuantity();
    serviceCnt.setValue(serviceCount);
    item.setQuantity(serviceCnt);

    item.setCategory(
        createCodeableConcept(eob, CcwCodebookVariable.LINE_CMS_TYPE_SRVC_CD, cmsServiceTypeCode));

    item.setLocation(
        createCodeableConcept(eob, CcwCodebookVariable.LINE_PLACE_OF_SRVC_CD, placeOfServiceCode));

    if (betosCode.isPresent()) {
      item.addExtension(createExtensionCoding(eob, CcwCodebookVariable.BETOS_CD, betosCode));
    }

    if (firstExpenseDate.isPresent() && lastExpenseDate.isPresent()) {
      validatePeriodDates(firstExpenseDate, lastExpenseDate);
      item.setServiced(
          new Period()
              .setStart((convertToDate(firstExpenseDate.get())), TemporalPrecisionEnum.DAY)
              .setEnd((convertToDate(lastExpenseDate.get())), TemporalPrecisionEnum.DAY));
    }

    AdjudicationComponent adjudicationForPayment = item.addAdjudication();
    adjudicationForPayment
        .setCategory(createAdjudicationCategory(CcwCodebookVariable.LINE_NCH_PMT_AMT))
        .setAmount(createMoney(paymentAmount));
    if (paymentCode.isPresent())
      adjudicationForPayment.addExtension(
          createExtensionCoding(eob, CcwCodebookVariable.LINE_PMT_80_100_CD, paymentCode));

    item.addAdjudication()
        .setCategory(createAdjudicationCategory(CcwCodebookVariable.LINE_BENE_PMT_AMT))
        .setAmount(createMoney(beneficiaryPaymentAmount));

    item.addAdjudication()
        .setCategory(createAdjudicationCategory(CcwCodebookVariable.LINE_PRVDR_PMT_AMT))
        .setAmount(createMoney(providerPaymentAmount));

    item.addAdjudication()
        .setCategory(
            TransformerUtils.createAdjudicationCategory(
                CcwCodebookVariable.LINE_BENE_PTB_DDCTBL_AMT))
        .setAmount(createMoney(beneficiaryPartBDeductAmount));

    if (primaryPayerCode.isPresent()) {
      item.addExtension(
          createExtensionCoding(eob, CcwCodebookVariable.LINE_BENE_PRMRY_PYR_CD, primaryPayerCode));
    }

    item.addAdjudication()
        .setCategory(createAdjudicationCategory(CcwCodebookVariable.LINE_BENE_PRMRY_PYR_PD_AMT))
        .setAmount(createMoney(primaryPayerPaidAmount));
    item.addAdjudication()
        .setCategory(
            TransformerUtils.createAdjudicationCategory(CcwCodebookVariable.LINE_COINSRNC_AMT))
        .setAmount(createMoney(coinsuranceAmount));

    item.addAdjudication()
        .setCategory(createAdjudicationCategory(CcwCodebookVariable.LINE_SBMTD_CHRG_AMT))
        .setAmount(createMoney(submittedChargeAmount));

    item.addAdjudication()
        .setCategory(
            TransformerUtils.createAdjudicationCategory(CcwCodebookVariable.LINE_ALOWD_CHRG_AMT))
        .setAmount(createMoney(allowedChargeAmount));

    if (processingIndicatorCode.isPresent())
      item.addAdjudication(
          createAdjudicationWithReason(
              eob, CcwCodebookVariable.LINE_PRCSG_IND_CD, processingIndicatorCode));

    if (serviceDeductibleCode.isPresent())
      item.addExtension(
          createExtensionCoding(
              eob, CcwCodebookVariable.LINE_SERVICE_DEDUCTIBLE, serviceDeductibleCode));

    Optional<Diagnosis> lineDiagnosis = Diagnosis.from(diagnosisCode, diagnosisCodeVersion);
    if (lineDiagnosis.isPresent()) addDiagnosisLink(eob, item, lineDiagnosis.get());

    if (hctHgbTestTypeCode.isPresent()) {
      Observation hctHgbObservation = new Observation();
      hctHgbObservation.setStatus(ObservationStatus.UNKNOWN);
      hctHgbObservation.setCode(
          createCodeableConcept(eob, CcwCodebookVariable.LINE_HCT_HGB_TYPE_CD, hctHgbTestTypeCode));
      hctHgbObservation.setValue(new Quantity().setValue(hctHgbTestResult));

      Extension hctHgbObservationReference =
          new Extension(
              CCWUtils.calculateVariableReferenceUrl(CcwCodebookVariable.LINE_HCT_HGB_RSLT_NUM),
              new Reference(hctHgbObservation));
      item.addExtension(hctHgbObservationReference);
    }

    if (nationalDrugCode.isPresent()) {
      addExtensionCoding(
          item,
          TransformerConstants.CODING_NDC,
          TransformerConstants.CODING_NDC,
          drugCodeName,
          nationalDrugCode.get());
    }

    return item;
  }

  /**
   * Transforms the common item level data elements between the {@link InpatientClaimLine} {@link
   * OutpatientClaimLine} {@link HospiceClaimLine} {@link HHAClaimLine}and {@link SNFClaimLine}
   * claim types to FHIR. The method parameter fields from {@link InpatientClaimLine} {@link
   * OutpatientClaimLine} {@link HospiceClaimLine} {@link HHAClaimLine}and {@link SNFClaimLine} are
   * listed below and their corresponding RIF CCW fields (denoted in all CAPS below from {@link
   * InpatientClaimColumn} {@link OutpatientClaimColumn} {@link HospiceClaimColumn} {@link
   * HHAClaimColumn} and {@link SNFClaimColumn}).
   *
   * @param item the {@link ItemComponent} to modify
   * @param eob the {@link ExplanationOfBenefit} to modify
   * @param revenueCenterCode REV_CNTR,
   * @param rateAmount REV_CNTR_RATE_AMT,
   * @param totalChargeAmount REV_CNTR_TOT_CHRG_AMT,
   * @param nonCoveredChargeAmount REV_CNTR_NCVRD_CHRG_AMT,
   * @param unitCount REV_CNTR_UNIT_CNT,
   * @param nationalDrugCodeQuantity REV_CNTR_NDC_QTY,
   * @param nationalDrugCodeQualifierCode REV_CNTR_NDC_QTY_QLFR_CD,
   * @param revenueCenterRenderingPhysicianNPI RNDRNG_PHYSN_NPI
   * @return the {@link ItemComponent}
   */
  static ItemComponent mapEobCommonItemRevenue(
      ItemComponent item,
      ExplanationOfBenefit eob,
      String revenueCenterCode,
      BigDecimal rateAmount,
      BigDecimal totalChargeAmount,
      BigDecimal nonCoveredChargeAmount,
      BigDecimal unitCount,
      Optional<BigDecimal> nationalDrugCodeQuantity,
      Optional<String> nationalDrugCodeQualifierCode,
      Optional<String> revenueCenterRenderingPhysicianNPI) {

    item.setRevenue(createCodeableConcept(eob, CcwCodebookVariable.REV_CNTR, revenueCenterCode));

    item.addAdjudication()
        .setCategory(
            TransformerUtils.createAdjudicationCategory(CcwCodebookVariable.REV_CNTR_RATE_AMT))
        .setAmount(createMoney(rateAmount));

    item.addAdjudication()
        .setCategory(
            TransformerUtils.createAdjudicationCategory(CcwCodebookVariable.REV_CNTR_TOT_CHRG_AMT))
        .setAmount(createMoney(totalChargeAmount));

    item.addAdjudication()
        .setCategory(
            TransformerUtils.createAdjudicationCategory(
                CcwCodebookVariable.REV_CNTR_NCVRD_CHRG_AMT))
        .setAmount(createMoney(nonCoveredChargeAmount));

    SimpleQuantity qty = new SimpleQuantity();
    qty.setValue(unitCount);
    item.setQuantity(qty);

    if (nationalDrugCodeQualifierCode.isPresent()) {
      /*
       * TODO: Is NDC count only ever present when line quantity isn't set? Depending
       * on that, it
       * may be that we should stop using this as an extension and instead set the
       * code & system on
       * the FHIR quantity field.
       */
      // TODO Shouldn't this be part of the same Extension with the NDC code itself?
      Extension drugQuantityExtension =
          createExtensionQuantity(CcwCodebookVariable.REV_CNTR_NDC_QTY, nationalDrugCodeQuantity);
      Quantity drugQuantity = (Quantity) drugQuantityExtension.getValue();
      TransformerUtils.setQuantityUnitInfo(
          CcwCodebookVariable.REV_CNTR_NDC_QTY_QLFR_CD,
          nationalDrugCodeQualifierCode,
          eob,
          drugQuantity);

      item.addExtension(drugQuantityExtension);
    }

    if (revenueCenterRenderingPhysicianNPI.isPresent()) {
      TransformerUtils.addCareTeamPractitioner(
          eob,
          item,
          TransformerConstants.CODING_NPI_US,
          revenueCenterRenderingPhysicianNPI.get(),
          ClaimCareteamrole.PRIMARY);
    }

    return item;
  }

  /**
   * Transforms the common item level data elements between the {@link OutpatientClaimLine} {@link
   * HospiceClaimLine} and {@link HHAClaimLine} claim types to FHIR. The method parameter fields
   * from {@link OutpatientClaimLine} {@link HospiceClaimLine} and {@link HHAClaimLine} are listed
   * below and their corresponding RIF CCW fields (denoted in all CAPS below from {@link
   * OutpatientClaimColumn} {@link HospiceClaimColumn} and {@link HHAClaimColumn}.
   *
   * @param item the {@link ItemComponent} to modify
   * @param revenueCenterDate REV_CNTR_DT,
   * @param paymentAmount REV_CNTR_PMT_AMT_AMT
   */
  static void mapEobCommonItemRevenueOutHHAHospice(
      ItemComponent item, Optional<LocalDate> revenueCenterDate, BigDecimal paymentAmount) {

    // Revenue Center Date
    if (revenueCenterDate.isPresent()) {
      item.setServiced(new DateType().setValue(convertToDate(revenueCenterDate.get())));
    }

    item.addAdjudication()
        .setCategory(createAdjudicationCategory(CcwCodebookVariable.REV_CNTR_PMT_AMT_AMT))
        .setAmount(createMoney(paymentAmount));
  }

  /**
   * Transforms the common group level data elements between the {@link InpatientClaim}, {@link
   * OutpatientClaim}* and {@link SNFClaim} claim types to FHIR. The method parameter fields from
   * {@link InpatientClaim}, {@link OutpatientClaim} and {@link SNFClaim} are listed below and their
   * corresponding RIF CCW fields (denoted in all CAPS below from {@link InpatientClaimColumn}
   * {@link OutpatientClaimColumn}and {@link SNFClaimColumn}).
   *
   * @param eob the {@link ExplanationOfBenefit} to modify
   * @param bloodDeductibleLiabilityAmount NCH_BENE_BLOOD_DDCTBL_LBLTY_AM
   * @param operatingPhysicianNpi the operating physician npi
   * @param otherPhysicianNpi the other physician npi
   * @param claimQueryCode CLAIM_QUERY_CODE
   * @param mcoPaidSw CLM_MCO_PD_SW
   */
  static void mapEobCommonGroupInpOutSNF(
      ExplanationOfBenefit eob,
      BigDecimal bloodDeductibleLiabilityAmount,
      Optional<String> operatingPhysicianNpi,
      Optional<String> otherPhysicianNpi,
      char claimQueryCode,
      Optional<Character> mcoPaidSw) {
    addAdjudicationTotal(
        eob, CcwCodebookVariable.NCH_BENE_BLOOD_DDCTBL_LBLTY_AM, bloodDeductibleLiabilityAmount);

    if (operatingPhysicianNpi.isPresent()) {
      TransformerUtils.addCareTeamPractitioner(
          eob,
          null,
          TransformerConstants.CODING_NPI_US,
          operatingPhysicianNpi.get(),
          ClaimCareteamrole.ASSIST);
    }

    if (otherPhysicianNpi.isPresent()) {
      TransformerUtils.addCareTeamPractitioner(
          eob,
          null,
          TransformerConstants.CODING_NPI_US,
          otherPhysicianNpi.get(),
          ClaimCareteamrole.OTHER);
    }

    eob.getBillablePeriod()
        .addExtension(
            createExtensionCoding(eob, CcwCodebookVariable.CLAIM_QUERY_CD, claimQueryCode));

    if (mcoPaidSw.isPresent()) {
      TransformerUtils.addInformationWithCode(
          eob, CcwCodebookVariable.CLM_MCO_PD_SW, CcwCodebookVariable.CLM_MCO_PD_SW, mcoPaidSw);
    }
  }

  /**
   * Transforms the common group level data elements between the {@link InpatientClaimLine} {@link
   * OutpatientClaimLine}* {@link HospiceClaimLine} {@link HHAClaimLine}and {@link SNFClaimLine}
   * claim types to FHIR. The method parameter fields from {@link InpatientClaimLine} {@link
   * OutpatientClaimLine}* {@link HospiceClaimLine} {@link HHAClaimLine}and {@link SNFClaimLine} are
   * listed below and their corresponding RIF CCW fields (denoted in all CAPS below from {@link
   * InpatientClaimColumn}* {@link OutpatientClaimColumn} {@link HospiceClaimColumn} {@link
   * HHAClaimColumn}* and {@link SNFClaimColumn}).
   *
   * @param eob the {@link ExplanationOfBenefit} to modify
   * @param organizationNpi ORG_NPI_NUM,
   * @param organizationNpiDisplay the organization npi display
   * @param claimFacilityTypeCode CLM_FAC_TYPE_CD,
   * @param claimFrequencyCode CLM_FREQ_CD,
   * @param claimNonPaymentReasonCode CLM_MDCR_NON_PMT_RSN_CD,
   * @param patientDischargeStatusCode PTNT_DSCHRG_STUS_CD,
   * @param claimServiceClassificationTypeCode CLM_SRVC_CLSFCTN_TYPE_CD,
   * @param claimPrimaryPayerCode NCH_PRMRY_PYR_CD,
   * @param attendingPhysicianNpi AT_PHYSN_NPI,
   * @param totalChargeAmount CLM_TOT_CHRG_AMT,
   * @param primaryPayerPaidAmount NCH_PRMRY_PYR_CLM_PD_AMT,
   * @param fiscalIntermediaryNumber FI_NUM
   * @param fiDocumentClaimControlNumber FI_DOC_CLM_CNTL_NUM
   * @param fiOriginalClaimControlNumber the fi original claim control number
   */
  static void mapEobCommonGroupInpOutHHAHospiceSNF(
      ExplanationOfBenefit eob,
      Optional<String> organizationNpi,
      Optional<String> organizationNpiDisplay,
      char claimFacilityTypeCode,
      char claimFrequencyCode,
      Optional<String> claimNonPaymentReasonCode,
      String patientDischargeStatusCode,
      char claimServiceClassificationTypeCode,
      Optional<Character> claimPrimaryPayerCode,
      Optional<String> attendingPhysicianNpi,
      BigDecimal totalChargeAmount,
      BigDecimal primaryPayerPaidAmount,
      Optional<String> fiscalIntermediaryNumber,
      Optional<String> fiDocumentClaimControlNumber,
      Optional<String> fiOriginalClaimControlNumber) {

    if (organizationNpi.isPresent()) {
      eob.setOrganization(
          TransformerUtils.createIdentifierReference(
              TransformerConstants.CODING_NPI_US, organizationNpi.get(), organizationNpiDisplay));
      eob.setFacility(
          TransformerUtils.createIdentifierReference(
              TransformerConstants.CODING_NPI_US, organizationNpi.get(), organizationNpiDisplay));
    }

    eob.getFacility()
        .addExtension(
            createExtensionCoding(eob, CcwCodebookVariable.CLM_FAC_TYPE_CD, claimFacilityTypeCode));

    TransformerUtils.addInformationWithCode(
        eob, CcwCodebookVariable.CLM_FREQ_CD, CcwCodebookVariable.CLM_FREQ_CD, claimFrequencyCode);

    if (claimNonPaymentReasonCode.isPresent()) {
      eob.addExtension(
          createExtensionCoding(
              eob, CcwCodebookVariable.CLM_MDCR_NON_PMT_RSN_CD, claimNonPaymentReasonCode));
    }

    if (!patientDischargeStatusCode.isEmpty()) {
      TransformerUtils.addInformationWithCode(
          eob,
          CcwCodebookVariable.PTNT_DSCHRG_STUS_CD,
          CcwCodebookVariable.PTNT_DSCHRG_STUS_CD,
          patientDischargeStatusCode);
    }

    // FIXME move into the mapType(...) method
    eob.getType()
        .addCoding(
            createCoding(
                eob,
                CcwCodebookVariable.CLM_SRVC_CLSFCTN_TYPE_CD,
                claimServiceClassificationTypeCode));

    if (claimPrimaryPayerCode.isPresent()) {
      TransformerUtils.addInformationWithCode(
          eob,
          CcwCodebookVariable.NCH_PRMRY_PYR_CD,
          CcwCodebookVariable.NCH_PRMRY_PYR_CD,
          claimPrimaryPayerCode.get());
    }

    if (attendingPhysicianNpi.isPresent()) {
      TransformerUtils.addCareTeamPractitioner(
          eob,
          null,
          TransformerConstants.CODING_NPI_US,
          attendingPhysicianNpi.get(),
          ClaimCareteamrole.PRIMARY);
    }
    eob.setTotalCost(createMoney(totalChargeAmount));

    addAdjudicationTotal(eob, CcwCodebookVariable.PRPAYAMT, primaryPayerPaidAmount);

    if (fiscalIntermediaryNumber.isPresent()) {
      eob.addExtension(
          createExtensionIdentifier(CcwCodebookVariable.FI_NUM, fiscalIntermediaryNumber));
    }

    if (fiDocumentClaimControlNumber.isPresent()) {
      eob.addExtension(
          createExtensionIdentifier(
              CcwCodebookMissingVariable.FI_DOC_CLM_CNTL_NUM, fiDocumentClaimControlNumber.get()));
    }

    if (fiOriginalClaimControlNumber.isPresent()) {
      eob.addExtension(
          createExtensionIdentifier(
              CcwCodebookMissingVariable.FI_ORIG_CLM_CNTL_NUM, fiOriginalClaimControlNumber.get()));
    }
  }

  /**
   * Transforms the common group level data elements between the {@link InpatientClaim} {@link
   * HHAClaim}* {@link HospiceClaim} and {@link SNFClaim} claim types to FHIR. The method parameter
   * fields from {@link InpatientClaim} {@link HHAClaim} {@link HospiceClaim} and {@link SNFClaim}
   * are listed below and their corresponding RIF CCW fields (denoted in all CAPS below from {@link
   * InpatientClaimColumn}* {@link HHAClaimColumn} {@link HospiceClaimColumn} and {@link
   * SNFClaimColumn}).
   *
   * @param eob the {@link ExplanationOfBenefit} to modify
   * @param claimAdmissionDate CLM_ADMSN_DT,
   * @param beneficiaryDischargeDate the beneficiary discharge date
   * @param utilizedDays CLM_UTLZTN_CNT,
   * @return the {@link ExplanationOfBenefit}
   */
  static ExplanationOfBenefit mapEobCommonGroupInpHHAHospiceSNF(
      ExplanationOfBenefit eob,
      Optional<LocalDate> claimAdmissionDate,
      Optional<LocalDate> beneficiaryDischargeDate,
      Optional<BigDecimal> utilizedDays) {

    if (claimAdmissionDate.isPresent() || beneficiaryDischargeDate.isPresent()) {
      TransformerUtils.validatePeriodDates(claimAdmissionDate, beneficiaryDischargeDate);
      Period period = new Period();
      if (claimAdmissionDate.isPresent()) {
        period.setStart(
            TransformerUtils.convertToDate(claimAdmissionDate.get()), TemporalPrecisionEnum.DAY);
      }
      if (beneficiaryDischargeDate.isPresent()) {
        period.setEnd(
            TransformerUtils.convertToDate(beneficiaryDischargeDate.get()),
            TemporalPrecisionEnum.DAY);
      }

      eob.setHospitalization(period);
    }

    if (utilizedDays.isPresent()) {
      BenefitComponent clmUtlztnDayCntFinancial =
          TransformerUtils.addBenefitBalanceFinancial(
              eob, BenefitCategory.MEDICAL, CcwCodebookVariable.CLM_UTLZTN_DAY_CNT);
      clmUtlztnDayCntFinancial.setUsed(new UnsignedIntType(utilizedDays.get().intValue()));
    }

    return eob;
  }

  /**
   * Transforms the common group level data elements between the {@link InpatientClaim} {@link
   * HHAClaim} {@link HospiceClaim} and {@link SNFClaim} claim types to FHIR. The method parameter
   * fields from {@link InpatientClaim} {@link HHAClaim} {@link HospiceClaim} and {@link SNFClaim}
   * are listed below and their corresponding RIF CCW fields (denoted in all CAPS below from {@link
   * InpatientClaimColumn} {@link HHAClaimColumn} {@link HospiceClaimColumn} and {@link
   * SNFClaimColumn}).
   *
   * @param eob the root {@link ExplanationOfBenefit} that the {@link ItemComponent} is part of
   * @param item the {@link ItemComponent} to modify
   * @param deductibleCoinsuranceCd REV_CNTR_DDCTBL_COINSRNC_CD
   */
  static void mapEobCommonGroupInpHHAHospiceSNFCoinsurance(
      ExplanationOfBenefit eob, ItemComponent item, Optional<Character> deductibleCoinsuranceCd) {

    if (deductibleCoinsuranceCd.isPresent()) {
      // FIXME should this be an adjudication?
      item.getRevenue()
          .addExtension(
              createExtensionCoding(
                  eob, CcwCodebookVariable.REV_CNTR_DDCTBL_COINSRNC_CD, deductibleCoinsuranceCd));
    }
  }

  /**
   * Generically attempts to retrieve a diagnosis from the current claim.
   *
   * @param substitution The methods to retrive diagnosis information all follow a similar pattern.
   *     This value is used to substitute into that pattern when looking up the specific method to
   *     retrive information with.
   * @param codes The mapping of diagnosis codes by their property name and respective value
   * @param codeVersions The mapping of diagnosis code versions by their property name and
   *     respective value
   * @param presentOnAdms The mapping of diagnosis "PresentOnAdmissionCode" codes by their property
   *     name and respective value
   * @param ccw CCW Codebook value that represents which "PresentOnAdmissionCode" is being used.
   *     Example: {@link CcwCodebookVariable#CLM_POA_IND_SW5}
   * @param label One or more labels to use when mapping the diagnosis.
   * @return a {@link Diagnosis} or {@link Optional#empty()}
   */
  public static Optional<Diagnosis> extractDiagnosis(
      String substitution,
      Map<String, Optional<String>> codes,
      Map<String, Optional<Character>> codeVersions,
      Map<String, Optional<Character>> presentOnAdms,
      Optional<CcwCodebookInterface> ccw,
      Optional<DiagnosisLabel> label) {
    Optional<String> code =
        codes.getOrDefault(String.format("diagnosis%sCode", substitution), Optional.empty());
    Optional<Character> codeVersion =
        codeVersions.getOrDefault(
            String.format("diagnosis%sCodeVersion", substitution), Optional.empty());
    Optional<Character> presentOnAdm =
        presentOnAdms.isEmpty()
            ? Optional.empty()
            : presentOnAdms.getOrDefault(
                String.format("diagnosis%sPresentOnAdmissionCode", substitution), Optional.empty());
    if (presentOnAdm.isEmpty() && ccw.isEmpty() && label.isEmpty())
      return Diagnosis.from(code, codeVersion);
    if (presentOnAdm.isEmpty() && ccw.isEmpty())
      return Diagnosis.from(code, codeVersion, label.get());
    return Diagnosis.from(code, codeVersion, presentOnAdm, ccw, label.get());
  }

  /**
   * Extracts all possible diagnosis types from a Claim.
   *
   * @param codes The mapping of diagnosis codes by their property name and respective value
   * @param codeVersions The mapping of diagnosis code versions by their property name and
   *     respective value
   * @param presentOnAdms The mapping of diagnosis "PresentOnAdmissionCode" codes by their property
   *     name and respective value
   * @return the {@link Diagnosis} that can be extracted from the specified claim properties
   */
  static List<Diagnosis> extractDiagnoses(
      Map<String, Optional<String>> codes,
      Map<String, Optional<Character>> codeVersions,
      Map<String, Optional<Character>> presentOnAdms) {

    List<Optional<Diagnosis>> diagnosis = new ArrayList<>();
    // Handle the "special" diagnosis fields
    diagnosis.add(
        extractDiagnosis(
            "Admitting",
            codes,
            codeVersions,
            presentOnAdms,
            Optional.empty(),
            Optional.of(DiagnosisLabel.ADMITTING)));
    diagnosis.add(
        extractDiagnosis(
            "1",
            codes,
            codeVersions,
            presentOnAdms,
            presentOnAdms.isEmpty()
                ? Optional.empty()
                : Optional.of(CcwCodebookVariable.CLM_POA_IND_SW1),
            Optional.of(DiagnosisLabel.PRINCIPAL)));
    diagnosis.add(
        extractDiagnosis(
            "Principal",
            codes,
            codeVersions,
            presentOnAdms,
            Optional.empty(),
            Optional.of(DiagnosisLabel.PRINCIPAL)));

    // Generically handle the rest (2-25)
    final int FIRST_DIAG = 2;
    final int LAST_DIAG = 25;
    IntStream.range(FIRST_DIAG, LAST_DIAG + 1)
        .mapToObj(
            i ->
                extractDiagnosis(
                    String.valueOf(i),
                    codes,
                    codeVersions,
                    presentOnAdms,
                    presentOnAdms.isEmpty()
                        ? Optional.empty()
                        : Optional.of(CcwCodebookVariable.valueOf("CLM_POA_IND_SW" + i)),
                    presentOnAdms.isEmpty() ? Optional.empty() : Optional.of(DiagnosisLabel.OTHER)))
        .forEach(diagnosis::add);

    // Handle first external diagnosis
    diagnosis.add(
        extractDiagnosis(
            "External1",
            codes,
            codeVersions,
            presentOnAdms,
            presentOnAdms.isEmpty()
                ? Optional.empty()
                : Optional.of(CcwCodebookVariable.CLM_E_POA_IND_SW1),
            Optional.of(DiagnosisLabel.FIRSTEXTERNAL)));
    diagnosis.add(
        extractDiagnosis(
            "ExternalFirst",
            codes,
            codeVersions,
            presentOnAdms,
            Optional.empty(),
            Optional.of(DiagnosisLabel.FIRSTEXTERNAL)));

    // Generically handle the rest (2-12)
    final int FIRST_EX_DIAG = 2;
    final int LAST_EX_DIAG = 12;
    IntStream.range(FIRST_EX_DIAG, LAST_EX_DIAG + 1)
        .mapToObj(
            i ->
                extractDiagnosis(
                    "External" + i,
                    codes,
                    codeVersions,
                    presentOnAdms,
                    presentOnAdms.isEmpty()
                        ? Optional.empty()
                        : Optional.of(CcwCodebookVariable.valueOf("CLM_E_POA_IND_SW" + i)),
                    Optional.of(DiagnosisLabel.EXTERNAL)))
        .forEach(diagnosis::add);

    // RSN_VISIT_CD(1-3) => diagnosis.diagnosisCodeableConcept
    // RSN_VISIT_VRSN_CD(1-3) => diagnosis.diagnosisCodeableConcept
    final int FIRST_INPATIENT_DIAGNOSIS = 1;
    final int LAST_INPATIENT_DIAGNOSIS = 3;
    IntStream.range(FIRST_INPATIENT_DIAGNOSIS, LAST_INPATIENT_DIAGNOSIS + 1)
        .mapToObj(
            i ->
                TransformerUtils.extractDiagnosis(
                    String.format("Admission%d", i),
                    codes,
                    codeVersions,
                    Map.of(),
                    Optional.empty(),
                    Optional.of(DiagnosisLabel.REASONFORVISIT)))
        .forEach(diagnosis::add);

    // Some may be empty. Convert from List<Optional<Diagnosis>> to List<Diagnosis>
    return diagnosis.stream()
        .filter(d -> d.isPresent())
        .map(d -> d.get())
        .collect(Collectors.toList());
  }

  /**
   * Generically attempts to retrieve a procedure from the current claim.
   *
   * @param procedure Procedure accessors all follow the same pattern except for an integer
   *     difference. This value is used as a substitution when looking up the method name.
   * @param codes The mapping of procedure codes by their property name and respective value
   * @param codeVersions The mapping of procedure code versions by their property name and
   *     respective value
   * @param dates The mapping of procedure dates by their property name and respective value
   * @return a {@link CCWProcedure} or {@link Optional#empty()}
   */
  public static Optional<CCWProcedure> extractCCWProcedure(
      int procedure,
      Map<String, Optional<String>> codes,
      Map<String, Optional<Character>> codeVersions,
      Map<String, Optional<LocalDate>> dates) {
    Optional<String> code =
        codes.getOrDefault(String.format("procedure%dCode", procedure), Optional.empty());
    Optional<Character> codeVersion =
        codeVersions.getOrDefault(
            String.format("procedure%dCodeVersion", procedure), Optional.empty());
    Optional<LocalDate> date =
        dates.getOrDefault(String.format("procedure%dDate", procedure), Optional.empty());
    return CCWProcedure.from(code, codeVersion, date);
  }

  /**
   * Generically attempts to retrieve the procedures from the current claim.
   *
   * @param codes The mapping of procedure codes by their property name and respective value
   * @param codeVersions The mapping of procedure code versions by their property name and
   *     respective value
   * @param dates The mapping of procedure dates by their property name and respective value
   * @return a list of {@link CCWProcedure}
   */
  public static List<CCWProcedure> extractCCWProcedures(
      Map<String, Optional<String>> codes,
      Map<String, Optional<Character>> codeVersions,
      Map<String, Optional<LocalDate>> dates) {
    // Handle Procedures
    // ICD_PRCDR_CD(1-25) => ExplanationOfBenefit.procedure.procedureCodableConcept
    // ICD_PRCDR_VRSN_CD(1-25) =>
    // ExplanationOfBenefit.procedure.procedureCodableConcept
    // PRCDR_DT(1-25) => ExplanationOfBenefit.procedure.date
    final int FIRST_PROCEDURE = 1;
    final int LAST_PROCEDURE = 25;
    return IntStream.range(FIRST_PROCEDURE, LAST_PROCEDURE + 1)
        .mapToObj(i -> TransformerUtils.extractCCWProcedure(i, codes, codeVersions, dates))
        .filter(p -> p.isPresent())
        .map(p -> p.get())
        .toList();
  }

  /**
   * Sets the provider number field which is common among these claim types: Inpatient, Outpatient,
   * Hospice, HHA and SNF.
   *
   * @param eob the {@link ExplanationOfBenefit} this method will modify
   * @param providerNumber a {@link String} PRVDR_NUM: representing the provider number for the
   *     claim
   */
  static void setProviderNumber(ExplanationOfBenefit eob, String providerNumber) {
    eob.setProvider(
        new Reference()
            .setIdentifier(
                TransformerUtils.createIdentifier(CcwCodebookVariable.PRVDR_NUM, providerNumber)));
  }

  /**
   * Maps a hcpcs {@link CodeableConcept} and any applicable modifiders to the given {@link
   * ItemComponent}.
   *
   * @param eob the {@link ExplanationOfBenefit} that the HCPCS code is being mapped into
   * @param item the {@link ItemComponent} that the HCPCS code is being mapped into
   * @param hcpcsYear the {@link CcwCodebookVariable#CARR_CLM_HCPCS_YR_CD} identifying the HCPCS
   *     code version in use
   * @param hcpcs the {@link CcwCodebookVariable#HCPCS_CD} to be mapped
   * @param hcpcsModifiers the {@link CcwCodebookVariable#HCPCS_1ST_MDFR_CD}, etc. values to be
   *     mapped (if any)
   */
  static void mapHcpcs(
      ExplanationOfBenefit eob,
      ItemComponent item,
      Optional<Character> hcpcsYear,
      Optional<String> hcpcs,
      List<Optional<String>> hcpcsModifiers) {
    // Create and map all of the possible CodeableConcepts.
    CodeableConcept hcpcsConcept =
        hcpcs.isPresent()
            ? createCodeableConcept(TransformerConstants.CODING_SYSTEM_HCPCS, hcpcs.get())
            : null;
    if (hcpcsConcept != null) item.setService(hcpcsConcept);
    List<CodeableConcept> hcpcsModifierConcepts = new ArrayList<>(4);
    for (Optional<String> hcpcsModifier : hcpcsModifiers) {
      if (!hcpcsModifier.isPresent()) continue;

      CodeableConcept hcpcsModifierConcept =
          createCodeableConcept(TransformerConstants.CODING_SYSTEM_HCPCS, hcpcsModifier.get());
      hcpcsModifierConcepts.add(hcpcsModifierConcept);
      item.addModifier(hcpcsModifierConcept);
    }

    // Set Coding.version for all of the mappings, if it's available.
    Stream.concat(Arrays.asList(hcpcsConcept).stream(), hcpcsModifierConcepts.stream())
        .forEach(
            concept -> {
              if (concept == null) return;
              if (!hcpcsYear.isPresent()) return;

              // Note: Only CARRIER and DME claims have the year/version field.
              concept.getCodingFirstRep().setVersion(hcpcsYear.get().toString());
            });
  }

  /**
   * Retrieves the Diagnosis display value from a Diagnosis code look up file.
   *
   * @param icdCode Diagnosis code
   * @return the icd code display
   */
  public static String retrieveIcdCodeDisplay(String icdCode) {

    if (icdCode.isEmpty()) return null;

    /*
     * There's a race condition here: we may initialize this static field more than
     * once if multiple
     * requests come in at the same time. However, the assignment is atomic, so the
     * race and
     * reinitialization is harmless other than maybe wasting a bit of time.
     */
    // read the entire ICD file the first time and put in a Map
    if (icdMap == null) {
      icdMap = readIcdCodeFile();
    }

    if (icdMap.containsKey(icdCode.toUpperCase())) {
      String icdCodeDisplay = icdMap.get(icdCode);
      return icdCodeDisplay;
    }

    // log which ICD codes we couldn't find a match for in our downloaded ICD file
    if (!icdCodeLookupMissingFailures.contains(icdCode)) {
      icdCodeLookupMissingFailures.add(icdCode);
      LOGGER.info(
          "No ICD code display value match found for ICD code {} in resource {}.",
          icdCode,
          "DGNS_CD.txt");
    }

    return null;
  }

  /**
   * Reads ALL the ICD codes and display values from the DGNS_CD.txt file. Refer to the README file
   * in the src/main/resources directory.
   *
   * @return the map of idc codes
   */
  private static Map<String, String> readIcdCodeFile() {
    Map<String, String> icdDiagnosisMap = new HashMap<String, String>();

    try (final InputStream icdCodeDisplayStream =
            Thread.currentThread().getContextClassLoader().getResourceAsStream("DGNS_CD.txt");
        final BufferedReader icdCodesIn =
            new BufferedReader(new InputStreamReader(icdCodeDisplayStream))) {
      /*
       * We want to extract the ICD Diagnosis codes and display values and put in a
       * map for easy
       * retrieval to get the display value icdColumns[1] is DGNS_DESC(i.e. 7840 code
       * is HEADACHE
       * description)
       */
      String line = "";
      icdCodesIn.readLine();
      while ((line = icdCodesIn.readLine()) != null) {
        String[] icdColumns = line.split("\t");
        icdDiagnosisMap.put(icdColumns[0], icdColumns[1]);
      }
      icdCodesIn.close();
    } catch (IOException e) {
      throw new UncheckedIOException("Unable to read ICD code data.", e);
    }

    return icdDiagnosisMap;
  }

  /**
   * Retrieves the NPI display value from an NPI code look up file.
   *
   * @param npiCode NPI code
   * @return the npi code display
   */
  public static String retrieveNpiCodeDisplay(String npiCode) {

    if (npiCode.isEmpty()) return null;

    /*
     * There's a race condition here: we may initialize this static field more than
     * once if multiple
     * requests come in at the same time. However, the assignment is atomic, so the
     * race and
     * reinitialization is harmless other than maybe wasting a bit of time.
     */
    // read the entire NPI file the first time and put in a Map
    if (npiMap == null) {
      npiMap = readNpiCodeFile();
    }

    if (npiMap.containsKey(npiCode.toUpperCase())) {
      String npiCodeDisplay = npiMap.get(npiCode);
      return npiCodeDisplay;
    }

    // log which NPI codes we couldn't find a match for in our downloaded NPI file
    npiCodeLookupMissingFailures.add(npiCode);

    return null;
  }

  /**
   * Gets the reference variable.
   *
   * @param ccwCodebookVariable the {@link CcwCodebookVariable} to get the url
   * @return url as a string
   */
  static String getReferenceUrl(CcwCodebookVariable ccwCodebookVariable) {
    return CCWUtils.calculateVariableReferenceUrl(ccwCodebookVariable);
  }

  /**
   * Checks to see if there is a extension that already exists in the careteamcomponent so a
   * duplicate entry for extension is not added. This is meant for non-Optional fields only, please
   * use the method codePresentAndCareTeamHasMatchingExtension for Optionals.
   *
   * @param careTeamComponent care team component
   * @param referenceUrl the {@link String} is the reference url to compare
   * @param codeValue the {@link String} is the code value to compare
   * @return {@link Boolean} whether it was found or not
   */
  public static boolean careTeamHasMatchingExtension(
      CareTeamComponent careTeamComponent, String referenceUrl, String codeValue) {

    if (!Strings.isNullOrEmpty(referenceUrl)
        && !Strings.isNullOrEmpty(codeValue)
        && careTeamComponent.getExtension().size() > 0) {

      List<Extension> extensions = careTeamComponent.getExtensionsByUrl(referenceUrl);

      for (Extension ext : extensions) {
        Coding coding = null;

        if (ext.getValue() instanceof Coding) {
          coding = (Coding) ext.getValue();
        }

        if (coding != null && coding.getCode().equals(codeValue)) {
          return true;
        }
      }
    }

    return false;
  }

  /**
   * Adds a care team extension to the supplied careTeamComponent if there is not already an
   * extension for the supplied extensionValue and extensionValue is not empty.
   *
   * @param codebookVariable the codebook variable to make the reference url
   * @param extensionValue the value for the extension, typically sourced from the claimLine
   * @param careTeamComponent the care team component to look for the extension in
   * @param eob the eob
   */
  public static void addCareTeamExtension(
      CcwCodebookVariable codebookVariable,
      Optional<?> extensionValue,
      CareTeamComponent careTeamComponent,
      ExplanationOfBenefit eob) {

    // If our extension value is an empty optional or empty/null string, nothing to add
    if (extensionValue.isEmpty() || Strings.isNullOrEmpty(String.valueOf(extensionValue.get()))) {
      return;
    }

    String valueAsString = String.valueOf(extensionValue.get());

    addCareTeamExtension(codebookVariable, valueAsString, careTeamComponent, eob);
  }

  /**
   * Adds a care team extension to the supplied careTeamComponent if there is not already an
   * extension for the supplied extensionValue and extensionValue is not empty.
   *
   * @param codebookVariable the codebook variable to make the reference url
   * @param extensionValue the value for the extension, typically sourced from the claimLine
   * @param careTeamComponent the care team component to look for the extension in
   * @param eob the eob
   */
  public static void addCareTeamExtension(
      CcwCodebookVariable codebookVariable,
      char extensionValue,
      CareTeamComponent careTeamComponent,
      ExplanationOfBenefit eob) {
    // If our extension value is empty/null, nothing to add
    if (Strings.isNullOrEmpty(String.valueOf(extensionValue))) {
      return;
    }

    String valueAsString = String.valueOf(extensionValue);

    addCareTeamExtension(codebookVariable, valueAsString, careTeamComponent, eob);
  }

  /**
   * Adds a care team extension to the supplied careTeamComponent if there is not already an
   * extension for the supplied extensionValue.
   *
   * <p>This method is kept private to dissuade the unpacking of optionals at the caller level; use
   * the methods above for optional/char values so that we can do validation within the util method
   * and keep it out of the calling code. If we have mandatory string values, this can be opened up,
   * but should be noted the values should be passed in as-is from the line, not transformed prior
   * to the call.
   *
   * @param codebookVariable the codebook variable to make the reference url
   * @param extensionValue the value for the extension, typically sourced from the claimLine
   * @param careTeamComponent the care team component to look for the extension in
   * @param eob the eob
   */
  private static void addCareTeamExtension(
      CcwCodebookVariable codebookVariable,
      String extensionValue,
      CareTeamComponent careTeamComponent,
      ExplanationOfBenefit eob) {
    String referenceUrl = getReferenceUrl(codebookVariable);
    boolean hasExtension =
        careTeamHasMatchingExtension(careTeamComponent, referenceUrl, extensionValue);

    // If the extension doesnt exist, add it
    if (!hasExtension) {
      careTeamComponent.addExtension(createExtensionCoding(eob, codebookVariable, extensionValue));
    }
  }

  /**
   * Adds a qualification {@link org.hl7.fhir.r4.model.CodeableConcept} to the given careTeam
   * component, if the input code optional is not empty. If the code is empty, returns with no
   * effect. Can safely be called to add qualification only if the value is present.
   *
   * @param careTeam the care team to add the
   * @param rootResource the root resource to use for the coding
   * @param ccwVariable the ccw variable to use for the coding
   * @param code an optional to create the {@link org.hl7.fhir.r4.model.CodeableConcept} from; if
   *     empty, method returns with no action taken
   */
  static void addCareTeamQualification(
      CareTeamComponent careTeam,
      IAnyResource rootResource,
      CcwCodebookInterface ccwVariable,
      Optional<?> code) {
    // While the original code was written in such a way that implies this optional wont be empty,
    // its still an optional, so dont bother adding anything if it happens to be empty
    if (code.isEmpty()) {
      return;
    }

    Coding coding = createCoding(rootResource, ccwVariable, code.get());

    CodeableConcept concept = new CodeableConcept();
    concept.addCoding(coding);

    careTeam.setQualification(concept);
  }

  /**
   * Reads ALL the NPI codes and display values from the NPI_Coded_Display_Values_Tab.txt file.
   * Refer to the README file in the src/main/resources directory.
   *
   * @return the map of npi codes
   */
  private static Map<String, String> readNpiCodeFile() {

    Map<String, String> npiCodeMap = new HashMap<String, String>();
    try (final InputStream npiCodeDisplayStream =
            Thread.currentThread()
                .getContextClassLoader()
                .getResourceAsStream("NPI_Coded_Display_Values_Tab.txt");
        final BufferedReader npiCodesIn =
            new BufferedReader(new InputStreamReader(npiCodeDisplayStream))) {
      /*
       * We want to extract the NPI codes and display values and put in a map for easy
       * retrieval to
       * get the display value-- npiColumns[0] is the NPI Code, npiColumns[4] is the
       * NPI
       * Organization Code, npiColumns[8] is the NPI provider name prefix,
       * npiColumns[6] is the NPI
       * provider first name, npiColumns[7] is the NPI provider middle name,
       * npiColumns[5] is the
       * NPI provider last name, npiColumns[9] is the NPI provider suffix name,
       * npiColumns[10] is
       * the NPI provider credential.
       */
      String line = "";
      npiCodesIn.readLine();
      while ((line = npiCodesIn.readLine()) != null) {
        String[] npiColumns = line.split("\t");
        if (npiColumns[4].isEmpty()) {
          String npiDisplayName =
              npiColumns[8].trim()
                  + " "
                  + npiColumns[6].trim()
                  + " "
                  + npiColumns[7].trim()
                  + " "
                  + npiColumns[5].trim()
                  + " "
                  + npiColumns[9].trim()
                  + " "
                  + npiColumns[10].trim();
          npiCodeMap.put(npiColumns[0], npiDisplayName.replace("  ", " ").trim());
        } else {
          npiCodeMap.put(npiColumns[0], npiColumns[4].replace("\"", "").trim());
        }
      }
    } catch (IOException e) {
      throw new UncheckedIOException("Unable to read NPI code data.", e);
    }
    return npiCodeMap;
  }

  /**
   * Retrieves the Procedure code and display value from a Procedure code look up file.
   *
   * @param procedureCode procedure code
   * @return the procedure code display
   */
  public static String retrieveProcedureCodeDisplay(String procedureCode) {

    if (procedureCode.isEmpty()) return null;

    /*
     * There's a race condition here: we may initialize this static field more than
     * once if multiple
     * requests come in at the same time. However, the assignment is atomic, so the
     * race and
     * reinitialization is harmless other than maybe wasting a bit of time.
     */
    // read the entire Procedure code file the first time and put in a Map
    if (procedureMap == null) {
      procedureMap = readProcedureCodeFile();
    }

    if (procedureMap.containsKey(procedureCode.toUpperCase())) {
      String procedureCodeDisplay = procedureMap.get(procedureCode);
      return procedureCodeDisplay;
    }

    // log which Procedure codes we couldn't find a match for in our procedure codes
    // file
    if (!procedureLookupMissingFailures.contains(procedureCode)) {
      procedureLookupMissingFailures.add(procedureCode);
      LOGGER.info(
          "No procedure code display value match found for procedure code {} in resource {}.",
          procedureCode,
          "PRCDR_CD.txt");
    }

    return null;
  }

  /**
   * Reads all the procedure codes and display values from the PRCDR_CD.txt file Refer to the README
   * file in the src/main/resources directory.
   *
   * @return the map of procedure codes
   */
  private static Map<String, String> readProcedureCodeFile() {

    Map<String, String> procedureCodeMap = new HashMap<String, String>();
    try (final InputStream procedureCodeDisplayStream =
            Thread.currentThread().getContextClassLoader().getResourceAsStream("PRCDR_CD.txt");
        final BufferedReader procedureCodesIn =
            new BufferedReader(new InputStreamReader(procedureCodeDisplayStream))) {
      /*
       * We want to extract the procedure codes and display values and put in a map
       * for easy
       * retrieval to get the display value icdColumns[0] is PRCDR_CD; icdColumns[1]
       * is
       * PRCDR_DESC(i.e. 8295 is INJECT TENDON OF HAND description)
       */
      String line = "";
      procedureCodesIn.readLine();
      while ((line = procedureCodesIn.readLine()) != null) {
        String[] icdColumns = line.split("\t");
        procedureCodeMap.put(icdColumns[0], icdColumns[1]);
      }
      procedureCodesIn.close();
    } catch (IOException e) {
      throw new UncheckedIOException("Unable to read Procedure code data.", e);
    }

    return procedureCodeMap;
  }

  /**
   * Create a bundle from the entire search result.
   *
   * @param paging contains the {@link OffsetLinkBuilder} information
   * @param resources a list of {@link ExplanationOfBenefit}s, {@link Coverage}s, or {@link
   *     Patient}s, of which a portion or all will be added to the bundle based on the paging values
   * @param transactionTime date for the bundle
   * @return Returns a {@link Bundle} of either {@link ExplanationOfBenefit}s, {@link Coverage}s, or
   *     {@link Patient}s, which may contain multiple matching resources, or may also be empty.
   */
  public static Bundle createBundle(
      OffsetLinkBuilder paging, List<IBaseResource> resources, Instant transactionTime) {
    Bundle bundle = new Bundle();
    List<IBaseResource> resourcesSubList = resources;
    if (paging.isPagingRequested()) {
      /*
       * FIXME: Due to a bug in HAPI-FHIR described here
       * https://github.com/jamesagnew/hapi-fhir/issues/1074 paging for count=0 is not
       * working
       * correctly.
       */
      // If we have no resources, don't sublist anything since it causes indexing issues
      if (resources.size() > 0) {
        int endIndex = Math.min(paging.getStartIndex() + paging.getPageSize(), resources.size());
        // Throw a 400 if startIndex >= results, since we cant sublist with these values
        if (paging.getStartIndex() >= resources.size()) {
          throw new InvalidRequestException(
              String.format(
                  "Value for startIndex (%s) must be less than than result size (%s)",
                  paging.getStartIndex(), resources.size()));
        }
        resourcesSubList = resources.subList(paging.getStartIndex(), endIndex);
      }
      bundle = TransformerUtils.addResourcesToBundle(bundle, resourcesSubList);
      paging.setTotal(resources.size()).addLinks(bundle);

      // Add number of paginated resources to MDC logs
      LoggingUtils.logResourceCountToMdc(resourcesSubList.size());
    } else {
      bundle = TransformerUtils.addResourcesToBundle(bundle, resources);

      // Add number of resources to MDC logs
      LoggingUtils.logResourceCountToMdc(resources.size());
    }

    /*
     * Dev Note: the Bundle's lastUpdated timestamp is the known last update time
     * for the whole
     * database. Because the filterManager's tracking of this timestamp is lazily
     * updated for
     * performance reason, the resources of the bundle may be after the filter
     * manager's version of
     * the timestamp.
     */
    // FUTURE: Fix this to use sublist similar to V2 to fix bug
    Instant maxBundleDate =
        resources.stream()
            .map(r -> r.getMeta().getLastUpdated().toInstant())
            .filter(Objects::nonNull)
            .max(Instant::compareTo)
            .orElse(transactionTime);
    bundle
        .getMeta()
        .setLastUpdated(
            transactionTime.isAfter(maxBundleDate)
                ? Date.from(transactionTime)
                : Date.from(maxBundleDate));
    bundle.setTotal(resources.size());
    return bundle;
  }

  /**
   * Create a bundle from the entire search result.
   *
   * @param resources a list of {@link ExplanationOfBenefit}s, {@link Coverage}s, or {@link
   *     Patient}s, all of which will be added to the bundle
   * @param paging contains the {@link LinkBuilder} information to add to the bundle
   * @param transactionTime date for the bundle
   * @return Returns a {@link Bundle} of either {@link ExplanationOfBenefit}s, {@link Coverage}s, or
   *     {@link Patient}s, which may contain multiple matching resources, or may also be empty.
   */
  public static Bundle createBundle(
      List<IBaseResource> resources, LinkBuilder paging, Instant transactionTime) {
    Bundle bundle = new Bundle();
    TransformerUtils.addResourcesToBundle(bundle, resources);
    paging.addLinks(bundle);
    bundle.setTotalElement(
        paging.isPagingRequested() ? new UnsignedIntType() : new UnsignedIntType(resources.size()));

    /*
     * Dev Note: the Bundle's lastUpdated timestamp is the known last update time
     * for the whole
     * database. Because the filterManager's tracking of this timestamp is lazily
     * updated for
     * performance reason, the resources of the bundle may be after the filter
     * manager's version of
     * the timestamp.
     */
    Instant maxBundleDate =
        resources.stream()
            .map(r -> r.getMeta().getLastUpdated().toInstant())
            .filter(Objects::nonNull)
            .max(Instant::compareTo)
            .orElse(transactionTime);
    bundle
        .getMeta()
        .setLastUpdated(
            transactionTime.isAfter(maxBundleDate)
                ? Date.from(transactionTime)
                : Date.from(maxBundleDate));

    // Add number of resources to MDC logs
    LoggingUtils.logResourceCountToMdc(bundle.getTotal());

    return bundle;
  }

  /**
   * Adds resources to the specified bundle.
   *
   * @param bundle a {@link Bundle} to add the list of {@link ExplanationOfBenefit} resources to.
   * @param resources a list of either {@link ExplanationOfBenefit}s, {@link Coverage}s, or {@link
   *     Patient}s, of which a portion will be added to the bundle based on the paging values
   * @return Returns a {@link Bundle} of {@link ExplanationOfBenefit}s, {@link Coverage}s, or {@link
   *     Patient}s, which may contain multiple matching resources, or may also be empty.
   */
  public static Bundle addResourcesToBundle(Bundle bundle, List<IBaseResource> resources) {
    for (IBaseResource res : resources) {
      BundleEntryComponent entry = bundle.addEntry();
      entry.setResource((Resource) res);
    }
    return bundle;
  }

  /**
   * Creates a currency identifier extension.
   *
   * @param currencyIdentifier the {@link CurrencyIdentifier} indicating the currency of an {@link
   *     Identifier}.
   * @return Returns an {@link Extension} describing the currency of an {@link Identifier}.
   */
  public static Extension createIdentifierCurrencyExtension(CurrencyIdentifier currencyIdentifier) {
    String system = TransformerConstants.CODING_SYSTEM_IDENTIFIER_CURRENCY;
    String code = "historic";
    String display = "Historic";
    if (currencyIdentifier.equals(CurrencyIdentifier.CURRENT)) {
      code = "current";
      display = "Current";
    }

    Coding currentValueCoding = new Coding(system, code, display);
    Extension currencyIdentifierExtension =
        new Extension(TransformerConstants.CODING_SYSTEM_IDENTIFIER_CURRENCY, currentValueCoding);

    return currencyIdentifierExtension;
  }

  /**
   * Records the JPA query details in {@link BfdMDC}.
   *
   * @param queryId an ID that identifies the type of JPA query being run, e.g. "bene_by_id"
   * @param queryDurationNanoseconds the JPA query's duration, in nanoseconds
   * @param recordCount the number of top-level records (e.g. JPA entities) returned by the query
   */
  public static void recordQueryInMdc(
      String queryId, long queryDurationNanoseconds, long recordCount) {
    String keyPrefix = String.format("jpa_query_%s", queryId);
    BfdMDC.put(
        String.format("%s_duration_nanoseconds", keyPrefix),
        Long.toString(queryDurationNanoseconds));
    BfdMDC.put(
        String.format("%s_duration_milliseconds", keyPrefix),
        Long.toString(queryDurationNanoseconds / 1000000));
    BfdMDC.put(String.format("%s_record_count", keyPrefix), Long.toString(recordCount));
  }

  /**
   * Sets the lastUpdated value in the resource.
   *
   * @param resource is the FHIR resource to set lastUpdate
   * @param lastUpdated is the lastUpdated value set. If not present, set the fallback lastUdpated.
   */
  public static void setLastUpdated(IAnyResource resource, Optional<Instant> lastUpdated) {
    resource
        .getMeta()
        .setLastUpdated(Date.from(lastUpdated.orElse(TransformerConstants.FALLBACK_LAST_UPDATED)));
  }

  /**
   * Sets the lastUpdated value in the resource if the passed in value is later than the current
   * value.
   *
   * @param resource is the FHIR resource to update
   * @param lastUpdated is the lastUpdated value from the entity
   */
  public static void updateMaxLastUpdated(IAnyResource resource, Optional<Instant> lastUpdated) {
    lastUpdated.ifPresent(
        newDate -> {
          Instant currentDate =
              resource.getMeta().getLastUpdated() != null
                  ? resource.getMeta().getLastUpdated().toInstant()
                  : null;
          if (currentDate != null && newDate.isAfter(currentDate)) {
            resource.getMeta().setLastUpdated(Date.from(newDate));
          }
        });
  }

  /**
   * Work around for https://github.com/jamesagnew/hapi-fhir/issues/1585. HAPI will fill in the
   * resource count as a total value when a Bundle has no total value.
   *
   * @param requestDetails of a resource provider
   */
  public static void workAroundHAPIIssue1585(RequestDetails requestDetails) {
    // The hack is to remove the _count parameter from theDetails so that total is
    // not modified.
    Map<String, String[]> params = new HashMap<String, String[]>(requestDetails.getParameters());
    if (params.remove(Constants.PARAM_COUNT) != null) {
      // Remove _count parameter from the current request details
      requestDetails.setParameters(params);
    }
  }

  /**
   * Compares {@link LocalDate} a against {@link LocalDate} using the supplied {@link
   * ParamPrefixEnum}.
   *
   * @param a the first item to compare
   * @param b the second item to compare
   * @param prefix prefix to use. Supported: {@link ParamPrefixEnum#GREATERTHAN_OR_EQUALS}, {@link
   *     ParamPrefixEnum#GREATERTHAN}, {@link ParamPrefixEnum#LESSTHAN_OR_EQUALS}, {@link
   *     ParamPrefixEnum#LESSTHAN}
   * @return true if the comparison between a and b returned true
   * @throws IllegalArgumentException if caller supplied an unsupported prefix
   */
  public static boolean compareLocalDate(
      @Nullable LocalDate a, @Nullable LocalDate b, ParamPrefixEnum prefix) {
    if (a == null || b == null) {
      return false;
    }
    switch (prefix) {
      case GREATERTHAN_OR_EQUALS:
        return !a.isBefore(b);
      case GREATERTHAN:
        return a.isAfter(b);
      case LESSTHAN_OR_EQUALS:
        return !a.isAfter(b);
      case LESSTHAN:
        return a.isBefore(b);
      default:
        throw new InvalidRequestException(String.format("Unsupported prefix supplied: %s", prefix));
    }
  }

  /**
   * Process a {@link Set} of {@link ClaimType} entries and build an {@link EnumSet} of {@link
   * ClaimType} entries that meet the criteria of having claims data (derived from int bitmask) and
   * match claim(s) requested by caller.
   *
   * @param claimTypes {@link Set} set of {@link ClaimType} identifiers requested by client
   * @param val int bitmask denoting the claim types that have data
   * @return {@link EnumSet} of {@link ClaimType} types to process.
   */
  public static EnumSet<ClaimType> fetchClaimsAvailability(Set<ClaimType> claimTypes, int val) {
    EnumSet<ClaimType> availSet = EnumSet.noneOf(ClaimType.class);
    if ((val & QueryUtils.V_CARRIER_HAS_DATA) != 0 && claimTypes.contains(ClaimType.CARRIER)) {
      availSet.add(ClaimType.CARRIER);
    }
    if ((val & QueryUtils.V_DME_HAS_DATA) != 0 && claimTypes.contains(ClaimType.DME)) {
      availSet.add(ClaimType.DME);
    }
    if ((val & QueryUtils.V_PART_D_HAS_DATA) != 0 && claimTypes.contains(ClaimType.PDE)) {
      availSet.add(ClaimType.PDE);
    }
    if ((val & QueryUtils.V_INPATIENT_HAS_DATA) != 0 && claimTypes.contains(ClaimType.INPATIENT)) {
      availSet.add(ClaimType.INPATIENT);
    }
    if ((val & QueryUtils.V_OUTPATIENT_HAS_DATA) != 0
        && claimTypes.contains(ClaimType.OUTPATIENT)) {
      availSet.add(ClaimType.OUTPATIENT);
    }
    if ((val & QueryUtils.V_HOSPICE_HAS_DATA) != 0 && claimTypes.contains(ClaimType.HOSPICE)) {
      availSet.add(ClaimType.HOSPICE);
    }
    if ((val & QueryUtils.V_SNF_HAS_DATA) != 0 && claimTypes.contains(ClaimType.SNF)) {
      availSet.add(ClaimType.SNF);
    }
    if ((val & QueryUtils.V_HHA_HAS_DATA) != 0 && claimTypes.contains(ClaimType.HHA)) {
      availSet.add(ClaimType.HHA);
    }
    return availSet;
  }
}
