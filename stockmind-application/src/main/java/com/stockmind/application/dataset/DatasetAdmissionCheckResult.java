package com.stockmind.application.dataset;

import java.util.List;

public record DatasetAdmissionCheckResult(boolean admitted, List<String> failedChecks) {
    public DatasetAdmissionCheckResult {
        failedChecks = List.copyOf(failedChecks);
    }
}
