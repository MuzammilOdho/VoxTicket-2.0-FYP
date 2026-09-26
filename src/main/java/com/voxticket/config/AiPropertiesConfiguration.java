package com.voxticket.config;

import com.voxticket.agent.AiProvidersProperties;
import com.voxticket.agent.AiTiersProperties;
import com.voxticket.agent.ModelSelectorProperties;
import com.voxticket.rag.RagProperties;
import com.voxticket.safety.PromptGuardProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/** The single place all AI-related configuration classes are registered - explicit rather than relying on classpath scanning behavior. */
@Configuration
@EnableConfigurationProperties({AiProvidersProperties.class, AiTiersProperties.class, ModelSelectorProperties.class, PromptGuardProperties.class, RagProperties.class})
public class AiPropertiesConfiguration {
}
