package com.reqflow.dto;

import com.reqflow.model.Vendor;

/** What the API actually returns for a Vendor -- decoupled from the JPA
 * entity so a persistence-only field (like BudgetLine's @Version
 * equivalent, if Vendor ever gets one) can't leak into the response
 * just because it exists on the entity. */
public record VendorResponse(Long id, String name, String category, int riskScore, boolean active) {

    public static VendorResponse from(Vendor vendor) {
        return new VendorResponse(vendor.getId(), vendor.getName(), vendor.getCategory(),
                vendor.getRiskScore(), vendor.isActive());
    }
}
