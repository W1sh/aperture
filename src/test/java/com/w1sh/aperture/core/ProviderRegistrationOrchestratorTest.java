package com.w1sh.aperture.core;

import com.w1sh.aperture.core.condition.ProviderConditionEvaluator;
import com.w1sh.aperture.core.condition.ProviderConditionFactory;
import com.w1sh.aperture.example.service.impl.DuplicateCalculatorServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashSet;

import static org.mockito.Mockito.*;

class ProviderRegistrationOrchestratorTest {

    private ProviderRegistrationOrchestrator orchestrator;
    private DefaultProviderFactory factory;
    private DefaultProviderRegistry registry;

    @BeforeEach
    void setUp() {
        registry = spy(new DefaultProviderRegistry());
        factory = spy(new DefaultProviderFactory(registry));
        ProviderConditionFactory conditionFactory = new ProviderConditionFactory();
        ProviderConditionEvaluator conditionEvaluator = new ProviderConditionEvaluator(registry, Environment.empty());
        orchestrator = new ProviderRegistrationOrchestrator(factory, registry, conditionFactory, conditionEvaluator);
    }

    @Test
    void should_orchestrateClassRegistration_whenGivenClassIsRegistered() {
        orchestrator.register(DuplicateCalculatorServiceImpl.class);

        orchestrator.orchestrate();

        verify(factory, times(1)).newProvider(any());
        verify(registry, times(1)).register(any(), any(), any());
    }

}