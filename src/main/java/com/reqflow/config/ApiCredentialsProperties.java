package com.reqflow.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Binds reqflow.security.* from application.yml. Defaults are fine for
 * local dev/demo; override via environment variables
 * (REQFLOW_SECURITY_USERNAME / REQFLOW_SECURITY_PASSWORD -- Spring Boot's
 * relaxed binding maps those to reqflow.security.username/password) for
 * anything shared. */
@ConfigurationProperties(prefix = "reqflow.security")
public class ApiCredentialsProperties {

    private String username = "reviewer";
    private String password = "reqflow-dev";

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public String getPassword() {
        return password;
    }

    public void setPassword(String password) {
        this.password = password;
    }
}
