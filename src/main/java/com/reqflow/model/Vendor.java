package com.reqflow.model;

import jakarta.persistence.*;
import jakarta.validation.constraints.*;

@Entity
@Table(name = "vendors")
public class Vendor {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @NotBlank
    private String name;

    @NotBlank
    private String category;

    /** 0 (no known risk) to 100 (maximum risk) -- a stand-in for whatever
     * real signals a procurement org would score a vendor on (payment
     * history, sanctions screening, past disputes). The triage policy
     * checks this against reqflow.policy.vendor-risk-ceiling. */
    @Min(0)
    @Max(100)
    private int riskScore;

    private boolean active = true;

    protected Vendor() { }

    public Vendor(String name, String category, int riskScore) {
        this.name = name;
        this.category = category;
        this.riskScore = riskScore;
    }

    public Long getId() { return id; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getCategory() { return category; }
    public void setCategory(String category) { this.category = category; }
    public int getRiskScore() { return riskScore; }
    public void setRiskScore(int riskScore) { this.riskScore = riskScore; }
    public boolean isActive() { return active; }
    public void setActive(boolean active) { this.active = active; }
}
