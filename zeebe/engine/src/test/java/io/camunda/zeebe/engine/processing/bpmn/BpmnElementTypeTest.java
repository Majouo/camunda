/*
 * Copyright Camunda Services GmbH and/or licensed to Camunda Services GmbH under
 * one or more contributor license agreements. See the NOTICE file distributed
 * with this work for additional information regarding copyright ownership.
 * Licensed under the Camunda License 1.0. You may not use this file
 * except in compliance with the Camunda License 1.0.
 */
package io.camunda.zeebe.engine.processing.bpmn;

import static org.assertj.core.api.Assertions.assertThat;

import io.camunda.zeebe.engine.util.EngineRule;
import io.camunda.zeebe.model.bpmn.Bpmn;
import io.camunda.zeebe.model.bpmn.BpmnModelInstance;
import io.camunda.zeebe.protocol.Protocol;
import io.camunda.zeebe.protocol.record.Record;
import io.camunda.zeebe.protocol.record.intent.MessageStartEventSubscriptionIntent;
import io.camunda.zeebe.protocol.record.value.BpmnElementType;
import io.camunda.zeebe.protocol.record.value.ProcessInstanceRecordValue;
import io.camunda.zeebe.test.util.Strings;
import io.camunda.zeebe.test.util.record.RecordingExporter;
import io.camunda.zeebe.test.util.record.RecordingExporterTestWatcher;
import java.time.Duration;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.ClassRule;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.junit.runners.Parameterized.Parameters;

@RunWith(Parameterized.class)
public final class BpmnElementTypeTest {

  @ClassRule public static final EngineRule ENGINE = EngineRule.singlePartition();
  private static final List<BpmnElementTypeScenario> SCENARIOS =
      Arrays.asList(
          new BpmnElementTypeScenario("Process", BpmnElementType.PROCESS) {
            @Override
            BpmnModelInstance modelInstance() {
              return Bpmn.createExecutableProcess(processId()).startEvent().done();
            }

            @Override
            String elementId() {
              return processId();
            }
          },
          new BpmnElementTypeScenario("Sub Process", BpmnElementType.SUB_PROCESS) {
            @Override
            BpmnModelInstance modelInstance() {
              return Bpmn.createExecutableProcess(processId())
                  .startEvent()
                  .subProcess(elementId())
                  .embeddedSubProcess()
                  .startEvent()
                  .subProcessDone()
                  .done();
            }
          },
          new BpmnElementTypeScenario("None Start Event", BpmnElementType.START_EVENT) {
            @Override
            public BpmnModelInstance modelInstance() {
              return Bpmn.createExecutableProcess(processId()).startEvent(elementId()).done();
            }
          },
          new BpmnElementTypeScenario("Message Start Event", BpmnElementType.START_EVENT) {
            @Override
            public BpmnModelInstance modelInstance() {
              return Bpmn.createExecutableProcess(processId())
                  .startEvent(elementId())
                  .message(messageName())
                  .done();
            }

            @Override
            public void test() {
              // wait for message subscription for the start event to be opened
              RecordingExporter.messageStartEventSubscriptionRecords(
                      MessageStartEventSubscriptionIntent.CREATED)
                  .getFirst();

              ENGINE.message().withName(messageName()).withCorrelationKey("id").publish();
            }
          },
          new BpmnElementTypeScenario("Timer Start Event", BpmnElementType.START_EVENT) {
            @Override
            BpmnModelInstance modelInstance() {
              return Bpmn.createExecutableProcess(processId())
                  .startEvent(elementId())
                  .timerWithCycle("R1/PT0.01S")
                  .done();
            }

            @Override
            void test() {
              ENGINE.increaseTime(Duration.ofMinutes(1));
            }
          },
          new BpmnElementTypeScenario(
              "Intermediate Message Catch Event", BpmnElementType.INTERMEDIATE_CATCH_EVENT) {
            @Override
            BpmnModelInstance modelInstance() {
              return Bpmn.createExecutableProcess(processId())
                  .startEvent()
                  .intermediateCatchEvent(elementId())
                  .message(b -> b.name(messageName()).zeebeCorrelationKeyExpression("id"))
                  .done();
            }

            @Override
            void test() {
              super.executeInstance(Collections.singletonMap("id", "test"));
              ENGINE.message().withName(messageName()).withCorrelationKey("test").publish();
            }
          },
          new BpmnElementTypeScenario(
              "Intermediate Timer Catch Event", BpmnElementType.INTERMEDIATE_CATCH_EVENT) {
            @Override
            BpmnModelInstance modelInstance() {
              return Bpmn.createExecutableProcess(processId())
                  .startEvent()
                  .intermediateCatchEvent(elementId())
                  .timerWithDuration("PT0.01S")
                  .done();
            }
          },
          new BpmnElementTypeScenario(
              "Intermediate Catch Event After Event Based Gateway",
              BpmnElementType.INTERMEDIATE_CATCH_EVENT) {
            @Override
            BpmnModelInstance modelInstance() {
              return Bpmn.createExecutableProcess(processId())
                  .startEvent()
                  .eventBasedGateway()
                  .intermediateCatchEvent(elementId())
                  .timerWithDuration("PT0.01S")
                  .endEvent()
                  .moveToLastGateway()
                  .intermediateCatchEvent()
                  .timerWithDuration("PT1H")
                  .endEvent()
                  .done();
            }
          },
          new BpmnElementTypeScenario("Message Boundary Event", BpmnElementType.BOUNDARY_EVENT) {
            @Override
            BpmnModelInstance modelInstance() {
              return Bpmn.createExecutableProcess(processId())
                  .startEvent()
                  .serviceTask("task", b -> b.zeebeJobType(taskType()))
                  .boundaryEvent(elementId())
                  .message(b -> b.name(messageName()).zeebeCorrelationKeyExpression("id"))
                  .endEvent()
                  .done();
            }

            @Override
            void test() {
              super.executeInstance(Collections.singletonMap("id", "test"));
              ENGINE.message().withName(messageName()).withCorrelationKey("test").publish();
            }
          },
          new BpmnElementTypeScenario("Timer Boundary Event", BpmnElementType.BOUNDARY_EVENT) {
            @Override
            BpmnModelInstance modelInstance() {
              return Bpmn.createExecutableProcess(processId())
                  .startEvent()
                  .serviceTask("task", b -> b.zeebeJobType(taskType()))
                  .boundaryEvent(elementId())
                  .timerWithDuration("PT0.01S")
                  .endEvent()
                  .done();
            }
          },
          new BpmnElementTypeScenario("End Event", BpmnElementType.END_EVENT) {
            @Override
            BpmnModelInstance modelInstance() {
              return Bpmn.createExecutableProcess(processId())
                  .startEvent()
                  .endEvent(elementId())
                  .done();
            }
          },
          new BpmnElementTypeScenario("Service Task", BpmnElementType.SERVICE_TASK) {
            @Override
            BpmnModelInstance modelInstance() {
              return Bpmn.createExecutableProcess(processId())
                  .startEvent()
                  .serviceTask(elementId(), b -> b.zeebeJobType(taskType()))
                  .done();
            }

            @Override
            void test() {
              final long processInstanceKey = super.executeInstance();
              ENGINE.job().ofInstance(processInstanceKey).withType(taskType()).complete();
            }
          },
          new BpmnElementTypeScenario("Business Rule Task", BpmnElementType.BUSINESS_RULE_TASK) {
            @Override
            BpmnModelInstance modelInstance() {
              return Bpmn.createExecutableProcess(processId())
                  .startEvent()
                  .businessRuleTask(elementId(), b -> b.zeebeJobType(taskType()))
                  .done();
            }

            @Override
            void test() {
              final long processInstanceKey = super.executeInstance();
              ENGINE.job().ofInstance(processInstanceKey).withType(taskType()).complete();
            }
          },
          new BpmnElementTypeScenario("Script Task", BpmnElementType.SCRIPT_TASK) {
            @Override
            BpmnModelInstance modelInstance() {
              return Bpmn.createExecutableProcess(processId())
                  .startEvent()
                  .scriptTask(elementId(), b -> b.zeebeJobType(taskType()))
                  .done();
            }

            @Override
            void test() {
              final long processInstanceKey = super.executeInstance();
              ENGINE.job().ofInstance(processInstanceKey).withType(taskType()).complete();
            }
          },
          new BpmnElementTypeScenario("Send Task", BpmnElementType.SEND_TASK) {
            @Override
            BpmnModelInstance modelInstance() {
              return Bpmn.createExecutableProcess(processId())
                  .startEvent()
                  .sendTask(elementId(), b -> b.zeebeJobType(taskType()))
                  .done();
            }

            @Override
            void test() {
              final long processInstanceKey = super.executeInstance();
              ENGINE.job().ofInstance(processInstanceKey).withType(taskType()).complete();
            }
          },
          new BpmnElementTypeScenario("User Task", BpmnElementType.USER_TASK) {
            @Override
            BpmnModelInstance modelInstance() {
              return Bpmn.createExecutableProcess(processId())
                  .startEvent()
                  .userTask(elementId())
                  .done();
            }

            @Override
            void test() {
              final long processInstanceKey = super.executeInstance();
              ENGINE
                  .job()
                  .ofInstance(processInstanceKey)
                  .withType(Protocol.USER_TASK_JOB_TYPE)
                  .complete();
            }
          },
          new BpmnElementTypeScenario("Receive Task", BpmnElementType.RECEIVE_TASK) {
            @Override
            BpmnModelInstance modelInstance() {
              return Bpmn.createExecutableProcess(processId())
                  .startEvent()
                  .receiveTask(elementId())
                  .message(b -> b.name(messageName()).zeebeCorrelationKeyExpression("id"))
                  .done();
            }

            @Override
            void test() {
              executeInstance(Collections.singletonMap("id", "test"));
              ENGINE.message().withName(messageName()).withCorrelationKey("test").publish();
            }
          },
          new BpmnElementTypeScenario("Exclusive Gateway", BpmnElementType.EXCLUSIVE_GATEWAY) {
            @Override
            BpmnModelInstance modelInstance() {
              return Bpmn.createExecutableProcess(processId())
                  .startEvent()
                  .exclusiveGateway(elementId())
                  .defaultFlow()
                  .endEvent()
                  .done();
            }
          },
          new BpmnElementTypeScenario(
              "Sequence Flow After Exclusive Gateway", BpmnElementType.SEQUENCE_FLOW) {
            @Override
            BpmnModelInstance modelInstance() {
              return Bpmn.createExecutableProcess(processId())
                  .startEvent()
                  .exclusiveGateway()
                  .conditionExpression("5 > 1")
                  .sequenceFlowId(elementId())
                  .endEvent()
                  .moveToLastExclusiveGateway()
                  .defaultFlow()
                  .endEvent()
                  .done();
            }
          },
          new BpmnElementTypeScenario("Event Based Gateway", BpmnElementType.EVENT_BASED_GATEWAY) {
            @Override
            BpmnModelInstance modelInstance() {
              return Bpmn.createExecutableProcess(processId())
                  .startEvent()
                  .eventBasedGateway(elementId())
                  .intermediateCatchEvent()
                  .message(b -> b.name(messageName()).zeebeCorrelationKeyExpression("id"))
                  .moveToLastGateway()
                  .intermediateCatchEvent()
                  .timerWithDuration("PT0.01S")
                  .done();
            }

            @Override
            void test() {
              executeInstance(Collections.singletonMap("id", "test"));
              ENGINE.message().withName(messageName()).withCorrelationKey("test").publish();
            }
          },
          new BpmnElementTypeScenario("Parallel Gateway", BpmnElementType.PARALLEL_GATEWAY) {
            @Override
            BpmnModelInstance modelInstance() {
              return Bpmn.createExecutableProcess(processId())
                  .startEvent()
                  .parallelGateway(elementId())
                  .endEvent()
                  .done();
            }
          },
          new BpmnElementTypeScenario("Sequence Flow", BpmnElementType.SEQUENCE_FLOW) {
            @Override
            BpmnModelInstance modelInstance() {
              return Bpmn.createExecutableProcess(processId())
                  .startEvent()
                  .sequenceFlowId(elementId())
                  .endEvent()
                  .done();
            }
          },
          new BpmnElementTypeScenario("Event Subprocess", BpmnElementType.EVENT_SUB_PROCESS) {
            @Override
            BpmnModelInstance modelInstance() {
              return Bpmn.createExecutableProcess(processId())
                  .eventSubProcess(
                      elementId(),
                      eventSubProcess ->
                          eventSubProcess.startEvent().timerWithDuration("PT0S").endEvent())
                  .startEvent()
                  .serviceTask("task", t -> t.zeebeJobType("test"))
                  .endEvent()
                  .done();
            }
          },
          new BpmnElementTypeScenario(
              "Intermediate throw event", BpmnElementType.INTERMEDIATE_THROW_EVENT) {
            @Override
            BpmnModelInstance modelInstance() {
              return Bpmn.createExecutableProcess(processId())
                  .startEvent()
                  .intermediateThrowEvent(elementId())
                  .endEvent()
                  .done();
            }
          },
          new BpmnElementTypeScenario("Manual Task", BpmnElementType.MANUAL_TASK) {
            @Override
            BpmnModelInstance modelInstance() {
              return Bpmn.createExecutableProcess(processId())
                  .startEvent()
                  .manualTask(elementId())
                  .endEvent()
                  .done();
            }
          },
          new BpmnElementTypeScenario("Undefined Task", BpmnElementType.TASK) {
            @Override
            BpmnModelInstance modelInstance() {
              return Bpmn.createExecutableProcess(processId())
                  .startEvent()
                  .task(elementId())
                  .endEvent()
                  .done();
            }
          },
          new BpmnElementTypeScenario("Ad-hoc subprocess", BpmnElementType.AD_HOC_SUB_PROCESS) {
            @Override
            BpmnModelInstance modelInstance() {
              return Bpmn.createExecutableProcess(processId())
                  .startEvent()
                  .adHocSubProcess(elementId(), adHocSubProcess -> adHocSubProcess.task("task"))
                  .endEvent()
                  .done();
            }
          });

  @Rule
  public final RecordingExporterTestWatcher recordingExporterTestWatcher =
      new RecordingExporterTestWatcher();

  private final BpmnElementTypeScenario scenario;

  public BpmnElementTypeTest(final BpmnElementTypeScenario scenario) {
    this.scenario = scenario;
  }

  @Parameters(name = "{0}")
  public static Collection<Object[]> scenarios() {
    return SCENARIOS.stream().map(s -> new Object[] {s}).collect(Collectors.toList());
  }

  @Test
  public void test() {
    // given
    ENGINE.deployment().withXmlResource(scenario.modelInstance()).deploy();

    // when
    scenario.test();

    // then
    final List<Record<ProcessInstanceRecordValue>> records =
        RecordingExporter.processInstanceRecords()
            .withBpmnProcessId(scenario.processId())
            .limitToProcessInstanceCompleted()
            .withElementId(scenario.elementId())
            .asList();

    assertThat(records)
        .extracting(r -> r.getValue().getBpmnElementType())
        .isNotEmpty()
        .containsOnly(scenario.elementType());
  }

  abstract static class BpmnElementTypeScenario {
    private final String name;
    private final BpmnElementType elementType;

    private final String processId = Strings.newRandomValidBpmnId();
    private final String elementId = Strings.newRandomValidBpmnId();
    private final String taskType = Strings.newRandomValidBpmnId();
    private final String messageName = Strings.newRandomValidBpmnId();

    BpmnElementTypeScenario(final String name, final BpmnElementType elementType) {
      this.name = name;
      this.elementType = elementType;
    }

    String name() {
      return name;
    }

    abstract BpmnModelInstance modelInstance();

    String processId() {
      return processId;
    }

    String elementId() {
      return elementId;
    }

    String taskType() {
      return taskType;
    }

    String messageName() {
      return messageName;
    }

    BpmnElementType elementType() {
      return elementType;
    }

    void test() {
      executeInstance();
    }

    long executeInstance() {
      return ENGINE.processInstance().ofBpmnProcessId(processId()).create();
    }

    long executeInstance(final Map<String, String> variables) {
      final String json =
          "{ "
              + variables.entrySet().stream()
                  .map(e -> String.format("\"%s\":\"%s\"", e.getKey(), e.getValue()))
                  .collect(Collectors.joining(","))
              + " }";
      return ENGINE.processInstance().ofBpmnProcessId(processId()).withVariables(json).create();
    }

    @Override
    public String toString() {
      return name();
    }
  }
}
