package com.capitalone.dashboard.evaluator;

import com.capitalone.dashboard.model.AuditException;
import com.capitalone.dashboard.model.CollectorItem;
import com.capitalone.dashboard.model.CollectorType;
import com.capitalone.dashboard.model.Dashboard;
import com.capitalone.dashboard.model.LibraryPolicyResult;
import com.capitalone.dashboard.model.LibraryPolicyThreatDisposition;
import com.capitalone.dashboard.model.LibraryPolicyThreatLevel;
import com.capitalone.dashboard.model.LibraryPolicyType;
import com.capitalone.dashboard.model.ScanState;
import com.capitalone.dashboard.repository.LibraryPolicyResultsRepository;
import com.capitalone.dashboard.request.ArtifactAuditRequest;
import com.capitalone.dashboard.response.LibraryPolicyAuditResponse;
import com.capitalone.dashboard.status.LibraryPolicyAuditStatus;
import org.apache.commons.collections.CollectionUtils;
import org.apache.commons.collections.MapUtils;
import org.apache.commons.collections.SetUtils;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

@Component
public class LibraryPolicyEvaluator extends Evaluator<LibraryPolicyAuditResponse> {

    private final LibraryPolicyResultsRepository libraryPolicyResultsRepository;

    @Autowired
    public LibraryPolicyEvaluator(LibraryPolicyResultsRepository libraryPolicyResultsRepository) {
        this.libraryPolicyResultsRepository = libraryPolicyResultsRepository;
    }

    @Override
    public Collection<LibraryPolicyAuditResponse> evaluate(Dashboard dashboard, long beginDate, long endDate, Map<?, ?> data) throws AuditException {

        List<CollectorItem> libraryPolicyItems = getCollectorItems(dashboard, CollectorType.LibraryPolicy);
        if (CollectionUtils.isEmpty(libraryPolicyItems)) {
            throw new AuditException("No library policy project configured", AuditException.NO_COLLECTOR_ITEM_CONFIGURED);
        }

        return libraryPolicyItems.stream().map(item -> evaluate(item, beginDate, endDate, null)).collect(Collectors.toList());
    }

    @Override
    public Collection<LibraryPolicyAuditResponse> evaluateNextGen(ArtifactAuditRequest artifactAuditRequest, Dashboard dashboard, long beginDate, long endDate, Map<?, ?> data) throws AuditException {
        return null;
    }


    @Override
    public LibraryPolicyAuditResponse evaluate(CollectorItem collectorItem, long beginDate, long endDate, Map<?, ?> data) {
        return getLibraryPolicyAuditResponse(collectorItem, beginDate, endDate);
    }

    /**
     * Reusable method for constructing the LibraryPolicyAuditResponse object
     *
     * @param collectorItem Collector item
     * @param beginDate     Begin Date
     * @param endDate       End Date
     * @return SecurityReviewAuditResponse
     */
    private LibraryPolicyAuditResponse getLibraryPolicyAuditResponse(CollectorItem collectorItem, long beginDate, long endDate) {
        List<LibraryPolicyResult> libraryPolicyResults = libraryPolicyResultsRepository.findByCollectorItemIdAndEvaluationTimestampIsBetweenOrderByTimestampDesc(collectorItem.getId(), beginDate - 1, endDate + 1);

        LibraryPolicyAuditResponse libraryPolicyAuditResponse = new LibraryPolicyAuditResponse();
        libraryPolicyAuditResponse.setAuditEntity(collectorItem.getOptions());

        if (CollectionUtils.isEmpty(libraryPolicyResults)) {
            libraryPolicyAuditResponse.addAuditStatus(LibraryPolicyAuditStatus.LIBRARY_POLICY_AUDIT_MISSING);
            return libraryPolicyAuditResponse;
        }
        libraryPolicyResults.sort(Comparator.comparing(LibraryPolicyResult::getEvaluationTimestamp).reversed());
        LibraryPolicyResult returnPolicyResult = libraryPolicyResults.get(0);
        libraryPolicyAuditResponse.setLibraryPolicyResult(returnPolicyResult);
        libraryPolicyAuditResponse.setLastExecutionTime(returnPolicyResult.getEvaluationTimestamp());

        if (StringUtils.equalsIgnoreCase(returnPolicyResult.getScanState(), ScanState.INVALID.getState())) {
            libraryPolicyAuditResponse.addAuditStatus(LibraryPolicyAuditStatus.LIBRARY_POLICY_INVALID_SCAN);
        } else if (StringUtils.equalsIgnoreCase(returnPolicyResult.getScanState(), ScanState.NO_FINDINGS.getState())) {
            libraryPolicyAuditResponse.addAuditStatus(LibraryPolicyAuditStatus.LIBRARY_POLICY_AUDIT_OK);
        } else {
            //threats by type
            Set<LibraryPolicyResult.Threat> securityThreats = !MapUtils.isEmpty(returnPolicyResult.getThreats()) ? returnPolicyResult.getThreats().get(LibraryPolicyType.Security) : SetUtils.EMPTY_SET;
            Set<LibraryPolicyResult.Threat> licenseThreats = !MapUtils.isEmpty(returnPolicyResult.getThreats()) ? returnPolicyResult.getThreats().get(LibraryPolicyType.License) : SetUtils.EMPTY_SET;


            boolean isOk = true;
            //License Threats
            if (CollectionUtils.isNotEmpty(licenseThreats) && licenseThreats.stream().anyMatch(threat -> Objects.equals(threat.getLevel(), LibraryPolicyThreatLevel.Critical) && hasViolations(threat,settings.getCriticalLicenseVulnerabilitiesAge()))) {
                libraryPolicyAuditResponse.addAuditStatus(LibraryPolicyAuditStatus.LIBRARY_POLICY_FOUND_CRITICAL_LICENSE);
                isOk = false;
            }

            if (CollectionUtils.isNotEmpty(licenseThreats) && licenseThreats.stream().anyMatch(threat -> Objects.equals(threat.getLevel(), LibraryPolicyThreatLevel.High) && hasViolations(threat,settings.getHighLicenseVulnerabilitiesAge()))) {
                libraryPolicyAuditResponse.addAuditStatus(LibraryPolicyAuditStatus.LIBRARY_POLICY_FOUND_HIGH_LICENSE);
                isOk = false;
            }

            //Security Threats
            if (CollectionUtils.isNotEmpty(securityThreats) && securityThreats.stream().anyMatch(threat -> Objects.equals(threat.getLevel(), LibraryPolicyThreatLevel.Critical) && hasViolations(threat,settings.getCriticalSecurityVulnerabilitiesAge()))) {
                libraryPolicyAuditResponse.addAuditStatus(LibraryPolicyAuditStatus.LIBRARY_POLICY_FOUND_CRITICAL_SECURITY);
                isOk = false;
            }

            if (CollectionUtils.isNotEmpty(securityThreats) && securityThreats.stream().anyMatch(threat -> Objects.equals(threat.getLevel(), LibraryPolicyThreatLevel.High) && hasViolations(threat,settings.getHighSecurityVulnerabilitiesAge()))) {
                libraryPolicyAuditResponse.addAuditStatus(LibraryPolicyAuditStatus.LIBRARY_POLICY_FOUND_HIGH_SECURITY);
                isOk = false;
            }

            if (isOk) {
                libraryPolicyAuditResponse.addAuditStatus(LibraryPolicyAuditStatus.LIBRARY_POLICY_AUDIT_OK);
            }
        }
        return libraryPolicyAuditResponse;
    }


    private boolean hasViolations(LibraryPolicyResult.Threat threat, int age) {
        if (MapUtils.isEmpty(threat.getDispositionCounts())) {
            return threat.getCount() > 0;
        }
        return threat.getDispositionCounts().containsKey(LibraryPolicyThreatDisposition.Open) &&
                (threat.getDispositionCounts().get(LibraryPolicyThreatDisposition.Open) > 0) && (threat.getMaxAge() > age);
    }
}
