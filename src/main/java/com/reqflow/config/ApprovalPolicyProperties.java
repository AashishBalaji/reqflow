package com.reqflow.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.math.BigDecimal;

/** Binds reqflow.policy.* from application.yml. Externalizing these two
 * numbers means the auto-approval rule can be tightened or loosened by
 * ops/config change alone, with no code change and no redeploy of logic. */
@ConfigurationProperties(prefix = "reqflow.policy")
public class ApprovalPolicyProperties {

    /** Requisitions at or under this amount clear the "small enough"
     * auto-approval check. */
    private BigDecimal autoApprovalCeiling;

    /** Vendors with a risk score at or above this value can never be
     * auto-approved, regardless of amount. */
    private int vendorRiskCeiling;

    public BigDecimal getAutoApprovalCeiling() {
        return autoApprovalCeiling;
    }

    public void setAutoApprovalCeiling(BigDecimal autoApprovalCeiling) {
        this.autoApprovalCeiling = autoApprovalCeiling;
    }

    public int getVendorRiskCeiling() {
        return vendorRiskCeiling;
    }

    public void setVendorRiskCeiling(int vendorRiskCeiling) {
        this.vendorRiskCeiling = vendorRiskCeiling;
    }
}
