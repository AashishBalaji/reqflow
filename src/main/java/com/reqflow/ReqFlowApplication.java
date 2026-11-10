package com.reqflow;

import com.reqflow.config.ApiCredentialsProperties;
import com.reqflow.config.ApprovalPolicyProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication
@EnableConfigurationProperties({ApprovalPolicyProperties.class, ApiCredentialsProperties.class})
public class ReqFlowApplication {
    public static void main(String[] args) {
        SpringApplication.run(ReqFlowApplication.class, args);
    }
}
